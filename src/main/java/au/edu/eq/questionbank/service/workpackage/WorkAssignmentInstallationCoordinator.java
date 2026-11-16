package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository.LocalIds;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentArchiveStager.StagedAssignment;

/**
 * Installs coordinator-issued whole-Exam assignments into a contributor
 * installation.
 *
 * <p>
 * Installation uses a private validated archive, verified staged assets,
 * non-overwriting filesystem publication and one SQLite transaction for graph
 * insertion and immutable session registration.
 *
 * <p>
 * A shared data-root lock serialises both Subject and assignment publication and
 * recovery. A committed assignment session is the durable completion marker
 * used by the publication journal.
 *
 * <p>
 * An installed assignment is selected automatically. Re-importing the same
 * assignment and package does not reapply the original baseline over subsequent
 * contributor work.
 *
 * <p>
 * This service does not implement Dashboard controls or ordinary capture writer
 * restrictions. Those must use the persisted assignment selection and mutation
 * guard.
 */
public final class WorkAssignmentInstallationCoordinator {

	private final ApplicationConfig config;
	private final SqliteDatabase database;
	private final WorkAssignmentArchiveStager stager;
	private final WorkAssignmentPublicationJournal journal;
	private final SqliteContributorAssignmentSessionRepository sessions;

	/**
	 * Creates an installer for the configured contributor data root.
	 *
	 * @param config application filesystem configuration
	 * @throws NullPointerException if config is null
	 */
	public WorkAssignmentInstallationCoordinator(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		database = new SqliteDatabase(config.databasePath());
		stager = new WorkAssignmentArchiveStager(config);
		journal = new WorkAssignmentPublicationJournal(config);
		sessions = new SqliteContributorAssignmentSessionRepository(database);
	}

	/**
	 * Installs and activates a whole-Exam assignment.
	 *
	 * <p>
	 * Interrupted Subject imports and assignments are recovered before a package is
	 * processed. A previously installed identical assignment is selected without
	 * overwriting its existing contributor work.
	 *
	 * <p>
	 * Successful installation commits the complete graph, immutable baseline and
	 * session before publication staging is discarded. A cleanup failure after
	 * commit is reported separately rather than misrepresented as a failed
	 * installation.
	 *
	 * <p>
	 * The caller must ensure other application processes do not modify managed
	 * source files outside this installation protocol.
	 *
	 * @param incomingArchive validated coordinator .examwork package
	 * @return installation result, including replay and cleanup status
	 * @throws IOException          if the archive, publication or recovery fails
	 * @throws SQLException         if database installation or registration fails
	 * @throws NullPointerException if incomingArchive is null
	 */
	public Result install(Path incomingArchive) throws IOException, SQLException {
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Path source = incomingArchive.toAbsolutePath().normalize();
		Path root = config.dataRoot().resolve(".assignment-install-staging");
		if (source.startsWith(root) || source.startsWith(config.dataRoot().resolve(".subject-import-staging"))) {
			throw new IOException("Cannot import directly from private assignment staging");
		}
		try (var lock = ManagedAssetPublicationLock.acquire(config.dataRoot())) {
			stagingRoot();
			ManagedAssetPublicationRecovery.recoverPending(config, lock);
			var inspection = new WorkAssignmentPackageInspector().inspect(source, ApplicationVersion.current(),
					SqliteDatabase.latestSchemaVersion());

			// The coordinator's own installation must never consume
			// its issued package as contributor work. Use persisted
			// installation identity, not filesystem path comparisons.
			UUID localInstallationId = new SqlitePortableIdentityRepository(database).installationId();
			if (localInstallationId.equals(inspection.manifest().originInstallationId())) {
				throw new IOException("Cannot install a contributor assignment into "
						+ "its originating coordinator installation. " + "Use a separate contributor data directory.");
			}
			UUID assignmentId = inspection.manifest().assignmentId();
			UUID packageId = inspection.manifest().packageId();
			var existing = sessions.find(assignmentId);
			if (existing.isPresent()) {
				var installed = existing.orElseThrow();
				requireReplayMatch(installed.packageId(), installed.archiveSha256(), inspection);

				// Re-import is also an explicit request to enter
				// the previously stored assignment.
				sessions.select(assignmentId);
				return new Result(assignmentId, packageId, true, false);
			}
			return installNew(source, inspection, lock);
		}
	}

