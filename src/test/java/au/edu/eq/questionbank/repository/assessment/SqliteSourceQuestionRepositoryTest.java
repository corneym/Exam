package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteSourceQuestionRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void savesReloadsAndScopesSourceQuestionsByBooklet() throws Exception {
		RepositoryFixture fixture = createFixture("source-questions.db");
		SqliteSourceQuestionRepository repository = fixture.repository();
		SourceQuestion first = repository.save(fixture.firstBooklet(), "21");
		SourceQuestion second = repository.save(fixture.secondBooklet(), "21");
		assertEquals(SharedContextStatus.UNKNOWN, first.getSharedContextStatus());
		assertEquals("21", first.getSourceQuestionCode());
		assertEquals(fixture.firstBooklet().getId(), first.getBooklet().getId());
		assertTrue(first.getId() > 0);
		assertEquals(1, repository.findByBooklet(fixture.firstBooklet()).size());
		assertEquals(first.getId(),
				repository.findByBookletAndCode(fixture.firstBooklet(), "21").orElseThrow().getId());
		assertEquals(second.getId(),
				repository.findByBookletAndCode(fixture.secondBooklet(), "21").orElseThrow().getId());
		assertThrows(IllegalArgumentException.class, () -> repository.save(fixture.firstBooklet(), "21"));
		SourceQuestion updated = repository.updatePreambleStatus(first, SharedContextStatus.PRESENT);
		assertEquals(SharedContextStatus.PRESENT, updated.getSharedContextStatus());
		SourceQuestion reloaded = repository.findByBookletAndCode(fixture.firstBooklet(), "21").orElseThrow();
		assertEquals(SharedContextStatus.PRESENT, reloaded.getSharedContextStatus());
	}

	@Test
	void rejectsInvalidArgumentsAndMissingPersistentIdentity() throws Exception {
		RepositoryFixture fixture = createFixture("invalid-source-questions.db");
		SqliteSourceQuestionRepository repository = fixture.repository();
		SourceQuestion missing = new SourceQuestion(999, fixture.firstBooklet(), "21");
		assertAll(() -> assertThrows(NullPointerException.class, () -> new SqliteSourceQuestionRepository(null)),
				() -> assertThrows(NullPointerException.class, () -> repository.findByBooklet(null)),
				() -> assertThrows(NullPointerException.class, () -> repository.findByBookletAndCode(null, "21")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> repository.findByBookletAndCode(fixture.firstBooklet(), " ")),
				() -> assertThrows(NullPointerException.class, () -> repository.save(null, "21")),
				() -> assertThrows(IllegalArgumentException.class, () -> repository.save(fixture.firstBooklet(), " ")),
				() -> assertThrows(NullPointerException.class,
						() -> repository.updatePreambleStatus(null, SharedContextStatus.NONE)),
				() -> assertThrows(NullPointerException.class, () -> repository.updatePreambleStatus(missing, null)),
				() -> assertThrows(IllegalStateException.class,
						() -> repository.updatePreambleStatus(missing, SharedContextStatus.PRESENT)));
	}

	@Test
	void returnsSourceQuestionsInPersistentInsertionOrder() throws Exception {
		RepositoryFixture fixture = createFixture("ordered-source-questions.db");
		SqliteSourceQuestionRepository repository = fixture.repository();
		repository.save(fixture.firstBooklet(), "24");
		repository.save(fixture.firstBooklet(), "7");
		repository.save(fixture.firstBooklet(), "19");
		assertEquals(List.of("24", "7", "19"), repository.findByBooklet(fixture.firstBooklet()).stream()
				.map(SourceQuestion::getSourceQuestionCode).toList());
	}

	@Test
	void roundTripsResolvedPreambleStates() throws Exception {
		RepositoryFixture fixture = createFixture("preamble-states.db");
		SqliteSourceQuestionRepository repository = fixture.repository();
		SourceQuestion sourceQuestion = repository.save(fixture.firstBooklet(), "21");
		sourceQuestion = repository.updatePreambleStatus(sourceQuestion, SharedContextStatus.NONE);
		assertEquals(SharedContextStatus.NONE,
				repository.findByBookletAndCode(fixture.firstBooklet(), "21").orElseThrow().getSharedContextStatus());
		repository.updatePreambleStatus(sourceQuestion, SharedContextStatus.PRESENT);
		assertEquals(SharedContextStatus.PRESENT,
				repository.findByBookletAndCode(fixture.firstBooklet(), "21").orElseThrow().getSharedContextStatus());
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
		return new RepositoryFixture(new SqliteSourceQuestionRepository(database), firstBooklet, secondBooklet);
	}

	private record RepositoryFixture(SqliteSourceQuestionRepository repository, ExamBooklet firstBooklet,
			ExamBooklet secondBooklet) {
	}
}
