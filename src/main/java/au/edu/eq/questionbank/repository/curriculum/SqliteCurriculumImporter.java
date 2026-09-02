package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.importer.curriculum.CurriculumNodeBuilder;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

/**
 * Imports a syllabus version and its optional curriculum hierarchy into SQLite
 * as one transaction. When a new version is marked current, any existing
 * current version for the same subject is cleared within that transaction.
 */
public final class SqliteCurriculumImporter {
	private record CurriculumNodeDefinition(String code, String name, String level, String parentCode,
			int displayOrder) {
	}

	private final SqliteDatabase database;
	private final SqliteCurriculumWriter writer;

	/**
	 * Creates an importer using the supplied database and low-level writer.
	 *
	 * @param database the database in which imports are transacted
	 * @param writer   the writer used for individual curriculum inserts
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public SqliteCurriculumImporter(SqliteDatabase database, SqliteCurriculumWriter writer) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (writer == null) {
			throw new NullPointerException("writer");
		}
		this.database = database;
		this.writer = writer;
	}

	/**
	 * Imports a syllabus version without curriculum nodes.
	 * <p>
	 * An identical existing version is returned without modification.
	 *
	 * @param subjectName the existing or new subject name
	 * @param syllabusName the syllabus-version name
	 * @param current whether this version is current for the subject
	 * @return the newly stored or already matching syllabus version
	 * @throws SQLException if the import cannot be stored or committed
	 * @throws CurriculumImportConflictException if the named version exists with
	 *                                           nodes or a different current status
	 * @throws IllegalArgumentException if a required name is blank
	 */
	public SyllabusVersion importSyllabus(String subjectName, String syllabusName, boolean current)
			throws SQLException {
		return importSyllabusWithResult(subjectName, syllabusName, current).syllabusVersion();
	}

	/**
	 * Imports a syllabus version without curriculum nodes and reports whether it
	 * was newly stored or already present with identical state.
	 *
	 * @param subjectName  the existing or new subject name
	 * @param syllabusName the syllabus-version name
	 * @param current      whether this version is current for the subject
	 * @return the import result
	 * @throws SQLException if the import cannot be read, stored, or committed
	 * @throws CurriculumImportConflictException if the named version exists with
	 *                                           nodes or a different current status
	 * @throws IllegalArgumentException if a required name is blank
	 */
	public CurriculumImportResult importSyllabusWithResult(String subjectName, String syllabusName, boolean current)
			throws SQLException {
		return importSyllabusWithResult(subjectName, syllabusName, current, List.of());
	}

	private Map<String, CurriculumNodeDefinition> definitions(List<CurriculumNode> nodes) {
		Map<String, CurriculumNodeDefinition> definitions = new LinkedHashMap<>();
		for (CurriculumNode node : nodes) {
			CurriculumNode parent = node.getParent();
			String parentCode = parent == null ? null : parent.getCode();
			CurriculumNodeDefinition definition = new CurriculumNodeDefinition(node.getCode(), node.getName(),
					node.getLevel().name(), parentCode, node.getDisplayOrder());
			definitions.put(node.getCode(), definition);
		}
		return definitions;
	}

