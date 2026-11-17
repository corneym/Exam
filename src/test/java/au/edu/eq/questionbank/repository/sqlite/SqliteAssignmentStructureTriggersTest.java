package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAssignmentStructureTriggersTest {

	@TempDir
	Path directory;

	@Test
	void migratesVersion29AndRestoresStructuralGuards() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeAssignmentGuardObjects(statement);
				statement.execute("""
						UPDATE schema_version
						SET version = 29
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion()));
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(29, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		try (Connection connection = database.openConnection()) {
			SqliteAssignmentStructureTriggers.verify(connection);
			SqliteAssignmentScopeTriggers.verify(connection);
		}
		database.verifyIntegrity();
	}

	@Test
	void migratesVersion31ThroughLatestWithoutChangingAssignmentData() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeAssignmentGuardObjects(statement);

				// Reconstruct the version-31 triggers.
				SqliteAssignmentStructureTriggers.install(connection);
				SqliteAssignmentScopeTriggers.install(connection);
				statement.execute("""
						UPDATE schema_version
						SET version = 31
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion()));
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(31, database.schemaVersion());
		database.verifySchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertBlocked(statement, """
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 301
					WHERE id = 61
					""");
		}
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 301
					WHERE id = 61
					""");
			SqliteAssignmentStructureTriggers.verify(connection);
			SqliteAssignmentScopeTriggers.verify(connection);
		}
		database.verifyIntegrity();
	}

	@Test
	void missingPendingMcqTriggerFailsSchemaVerification() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER
					trg_contributor_structure_exam_booklets_pending_mcq_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing contributor structure trigger"));
	}

	@Test
	void missingStructureTriggerFailsSchemaVerification() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER
					trg_contributor_structure_exams_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing contributor structure trigger"));
	}

	@Test
	void normalModeAllowsCoordinatorOwnedChanges() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE contributor_assignment_selection
					SET assignment_id = NULL
					WHERE singleton_id = 1
					""");
			statement.execute("""
					UPDATE exams
					SET exam_name = 'Coordinator edited'
					WHERE id = 31
					""");
		}
		database.verifyIntegrity();
	}

	@Test
	void normalModeAllowsPendingMcqUpdates() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE contributor_assignment_selection
					SET assignment_id = NULL
					WHERE singleton_id = 1
					""");
			statement.execute("""
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 301
					WHERE id = 61
					""");
			statement.execute("""
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = NULL
					WHERE id = 61
					""");
		}
		database.verifyIntegrity();
	}

	@Test
	void permitsOnlyAssignedBookletContinuationAndBlocksStructuralEdits() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Context 301 belongs to Booklet 61 in the assigned Exam.
			statement.execute("""
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 301
					WHERE id = 61
					""");

			// A subsequent MCQ may deliberately renew the same context.
			statement.execute("""
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 301
					WHERE id = 61
					""");

			// Context 302 belongs to a different Booklet and Exam.
			assertBlocked(statement, """
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 302
					WHERE id = 61
					""");

			// A contributor cannot update a Booklet outside their Exam,
			// even when the context belongs to that other Booklet.
			assertBlocked(statement, """
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 302
					WHERE id = 62
					""");

			// A combined pending-context and authoritative metadata
			// update must not bypass structural protection.
			assertBlocked(statement, """
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = 301,
					    booklet_name = 'Changed'
					WHERE id = 61
					""");

			// Even an attempted no-op structural update is prohibited.
			assertBlocked(statement, """
					UPDATE exam_booklets
					SET booklet_name = booklet_name
					WHERE id = 61
					""");

			// Consuming the continuation must be permitted.
			statement.execute("""
					UPDATE exam_booklets
					SET pending_mcq_shared_context_id = NULL
					WHERE id = 61
					""");
			try (var result = statement.executeQuery("""
					SELECT pending_mcq_shared_context_id, booklet_name
					FROM exam_booklets
					WHERE id = 61
					""")) {
				assertTrue(result.next());
				result.getLong("pending_mcq_shared_context_id");
				assertTrue(result.wasNull());
				assertEquals("Assigned Booklet", result.getString("booklet_name"));
			}
		}
		database.verifyIntegrity();
	}

	@Test
	void rejectsCoordinatorOwnedChangesWhileAssignmentSelected() throws Exception {
		SqliteDatabase database = fixture();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertBlocked(statement, """
					UPDATE subjects
					SET subject_name = 'Changed'
					WHERE id = 1
					""");
			assertBlocked(statement, """
					UPDATE syllabus_versions
					SET syllabus_name = 'Changed'
					WHERE id = 11
					""");
			assertBlocked(statement, """
					UPDATE curriculum_nodes
					SET curriculum_name = 'Changed'
					WHERE id = 101
					""");
			assertBlocked(statement, """
					UPDATE exam_providers
					SET provider_name = 'Changed'
					WHERE id = 1
					""");
			assertBlocked(statement, """
					UPDATE source_documents
					SET relative_path = 'changed.pdf'
					WHERE id = 51
					""");
			assertBlocked(statement, """
					UPDATE exams
					SET exam_name = 'Changed'
					WHERE id = 31
					""");
			assertBlocked(statement, """
					UPDATE exam_booklets
					SET booklet_name = 'Changed'
					WHERE id = 61
					""");
			assertBlocked(statement, """
					UPDATE answer_files
					SET answer_file_name = 'Changed'
					WHERE id = 81
					""");
			assertBlocked(statement, """
					INSERT INTO subjects (id, subject_name)
					VALUES (99, 'New Subject')
					""");
			assertBlocked(statement, """
					DELETE FROM exams
					WHERE id = 32
					""");
		}
		database.verifyIntegrity();
	}

	private void assertBlocked(Statement statement, String sql) {
		SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
		assertTrue(failure.getMessage().contains("Coordinator-owned structure is read-only"), failure.getMessage());
	}

	private SqliteDatabase fixture() throws Exception {
		return SqliteAssignmentGuardFixture.create(directory.resolve("guard.db"), false);
	}
}
