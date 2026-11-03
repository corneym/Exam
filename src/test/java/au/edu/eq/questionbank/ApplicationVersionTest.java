package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApplicationVersionTest {

	@Test
	void returnsFilteredApplicationVersion() {
		String version = ApplicationVersion.current();
		assertFalse(version.isBlank());
		assertFalse(version.contains("${"));

		// Historical releases used major.minor. New releases use
		// major.minor.patch. Both remain valid application versions.
		assertTrue(version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))?"),
				() -> "Unexpected application version: " + version);
	}
}
