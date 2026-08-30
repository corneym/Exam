package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

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
	void rejectsDuplicateSyllabusVersion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		importer.importSyllabus("Chemistry", "2025", true);
		assertThrows(SQLException.class, () -> importer.importSyllabus("Chemistry", "2025", true));
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