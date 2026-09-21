package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class AnswerCapturePaneOrderingTest {

	private final Subject chemistry = new Subject(1, "Chemistry");
	private final SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
	private final Unit unit = new Unit(3, syllabus, "1", "Unit 1", 1);
	private final Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic 1", 1);
	private final Descriptor descriptor = new Descriptor(5, syllabus, topic, "1.1.1", "Descriptor 1", 1);
	private final ExamProvider provider = new ExamProvider(6, "QCAA");
	private final Exam exam = new Exam(7, chemistry, provider, 2024, "External Assessment");
	private final ExamBooklet booklet = new ExamBooklet(8, exam, "Paper 1",
			new SourceDocument(9, "Chemistry/2024/paper1.pdf"));

	@Test
	void unansweredQueueUsesNaturalQuestionOrder() {
		Question question10 = question(10, "10");
		Question question3b = question(11, "3b");
		Question question3 = question(12, "3");
		Question question3a = question(13, "3a");
		Question persistedAnsweredQuestion = question(14, "2");
		Question locallyAnsweredQuestion = question(15, "1");
		/*
		 * A persisted Answer must remove its Question from the Answer-capture queue.
		 */
		persistedAnsweredQuestion.setAnswer(new Answer(20, "A", List.of()));
		/*
		 * Supply Questions deliberately out of source order and separately mark one as
		 * answered after the snapshot was obtained.
		 */
		List<Question> result = AnswerCapturePane.unansweredQuestionsInSourceOrder(List.of(question10, question3b,
				persistedAnsweredQuestion, question3, locallyAnsweredQuestion, question3a),
				Set.of(locallyAnsweredQuestion.getId()));
		/*
		 * Only unanswered Questions remain, and their codes follow natural examination
		 * order rather than insertion or database-ID order.
		 */
		assertEquals(List.of("3", "3a", "3b", "10"), result.stream().map(Question::getQuestionCode).toList());
	}

	@Test
	void workingSubjectExcludesUnansweredQuestionsFromOtherSubjects() {
		Question chemistryQuestion = question(10, "4");

		// Build one otherwise valid unanswered Question belonging to a different
		// Subject. Its capture state is deliberately equivalent to the Chemistry
		// Question so Subject alone determines whether it belongs in the work queue.
		Subject engineering = new Subject(30, "Engineering");
		SyllabusVersion engineeringSyllabus = new SyllabusVersion(31, engineering, "2025", true);
		Unit engineeringUnit = new Unit(32, engineeringSyllabus, "1", "Unit 1", 1);
		Topic engineeringTopic = new Topic(33, engineeringSyllabus, engineeringUnit, "1.1", "Topic 1", 1);
		Descriptor engineeringDescriptor = new Descriptor(34, engineeringSyllabus, engineeringTopic, "1.1.1",
				"Descriptor 1", 1);
		Exam engineeringExam = new Exam(35, engineering, provider, 2024, "External Assessment");
		ExamBooklet engineeringBooklet = new ExamBooklet(36, engineeringExam, "Paper 1",
				new SourceDocument(37, "Engineering/2024/paper1.pdf"));
		Question engineeringQuestion = new Question(38, engineeringBooklet, "1", "", 1, List.of(),
				engineeringDescriptor, false, null, null, QuestionResponseType.WRITTEN_RESPONSE);

		// The Working Subject is a transient queue filter. Questions belonging to
		// other Subjects remain stored but are omitted from the active Answer queue.
		List<Question> result = AnswerCapturePane
				.unansweredQuestionsInSourceOrder(List.of(engineeringQuestion, chemistryQuestion), Set.of(), chemistry);
		assertEquals(List.of(chemistryQuestion), result);
	}

	private Question question(long id, String questionCode) {
		/*
		 * Answer-queue ordering depends only on Question metadata, so source regions
		 * are unnecessary in this focused test fixture.
		 */
		return new Question(id, booklet, questionCode, "", 1, List.of(), descriptor, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
	}
}
