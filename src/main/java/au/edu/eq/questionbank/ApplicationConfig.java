package au.edu.eq.questionbank;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Filesystem locations for external PDF and curriculum application data and
 * the persistent question-bank database.
 *
 * @param pdfDataRoot        root beneath which source examination PDFs are
 *                           stored
 * @param curriculumDataRoot root beneath which curriculum workbooks are stored
 * @param databasePath       path to the SQLite question-bank database
 */
public record ApplicationConfig(Path pdfDataRoot, Path curriculumDataRoot, Path databasePath) {

	private static final String PDF_DATA_ROOT_PROPERTY = "pdf.dataRoot";
	private static final String CURRICULUM_DATA_ROOT_PROPERTY = "curriculum.dataRoot";
	private static final String DATABASE_PATH_PROPERTY = "database.path";

	/**
	 * Validates directly supplied configuration paths.
	 *
	 * @throws NullPointerException if any path is {@code null}
	 */
	public ApplicationConfig {
		if (pdfDataRoot == null) {
			throw new NullPointerException("pdfDataRoot");
		}
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		if (databasePath == null) {
			throw new NullPointerException("databasePath");
		}
	}

	/**
	 * Loads {@code pdf.dataRoot}, {@code curriculum.dataRoot}, and
	 * {@code database.path} from a Java properties file. Loaded values are
	 * converted to normalized absolute paths.
	 *
	 * @param propertiesFile the configuration file to read
	 * @return the loaded application configuration
	 * @throws IOException            if the file cannot be read
	 * @throws ConfigurationException if a required property is missing, blank, or
	 *                                not a valid path
	 * @throws NullPointerException   if {@code propertiesFile} is {@code null}
	 */
	public static ApplicationConfig load(Path propertiesFile) throws IOException {

		if (propertiesFile == null) {
			throw new NullPointerException("propertiesFile");
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(propertiesFile)) {
			properties.load(reader);
		}
		Path pdfDataRoot = readRequiredPath(properties, PDF_DATA_ROOT_PROPERTY, propertiesFile);
		Path curriculumDataRoot = readRequiredPath(properties, CURRICULUM_DATA_ROOT_PROPERTY, propertiesFile);
		Path databasePath = readRequiredPath(properties, DATABASE_PATH_PROPERTY, propertiesFile);
		return new ApplicationConfig(pdfDataRoot, curriculumDataRoot, databasePath);
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
