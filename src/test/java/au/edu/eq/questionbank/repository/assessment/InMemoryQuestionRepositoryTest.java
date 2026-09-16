package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class InMemoryQuestionRepositoryTest {

	private CurriculumNode classification;
	private CurriculumNode refinedClassification;
	private Exam exam;
	private ExamBooklet booklet;
	private InMemoryQuestionRepository repository;

	@Test
	void preservesResponseTypeWhenQuestionIsReconstructed() {
		Question saved = repository.save(booklet, "Q1", "Question Q1", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0)), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, saved.getResponseType());
		Question updated = repository.updateCaptureRelationships(saved.getId(), refinedClassification, null, null);
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.getResponseType());
	}

	@Test
	void returnsEmptyWhenNoQuestionHasTheRequestedId() {
		saveQuestion("Q1", 1);
		assertTrue(repository.findById(2).isEmpty());
	}

	@Test
	void savesQuestionsAndFindsThemById() {
		Question first = saveQuestion("Q1", 1);
		Question second = saveQuestion("Q2", 2);
		assertEquals(1, first.getId());
		assertEquals(2, second.getId());
		assertEquals(List.of(first, second), repository.findAll());
		assertSame(second, repository.findById(second.getId()).orElseThrow());
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Biology");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		classification = new Subtopic(3, syllabus, topic, "1.1.1", "Subtopic 1", 1);
		refinedClassification = new Descriptor(4, syllabus, (Subtopic) classification, "1.1.1.1", "Descriptor 1", 1);
		exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2025, "External assessment");
		booklet = new ExamBooklet(1, exam, "Question booklet", new SourceDocument(1, "biology/2025/exam.pdf"));
		repository = new InMemoryQuestionRepository();
	}

	@Test
	void updatesClassificationWithoutReplacingExistingRegions() {
		Question existing = saveQuestion("Q1", 1);
		Question updated = repository.updateCaptureRelationships(existing.getId(), refinedClassification, null, null);
		assertSame(refinedClassification, updated.getClassification());
		assertEquals(existing.getRegions(), updated.getRegions());
	}

	private Question saveQuestion(String code, int pageNumber) {
		return repository.save(booklet, code, "Question " + code, 1,
				List.of(new QuestionRegion(booklet, pageNumber, 0.0, 0.0, 1.0, 1.0)), classification, false);
	}
}
