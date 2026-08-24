package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class InMemoryQuestionRepositoryTest {

	private CurriculumNode classification;
	private Exam exam;
	private ExamBooklet booklet;
	private InMemoryQuestionRepository repository;

	private Question createQuestion(long id, String code, int pageNumber) {
		return new Question(id, exam, code, "Question " + code,
				List.of(new QuestionRegion(booklet, pageNumber, 0, 1)), classification);
	}

	@Test
	void returnsEmptyWhenNoQuestionHasTheRequestedId() {
		repository.save(createQuestion(1, "Q1", 1));

		assertTrue(repository.findById(2).isEmpty());
	}

	@Test
	void savesQuestionsAndFindsThemById() {
		Question first = createQuestion(1, "Q1", 1);
		Question second = createQuestion(2, "Q2", 2);

		repository.save(first);
		repository.save(second);

		assertEquals(List.of(first, second), repository.findAll());
		assertSame(second, repository.findById(2).orElseThrow());
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Biology");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		CurriculumNode unit = new CurriculumNode(1, syllabus, null, "1", "Unit 1", CurriculumLevel.UNIT, 1);
		CurriculumNode topic = new CurriculumNode(2, syllabus, unit, "1.1", "Topic 1", CurriculumLevel.TOPIC, 1);
		classification = new CurriculumNode(3, syllabus, topic, "1.1.1", "Subtopic 1",
				CurriculumLevel.SUBTOPIC, 1);

		exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2025, "External assessment");
		booklet = new ExamBooklet(1, exam, "Question booklet", new SourceDocument(1, "biology/2025/exam.pdf"));
		repository = new InMemoryQuestionRepository();
	}
}
