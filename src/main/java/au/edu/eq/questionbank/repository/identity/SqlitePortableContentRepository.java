package au.edu.eq.questionbank.repository.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.EqworkRecord;

/**
 * Reads canonical portable content from the current SQLite entity graph.
 *
 * <p>
 * Local integer keys are replaced with portable UUID references. Managed
 * filesystem paths are excluded. Ordered child collections retain their
 * authoritative order, while unordered collections are canonically sorted.
 *
 * <p>
 * Content fingerprints do not contain revision tokens. An unchanged fingerprint
 * therefore does not establish unchanged causal history.
 */
public final class SqlitePortableContentRepository {

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
	private static final Map<EntityType, EntityTable> ENTITIES = Map.ofEntries(
			Map.entry(EntityType.SUBJECT, new EntityTable("subjects")),
			Map.entry(EntityType.SYLLABUS_VERSION, new EntityTable("syllabus_versions")),
			Map.entry(EntityType.CURRICULUM_NODE,
					new EntityTable("curriculum_nodes",
							new ChildTable("curriculum_mapping_reviews", "source_node_id", "target_syllabus_version_id",
									false))),
			Map.entry(EntityType.EXAM_PROVIDER, new EntityTable("exam_providers")),
			Map.entry(EntityType.SOURCE_DOCUMENT, new EntityTable("source_documents")),
			Map.entry(EntityType.EXAM, new EntityTable("exams")),
			Map.entry(EntityType.EXAM_BOOKLET,
					new EntityTable("exam_booklets",
							new ChildTable("booklet_applicability_reviews", "booklet_id", "booklet_id", false))),
			Map.entry(EntityType.QUESTION, new EntityTable("questions",
					new ChildTable("question_regions", "question_id", "region_order", true),
					new ChildTable("question_images", "question_id", "id", false),
					new ChildTable("question_content_parts", "question_id", "content_order", true),
					new ChildTable("question_output_exclusions", "question_id", "current_curriculum_node_id", false))),
			Map.entry(EntityType.ANSWER_FILE, new EntityTable("answer_files")),
			Map.entry(EntityType.ANSWER,
					new EntityTable("answers", new ChildTable("answer_regions", "answer_id", "region_order", true))),
			Map.entry(EntityType.SOURCE_QUESTION, new EntityTable("source_questions")),
			Map.entry(EntityType.SHARED_QUESTION_CONTEXT, new EntityTable("shared_question_contexts",
					new ChildTable("shared_question_context_regions", "shared_context_id", "region_order", true),
					new ChildTable("shared_question_context_images", "shared_context_id", "image_order", true),
					new ChildTable("shared_question_context_content_parts", "shared_context_id", "content_order",
							true))),
			Map.entry(EntityType.CURRICULUM_MAPPING, new EntityTable("curriculum_mappings")),
			Map.entry(EntityType.QUESTION_IMAGE, new EntityTable("question_images")));
	private final SqliteDatabase database;
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates a read-only portable content mapping boundary.
	 *
	 * @param database initialised database
	 */
	public SqlitePortableContentRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Reads a portable record and its deterministic content fingerprint.
	 *
	 * @param type    type of the persisted entity
	 * @param localId positive SQLite-local ID
	 * @return canonical portable record and SHA-256 digest
	 * @throws SQLException if the record or a referenced identity is missing
	 */
	public Snapshot snapshot(EntityType type, long localId) throws SQLException {
		Objects.requireNonNull(type, "type");
		if (localId < 1) {
			throw new IllegalArgumentException("localId must be positive");
		}
		EntityTable entity = ENTITIES.get(type);
		if (entity == null) {
			throw new IllegalArgumentException("Unsupported portable entity type: " + type);
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				UUID[] identity = identity(connection, type, localId);
				Map<String, Object> values = readEntity(connection, entity.tableName(), localId);
				for (ChildTable child : entity.children()) {
					values.put(child.tableName(), readChildren(connection, child, localId));
				}
				JsonNode json = mapper.valueToTree(values);
				EqworkRecord record = new EqworkRecord(EqworkRecord.CURRENT_RECORD_VERSION, type.name(), identity[0],
						identity[1], null, json);
				String fingerprint = fingerprint(type, json);
				connection.commit();
				return new Snapshot(record, fingerprint);
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		}
	}

	private String canonicalJson(Map<String, Object> values) throws SQLException {
		try {
			return mapper.writeValueAsString(values);
		} catch (Exception exception) {
			throw new SQLException("Could not serialise canonical portable values", exception);
		}
	}

