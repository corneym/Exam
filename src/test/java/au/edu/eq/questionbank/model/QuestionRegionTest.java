package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
