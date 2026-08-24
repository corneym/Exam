package au.edu.eq.questionbank;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public record ApplicationConfig(Path pdfDataRoot, Path curriculumDataRoot) {

	private static final String PDF_DATA_ROOT_PROPERTY = "pdf.dataRoot";

	private static final String CURRICULUM_DATA_ROOT_PROPERTY = "curriculum.dataRoot";

	public ApplicationConfig {
		Objects.requireNonNull(pdfDataRoot, "pdfDataRoot");
		Objects.requireNonNull(curriculumDataRoot, "curriculumDataRoot");
	}

	public static ApplicationConfig load(Path propertiesFile) throws IOException {

		Objects.requireNonNull(propertiesFile, "propertiesFile");
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(propertiesFile)) {
			properties.load(reader);
		}
		Path pdfDataRoot = readRequiredPath(properties, PDF_DATA_ROOT_PROPERTY, propertiesFile);
		Path curriculumDataRoot = readRequiredPath(properties, CURRICULUM_DATA_ROOT_PROPERTY, propertiesFile);
		return new ApplicationConfig(pdfDataRoot, curriculumDataRoot);
	}

	private static Path readRequiredPath(Properties properties, String propertyName, Path propertiesFile) {
		String value = properties.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new ConfigurationException(
					String.format("Missing required property '%s' in %s", propertyName, propertiesFile.toString()));
		}
		try {
			return Path.of(value.trim()).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new ConfigurationException(
					"Invalid path for property '" + propertyName + "' in " + propertiesFile + ": " + value);
		}
	}
}