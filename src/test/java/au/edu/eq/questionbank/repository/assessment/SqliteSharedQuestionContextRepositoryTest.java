package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteSharedQuestionContextRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void failedVersion24MigrationRollsBackSchemaAndDataThenRetries() throws Exception {
		RepositoryFixture fixture = createFixture("context-v24-rollback.db");
		SqliteDatabase database = fixture.database();
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(2, 0.10, 0.15, 0.70, 0.20);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(3, 0.15, 0.25, 0.60, 0.30);
		List<SharedQuestionContextRegion> originalRegions = List.of(first, second);
		SharedQuestionContext original = fixture.repository().save(fixture.firstBooklet(), "Existing PDF context",
				originalRegions);

		// Reconstruct the pre-migration v23 schema using an isolated
		// temporary database. The existing PDF region rows remain intact.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE shared_question_context_content_parts");
			statement.execute("DROP TABLE shared_question_context_images");
			statement.execute("UPDATE schema_version SET version = 23");

			// The migration's final schema-version update occurs after
			// table creation and PDF-region ordering backfill.
			// Abort at that point to exercise rollback of earlier DDL/DML.
			statement.execute("""
					CREATE TRIGGER reject_version24_migration
					BEFORE UPDATE ON schema_version
					WHEN NEW.version = 24
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'injected version 24 migration failure'
					    );
					END
					""");
		}
		assertEquals(23, database.schemaVersion());

		// Migration must fail after it has executed the new DDL and
		// backfill statements, but before the transaction can commit.
		assertThrows(SQLException.class, database::initialiseSchema);

		// SQLite transactional DDL must also be rolled back, not just
		// the final schema-version value.
		assertEquals(23, database.schemaVersion());
		assertFalse(tableExists(database, "shared_question_context_images"));
		assertFalse(tableExists(database, "shared_question_context_content_parts"));

		// All original source records and relationships remain intact.
		assertEquals(1, rowCount(database, "shared_question_contexts"));
		assertEquals(2, rowCount(database, "shared_question_context_regions"));
		SharedQuestionContext afterFailure = fixture.repository().findByBooklet(fixture.firstBooklet()).getFirst();
		assertEquals(original.getId(), afterFailure.getId());
		assertEquals(original.getLabel(), afterFailure.getLabel());
		assertEquals(originalRegions, afterFailure.getRegions());

		// Remove only the test's deliberate migration blocker.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_version24_migration");
		}

		// Retrying on the same database must now succeed.
		database.initialiseSchema();
		assertEquals(24, database.schemaVersion());
		assertTrue(tableExists(database, "shared_question_context_images"));
		assertTrue(tableExists(database, "shared_question_context_content_parts"));
		SharedQuestionContext migrated = fixture.repository().findByBooklet(fixture.firstBooklet()).getFirst();
		assertEquals(original.getId(), migrated.getId());
		assertEquals(original.getLabel(), migrated.getLabel());
		assertEquals(originalRegions, migrated.getRegions());
		assertEquals(2, rowCount(database, "shared_question_context_regions"));
		assertEquals(2, rowCount(database, "shared_question_context_content_parts"));
		assertEquals(0, rowCount(database, "shared_question_context_images"));

		// Reinitialisation must be idempotent.
		database.initialiseSchema();
		assertEquals(24, database.schemaVersion());
		assertEquals(2, rowCount(database, "shared_question_context_regions"));
		assertEquals(2, rowCount(database, "shared_question_context_content_parts"));
		assertEquals(0, rowCount(database, "shared_question_context_images"));
	}

	@Test
	void migratesExistingPdfContextsWithoutChangingRegionsOrIdentity() throws Exception {
		RepositoryFixture fixture = createFixture("context-v24-migration.db");
		SqliteDatabase database = fixture.database();
		List<SharedQuestionContextRegion> originalRegions = List.of(
				new SharedQuestionContextRegion(2, 0.10, 0.15, 0.70, 0.20),
				new SharedQuestionContextRegion(3, 0.15, 0.25, 0.60, 0.30));
		SharedQuestionContext original = fixture.repository().save(fixture.firstBooklet(), "Existing PDF context",
				originalRegions);

		// Construct a representative v23 database using the established
		// schema and data, but without the newly introduced v24 tables.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE shared_question_context_content_parts");
			statement.execute("DROP TABLE shared_question_context_images");
			statement.execute("UPDATE schema_version SET version = 23");
		}
		database.initialiseSchema();
		assertEquals(24, database.schemaVersion());
		SharedQuestionContext migrated = fixture.repository().findByBooklet(fixture.firstBooklet()).getFirst();
		assertEquals(original.getId(), migrated.getId());
		assertEquals(original.getLabel(), migrated.getLabel());
		assertEquals(originalRegions, migrated.getRegions());

		// The migration creates ordering records for the previously
		// stored PDF regions without replacing their source records.
		assertEquals(2, rowCount(database, "shared_question_context_regions"));
		assertEquals(2, rowCount(database, "shared_question_context_content_parts"));
		assertEquals(0, rowCount(database, "shared_question_context_images"));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT content_order, content_type, region_order
						FROM shared_question_context_content_parts
						WHERE shared_context_id = %d
						ORDER BY content_order
						""".formatted(original.getId()))) {
			assertTrue(result.next());
			assertEquals(0, result.getInt("content_order"));
			assertEquals("PDF_REGION", result.getString("content_type"));
			assertEquals(0, result.getInt("region_order"));
			assertTrue(result.next());
			assertEquals(1, result.getInt("content_order"));
			assertEquals("PDF_REGION", result.getString("content_type"));
			assertEquals(1, result.getInt("region_order"));
			assertFalse(result.next());
		}
	}

	@Test
	void pdfOnlySaveAndReplacementMaintainVersion24ContentOrder() throws Exception {
		RepositoryFixture fixture = createFixture("pdf-v24-order.db");
		SqliteDatabase database = fixture.database();

		SharedQuestionContextRegion first = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.60, 0.20);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(2, 0.10, 0.20, 0.60, 0.20);

		SharedQuestionContext saved = fixture.repository().save(fixture.firstBooklet(), "Original context",
				List.of(first, second));

		assertEquals(2, rowCount(database, "shared_question_context_regions"));
		assertEquals(2, rowCount(database, "shared_question_context_content_parts"));

		SharedQuestionContextRegion replacement = new SharedQuestionContextRegion(3, 0.15, 0.25, 0.50, 0.30);

		SharedQuestionContext replaced = fixture.repository().replace(saved, "Corrected context", List.of(replacement));

		assertEquals(saved.getId(), replaced.getId());
		assertEquals(1, rowCount(database, "shared_question_context_regions"));
		assertEquals(1, rowCount(database, "shared_question_context_content_parts"));

		SharedQuestionContext reloaded = fixture.repository().findByBooklet(fixture.firstBooklet()).getFirst();

		assertEquals("Corrected context", reloaded.getLabel());
		assertEquals(List.of(replacement), reloaded.getRegions());

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT content_order, content_type, region_order
						FROM shared_question_context_content_parts
						WHERE shared_context_id = ?
						""")) {
			statement.setLong(1, saved.getId());

			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				assertEquals(0, result.getInt("content_order"));
				assertEquals("PDF_REGION", result.getString("content_type"));
				assertEquals(0, result.getInt("region_order"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void rejectsInvalidArguments() throws Exception {
		RepositoryFixture fixture = createFixture("invalid-shared-context.db");
		SqliteSharedQuestionContextRepository repository = fixture.repository();
		SharedQuestionContextRegion region = new SharedQuestionContextRegion(1, 0.1, 0.1, 0.5, 0.2);
		assertAll(() -> assertThrows(NullPointerException.class, () -> new SqliteSharedQuestionContextRepository(null)),
				() -> assertThrows(NullPointerException.class, () -> repository.findByBooklet(null)),
				() -> assertThrows(NullPointerException.class,
						() -> repository.save(null, "Shared Context", List.of(region))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> repository.save(fixture.firstBooklet(), " ", List.of(region))),
				() -> assertThrows(NullPointerException.class,
						() -> repository.save(fixture.firstBooklet(), "Shared Context", null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> repository.save(fixture.firstBooklet(), "Shared Context", List.of())),
				() -> assertThrows(NullPointerException.class,
						() -> repository.save(fixture.firstBooklet(), "Shared Context", Arrays.asList(region, null))));
	}

	@Test
	void replacesSharedContextWithoutChangingIdentity() throws Exception {
		RepositoryFixture fixture = createFixture("replace-shared-context.db");
		SqliteSharedQuestionContextRepository repository = fixture.repository();
		SharedQuestionContext saved = repository.save(fixture.firstBooklet(), "Question 21 shared context",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.70, 0.15)));
		List<SharedQuestionContextRegion> replacementRegions = List.of(
				new SharedQuestionContextRegion(3, 0.12, 0.20, 0.65, 0.18),
				new SharedQuestionContextRegion(4, 0.14, 0.10, 0.60, 0.22));
		SharedQuestionContext replaced = repository.replace(saved, "Corrected Question 21 shared context",
				replacementRegions);

		// Correction must preserve the shared-context identity so existing Question
		// foreign keys continue to refer to this same logical shared context.
		assertEquals(saved.getId(), replaced.getId());
		assertEquals(fixture.firstBooklet().getId(), replaced.getBooklet().getId());
		assertEquals("Corrected Question 21 shared context", replaced.getLabel());
		assertEquals(replacementRegions, replaced.getRegions());
		List<SharedQuestionContext> reloaded = repository.findByBooklet(fixture.firstBooklet());
		assertEquals(1, reloaded.size());
		assertEquals(saved.getId(), reloaded.getFirst().getId());
		assertEquals("Corrected Question 21 shared context", reloaded.getFirst().getLabel());
		assertEquals(replacementRegions, reloaded.getFirst().getRegions());
	}

	@Test
	void rollsBackContextWhenARegionCannotBeInserted() throws Exception {
		RepositoryFixture fixture = createFixture("shared-context-rollback.db");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_test_context_region
					BEFORE INSERT ON shared_question_context_regions
					BEGIN
					    SELECT RAISE(ABORT, 'deliberate test failure');
					END
					""");
		}
		List<SharedQuestionContextRegion> regions = List.of(new SharedQuestionContextRegion(1, 0.1, 0.1, 0.5, 0.2));
		assertThrows(IllegalStateException.class,
				() -> fixture.repository().save(fixture.firstBooklet(), "Question 21 shared context", regions));
		assertEquals(0, rowCount(fixture.database(), "shared_question_contexts"));
		assertEquals(0, rowCount(fixture.database(), "shared_question_context_regions"));
	}

	@Test
	void rollsBackEarlierRegionsWhenALaterRegionFails() throws Exception {
		RepositoryFixture fixture = createFixture("later-region-rollback.db");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_second_context_region
					BEFORE INSERT ON shared_question_context_regions
					WHEN NEW.page_number = 2
					BEGIN
					    SELECT RAISE(ABORT, 'reject later region');
					END
					""");
		}
		assertThrows(IllegalStateException.class,
				() -> fixture.repository().save(fixture.firstBooklet(), "Shared Context",
						List.of(new SharedQuestionContextRegion(1, 0.1, 0.1, 0.5, 0.2),
								new SharedQuestionContextRegion(2, 0.1, 0.1, 0.5, 0.2))));
		assertEquals(0, rowCount(fixture.database(), "shared_question_contexts"));
		assertEquals(0, rowCount(fixture.database(), "shared_question_context_regions"));
	}

	@Test
	void rollsBackSharedContextReplacementWhenRegionInsertFails() throws Exception {
		RepositoryFixture fixture = createFixture("replace-shared-context-rollback.db");
		SqliteSharedQuestionContextRepository repository = fixture.repository();
		SharedQuestionContextRegion originalRegion = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.60, 0.20);
		SharedQuestionContext original = repository.save(fixture.firstBooklet(), "Original shared context",
				List.of(originalRegion));

		// Fail only the replacement insert. The original context and region have
		// already been persisted successfully before this trigger is installed.
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_replacement_context_region
					BEFORE INSERT ON shared_question_context_regions
					WHEN NEW.page_number = 2
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'deliberate replacement failure');
					END
					""");
		}
		SharedQuestionContextRegion replacementRegion = new SharedQuestionContextRegion(2, 0.20, 0.25, 0.55, 0.30);
		assertThrows(IllegalStateException.class,
				() -> repository.replace(original, "Changed shared context", List.of(replacementRegion)));
		List<SharedQuestionContext> reloaded = repository.findByBooklet(fixture.firstBooklet());
		assertEquals(1, reloaded.size());
		SharedQuestionContext afterFailure = reloaded.getFirst();

		// replace() updates the context label and deletes its old regions before
		// inserting the replacement set. A failed insert must roll all of those
		// changes back within the same transaction.
		assertEquals(original.getId(), afterFailure.getId());
		assertEquals("Original shared context", afterFailure.getLabel());
		assertEquals(List.of(originalRegion), afterFailure.getRegions());
		assertEquals(1, rowCount(fixture.database(), "shared_question_contexts"));
		assertEquals(1, rowCount(fixture.database(), "shared_question_context_regions"));
	}

	@Test
	void savesReloadsOrderedRegionsAndScopesByBooklet() throws Exception {
		RepositoryFixture fixture = createFixture("shared-context.db");
		SqliteSharedQuestionContextRepository repository = fixture.repository();
		List<SharedQuestionContextRegion> regions = List.of(new SharedQuestionContextRegion(3, 0.10, 0.15, 0.70, 0.20),
				new SharedQuestionContextRegion(4, 0.12, 0.10, 0.65, 0.25));
		SharedQuestionContext saved = repository.save(fixture.firstBooklet(), "Question 21 shared context", regions);
		repository.save(fixture.secondBooklet(), "Other booklet context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20)));
		List<SharedQuestionContext> loaded = repository.findByBooklet(fixture.firstBooklet());
		assertEquals(1, loaded.size());
		SharedQuestionContext context = loaded.getFirst();
		assertEquals(saved.getId(), context.getId());
		assertEquals(fixture.firstBooklet().getId(), context.getBooklet().getId());
		assertEquals("Question 21 shared context", context.getLabel());
		assertEquals(regions, context.getRegions());
		assertEquals(1, repository.findByBooklet(fixture.secondBooklet()).size());
	}

	private RepositoryFixture createFixture(String databaseName) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet firstBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet secondBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/2025/paper2.pdf");
		return new RepositoryFixture(database, new SqliteSharedQuestionContextRepository(database), firstBooklet,
				secondBooklet);
	}

	private int rowCount(SqliteDatabase database, String table) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private boolean tableExists(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM sqlite_master
						WHERE type = 'table'
						  AND name = ?
						""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private record RepositoryFixture(SqliteDatabase database, SqliteSharedQuestionContextRepository repository,
			ExamBooklet firstBooklet, ExamBooklet secondBooklet) {
	}
}
