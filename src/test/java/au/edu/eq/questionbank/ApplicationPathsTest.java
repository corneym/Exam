package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ApplicationPathsTest {

	@Test
	void configurationFallsBackToUserHome() {
		Path userHome = Path.of("build", "user-home");
		Path result = ApplicationPaths.resolveConfigurationDirectory(null, userHome.toString());

		// Non-Windows and development environments retain a predictable writable
		// application directory beneath the user's home directory.
		assertEquals(userHome.resolve(".exam-question-bank").toAbsolutePath().normalize(), result);
	}

	@Test
	void defaultPathsShareTheApplicationConfigurationDirectory() {
		Path configurationDirectory = ApplicationPaths.configurationDirectory();

		// Configuration and the default data root belong to the same user-owned
		// application area, while remaining separate filesystem entries.
		assertEquals(configurationDirectory.resolve("questionbank.properties"), ApplicationPaths.propertiesFile());
		assertEquals(configurationDirectory.resolve("data"), ApplicationPaths.defaultDataRoot());
	}

	@Test
	void diagnosticsDirectoryRemainsOutsideDataRoot() {
		Path diagnostics = ApplicationPaths.diagnosticsDirectory();
		Path dataRoot = ApplicationPaths.defaultDataRoot();
		assertEquals(ApplicationPaths.configurationDirectory().resolve("diagnostics"), diagnostics);
		assertFalse(diagnostics.startsWith(dataRoot));
	}

	@Test
	void explicitConfigurationOverrideSelectsDevelopmentFile() throws Exception {
		Path file = Files.createTempFile("questionbank-dev-", ".properties");
		String previous = System.getProperty("eqb.config.file");
		try {
			System.setProperty("eqb.config.file", file.toString());
			assertEquals(file.toAbsolutePath().normalize(), ApplicationPaths.propertiesFile());
		} finally {
			if (previous == null) {
				System.clearProperty("eqb.config.file");
			} else {
				System.setProperty("eqb.config.file", previous);
			}
			Files.deleteIfExists(file);
		}
	}

	@Test
	void missingExplicitConfigurationIsRejected() throws Exception {
		Path directory = Files.createTempDirectory("eqb-config-");
		Path missing = directory.resolve("missing.properties");
		String previous = System.getProperty("eqb.config.file");
		try {
			System.setProperty("eqb.config.file", missing.toString());
			assertThrows(IllegalArgumentException.class, ApplicationPaths::propertiesFile);
		} finally {
			if (previous == null) {
				System.clearProperty("eqb.config.file");
			} else {
				System.setProperty("eqb.config.file", previous);
			}
			Files.deleteIfExists(directory);
		}
	}

	@Test
	void windowsConfigurationUsesSeparateLocalAppDataDirectory() {
		Path localAppData = Path.of("build", "local-app-data");
		Path result = ApplicationPaths.resolveConfigurationDirectory(localAppData.toString(),
				Path.of("unused-home").toString());

		// The writable data directory must not reuse the packaged application's
		// jpackage product directory, because MSI uninstall owns that location.
		assertEquals(localAppData.resolve("Exam Question Bank Data").toAbsolutePath().normalize(), result);
	}
}
