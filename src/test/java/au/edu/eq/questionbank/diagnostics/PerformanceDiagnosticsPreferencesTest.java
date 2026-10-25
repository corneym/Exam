package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;

class PerformanceDiagnosticsPreferencesTest {

	@TempDir
	Path tempDir;

	@Test
	void collectionIsDisabledByDefault() throws Exception {
		Path properties = tempDir.resolve("missing.properties");
		assertFalse(PerformanceDiagnosticsPreferences.load(properties));
		assertFalse(Files.exists(properties));
	}

	@Test
	void explicitJvmOverrideTakesPrecedence() throws Exception {
		Path properties = tempDir.resolve("questionbank.properties");
		PerformanceDiagnosticsPreferences.save(properties, true);
		String previous = System.getProperty("eqb.performance.enabled");
		try {
			System.setProperty("eqb.performance.enabled", "false");
			try (PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(tempDir.resolve("diagnostics"),
					PerformanceDiagnosticsPreferences.load(properties))) {
				assertFalse(recorder.isEnabled());
			}
			System.setProperty("eqb.performance.enabled", "true");
			try (PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(tempDir.resolve("diagnostics"),
					false)) {
				assertTrue(recorder.isEnabled());
			}
		} finally {
			if (previous == null) {
				System.clearProperty("eqb.performance.enabled");
			} else {
				System.setProperty("eqb.performance.enabled", previous);
			}
		}
	}

	@Test
	void invalidSavedSettingIsRejected() throws Exception {
		Path properties = tempDir.resolve("questionbank.properties");
		Files.writeString(properties, "diagnostics.performance.enabled=perhaps\n");
		assertThrows(IOException.class, () -> PerformanceDiagnosticsPreferences.load(properties));
	}

	@Test
	void preferenceSurvivesRestartAndPreservesDataRoot() throws Exception {
		Path properties = tempDir.resolve("questionbank.properties");
		Path dataRoot = tempDir.resolve("questions");
		ApplicationConfig.saveDataRoot(properties, dataRoot);
		PerformanceDiagnosticsPreferences.save(properties, true);
		assertTrue(PerformanceDiagnosticsPreferences.load(properties));
		assertEquals(dataRoot.toAbsolutePath().normalize(), ApplicationConfig.load(properties).dataRoot());
		PerformanceDiagnosticsPreferences.save(properties, false);
		assertFalse(PerformanceDiagnosticsPreferences.load(properties));
		assertEquals(dataRoot.toAbsolutePath().normalize(), ApplicationConfig.load(properties).dataRoot());
	}
}
