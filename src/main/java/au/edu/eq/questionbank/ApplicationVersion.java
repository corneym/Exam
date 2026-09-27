package au.edu.eq.questionbank;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Provides the application version embedded from the authoritative Maven
 * project version.
 */
public final class ApplicationVersion {

	private static final String VERSION_RESOURCE = "/au/edu/eq/questionbank/application.properties";
	private static final String VERSION_PROPERTY = "application.version";

	private ApplicationVersion() {
	}

	/**
	 * Returns the version embedded into the application during the Maven build.
	 *
	 * @return non-blank application version
	 * @throws IllegalStateException if the packaged version resource is missing,
	 *                               unreadable or invalid
	 */
	public static String current() {
		Properties properties = new Properties();
		try (InputStream input = ApplicationVersion.class.getResourceAsStream(VERSION_RESOURCE)) {
			if (input == null) {

				// Version metadata is required application infrastructure rather than
				// optional display information.
				throw new IllegalStateException("Application version resource not found: " + VERSION_RESOURCE);
			}
			properties.load(input);
		} catch (IOException exception) {
			throw new IllegalStateException("Application version resource could not be read", exception);
		}
		String version = properties.getProperty(VERSION_PROPERTY);
		if (version == null || version.isBlank() || version.contains("${")) {

			// An unresolved Maven token indicates an incorrectly configured build.
			throw new IllegalStateException("Application version is missing or unresolved");
		}
		return version.trim();
	}
}
