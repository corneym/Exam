package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableAliasRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
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

	/**
	 * Constructs a graph writer for the active master database.
	 *
	 * @param database authoritative SQLite database
	 */
	public SubjectImportGraphWriter(SqliteDatabase database) {
		Objects.requireNonNull(database, "database");
		aliases = new SqlitePortableAliasRepository(database);
		identities = new SqlitePortableIdentityRepository(database);
	}

	/**
	 * Inserts an approved Subject graph in the caller's open transaction.
	 *
	 * @param connection caller-owned transaction; commit and rollback are external
	 * @param plan       approved import plan
	 * @param staged     verified staged package with all approved binary content
	 * @param decoded    independently validated decoded Subject graph
	 * @return canonical identities resolved or inserted for accepted records
	 * @throws SQLException if persistence, relationships or aliasing fail
	 * @throws IOException  if staged binary content or paths cannot be verified
	 */
	public Map<Key, UUID> apply(Connection connection, SubjectImportApprovalPlan plan, StagedPackage staged,
			DecodedPackage decoded) throws SQLException, IOException {
		Objects.requireNonNull(connection, "connection");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Graph writer needs caller-owned transaction");
		}
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(decoded, "decoded");
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
			ordered.computeIfAbsent(EntityType.valueOf(record.recordType()), key -> new ArrayList<>()).add(record);
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
			aliases.approveAlias(connection, origin, type, original, entry.getValue(),
					clippedNote(action.justification()));
		}
		return Map.copyOf(canonicalIds);
	}

	/**
	 * Loads one staged package and validates plan coverage before any mutation.
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
