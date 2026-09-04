package au.edu.eq.questionbank.repository.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteCurriculumImporterTest {

	@TempDir
	Path tempDir;

	@Test
	void commitsSuccessfulSyllabusImport() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		SyllabusVersion version = importer.importSyllabus("Chemistry", "2025", true);
		assertTrue(version.getId() > 0);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM syllabus_versions
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
	}

	@Test
	void importingNewCurrentVersionMakesPreviousVersionNotCurrent() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		importer.importSyllabus("Chemistry", "2019", true);
		importer.importSyllabus("Chemistry", "2025", true);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Subject chemistry = repository.findAllSubjects().get(0);
		List<SyllabusVersion> versions = repository.findVersionsForSubject(chemistry);
		assertEquals(2, versions.size());
		assertEquals("2019", versions.get(0).getName());
		assertFalse(versions.get(0).isCurrent());
		assertEquals("2025", versions.get(1).getName());
		assertTrue(versions.get(1).isCurrent());
	}

	@Test
	void reusesIdenticalSyllabusVersionWithoutNodes() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		CurriculumImportResult first = importer.importSyllabusWithResult("Chemistry", "2025", true);
		CurriculumImportResult second = importer.importSyllabusWithResult("Chemistry", "2025", true);
		assertTrue(first.imported());
		assertFalse(second.imported());
		assertEquals(first.syllabusVersion().getId(), second.syllabusVersion().getId());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    (SELECT COUNT(*) FROM subjects) AS subject_count,
						    (SELECT COUNT(*) FROM syllabus_versions) AS version_count,
						    (SELECT COUNT(*) FROM curriculum_nodes) AS node_count
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt("subject_count"));
			assertEquals(1, result.getInt("version_count"));
			assertEquals(0, result.getInt("node_count"));
		}
	}

	@Test
	void reusesSubjectWhenImportingAnotherSyllabusVersion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		importer.importSyllabus("Chemistry", "2019", false);
		importer.importSyllabus("Chemistry", "2025", true);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM subjects
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
	}

	@Test
	void rollsBackFailedSyllabusImport() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		assertThrows(IllegalArgumentException.class, () -> importer.importSyllabus("Chemistry", " ", true));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM subjects
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}
}