	private void deactivateForInstallation(Connection connection) throws SQLException {

		// Only the installer temporarily clears selection, under
		// the caller-owned write transaction. Other connections
		// continue to see the previously committed selection
		// until this transaction commits.
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE contributor_assignment_selection
				SET assignment_id = NULL,
				    updated_at = ?
				WHERE singleton_id = 1
				""")) {
			statement.setString(1, Instant.now().toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Contributor selection singleton is missing");
			}
		}
	}

	private Result installNew(Path source, WorkAssignmentPackageInspector.Inspection original,
			ManagedAssetPublicationLock lock)
			throws IOException, SQLException {
		StagedAssignment staged = stager.stage(source);
		UUID packageId = original.manifest().packageId();
		UUID assignmentId = original.manifest().assignmentId();
		boolean committed = false;
		boolean prepared = false;
		try {
			if (!staged.inspection().archiveSha256().equals(original.archiveSha256())
					|| !staged.inspection().manifest().equals(original.manifest())) {
				throw new IOException("Incoming assignment changed during installation");
			}
			var extraction = new WorkAssignmentAssetExtractor(config).extract(staged);
			journal.prepare(extraction);
			prepared = true;
			try (Connection connection = database.openConnection()) {
				connection.setAutoCommit(false);
				try {
					reserveWriter(connection);

					// Temporarily suspend the previous assignment
					// within this transaction only. A rollback
					// restores its selection automatically.
					deactivateForInstallation(connection);
					journal.publishFiles(packageId);
					Map<Key, Long> installed = new SubjectImportGraphWriter(database).installAssignment(connection,
							extraction);
					LocalIds localIds = localIds(installed, staged.inspection());
					sessions.registerInstalled(connection, staged.inspection(), localIds);
					requireForeignKeys(connection);
					journal.verifyPublished(packageId);

					// Activation commits atomically with the
					// complete graph and immutable session.
					selectInstalledInTransaction(connection, assignmentId);
					connection.commit();
					committed = true;
				} catch (SQLException | IOException | RuntimeException failure) {
					try {
						connection.rollback();
					} catch (SQLException rollbackFailure) {
						failure.addSuppressed(rollbackFailure);
					}
					throw failure;
				}
			}
			boolean cleanupPending = false;
			try {
				journal.recover(packageId, lock);
			} catch (SQLException | IOException cleanupFailure) {

				// The database and installed session already committed.
				// Retain staging for a later recovery attempt.
				cleanupPending = true;
			}
			return new Result(assignmentId, packageId, false, cleanupPending);
		} catch (SQLException | IOException | RuntimeException failure) {
			if (!committed) {
				try {
					Path journalPath = staged.directory().resolve("publication.properties");
					if (prepared || Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
						journal.recover(packageId, lock);
					} else {
						stager.discard(staged);
					}
				} catch (SQLException | IOException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	private LocalIds localIds(Map<Key, Long> installed, WorkAssignmentPackageInspector.Inspection inspection)
			throws SQLException {
		UUID subject = inspection.manifest().subjectId();
		UUID exam = inspection.manifest().scope().examIds().getFirst();
		var values = inspection.context().values();
		UUID currentSyllabus = UUID.fromString(values.path("current_syllabus_id").asText());
		Long legacySyllabus = null;
		if (values.path("legacy_capture_enabled").asBoolean()) {
			UUID legacyPortableId = UUID.fromString(values.path("legacy_syllabus_id").asText());
			legacySyllabus = requiredLocalId(installed, EntityType.SYLLABUS_VERSION, legacyPortableId);
		}
		return new LocalIds(requiredLocalId(installed, EntityType.SUBJECT, subject),
				requiredLocalId(installed, EntityType.EXAM, exam),
				requiredLocalId(installed, EntityType.SYLLABUS_VERSION, currentSyllabus), legacySyllabus);
	}

	private long requiredLocalId(Map<Key, Long> installed, EntityType type, UUID portableId) throws SQLException {
		Key key = SubjectReconciliationReview.recordKey(type.name(), portableId);
		Long result = installed.get(key);
		if (result == null || result < 1) {
			throw new SQLException("Missing installed assignment identity: " + type + ":" + portableId);
		}
		return result;
	}

	private void requireForeignKeys(Connection connection) throws SQLException {
		try (var statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (result.next()) {
				throw new SQLException("Assignment installation would commit " + "broken foreign-key relationships");
			}
		}
	}

	private void requireReplayMatch(UUID installedPackageId, String installedArchiveSha256,
			WorkAssignmentPackageInspector.Inspection incoming) throws IOException {
		if (!installedPackageId.equals(incoming.manifest().packageId())
				|| !installedArchiveSha256.equals(incoming.archiveSha256())) {
			throw new IOException("Assignment identity is already installed " + "from a different package");
		}
	}

	private void reserveWriter(Connection connection) throws SQLException {

		// Reserve SQLite's writer before publishing files so that
		// another SQLite writer cannot interleave graph changes.
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE installation_identity
				SET installation_id = installation_id
				WHERE singleton_id = 1
				""")) {
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Cannot reserve contributor installation writer");
			}
		}
	}

	private void selectInstalledInTransaction(Connection connection, UUID assignmentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE contributor_assignment_selection
				SET assignment_id = ?,
				    updated_at = ?
				WHERE singleton_id = 1
				""")) {
			statement.setString(1, assignmentId.toString());
			statement.setString(2, Instant.now().toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Could not activate installed contributor assignment");
			}
		}
	}

	private Path stagingRoot() throws IOException {
		Path dataRoot = config.dataRoot();
		if (!Files.isDirectory(dataRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contributor data root is not an ordinary directory");
		}
		Path root = dataRoot.resolve(".assignment-install-staging");
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(root);
		}
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contributor assignment staging root is unsafe");
		}
		return root;
	}

	/**
	 * Outcome of a contributor assignment installation attempt.
	 *
	 * @param assignmentId   installed immutable assignment identity
	 * @param packageId      installed archive identity
	 * @param replay         true when an identical assignment was already installed
	 * @param cleanupPending true when the assignment committed but its private
	 *                       recovery evidence could not yet be removed
	 */
	public record Result(UUID assignmentId, UUID packageId, boolean replay, boolean cleanupPending) {

		/**
		 * Validates the required installation result identities.
		 *
		 * @throws NullPointerException if an identity is null
		 */
		public Result {
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(packageId, "packageId");
		}
	}
}
