package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void findsExistingSubjectByNameUsingCallerConnection() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject inserted = writer.insertSubject("Chemistry");
		try (Connection connection = database.openConnection()) {
			Subject found = writer.findSubjectByName(connection, "Chemistry");
			assertEquals(inserted, found);
			assertEquals("Chemistry", found.getName());
		}
	}

	@Test
	void insertsCurriculumUnit() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		Subject subject = writer.insertSubject("Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);

		CurriculumNode unit = writer.insertUnit(version, "1", "Unit 1", 0);

		assertTrue(unit.getId() > 0);
		assertEquals(version, unit.getSyllabusVersion());
		assertEquals(CurriculumLevel.UNIT, unit.getLevel());
		assertEquals("1", unit.getCode());
		assertEquals("Unit 1", unit.getName());
		assertEquals(0, unit.getDisplayOrder());
		assertNull(unit.getParent());
	}

	@Test
	void insertsDescriptorDirectlyUnderTopic() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		Subject subject = writer.insertSubject("Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);
		Unit unit = writer.insertUnit(version, "1", "Unit 1", 0);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic 1", 0);
		Descriptor descriptor = writer.insertDescriptor(topic, "1.1.a", "Students should be able to explain...", 0);
		assertEquals(CurriculumLevel.DESCRIPTOR, descriptor.getLevel());
		assertEquals(topic, descriptor.getParent());
		assertEquals("Students should be able to explain...", descriptor.getName());
	}

	@Test
	void insertsSubjectAndReturnsPersistentSubject() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Chemistry");
		assertTrue(subject.getId() > 0);
		assertEquals("Chemistry", subject.getName());
	}

	@Test
	void insertsSyllabusVersionForSubject() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		Subject subject = writer.insertSubject("Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);

		assertTrue(version.getId() > 0);
		assertEquals(subject, version.getSubject());
		assertEquals("2025", version.getName());
		assertTrue(version.isCurrent());
	}

	@Test
	void subjectInsertCanParticipateInCallerTransaction() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			Subject subject = writer.insertSubject(connection, "Chemistry");

			assertTrue(subject.getId() > 0);

			connection.rollback();
		}

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM subjects")) {

			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void subtopicAndDescriptorInsertsCanParticipateInCallerTransaction() throws Exception {

		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			Subject subject = writer.insertSubject(connection, "Chemistry");
			SyllabusVersion version = writer.insertSyllabusVersion(connection, subject, "2025", true);
			Unit unit = writer.insertUnit(connection, version, "1", "Unit 1", 0);
			Topic topic = writer.insertTopic(connection, unit, "1.1", "Topic 1", 0);
			Subtopic subtopic = writer.insertSubtopic(connection, topic, "1.1.1", "Subtopic 1", 0);
			Descriptor topicDescriptor = writer.insertDescriptor(connection, topic, "1.1.a", "Topic descriptor", 0);
			Descriptor subtopicDescriptor = writer.insertDescriptor(connection, subtopic, "1.1.1.a",
					"Subtopic descriptor", 0);

			assertTrue(subtopic.getId() > 0);
			assertTrue(topicDescriptor.getId() > 0);
			assertTrue(subtopicDescriptor.getId() > 0);

			connection.rollback();
		}

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM curriculum_nodes")) {

			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void syllabusVersionInsertCanParticipateInCallerTransaction() throws Exception {

		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			Subject subject = writer.insertSubject(connection, "Chemistry");
			SyllabusVersion version = writer.insertSyllabusVersion(connection, subject, "2025", true);
			assertTrue(version.getId() > 0);
			connection.rollback();
		}

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM syllabus_versions")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void topicInsertCanParticipateInCallerTransaction() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			Subject subject = writer.insertSubject(connection, "Chemistry");

			SyllabusVersion version = writer.insertSyllabusVersion(connection, subject, "2025", true);

			Unit unit = writer.insertUnit(connection, version, "1", "Unit 1", 0);

			Topic topic = writer.insertTopic(connection, unit, "1.1", "Topic 1", 0);

			assertTrue(topic.getId() > 0);

			connection.rollback();
		}

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM curriculum_nodes")) {

			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void unitInsertCanParticipateInCallerTransaction() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			Subject subject = writer.insertSubject(connection, "Chemistry");

			SyllabusVersion version = writer.insertSyllabusVersion(connection, subject, "2025", true);

			Unit unit = writer.insertUnit(connection, version, "1", "Unit 1", 0);

			assertTrue(unit.getId() > 0);

			connection.rollback();
		}

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM curriculum_nodes")) {

			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}
}
