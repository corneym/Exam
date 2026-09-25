package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseSnapshotTest {

	@TempDir
	Path tempDir;

	@Test
	void createsValidatedSnapshotPreservingQuestionAndCurriculumData() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		Path snapshotPath = tempDir.resolve("staging").resolve("snapshot.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode = WAL");
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, 'Chemistry 2019', 0),
					    (2, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL,
					     '2019-D1', '2019 descriptor',
					     'DESCRIPTOR', 0),
					    (20, 2, NULL,
					     '2025-D1', '2025 descriptor',
					     'DESCRIPTOR', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_mappings
					    (id, source_node_id, target_node_id,
					     mapping_status)
					VALUES
					    (1, 10, 20, 'CONFIRMED')
					""");
			statement.execute("""
					INSERT INTO curriculum_mapping_reviews
					    (source_node_id,
					     target_syllabus_version_id,
					     review_outcome)
					VALUES
					    (10, 2, 'MATCHED')
					""");
			statement.execute("""
					INSERT INTO exam_providers
					    (id, provider_name)
					VALUES
					    (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents
					    (id, relative_path)
					VALUES
					    (1, 'Chemistry/2025/paper-1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id,
					     exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025,
					     'External assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id,
					     booklet_name)
					VALUES
					    (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id,
					     classification_node_id,
					     question_code, question_text,
					     marks, shared_context_capture_required)
					VALUES
					    (100, 1, 20,
					     'Q5',
					     'Explain the observed equilibrium shift.',
					     4, 1)
					""");
			statement.execute("""
					INSERT INTO question_regions
					    (question_id, region_order,
					     booklet_id, page_number,
					     x, y, width, height)
					VALUES
					    (100, 0, 1, 4,
					     0.10, 0.20, 0.30, 0.15),
					    (100, 1, 1, 5,
					     0.12, 0.18, 0.40, 0.20)
					""");

			// Keep this connection open while the snapshot is made. The database is in WAL
			// mode, so the test exercises snapshotting a live SQLite database rather than
			// copying a closed database file.
			database.createConsistentSnapshot(snapshotPath);
		}
		assertTrue(Files.isRegularFile(snapshotPath));
		SqliteDatabase snapshot = new SqliteDatabase(snapshotPath);
		assertEquals(SqliteDatabase.latestSchemaVersion(), snapshot.schemaVersion());
		assertDoesNotThrow(snapshot::verifySchema);
		assertDoesNotThrow(snapshot::verifyIntegrity);
		try (Connection connection = snapshot.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT question_code,
					       question_text,
					       marks,
					       shared_context_capture_required,
					       classification_node_id
					FROM questions
					WHERE id = 100
					""")) {
				assertTrue(result.next());
				assertEquals("Q5", result.getString("question_code"));
				assertEquals("Explain the observed equilibrium shift.", result.getString("question_text"));
				assertEquals(4, result.getInt("marks"));
				assertEquals(1, result.getInt("shared_context_capture_required"));
				assertEquals(20, result.getLong("classification_node_id"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT region_order, page_number
					FROM question_regions
					WHERE question_id = 100
					ORDER BY region_order
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt("region_order"));
				assertEquals(4, result.getInt("page_number"));
				assertTrue(result.next());
				assertEquals(1, result.getInt("region_order"));
				assertEquals(5, result.getInt("page_number"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT mapping_status
					FROM curriculum_mappings
					WHERE source_node_id = 10
					  AND target_node_id = 20
					""")) {
				assertTrue(result.next());
				assertEquals("CONFIRMED", result.getString("mapping_status"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT review_outcome
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 10
					  AND target_syllabus_version_id = 2
					""")) {
				assertTrue(result.next());
				assertEquals("MATCHED", result.getString("review_outcome"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void rejectsDatabaseWithoutQuestionBankSchema() throws Exception {
		Path databasePath = tempDir.resolve("not-question-bank.db");
		Path snapshotPath = tempDir.resolve("snapshot.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE unrelated_data (
					    id INTEGER PRIMARY KEY
					)
					""");
		}
		assertThrows(SQLException.class, () -> database.createConsistentSnapshot(snapshotPath));
		assertFalse(Files.exists(snapshotPath));
	}

	@Test
	void rejectsExistingSnapshotDestination() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		Path snapshotPath = tempDir.resolve("snapshot.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Files.writeString(snapshotPath, "existing");
		assertThrows(IOException.class, () -> database.createConsistentSnapshot(snapshotPath));
		assertEquals("existing", Files.readString(snapshotPath));
	}

	@Test
	void rejectsLiveDatabaseAsSnapshotDestination() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		assertThrows(IllegalArgumentException.class, () -> database.createConsistentSnapshot(databasePath));
	}
}
