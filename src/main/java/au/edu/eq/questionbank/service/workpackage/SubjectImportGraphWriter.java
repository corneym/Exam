package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableAliasRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.Identity;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorIdentityBinder;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Action;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Operation;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.AssetKind;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedAsset;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Applies approved portable Subject records in one caller-owned transaction.
 *
 * <p>
 * All table identifiers are selected from a fixed internal registry. The
 * archive supplies data values, never SQL table or column identifiers. Database
 * generated canonical UUIDs remain immutable; every inserted origin identity
 * receives an approved alias to its generated canonical UUID.
 *
 * <p>
 * Record relationships are rebased to local integer IDs, the shared Answer File
 * and Shared Context graphs are preserved, and nested ordered children are
 * inserted only after their referenced parents and images. All changes are
 * rolled back if the caller's transaction fails.
 */
public final class SubjectImportGraphWriter {

	private static final Map<String, EntityType> REFERENCES = Map.ofEntries(Map.entry("subject_id", EntityType.SUBJECT),
			Map.entry("syllabus_version_id", EntityType.SYLLABUS_VERSION),
			Map.entry("parent_id", EntityType.CURRICULUM_NODE), Map.entry("provider_id", EntityType.EXAM_PROVIDER),
			Map.entry("source_document_id", EntityType.SOURCE_DOCUMENT), Map.entry("exam_id", EntityType.EXAM),
			Map.entry("booklet_id", EntityType.EXAM_BOOKLET),
			Map.entry("classification_node_id", EntityType.CURRICULUM_NODE),
			Map.entry("source_question_id", EntityType.SOURCE_QUESTION),
			Map.entry("shared_context_id", EntityType.SHARED_QUESTION_CONTEXT),
			Map.entry("pending_mcq_shared_context_id", EntityType.SHARED_QUESTION_CONTEXT),
			Map.entry("answer_file_id", EntityType.ANSWER_FILE), Map.entry("question_id", EntityType.QUESTION),
			Map.entry("answer_id", EntityType.ANSWER), Map.entry("source_node_id", EntityType.CURRICULUM_NODE),
			Map.entry("target_node_id", EntityType.CURRICULUM_NODE),
			Map.entry("target_syllabus_version_id", EntityType.SYLLABUS_VERSION),
			Map.entry("current_curriculum_node_id", EntityType.CURRICULUM_NODE),
			Map.entry("image_id", EntityType.QUESTION_IMAGE));
	private static final List<EntityType> ORDER = List.of(EntityType.SUBJECT, EntityType.EXAM_PROVIDER,
			EntityType.SYLLABUS_VERSION, EntityType.CURRICULUM_NODE, EntityType.SOURCE_DOCUMENT, EntityType.EXAM,
			EntityType.ANSWER_FILE, EntityType.EXAM_BOOKLET, EntityType.SOURCE_QUESTION,
			EntityType.SHARED_QUESTION_CONTEXT, EntityType.QUESTION, EntityType.QUESTION_IMAGE, EntityType.ANSWER,
			EntityType.CURRICULUM_MAPPING);
	private static final Map<EntityType, Table> TABLES = Map.ofEntries(
			Map.entry(EntityType.SUBJECT, new Table("subjects")),
			Map.entry(EntityType.SYLLABUS_VERSION, new Table("syllabus_versions")),
			Map.entry(EntityType.CURRICULUM_NODE,
					new Table("curriculum_nodes", new Child("curriculum_mapping_reviews", "source_node_id"))),
			Map.entry(EntityType.EXAM_PROVIDER, new Table("exam_providers")),
			Map.entry(EntityType.SOURCE_DOCUMENT, new Table("source_documents")),
			Map.entry(EntityType.EXAM, new Table("exams")),
			Map.entry(EntityType.EXAM_BOOKLET,
					new Table("exam_booklets", new Child("booklet_applicability_reviews", "booklet_id"))),
			Map.entry(EntityType.QUESTION, new Table("questions", new Child("question_regions", "question_id"),
					new Child("question_images", "question_id"), new Child("question_content_parts", "question_id"),
					new Child("question_output_exclusions", "question_id"))),
			Map.entry(EntityType.ANSWER_FILE, new Table("answer_files")),
			Map.entry(EntityType.ANSWER, new Table("answers", new Child("answer_regions", "answer_id"))),
			Map.entry(EntityType.SOURCE_QUESTION, new Table("source_questions")),
			Map.entry(EntityType.SHARED_QUESTION_CONTEXT,
					new Table("shared_question_contexts",
							new Child("shared_question_context_regions", "shared_context_id"),
							new Child("shared_question_context_images", "shared_context_id"),
							new Child("shared_question_context_content_parts", "shared_context_id"))),
			Map.entry(EntityType.CURRICULUM_MAPPING, new Table("curriculum_mappings")),
			Map.entry(EntityType.QUESTION_IMAGE, new Table("question_images")));
	private final SqlitePortableAliasRepository aliases;
	private final SqlitePortableIdentityRepository identities;
	private final SqliteDatabase database;

	/**
	 * Constructs a graph writer for the active master database.
	 *
	 * @param database authoritative SQLite database
	 */
	public SubjectImportGraphWriter(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
		aliases = new SqlitePortableAliasRepository(database);
		identities = new SqlitePortableIdentityRepository(database);
	}

	/**
	 * Inserts an approved Subject graph in the caller's open transaction.
	 *
	 * @param connection   caller-owned transaction; commit and rollback are
	 *                     external
	 * @param plan         approved import plan
	 * @param staged       verified staged package with all approved binary content
	 * @param decoded      independently validated decoded Subject graph
	 * @param approvalNote non-blank coordinator approval note for the import
	 * @return canonical identities resolved or inserted for accepted records
	 * @throws SQLException if persistence, relationships or aliasing fail
	 * @throws IOException  if staged binary content or paths cannot be verified
	 */
	public Map<Key, UUID> apply(Connection connection, SubjectImportApprovalPlan plan, StagedPackage staged,
			DecodedPackage decoded, String approvalNote) throws SQLException, IOException {
		Objects.requireNonNull(connection, "connection");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Graph writer needs caller-owned transaction");
		}
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(decoded, "decoded");
		PortableDomainRecordValidator.validate(decoded.manifest().subjectId(), decoded.records());
		Objects.requireNonNull(approvalNote, "approvalNote");
		if (approvalNote.isBlank()) {
			throw new IllegalArgumentException("Subject import requires a coordinator approval note");
		}
		if (!decoded.manifest().packageId().equals(staged.packageId())) {
			throw new SQLException("Package graph identity changed after decoding");
		}
		Map<Key, Action> actions = new HashMap<>();
		for (Action action : plan.actions()) {
			actions.put(action.key(), action);
		}
		Map<Key, Long> localIds = new HashMap<>();
		Map<Key, UUID> canonicalIds = new LinkedHashMap<>();
		Map<EntityType, List<ExchangeRecord>> ordered = new EnumMap<>(EntityType.class);
		for (ExchangeRecord record : decoded.records()) {
			ordered.computeIfAbsent(EntityType.valueOf(record.recordType()), _ -> new ArrayList<>()).add(record);
		}
		for (List<ExchangeRecord> group : ordered.values()) {
			group.sort(Comparator.comparing(record -> record.recordId().toString()));
		}
		UUID origin = decoded.manifest().originInstallationId();

