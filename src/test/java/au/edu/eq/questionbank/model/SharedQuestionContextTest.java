package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedQuestionContextTest {

	private ExamBooklet booklet;

	@Test
	void rejectsInvalidMetadataAndRegions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContext(0, booklet, "Preamble",
								List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 1)))),
				() -> assertThrows(NullPointerException.class,
						() -> new SharedQuestionContext(1, null, "Preamble",
								List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 1)))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContext(1, booklet, " ",
								List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 1)))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContext(1, booklet, "Preamble", List.of())));
	}

	@Test
	void retainsOrderedRegions() {
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(3, 0.1, 0.1, 0.8, 0.2);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(4, 0.1, 0.2, 0.8, 0.3);
		SharedQuestionContext context = new SharedQuestionContext(7, booklet, "Question 21 preamble",
				List.of(first, second));
		assertAll(() -> assertEquals(7, context.getId()), () -> assertSame(booklet, context.getBooklet()),
				() -> assertEquals("Question 21 preamble", context.getLabel()),
				() -> assertEquals(List.of(first, second), context.getRegions()));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2025, "External assessment");
		booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "paper-1.pdf"));
	}

	@Test
	void takesImmutableSnapshotOfRegions() {
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(3, 0, 0, 1, 0.2);
		List<SharedQuestionContextRegion> supplied = new ArrayList<>();
		supplied.add(first);
		SharedQuestionContext context = new SharedQuestionContext(7, booklet, "Preamble", supplied);
		supplied.add(new SharedQuestionContextRegion(4, 0, 0, 1, 0.2));
		assertEquals(List.of(first), context.getRegions());
	}
}
