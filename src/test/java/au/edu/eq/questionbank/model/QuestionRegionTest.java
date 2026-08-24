package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionRegionTest {

	private ExamBooklet booklet;

	@Test
	void acceptsAFullPageRegion() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);

		assertAll(() -> assertEquals(0.0, region.x()), () -> assertEquals(0.0, region.y()),
				() -> assertEquals(1.0, region.width()), () -> assertEquals(1.0, region.height()));
	}

	@Test
	void acceptsARegionEndingExactlyAtTheRightAndBottomEdges() {
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.75, 0.75, 0.25, 0.25);

		assertAll(() -> assertEquals(0.75, region.x()), () -> assertEquals(0.75, region.y()),
				() -> assertEquals(0.25, region.width()), () -> assertEquals(0.25, region.height()));
	}

	@Test
	void exposesItsBookletPageAndNormalisedRectangle() {
		QuestionRegion region = new QuestionRegion(booklet, 3, 0.125, 0.25, 0.5, 0.625);

		assertAll(() -> assertSame(booklet, region.booklet()), () -> assertEquals(3, region.pageNumber()),
				() -> assertEquals(0.125, region.x()), () -> assertEquals(0.25, region.y()),
				() -> assertEquals(0.5, region.width()), () -> assertEquals(0.625, region.height()));
	}

	@Test
	void rejectsNonFiniteCoordinatesAndDimensions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, Double.NaN, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, Double.POSITIVE_INFINITY, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, Double.NaN, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, Double.POSITIVE_INFINITY, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, Double.NaN, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, Double.NEGATIVE_INFINITY, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, Double.NaN)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, Double.NEGATIVE_INFINITY)));
	}

	@Test
	void rejectsNonPositiveOrOversizedDimensions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, 0.0, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, -0.1, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.0, 0.1, 1.1, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, -0.1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.0, 0.5, 1.1)));
	}

	@Test
	void rejectsNullBooklet() {
		assertThrows(NullPointerException.class, () -> new QuestionRegion(null, 1, 0.1, 0.1, 0.5, 0.5));
	}

	@Test
	void rejectsOriginsOutsideTheNormalizedPage() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, -0.1, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 1.0, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, -0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 1.0, 0.5, 0.5)));
	}

	@Test
	void rejectsPageNumbersLessThanOne() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 0, 0.1, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, -1, 0.1, 0.1, 0.5, 0.5)));
	}

	@Test
	void rejectsRegionsExtendingBeyondThePageEdges() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.6, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(booklet, 1, 0.1, 0.6, 0.5, 0.5)));
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
	void usesValueEquality() {
		QuestionRegion first = new QuestionRegion(booklet, 2, 0.1, 0.2, 0.3, 0.4);
		QuestionRegion sameValues = new QuestionRegion(booklet, 2, 0.1, 0.2, 0.3, 0.4);

		assertEquals(first, sameValues);
		assertEquals(first.hashCode(), sameValues.hashCode());
	}
}
