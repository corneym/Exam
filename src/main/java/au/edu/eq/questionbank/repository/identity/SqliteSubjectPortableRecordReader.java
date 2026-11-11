package au.edu.eq.questionbank.repository.identity;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.EqworkRecord;

/**
 * Converts an immutable Subject graph selection into portable current-state
 * records and verifies the closure of all supported entity relationships.
 *
 * <p>
 * The reader uses the canonical content representation introduced for
 * persistent portable identities in schema versions 25 and 26. Each exported
 * record retains its durable entity UUID and causal revision UUID.
 * Database-local integer identifiers are converted to portable references.
 *
 * <p>
 * Every supported relationship is checked against the selected Subject graph. A
 * relationship to an entity that is absent from the graph causes an explicit
 * failure rather than producing an incomplete package.
 *
 * <p>
 * This class neither exports filesystem paths nor copies binary content. Binary
 * assets, including SQLite-stored PNG images, are handled by the separate
 * Subject asset collector.
 *
 * <p>
 * The supplied database path must identify an immutable, consistent SQLite
 * snapshot rather than the application's live database.
 *
 * @see SqliteSubjectGraphReader
 * @see SqlitePortableContentRepository
 * @see SqlitePortableIdentityRepository
 * @see EqworkRecord
 */
public final class SqliteSubjectPortableRecordReader {

	private static final Map<String, EntityType> REFERENCE_FIELDS = Map.ofEntries(
			Map.entry("subject_id", EntityType.SUBJECT), Map.entry("syllabus_version_id", EntityType.SYLLABUS_VERSION),
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
	private final SqlitePortableContentRepository content;
	private final SqlitePortableIdentityRepository identities;

	/**
	 * Constructs a read-only portable record reader for an immutable SQLite
	 * snapshot.
	 *
	 * @param stableSnapshotPath existing, consistent SQLite snapshot path
	 * @throws NullPointerException if the supplied path is null
	 */
	public SqliteSubjectPortableRecordReader(Path stableSnapshotPath) {
		Objects.requireNonNull(stableSnapshotPath, "stableSnapshotPath");
		SqliteDatabase database = SqliteDatabase.readOnly(stableSnapshotPath);
		content = new SqlitePortableContentRepository(database);
		identities = new SqlitePortableIdentityRepository(database);
	}

	/**
	 * Retrieves every selected entity as a portable record and verifies that all
	 * supported references point to records within the selected graph.
	 *
	 * <p>
	 * Entity UUIDs and revision UUIDs are preserved. Child collections retain their
	 * original authoritative order. The outer record collection is sorted by entity
	 * type and portable UUID for canonical JSONL serialisation.
	 *
	 * @param graph complete Subject selection obtained from the same snapshot
	 * @return immutable list of portable current-state records
	 * @throws NullPointerException if the graph is null
	 * @throws SQLException         if identity resolution fails, records cannot be
	 *                              read, or the selected Subject graph has missing
	 *                              references
	 */
	public List<EqworkRecord> read(SubjectGraph graph) throws SQLException {
		Objects.requireNonNull(graph, "graph");
		Map<EntityType, Set<UUID>> selected = new EnumMap<>(EntityType.class);
		for (EntityType type : EntityType.values()) {
			Set<UUID> selectedIds = new HashSet<>();
			for (long localId : graph.ids(type)) {
				UUID portableId = identities.requireByLocalId(type, localId).portableId();
				if (!selectedIds.add(portableId)) {
					throw new SQLException("Duplicate selected portable identity: " + type + ":" + portableId);
				}
			}
			selected.put(type, Set.copyOf(selectedIds));
		}
		List<EqworkRecord> records = new ArrayList<>();
		for (EntityType type : EntityType.values()) {
			for (long localId : graph.ids(type)) {
				EqworkRecord record = content.snapshot(type, localId).record();
				if (!selected.get(type).contains(record.recordId())) {
					throw new SQLException("Portable identity changed during export: " + type + ":" + localId);
				}
				requireReferenceClosure(record.values(), selected, type.name() + ":" + record.recordId());
				records.add(record);
			}
		}
		records.sort(
				Comparator.comparing(EqworkRecord::recordType).thenComparing(record -> record.recordId().toString()));
		return List.copyOf(records);
	}

	/**
	 * Recursively checks all recognised portable references within an entity's JSON
	 * content and ordered child collections.
	 *
	 * @param node     current JSON object, array or leaf
	 * @param selected entity UUIDs belonging to the selected Subject
	 * @param location descriptive location for error reporting
	 * @throws SQLException if a reference is malformed or points to an entity
	 *                      missing from the Subject selection
	 */
	private void requireReferenceClosure(JsonNode node, Map<EntityType, Set<UUID>> selected, String location)
			throws SQLException {
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				requireReferenceClosure(node.get(index), selected, location + "[" + index + "]");
			}
			return;
		}
		if (!node.isObject()) {
			return;
		}
		for (Map.Entry<String, JsonNode> field : node.properties()) {
			String name = field.getKey();
			JsonNode value = field.getValue();
			EntityType type = REFERENCE_FIELDS.get(name);
			if (type != null && !value.isNull()) {
				UUID reference;
				try {
					if (!value.isTextual()) {
						throw new IllegalArgumentException("Reference is not a UUID string");
					}
					reference = UUID.fromString(value.asText());
					if (!reference.toString().equals(value.asText())) {
						throw new IllegalArgumentException("Reference UUID is not canonical");
					}
				} catch (IllegalArgumentException exception) {
					throw new SQLException("Invalid portable reference: " + location + "." + name, exception);
				}
				if (!selected.get(type).contains(reference)) {
					throw new SQLException("Incomplete Subject export: " + location + "." + name
							+ " references missing " + type + " " + reference);
				}
			}
			requireReferenceClosure(value, selected, location + "." + name);
		}
	}
}
