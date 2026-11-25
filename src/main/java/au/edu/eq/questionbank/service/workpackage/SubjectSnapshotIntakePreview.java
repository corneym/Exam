package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableAliasRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectPortableRecordReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository.ImportedAuthority;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.SnapshotEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectSnapshotFreshness.Outcome;

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

	static Long findExistingSubject(Connection connection, Fingerprint fingerprint) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(fingerprint, "fingerprint");
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT s.id,
				       s.subject_name,
				       EXISTS (
				           SELECT 1
				           FROM imported_subject_authorities authority
				           WHERE authority.subject_local_id = s.id
				             AND authority.origin_installation_id = ?
				             AND authority.origin_subject_portable_id = ?
				       ) AS matching_origin
				FROM subjects s
				WHERE s.subject_name = ? COLLATE NOCASE
				   OR EXISTS (
				       SELECT 1
				       FROM imported_subject_authorities authority
				       WHERE authority.subject_local_id = s.id
				         AND authority.origin_installation_id = ?
				         AND authority.origin_subject_portable_id = ?
				   )
				ORDER BY s.id
				""")) {
			statement.setString(1, fingerprint.originInstallationId().toString());
			statement.setString(2, fingerprint.subjectId().toString());
			statement.setString(3, fingerprint.subjectName());
			statement.setString(4, fingerprint.originInstallationId().toString());
			statement.setString(5, fingerprint.subjectId().toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				long localId = result.getLong("id");
				String existingName = result.getString("subject_name");
				boolean matchingOrigin = result.getBoolean("matching_origin");

				// A name collision must never override an
				// established originating Subject identity.
				if (result.next()) {
					throw new SQLException("The incoming Subject name and originating " + "identity match different "
							+ "destination Subjects.");
				}

				// Subject names are immutable. An established
				// originating identity cannot acquire another name,
				// including a case-only spelling change.
				if (matchingOrigin && !existingName.equals(fingerprint.subjectName())) {
					throw new SQLException(
							"Subject names are immutable. " + "The originating Subject is registered as " + existingName
									+ " but the incoming snapshot identifies it as " + fingerprint.subjectName() + ".");
				}
				return localId;
			}
		}
	}

	/**
	 * Validates a Subject snapshot and identifies its destination state.
	 *
	 * <p>
	 * The supplied archive is copied into private temporary storage. Package,
	 * domain graph and asset-index validation use that copy. No destination
	 * database or managed asset mutations are performed.
	 *
	 * <p>
	 * Existing Subjects are resolved by name and previously recorded originating
	 * Subject identity. Subject names are immutable. An established originating
	 * identity with a different incoming Subject name is rejected rather than
	 * treated as a new Subject or eligible refresh.
	 *
	 * @param incomingArchive complete Subject snapshot archive
	 * @return immutable inspection and record-count summary
	 * @throws IOException          if the archive is unreadable, malformed or
	 *                              invalid
	 * @throws SQLException         if destination inspection fails, the destination
	 *                              is ambiguous or the originating Subject name
	 *                              differs
	 * @throws NullPointerException if incomingArchive is null
	 */
	public Inspection inspect(Path incomingArchive) throws IOException, SQLException {
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Path source = incomingArchive.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| !source.getFileName().toString().endsWith(".examwork")) {
			throw new PackageFormatException("Choose an ordinary .examwork Subject snapshot file.");
		}
		if (Files.size(source) > 512L * 1024 * 1024) {
			throw new PackageFormatException("Subject archive exceeds maximum compressed size.");
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
				throw new PackageFormatException("Subject identity differs between " + "the manifest and records.");
			}
			UUID localInstallationId = new SqlitePortableIdentityRepository(database).installationId();
			if (localInstallationId.equals(manifest.originInstallationId())) {
				throw new PackageFormatException(
						"This Subject snapshot originated " + "from the current installation.");
			}
			State state;
			Long existingSubjectId;
			try (Connection connection = database.openConnection()) {
				connection.setAutoCommit(false);
				try {

					// Resolve package history and destination identity
					// from the same consistent database read transaction.
					var recorded = new SqliteSubjectImportLedger(database).find(connection, manifest.packageId());
					existingSubjectId = findExistingSubject(connection, fingerprint);
					if (recorded.isPresent()) {
						var previous = recorded.orElseThrow();
						if (!previous.archiveSha256().equals(fingerprint.archiveSha256())) {
							throw new PackageFormatException(
									"The package UUID was previously " + "recorded with different bytes.");
						}
						if (previous.outcome() != PackageOutcome.APPLIED) {
							throw new PackageFormatException(
									"This package was previously rejected " + "and cannot be replayed.");
						}
						state = State.ALREADY_IMPORTED;
					} else {
						state = existingSubjectId == null ? State.NEW_SUBJECT : State.EXISTING_SUBJECT;
					}
					connection.commit();
				} catch (SQLException | IOException | RuntimeException failure) {
					try {
						connection.rollback();
					} catch (SQLException rollbackFailure) {
						failure.addSuppressed(rollbackFailure);
					}
					throw failure;
				}
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

	/**
	 * Constructs a complete read-only refresh comparison for an existing imported
	 * Subject.
	 *
	 * <p>
	 * The incoming archive is inspected from a private copy. Destination records,
	 * authority evidence and identity mappings are read from one consistent SQLite
	 * snapshot. Managed assets are independently collected and verified.
	 *
	 * <p>
	 * This operation neither approves nor publishes any changes.
	 *
	 * @param incomingArchive incoming authoritative Subject snapshot
	 * @param layout          destination managed-data layout
	 * @return immutable refresh comparison
	 * @throws IOException          if archive or asset validation fails
	 * @throws SQLException         if destination inspection fails
	 * @throws NullPointerException if either argument is null
	 */
	public SubjectRefreshPlan previewRefresh(Path incomingArchive, ManagedDataLayout layout)
			throws IOException, SQLException {
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Objects.requireNonNull(layout, "layout");
		Path source = incomingArchive.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| !source.getFileName().toString().endsWith(".examwork")) {
			throw new PackageFormatException("Choose an ordinary .examwork Subject snapshot file.");
		}
		if (Files.size(source) > 512L * 1024 * 1024) {
			throw new PackageFormatException("Subject archive exceeds maximum compressed size.");
		}
		Path stagingDirectory = Files.createTempDirectory("examwork-refresh-preview-");
		try {
			Path privateArchive = stagingDirectory.resolve("incoming.examwork");
			Files.copy(source, privateArchive, LinkOption.NOFOLLOW_LINKS);
			Path databaseSnapshot = stagingDirectory.resolve("destination.db");
			database.createConsistentSnapshot(databaseSnapshot);
			SqliteDatabase snapshotDatabase = SqliteDatabase.readOnly(databaseSnapshot);
			var snapshotPreview = new SubjectSnapshotIntakePreview(snapshotDatabase);
			Inspection inspection = snapshotPreview.inspect(privateArchive);
			var incomingEvidence = new SubjectImportSnapshotFingerprint().inspectEvidence(privateArchive);
			try (Connection connection = snapshotDatabase.openConnection()) {
				connection.setAutoCommit(false);
				RefreshEvidence evidence = snapshotPreview.requireRefreshEvidence(connection, inspection,
						incomingEvidence);
				long subjectId = evidence.authority().subjectLocalId();
				var graph = new SqliteSubjectGraphReader(databaseSnapshot).read(subjectId);
				List<ExchangeRecord> current = new SqliteSubjectPortableRecordReader(databaseSnapshot).read(graph);
				var collector = new SubjectAssetCollector(snapshotDatabase, layout, stagingDirectory);
				ExamWorkAssetIndex currentAssets = collector.collect(graph).index();
				var mappings = new SubjectRefreshAliasResolver(snapshotDatabase).resolve(connection,
						evidence.incoming().fingerprint().originInstallationId(), current, inspection.records());
				var normaliser = new SubjectRefreshIdentityNormaliser();
				List<ExchangeRecord> incoming = normaliser.normalise(inspection.records(), mappings);
				ExamWorkAssetIndex incomingAssets = normaliser.normaliseAssets(inspection.assets(), mappings);
				var classifier = new SubjectRefreshChangeClassifier();
				List<ExchangeRecord> comparisonCurrent = includeExistingSharedProviders(connection, snapshotDatabase,
						current, incoming);
				List<ExchangeRecord> comparisonIncoming = preserveSharedProviders(comparisonCurrent, incoming);
				var recordChanges = classifier.compareRecords(comparisonCurrent, comparisonIncoming);
				var assetChanges = classifier.compareAssets(currentAssets, incomingAssets);
				String destinationDigest = fingerprintDestination(comparisonCurrent, currentAssets);
				connection.commit();
				return new SubjectRefreshPlan(evidence, destinationDigest, mappings, recordChanges, assetChanges);
			}
		} finally {
			deleteRefreshStaging(stagingDirectory);
		}
	}

	/**
	 * Establishes whether an inspected snapshot belongs to an existing imported
	 * Subject owned by the same originating authority.
	 *
	 * <p>
	 * Locally authoritative Subjects, different originating installations,
	 * different originating Subject identities and previously recorded packages are
	 * not eligible for a new refresh operation.
	 *
	 * <p>
	 * This is a read-only eligibility check. It neither approves a refresh nor
	 * establishes that the incoming snapshot is newer than the last accepted
	 * snapshot. Archive freshness, destination content comparison, explicit
	 * approval and transactional revalidation remain necessary.
	 *
	 * @param inspection previously validated incoming snapshot
	 * @return current imported authority for the eligible destination
	 * @throws SQLException           if destination evidence cannot be read
	 * @throws PackageFormatException if refresh eligibility cannot be established
	 * @throws NullPointerException   if inspection is null
	 */
	public ImportedAuthority requireRefreshAuthority(Inspection inspection)
			throws SQLException, PackageFormatException {
		Objects.requireNonNull(inspection, "inspection");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				ImportedAuthority authority = requireRefreshAuthority(connection, inspection);
				connection.commit();
				return authority;
			} catch (SQLException | PackageFormatException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	/**
	 * Revalidates the incoming archive and compares its originating timestamp with
	 * the latest accepted snapshot of the same imported Subject.
	 *
	 * <p>
	 * Provenance, package history and originating evidence are read in one
	 * consistent destination transaction. This read-only operation does not approve
	 * or publish a refresh. Missing legacy freshness evidence fails closed.
	 *
	 * @param inspection      previously validated incoming snapshot
	 * @param incomingArchive exact archive selected for refresh
	 * @return eligible incoming and latest accepted originating evidence
	 * @throws IOException          if archive validation or freshness eligibility
	 *                              fails
	 * @throws SQLException         if destination evidence cannot be read
	 * @throws NullPointerException if either argument is null
	 */
	public RefreshEvidence requireRefreshEvidence(Inspection inspection, Path incomingArchive)
			throws IOException, SQLException {
		Objects.requireNonNull(inspection, "inspection");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		SnapshotEvidence incoming = new SubjectImportSnapshotFingerprint().inspectEvidence(incomingArchive);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				RefreshEvidence result = requireRefreshEvidence(connection, inspection, incoming);
				connection.commit();
				return result;
			} catch (SQLException | IOException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	ImportedAuthority requireRefreshAuthority(Connection connection, Inspection inspection)
			throws SQLException, PackageFormatException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(inspection, "inspection");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Refresh eligibility requires a consistent " + "caller-owned transaction.");
		}
		if (inspection.state() != State.EXISTING_SUBJECT || inspection.existingSubjectId() == null) {
			throw new PackageFormatException(
					"Refresh requires an existing imported Subject " + "and a previously unapplied package.");
		}
		Fingerprint incoming = inspection.fingerprint();
		Long currentSubjectId = findExistingSubject(connection, incoming);
		if (!Objects.equals(currentSubjectId, inspection.existingSubjectId())) {
			throw new PackageFormatException("The destination Subject changed after inspection.");
		}
		SqliteImportedSubjectAuthorityRepository authorities = new SqliteImportedSubjectAuthorityRepository(database);
		ImportedAuthority authority = authorities.find(connection, currentSubjectId)
				.orElseThrow(() -> new PackageFormatException(
						"The existing Subject is locally authoritative " + "and cannot be overwritten by a snapshot."));

		// Both originating identities must match. A common name
		// or a common originating installation is not sufficient.
		if (!authority.originInstallationId().equals(incoming.originInstallationId())) {
			throw new PackageFormatException(
					"The incoming snapshot belongs to a different " + "originating installation.");
		}
		if (!authority.originSubjectPortableId().equals(incoming.subjectId())) {
			throw new PackageFormatException(
					"The incoming snapshot belongs to a different " + "originating Subject identity.");
		}
		SqliteSubjectImportLedger ledger = new SqliteSubjectImportLedger(database);
		var previousIncoming = ledger.find(connection, incoming.packageId());
		if (previousIncoming.isPresent()) {
			if (!previousIncoming.orElseThrow().archiveSha256().equals(incoming.archiveSha256())) {
				throw new PackageFormatException("The package UUID was previously recorded " + "with different bytes.");
			}
			throw new PackageFormatException("The incoming package already has a recorded outcome.");
		}

		// Imported authority must be backed by a consistent terminal
		// package entry. An authority row alone is insufficient.
		var latest = ledger.find(connection, authority.latestPackageId()).orElseThrow(
				() -> new PackageFormatException("The latest imported Subject package evidence is missing."));
		if (latest.outcome() != PackageOutcome.APPLIED
				|| !latest.originInstallationId().equals(authority.originInstallationId())
				|| !latest.subjectPortableId().equals(authority.originSubjectPortableId())
				|| !latest.archiveSha256().equals(authority.archiveSha256())) {
			throw new PackageFormatException(
					"The recorded Subject authority and latest " + "applied package evidence are inconsistent.");
		}
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(database);
		UUID canonicalSubjectId = aliases
				.findAlias(connection, incoming.originInstallationId(), EntityType.SUBJECT, incoming.subjectId())
				.orElseThrow(
						() -> new PackageFormatException("The originating Subject has no approved canonical alias."));
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		var canonicalSubject = identities.findByPortableId(connection, EntityType.SUBJECT, canonicalSubjectId)
				.orElseThrow(() -> new PackageFormatException("The imported canonical Subject identity is missing."));
		if (canonicalSubject.localId() != currentSubjectId.longValue()) {
			throw new PackageFormatException(
					"The originating Subject alias points to " + "a different destination Subject.");
		}
		return authority;
	}

	RefreshEvidence requireRefreshEvidence(Connection connection, Inspection inspection, SnapshotEvidence incoming)
			throws SQLException, PackageFormatException {
		Objects.requireNonNull(incoming, "incoming");
		Objects.requireNonNull(inspection, "inspection");
		if (!inspection.fingerprint().equals(incoming.fingerprint())) {
			throw new PackageFormatException("Incoming Subject package differs from the inspected archive");
		}

		// Read the authority and its accepted originating evidence through the
		// same transaction so a concurrent refresh cannot mix two package states.
		ImportedAuthority authority = requireRefreshAuthority(connection, inspection);
		var recorded = new SqliteSubjectImportLedger(database)
				.findOriginatingSnapshotEvidence(connection, authority.latestPackageId())
				.orElseThrow(() -> new PackageFormatException(
						"The latest accepted snapshot has no recorded originating freshness evidence. "
								+ "A refresh cannot proceed without verified evidence of that snapshot."));
		SnapshotEvidence accepted = new SnapshotEvidence(new Fingerprint(authority.latestPackageId(),
				authority.originInstallationId(), authority.originSubjectPortableId(),
				incoming.fingerprint().subjectName(), recorded.contentSha256(), authority.archiveSha256()),
				recorded.createdAt());
		Outcome outcome = new SubjectSnapshotFreshness().requireNewer(accepted, incoming);
		return new RefreshEvidence(authority, accepted, incoming, outcome);
	}

	private void deleteRefreshStaging(Path directory) throws IOException {
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private String fingerprintDestination(List<ExchangeRecord> records, ExamWorkAssetIndex assets) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
		var codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> orderedRecords = records.stream()
				.sorted(Comparator.comparing(ExchangeRecord::recordType).thenComparing(ExchangeRecord::recordId))
				.toList();
		try (DigestOutputStream output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
			codec.writeRecords(orderedRecords, output);

			// Separate the two structured sections so their combined
			// digest cannot depend on ambiguous section boundaries.
			output.write(0);
			codec.writeAssetIndex(assets, output);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private List<ExchangeRecord> includeExistingSharedProviders(Connection connection, SqliteDatabase snapshotDatabase,
			List<ExchangeRecord> current, List<ExchangeRecord> incoming) throws SQLException, PackageFormatException {
		List<ExchangeRecord> comparison = new ArrayList<>(current);
		Set<UUID> representedProviders = current.stream().filter(record -> "EXAM_PROVIDER".equals(record.recordType()))
				.map(ExchangeRecord::recordId).collect(Collectors.toCollection(HashSet::new));
		var identities = new SqlitePortableIdentityRepository(snapshotDatabase);
		var content = new SqlitePortableContentRepository(snapshotDatabase);
		for (ExchangeRecord record : incoming) {
			if (!"EXAM_PROVIDER".equals(record.recordType()) || representedProviders.contains(record.recordId())) {
				continue;
			}
			var identity = identities.findByPortableId(connection, EntityType.EXAM_PROVIDER, record.recordId());
			if (identity.isPresent()) {

				// The provider exists globally but was not previously
				// referenced by this particular Subject.
				ExchangeRecord existing = content.snapshot(EntityType.EXAM_PROVIDER, identity.orElseThrow().localId())
						.record();
				if (!existing.recordId().equals(record.recordId())) {
					throw new PackageFormatException("Shared provider identity changed " + "during refresh inspection");
				}
				comparison.add(existing);
				representedProviders.add(existing.recordId());
			}
		}
		return List.copyOf(comparison);
	}

	private List<ExchangeRecord> preserveSharedProviders(List<ExchangeRecord> current, List<ExchangeRecord> incoming)
			throws PackageFormatException {
		Map<UUID, ExchangeRecord> existingProviders = new HashMap<>();
		for (ExchangeRecord record : current) {
			if ("EXAM_PROVIDER".equals(record.recordType())) {
				existingProviders.put(record.recordId(), record);
			}
		}
		List<ExchangeRecord> comparison = new ArrayList<>();
		Set<UUID> representedProviders = new HashSet<>();
		for (ExchangeRecord record : incoming) {
			if (!"EXAM_PROVIDER".equals(record.recordType())) {
				comparison.add(record);
				continue;
			}
			representedProviders.add(record.recordId());
			ExchangeRecord existing = existingProviders.get(record.recordId());
			if (existing == null) {
				comparison.add(record);
				continue;
			}
			if (!existing.values().equals(record.values())) {
				throw new PackageFormatException(
						"Incoming snapshot would modify " + "shared examination provider: " + record.recordId());
			}

			// A shared provider retains its destination revision.
			// Importing one Subject cannot revise shared reference data.
			comparison.add(existing);
		}
		for (ExchangeRecord existing : existingProviders.values()) {
			if (!representedProviders.contains(existing.recordId())) {

				// Absence from one Subject's new graph does not
				// authorise deletion of a globally shared provider.
				comparison.add(existing);
			}
		}
		return List.copyOf(comparison);
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
	 * Read-only evidence of a chronologically eligible refresh candidate.
	 *
	 * @param authority imported Subject authority observed during inspection
	 * @param accepted  latest accepted originating snapshot evidence
	 * @param incoming  verified incoming snapshot evidence
	 * @param outcome   chronological eligibility classification
	 */
	public record RefreshEvidence(ImportedAuthority authority, SnapshotEvidence accepted, SnapshotEvidence incoming,
			Outcome outcome) {

		/**
		 * Validates complete eligible refresh evidence.
		 *
		 * @throws NullPointerException     if any component is null
		 * @throws IllegalArgumentException if the outcome is not an eligible newer
		 *                                  snapshot
		 */
		public RefreshEvidence {
			Objects.requireNonNull(authority, "authority");
			Objects.requireNonNull(accepted, "accepted");
			Objects.requireNonNull(incoming, "incoming");
			Objects.requireNonNull(outcome, "outcome");
			if (outcome != Outcome.NEWER_CHANGED && outcome != Outcome.NEWER_UNCHANGED) {
				throw new IllegalArgumentException("Refresh evidence requires an eligible newer snapshot");
			}
		}
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
