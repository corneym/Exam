
package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;

/**
 * Inspects one complete Subject snapshot against the destination database.
 *
 * <p>
 * The caller supplies a single archive. A private copy is validated before its
 * records and assets are inspected. This service does not create Subjects,
 * change imported data, publish assets or mark a Subject as authoritative.
 *
 * <p>
 * An existing Subject name is never interpreted as permission to merge or
 * replace its records. A recorded identical package is recognised as a replay.
 */
public final class SubjectSnapshotIntakePreview {

	private final SqliteDatabase database;

	/**
	 * Creates a Subject snapshot inspector.
	 *
	 * @param database initialised destination database
	 * @throws NullPointerException if database is null
	 */
	public SubjectSnapshotIntakePreview(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Validates a Subject snapshot and identifies its destination state.
	 *
	 * <p>
	 * The supplied archive is copied into private temporary storage. Package,
	 * domain graph and asset-index validation use that copy. No destination
	 * database or managed asset mutations are performed.
	 *
	 * @param incomingArchive complete Subject snapshot archive
	 * @return immutable inspection and record-count summary
	 * @throws IOException          if the archive is unreadable, malformed or
	 *                              invalid
	 * @throws SQLException         if destination inspection fails
	 * @throws NullPointerException if incomingArchive is null
	 */
	public Inspection inspect(Path incomingArchive) throws IOException, SQLException {
		Objects.requireNonNull(incomingArchive, "incomingArchive");

		Path source = incomingArchive.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| !source.getFileName().toString().endsWith(".examwork")) {
			throw new PackageFormatException("Choose an ordinary .examwork Subject snapshot file.");
		}

		Path directory = Files.createTempDirectory("examwork-subject-inspection-");
		Path privateArchive = directory.resolve("incoming.examwork");

		try {
			Files.copy(source, privateArchive, LinkOption.NOFOLLOW_LINKS);

			ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(privateArchive,
					ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());

			if (manifest.packageType() != ExamWorkPackageType.SUBJECT_SNAPSHOT
					|| manifest.scope().level() != ScopeLevel.SUBJECT) {
				throw new PackageFormatException("The selected package is not a complete Subject snapshot.");
			}

			Fingerprint fingerprint = new SubjectImportSnapshotFingerprint().inspect(privateArchive);

			List<ExchangeRecord> records;
			ExamWorkAssetIndex assets;

			try (ZipFile archive = ZipFile.builder().setPath(privateArchive).get()) {
				ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
				records = readRecords(archive, manifest, codec);
				assets = readAssets(archive, codec);
			}

			assets.requireMatchingManifest(manifest);
			PortableDomainRecordValidator.validate(manifest.subjectId(), records);

			List<ExchangeRecord> subjects = records.stream().filter(record -> record.recordType().equals("SUBJECT"))
					.toList();

			if (subjects.size() != 1 || !subjects.getFirst().recordId().equals(manifest.subjectId())
					|| !manifest.subjectName().equals(subjects.getFirst().values().path("subject_name").asText())) {
				throw new PackageFormatException("Subject identity differs between the manifest and records.");
			}

			UUID localInstallationId = new SqlitePortableIdentityRepository(database).installationId();

			if (localInstallationId.equals(manifest.originInstallationId())) {
				throw new PackageFormatException("This Subject snapshot originated from the current installation.");
			}

			var recorded = new SqliteSubjectImportLedger(database).find(manifest.packageId());

			State state;
			Long existingSubjectId = findExistingSubject(manifest.subjectName());

			if (recorded.isPresent()) {
				var previous = recorded.orElseThrow();

				if (!previous.archiveSha256().equals(fingerprint.archiveSha256())) {
					throw new PackageFormatException("The package UUID was previously recorded with different bytes.");
				}
				if (previous.outcome() != PackageOutcome.APPLIED) {
					throw new PackageFormatException("This package was previously rejected and cannot be replayed.");
				}

				state = State.ALREADY_IMPORTED;
			} else {
				state = existingSubjectId == null ? State.NEW_SUBJECT : State.EXISTING_SUBJECT;
			}

			Map<String, Long> counts = records.stream()
					.collect(Collectors.groupingBy(ExchangeRecord::recordType, TreeMap::new, Collectors.counting()));

			return new Inspection(fingerprint, state, existingSubjectId, counts, records, assets);

		} finally {
			try {
				Files.deleteIfExists(privateArchive);
			} finally {
				Files.deleteIfExists(directory);
			}
		}
	}

