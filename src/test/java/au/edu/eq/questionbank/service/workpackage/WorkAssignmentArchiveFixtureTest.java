package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkAssignmentArchiveFixtureTest {

	@Test
	void acceptsCompleteKeyValuePairs() {
		var fields = WorkAssignmentArchiveFixture.fields("first", "one", "second", "two");
		assertEquals("one", fields.path("first").asText());
		assertEquals("two", fields.path("second").asText());
		assertTrue(WorkAssignmentArchiveFixture.fields().isEmpty());
	}

	@Test
	void rejectsOddNumberOfArguments() {
		assertThrows(IllegalArgumentException.class, () -> WorkAssignmentArchiveFixture.fields("unpaired"));
		assertThrows(IllegalArgumentException.class,
				() -> WorkAssignmentArchiveFixture.fields("first", "one", "second"));
	}
}
