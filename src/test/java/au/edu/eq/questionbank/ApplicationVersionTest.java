package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ApplicationVersionTest {

	@Test
	void returnsFilteredApplicationVersion() {
		String version = ApplicationVersion.current();
		assertFalse(version.isBlank());
		assertFalse(version.contains("${"));

		// Sprint 11 establishes Maven version 0.1 as the authoritative release
		// version consumed by application runtime metadata.
		assertEquals("0.1", version);
	}
}
