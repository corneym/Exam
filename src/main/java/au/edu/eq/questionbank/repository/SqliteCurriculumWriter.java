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

public final class SqliteCurriculumWriter {

	private final SqliteDatabase database;

	public SqliteCurriculumWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	public Descriptor insertDescriptor(Subtopic parent, String code, String name, int displayOrder)
			throws SQLException {

		long id = insertChild(parent, CurriculumLevel.DESCRIPTOR, code, name, displayOrder);

		return new Descriptor(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	public Descriptor insertDescriptor(Topic parent, String code, String name, int displayOrder) throws SQLException {

		long id = insertChild(parent, CurriculumLevel.DESCRIPTOR, code, name, displayOrder);

		return new Descriptor(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	public Subject insertSubject(String subjectName) throws SQLException {
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
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

	public Subtopic insertSubtopic(Topic parent, String code, String name, int displayOrder) throws SQLException {

		long id = insertChild(parent, CurriculumLevel.SUBTOPIC, code, name, displayOrder);

		return new Subtopic(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	public SyllabusVersion insertSyllabusVersion(Subject subject, String syllabusName, boolean current)
			throws SQLException {

		if (subject == null) {
			throw new NullPointerException("subject");
		}

		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("syllabusName must not be blank");
		}

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
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

	public Topic insertTopic(Unit parent, String code, String name, int displayOrder) throws SQLException {
		long id = insertChild(parent, CurriculumLevel.TOPIC, code, name, displayOrder);

		return new Topic(id, parent.getSyllabusVersion(), parent, code, name, displayOrder);
	}

	public Unit insertUnit(SyllabusVersion syllabusVersion, String code, String name, int displayOrder)
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

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
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

	private long insertChild(CurriculumNode parent, CurriculumLevel level, String code, String name, int displayOrder)
			throws SQLException {

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

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
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
}
