package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionTest {

	private final SourceDocument sourceDocument = new SourceDocument(10, "chemistry/exam.pdf");
	private final Exam exam = new Exam(20, "Chemistry", 2024, "External assessment", sourceDocument);

	@Test
	void retainsAQuestionWithOneRegion() {
		QuestionRegion region = new QuestionRegion(1, 0.1, 0.2, 0.7, 0.3);

		Question question = new Question(30, exam, "Q1", "Calculate the result.", List.of(region));

		assertAll(
				() -> assertEquals(30, question.getId()),
				() -> assertSame(exam, question.getExam()),
				() -> assertEquals("Q1", question.getQuestionCode()),
				() -> assertEquals("Calculate the result.", question.getQuestionText()),
				() -> assertEquals(List.of(region), question.getRegions()));
	}

	@Test
	void retainsMultipleRegionsInTheirOriginalOrder() {
		QuestionRegion firstPagePart = new QuestionRegion(1, 0.1, 0.7, 0.8, 0.2);
		QuestionRegion secondPagePart = new QuestionRegion(2, 0.1, 0.1, 0.8, 0.4);

		Question question = new Question(31, exam, "Q2", "A question spanning pages.",
				List.of(firstPagePart, secondPagePart));

		assertEquals(List.of(firstPagePart, secondPagePart), question.getRegions());
	}

	@Test
	void takesAnImmutableSnapshotOfRegions() {
		QuestionRegion originalRegion = new QuestionRegion(1, 0.1, 0.2, 0.3, 0.4);
		List<QuestionRegion> suppliedRegions = new ArrayList<>(List.of(originalRegion));
		Question question = new Question(32, exam, "Q3", "Protected regions.", suppliedRegions);

		suppliedRegions.add(new QuestionRegion(2, 0.5, 0.6, 0.2, 0.1));

		assertEquals(List.of(originalRegion), question.getRegions());
		assertThrows(UnsupportedOperationException.class,
				() -> question.getRegions().add(new QuestionRegion(3, 0, 0, 1, 1)));
	}

	@Test
	void rejectsAnEmptyRegionList() {
		assertThrows(IllegalArgumentException.class,
				() -> new Question(33, exam, "Q4", "Missing region.", List.of()));
	}

	@Test
	void rejectsANullRegionList() {
		assertThrows(NullPointerException.class,
				() -> new Question(34, exam, "Q5", "Missing regions.", null));
	}

	@Test
	void rejectsANullRegionElement() {
		List<QuestionRegion> regions = new ArrayList<>();
		regions.add(null);

		assertThrows(NullPointerException.class,
				() -> new Question(35, exam, "Q6", "Null region.", regions));
	}
}
