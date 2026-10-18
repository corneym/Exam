package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class QuestionCapturePaneQueueTest {

	@Test
	void bookletScopeExcludesOtherExamsAndOtherBooklets() {
		Subject chemistry = new Subject(2, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Question external = question(10, chemistry, provider, "Chemistry/2020/external.pdf", "1");
		Exam mockExam = new Exam(100, chemistry, provider, 2020, "Mock Examination");
		ExamBooklet mockMcq = new ExamBooklet(101, mockExam, "MCQ",
				new SourceDocument(102, "Chemistry/2020/mock-mcq.pdf"));
		ExamBooklet mockPaper1 = new ExamBooklet(103, mockExam, "Paper 1",
				new SourceDocument(104, "Chemistry/2020/mock-paper1.pdf"));
		Exam sampleExam = new Exam(200, chemistry, provider, 2020, "Sample Examination");
		ExamBooklet sampleMcq = new ExamBooklet(201, sampleExam, "MCQ",
				new SourceDocument(202, "Chemistry/2020/sample-mcq.pdf"));
		Question mockQuestion = new Question(110, mockMcq, "1", "", 1, List.of(), external.getClassification(), false,
				null, null, QuestionResponseType.MULTIPLE_CHOICE);
		Question otherMockPaper = new Question(111, mockPaper1, "1", "", 1, List.of(), external.getClassification(),
				false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
		Question sampleQuestion = new Question(210, sampleMcq, "1", "", 1, List.of(), external.getClassification(),
				false, null, null, QuestionResponseType.MULTIPLE_CHOICE);
		List<Question> allQuestions = List.of(external, mockQuestion, otherMockPaper, sampleQuestion);

		// Subject-wide correction deliberately retains Questions
		// from all three different examinations.
		assertEquals(allQuestions, QuestionCapturePane.awaitingCaptureForWorkingSubject(allQuestions, chemistry));

		// Opening Mock MCQ permits only Mock MCQ Question work.
		assertEquals(List.of(mockQuestion),
				QuestionCapturePane.awaitingCaptureForWorkingSubject(allQuestions, chemistry, mockMcq.getId()));

		// Another booklet under the same Mock Exam must also be
		// excluded from the MCQ queue.
		assertEquals(List.of(otherMockPaper),
				QuestionCapturePane.awaitingCaptureForWorkingSubject(allQuestions, chemistry, mockPaper1.getId()));

		// Sample MCQ is not interchangeable with Mock MCQ.
		assertEquals(List.of(sampleQuestion),
				QuestionCapturePane.awaitingCaptureForWorkingSubject(allQuestions, chemistry, sampleMcq.getId()));

		// A booklet with no outstanding metadata must produce an
		// empty queue, not a fallback to Subject-wide Questions.
		assertEquals(List.of(), QuestionCapturePane.awaitingCaptureForWorkingSubject(allQuestions, chemistry, 9999L));
	}

	@Test
	void imageOnlyQuestionDoesNotRemainInAwaitingCaptureQueue() {
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Subject chemistry = new Subject(2, "Chemistry");
		Question imported = question(10, chemistry, provider, "Chemistry/2024/paper.pdf", "1");
		Question imageOnly = new Question(imported.getId(), imported.getBooklet(), imported.getQuestionCode(),
				imported.getQuestionText(), imported.getMarks(), List.of(), imported.getClassification(), false, null,
				null, QuestionResponseType.WRITTEN_RESPONSE, List.of(new ImageQuestionContentPart(new byte[] { 1 })));
		List<Question> awaiting = QuestionCapturePane.awaitingCaptureForWorkingSubject(List.of(imageOnly), chemistry);
		assertEquals(List.of(), awaiting);
	}

	@Test
	void importedQuestionLabelsDistinguishExaminationNames() {
		Subject chemistry = new Subject(2, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Question external = question(10, chemistry, provider, "Chemistry/2020/external.pdf", "1");
		Exam mockExam = new Exam(70, chemistry, provider, 2024, "Mock Examination");
		ExamBooklet mockBooklet = new ExamBooklet(71, mockExam, "Paper 1",
				new SourceDocument(72, "Chemistry/2020/mock.pdf"));
		Question mock = new Question(73, mockBooklet, "1", "", 1, List.of(), external.getClassification(), false, null,
				null, QuestionResponseType.WRITTEN_RESPONSE);
		Exam sampleExam = new Exam(80, chemistry, provider, 2024, "Sample Examination");
		ExamBooklet sampleBooklet = new ExamBooklet(81, sampleExam, "Paper 1",
				new SourceDocument(82, "Chemistry/2020/sample.pdf"));
		Question sample = new Question(83, sampleBooklet, "1", "", 1, List.of(), external.getClassification(), false,
				null, null, QuestionResponseType.WRITTEN_RESPONSE);
		ImportedQuestionStringConverter converter = new ImportedQuestionStringConverter();
		assertEquals("QCAA 2024 — External Assessment — Paper 1 — 1 — 1 mark(s)", converter.toString(external));
		assertEquals("QCAA 2024 — Mock Examination — Paper 1 — 1 — 1 mark(s)", converter.toString(mock));
		assertEquals("QCAA 2024 — Sample Examination — Paper 1 — 1 — 1 mark(s)", converter.toString(sample));
		assertEquals("", converter.toString(null));
	}

	@Test
	void replacementInvalidatedQuestionRemainsInQueueDespitePreservedImageContent() {
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Subject chemistry = new Subject(2, "Chemistry");
		Question imported = question(10, chemistry, provider, "Chemistry/2024/paper.pdf", "1");
		Question replacementInvalidated = new Question(imported.getId(), imported.getBooklet(),
				imported.getQuestionCode(), imported.getQuestionText(), imported.getMarks(), List.of(),
				imported.getClassification(), false, null, null, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new ImageQuestionContentPart(new byte[] { 1 })), true);
		List<Question> awaiting = QuestionCapturePane.awaitingCaptureForWorkingSubject(List.of(replacementInvalidated),
				chemistry);

		// Image content is preserved, but explicit source invalidation keeps the
		// Question in the operational recapture queue.
		assertEquals(List.of(replacementInvalidated), awaiting);
	}

	@Test
	void workingSubjectExcludesImportedQuestionsFromOtherSubjects() {
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Subject chemistry = new Subject(2, "Chemistry");
		Question chemistryQuestion = question(10, chemistry, provider, "Chemistry/2024/paper.pdf", "1");
		Subject engineering = new Subject(20, "Engineering");
		Question engineeringQuestion = question(30, engineering, provider, "Engineering/2024/paper.pdf", "2");

		// Working Subject changes only which uncaptured Questions are presented to
		// the user. Questions belonging to other Subjects remain stored unchanged.
		List<Question> result = QuestionCapturePane
				.awaitingCaptureForWorkingSubject(List.of(engineeringQuestion, chemistryQuestion), chemistry);
		assertEquals(List.of(chemistryQuestion), result);
	}

	private Question question(long id, Subject subject, ExamProvider provider, String sourcePath, String questionCode) {
		SyllabusVersion syllabus = new SyllabusVersion(id + 1, subject, "2025", true);
		Unit unit = new Unit(id + 2, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(id + 3, syllabus, unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = new Descriptor(id + 4, syllabus, topic, "1.1.1", "Descriptor 1", 1);
		Exam exam = new Exam(id + 5, subject, provider, 2024, "External Assessment");
		ExamBooklet booklet = new ExamBooklet(id + 6, exam, "Paper 1", new SourceDocument(id + 7, sourcePath));

		// An empty region list represents an imported Question still awaiting
		// Question-region capture.
		return new Question(id, booklet, questionCode, "", 1, List.of(), descriptor, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
	}
}
