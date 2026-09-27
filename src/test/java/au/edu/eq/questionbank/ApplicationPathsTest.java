package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
	void windowsConfigurationUsesSeparateLocalAppDataDirectory() {
		Path localAppData = Path.of("build", "local-app-data");
		Path result = ApplicationPaths.resolveConfigurationDirectory(localAppData.toString(),
				Path.of("unused-home").toString());

		// The writable data directory must not reuse the packaged application's
		// jpackage product directory, because MSI uninstall owns that location.
		assertEquals(localAppData.resolve("Exam Question Bank Data").toAbsolutePath().normalize(), result);
	}
}
