package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SharedQuestionContextRegionTest {

	@Test
	void rejectsInvalidPageAndCoordinates() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContextRegion(0, 0, 0, 1, 1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContextRegion(1, Double.NaN, 0, 1, 1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContextRegion(1, 0.8, 0, 0.3, 1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContextRegion(1, 0, 0.8, 1, 0.3)));
	}

	@Test
	void retainsValidNormalizedRegion() {
		SharedQuestionContextRegion region = new SharedQuestionContextRegion(2, 0.1, 0.2, 0.7, 0.5);
		assertAll(() -> assertEquals(2, region.pageNumber()), () -> assertEquals(0.1, region.x()),
				() -> assertEquals(0.2, region.y()), () -> assertEquals(0.7, region.width()),
				() -> assertEquals(0.5, region.height()));
	}
}
