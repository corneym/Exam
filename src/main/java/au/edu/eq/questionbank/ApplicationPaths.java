package au.edu.eq.questionbank;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves writable filesystem locations owned by Exam Question Bank.
 * <p>
 * Installed application files remain read-only. User configuration and default
 * application data are stored separately in a user-writable location.
 */
public final class ApplicationPaths {

	private static final String WINDOWS_LOCAL_APP_DATA = "LOCALAPPDATA";
	private static final String USER_DATA_DIRECTORY = "Exam Question Bank Data";
	private static final String FALLBACK_DIRECTORY = ".exam-question-bank";
	private static final String PROPERTIES_FILENAME = "questionbank.properties";
	private static final String DATA_DIRECTORY = "data";

	private ApplicationPaths() {
	}

	/**
	 * Returns the user-writable directory containing application configuration.
	 *
	 * @return normalized absolute configuration directory
	 */
	public static Path configurationDirectory() {
		return resolveConfigurationDirectory(System.getenv(WINDOWS_LOCAL_APP_DATA), System.getProperty("user.home"));
	}

	/**
	 * Returns the default location for new application data.
	 *
	 * @return normalized absolute default data-root path
	 */
	public static Path defaultDataRoot() {

		// Keep the default data root beneath the user-owned configuration directory,
		// but separate from the properties file itself.
		return configurationDirectory().resolve(DATA_DIRECTORY).toAbsolutePath().normalize();
	}

	/**
	 * Returns the dedicated directory for optional performance diagnostics. This is
	 * separate from the authoritative application data root.
	 *
	 * @return normalised absolute diagnostics directory
	 */
	public static Path diagnosticsDirectory() {
		return configurationDirectory().resolve("diagnostics").toAbsolutePath().normalize();
	}

	/**
	 * Returns the application configuration file. An explicit JVM property
	 * overrides the normal production location.
	 *
	 * @return normalised absolute configuration-file path
	 */
	public static Path propertiesFile() {
		String override = System.getProperty("eqb.config.file");
		if (override != null) {
			if (override.isBlank()) {
				throw new IllegalArgumentException("eqb.config.file must not be blank");
			}
			Path selected = Path.of(override).toAbsolutePath().normalize();

			// Never silently create a fresh configuration when an
			// explicitly selected development configuration is missing.
			if (!Files.isRegularFile(selected)) {
				throw new IllegalArgumentException("Configuration file does not exist: " + selected);
			}
			return selected;
		}
		return configurationDirectory().resolve(PROPERTIES_FILENAME).toAbsolutePath().normalize();
	}

	static Path resolveConfigurationDirectory(String localAppData, String userHome) {
		if (localAppData != null && !localAppData.isBlank()) {

			// Keep writable configuration and application data in a sibling directory
			// to the jpackage installation so uninstalling the application cannot remove
			// user-owned state.
			return Path.of(localAppData).resolve(USER_DATA_DIRECTORY).toAbsolutePath().normalize();
		}
		if (userHome == null || userHome.isBlank()) {
			throw new IllegalStateException("No user-writable application directory is available");
		}

		// Retain a deterministic user-home fallback for development and non-Windows
		// environments.
		return Path.of(userHome).resolve(FALLBACK_DIRECTORY).toAbsolutePath().normalize();
	}
}
