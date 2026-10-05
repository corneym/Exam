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

		// Release versions use the same major.minor format enforced by the
		// release-build process. Do not hard-code a particular release number.
		assertTrue(version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"));
	}
}
