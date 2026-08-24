package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionTest {

	private final Exam exam = new Exam(20, new Subject(1, "Chemistry"), new ExamProvider(3, "Provider"), 2024,
			"External assessment");

	private ExamBooklet booklet;

	private CurriculumNode createClassification() {
		Subject subject = new Subject(1, "Science");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		CurriculumNode unit = new CurriculumNode(1, syllabus, null, "1", "Unit 1", CurriculumLevel.UNIT, 1);
		CurriculumNode topic = new CurriculumNode(2, syllabus, unit, "1.1", "Topic 1", CurriculumLevel.TOPIC, 1);
		return new CurriculumNode(3, syllabus, topic, "1.1.1", "Subtopic 1", CurriculumLevel.SUBTOPIC, 1);
	}

	private CurriculumNode createInvalidClassification() {
		Subject subject = new Subject(1, "Science");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		return new CurriculumNode(1, syllabus, null, "1", "Unit 1", CurriculumLevel.UNIT, 1);
	}

	@Test
	void rejectInvalidClassification() {
		assertThrows(IllegalArgumentException.class, () -> new Question(36, exam, "Q6", "Invalid classification",
				List.of(new QuestionRegion(booklet, 1, 0, 1)), createInvalidClassification()));
	}

	@Test
	void rejectsAnEmptyRegionList() {
		assertThrows(IllegalArgumentException.class,
				() -> new Question(33, exam, "Q4", "Missing region.", List.of(), createClassification()));
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
	void retainsAQuestionWithOneRegion() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.2, 0.3);

		Question question = new Question(30, exam, "Q1", "Calculate the result.", List.of(region),
				createClassification());

		assertAll(() -> assertEquals(30, question.getId()), () -> assertSame(exam, question.getExam()),
				() -> assertEquals("Q1", question.getQuestionCode()),
				() -> assertEquals("Calculate the result.", question.getQuestionText()),
				() -> assertEquals(List.of(region), question.getRegions()));
	}

	@Test
	void retainsMultipleRegionsInTheirOriginalOrder() {
		QuestionRegion firstPagePart = new QuestionRegion(booklet, 1, 0.7, 0.2);
		QuestionRegion secondPagePart = new QuestionRegion(booklet, 2, 0.1, 0.4);

		Question question = new Question(31, exam, "Q2", "A question spanning pages.",
				List.of(firstPagePart, secondPagePart), createClassification());

		assertEquals(List.of(firstPagePart, secondPagePart), question.getRegions());
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(78, "Psych");
		ExamProvider provider = new ExamProvider(45, "QCAA");
		Exam exam = new Exam(9, subject, provider, 2020, "Test exam");
		SourceDocument document = new SourceDocument(4, "path");

		booklet = new ExamBooklet(3, exam, "Test booklet", document);
	}

	@Test
	void takesAnImmutableSnapshotOfRegions() {
		QuestionRegion originalRegion = new QuestionRegion(booklet, 1, 0.2, 0.4);
		List<QuestionRegion> suppliedRegions = new ArrayList<>(List.of(originalRegion));
		Question question = new Question(32, exam, "Q3", "Protected regions.", suppliedRegions, createClassification());

		suppliedRegions.add(new QuestionRegion(booklet, 2, 0.6, 0.1));

		assertEquals(List.of(originalRegion), question.getRegions());
		assertThrows(UnsupportedOperationException.class,
				() -> question.getRegions().add(new QuestionRegion(booklet, 3, 0, 1)));
	}
}
