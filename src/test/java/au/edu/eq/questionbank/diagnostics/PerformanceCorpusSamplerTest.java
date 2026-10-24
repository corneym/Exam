package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class PerformanceCorpusSamplerTest {

	@TempDir
	Path tempDir;

	@Test
	void existingQuestionAndAnswerAreCountedWithoutMutation() throws Exception {
		Path file = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(file);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES (1, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, 'D1',
					     'Descriptor', 'DESCRIPTOR', 0)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'paper-1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id,
					     exam_year, exam_name)
					VALUES (1, 1, 1, 2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id,
					     question_code, question_text,
					     marks, shared_context_capture_required)
					VALUES
					    (100, 1, 10, 'Q1', '', 4, 0)
					""");
			statement.execute("""
					INSERT INTO answers (question_id, answer_text)
					VALUES (100, '')
					""");
		}
		byte[] databaseBefore = Files.readAllBytes(file);
		PerformanceCorpusSampler.Sample sample = PerformanceCorpusSampler.capture(file);
		assertEquals(1, sample.questionCount());
		assertEquals(1, sample.answerCount());
		assertEquals(0, sample.questionContentPartCount());
		assertEquals(0, sample.sharedContextContentPartCount());

		// The diagnostic query must not modify the corpus.
		assertTrue(java.util.Arrays.equals(databaseBefore, Files.readAllBytes(file)));
	}

	@Test
	void initialisedEmptyCorpusReportsZeroCounts() throws Exception {
		Path file = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(file);
		database.initialiseSchema();
		PerformanceCorpusSampler.Sample sample = PerformanceCorpusSampler.capture(file);
		assertEquals(SqliteDatabase.latestSchemaVersion(), sample.schemaVersion());
		assertEquals(0, sample.questionCount());
		assertEquals(0, sample.answerCount());
		assertEquals(0, sample.questionContentPartCount());
		assertEquals(0, sample.sharedContextContentPartCount());
		assertTrue(sample.databaseBytes() > 0);
	}

	@Test
	void missingDatabaseIsNotCreated() {
		Path missing = tempDir.resolve("missing.db");
		assertThrows(IOException.class, () -> PerformanceCorpusSampler.capture(missing));
		assertFalse(Files.exists(missing));
	}
}
