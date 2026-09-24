package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class InMemoryQuestionOutputApplicabilityRepositoryTest {

	private Descriptor firstDescriptor;
	private Descriptor secondDescriptor;
	private Question question;
	private InMemoryQuestionOutputApplicabilityRepository repository;

	@Test
	void defaultsToIncludedAndTogglesIndividualExclusions() {
		assertTrue(repository.findExcludedCurrentNodeIds(question).isEmpty());
		repository.setExcluded(question, firstDescriptor, true);
		repository.setExcluded(question, secondDescriptor, true);
		assertEquals(Set.of(firstDescriptor.getId(), secondDescriptor.getId()),
				repository.findExcludedCurrentNodeIds(question));
		repository.setExcluded(question, firstDescriptor, false);
		assertEquals(Set.of(secondDescriptor.getId()), repository.findExcludedCurrentNodeIds(question));

		// Restoring an already-included node must remain a harmless no-op.
		repository.setExcluded(question, firstDescriptor, false);
		assertEquals(Set.of(secondDescriptor.getId()), repository.findExcludedCurrentNodeIds(question));
	}

	@Test
	void rejectsTopicAsOutputExclusionTarget() {
		Topic topic = (Topic) firstDescriptor.getParent();

		// Question placements belong only at Subtopic or Descriptor level.
		assertThrows(IllegalArgumentException.class, () -> repository.setExcluded(question, topic, true));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		firstDescriptor = new Descriptor(3, syllabus, topic, "1.1.1", "Descriptor 1", 1);
		secondDescriptor = new Descriptor(4, syllabus, topic, "1.1.2", "Descriptor 2", 2);
		Exam exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2025, "External assessment");
		ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "chemistry/2025/paper1.pdf"));
		InMemoryQuestionRepository questionRepository = new InMemoryQuestionRepository();
		question = questionRepository.save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), firstDescriptor, false);
		repository = new InMemoryQuestionOutputApplicabilityRepository();
	}
}
