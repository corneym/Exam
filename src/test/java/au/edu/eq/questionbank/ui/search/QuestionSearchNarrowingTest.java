package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class QuestionSearchNarrowingTest {

	private final Subject subject = new Subject(1, "Physics");
	private final SyllabusVersion syllabus = new SyllabusVersion(2, subject, "2025", true);
	private final Unit unit = new Unit(3, syllabus, "1", "Unit", 1);
	private final Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic", 1);
	private final Subtopic subtopic = new Subtopic(5, syllabus, topic, "1.1.1", "Subtopic", 1);
	private final Descriptor descriptor = new Descriptor(6, syllabus, subtopic, "1.1.1.1", "Descriptor", 1);
	private final Exam exam = new Exam(7, subject, new ExamProvider(8, "QCAA"), 2025, "External Assessment");
	private final ExamBooklet booklet = new ExamBooklet(9, exam, "Paper 1", new SourceDocument(10, "paper1.pdf"));

	@Test
	void displayedQueueSnapshotsMissingIdsAndRechecksStoredClassification() {
		Question first = question(20, subtopic);
		Question classified = question(21, descriptor);
		Question outside = question(22, subtopic);
		List<Question> displayed = new ArrayList<>(List.of(first, classified));
		QuestionSearchNarrowing queue = QuestionSearchNarrowing.forQuestionWork(displayed);
		displayed.clear();
		displayed.add(outside);

		// Freshly retrieved objects with the same persistent identity still match.
		assertTrue(queue.isDescriptorAssignmentQueue());
		assertTrue(queue.includes(question(20, subtopic)));
		assertFalse(queue.includes(question(20, descriptor)));
		assertFalse(queue.includes(outside));
		assertFalse(queue.includes(classified));
		assertFalse(queue.includes(question(21, subtopic)), "Already classified at invocation is outside the queue");
	}

	@Test
	void emptyDisplayedQueueCannotExpandToAllQuestions() {
		QuestionSearchNarrowing queue = QuestionSearchNarrowing.forQuestionWork(List.of());
		assertTrue(queue.isRestricted());
		assertTrue(queue.isDescriptorAssignmentQueue());
		assertFalse(queue.includes(question(20, subtopic)));
	}

	private Question question(long id, CurriculumNode classification) {
		return new Question(id, booklet, "Q" + id, "", 1, List.of(), classification, false);
	}
}
