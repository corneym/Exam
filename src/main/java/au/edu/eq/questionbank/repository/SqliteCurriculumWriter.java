package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

/**
 * Inserts subjects, syllabus versions, and curriculum nodes into SQLite and
 * returns domain objects carrying their generated persistent identifiers.
 * <p>
 * Public operations own a short-lived connection. Package-private overloads
 * accept a caller-owned connection so multi-row imports can participate in one
 * transaction.
 */
public final class SqliteCurriculumWriter {

	private final SqliteDatabase database;

	/**
	 * Creates a writer for the supplied database.
	 *
	 * @param database the initialized question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteCurriculumWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Inserts a descriptor beneath a subtopic.
	 *
	 * @param parent the descriptor's subtopic parent
	 * @param code the non-blank curriculum code
	 * @param name the non-blank descriptor text
	 * @param displayOrder the non-negative order among siblings
	 * @return the stored descriptor
	 * @throws SQLException if the insert fails
	 * @throws NullPointerException if {@code parent} is {@code null}
	 * @throws IllegalArgumentException if {@code code} or {@code name} is null or
	 *                                  blank, or the order is negative
	 */
	public Descriptor insertDescriptor(Subtopic parent, String code, String name, int displayOrder)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertDescriptor(connection, parent, code, name, displayOrder);
		}
	}

	/**
	 * Inserts a descriptor directly beneath a topic.
	 *
	 * @param parent the descriptor's topic parent
	 * @param code the non-blank curriculum code
	 * @param name the non-blank descriptor text
	 * @param displayOrder the non-negative order among siblings
	 * @return the stored descriptor
	 * @throws SQLException if the insert fails
	 * @throws NullPointerException if {@code parent} is {@code null}
	 * @throws IllegalArgumentException if {@code code} or {@code name} is null or
	 *                                  blank, or the order is negative
	 */
	public Descriptor insertDescriptor(Topic parent, String code, String name, int displayOrder) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertDescriptor(connection, parent, code, name, displayOrder);
		}
	}

	/**
	 * Inserts a subject.
	 *
	 * @param subjectName the non-blank subject name
	 * @return the stored subject
	 * @throws SQLException if the insert fails, including a uniqueness violation
	 * @throws IllegalArgumentException if {@code subjectName} is null or blank
	 */
	public Subject insertSubject(String subjectName) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertSubject(connection, subjectName);
		}
	}

	/**
	 * Inserts a subtopic beneath a topic.
	 *
	 * @param parent the subtopic's topic parent
	 * @param code the non-blank curriculum code
	 * @param name the non-blank subtopic name
	 * @param displayOrder the non-negative order among siblings
	 * @return the stored subtopic
	 * @throws SQLException if the insert fails
	 * @throws NullPointerException if {@code parent} is {@code null}
	 * @throws IllegalArgumentException if {@code code} or {@code name} is null or
	 *                                  blank, or the order is negative
	 */
	public Subtopic insertSubtopic(Topic parent, String code, String name, int displayOrder) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertSubtopic(connection, parent, code, name, displayOrder);
		}
	}

	/**
	 * Inserts a named syllabus version for a subject.
	 *
	 * @param subject the owning subject
	 * @param syllabusName the non-blank version name
	 * @param current whether the version is current
	 * @return the stored syllabus version
	 * @throws SQLException if the insert fails
	 * @throws NullPointerException if {@code subject} is {@code null}
	 * @throws IllegalArgumentException if {@code syllabusName} is null or blank
	 */
	public SyllabusVersion insertSyllabusVersion(Subject subject, String syllabusName, boolean current)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertSyllabusVersion(connection, subject, syllabusName, current);
		}
	}

	/**
	 * Inserts a topic beneath a unit.
	 *
	 * @param parent the topic's unit parent
	 * @param code the non-blank curriculum code
	 * @param name the non-blank topic name
	 * @param displayOrder the non-negative order among siblings
	 * @return the stored topic
	 * @throws SQLException if the insert fails
	 * @throws NullPointerException if {@code parent} is {@code null}
	 * @throws IllegalArgumentException if {@code code} or {@code name} is null or
	 *                                  blank, or the order is negative
	 */
	public Topic insertTopic(Unit parent, String code, String name, int displayOrder) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertTopic(connection, parent, code, name, displayOrder);
		}
	}

	/**
	 * Inserts a root unit in a syllabus version.
	 *
	 * @param syllabusVersion the containing syllabus version
	 * @param code the non-blank curriculum code
	 * @param name the non-blank unit name
	 * @param displayOrder the non-negative order among root units
	 * @return the stored unit
	 * @throws SQLException if the insert fails
	 * @throws NullPointerException if {@code syllabusVersion} is {@code null}
	 * @throws IllegalArgumentException if {@code code} or {@code name} is null or
	 *                                  blank, or the order is negative
	 */
	public Unit insertUnit(SyllabusVersion syllabusVersion, String code, String name, int displayOrder)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertUnit(connection, syllabusVersion, code, name, displayOrder);
		}
	}

	private long insertChild(Connection connection, CurriculumNode parent, CurriculumLevel level, String code,
			String name, int displayOrder) throws SQLException {

		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (parent == null) {
			throw new NullPointerException("parent");
		}
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (displayOrder < 0) {
			throw new IllegalArgumentException("displayOrder must not be negative");
		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO curriculum_nodes
					(syllabus_version_id, parent_id,
					 curriculum_code, curriculum_name,
					 curriculum_level, display_order)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {

			statement.setLong(1, parent.getSyllabusVersion().getId());

			statement.setLong(2, parent.getId());

			statement.setString(3, code);
			statement.setString(4, name);
			statement.setString(5, level.name());
			statement.setInt(6, displayOrder);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Curriculum node insert did not return an id");
				}

				return result.getLong("id");
			}
		}
	}

	Subject findSubjectByName(Connection connection, String subjectName) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, subject_name
				FROM subjects
				WHERE subject_name = ?
				""")) {
			statement.setString(1, subjectName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				return new Subject(result.getLong("id"), result.getString("subject_name"));
			}
		}
	}

	Descriptor insertDescriptor(Connection connection, Subtopic parent, String code, String name, int displayOrder)
			throws SQLException {
		long id = insertChild(connection, parent, CurriculumLevel.DESCRIPTOR, code, name, displayOrder);
		return new Descriptor(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	Descriptor insertDescriptor(Connection connection, Topic parent, String code, String name, int displayOrder)
			throws SQLException {
		long id = insertChild(connection, parent, CurriculumLevel.DESCRIPTOR, code, name, displayOrder);
		return new Descriptor(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	Subject insertSubject(Connection connection, String subjectName) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO subjects (subject_name)
				VALUES (?)
				RETURNING id
				""")) {

			statement.setString(1, subjectName);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Subject insert did not return an id");
				}

				return new Subject(result.getLong("id"), subjectName);
			}
		}
	}

	Subtopic insertSubtopic(Connection connection, Topic parent, String code, String name, int displayOrder)
			throws SQLException {
		long id = insertChild(connection, parent, CurriculumLevel.SUBTOPIC, code, name, displayOrder);
		return new Subtopic(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	SyllabusVersion insertSyllabusVersion(Connection connection, Subject subject, String syllabusName, boolean current)
			throws SQLException {

		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("syllabusName must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO syllabus_versions
					(subject_id, syllabus_name, is_current)
				VALUES (?, ?, ?)
				RETURNING id
				""")) {

			statement.setLong(1, subject.getId());
			statement.setString(2, syllabusName);
			statement.setInt(3, current ? 1 : 0);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Syllabus version insert did not return an id");
				}

				return new SyllabusVersion(result.getLong("id"), subject, syllabusName, current);
			}
		}
	}

	Topic insertTopic(Connection connection, Unit parent, String code, String name, int displayOrder)
			throws SQLException {
		long id = insertChild(connection, parent, CurriculumLevel.TOPIC, code, name, displayOrder);
		return new Topic(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	Unit insertUnit(Connection connection, SyllabusVersion syllabusVersion, String code, String name, int displayOrder)
			throws SQLException {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (displayOrder < 0) {
			throw new IllegalArgumentException("displayOrder must not be negative");
		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO curriculum_nodes
					(syllabus_version_id, parent_id,
					 curriculum_code, curriculum_name,
					 curriculum_level, display_order)
				VALUES (?, NULL, ?, ?, 'UNIT', ?)
				RETURNING id
				""")) {

			statement.setLong(1, syllabusVersion.getId());
			statement.setString(2, code);
			statement.setString(3, name);
			statement.setInt(4, displayOrder);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Curriculum unit insert did not return an id");
				}

				return new Unit(result.getLong("id"), syllabusVersion, code, name, displayOrder);
			}
		}
	}
}
