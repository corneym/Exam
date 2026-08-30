package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExamProviderTest {

	@Test
	void retainsProviderIdentityAndName() {
		ExamProvider provider = new ExamProvider(4, "Independent Schools Queensland");

		assertAll(() -> assertEquals(4, provider.getId()),
				() -> assertEquals("Independent Schools Queensland", provider.getName()));
	}

	@Test
	void rejectsInvalidPersistentValues() {
		assertAll(() -> assertThrows(IllegalArgumentException.class, () -> new ExamProvider(0, "QCAA")),
				() -> assertThrows(IllegalArgumentException.class, () -> new ExamProvider(1, null)),
				() -> assertThrows(IllegalArgumentException.class, () -> new ExamProvider(1, " ")));
	}
}
