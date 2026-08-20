package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SyllabusVersionTest {

	@Test
	void rejectsZeroId() {
		Subject chemistry = new Subject(1, "Chemistry");

		assertThrows(IllegalArgumentException.class, () -> new SyllabusVersion(0, chemistry, "2025", true));
	}

	@Test
	void rejectsNegativeId() {
		Subject chemistry = new Subject(1, "Chemistry");

		assertThrows(IllegalArgumentException.class, () -> new SyllabusVersion(-1, chemistry, "2025", true));
	}
}