	private String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String fingerprint(EntityType type, JsonNode values) throws SQLException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");

			// Explicit domain separation permits future fingerprint
			// versions without changing existing entity UUIDs.
			digest.update("eqwork-content-v1\n".getBytes(StandardCharsets.UTF_8));
			digest.update(type.name().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			digest.update(mapper.writeValueAsBytes(values));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		} catch (Exception exception) {
			throw new SQLException("Could not calculate content fingerprint", exception);
		}
	}

	private UUID[] identity(Connection connection, EntityType type, long localId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT portable_id, revision_id
				FROM portable_entity_identities
				WHERE entity_type = ?
				  AND local_id = ?
				  AND retired = 0
				""")) {
			statement.setString(1, type.name());
			statement.setLong(2, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Missing active portable identity: " + type + ":" + localId);
				}
				UUID portableId = UUID.fromString(result.getString("portable_id"));
				UUID revisionId = UUID.fromString(result.getString("revision_id"));
				if (result.next()) {
					throw new SQLException("Duplicate portable identity");
				}
				return new UUID[] { portableId, revisionId };
			}
		}
	}

	private Object portableValue(Connection connection, String column, Object original) throws SQLException {
		if (original == null) {
			return null;
		}
		EntityType referenceType = REFERENCES.get(column);
		if (referenceType != null) {
			if (!(original instanceof Number number)) {
				throw new SQLException("Non-numeric local reference: " + column);
			}
			return identity(connection, referenceType, number.longValue())[0].toString();
		}
		if (original instanceof byte[] bytes) {

			// Binary data is represented by its content digest, not by
			// a machine-local file name or base64 serialisation.
			return digest(bytes);
		}
		return original;
	}

	private List<Map<String, Object>> readChildren(Connection connection, ChildTable child, long localId)
			throws SQLException {
		String sql = "SELECT * FROM " + child.tableName() + " WHERE " + child.ownerColumn() + " = ? ORDER BY "
				+ child.orderColumn();
		List<Map<String, Object>> children = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					children.add(readRow(connection, result, child.ownerColumn()));
				}
			}
		}
		if (!child.ordered()) {

			// Review and exclusion collections are sets. Their order
			// must not depend on SQLite-local integer IDs.
			List<Map<String, Object>> sorted = new ArrayList<>(children);
			Map<Map<String, Object>, String> keys = new java.util.IdentityHashMap<>();
			for (Map<String, Object> value : sorted) {
				keys.put(value, canonicalJson(value));
			}
			sorted.sort(Comparator.comparing(keys::get));
			return List.copyOf(sorted);
		}
		return List.copyOf(children);
	}

	private Map<String, Object> readEntity(Connection connection, String tableName, long localId) throws SQLException {
		String sql = "SELECT * FROM " + tableName + " WHERE id = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Missing persisted entity: " + tableName + ":" + localId);
				}
				Map<String, Object> values = readRow(connection, result, null);
				if (result.next()) {
					throw new SQLException("Duplicate local primary key in " + tableName);
				}
				return values;
			}
		}
	}

	private Map<String, Object> readRow(Connection connection, ResultSet result, String ownerColumn)
			throws SQLException {
		Map<String, Object> values = new TreeMap<>();
		ResultSetMetaData metadata = result.getMetaData();
		for (int index = 1; index <= metadata.getColumnCount(); index++) {
			String column = metadata.getColumnName(index);

			// id is the local primary key; ownerColumn is represented
			// by the enclosing record's portable UUID.
			if (column.equals("id") || column.equals(ownerColumn) || column.endsWith("_path")
					|| column.equals("relative_path")) {
				continue;
			}
			values.put(column, portableValue(connection, column, result.getObject(index)));
		}
		return values;
	}

	/**
	 * A portable record and its canonical content fingerprint.
	 *
	 * @param record        portable record with its causal revision
	 * @param contentSha256 lower-case SHA-256 content digest
	 */
	public record Snapshot(EqworkRecord record, String contentSha256) {

		/**
		 * Validates the snapshot.
		 */
		public Snapshot {
			Objects.requireNonNull(record, "record");
			if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid content SHA-256");
			}
		}
	}

	private record ChildTable(String tableName, String ownerColumn, String orderColumn, boolean ordered) {
	}

	private record EntityTable(String tableName, List<ChildTable> children) {

		private EntityTable(String tableName, ChildTable... children) {
			this(tableName, List.of(children));
		}
	}
}
