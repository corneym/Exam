package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

/**
 * Persists the user's opt-in performance collection preference.
 *
 * The JVM property eqb.performance.enabled remains an explicit override.
 */
public final class PerformanceDiagnosticsPreferences {

	private static final String ENABLED_KEY = "diagnostics.performance.enabled";

	private PerformanceDiagnosticsPreferences() {
	}

	/**
	 * Reads the saved collection preference.
	 *
	 * @param propertiesFile application configuration file
	 * @return true when the user has enabled collection
	 * @throws IOException if the configuration cannot be read
	 */
	public static boolean load(Path propertiesFile) throws IOException {
		Properties properties = read(propertiesFile);
		String value = properties.getProperty(ENABLED_KEY, "false").trim();
		if ("true".equalsIgnoreCase(value)) {
			return true;
		}
		if ("false".equalsIgnoreCase(value)) {
			return false;
		}
		throw new IOException("Invalid performance diagnostics preference");
	}

	/**
	 * Saves the preference without removing unrelated application options.
	 *
	 * @param propertiesFile application configuration file
	 * @param enabled        whether routine collection is enabled
	 * @throws IOException if the preference cannot be saved
	 */
	public static void save(Path propertiesFile, boolean enabled) throws IOException {
		Objects.requireNonNull(propertiesFile, "propertiesFile");
		Path destination = propertiesFile.toAbsolutePath().normalize();
		Path parent = destination.getParent();
		Properties properties = read(destination);
		Files.createDirectories(parent);
		properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
		Path temporary = Files.createTempFile(parent, ".eqb-options-", ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				properties.store(writer, "Exam Question Bank configuration");
			}
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static Properties read(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		Properties properties = new Properties();
		if (Files.notExists(file)) {
			return properties;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}
}
