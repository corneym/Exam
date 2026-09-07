package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
	void savesReloadsOrderedRegionsAndScopesByBooklet() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("shared-context.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet firstBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet secondBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/2025/paper2.pdf");
		SqliteSharedQuestionContextRepository repository = new SqliteSharedQuestionContextRepository(database);
		List<SharedQuestionContextRegion> regions = List.of(new SharedQuestionContextRegion(3, 0.10, 0.15, 0.70, 0.20),
				new SharedQuestionContextRegion(4, 0.12, 0.10, 0.65, 0.25));
		SharedQuestionContext saved = repository.save(firstBooklet, "Question 21 preamble", regions);
		repository.save(secondBooklet, "Other booklet context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20)));
		List<SharedQuestionContext> loaded = repository.findByBooklet(firstBooklet);
		assertEquals(1, loaded.size());
		SharedQuestionContext context = loaded.getFirst();
		assertEquals(saved.getId(), context.getId());
		assertEquals(firstBooklet.getId(), context.getBooklet().getId());
		assertEquals("Question 21 preamble", context.getLabel());
		assertEquals(2, context.getRegions().size());
		assertEquals(3, context.getRegions().get(0).pageNumber());
		assertEquals(4, context.getRegions().get(1).pageNumber());
		assertEquals(0.10, context.getRegions().get(0).x());
		assertEquals(0.12, context.getRegions().get(1).x());
		assertTrue(repository.findByBooklet(secondBooklet).size() == 1);
	}
}
