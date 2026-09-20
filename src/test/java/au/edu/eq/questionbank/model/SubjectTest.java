package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SubjectTest {

	@Test
	void rejectsZeroId() {
		assertThrows(IllegalArgumentException.class, () -> new Subject(0, "Chemistry"));
	}

	@Test
	void rejectsNegativeId() {
		assertThrows(IllegalArgumentException.class, () -> new Subject(-1, "Chemistry"));
	}
}
