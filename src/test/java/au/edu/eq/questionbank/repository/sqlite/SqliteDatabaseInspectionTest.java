package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseInspectionTest {

	@TempDir
	Path tempDir;

	@Test
	void latestSchemaContainsNullablePendingMcqSharedContextReference() throws SQLException {
		Path databasePath = tempDir.resolve("pending-mcq-context.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		boolean columnFound = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"pending_mcq_shared_context_id".equals(result.getString("name"))) {
					continue;
				}
				columnFound = true;

				// A booklet normally has no unfinished MCQ continuation, so the column
				// must permit NULL.
				assertEquals(0, result.getInt("notnull"));
			}
		}
		assertTrue(columnFound);
		boolean foreignKeyFound = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA foreign_key_list(exam_booklets)")) {
			while (result.next()) {
				if ("pending_mcq_shared_context_id".equals(result.getString("from"))
						&& "shared_question_contexts".equals(result.getString("table"))
						&& "id".equals(result.getString("to"))) {
					foreignKeyFound = true;
				}
			}
		}

		// The workflow state must never reference a context row that does not exist.
		assertTrue(foreignKeyFound);

		// Later migrations retain the version-11 pending-context relationship.
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
	}

	@Test
	void latestSchemaContainsQuestionOutputExclusions() throws SQLException {
		Path databasePath = tempDir.resolve("question-output-exclusions.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		int questionPrimaryKeyPosition = 0;
		int nodePrimaryKeyPosition = 0;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(question_output_exclusions)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				if ("question_id".equals(columnName)) {
					questionPrimaryKeyPosition = result.getInt("pk");

					// Every exclusion must identify a persisted Question.
					assertEquals(1, result.getInt("notnull"));
				} else if ("current_curriculum_node_id".equals(columnName)) {
					nodePrimaryKeyPosition = result.getInt("pk");

					// Every exclusion must identify one concrete current placement node.
					assertEquals(1, result.getInt("notnull"));
				}
			}
		}
		assertEquals(1, questionPrimaryKeyPosition);
		assertEquals(2, nodePrimaryKeyPosition);
		boolean questionForeignKeyFound = false;
		boolean curriculumForeignKeyFound = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA foreign_key_list(question_output_exclusions)")) {
			while (result.next()) {
				String from = result.getString("from");
				String table = result.getString("table");
				String to = result.getString("to");
				if ("question_id".equals(from) && "questions".equals(table) && "id".equals(to)) {
					questionForeignKeyFound = true;
				}
				if ("current_curriculum_node_id".equals(from) && "curriculum_nodes".equals(table) && "id".equals(to)) {
					curriculumForeignKeyFound = true;
				}
			}
		}

		// Both sides of the exclusion are durable domain identities rather than
		// free-standing numeric preferences.
		assertTrue(questionForeignKeyFound);
		assertTrue(curriculumForeignKeyFound);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
	}

	@Test
	void latestSchemaUsesSharedContextColumnNames() throws SQLException {
		Path databasePath = tempDir.resolve("shared-context-column-names.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		boolean hasSharedContextCaptureRequired = false;
		boolean hasLegacyPreambleCaptureRequired = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				String name = result.getString("name");
				if ("shared_context_capture_required".equals(name)) {
					hasSharedContextCaptureRequired = true;
				} else if ("preamble_capture_required".equals(name)) {
					hasLegacyPreambleCaptureRequired = true;
				}
			}
		}
		assertTrue(hasSharedContextCaptureRequired);
		assertFalse(hasLegacyPreambleCaptureRequired);
		boolean hasSharedContextStatus = false;
		boolean hasLegacyPreambleStatus = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(source_questions)")) {
			while (result.next()) {
				String name = result.getString("name");
				if ("shared_context_status".equals(name)) {
					hasSharedContextStatus = true;
				} else if ("preamble_status".equals(name)) {
					hasLegacyPreambleStatus = true;
				}
			}
		}
		assertTrue(hasSharedContextStatus);
		assertFalse(hasLegacyPreambleStatus);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
	}

	@Test
	void schemaVersionReturnsLatestVersionForInitialisedDatabase() throws SQLException {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
	}

	@Test
	void schemaVersionReturnsZeroWhenDatabaseHasNoQuestionBankSchema() throws SQLException {
		Path databasePath = tempDir.resolve("empty.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection _ = database.openConnection()) {

			// Opening the connection creates the empty SQLite file.
		}
		assertEquals(0, database.schemaVersion());
	}

	@Test
	void verifyIntegrityAcceptsValidInitialisedDatabase() throws SQLException {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		assertDoesNotThrow(database::verifyIntegrity);
	}

	@Test
	void verifyIntegrityRejectsForeignKeyViolation() throws SQLException {
		Path databasePath = tempDir.resolve("foreign-key-failure.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			statement.execute("""
					CREATE TABLE parent (
					    id INTEGER PRIMARY KEY
					)
					""");
			statement.execute("""
					CREATE TABLE child (
					    id INTEGER PRIMARY KEY,
					    parent_id INTEGER NOT NULL,
					    FOREIGN KEY (parent_id) REFERENCES parent(id)
					)
					""");
			statement.execute("""
					INSERT INTO child (id, parent_id)
					VALUES (1, 999)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::verifyIntegrity);
		assertTrue(exception.getMessage().contains("SQLite foreign-key check failed"));
	}

	@Test
	void version13MigrationCreatesOrderedPdfContentParts() throws Exception {
		Path databasePath = tempDir.resolve("version-13-question-content.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Build enough real relational data to retain two legacy PDF regions across
			// the version-13 to version-14 migration.
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current, curriculum_status
					)
					VALUES (1, 1, '2019', 0, 'FINAL')
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES (
					    1, 1, NULL,
					    '1.1.1', 'Electrochemistry',
					    'SUBTOPIC', 0
					)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'Chemistry/2019/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id, exam_year, exam_name
					)
					VALUES (1, 1, 1, 2019, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id,
					    booklet_name, question_format
					)
					VALUES (1, 1, 1, 'Paper 1', 'UNSPECIFIED')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required, response_type
					)
					VALUES (
					    1, 1, 1,
					    'Q5', '', 1,
					    0, 'MULTIPLE_CHOICE'
					)
					""");
			statement.execute("""
					INSERT INTO question_regions (
					    question_id, region_order, booklet_id,
					    page_number, x, y, width, height
					)
					VALUES
					    (1, 0, 1, 4, 0.10, 0.20, 0.70, 0.20),
					    (1, 1, 1, 5, 0.10, 0.10, 0.70, 0.25)
					""");
			statement.execute("ALTER TABLE answer_files DROP COLUMN contains_answer_explanations");

			// Version 18 added Exam-level expected asset counts.
			statement.execute("ALTER TABLE exams DROP COLUMN expected_answer_file_count");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_question_booklet_count");

			// Version 17 did not exist in the historical v13 fixture.
			statement.execute("ALTER TABLE questions DROP COLUMN source_capture_required");

			// Version 16 did not exist in the historical v13 fixture.
			statement.execute("ALTER TABLE source_documents DROP COLUMN content_sha256");

			// The fixture starts from the current latest schema. Remove later-version
			// structures in reverse order so the resulting database genuinely matches
			// version 13 before exercising the real forward migration path.
			statement.execute("ALTER TABLE exam_booklets DROP COLUMN expected_question_count");
			statement.execute("ALTER TABLE exams DROP COLUMN capture_state");
			statement.execute("DROP TABLE question_content_parts");
			statement.execute("DROP TABLE question_images");
			statement.execute("UPDATE schema_version SET version = 13");
		}
		assertEquals(13, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("""
						SELECT content_order, content_type, region_order, image_id
						FROM question_content_parts
						WHERE question_id = 1
						ORDER BY content_order
						""")) {

			// Migration preserves the exact existing PDF-region assembly order.
			assertTrue(result.next());
			assertEquals(0, result.getInt("content_order"));
			assertEquals("PDF_REGION", result.getString("content_type"));
			assertEquals(0, result.getInt("region_order"));
			assertEquals(null, result.getObject("image_id"));
			assertTrue(result.next());
			assertEquals(1, result.getInt("content_order"));
			assertEquals("PDF_REGION", result.getString("content_type"));
			assertEquals(1, result.getInt("region_order"));
			assertEquals(null, result.getObject("image_id"));
			assertFalse(result.next());
		}
	}

	@Test
	void version14MigrationAddsExamCapturePlanningMetadata() throws Exception {
		Path databasePath = tempDir.resolve("version-14-exam-capture-metadata.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE answer_files DROP COLUMN contains_answer_explanations");

			// Version 18 added Exam-level expected asset counts.
			statement.execute("ALTER TABLE exams DROP COLUMN expected_answer_file_count");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_question_booklet_count");

			// Remove the later version-17 recapture marker before constructing v14.
			statement.execute("ALTER TABLE questions DROP COLUMN source_capture_required");

			// Remove the later v16 hash metadata before constructing the v14 fixture.
			statement.execute("ALTER TABLE source_documents DROP COLUMN content_sha256");

			// Remove only the version-15 additions so the fixture represents the exact
			// structural state immediately before the new migration.
			statement.execute("ALTER TABLE exam_booklets DROP COLUMN expected_question_count");
			statement.execute("ALTER TABLE exams DROP COLUMN capture_state");
			statement.execute("UPDATE schema_version SET version = 14");

			// Seed a real existing Exam and booklet whose v14 data contains no completion
			// declaration and no expected Question count.
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'Chemistry/QCAA/2025/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id, exam_year, exam_name
					)
					VALUES (1, 1, 1, 2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id,
					    exam_id,
					    source_document_id,
					    booklet_name,
					    question_format
					)
					VALUES (
					    1,
					    1,
					    1,
					    'Paper 1',
					    'MULTIPLE_CHOICE'
					)
					""");
		}
		assertEquals(14, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);

		// Run the production sequential migration rather than reproducing its SQL in
		// the test.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("""
						SELECT
						    e.capture_state,
						    eb.expected_question_count
						FROM exams e
						JOIN exam_booklets eb
						    ON eb.exam_id = e.id
						WHERE e.id = 1
						  AND eb.id = 1
						""")) {
			assertTrue(result.next());

			// Existing Exams remain editable until the user explicitly declares them
			// complete.
			assertEquals("ACTIVE", result.getString("capture_state"));

			// Migration must not invent an expected Question count for legacy data.
			assertEquals(null, result.getObject("expected_question_count"));
			assertFalse(result.next());
		}
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// The database itself protects the small closed lifecycle vocabulary.
			assertThrows(SQLException.class,
					() -> statement.execute("UPDATE exams SET capture_state = 'UNKNOWN' WHERE id = 1"));

			// An expected count is either unknown or a positive number of top-level
			// Questions; zero and negative values are invalid planning data.
			assertThrows(SQLException.class,
					() -> statement.execute("UPDATE exam_booklets SET expected_question_count = 0 WHERE id = 1"));
			statement.execute("UPDATE exams SET capture_state = 'COMPLETE' WHERE id = 1");
			statement.execute("UPDATE exam_booklets SET expected_question_count = 20 WHERE id = 1");
		}
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("""
						SELECT
						    e.capture_state,
						    eb.expected_question_count
						FROM exams e
						JOIN exam_booklets eb
						    ON eb.exam_id = e.id
						WHERE e.id = 1
						  AND eb.id = 1
						""")) {
			assertTrue(result.next());
			assertEquals("COMPLETE", result.getString("capture_state"));
			assertEquals(20, result.getInt("expected_question_count"));
			assertFalse(result.next());
		}
	}

	@Test
	void version15MigrationAddsNullableNonUniqueSourceDocumentHash() throws Exception {
		Path databasePath = tempDir.resolve("version-15-source-document-hash.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Seed an existing document before reconstructing the exact v15 schema.
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'Chemistry/QCAA/2025/paper1.pdf')
					""");
			statement.execute("ALTER TABLE answer_files DROP COLUMN contains_answer_explanations");

			// Version 18 added Exam-level expected asset counts.
			statement.execute("ALTER TABLE exams DROP COLUMN expected_answer_file_count");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_question_booklet_count");

			// Version 17 added Question source-recapture state after this fixture.
			statement.execute("ALTER TABLE questions DROP COLUMN source_capture_required");

			// The fixture is manufactured from the current schema, so remove only the
			// structure introduced by v16 before claiming that it is version 15.
			statement.execute("ALTER TABLE source_documents DROP COLUMN content_sha256");
			statement.execute("UPDATE schema_version SET version = 15");
		}
		assertEquals(15, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);

		// Exercise the real production migration path.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("""
						SELECT content_sha256
						FROM source_documents
						WHERE id = 1
						""")) {
			assertTrue(result.next());

			// Migration must not invent a hash for a document whose bytes were not read.
			assertEquals(null, result.getObject("content_sha256"));
			assertFalse(result.next());
		}
		String validHash = "a".repeat(64);
		String invalidHash = "g".repeat(64);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// A canonical lower-case SHA-256 hex digest is accepted.
			statement.executeUpdate("""
					UPDATE source_documents
					SET content_sha256 = '%s'
					WHERE id = 1
					""".formatted(validHash));

			// Identical content is deliberately legal at the database level so the
			// application can detect and resolve duplicates explicitly.
			statement.executeUpdate("""
					INSERT INTO source_documents (
					    relative_path,
					    content_sha256
					)
					VALUES (
					    'Chemistry/QCAA/2025/duplicate-paper.pdf',
					    '%s'
					)
					""".formatted(validHash));
			try (var duplicateCount = statement.executeQuery("""
					SELECT COUNT(*)
					FROM source_documents
					WHERE content_sha256 = '%s'
					""".formatted(validHash))) {
				assertTrue(duplicateCount.next());
				assertEquals(2, duplicateCount.getInt(1));
			}

			// Stored hashes must have the exact SHA-256 hexadecimal width.
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE source_documents
					SET content_sha256 = 'abc'
					WHERE id = 1
					"""));

			// Sixty-four characters alone are insufficient when they are not hexadecimal.
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE source_documents
					SET content_sha256 = '%s'
					WHERE id = 1
					""".formatted(invalidHash)));
		}
	}

	@Test
	void version16MigrationAddsQuestionSourceRecaptureState() throws Exception {
		Path databasePath = tempDir.resolve("version-16-question-source-recapture.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Manufacture the exact version-16 schema from the current latest schema.
			// Remove later additions in reverse migration order before changing the
			// recorded version.
			statement.execute("ALTER TABLE answer_files DROP COLUMN contains_answer_explanations");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_answer_file_count");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_question_booklet_count");
			statement.execute("ALTER TABLE questions DROP COLUMN source_capture_required");
			statement.execute("UPDATE schema_version SET version = 16");
		}
		assertEquals(16, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		boolean foundColumn = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				if (!"source_capture_required".equals(result.getString("name"))) {
					continue;
				}
				foundColumn = true;

				// The migration supplies an explicit false value for both existing and
				// subsequently inserted Questions unless replacement marks them otherwise.
				assertEquals(1, result.getInt("notnull"));
				assertEquals("0", result.getString("dflt_value"));
			}
		}
		assertTrue(foundColumn);
	}

	@Test
	void version17MigrationAddsExamAssetExpectations() throws Exception {
		Path databasePath = tempDir.resolve("version-17-exam-asset-expectations.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Manufacture the exact immediately preceding schema from the current one.
			statement.execute("ALTER TABLE answer_files DROP COLUMN contains_answer_explanations");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_answer_file_count");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_question_booklet_count");
			statement.execute("UPDATE schema_version SET version = 17");
		}
		assertEquals(17, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		boolean foundQuestionBookletExpectation = false;
		boolean foundAnswerFileExpectation = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exams)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				if ("expected_question_booklet_count".equals(columnName)) {
					foundQuestionBookletExpectation = true;

					// Existing Exams retain an unknown expectation rather than having their
					// current asset count inferred during migration.
					assertEquals(0, result.getInt("notnull"));
				}
				if ("expected_answer_file_count".equals(columnName)) {
					foundAnswerFileExpectation = true;
					assertEquals(0, result.getInt("notnull"));
				}
			}
		}
		assertTrue(foundQuestionBookletExpectation);
		assertTrue(foundAnswerFileExpectation);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Seed the minimum persisted Exam relationships required to exercise the new
			// column constraints against an actual row.
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id,
					    subject_id,
					    provider_id,
					    exam_year,
					    exam_name
					)
					VALUES (
					    1,
					    1,
					    1,
					    2025,
					    'External Assessment'
					)
					""");

			// Question booklet expectations, when known, must be positive.
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE exams
					SET expected_question_booklet_count = 0
					WHERE id = 1
					"""));

			// Zero Answer files is a legitimate explicit expectation.
			assertDoesNotThrow(() -> statement.executeUpdate("""
					UPDATE exams
					SET expected_answer_file_count = 0
					WHERE id = 1
					"""));

			// Negative expected Answer-file counts are invalid.
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE exams
					SET expected_answer_file_count = -1
					WHERE id = 1
					"""));
		}
	}
}
