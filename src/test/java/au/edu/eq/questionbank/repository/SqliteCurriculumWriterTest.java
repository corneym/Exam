package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class SqliteCurriculumWriterTest {

	@TempDir
	Path tempDir;

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
		CurriculumNode unit = writer.insertUnit(version, "1", "Unit 1", 0);
		CurriculumNode topic = writer.insertChild(unit, CurriculumLevel.TOPIC, "1.1", "Topic 1", 0);
		CurriculumNode descriptor = writer.insertChild(topic, CurriculumLevel.DESCRIPTOR, "1.1.a",
				"Students should be able to explain...", 0);
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
}