		// Resolve approved existing records before dependent insertions.
		for (EntityType type : ORDER) {
			for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
				Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
				Action action = actions.get(key);
				if (action.operation() != Operation.LINK_ALIAS) {
					continue;
				}
				UUID canonical = Objects.requireNonNull(action.masterId(), "masterId");
				var identity = identities.findByPortableId(connection, type, canonical)
						.orElseThrow(() -> new SQLException("Missing approved master identity: " + key));
				localIds.put(key, identity.localId());
				canonicalIds.put(key, canonical);
			}
		}

		// Insert base entities in foreign-key dependency order.
		for (EntityType type : ORDER) {
			for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
				Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
				Action action = actions.get(key);
				if (action.operation() != Operation.INSERT_RECORD) {
					continue;
				}
				if (aliases.findAlias(connection, origin, type, record.recordId()).isPresent()) {
					throw new SQLException("Approved new record already has an origin alias: " + key);
				}
				if (identities.findByPortableId(connection, type, record.recordId()).isPresent()) {
					throw new SQLException("Incoming UUID already belongs to a master record: " + key);
				}
				long local = insertBase(connection, type, record, decoded, staged, localIds);
				var identity = identities.findByLocalId(connection, type, local)
						.orElseThrow(() -> new SQLException("Missing generated canonical identity: " + key));
				localIds.put(key, local);
				canonicalIds.put(key, identity.portableId());
			}
		}

		// Repair nullable references after all entity rows exist.
		for (EntityType type : ORDER) {
			for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
				Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
				if (actions.get(key).operation() != Operation.INSERT_RECORD) {
					continue;
				}
				repairDeferredReferences(connection, type, localIds.get(key), record.values(), localIds);
			}
		}

		// Insert ordinary child rows before review confirmations.
		// Invalidation triggers correctly revoke booklet reviews when
		// Question, Answer or Shared Context content is changed.
		for (EntityType type : ORDER) {
			if (type == EntityType.EXAM_BOOKLET) {
				continue;
			}
			for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
				Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
				if (actions.get(key).operation() != Operation.INSERT_RECORD) {
					continue;
				}
				insertChildren(connection, type, localIds.get(key), record, decoded, staged, localIds);
			}
		}

		// Restore explicit booklet applicability attestations LAST.
		// This occurs only after all relevant Question, Answer and
		// Shared Context writes, and remains in the same transaction.
		for (ExchangeRecord record : ordered.getOrDefault(EntityType.EXAM_BOOKLET, List.of())) {
			Key key = SubjectReconciliationReview.recordKey(EntityType.EXAM_BOOKLET.name(), record.recordId());
			if (actions.get(key).operation() != Operation.INSERT_RECORD) {
				continue;
			}
			insertChildren(connection, EntityType.EXAM_BOOKLET, localIds.get(key), record, decoded, staged, localIds);
		}

		// Record origin aliases only after the complete graph has been
		// successfully inserted and all relationships are resolved.
		for (Map.Entry<Key, UUID> entry : canonicalIds.entrySet()) {
			Key key = entry.getKey();
			EntityType type = EntityType.valueOf(key.value().substring(0, key.value().indexOf(':')));
			UUID original = UUID.fromString(key.value().substring(key.value().indexOf(':') + 1));
			Action action = actions.get(key);

			// Ordinary approvals may have no individual justification.
			// Use the overall coordinator approval note in that case.
			// Explicit conflict justifications are retained when supplied.
			String justification = action.justification();
			String aliasNote = justification.isBlank() ? approvalNote : justification;
			aliases.approveAlias(connection, origin, type, original, entry.getValue(), clippedNote(aliasNote));
		}
		return Map.copyOf(canonicalIds);
	}

	/**
	 * Loads one staged package and validates domain semantics and plan coverage
	 * before any mutation. Integrity-valid records that violate domain ownership,
	 * classification or content invariants are rejected with a package validation
	 * error.
	 *
	 * @param staged immutable staged incoming archive and assets
	 * @param plan   approved non-destructive import plan
	 * @return complete decoded portable records and asset index
	 * @throws IOException if the archive, record set or approval mismatches
	 */
	public DecodedPackage decode(StagedPackage staged, SubjectImportApprovalPlan plan) throws IOException {
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(plan, "plan");
		if (!staged.packageId().equals(plan.reviewedIncoming().packageId())
				|| !staged.approvalSha256().equals(plan.approvalSha256())) {
			throw new IOException("Staging does not match approved Subject import plan");
		}
		plan.requireFreshIncoming(staged.archive());
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(staged.archive(),
				ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
		List<ExchangeRecord> records = new ArrayList<>();
		ExamWorkAssetIndex index;
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		try (ZipFile zip = ZipFile.builder().setPath(staged.archive()).get()) {
			ZipArchiveEntry indexEntry = zip.getEntry(ExamWorkAssetIndex.INDEX_PATH);
			try (InputStream input = zip.getInputStream(indexEntry)) {
				index = codec.readAssetIndex(input);
			}
			index.requireMatchingManifest(manifest);
			for (var entry : manifest.entries()) {
				if (entry.role() != EntryRole.RECORDS) {
					continue;
				}
				ZipArchiveEntry recordEntry = zip.getEntry(entry.path());
				if (recordEntry == null) {
					throw new IOException("Missing declared package records: " + entry.path());
				}
				try (InputStream input = zip.getInputStream(recordEntry)) {
					records.addAll(codec.readRecords(input));
				}
			}
		}
		Map<Key, Action> actions = new HashMap<>();
		for (Action action : plan.actions()) {
			if (actions.putIfAbsent(action.key(), action) != null) {
				throw new IOException("Duplicate approved plan key: " + action.key());
			}
		}
		PortableDomainRecordValidator.validate(manifest.subjectId(), records);
		Set<Key> recordsSeen = new HashSet<>();
		for (ExchangeRecord record : records) {
			EntityType type;
			try {
				type = EntityType.valueOf(record.recordType());
			} catch (IllegalArgumentException exception) {
				throw new IOException("Unsupported imported entity type: " + record.recordType(), exception);
			}
			if (!TABLES.containsKey(type)) {
				throw new IOException("Entity type has no supported insertion mapping");
			}
			Key key = SubjectReconciliationReview.recordKey(record.recordType(), record.recordId());
			if (!recordsSeen.add(key) || !actions.containsKey(key)) {
				throw new IOException("Unreviewed or duplicate imported record: " + key);
			}
		}
		for (Action action : plan.actions()) {
			if ("RECORD".equals(action.key().kind()) && !recordsSeen.contains(action.key())) {
				throw new IOException("Approved plan refers to a missing record: " + action.key());
			}
		}
		if (!manifest.subjectName().equals(plan.reviewedIncoming().subjectName())) {
			throw new IOException("Incoming archive belongs to an unapproved Subject");
		}
		return new DecodedPackage(manifest, List.copyOf(records), index);
	}

	/**
	 * Decodes and verifies the exact approved incoming refresh graph.
	 *
	 * <p>
	 * Originating records are validated before translation into the approved
	 * comparison identity namespace. Existing shared provider revisions are
	 * preserved.
	 *
	 * <p>
	 * The manifest describes the original archive, while records and logical asset
	 * owners use comparison identities. No database mutation occurs.
	 *
	 * @param staged   complete verified refresh staging
	 * @param approval exact approved refresh comparison
	 * @return decoded and normalised graph and asset inventory
	 * @throws IOException          if archive, record, identity or asset evidence
	 *                              differs from approval
	 * @throws NullPointerException if a required argument is null
	 */
	public DecodedPackage decodeRefresh(StagedPackage staged, SubjectRefreshPlan.Approval approval) throws IOException {
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(approval, "approval");
		SubjectRefreshPlan plan = approval.plan();
		var expected = plan.evidence().incoming().fingerprint();
		if (!staged.packageId().equals(expected.packageId())
				|| !staged.approvalSha256().equals(approval.approvalSha256())) {
			throw new IOException("Staged graph belongs to a different refresh approval");
		}
		approval.requireFreshIncoming(staged.archive());
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(staged.archive(),
				ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
		if (!manifest.packageId().equals(expected.packageId()) || !manifest.subjectId().equals(expected.subjectId())
				|| !manifest.originInstallationId().equals(expected.originInstallationId())
				|| !manifest.subjectName().equals(expected.subjectName())) {
			throw new IOException("Decoded refresh manifest differs from approval");
		}
		List<ExchangeRecord> records = new ArrayList<>();
		ExamWorkAssetIndex index;
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		try (ZipFile zip = ZipFile.builder().setPath(staged.archive()).get()) {
			ZipArchiveEntry indexEntry = zip.getEntry(ExamWorkAssetIndex.INDEX_PATH);
			if (indexEntry == null) {
				throw new PackageFormatException("Subject refresh has no asset index");
			}
			try (InputStream input = zip.getInputStream(indexEntry)) {
				index = codec.readAssetIndex(input);
			}
			index.requireMatchingManifest(manifest);
			for (var entry : manifest.entries()) {
				if (entry.role() != EntryRole.RECORDS) {
					continue;
				}
				ZipArchiveEntry recordEntry = zip.getEntry(entry.path());
				if (recordEntry == null) {
					throw new IOException("Missing declared refresh records: " + entry.path());
				}
				try (InputStream input = zip.getInputStream(recordEntry)) {
					records.addAll(codec.readRecords(input));
				}
			}
		}

		// Validate originating domain semantics before translating identities.
		PortableDomainRecordValidator.validate(manifest.subjectId(), records);
		SubjectRefreshIdentityNormaliser normaliser = new SubjectRefreshIdentityNormaliser();
		List<ExchangeRecord> normalised = normaliser.normalise(records, plan.identities());
		ExamWorkAssetIndex normalisedAssets = normaliser.normaliseAssets(index, plan.identities());
		List<ExchangeRecord> approvedRecords = requireApprovedRefreshRecords(plan, normalised);
		requireVerifiedRefreshAssets(plan, staged, normalisedAssets);
		return new DecodedPackage(manifest, approvedRecords, normalisedAssets);
	}

	/**
	 * Installs or refreshes a validated whole-Exam assignment graph in the caller's
	 * uncommitted SQLite transaction.
	 *
	 * <p>
	 * Ordinary installation reuses only matching existing records. A subsequent
	 * assignment may refresh validated Exam-owned records and reconcile previously
	 * submitted contributor identities. Shared dependencies must remain compatible.
	 *
	 * <p>
	 * The complete installed graph must match the issued baseline. Managed file
	 * publication, session registration, activation and transaction completion
	 * remain the caller's responsibility.
	 *
	 * @param connection caller-owned installation transaction
	 * @param extraction verified private archive and extracted assets
	 * @return immutable mapping from issued portable records to local IDs
	 * @throws SQLException          if reconciliation or persistence fails
	 * @throws IOException           if package validation or asset verification
	 *                               fails
	 * @throws IllegalStateException if the connection uses autocommit
	 * @throws NullPointerException  if a required argument is null
	 */
	public Map<Key, Long> installAssignment(Connection connection, WorkAssignmentAssetExtractor.Extraction extraction)
			throws SQLException, IOException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(extraction, "extraction");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Assignment graph installation requires a transaction");
		}
		var verified = new WorkAssignmentPackageInspector().inspect(extraction.staged().archive(),
				ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
		if (!verified.archiveSha256().equals(extraction.staged().inspection().archiveSha256())
				|| !verified.manifest().equals(extraction.staged().inspection().manifest())) {
			throw new IOException("Assignment archive changed after private staging");
		}
		StagedPackage staged = assignmentStaging(verified, extraction);
		DecodedPackage decoded = new DecodedPackage(verified.manifest(), verified.records(), verified.assets());
		Map<EntityType, List<ExchangeRecord>> ordered = new EnumMap<>(EntityType.class);
		for (ExchangeRecord record : verified.records()) {
			EntityType type = EntityType.valueOf(record.recordType());
			if (!TABLES.containsKey(type)) {
				throw new SQLException("Unsupported assignment entity type: " + type);
			}
			ordered.computeIfAbsent(type, _ -> new ArrayList<>()).add(record);
		}
		for (List<ExchangeRecord> group : ordered.values()) {
			group.sort(Comparator.comparing(record -> record.recordId().toString()));
		}
		Savepoint checkpoint = connection.setSavepoint();
		try {
			Map<Key, Long> localIds = new LinkedHashMap<>();
			Set<Key> inserted = new HashSet<>();
			Set<Key> refreshed = new HashSet<>();
			Set<Key> baseUpdated = new HashSet<>();
			SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);

			// Establish refresh authority before modifying any
			// previously installed domain records or identities.
			AssignmentRefreshPlan refresh = AssignmentRefreshPlan.prepare(connection, database, verified);
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			reconcileAssignmentIdentities(binder, refresh, verified.records());

			// Resolve both unchanged authoritative identities and
			// the newly reconciled canonical contributor identities.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					var existing = identities.findByPortableId(connection, type, record.recordId());
					if (existing.isEmpty()) {
						continue;
					}
					long localId = existing.orElseThrow().localId();
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (refresh.canRefresh(key, localId)) {
						refreshed.add(key);
					} else {
						requireExistingAssignmentRecord(connection, content, type, localId, record, verified.assets());
					}
					localIds.put(key, localId);
				}
			}

			// Insert genuinely new records in dependency order.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (localIds.containsKey(key)) {
						continue;
					}
					long localId = insertBase(connection, type, record, decoded, staged, localIds);
					localIds.put(key, localId);
					inserted.add(key);
				}
			}

			// Refresh existing scalar content without replacing
			// SQLite-local primary keys or breaking relationships.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (!refreshed.contains(key)) {
						continue;
					}
					long localId = localIds.get(key);
					ExchangeRecord actual = content.snapshot(connection, type, localId).record();
					if (!scalarValues(type, actual).equals(scalarValues(type, record))) {
						updateAssignmentBase(connection, type, localId, record, decoded, staged, localIds);
						baseUpdated.add(key);
					}
				}
			}

			// Repair deferred references after every required
			// entity has an established local primary key.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (inserted.contains(key) || baseUpdated.contains(key)) {
						repairDeferredReferences(connection, type, localIds.get(key), record.values(), localIds);
					}
				}
			}

			// Restore ordinary ordered child collections before
			// Booklet applicability-review evidence.
			for (EntityType type : ORDER) {
				if (type == EntityType.EXAM_BOOKLET) {
					continue;
				}
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (inserted.contains(key)) {
						insertChildren(connection, type, localIds.get(key), record, decoded, staged, localIds);
					} else if (refreshed.contains(key)) {
						replaceChangedAssignmentChildren(connection, content, type, localIds.get(key), record, decoded,
								staged, localIds);
					}
				}
			}

			// Question and Answer writes can invalidate Booklet
			// applicability reviews. Restore authoritative review
			// evidence only after those writes have finished.
			for (ExchangeRecord record : ordered.getOrDefault(EntityType.EXAM_BOOKLET, List.of())) {
				Key key = SubjectReconciliationReview.recordKey(EntityType.EXAM_BOOKLET.name(), record.recordId());
				if (inserted.contains(key)) {
					insertChildren(connection, EntityType.EXAM_BOOKLET, localIds.get(key), record, decoded, staged,
							localIds);
				} else if (refreshed.contains(key)) {
					replaceChangedAssignmentChildren(connection, content, EntityType.EXAM_BOOKLET, localIds.get(key),
							record, decoded, staged, localIds);
				}
			}
			List<SqliteContributorIdentityBinder.Binding> bindings = new ArrayList<>();
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (inserted.contains(key)) {
						bindings.add(new SqliteContributorIdentityBinder.Binding(type, localIds.get(key),
								record.recordId(), record.revisionId()));
					}
				}
			}

			// Finish ordinary insertion identity binding only
			// after all domain writes and invalidation triggers.
			binder.bindNew(bindings);

			// Restore authoritative revisions for refreshed
			// records after all child and scalar mutations.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (refreshed.contains(key)) {
						restoreAssignmentRevision(connection, type, localIds.get(key), record);
					}
				}
			}

			// Verify each supplied record independently.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					ExchangeRecord persisted = content.snapshot(connection, type, localIds.get(key)).record();
					if (!record.equals(persisted)) {
						throw new SQLException("Installed assignment record differs " + "from issued baseline: " + key);
					}
				}
			}
			long subjectId = requiredReference(localIds, EntityType.SUBJECT,
					TextNode.valueOf(verified.manifest().subjectId().toString()));
			long examId = requiredReference(localIds, EntityType.EXAM,
					TextNode.valueOf(verified.manifest().scope().examIds().getFirst().toString()));

			// Verify the complete local graph, not merely the
			// presence of every incoming record. Unexpected
			// leftover records must also cause rollback.
			AssignmentRefreshPlan.requireComplete(connection, database, subjectId, examId, verified.baseline());
			connection.releaseSavepoint(checkpoint);
			return Map.copyOf(localIds);
		} catch (SQLException | IOException | RuntimeException failure) {
			try {
				connection.rollback(checkpoint);
			} catch (SQLException rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			try {
				connection.releaseSavepoint(checkpoint);
			} catch (SQLException releaseFailure) {
				failure.addSuppressed(releaseFailure);
			}
			throw failure;
		}
	}

	/**
	 * Applies approved Subject graph additions, updates, removals and revision
	 * changes inside the caller-owned SQLite transaction.
	 *
	 * <p>
	 * The caller must hold the publication lock and must have regenerated the
	 * approved comparison before invoking this operation. Physical asset
	 * publication, package ledger recording and authority advancement remain the
	 * caller's responsibility.
	 *
	 * <p>
	 * Existing canonical identities are preserved. New records receive approved
	 * comparison identities. Removed records retain durable retirement and alias
	 * evidence. Shared resources are protected against unauthorised changes.
	 *
	 * @param connection caller-owned destination transaction
	 * @param approval   exact approved refresh comparison
	 * @param staged     verified private archive and binary assets
	 * @param layout     authoritative managed-data layout
	 * @param lock       held managed-asset publication lock
	 * @return immutable mapping from comparison identities to local identifiers
	 * @throws SQLException          if graph persistence, dependency or ownership
	 *                               validation fails
	 * @throws IOException           if package or asset validation fails
	 * @throws IllegalStateException if the connection uses autocommit
	 */
	Map<SubjectRefreshChangeClassifier.RecordKey, Long> applyRefreshGraph(Connection connection,
			SubjectRefreshPlan.Approval approval, StagedPackage staged, ManagedDataLayout layout,
			ManagedAssetPublicationLock lock) throws SQLException, IOException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(approval, "approval");
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(lock, "lock");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Subject refresh requires a caller-owned transaction");
		}
		lock.requireHeld(layout.dataRoot());
		SubjectRefreshPlan plan = approval.plan();
		DecodedPackage decoded = decodeRefresh(staged, approval);
		long subjectId = plan.evidence().authority().subjectLocalId();
		try (Statement statement = connection.createStatement()) {
			if (statement.executeUpdate("""
					UPDATE installation_identity
					SET installation_id = installation_id
					WHERE singleton_id = 1
					""") != 1) {
				throw new SQLException("Cannot reserve authoritative Subject refresh writer");
			}
		}
		var authority = new SqliteImportedSubjectAuthorityRepository(database).find(connection, subjectId)
				.orElseThrow(() -> new SQLException("Destination Subject has no imported authority"));
		if (!authority.equals(plan.evidence().authority())) {
			throw new SQLException("Imported Subject authority changed after approval");
		}
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		var graph = SqliteSubjectGraphReader.readInTransaction(connection, subjectId);
		Map<Key, Long> localIds = requireRefreshCurrentGraph(connection, content, graph, plan);
		requireRefreshResourceOwnership(connection, graph, plan, decoded, staged, layout, localIds);
		Map<EntityType, List<ExchangeRecord>> ordered = new EnumMap<>(EntityType.class);
		for (ExchangeRecord record : decoded.records()) {
			EntityType type = EntityType.valueOf(record.recordType());
			ordered.computeIfAbsent(type, _ -> new ArrayList<>()).add(record);
		}
		for (List<ExchangeRecord> group : ordered.values()) {
			group.sort(Comparator.comparing(record -> record.recordId().toString()));
		}
		Savepoint checkpoint = connection.setSavepoint();
		try {
			Set<Key> inserted = new HashSet<>();
			Set<Key> baseUpdated = new HashSet<>();
			demoteRefreshSyllabuses(connection, plan, localIds);
			Map<Key, SubjectRefreshChangeClassifier.RecordChange> removals = prepareRefreshRemovals(connection, plan,
					localIds);

			// Remove records whose dependencies are already resolved.
			// This can release natural-key uniqueness constraints before
			// replacement records are inserted.
			deleteReadyRefreshRecords(connection, removals, localIds, false);

			// SQLite may reuse integer IDs belonging to deleted records.
			// Capture the active identity keys after the early removal pass.
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (localIds.containsKey(key)) {
						continue;
					}
					long localId = insertBase(connection, type, record, decoded, staged, localIds);
					localIds.put(key, localId);
					inserted.add(key);
				}
			}

			// Existing records retain their primary keys and canonical UUIDs.
			for (EntityType type : ORDER) {
				if (type == EntityType.EXAM_PROVIDER) {
					continue;
				}
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (inserted.contains(key)) {
						continue;
					}
					long localId = localIds.get(key);
					ExchangeRecord actual = content.snapshot(connection, type, localId).record();
					boolean scalarChanged = !scalarValues(type, actual).equals(scalarValues(type, record));
					boolean pathChanged = refreshManagedPathChanged(connection, type, localId, record, decoded, staged);
					if (scalarChanged || pathChanged) {
						updateAssignmentBase(connection, type, localId, record, decoded, staged, localIds);
						baseUpdated.add(key);
					}
				}
			}

			// Resolve deferred relationships after all incoming base
			// records have obtained local identifiers.
			for (EntityType type : ORDER) {
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					if (inserted.contains(key) || baseUpdated.contains(key)) {
						repairDeferredReferences(connection, type, localIds.get(key), record.values(), localIds);
					}
				}
			}
			for (EntityType type : ORDER) {
				if (type == EntityType.EXAM_BOOKLET) {
					continue;
				}
				for (ExchangeRecord record : ordered.getOrDefault(type, List.of())) {
					Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
					long localId = localIds.get(key);
					if (inserted.contains(key)) {
						insertChildren(connection, type, localId, record, decoded, staged, localIds);
					} else {
						replaceChangedAssignmentChildren(connection, content, type, localId, record, decoded, staged,
								localIds);
					}
				}
			}

			// Surviving records now contain their incoming relationships.
			// Any remaining deletion dependency is unexpected and must
			// cause the complete graph operation to roll back.
			deleteReadyRefreshRecords(connection, removals, localIds, true);

			// Booklet applicability attestations are restored after all
			// Question and Answer changes, including removals.
			for (ExchangeRecord record : ordered.getOrDefault(EntityType.EXAM_BOOKLET, List.of())) {
				Key key = SubjectReconciliationReview.recordKey(EntityType.EXAM_BOOKLET.name(), record.recordId());
				long localId = localIds.get(key);
				if (inserted.contains(key)) {
					insertChildren(connection, EntityType.EXAM_BOOKLET, localId, record, decoded, staged, localIds);
				} else {
					replaceChangedAssignmentChildren(connection, content, EntityType.EXAM_BOOKLET, localId, record,
							decoded, staged, localIds);
				}
			}
			List<SqliteContributorIdentityBinder.Binding> bindings = new ArrayList<>();
			for (ExchangeRecord record : decoded.records()) {
				EntityType type = EntityType.valueOf(record.recordType());
				Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
				if (inserted.contains(key)) {
					bindings.add(new SqliteContributorIdentityBinder.Binding(type, localIds.get(key), record.recordId(),
							record.revisionId()));
				}
			}
			binder.bindNew(bindings);
			for (ExchangeRecord record : decoded.records()) {
				EntityType type = EntityType.valueOf(record.recordType());
				Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
				if (!inserted.contains(key) && type != EntityType.EXAM_PROVIDER) {
					restoreAssignmentRevision(connection, type, localIds.get(key), record);
				}
			}
			Map<SubjectRefreshChangeClassifier.RecordKey, Long> result = new HashMap<>();
			UUID origin = decoded.manifest().originInstallationId();
			for (var mapping : plan.identities().entrySet()) {
				var originating = mapping.getKey();
				EntityType type = EntityType.valueOf(originating.recordType());
				UUID canonical = mapping.getValue();
				Key key = SubjectReconciliationReview.recordKey(type.name(), canonical);
				Long localId = localIds.get(key);
				if (localId == null) {
					throw new SQLException("Missing refresh canonical identity: " + canonical);
				}
				aliases.approveAlias(connection, origin, type, originating.recordId(), canonical,
						approval.approvalNote());
				result.put(new SubjectRefreshChangeClassifier.RecordKey(type.name(), canonical), localId);
			}
			requireAppliedRefreshGraph(connection, content, subjectId, decoded, staged, localIds);
			requireRefreshRetirements(connection, plan);
			connection.releaseSavepoint(checkpoint);
			return Map.copyOf(result);
		} catch (SQLException | IOException | RuntimeException failure) {
			try {
				connection.rollback(checkpoint);
			} catch (SQLException rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			try {
				connection.releaseSavepoint(checkpoint);
			} catch (SQLException releaseFailure) {
				failure.addSuppressed(releaseFailure);
			}
			throw failure;
		}
	}

	private StagedPackage assignmentStaging(WorkAssignmentPackageInspector.Inspection inspection,
			WorkAssignmentAssetExtractor.Extraction extraction) throws IOException {
		Map<String, ExamWorkManifest.Entry> declared = new HashMap<>();
		for (ExamWorkManifest.Entry entry : inspection.manifest().entries()) {
			if (entry.role() == EntryRole.ASSET) {
				declared.put(entry.path(), entry);
			}
		}
		Set<String> extractedPaths = new HashSet<>();
		for (var asset : extraction.assets()) {
			ExamWorkManifest.Entry entry = declared.get(asset.archivePath());
			if (entry == null || !extractedPaths.add(asset.archivePath()) || entry.size() != asset.size()
					|| !entry.sha256().equals(asset.sha256())) {
				throw new IOException("Extracted assignment asset inventory differs " + "from manifest");
			}
			extraction.requireVerifiedAsset(asset.archivePath());
		}
		if (!extractedPaths.equals(declared.keySet())) {
			throw new IOException("Assignment extraction omitted physical assets");
		}
		List<StagedAsset> adapted = new ArrayList<>();
		for (Reference reference : inspection.assets().references()) {
			Path source = extraction.requireVerifiedAsset(reference.archivePath());
			AssetKind kind;
			try {
				kind = SubjectImportAssetStager.kindFor(reference);
			} catch (IllegalArgumentException exception) {
				throw new IOException("Unsupported assignment asset usage", exception);
			}
			adapted.add(new StagedAsset(reference.managedPath(), reference.archivePath(), source, reference.sha256(),
					reference.size(), kind));
		}

		// Adapt only the verified binary inventory to the existing
		// table-mapping routines. No Subject import approval, alias
		// or publication policy is inherited.
		return new StagedPackage(inspection.manifest().packageId(), inspection.archiveSha256(),
				extraction.staged().directory(), extraction.staged().archive(), adapted);
	}

	/**
	 * Reads one staged binary asset corresponding to a portable image digest.
	 */
	private byte[] blobFor(DecodedPackage decoded, StagedPackage staged, EntityType ownerType, UUID ownerId,
			JsonNode row, String table, JsonNode digest) throws IOException, SQLException {
		if (!digest.isTextual() || !digest.asText().matches("[0-9a-f]{64}")) {
			throw new SQLException("Imported image has an invalid SHA-256 digest");
		}
		String usage = table.equals("question_images") ? "EMBEDDED_QUESTION_PNG"
				: table.equals("shared_question_context_images") ? "EMBEDDED_CONTEXT_PNG" : null;
		if (usage == null) {
			throw new SQLException("Unexpected BLOB field in imported table " + table);
		}
		for (Reference reference : decoded.index().references()) {
			if (!usage.equals(reference.usage()) || !ownerType.name().equals(reference.ownerType())
					|| !ownerId.equals(reference.ownerId()) || !digest.asText().equals(reference.sha256())) {
				continue;
			}
			if (table.equals("shared_question_context_images")) {
				int order = row.path("image_order").asInt(-1);
				if (!reference.managedPath().endsWith("-" + order + ".png")) {
					continue;
				}
			}
			StagedAsset asset = staged.assets().stream().filter(candidate -> candidate.kind() == AssetKind.EMBEDDED_PNG)
					.filter(candidate -> candidate.managedPath().equals(reference.managedPath())).findFirst()
					.orElseThrow(() -> new IOException("Missing approved staged PNG: " + reference.managedPath()));
			byte[] bytes = Files.readAllBytes(asset.stagedPath());
			if (!asset.sha256().equals(reference.sha256()) || bytes.length != reference.size()) {
				throw new IOException("Staged embedded PNG size or checksum changed");
			}
			String actual;
			try {
				actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException("SHA-256 unavailable", exception);
			}
			if (!actual.equals(reference.sha256())) {
				throw new IOException("Staged embedded PNG was changed after asset validation");
			}
			return bytes;
		}
		throw new IOException("No approved embedded image matches " + ownerType + ":" + ownerId);
	}

	/**
	 * Binds a coordinator's note to the repository's bounded audit field.
	 */
	private String clippedNote(String note) {
		String text = Objects.requireNonNull(note, "note").strip();
		if (text.isEmpty()) {
			throw new IllegalArgumentException("Subject alias requires a coordinator note");
		}
		return text.length() > 1000 ? text.substring(0, 1000) : text;
	}

	/**
	 * Returns columns from an internal, whitelisted database table.
	 */
	private Set<String> columns(Connection connection, String table) throws SQLException {
		if (TABLES.values().stream().noneMatch(spec -> spec.name().equals(table)
				|| spec.children().stream().anyMatch(child -> child.name().equals(table)))) {
			throw new SQLException("Unrecognised table in Subject import registry: " + table);
		}
		Set<String> names = new HashSet<>();
		try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM pragma_table_info(?)")) {
			statement.setString(1, table);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					String name = result.getString(1);
					if (!name.matches("[a-z][a-z0-9_]*")) {
						throw new SQLException("Unexpected SQLite table column: " + name);
					}
					names.add(name);
				}
			}
		}
		if (names.isEmpty()) {
			throw new SQLException("Missing Subject import table: " + table);
		}
		return names;
	}

	/**
	 * Deletes approved records only when no remaining foreign-key references depend
	 * on them.
	 *
	 * <p>
	 * Repeated passes accommodate parent-child relationships within the same entity
	 * type, including nested curriculum nodes.
	 */
	private void deleteReadyRefreshRecords(Connection connection,
			Map<Key, SubjectRefreshChangeClassifier.RecordChange> removals, Map<Key, Long> localIds,
			boolean requireComplete) throws SQLException {
		boolean progressed;
		do {
			progressed = false;
			for (EntityType type : ORDER.reversed()) {
				for (var entry : List.copyOf(removals.entrySet())) {
					Key key = entry.getKey();
					var change = entry.getValue();
					if (!type.name().equals(change.key().recordType())) {
						continue;
					}
					Long localId = localIds.get(key);
					if (localId == null) {
						throw new SQLException("Missing approved removal identity: " + key);
					}
					String table = TABLES.get(type).name();
					if (hasRefreshDependants(connection, table, localId)) {
						continue;
					}

					// Deleting owned children can generate intermediate
					// revision tokens. Retirement must record the actual
					// previously approved canonical revision instead.
					restoreAssignmentRevision(connection, type, localId, change.current());
					String sql = "DELETE FROM " + quoteRefreshIdentifier(table) + " WHERE id = ?";
					try (PreparedStatement statement = connection.prepareStatement(sql)) {
						statement.setLong(1, localId);
						if (SqliteDirectChanges.executeUpdate(statement) != 1) {
							throw new SQLException("Approved removal did not delete exactly one record: " + key);
						}
					}
					requireRefreshRetirement(connection, change, localId);
					removals.remove(key);
					localIds.remove(key);
					progressed = true;
				}
			}
		} while (progressed && !removals.isEmpty());
		if (requireComplete && !removals.isEmpty()) {
			throw new SQLException("Approved Subject removals retain unresolved dependencies: " + removals.keySet());
		}
	}

	/**
	 * Demotes existing current syllabuses that will either be removed or become
	 * historical in the approved incoming snapshot.
	 */
	private void demoteRefreshSyllabuses(Connection connection, SubjectRefreshPlan plan, Map<Key, Long> localIds)
			throws SQLException {
		for (var change : plan.recordChanges()) {
			if (!EntityType.SYLLABUS_VERSION.name().equals(change.key().recordType()) || change.current() == null) {
				continue;
			}
			boolean wasCurrent = change.current().values().path("is_current").asInt() == 1;
			boolean willBeCurrent = change.incoming() != null
					&& change.incoming().values().path("is_current").asInt() == 1;
			if (!wasCurrent || willBeCurrent) {
				continue;
			}
			Key key = SubjectReconciliationReview.recordKey(EntityType.SYLLABUS_VERSION.name(),
					change.key().recordId());
			Long localId = localIds.get(key);
			if (localId == null) {
				throw new SQLException("Missing current syllabus identity");
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE syllabus_versions
					SET is_current = 0
					WHERE id = ?
					""")) {
				statement.setLong(1, localId);
				if (SqliteDirectChanges.executeUpdate(statement) != 1) {
					throw new SQLException("Cannot demote previous current syllabus");
				}
			}
		}
	}

	/**
	 * Determines whether any persisted foreign-key reference still depends on a
	 * proposed deletion.
	 *
	 * <p>
	 * Inspecting the live schema includes relationships not represented in portable
	 * Subject records. CASCADE and SET NULL relationships are treated as
	 * dependencies rather than permission for implicit mutation.
	 */
	private boolean hasRefreshDependants(Connection connection, String parentTable, long localId) throws SQLException {
		record ForeignKey(String childTable, int foreignKeyId) {
		}
		Map<ForeignKey, List<String>> relationships = new LinkedHashMap<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    schema_table.name AS child_table,
				    fk.id AS foreign_key_id,
				    fk.seq AS column_order,
				    fk."from" AS child_column,
				    fk."to" AS parent_column
				FROM sqlite_schema schema_table
				JOIN pragma_foreign_key_list(schema_table.name) fk
				WHERE schema_table.type = 'table'
				  AND fk."table" = ? COLLATE NOCASE
				ORDER BY schema_table.name, fk.id, fk.seq
				""")) {
			statement.setString(1, parentTable);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					String childTable = rows.getString("child_table");
					int foreignKeyId = rows.getInt("foreign_key_id");
					String childColumn = rows.getString("child_column");
					String parentColumn = rows.getString("parent_column");

					// Every portable base table has one integer primary key.
					if (parentColumn == null || parentColumn.isBlank()) {
						parentColumn = "id";
					}
					ForeignKey key = new ForeignKey(childTable, foreignKeyId);
					String comparison = "parent." + quoteRefreshIdentifier(parentColumn) + " = child."
							+ quoteRefreshIdentifier(childColumn);
					relationships.computeIfAbsent(key, _ -> new ArrayList<>()).add(comparison);
				}
			}
		}

		// Group composite foreign-key columns together. Checking individual
		// columns would incorrectly report references to unrelated records.
		for (var relationship : relationships.entrySet()) {
			String sql = "SELECT 1 FROM " + quoteRefreshIdentifier(relationship.getKey().childTable()) + " child JOIN "
					+ quoteRefreshIdentifier(parentTable) + " parent ON "
					+ String.join(" AND ", relationship.getValue()) + " WHERE parent.id = ? LIMIT 1";
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, localId);
				try (ResultSet rows = statement.executeQuery()) {
					if (rows.next()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Inserts one approved row, including nullable child-only owner fields.
	 */
	private long insert(Connection connection, String table, Map<String, Object> values) throws SQLException {
		if (values.isEmpty()) {
			throw new SQLException("Cannot insert a Subject entity with no data: " + table);
		}
		List<String> names = List.copyOf(values.keySet());
		String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
				+ String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < names.size(); i++) {
				Object value = values.get(names.get(i));
				if (value instanceof byte[] bytes) {
					statement.setBytes(i + 1, bytes);
				} else {
					statement.setObject(i + 1, value);
				}
			}
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Subject import did not insert exactly one row");
			}
		}
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
			if (!result.next() || result.getLong(1) < 1) {
				throw new SQLException("No row ID returned for Subject import row");
			}
			return result.getLong(1);
		}
	}

	/**
	 * Adds one whitelisted entity without supplying any SQL identifier from JSON.
	 */
	private long insertBase(Connection connection, EntityType type, ExchangeRecord record, DecodedPackage decoded,
			StagedPackage staged, Map<Key, Long> ids) throws SQLException, IOException {
		Table table = TABLES.get(type);
		Map<String, Object> values = rowValues(connection, table.name(), record.values(), null, decoded, staged, ids,
				record.recordId(), type, true);
		if (type == EntityType.SYLLABUS_VERSION && record.values().path("is_current").asInt(0) != 0) {
			long subjectId = requiredReference(ids, EntityType.SUBJECT, record.values().path("subject_id"));
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT COUNT(*) FROM syllabus_versions WHERE subject_id = ? AND is_current = 1")) {
				statement.setLong(1, subjectId);
				try (ResultSet result = statement.executeQuery()) {
					if (result.next() && result.getLong(1) > 0) {
						throw new SQLException("New syllabus cannot replace the current master syllabus");
					}
				}
			}
		}
		return insert(connection, table.name(), values);
	}

	/**
	 * Inserts all ordered child content after the entity and image identities.
	 */
	private void insertChildren(Connection connection, EntityType type, long localId, ExchangeRecord record,
			DecodedPackage decoded, StagedPackage staged, Map<Key, Long> ids) throws SQLException, IOException {
		for (Child child : TABLES.get(type).children()) {
			JsonNode rows = record.values().get(child.name());
			if (rows == null || !rows.isArray()) {
				throw new SQLException("Missing expected child collection " + child.name() + " for " + type);
			}

			// QuestionImage is separately exported as an entity with a canonical
			// UUID. Re-inserting its nested digest-only representation would
			// duplicate the physical image and corrupt content-part references.
			if (type == EntityType.QUESTION && child.name().equals("question_images")) {
				continue;
			}
			for (JsonNode item : rows) {
				Map<String, Object> values = rowValues(connection, child.name(), item, child.owner(), decoded, staged,
						ids, record.recordId(), type, false);
				values.put(child.owner(), localId);
				insert(connection, child.name(), values);
			}
		}
	}

	/**
	 * Looks up one owner-associated managed file and checks approval coverage.
	 */
	private String managedPath(ExamWorkAssetIndex index, StagedPackage staged, String usage, EntityType ownerType,
			UUID ownerId) throws SQLException, IOException {
		Reference found = null;
		for (Reference reference : index.references()) {
			if (usage.equals(reference.usage()) && ownerType.name().equals(reference.ownerType())
					&& ownerId.equals(reference.ownerId())) {
				if (found != null) {
					throw new SQLException("Multiple " + usage + " assets for " + ownerId);
				}
				found = reference;
			}
		}
		if (found == null) {
			return null;
		}
		PathValidation.requireSubjectPath(found.managedPath());

		// New imported managed files must have been staged.
		// An identical approved master file is independently
		// checked by the coordinator.
		boolean stagedMatch = false;
		for (StagedAsset asset : staged.assets()) {
			if (asset.kind() == AssetKind.MANAGED_FILE && asset.managedPath().equals(found.managedPath())
					&& asset.sha256().equals(found.sha256())) {
				stagedMatch = true;
				break;
			}
		}
		if (!stagedMatch) {
			throw new SQLException("New imported file reference lacks staged bytes: " + found.managedPath());
		}
		return found.managedPath();
	}

	/**
	 * Collects approved portable record removals and releases their owned dependent
	 * child collections before deleting base entities.
	 */
	private Map<Key, SubjectRefreshChangeClassifier.RecordChange> prepareRefreshRemovals(Connection connection,
			SubjectRefreshPlan plan, Map<Key, Long> localIds) throws SQLException {
		Map<Key, SubjectRefreshChangeClassifier.RecordChange> removals = new LinkedHashMap<>();
		for (var change : plan.recordChanges()) {
			if (change.kind() != SubjectRefreshChangeClassifier.ChangeKind.REMOVE) {
				continue;
			}
			EntityType type = EntityType.valueOf(change.key().recordType());
			if (type == EntityType.SUBJECT || type == EntityType.EXAM_PROVIDER) {
				throw new SQLException("Refresh cannot delete a Subject or shared examination provider");
			}
			Key key = SubjectReconciliationReview.recordKey(type.name(), change.key().recordId());
			if (!localIds.containsKey(key) || change.current() == null || change.incoming() != null) {
				throw new SQLException("Invalid approved Subject record removal: " + key);
			}
			removals.put(key, change);
		}
		for (var entry : removals.entrySet()) {
			var change = entry.getValue();
			EntityType type = EntityType.valueOf(change.key().recordType());
			long localId = localIds.get(entry.getKey());

			// A removed booklet can reference one of its own shared
			// contexts, producing a deletion dependency cycle.
			if (type == EntityType.EXAM_BOOKLET) {
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE exam_booklets
						SET pending_mcq_shared_context_id = NULL
						WHERE id = ?
						  AND pending_mcq_shared_context_id IS NOT NULL
						""")) {
					statement.setLong(1, localId);
					statement.executeUpdate();
				}
			}

			// Dependent content parts must be removed before their regions
			// and image collections.
			for (Child child : TABLES.get(type).children().reversed()) {

				// QuestionImage is itself a portable entity. Its deletion
				// requires an independently approved removal and retirement.
				if (type == EntityType.QUESTION && child.name().equals("question_images")) {
					continue;
				}
				String sql = "DELETE FROM " + quoteRefreshIdentifier(child.name()) + " WHERE "
						+ quoteRefreshIdentifier(child.owner()) + " = ?";
				try (PreparedStatement statement = connection.prepareStatement(sql)) {
					statement.setLong(1, localId);
					statement.executeUpdate();
				}
			}
		}
		return removals;
	}

	/**
	 * Converts a JSON scalar without permitting nested arbitrary SQL objects.
	 */
	private Object primitive(JsonNode value, String field) throws SQLException {
		if (value.isNull()) {
			return null;
		}
		if (value.isTextual()) {
			return value.asText();
		}
		if (value.isIntegralNumber()) {
			return value.longValue();
		}
		if (value.isFloatingPointNumber()) {
			return value.doubleValue();
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? 1 : 0;
		}
		throw new SQLException("Unsupported portable SQLite value: " + field);
	}

	/**
	 * Quotes a trusted SQLite schema identifier for dynamically generated
	 * dependency queries.
	 */
	private String quoteRefreshIdentifier(String identifier) throws SQLException {
		if (identifier == null || identifier.isBlank() || identifier.indexOf('\0') >= 0) {
			throw new SQLException("Invalid SQLite schema identifier");
		}
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}

	private void reconcileAssignmentIdentities(SqliteContributorIdentityBinder binder, AssignmentRefreshPlan refresh,
			List<ExchangeRecord> records) throws SQLException {
		List<Identity> originals = new ArrayList<>();
		List<SqliteContributorIdentityBinder.Binding> bindings = new ArrayList<>();
		for (ExchangeRecord record : records) {
			EntityType type = EntityType.valueOf(record.recordType());
			Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
			Identity original = refresh.replacements().get(key);
			if (original == null) {
				continue;
			}
			originals.add(original);
			bindings.add(new SqliteContributorIdentityBinder.Binding(type, original.localId(), record.recordId(),
					record.revisionId()));
		}
		if (bindings.size() != refresh.replacements().size()) {
			throw new SQLException("Incomplete authoritative identity reconciliation");
		}
		binder.bindReconciled(originals, bindings);
	}

	/**
	 * Compares separately packaged managed paths that are not included in portable
	 * scalar record content.
	 */
	private boolean refreshManagedPathChanged(Connection connection, EntityType type, long localId,
			ExchangeRecord record, DecodedPackage decoded, StagedPackage staged) throws SQLException, IOException {
		String sql;
		String usage;
		switch (type) {
		case SOURCE_DOCUMENT -> {
			sql = "SELECT relative_path FROM source_documents WHERE id = ?";
			usage = "SOURCE_PDF";
		}
		case SYLLABUS_VERSION -> {
			sql = "SELECT source_pdf_path FROM syllabus_versions WHERE id = ?";
			usage = "SYLLABUS_PDF";
		}
		default -> {
			return false;
		}
		}
		String expected = managedPath(decoded.index(), staged, usage, type, record.recordId());
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					throw new SQLException("Missing managed-path owner: " + type + " " + localId);
				}
				String actual = rows.getString(1);
				return !Objects.equals(actual, expected);
			}
		}
	}

	/**
	 * Populates deferred optional foreign keys after all entity rows exist.
	 */
	private void repairDeferredReferences(Connection connection, EntityType type, long localId, JsonNode json,
			Map<Key, Long> ids) throws SQLException {
		String column;
		if (type == EntityType.CURRICULUM_NODE) {
			column = "parent_id";
		} else if (type == EntityType.EXAM_BOOKLET) {
			column = "pending_mcq_shared_context_id";
		} else {
			return;
		}
		JsonNode value = json.get(column);
		if (value == null || value.isNull()) {
			return;
		}
		Object foreign = requiredReference(ids, REFERENCES.get(column), value);
		String sql = "UPDATE " + TABLES.get(type).name() + " SET " + column + " = ? WHERE id = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, foreign);
			statement.setLong(2, localId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Missing deferred FK owner: " + type + ":" + localId);
			}
		}
	}

	private void replaceChangedAssignmentChildren(Connection connection, SqlitePortableContentRepository content,
			EntityType type, long localId, ExchangeRecord record, DecodedPackage decoded, StagedPackage staged,
			Map<Key, Long> ids) throws SQLException, IOException {
		ExchangeRecord actual = content.snapshot(connection, type, localId).record();
		List<Child> children = TABLES.get(type).children().stream()
				.filter(child -> type != EntityType.QUESTION || !child.name().equals("question_images")).toList();
		boolean changed = children.stream().anyMatch(
				child -> !Objects.equals(actual.values().get(child.name()), record.values().get(child.name())));
		if (!changed) {
			return;
		}

		// Remove dependent content parts before their referenced
		// regions or images. Question images are independent
		// portable entities and are not deleted here.
		for (Child child : children.reversed()) {
			String sql = "DELETE FROM " + child.name() + " WHERE " + child.owner() + " = ?";
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, localId);
				statement.executeUpdate();
			}
		}

		// Reuse the established portable child and asset
		// insertion routines to reconstruct ordered content.
		insertChildren(connection, type, localId, record, decoded, staged, ids);
	}

	/**
	 * Verifies the complete resulting Subject graph, including portable identities,
	 * revisions, relationships, children and managed paths.
	 */
	private void requireAppliedRefreshGraph(Connection connection, SqlitePortableContentRepository content,
			long subjectId, DecodedPackage decoded, StagedPackage staged, Map<Key, Long> localIds)
			throws SQLException, IOException {
		var graph = SqliteSubjectGraphReader.readInTransaction(connection, subjectId);
		Map<EntityType, Set<Long>> expected = new EnumMap<>(EntityType.class);
		for (EntityType type : EntityType.values()) {
			expected.put(type, new HashSet<>());
		}
		for (ExchangeRecord record : decoded.records()) {
			EntityType type = EntityType.valueOf(record.recordType());
			Key key = SubjectReconciliationReview.recordKey(type.name(), record.recordId());
			Long localId = localIds.get(key);
			if (localId == null || !expected.get(type).add(localId)) {
				throw new SQLException("Missing or duplicate applied refresh identity: " + key);
			}
			ExchangeRecord persisted = content.snapshot(connection, type, localId).record();
			if (!persisted.equals(record)) {
				throw new SQLException("Applied Subject refresh record differs from approval: " + key);
			}
			if (refreshManagedPathChanged(connection, type, localId, record, decoded, staged)) {
				throw new SQLException("Applied Subject refresh managed path differs from approval: " + key);
			}
		}

		// Checking individual records alone cannot detect unexpected
		// additional entities or records accidentally moved outside the Subject.
		for (EntityType type : EntityType.values()) {
			if (!expected.get(type).equals(new HashSet<>(graph.ids(type)))) {
				throw new SQLException("Applied Subject refresh has incorrect graph coverage: " + type);
			}
		}
		try (Statement statement = connection.createStatement();
				ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (violations.next()) {
				throw new SQLException("Applied Subject refresh contains foreign-key violations");
			}
		}
	}

	/**
	 * Requires decoded records to match the approved comparison while retaining
	 * existing shared examination provider revisions.
	 */
	private List<ExchangeRecord> requireApprovedRefreshRecords(SubjectRefreshPlan plan, List<ExchangeRecord> records)
			throws IOException {
		Map<SubjectRefreshChangeClassifier.RecordKey, SubjectRefreshChangeClassifier.RecordChange> expected = new HashMap<>();
		for (var change : plan.recordChanges()) {
			if (change.incoming() != null) {
				expected.put(change.key(), change);
			}
		}
		List<ExchangeRecord> approved = new ArrayList<>();
		for (ExchangeRecord record : records) {
			EntityType type;
			try {
				type = EntityType.valueOf(record.recordType());
			} catch (IllegalArgumentException failure) {
				throw new IOException("Unsupported refresh entity type", failure);
			}
			if (!TABLES.containsKey(type)) {
				throw new IOException("Refresh entity has no supported persistence mapping");
			}
			var key = new SubjectRefreshChangeClassifier.RecordKey(record.recordType(), record.recordId());
			var change = expected.remove(key);
			if (change == null) {
				throw new IOException("Unapproved incoming refresh record: " + key);
			}
			if (type == EntityType.EXAM_PROVIDER
					&& change.kind() == SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED) {

				// Shared provider values must match, but the destination
				// revision remains authoritative across all Subjects.
				if (!record.values().equals(change.incoming().values())) {
					throw new IOException("Refresh would modify a shared examination provider");
				}
				approved.add(change.incoming());
			} else {
				if (!record.equals(change.incoming())) {
					throw new IOException("Decoded refresh record differs from approval: " + key);
				}
				approved.add(record);
			}
		}
		for (var remaining : expected.values()) {

			// A provider may no longer be referenced by this Subject.
			// That does not authorise deletion from the global registry.
			if (!"EXAM_PROVIDER".equals(remaining.key().recordType())
					|| remaining.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED) {
				throw new IOException("Approved incoming refresh record is missing: " + remaining.key());
			}
		}
		return List.copyOf(approved);
	}

	/**
	 * Finds a mandatory already-inserted local foreign key.
	 */
	private long requiredReference(Map<Key, Long> ids, EntityType type, JsonNode value) throws SQLException {
		if (type == null || !value.isTextual()) {
			throw new SQLException("Imported foreign key is not a canonical UUID");
		}
		UUID uuid;
		try {
			uuid = UUID.fromString(value.asText());
			if (!uuid.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Invalid imported portable FK", exception);
		}
		Long local = ids.get(SubjectReconciliationReview.recordKey(type.name(), uuid));
		if (local == null) {
			throw new SQLException("Imported relationship has no approved local identity: " + type + ":" + uuid);
		}
		return local;
	}

	private void requireExistingAssignmentRecord(Connection connection, SqlitePortableContentRepository content,
			EntityType type, long localId, ExchangeRecord record, ExamWorkAssetIndex assets) throws SQLException {
		ExchangeRecord actual = content.snapshot(connection, type, localId).record();
		if (!record.equals(actual)) {
			throw new SQLException("Existing contributor record differs from " + "the issued baseline: " + type + ":"
					+ record.recordId());
		}
		String column;
		String table;
		String usage;
		if (type == EntityType.SOURCE_DOCUMENT) {
			table = "source_documents";
			column = "relative_path";
			usage = "SOURCE_PDF";
		} else if (type == EntityType.SYLLABUS_VERSION) {
			table = "syllabus_versions";
			column = "source_pdf_path";
			usage = "SYLLABUS_PDF";
		} else {
			return;
		}
		String expectedPath = null;
		for (Reference reference : assets.references()) {
			if (!usage.equals(reference.usage()) || !type.name().equals(reference.ownerType())
					|| !record.recordId().equals(reference.ownerId())) {
				continue;
			}
			if (expectedPath != null) {
				throw new SQLException("Duplicate assignment asset binding for " + record.recordId());
			}
			expectedPath = reference.managedPath();
		}
		String sql = "SELECT " + column + " FROM " + table + " WHERE id = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || !Objects.equals(expectedPath, result.getString(1))) {
					throw new SQLException("Existing assignment source path " + "differs from issued dependency: "
							+ record.recordId());
				}
				if (result.next()) {
					throw new SQLException("Duplicate existing assignment source row");
				}
			}
		}
	}

	/**
	 * Requires the current destination graph to match the approved comparison
	 * before permitting any domain mutation.
	 */
	private Map<Key, Long> requireRefreshCurrentGraph(Connection connection, SqlitePortableContentRepository content,
			SqliteSubjectGraphReader.SubjectGraph graph, SubjectRefreshPlan plan) throws SQLException {
		if (!graph.subjectName().equals(plan.evidence().incoming().fingerprint().subjectName())) {
			throw new SQLException("Destination Subject name changed after approval");
		}
		Map<Key, SubjectRefreshChangeClassifier.RecordChange> reviewed = new HashMap<>();
		Map<Key, Long> localIds = new HashMap<>();
		for (var change : plan.recordChanges()) {
			Key key = SubjectReconciliationReview.recordKey(change.key().recordType(), change.key().recordId());
			reviewed.put(key, change);
		}

		// Every existing Subject entity must occur in the approved comparison.
		for (EntityType type : EntityType.values()) {
			for (long localId : graph.ids(type)) {
				ExchangeRecord actual = content.snapshot(connection, type, localId).record();
				Key key = SubjectReconciliationReview.recordKey(type.name(), actual.recordId());
				var change = reviewed.get(key);
				if (change == null || change.current() == null || !change.current().equals(actual)) {
					throw new SQLException("Destination graph changed after refresh approval: " + key);
				}
				if (localIds.putIfAbsent(key, localId) != null) {
					throw new SQLException("Duplicate current Subject refresh identity");
				}
			}
		}
		for (var change : plan.recordChanges()) {
			EntityType type = EntityType.valueOf(change.key().recordType());
			Key key = SubjectReconciliationReview.recordKey(type.name(), change.key().recordId());
			if (change.current() == null) {
				if (localIds.containsKey(key)
						|| identities.findByPortableId(connection, type, change.key().recordId()).isPresent()) {
					throw new SQLException("Approved new refresh identity already exists: " + key);
				}
				continue;
			}
			if (type == EntityType.EXAM_PROVIDER) {
				if (change.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED) {
					throw new SQLException("Refresh cannot modify an existing shared provider");
				}
				if (!localIds.containsKey(key)) {

					// An approved shared provider may already exist globally
					// without being referenced by the current Subject.
					var identity = identities.findByPortableId(connection, type, change.key().recordId())
							.orElseThrow(() -> new SQLException("Reviewed shared provider no longer exists"));
					ExchangeRecord actual = content.snapshot(connection, type, identity.localId()).record();
					if (!change.current().equals(actual)) {
						throw new SQLException("Shared provider changed after approval");
					}
					localIds.put(key, identity.localId());
				}
			} else if (!localIds.containsKey(key)) {
				throw new SQLException("Reviewed entity is missing from destination Subject: " + key);
			}
		}
		return localIds;
	}

	/**
	 * Resolves a persisted managed resource path without allowing symbolic links or
	 * other indirect filesystem components.
	 */
	private Path requireRefreshReferencePath(ManagedDataLayout layout, String relativePath) throws IOException {
		Path destination;
		try {
			destination = layout.resolve(relativePath);
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new IOException("Cannot establish ownership of persisted managed resource", failure);
		}
		Path current = layout.dataRoot();
		for (Path component : layout.dataRoot().relativize(destination)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Indirect persisted managed resource path: " + current);
			}
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				break;
			}
			var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (attributes.isOther()) {
				throw new IOException("Unsupported persisted managed resource path: " + current);
			}
		}
		return destination;
	}

	/**
	 * Prevents refresh graph changes or physical asset changes from modifying
	 * resources belonging to unrelated Subjects.
	 */
	private void requireRefreshResourceOwnership(Connection connection, SqliteSubjectGraphReader.SubjectGraph graph,
			SubjectRefreshPlan plan, DecodedPackage decoded, StagedPackage staged, ManagedDataLayout layout,
			Map<Key, Long> localIds) throws SQLException, IOException {
		Set<Long> selectedDocuments = new HashSet<>(graph.ids(EntityType.SOURCE_DOCUMENT));
		Set<Long> sharedDocuments = new HashSet<>();
		Set<Path> protectedPaths = new HashSet<>();
		String documentSql = """
				SELECT
				    d.id,
				    d.relative_path,
				    EXISTS (
				        SELECT 1
				        FROM exam_booklets b
				        JOIN exams e ON e.id = b.exam_id
				        WHERE b.source_document_id = d.id
				          AND e.subject_id <> ?
				    )
				    OR EXISTS (
				        SELECT 1
				        FROM answer_files a
				        JOIN exams e ON e.id = a.exam_id
				        WHERE a.source_document_id = d.id
				          AND e.subject_id <> ?
				    ) AS externally_referenced
				FROM source_documents d
				""";
		try (PreparedStatement statement = connection.prepareStatement(documentSql)) {
			statement.setLong(1, graph.subjectId());
			statement.setLong(2, graph.subjectId());
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					long documentId = rows.getLong("id");
					boolean external = rows.getBoolean("externally_referenced");
					boolean selected = selectedDocuments.contains(documentId);
					if (external && selected) {
						sharedDocuments.add(documentId);
					}

					// Protect documents belonging to other Subjects,
					// shared documents and unselected inventory records.
					if (external || !selected) {
						protectedPaths.add(requireRefreshReferencePath(layout, rows.getString("relative_path")));
					}
				}
			}
		}

		// Syllabus source PDFs are independently referenced managed
		// resources and require the same cross-Subject protection.
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT source_pdf_path
				FROM syllabus_versions
				WHERE subject_id <> ?
				  AND source_pdf_path IS NOT NULL
				""")) {
			statement.setLong(1, graph.subjectId());
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					protectedPaths.add(requireRefreshReferencePath(layout, rows.getString(1)));
				}
			}
		}

		// An existing shared SourceDocument must retain both its
		// domain content and its persisted managed path.
		for (var change : plan.recordChanges()) {
			if (!EntityType.SOURCE_DOCUMENT.name().equals(change.key().recordType()) || change.current() == null) {
				continue;
			}
			Key key = SubjectReconciliationReview.recordKey(EntityType.SOURCE_DOCUMENT.name(), change.key().recordId());
			Long localId = localIds.get(key);
			if (localId == null || !sharedDocuments.contains(localId)) {
				continue;
			}
			if (change.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED || change.incoming() == null
					|| refreshManagedPathChanged(connection, EntityType.SOURCE_DOCUMENT, localId, change.incoming(),
							decoded, staged)) {
				throw new SQLException("Refresh would modify a shared SourceDocument: " + change.key().recordId());
			}
		}
		Path embeddedRoot = layout.subjectDirectory(graph.subjectName()).resolve("exams").resolve("_examwork_embedded");

		// Protect physical resources even where separate database
		// records refer to the same managed file.
		for (var change : plan.assetChanges()) {
			if (change.kind() == SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED) {
				continue;
			}
			Path destination = requireRefreshReferencePath(layout, change.managedPath());
			if (destination.startsWith(embeddedRoot)) {
				continue;
			}
			for (Path protectedPath : protectedPaths) {
				boolean samePath = destination.equals(protectedPath);
				if (!samePath && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
						&& Files.isRegularFile(protectedPath, LinkOption.NOFOLLOW_LINKS)) {
					samePath = Files.isSameFile(destination, protectedPath);
				}
				if (samePath) {
					throw new IOException("Refresh would modify a managed resource "
							+ "referenced outside the Subject: " + change.managedPath());
				}
			}
		}
	}

	/**
	 * Verifies that deletion retired the exact previously approved canonical
	 * identity and revision without leaving an active identity behind.
	 */
	private void requireRefreshRetirement(Connection connection, SubjectRefreshChangeClassifier.RecordChange change,
			long localId) throws SQLException {
		EntityType type = EntityType.valueOf(change.key().recordType());
		UUID portableId = change.key().recordId();
		if (identities.findByPortableId(connection, type, portableId).isPresent()) {
			throw new SQLException("Removed portable identity remains active: " + portableId);
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    entity_type,
				    local_id,
				    revision_id
				FROM portable_entity_retirements
				WHERE portable_id = ?
				""")) {
			statement.setString(1, portableId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next() || !type.name().equals(rows.getString("entity_type"))
						|| rows.getLong("local_id") != localId
						|| !change.current().revisionId().toString().equals(rows.getString("revision_id"))
						|| rows.next()) {
					throw new SQLException("Incorrect retirement evidence for removed identity: " + portableId);
				}
			}
		}
	}

	/**
	 * Verifies final removal evidence and preservation of originating aliases.
	 *
	 * <p>
	 * An integer primary key may have been reused during insertion. The retired
	 * portable UUID, rather than that integer key, establishes the durable identity
	 * of the removed record.
	 */
	private void requireRefreshRetirements(Connection connection, SubjectRefreshPlan plan) throws SQLException {
		UUID origin = plan.evidence().authority().originInstallationId();
		for (var change : plan.recordChanges()) {
			if (change.kind() != SubjectRefreshChangeClassifier.ChangeKind.REMOVE) {
				continue;
			}
			long retiredLocalId;
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT local_id
					FROM portable_entity_retirements
					WHERE portable_id = ?
					""")) {
				statement.setString(1, change.key().recordId().toString());
				try (ResultSet rows = statement.executeQuery()) {
					if (!rows.next()) {
						throw new SQLException("Missing final Subject retirement evidence: " + change.key());
					}
					retiredLocalId = rows.getLong(1);
				}
			}
			requireRefreshRetirement(connection, change, retiredLocalId);

			// Retaining the originating alias prevents a later snapshot
			// from silently resurrecting the removed identity.
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT 1
					FROM portable_identity_aliases
					WHERE origin_installation_id = ?
					  AND entity_type = ?
					  AND canonical_portable_id = ?
					LIMIT 1
					""")) {
				statement.setString(1, origin.toString());
				statement.setString(2, change.key().recordType());
				statement.setString(3, change.key().recordId().toString());
				try (ResultSet rows = statement.executeQuery()) {
					if (!rows.next()) {
						throw new SQLException("Removed canonical identity has lost its origin alias: " + change.key());
					}
				}
			}
		}
	}

	/**
	 * Verifies approved logical asset coverage and actual private staged bytes.
	 */
	private void requireVerifiedRefreshAssets(SubjectRefreshPlan plan, StagedPackage staged, ExamWorkAssetIndex index)
			throws IOException {
		Map<String, SubjectRefreshChangeClassifier.AssetContent> expected = new HashMap<>();
		for (var change : plan.assetChanges()) {
			if (change.incoming() != null) {
				expected.put(change.managedPath(), change.incoming());
			}
		}
		Path assetDirectory = staged.directory().resolve("assets");
		if (!Files.isDirectory(staged.directory(), LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(assetDirectory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe refresh graph staging directory");
		}
		Map<String, StagedAsset> assets = new HashMap<>();
		for (StagedAsset asset : staged.assets()) {
			if (assets.putIfAbsent(asset.managedPath(), asset) != null) {
				throw new IOException("Duplicate staged refresh graph asset");
			}
			var approved = expected.get(asset.managedPath());
			if (approved == null || !approved.sha256().equals(asset.sha256()) || approved.size() != asset.size()) {
				throw new IOException("Unapproved staged refresh graph asset: " + asset.managedPath());
			}
			Path file = asset.stagedPath();
			if (!file.getParent().equals(assetDirectory) || !file.getFileName().toString().matches("[0-9]{5}\\.bin")
					|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != asset.size()) {
				throw new IOException("Missing or unsafe staged refresh graph asset: " + asset.managedPath());
			}
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException failure) {
				throw new IllegalStateException("SHA-256 is unavailable", failure);
			}

			// Stream the bytes instead of loading large PDFs into memory.
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				for (int count; (count = input.read(buffer)) != -1;) {
					digest.update(buffer, 0, count);
				}
			}
			String actual = HexFormat.of().formatHex(digest.digest());
			if (!actual.equals(asset.sha256())) {
				throw new IOException("Staged refresh graph asset checksum changed: " + asset.managedPath());
			}
		}
		if (!assets.keySet().equals(expected.keySet())) {
			throw new IOException("Staged refresh graph asset inventory is incomplete");
		}
		Set<String> represented = new HashSet<>();
		for (Reference reference : index.references()) {
			StagedAsset asset = assets.get(reference.managedPath());
			AssetKind kind;
			try {
				kind = SubjectImportAssetStager.kindFor(reference);
			} catch (IllegalArgumentException failure) {
				throw new IOException("Unsupported refresh asset kind", failure);
			}
			if (asset == null || asset.kind() != kind || !asset.archivePath().equals(reference.archivePath())
					|| !asset.sha256().equals(reference.sha256()) || asset.size() != reference.size()) {
				throw new IOException(
						"Refresh graph asset reference lacks verified staging: " + reference.managedPath());
			}
			represented.add(reference.managedPath());
		}
		if (!represented.equals(expected.keySet())) {
			throw new IOException("Decoded refresh asset index differs from approval");
		}
	}

	private void restoreAssignmentRevision(Connection connection, EntityType type, long localId, ExchangeRecord record)
			throws SQLException {
		var identity = identities.findByLocalId(connection, type, localId)
				.orElseThrow(() -> new SQLException("Refreshed assignment identity is missing"));
		if (!identity.portableId().equals(record.recordId())) {
			throw new SQLException("Refreshed record has an unexpected identity");
		}
		if (record.revisionId().equals(identity.revisionId())) {
			return;
		}

		// Ordinary writes have generated local revision tokens.
		// The refreshed read-only record must instead retain the
		// exact authoritative revision carried by the package.
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE portable_entity_identities
				SET revision_id = ?
				WHERE entity_type = ?
				  AND local_id = ?
				  AND portable_id = ?
				  AND retired = 0
				  AND NOT EXISTS (
				      SELECT 1
				      FROM portable_entity_retirements
				      WHERE revision_id = ?
				         OR portable_id = ?
				  )
				""")) {
			statement.setString(1, record.revisionId().toString());
			statement.setString(2, type.name());
			statement.setLong(3, localId);
			statement.setString(4, record.recordId().toString());
			statement.setString(5, record.revisionId().toString());
			statement.setString(6, record.recordId().toString());
			if (SqliteDirectChanges.executeUpdate(statement) != 1) {
				throw new SQLException("Cannot restore authoritative assignment revision");
			}
		}
	}

	/**
	 * Converts portable JSON to columns defined in the live SQLite schema.
	 */
	private Map<String, Object> rowValues(Connection connection, String table, JsonNode json, String ownerColumn,
			DecodedPackage decoded, StagedPackage staged, Map<Key, Long> ids, UUID ownerId, EntityType ownerType,
			boolean entity) throws SQLException, IOException {
		if (!json.isObject()) {
			throw new SQLException("Record or child row must be a JSON object: " + table);
		}
		Map<String, Object> values = new LinkedHashMap<>();
		Set<String> columns = columns(connection, table);
		for (var entry : json.properties()) {
			String column = entry.getKey();
			if (entity && TABLES.get(ownerType).children().stream().anyMatch(child -> child.name().equals(column))) {
				continue;
			}
			if (!columns.contains(column) || column.equals("id") || column.equals(ownerColumn)) {
				throw new SQLException("Unexpected imported field: " + table + "." + column);
			}
			if (entity && ((ownerType == EntityType.CURRICULUM_NODE && column.equals("parent_id"))
					|| (ownerType == EntityType.EXAM_BOOKLET && column.equals("pending_mcq_shared_context_id")))) {
				values.put(column, null);
				continue;
			}
			JsonNode value = entry.getValue();
			if (column.equals("image_png")) {
				values.put(column, blobFor(decoded, staged, ownerType, ownerId, json, table, value));
			} else if (REFERENCES.containsKey(column)) {
				values.put(column, value.isNull() ? null : requiredReference(ids, REFERENCES.get(column), value));
			} else {
				values.put(column, primitive(value, table + "." + column));
			}
		}
		if (entity && ownerType == EntityType.SOURCE_DOCUMENT) {
			String path = managedPath(decoded.index(), staged, "SOURCE_PDF", ownerType, ownerId);
			if (path == null) {
				throw new SQLException("Incoming SourceDocument has no verified source PDF");
			}
			values.put("relative_path", path);
		} else if (entity && ownerType == EntityType.SYLLABUS_VERSION) {
			values.put("source_pdf_path", managedPath(decoded.index(), staged, "SYLLABUS_PDF", ownerType, ownerId));
		}
		return values;
	}

	private ObjectNode scalarValues(EntityType type, ExchangeRecord record) {
		ObjectNode values = (ObjectNode) record.values();

		// Nested collections are refreshed independently
		// after all base entities and identities are resolved.
		for (Child child : TABLES.get(type).children()) {
			values.remove(child.name());
		}
		return values;
	}

	private void updateAssignmentBase(Connection connection, EntityType type, long localId, ExchangeRecord record,
			DecodedPackage decoded, StagedPackage staged, Map<Key, Long> ids) throws SQLException, IOException {
		Table table = TABLES.get(type);
		Map<String, Object> values = rowValues(connection, table.name(), record.values(), null, decoded, staged, ids,
				record.recordId(), type, true);
		if (values.isEmpty()) {
			throw new SQLException("Assignment refresh has no scalar values");
		}

		// Table and column names originate exclusively from the
		// existing allowlisted table-mapping implementation.
		List<String> columns = List.copyOf(values.keySet());
		String assignments = String.join(", ", columns.stream().map(column -> column + " = ?").toList());
		String sql = "UPDATE " + table.name() + " SET " + assignments + " WHERE id = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < columns.size(); i++) {
				Object value = values.get(columns.get(i));
				if (value instanceof byte[] bytes) {
					statement.setBytes(i + 1, bytes);
				} else {
					statement.setObject(i + 1, value);
				}
			}
			statement.setLong(columns.size() + 1, localId);
			if (SqliteDirectChanges.executeUpdate(statement) != 1) {
				throw new SQLException("Assignment refresh did not update exactly one " + type + " record");
			}
		}
	}

	/**
	 * Immutable validated package records and binary index.
	 *
	 * @param manifest independently verified manifest
	 * @param records  complete Subject domain record graph
	 * @param index    verified logical asset references
	 */
	public record DecodedPackage(ExamWorkManifest manifest, List<ExchangeRecord> records, ExamWorkAssetIndex index) {

		/** Validates decoded package coverage. */
		public DecodedPackage {
			Objects.requireNonNull(manifest, "manifest");
			records = List.copyOf(records);
			Objects.requireNonNull(index, "index");
		}
	}

	/**
	 * Lightweight validation for an already validated portable managed path.
	 */
	private static final class PathValidation {

		private static void requireSubjectPath(String path) throws SQLException {
			if (path == null || !path.startsWith("subjects/") || path.contains("..")) {
				throw new SQLException("Unsafe imported file location");
			}
		}
	}

	/**
	 * Internal allowlisted persisted table and its nested child collections.
	 */
	private record Table(String name, List<Child> children) {

		private Table(String name, Child... children) {
			this(name, List.of(children));
		}
	}

	/**
	 * Internal allowlisted child table and its SQLite foreign-key owner.
	 */
	private record Child(String name, String owner) {
	}
}
