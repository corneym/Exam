package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QuestionRegionTest {

	@Test
	void exposesItsPageAndNormalisedCoordinates() {
		QuestionRegion region = new QuestionRegion(3, 0.125, 0.25, 0.5, 0.625);

		assertAll(
				() -> assertEquals(3, region.pageNumber()),
				() -> assertEquals(0.125, region.x()),
				() -> assertEquals(0.25, region.y()),
				() -> assertEquals(0.5, region.width()),
				() -> assertEquals(0.625, region.height()));
	}

	@Test
	void usesValueEquality() {
		QuestionRegion first = new QuestionRegion(2, 0.1, 0.2, 0.3, 0.4);
		QuestionRegion sameValues = new QuestionRegion(2, 0.1, 0.2, 0.3, 0.4);

		assertEquals(first, sameValues);
		assertEquals(first.hashCode(), sameValues.hashCode());
	}

	@Test
	void acceptsAFullPageRegion() {
		QuestionRegion region = new QuestionRegion(1, 0.0, 0.0, 1.0, 1.0);

		assertAll(
				() -> assertEquals(0.0, region.x()),
				() -> assertEquals(0.0, region.y()),
				() -> assertEquals(1.0, region.width()),
				() -> assertEquals(1.0, region.height()));
	}

	@Test
	void rejectsPageNumbersLessThanOne() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(0, 0.1, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(-1, 0.1, 0.1, 0.5, 0.5)));
	}

	@Test
	void rejectsNonFiniteCoordinatesAndDimensions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, Double.NaN, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, Double.POSITIVE_INFINITY, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, 0.1, Double.NEGATIVE_INFINITY, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, 0.1, 0.5, Double.NaN)));
	}

	@Test
	void rejectsOriginsOutsideTheNormalizedPage() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, -0.1, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, -0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 1.0, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, 1.0, 0.5, 0.5)));
	}

	@Test
	void rejectsNonPositiveOrOversizedDimensions() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, 0.1, 0.0, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, 0.1, 0.5, -0.1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.0, 0.0, 1.1, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.0, 0.0, 0.5, 1.1)));
	}

	@Test
	void rejectsRegionsExtendingBeyondThePageEdges() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.6, 0.1, 0.5, 0.5)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new QuestionRegion(1, 0.1, 0.6, 0.5, 0.5)));
	}
}
