package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnswerRegionTest {

	private AnswerFile answerFile;

	@Test
	void acceptsAFullPageRegion() {
		AnswerRegion region = new AnswerRegion(answerFile, 1, 0.0, 0.0, 1.0, 1.0);
		assertAll(() -> assertEquals(0.0, region.x()), () -> assertEquals(0.0, region.y()),
				() -> assertEquals(1.0, region.width()), () -> assertEquals(1.0, region.height()));
	}

	@Test
	void acceptsARegionEndingExactlyAtTheRightAndBottomEdges() {
		AnswerRegion region = new AnswerRegion(answerFile, 1, 0.75, 0.75, 0.25, 0.25);
		assertAll(() -> assertEquals(0.75, region.x()), () -> assertEquals(0.75, region.y()),
				() -> assertEquals(0.25, region.width()), () -> assertEquals(0.25, region.height()));
	}

	@Test
	void exposesItsAnswerFilePageAndNormalisedRectangle() {
		AnswerRegion region = new AnswerRegion(answerFile, 3, 0.125, 0.25, 0.5, 0.625);
		assertAll(() -> assertSame(answerFile, region.answerFile()), () -> assertEquals(3, region.pageNumber()),
				() -> assertEquals(0.125, region.x()), () -> assertEquals(0.25, region.y()),
				() -> assertEquals(0.5, region.width()), () -> assertEquals(0.625, region.height()));
	}

	@Test
	void rejectsNonFiniteCoordinatesAndDimensions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, Double.NaN, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, Double.POSITIVE_INFINITY, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, Double.NaN, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.1, Double.NaN, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.5, Double.NaN)));
	}

	@Test
	void rejectsNonPositiveOrOversizedDimensions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.0, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.1, -0.1, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.0, 0.1, 1.1, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.5, 0.0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.5, -0.1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.0, 0.5, 1.1)));
	}

	@Test
	void rejectsNullAnswerFile() {
		assertThrows(NullPointerException.class, () -> new AnswerRegion(null, 1, 0.1, 0.1, 0.5, 0.5));
	}

	@Test
	void rejectsOriginsOutsideTheNormalizedPage() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, -0.1, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 1.0, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, -0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 1.0, 0.5, 0.5)));
	}

	@Test
	void rejectsPageNumbersLessThanOne() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 0, 0.1, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, -1, 0.1, 0.1, 0.5, 0.5)));
	}

	@Test
	void rejectsRegionsExtendingBeyondThePageEdges() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.6, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new AnswerRegion(answerFile, 1, 0.1, 0.6, 0.5, 0.5)));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(78, "Chemistry");
		ExamProvider provider = new ExamProvider(45, "QCAA");
		Exam exam = new Exam(9, subject, provider, 2025, "External Assessment");
		SourceDocument document = new SourceDocument(4, "answers/marking-guide.pdf");
		answerFile = new AnswerFile(3, exam, "Marking guide", document);
	}

	@Test
	void usesValueEquality() {
		AnswerRegion first = new AnswerRegion(answerFile, 2, 0.1, 0.2, 0.3, 0.4);
		AnswerRegion sameValues = new AnswerRegion(answerFile, 2, 0.1, 0.2, 0.3, 0.4);
		assertEquals(first, sameValues);
		assertEquals(first.hashCode(), sameValues.hashCode());
	}
}