	private SyllabusVersion findSyllabusVersion(Connection connection, Subject subject, String syllabusName)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, is_current
				FROM syllabus_versions
				WHERE subject_id = ?
				  AND syllabus_name = ?
				""")) {
			statement.setLong(1, subject.getId());
			statement.setString(2, syllabusName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				return new SyllabusVersion(result.getLong("id"), subject, syllabusName,
						result.getInt("is_current") == 1);
			}
		}
	}

	private Map<String, CurriculumNodeDefinition> readStoredDefinitions(Connection connection,
			SyllabusVersion syllabusVersion) throws SQLException {
		Map<String, CurriculumNodeDefinition> definitions = new LinkedHashMap<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    node.curriculum_code,
				    node.curriculum_name,
				    node.curriculum_level,
				    node.display_order,
				    parent.curriculum_code AS parent_code,
				    parent.syllabus_version_id AS parent_syllabus_version_id
				FROM curriculum_nodes node
				LEFT JOIN curriculum_nodes parent
				    ON parent.id = node.parent_id
				WHERE node.syllabus_version_id = ?
				ORDER BY node.curriculum_code
				""")) {
			statement.setLong(1, syllabusVersion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					String parentCode = result.getString("parent_code");
					long parentSyllabusVersionId = result.getLong("parent_syllabus_version_id");
					if (!result.wasNull() && parentSyllabusVersionId != syllabusVersion.getId()) {
						throw conflict(syllabusVersion, "has a parent in another syllabus version");
					}
					CurriculumNodeDefinition definition = new CurriculumNodeDefinition(
							result.getString("curriculum_code"), result.getString("curriculum_name"),
							result.getString("curriculum_level"), parentCode, result.getInt("display_order"));
					definitions.put(definition.code(), definition);
				}
			}
		}
		return definitions;
	}

	private CurriculumImportConflictException conflict(SyllabusVersion syllabusVersion, String detail) {
		return new CurriculumImportConflictException(syllabusVersion.getSubject().getName() + " "
				+ syllabusVersion.getName() + " is already imported with different curriculum data: " + detail + ".");
	}

	private void verifyExistingImport(Connection connection, SyllabusVersion existingVersion, boolean current,
			List<CurriculumNode> incomingNodes) throws SQLException {
		if (existingVersion.isCurrent() != current) {
			String storedStatus = existingVersion.isCurrent() ? "current" : "historical";
			String requestedStatus = current ? "current" : "historical";
			throw new CurriculumImportConflictException(existingVersion.getSubject().getName() + " "
					+ existingVersion.getName() + " is already imported as " + storedStatus
					+ " and cannot be re-imported as " + requestedStatus + ".");
		}

		Map<String, CurriculumNodeDefinition> stored = readStoredDefinitions(connection, existingVersion);
		Map<String, CurriculumNodeDefinition> incoming = definitions(incomingNodes);
		for (String code : new TreeSet<>(incoming.keySet())) {
			if (!stored.containsKey(code)) {
				throw conflict(existingVersion, "the incoming hierarchy adds curriculum code " + code);
			}
			if (!incoming.get(code).equals(stored.get(code))) {
				throw conflict(existingVersion, "curriculum code " + code + " differs");
			}
		}
		for (String code : new TreeSet<>(stored.keySet())) {
			if (!incoming.containsKey(code)) {
				throw conflict(existingVersion, "the incoming hierarchy omits curriculum code " + code);
			}
		}
	}

	private List<CurriculumNode> buildNodes(SyllabusVersion syllabusVersion, List<CurriculumImportRow> rows) {
		CurriculumNodeBuilder nodeBuilder = new CurriculumNodeBuilder();
		AtomicLong temporaryIds = new AtomicLong(1);
		return nodeBuilder.build(syllabusVersion, rows, temporaryIds::getAndIncrement);
	}

	private CurriculumImportResult importSyllabusWithResult(Connection connection, String subjectName,
			String syllabusName, boolean current, List<CurriculumImportRow> rows) throws SQLException {
		Subject subject = writer.findSubjectByName(connection, subjectName);
		if (subject != null) {
			SyllabusVersion existingVersion = findSyllabusVersion(connection, subject, syllabusName);
			if (existingVersion != null) {
				List<CurriculumNode> incomingNodes = buildNodes(existingVersion, rows);
				verifyExistingImport(connection, existingVersion, current, incomingNodes);
				return new CurriculumImportResult(existingVersion, false);
			}
		} else {
			subject = writer.insertSubject(connection, subjectName);
		}

		if (current) {
			clearCurrentVersion(connection, subject);
		}
		SyllabusVersion syllabusVersion = writer.insertSyllabusVersion(connection, subject, syllabusName, current);
		List<CurriculumNode> nodes = buildNodes(syllabusVersion, rows);
		insertNodes(connection, syllabusVersion, nodes);
		return new CurriculumImportResult(syllabusVersion, true);
	}

	private void insertNodes(Connection connection, SyllabusVersion syllabusVersion, List<CurriculumNode> nodes)
			throws SQLException {
		Map<String, CurriculumNode> storedNodes = new HashMap<>();
		for (CurriculumNode node : nodes) {
			CurriculumNode storedNode;
			switch (node.getLevel()) {
			case UNIT:
				storedNode = writer.insertUnit(connection, syllabusVersion, node.getCode(), node.getName(),
						node.getDisplayOrder());
				break;
			case TOPIC:
				Unit unit = (Unit) storedNodes.get(node.getParent().getCode());

				storedNode = writer.insertTopic(connection, unit, node.getCode(), node.getName(),
						node.getDisplayOrder());
				break;
			case SUBTOPIC:
				Topic topic = (Topic) storedNodes.get(node.getParent().getCode());

				storedNode = writer.insertSubtopic(connection, topic, node.getCode(), node.getName(),
						node.getDisplayOrder());
				break;
			case DESCRIPTOR:
				CurriculumNode parent = storedNodes.get(node.getParent().getCode());

				if (parent instanceof Topic topicParent) {
					storedNode = writer.insertDescriptor(connection, topicParent, node.getCode(), node.getName(),
							node.getDisplayOrder());

				} else if (parent instanceof Subtopic subtopicParent) {
					storedNode = writer.insertDescriptor(connection, subtopicParent, node.getCode(), node.getName(),
							node.getDisplayOrder());
				} else {
					throw new IllegalStateException("Descriptor has invalid stored parent");
				}
				break;
			default:
				throw new IllegalStateException("Unsupported curriculum level: " + node.getLevel());
			}
			storedNodes.put(storedNode.getCode(), storedNode);
		}
	}

	private CurriculumImportResult transactImport(String subjectName, String syllabusName, boolean current,
			List<CurriculumImportRow> rows) throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				CurriculumImportResult result = importSyllabusWithResult(connection, subjectName, syllabusName, current,
						rows);
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
	}

	/**
	 * Imports a syllabus version and a complete ordered hierarchy of curriculum
	 * rows. The version and all generated nodes are committed or rolled back
	 * together. An identical existing hierarchy is returned without modification.
	 *
	 * @param subjectName the existing or new subject name
	 * @param syllabusName the syllabus-version name
	 * @param current whether this version is current for the subject
	 * @param rows validated import rows from which the hierarchy is built
	 * @return the newly stored or already matching syllabus version
	 * @throws SQLException if the import cannot be stored or committed
	 * @throws NullPointerException if {@code rows} is {@code null}
	 * @throws CurriculumImportConflictException if the named version exists with
	 *                                           different hierarchy data or current
	 *                                           status
	 * @throws IllegalArgumentException if names or hierarchy rows are invalid
	 */
	public SyllabusVersion importSyllabus(String subjectName, String syllabusName, boolean current,
			List<CurriculumImportRow> rows) throws SQLException {
		return importSyllabusWithResult(subjectName, syllabusName, current, rows).syllabusVersion();
	}

	/**
	 * Imports a syllabus hierarchy or reuses the matching stored hierarchy without
	 * writing duplicate rows.
	 *
	 * @param subjectName the existing or new subject name
	 * @param syllabusName the syllabus-version name
	 * @param current whether this version is current for the subject
	 * @param rows validated import rows from which the hierarchy is built
	 * @return whether the hierarchy was newly imported or already present
	 * @throws SQLException if the import cannot be read, stored, or committed
	 * @throws NullPointerException if {@code rows} is {@code null}
	 * @throws CurriculumImportConflictException if the named version exists with
	 *                                           different hierarchy data or current
	 *                                           status
	 * @throws IllegalArgumentException if names or hierarchy rows are invalid
	 */
	public CurriculumImportResult importSyllabusWithResult(String subjectName, String syllabusName, boolean current,
			List<CurriculumImportRow> rows) throws SQLException {

		if (rows == null) {
			throw new NullPointerException("rows");
		}
		return transactImport(subjectName, syllabusName, current, rows);
	}

	private void clearCurrentVersion(Connection connection, Subject subject) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE syllabus_versions
				SET is_current = 0
				WHERE subject_id = ?
				  AND is_current = 1
				""")) {

			statement.setLong(1, subject.getId());
			statement.executeUpdate();
		}
	}
}
