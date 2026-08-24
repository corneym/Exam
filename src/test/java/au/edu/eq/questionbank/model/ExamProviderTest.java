package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExamProviderTest {

	@Test
	void retainsProviderIdentityAndName() {
		ExamProvider provider = new ExamProvider(4, "Independent Schools Queensland");

		assertAll(() -> assertEquals(4, provider.getId()),
				() -> assertEquals("Independent Schools Queensland", provider.getName()));
	}
}
