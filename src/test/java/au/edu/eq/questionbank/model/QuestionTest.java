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

	private CurriculumNode createClassification() {
		Subject subject = exam.getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		return new Subtopic(3, syllabus, topic, "1.1.1", "Subtopic 1", 1);
	}

	private CurriculumNode createInvalidClassification() {
		Subject subject = exam.getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		return new Unit(1, syllabus, "1", "Unit 1", 1);
	}

	@Test
	void acceptsAnswerRegionsFromTheSameExam() {
		Question question = new Question(1, exam, "Q1", "Question",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());
		AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", new SourceDocument(10, "answers.pdf"));
		AnswerRegion answerRegion = new AnswerRegion(answerFile, 2, 0.1, 0.2, 0.8, 0.3);
		Answer answer = new Answer(1, null, List.of(answerRegion));
		question.setAnswer(answer);
		assertSame(answer, question.getAnswer());
	}

	@Test
	void acceptsATextOnlyAnswer() {
		Question question = new Question(1, exam, "Q1", "Question",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());

		Answer answer = new Answer(1, "B", List.of());

		question.setAnswer(answer);

		assertTrue(question.hasAnswer());
		assertSame(answer, question.getAnswer());
	}

	@Test
	void rejectInvalidClassification() {
		assertThrows(IllegalArgumentException.class, () -> new Question(36, exam, "Q6", "Invalid classification",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0)), createInvalidClassification()));
	}

	@Test
	void rejectsClassificationFromDifferentSubject() {
		Subject chemistry = new Subject(79, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit = new Unit(4, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(5, syllabus, unit, "1.1", "Topic 1", 1);
		Subtopic classification = new Subtopic(6, syllabus, topic, "1.1.1", "Subtopic 1", 1);

		assertThrows(IllegalArgumentException.class,
				() -> new Question(1, exam, "Q1", "Question",
						List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), classification));
	}

	@Test
	void rejectsInvalidPersistentMetadata() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5);

		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Question(0, exam, "Q1", "Question", List.of(region), createClassification())),
				() -> assertThrows(NullPointerException.class,
						() -> new Question(1, null, "Q1", "Question", List.of(region), createClassification())),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Question(1, exam, " ", "Question", List.of(region), createClassification())),
				() -> assertThrows(NullPointerException.class,
						() -> new Question(1, exam, "Q1", null, List.of(region), createClassification())));
	}

	@Test
	void rejectsAnEmptyRegionList() {
		assertThrows(IllegalArgumentException.class,
				() -> new Question(33, exam, "Q4", "Missing region.", List.of(), createClassification()));
	}

	@Test
	void rejectsAnswerRegionFromDifferentExam() {
		Question question = new Question(1, exam, "Q1", "Question",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());

		Exam otherExam = new Exam(99, exam.getSubject(), exam.getProvider(), exam.getYear(), "Other exam");

		AnswerFile answerFile = new AnswerFile(99, otherExam, "Other marking guide",
				new SourceDocument(99, "other-answers.pdf"));

		Answer answer = new Answer(1, null, List.of(new AnswerRegion(answerFile, 1, 0.0, 0.0, 1.0, 0.5)));

		assertThrows(IllegalArgumentException.class, () -> question.setAnswer(answer));
	}

	@Test
	void rejectsANullClassification() {
		assertThrows(NullPointerException.class, () -> new Question(37, exam, "Q7", "Missing classification.",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0)), null));
	}

	@Test
	void rejectsANullRegionElement() {
		List<QuestionRegion> regions = new ArrayList<>();
		regions.add(null);

		assertThrows(NullPointerException.class,
				() -> new Question(35, exam, "Q6", "Null region.", regions, createClassification()));
	}

	@Test
	void rejectsANullRegionList() {
		assertThrows(NullPointerException.class,
				() -> new Question(34, exam, "Q5", "Missing regions.", null, createClassification()));
	}

	@Test
	void rejectsNullAnswer() {
		Question question = new Question(1, exam, "Q1", "Question",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());

		assertThrows(NullPointerException.class, () -> question.setAnswer(null));
	}

	@Test
	void rejectsRegionFromDifferentExam() {
		Exam otherExam = new Exam(99, exam.getSubject(), exam.getProvider(), exam.getYear(), "Other exam");

		ExamBooklet otherBooklet = new ExamBooklet(99, otherExam, "Other booklet", new SourceDocument(99, "other.pdf"));

		assertThrows(IllegalArgumentException.class, () -> new Question(1, exam, "Q1", "Question",
				List.of(new QuestionRegion(otherBooklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification()));
	}

	@Test
	void rejectsRegionsFromDifferentBooklets() {
		ExamBooklet secondBooklet = new ExamBooklet(99, exam, "Second booklet", new SourceDocument(99, "second.pdf"));

		assertThrows(IllegalArgumentException.class,
				() -> new Question(1, exam, "Q1", "Question",
						List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.4),
								new QuestionRegion(secondBooklet, 2, 0.0, 0.5, 1.0, 0.4)),
						createClassification()));
	}

	@Test
	void retainsAQuestionWithOneRegion() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.1, 0.2, 0.8, 0.3);
		CurriculumNode classification = createClassification();

		Question question = new Question(30, exam, "Q1", "Calculate the result.", List.of(region), classification);

		assertAll(() -> assertEquals(30, question.getId()), () -> assertSame(exam, question.getExam()),
				() -> assertEquals("Q1", question.getQuestionCode()),
				() -> assertEquals("Calculate the result.", question.getQuestionText()),
				() -> assertEquals(List.of(region), question.getRegions()),
				() -> assertSame(classification, question.getClassification()));
	}

	@Test
	void retainsMultipleRegionsInTheirOriginalOrder() {
		QuestionRegion firstPagePart = new QuestionRegion(booklet, 1, 0.0, 0.7, 1.0, 0.2);
		QuestionRegion secondPagePart = new QuestionRegion(booklet, 2, 0.0, 0.1, 1.0, 0.4);

		Question question = new Question(31, exam, "Q2", "A question spanning pages.",
				List.of(firstPagePart, secondPagePart), createClassification());

		assertAll(() -> assertEquals(List.of(firstPagePart, secondPagePart), question.getRegions()));
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
	void startsWithoutAnAnswer() {
		Question question = new Question(1, exam, "Q1", "Question",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 0.5)), createClassification());

		assertFalse(question.hasAnswer());
		assertEquals(null, question.getAnswer());
	}

	@Test
	void takesAnImmutableSnapshotOfRegions() {
		QuestionRegion originalRegion = new QuestionRegion(booklet, 1, 0.0, 0.2, 1.0, 0.4);
		List<QuestionRegion> suppliedRegions = new ArrayList<>(List.of(originalRegion));
		Question question = new Question(32, exam, "Q3", "Protected regions.", suppliedRegions, createClassification());

		suppliedRegions.add(new QuestionRegion(booklet, 2, 0.0, 0.6, 1.0, 0.1));

		assertEquals(List.of(originalRegion), question.getRegions());
		assertThrows(UnsupportedOperationException.class,
				() -> question.getRegions().add(new QuestionRegion(booklet, 3, 0.0, 0.0, 1.0, 1.0)));
	}
}
