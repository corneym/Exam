package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
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

	private record RepositoryFixture(SqliteDatabase database, SqliteSharedQuestionContextRepository repository,
			ExamBooklet firstBooklet, ExamBooklet secondBooklet) {
	}
}
