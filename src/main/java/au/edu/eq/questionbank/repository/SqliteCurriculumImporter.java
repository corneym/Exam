package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import au.edu.eq.questionbank.importer.CurriculumImportRow;
import au.edu.eq.questionbank.importer.CurriculumNodeBuilder;
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
	 *
	 * @param subjectName the existing or new subject name
	 * @param syllabusName the new syllabus-version name
	 * @param current whether this version becomes current for the subject
	 * @return the stored syllabus version with its generated identifier
	 * @throws SQLException if the import cannot be stored or committed
	 * @throws IllegalArgumentException if a required name is blank
	 */
	public SyllabusVersion importSyllabus(String subjectName, String syllabusName, boolean current)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Subject subject = writer.findSubjectByName(connection, subjectName);
				if (subject == null) {
					subject = writer.insertSubject(connection, subjectName);
				}
				if (current) {
					clearCurrentVersion(connection, subject);
				}

				SyllabusVersion syllabusVersion = writer.insertSyllabusVersion(connection, subject, syllabusName,
						current);
				connection.commit();
				return syllabusVersion;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	/**
	 * Imports a syllabus version and a complete ordered hierarchy of curriculum
	 * rows. The version and all generated nodes are committed or rolled back
	 * together.
	 *
	 * @param subjectName the existing or new subject name
	 * @param syllabusName the new syllabus-version name
	 * @param current whether this version becomes current for the subject
	 * @param rows validated import rows from which the hierarchy is built
	 * @return the stored syllabus version with its generated identifier
	 * @throws SQLException if the import cannot be stored or committed
	 * @throws NullPointerException if {@code rows} is {@code null}
	 * @throws IllegalArgumentException if names or hierarchy rows are invalid
	 */
	public SyllabusVersion importSyllabus(String subjectName, String syllabusName, boolean current,
			List<CurriculumImportRow> rows) throws SQLException {

		if (rows == null) {
			throw new NullPointerException("rows");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Subject subject = writer.findSubjectByName(connection, subjectName);
				if (subject == null) {
					subject = writer.insertSubject(connection, subjectName);
				}
				if (current) {
					clearCurrentVersion(connection, subject);
				}
				SyllabusVersion syllabusVersion = writer.insertSyllabusVersion(connection, subject, syllabusName,
						current);
				CurriculumNodeBuilder nodeBuilder = new CurriculumNodeBuilder();
				AtomicLong temporaryIds = new AtomicLong(1);
				List<CurriculumNode> nodes = nodeBuilder.build(syllabusVersion, rows, temporaryIds::getAndIncrement);
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
							storedNode = writer.insertDescriptor(connection, topicParent, node.getCode(),
									node.getName(), node.getDisplayOrder());

						} else if (parent instanceof Subtopic subtopicParent) {
							storedNode = writer.insertDescriptor(connection, subtopicParent, node.getCode(),
									node.getName(), node.getDisplayOrder());
						} else {
							throw new IllegalStateException("Descriptor has invalid stored parent");
						}
						break;
					default:
						throw new IllegalStateException("Unsupported curriculum level: " + node.getLevel());
					}
					storedNodes.put(storedNode.getCode(), storedNode);
				}
				connection.commit();
				return syllabusVersion;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
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
