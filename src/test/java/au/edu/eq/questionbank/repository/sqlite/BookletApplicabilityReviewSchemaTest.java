package au.edu.eq.questionbank.repository.sqlite;

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

class BookletApplicabilityReviewSchemaTest {

	@TempDir
	Path tempDir;

	@Test
	void failedV23MigrationRollsBackTriggerChangeAndCanBeRetried() throws Exception {
		SqliteDatabase database = version22ReviewedFixture("v22-rollback.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_v23_for_test
					BEFORE UPDATE ON schema_version
					WHEN NEW.version = 23
					BEGIN
					    SELECT RAISE(ABORT, 'deliberate v23 migration failure');
					END
					""");
		}
		assertThrows(SQLException.class, database::initialiseSchema);
		assertEquals(22, database.schemaVersion());
		assertTrue(hasReview(database));
		assertFalse(bookletUpdateTriggerSql(database).contains("WHEN OLD.exam_id"));
		database.verifySchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_v23_for_test");
		}
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertTrue(hasReview(database));
		database.verifySchema();
	}

	@Test
	void migratesPopulatedV22WithoutClearingReviewedStatus() throws Exception {
		SqliteDatabase database = version22ReviewedFixture("v22-preserve-reviews.db");
		assertEquals(22, database.schemaVersion());
		assertTrue(hasReview(database));
		database.verifySchema();

		// Execute the real v22->v23 migration, including its trigger replacement.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		assertTrue(hasReview(database));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE exam_booklets
					SET legacy_paper_code = '1'
					WHERE id = 10
					""");
		}
		assertTrue(hasReview(database));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE exam_booklets
					SET expected_question_count = 3
					WHERE id = 10
					""");
		}
		assertFalse(hasReview(database));
	}

	@Test
	void migrationCompatibilityProbeDoesNotAlterOriginalV22Database() throws Exception {
		SqliteDatabase database = version22ReviewedFixture("v22-compatibility-probe.db");
		String originalTriggerSql = bookletUpdateTriggerSql(database);
		assertEquals(22, database.schemaVersion());
		assertTrue(hasReview(database));
		database.verifyMigrationCompatibility();
		assertEquals(22, database.schemaVersion());
		assertEquals(originalTriggerSql, bookletUpdateTriggerSql(database));
		assertTrue(hasReview(database));
		database.verifySchema();

		// The live upgrade is a separate operation; it must still retain the review.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertTrue(hasReview(database));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void newDatabaseStartsWithNoImplicitBookletReviews() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("reviews.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// A confirmation must be a separately persisted human decision.
			// Existing Question and Exam metadata must not imply a review.
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM booklet_applicability_reviews
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
		}
	}

	@Test
	void reviewRecordIsUniqueAndBelongsToAnExistingBooklet() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("reviews.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
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
					VALUES (1, 'paper.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (1, 1, 1, 'Paper 1')
					""");

			// A row represents an explicit positive confirmation.
			statement.execute("""
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (1)
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (1)
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (999)
					"""));

			// Clearing a review does not delete the underlying booklet.
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					DELETE FROM booklet_applicability_reviews
					WHERE booklet_id = 1
					"""));
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM booklet_applicability_reviews
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT id
					FROM exam_booklets
					WHERE id = 1
					""")) {
				assertTrue(result.next());
				assertEquals(1, result.getLong(1));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void version21SqlResourceInstallsAllInvalidationTriggers() throws Exception {
		SqliteDatabase database = version20ReviewedFixture("v21-sql-resource.db");
		assertEquals(20, database.schemaVersion());
		assertTrue(hasReview(database));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v20-to-v21.sql"));
				BookletApplicabilityReviewInvalidationTriggers.verifyLegacy(connection);
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name GLOB 'app_review_*'
						""")) {
					assertTrue(result.next());
					assertEquals(51, result.getInt(1));
				}
				connection.commit();
			} catch (Exception failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(21, database.schemaVersion());
		assertFalse(hasReview(database));
		database.verifySchema();

		// The historical SQL-installed definitions must remain
		// compatible with every subsequent schema migration.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void version23InitialisationIsIdempotentAfterSuccessfulMigration() throws Exception {
		SqliteDatabase database = version22ReviewedFixture("v23-repeat-initialise.db");
		database.initialiseSchema();
		String installedTriggerSql = bookletUpdateTriggerSql(database);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertTrue(hasReview(database));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertEquals(installedTriggerSql, bookletUpdateTriggerSql(database));
		assertTrue(hasReview(database));
		database.verifySchema();
	}

	@Test
	void version23RejectsAlteredOtherInvalidationTrigger() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("altered-review.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER app_review_questions_update
					""");

			// Retaining the expected name must not conceal a
			// trigger whose invalidation behaviour has been removed.
			statement.execute("""
					CREATE TRIGGER app_review_questions_update
					AFTER UPDATE ON questions
					BEGIN
					    SELECT 1;
					END
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage()
				.contains("Altered applicability-review invalidation trigger: " + "app_review_questions_update"));
	}

	@Test
	void version23RejectsSilentlyRestoredBroadBookletUpdateTrigger() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("v23-verification.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER app_review_exam_booklets_update");
			statement.execute(broadBookletUpdateTriggerSql());
		}

		// The broad historical definition must not be accepted
		// merely because its name and owning table are correct.
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage()
				.contains("Altered applicability-review invalidation trigger: " + "app_review_exam_booklets_update"));
	}

	@Test
	void version23SqlResourceReplacesBookletUpdateWithoutDeletingReviews() throws Exception {
		SqliteDatabase database = version22ReviewedFixture("v23-sql-resource.db");
		assertTrue(hasReview(database));
		assertFalse(bookletUpdateTriggerSql(database).contains("WHEN OLD.exam_id"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v22-to-v23.sql"));
				BookletApplicabilityReviewInvalidationTriggers.verify(connection);
				connection.commit();
			} catch (Exception failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(23, database.schemaVersion());
		assertTrue(hasReview(database));
		assertTrue(bookletUpdateTriggerSql(database).contains("WHEN OLD.exam_id"));
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertTrue(hasReview(database));
		database.verifySchema();
		database.verifyIntegrity();
	}

	private String bookletUpdateTriggerSql(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT sql
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name = 'app_review_exam_booklets_update'
						""")) {
			if (!result.next()) {
				throw new SQLException("Missing booklet UPDATE review trigger");
			}
			return result.getString("sql");
		}
	}

	private String broadBookletUpdateTriggerSql() {
		return """
				CREATE TRIGGER app_review_exam_booklets_update
				AFTER UPDATE ON exam_booklets
				BEGIN
				    DELETE FROM booklet_applicability_reviews
				    WHERE booklet_id IN (
				        SELECT OLD.id
				        UNION
				        SELECT NEW.id
				    );
				END
				""";
	}

	private boolean hasReview(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM booklet_applicability_reviews
						WHERE booklet_id = 10
						""")) {
			return result.next() && result.getInt(1) == 1;
		}
	}

	private SqliteDatabase version20ReviewedFixture(String filename) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve(filename));
		database.initialiseSchema();

		// Reconstruct a disposable historical fixture without
		// retaining any later schema objects or review triggers.
		HistoricalSchemaFixture.removePostVersion19Objects(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("UPDATE schema_version SET version = 19");
				SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v19-to-v20.sql"));
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
						VALUES (1, 'Chemistry/QCAA/2020/Paper1.pdf')
						""");
				statement.execute("""
						INSERT INTO exams (
						    id, subject_id, provider_id,
						    exam_year, exam_name
						)
						VALUES (
						    1, 1, 1, 2020, 'External Assessment'
						)
						""");
				statement.execute("""
						INSERT INTO exam_booklets (
						    id, exam_id, source_document_id,
						    booklet_name
						)
						VALUES (10, 1, 1, 'Paper 1')
						""");
				statement.execute("""
						INSERT INTO booklet_applicability_reviews (booklet_id)
						VALUES (10)
						""");
				connection.commit();
			} catch (Exception failure) {
				connection.rollback();
				throw failure;
			}
		}
		database.verifySchema();
		return database;
	}

	private SqliteDatabase version22ReviewedFixture(String filename) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve(filename));
		database.initialiseSchema();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {

				// Reconstruct the v22 fixture without retaining v24 tables.
				HistoricalSchemaFixture.removeVersion24Objects(statement);
				statement.execute("DROP TRIGGER app_review_exam_booklets_update");
				statement.execute(broadBookletUpdateTriggerSql());
				statement.execute("UPDATE schema_version SET version = 22");
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
						VALUES (1, 'Chemistry/QCAA/2020/Paper1.pdf')
						""");
				statement.execute("""
						INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
						VALUES (1, 1, 1, 2020, 'External Assessment')
						""");
				statement.execute("""
						INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
						VALUES (10, 1, 1, 'Paper 1')
						""");
				statement.execute("""
						INSERT INTO booklet_applicability_reviews (booklet_id)
						VALUES (10)
						""");
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		database.verifySchema();
		return database;
	}
}