	private Long findExistingSubject(String name) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id
						FROM subjects
						WHERE subject_name = ? COLLATE NOCASE
						""")) {

			statement.setString(1, name);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}

				long localId = result.getLong(1);
				if (result.next()) {
					throw new SQLException("Multiple existing Subjects match the incoming name.");
				}
				return localId;
			}
		}
	}

	private ExamWorkAssetIndex readAssets(ZipFile archive, ExamWorkJsonlCodec codec) throws IOException {

		ZipArchiveEntry entry = archive.getEntry(ExamWorkAssetIndex.INDEX_PATH);
		if (entry == null) {
			throw new PackageFormatException("Subject snapshot has no logical asset index.");
		}

		try (InputStream input = archive.getInputStream(entry)) {
			return codec.readAssetIndex(input);
		}
	}

	private List<ExchangeRecord> readRecords(ZipFile archive, ExamWorkManifest manifest, ExamWorkJsonlCodec codec)
			throws IOException {

		List<ExchangeRecord> records = new ArrayList<>();

		for (ExamWorkManifest.Entry entry : manifest.entries()) {
			if (entry.role() != EntryRole.RECORDS) {
				continue;
			}

			ZipArchiveEntry member = archive.getEntry(entry.path());
			if (member == null) {
				throw new PackageFormatException("Subject snapshot is missing records: " + entry.path());
			}

			try (InputStream input = archive.getInputStream(member)) {
				records.addAll(codec.readRecords(input));
			}
		}

		if (records.isEmpty()) {
			throw new PackageFormatException("Subject snapshot contains no domain records.");
		}

		return List.copyOf(records);
	}

	/**
	 * Classification of the incoming Subject package.
	 */
	public enum State {
		/** No Subject with this name exists in the destination. */
		NEW_SUBJECT,

		/** The destination already contains a Subject with this name. */
		EXISTING_SUBJECT,

		/** The identical archive is already recorded as applied. */
		ALREADY_IMPORTED
	}

	/**
	 * Immutable result of complete, read-only archive inspection.
	 *
	 * @param fingerprint       verified package identity and checksums
	 * @param state             destination classification
	 * @param existingSubjectId existing local Subject ID, or null
	 * @param recordCounts      number of records of each portable type
	 * @param records           verified complete domain records
	 * @param assets            verified logical asset index
	 */
	public record Inspection(Fingerprint fingerprint, State state, Long existingSubjectId,
			Map<String, Long> recordCounts, List<ExchangeRecord> records, ExamWorkAssetIndex assets) {

		/**
		 * Validates and defensively copies the inspection data.
		 */
		public Inspection {
			Objects.requireNonNull(fingerprint, "fingerprint");
			Objects.requireNonNull(state, "state");
			recordCounts = Map.copyOf(Objects.requireNonNull(recordCounts, "recordCounts"));
			records = List.copyOf(Objects.requireNonNull(records, "records"));
			Objects.requireNonNull(assets, "assets");

			if (existingSubjectId != null && existingSubjectId < 1) {
				throw new IllegalArgumentException("Existing Subject ID must be positive.");
			}
			if (state == State.EXISTING_SUBJECT && existingSubjectId == null) {
				throw new IllegalArgumentException("Existing Subject state requires its local ID.");
			}
			if (state == State.NEW_SUBJECT && existingSubjectId != null) {
				throw new IllegalArgumentException("New Subject must not have a destination ID.");
			}
		}

		/**
		 * Returns the number of records of the specified portable type.
		 *
		 * @param recordType portable entity type
		 * @return number of matching records
		 */
		public long count(String recordType) {
			Objects.requireNonNull(recordType, "recordType");
			return recordCounts.getOrDefault(recordType, 0L);
		}
	}
}
