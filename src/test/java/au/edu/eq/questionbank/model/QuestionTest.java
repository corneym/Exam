package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionTest {

	private Exam exam;
	private ExamBooklet booklet;

	@Test
	void acceptsAnswerRegionsFromTheSameExam() {
		Question question = new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());
		AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", new SourceDocument(10, "answers.pdf"));
		AnswerRegion answerRegion = new AnswerRegion(answerFile, 2, 0.1, 0.2, 0.8, 0.3);
		Answer answer = new Answer(1, null, List.of(answerRegion));
		question.setAnswer(answer);
		assertSame(answer, question.getAnswer());
	}

	@Test
	void acceptsATextOnlyAnswer() {
		Question question = new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());
		Answer answer = new Answer(1, "B", List.of());
		question.setAnswer(answer);
		assertTrue(question.hasAnswer());
		assertSame(answer, question.getAnswer());
	}

	@Test
	void acceptsDescriptorClassification() {
		CurriculumNode classification = createDescriptorClassification();
		Question question = new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), classification);
		assertSame(classification, question.getClassification());
		assertEquals(CurriculumLevel.DESCRIPTOR, question.getClassification().getLevel());
	}

	@Test
	void acceptsImportedQuestionWithoutRegions() {
		CurriculumNode classification = createClassification();
		Question question = new Question(33, booklet, "21a", "", 1, List.of(), classification, true);
		assertAll(() -> assertSame(booklet, question.getBooklet()), () -> assertSame(exam, question.getExam()),
				() -> assertTrue(question.getRegions().isEmpty()),
				() -> assertTrue(question.isPreambleCaptureRequired()),
				() -> assertSame(classification, question.getClassification()));
	}

	@Test
	void explicitBookletRejectsRegionFromAnotherBooklet() {
		ExamBooklet secondBooklet = new ExamBooklet(99, exam, "Second booklet", new SourceDocument(99, "second.pdf"));
		QuestionRegion region = new QuestionRegion(secondBooklet, 1, 0.0, 0.0, 1.0, 0.5);
		assertThrows(IllegalArgumentException.class,
				() -> new Question(1, booklet, "Q1", "Question", 1, List.of(region), createClassification(), false));
	}

	@Test
	void importedQuestionCanHaveNoPreambleCaptureRequirement() {
		Question question = new Question(33, booklet, "7", "", 1, List.of(), createClassification(), false);
		assertFalse(question.isPreambleCaptureRequired());
	}

	@Test
	void questionWithoutLegacyRequirementIsNotUnresolved() {
		CurriculumNode classification = createClassification();
		Question question = new Question(1, booklet, "Q1", "", 1, List.of(), classification, false);
		assertFalse(question.isPreambleCaptureRequired());
		assertFalse(question.isSharedContextUnresolved());
	}

	@Test
	void rejectInvalidClassification() {
		assertThrows(IllegalArgumentException.class, () -> new Question(36, exam, "Q6", "Invalid classification", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0)), createInvalidClassification()));
	}

	@Test
	void rejectsAnEmptyRegionList() {
		assertThrows(IllegalArgumentException.class,
				() -> new Question(33, exam, "Q4", "Missing region.", 1, List.of(), createClassification()));
	}

	@Test
	void rejectsAnswerRegionFromDifferentExam() {
		Question question = new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());
		Exam otherExam = new Exam(99, exam.getSubject(), exam.getProvider(), exam.getYear(), "Other exam");
		AnswerFile answerFile = new AnswerFile(99, otherExam, "Other marking guide",
				new SourceDocument(99, "other-answers.pdf"));
		Answer answer = new Answer(1, null, List.of(new AnswerRegion(answerFile, 1, 0.0, 0.0, 1.0, 0.5)));
		assertThrows(IllegalArgumentException.class, () -> question.setAnswer(answer));
	}

	@Test
	void rejectsANullClassification() {
		assertThrows(NullPointerException.class, () -> new Question(37, exam, "Q7", "Missing classification.", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0)), null));
	}

	@Test
	void rejectsANullRegionElement() {
		List<QuestionRegion> regions = new ArrayList<>();
		regions.add(null);
		assertThrows(NullPointerException.class,
				() -> new Question(35, exam, "Q6", "Null region.", 1, regions, createClassification()));
	}

	@Test
	void rejectsANullRegionList() {
		assertThrows(NullPointerException.class,
				() -> new Question(34, exam, "Q5", "Missing regions.", 1, null, createClassification()));
	}

	@Test
	void rejectsClassificationFromDifferentSubject() {
		Subject chemistry = new Subject(79, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit = new Unit(4, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(5, syllabus, unit, "1.1", "Topic 1", 1);
		Subtopic classification = new Subtopic(6, syllabus, topic, "1.1.1", "Subtopic 1", 1);
		assertThrows(IllegalArgumentException.class, () -> new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), classification));
	}

	@Test
	void rejectsDescriptorFromDifferentSubject() {
		Subject chemistry = new Subject(79, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit = new Unit(4, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(5, syllabus, unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = new Descriptor(6, syllabus, topic, "1.1.1", "Descriptor 1", 1);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new Question(1, exam, "Q1",
				"Question", 1, List.of(new QuestionRegion(booklet, 1, 0, 0, 1, 1)), descriptor));
		assertEquals("Question classification must belong to the exam's subject", error.getMessage());
	}

	@Test
	void rejectsInvalidPersistentMetadata() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5);
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Question(0, exam, "Q1", "Question", 1, List.of(region), createClassification())),
				() -> assertThrows(NullPointerException.class,
						() -> new Question(1, null, "Q1", "Question", 1, List.of(region), createClassification())),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Question(1, exam, " ", "Question", 1, List.of(region), createClassification())),
				() -> assertThrows(NullPointerException.class,
						() -> new Question(1, exam, "Q1", null, 1, List.of(region), createClassification())));
	}

	@Test
	void rejectsNonPositiveMarks() {
		assertThrows(IllegalArgumentException.class,
				() -> new Question(1, booklet, "Q1", "", 0, List.of(), createClassification(), false));
	}

	@Test
	void rejectsNullAnswer() {
		Question question = new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());
		assertThrows(NullPointerException.class, () -> question.setAnswer(null));
	}

	@Test
	void rejectsRegionFromDifferentExam() {
		Exam otherExam = new Exam(99, exam.getSubject(), exam.getProvider(), exam.getYear(), "Other exam");
		ExamBooklet otherBooklet = new ExamBooklet(99, otherExam, "Other booklet", new SourceDocument(99, "other.pdf"));
		assertThrows(IllegalArgumentException.class, () -> new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(otherBooklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification()));
	}

	@Test
	void rejectsRegionsFromDifferentBooklets() {
		ExamBooklet secondBooklet = new ExamBooklet(99, exam, "Second booklet", new SourceDocument(99, "second.pdf"));
		assertThrows(IllegalArgumentException.class,
				() -> new Question(1, exam, "Q1", "Question", 1,
						List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.4),
								new QuestionRegion(secondBooklet, 2, 0.0, 0.5, 1.0, 0.4)),
						createClassification()));
	}

	@Test
	void rejectsSourceQuestionAndSharedContextFromAnotherBooklet() {
		CurriculumNode classification = createClassification();
		ExamBooklet otherBooklet = new ExamBooklet(99, exam, "Other booklet", new SourceDocument(99, "other.pdf"));
		SourceQuestion otherSourceQuestion = new SourceQuestion(50, otherBooklet, "21");
		SharedQuestionContext otherContext = new SharedQuestionContext(60, otherBooklet, "Other preamble",
				List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 0.2)));
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Question(33, booklet, "21a", "", 2, List.of(), classification, true,
								otherSourceQuestion, null)),
				() -> assertThrows(IllegalArgumentException.class, () -> new Question(33, booklet, "21a", "", 2,
						List.of(), classification, true, null, otherContext)));
	}

	@Test
	void rejectsTopicClassification() {
		SyllabusVersion syllabus = new SyllabusVersion(1, exam.getSubject(), "2026", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		assertThrows(IllegalArgumentException.class, () -> new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0, 0, 1, 1)), topic));
	}

	@Test
	void reportsLegacySharedContextRequirementAsUnresolved() {
		CurriculumNode classification = createClassification();
		Question question = new Question(1, booklet, "21a", "", 2, List.of(), classification, true);
		assertTrue(question.isPreambleCaptureRequired());
		assertTrue(question.isSharedContextUnresolved());
		assertFalse(question.hasSharedContext());
	}

	@Test
	void retainsAQuestionWithOneRegion() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.1, 0.2, 0.8, 0.3);
		CurriculumNode classification = createClassification();
		Question question = new Question(30, exam, "Q1", "Calculate the result.", 1, List.of(region), classification);
		assertAll(() -> assertEquals(30, question.getId()), () -> assertSame(exam, question.getExam()),
				() -> assertEquals("Q1", question.getQuestionCode()),
				() -> assertEquals("Calculate the result.", question.getQuestionText()),
				() -> assertEquals(List.of(region), question.getRegions()),
				() -> assertSame(booklet, question.getBooklet()),
				() -> assertFalse(question.isPreambleCaptureRequired()),
				() -> assertSame(classification, question.getClassification()));
	}

	@Test
	void retainsMarks() {
		Question question = new Question(1, booklet, "Q1", "", 4, List.of(), createClassification(), false);
		assertEquals(4, question.getMarks());
	}

	@Test
	void retainsMultipleRegionsInTheirOriginalOrder() {
		QuestionRegion firstPagePart = new QuestionRegion(booklet, 1, 0.0, 0.7, 1.0, 0.2);
		QuestionRegion secondPagePart = new QuestionRegion(booklet, 2, 0.0, 0.1, 1.0, 0.4);
		Question question = new Question(31, exam, "Q2", "A question spanning pages.", 1,
				List.of(firstPagePart, secondPagePart), createClassification());
		assertAll(() -> assertEquals(List.of(firstPagePart, secondPagePart), question.getRegions()));
	}

	@Test
	void retainsSourceQuestionAndSharedContextRelationships() {
		CurriculumNode classification = createClassification();
		SourceQuestion sourceQuestion = new SourceQuestion(50, booklet, "21");
		SharedQuestionContext sharedContext = new SharedQuestionContext(60, booklet, "Question 21 preamble",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.2)));
		Question question = new Question(33, booklet, "21a", "", 2, List.of(), classification, true, sourceQuestion,
				sharedContext);
		assertAll(() -> assertSame(sourceQuestion, question.getSourceQuestion()),
				() -> assertSame(sharedContext, question.getSharedContext()),
				() -> assertTrue(question.hasSourceQuestion()), () -> assertTrue(question.hasSharedContext()));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(78, "Psych");
		ExamProvider provider = new ExamProvider(45, "QCAA");
		exam = new Exam(9, subject, provider, 2020, "Test exam");
		SourceDocument document = new SourceDocument(4, "path");
		booklet = new ExamBooklet(3, exam, "Test booklet", document);
	}

	@Test
	void sharedContextResolvesLegacyRequirementWithoutRemovingHistoricalFlag() {
		CurriculumNode classification = createClassification();
		SharedQuestionContext sharedContext = new SharedQuestionContext(1, booklet, "Question 21 preamble",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20)));
		Question question = new Question(1, booklet, "21a", "", 2, List.of(), classification, true, null,
				sharedContext);
		assertTrue(question.isPreambleCaptureRequired());
		assertTrue(question.hasSharedContext());
		assertFalse(question.isSharedContextUnresolved());
	}

	@Test
	void startsWithoutAnAnswer() {
		Question question = new Question(1, exam, "Q1", "Question", 1,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());
		assertFalse(question.hasAnswer());
		assertEquals(null, question.getAnswer());
	}

	@Test
	void takesAnImmutableSnapshotOfRegions() {
		QuestionRegion originalRegion = new QuestionRegion(booklet, 1, 0.0, 0.2, 1.0, 0.4);
		List<QuestionRegion> suppliedRegions = new ArrayList<>(List.of(originalRegion));
		Question question = new Question(32, exam, "Q3", "Protected regions.", 1, suppliedRegions,
				createClassification());
		suppliedRegions.add(new QuestionRegion(booklet, 2, 0.0, 0.6, 1.0, 0.1));
		assertEquals(List.of(originalRegion), question.getRegions());
		assertThrows(UnsupportedOperationException.class,
				() -> question.getRegions().add(new QuestionRegion(booklet, 3, 0.0, 0.0, 1.0, 1.0)));
	}

	private CurriculumNode createClassification() {
		Subject subject = exam.getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		return new Subtopic(3, syllabus, topic, "1.1.1", "Subtopic 1", 1);
	}

	private CurriculumNode createDescriptorClassification() {
		Subject subject = exam.getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		return new Descriptor(3, syllabus, topic, "1.1.1", "Descriptor 1", 1);
	}

	private CurriculumNode createInvalidClassification() {
		Subject subject = exam.getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		return new Unit(1, syllabus, "1", "Unit 1", 1);
	}
}
