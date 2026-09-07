package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteSourceQuestionRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void savesReloadsAndScopesSourceQuestionsByBooklet() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("source-questions.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet firstBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet secondBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/2025/paper2.pdf");
		SqliteSourceQuestionRepository repository = new SqliteSourceQuestionRepository(database);
		SourceQuestion first = repository.save(firstBooklet, "21");
		SourceQuestion second = repository.save(secondBooklet, "21");
		assertEquals("21", first.getSourceQuestionCode());
		assertEquals(firstBooklet.getId(), first.getBooklet().getId());
		assertTrue(first.getId() > 0);
		assertEquals(1, repository.findByBooklet(firstBooklet).size());
		assertEquals(first.getId(), repository.findByBookletAndCode(firstBooklet, "21").orElseThrow().getId());
		assertEquals(second.getId(), repository.findByBookletAndCode(secondBooklet, "21").orElseThrow().getId());
		assertThrows(IllegalArgumentException.class, () -> repository.save(firstBooklet, "21"));
	}
}
