package au.edu.eq.questionbank;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Filesystem locations for application data.
 * <p>
 * A configured data root contains the PDF library, curriculum data, and
 * question-bank database.
 *
 * @param pdfDataRoot        root beneath which source examination PDFs are
 *                           stored
 * @param curriculumDataRoot root beneath which curriculum workbooks are stored
 * @param databasePath       path to the SQLite question-bank database
 */
public record ApplicationConfig(Path pdfDataRoot, Path curriculumDataRoot, Path databasePath) {

	private static final String DATA_ROOT_PROPERTY = "data.root";
	private static final String PDF_DATA_ROOT_PROPERTY = "pdf.dataRoot";
	private static final String CURRICULUM_DATA_ROOT_PROPERTY = "curriculum.dataRoot";
	private static final String DATABASE_PATH_PROPERTY = "database.path";
	private static final String PDF_DIRECTORY = "pdf";
	private static final String CURRICULUM_DIRECTORY = "curriculum";
	private static final String DATABASE_FILENAME = "questionbank.db";

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
		pdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
		databasePath = databasePath.toAbsolutePath().normalize();
	}

	/**
	 * Returns the application data root.
	 *
	 * @return the directory containing the database and application data folders
	 */
	public Path dataRoot() {
		return databasePath.getParent();
	}

	/**
	 * Loads configuration from a Java properties file.
	 * <p>
	 * When {@code data.root} is present, the PDF root, curriculum root, and
	 * database path are derived from it. The older individual path properties
	 * remain supported temporarily for existing configuration files.
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
		if (properties.containsKey(DATA_ROOT_PROPERTY)) {
			Path dataRoot = readRequiredPath(properties, DATA_ROOT_PROPERTY, propertiesFile);
			return fromDataRoot(dataRoot);
		}
		Path pdfDataRoot = readRequiredPath(properties, PDF_DATA_ROOT_PROPERTY, propertiesFile);
		Path curriculumDataRoot = readRequiredPath(properties, CURRICULUM_DATA_ROOT_PROPERTY, propertiesFile);
		Path databasePath = readRequiredPath(properties, DATABASE_PATH_PROPERTY, propertiesFile);
		return new ApplicationConfig(pdfDataRoot, curriculumDataRoot, databasePath);
	}

	/**
	 * Creates configuration derived from a single application data root.
	 *
	 * @param dataRoot the application data root
	 * @return derived application configuration
	 */
	public static ApplicationConfig fromDataRoot(Path dataRoot) {
		if (dataRoot == null) {
			throw new NullPointerException("dataRoot");
		}
		Path normalisedRoot = dataRoot.toAbsolutePath().normalize();
		return new ApplicationConfig(normalisedRoot.resolve(PDF_DIRECTORY),
				normalisedRoot.resolve(CURRICULUM_DIRECTORY), normalisedRoot.resolve(DATABASE_FILENAME));
	}

	/**
	 * Saves the configured data root. Legacy individual path properties are removed
	 * when the configuration is saved.
	 *
	 * @param propertiesFile the properties file to update
	 * @param dataRoot       the application data root
	 * @throws IOException          if the properties file cannot be read or written
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public static void saveDataRoot(Path propertiesFile, Path dataRoot) throws IOException {
		if (propertiesFile == null) {
			throw new NullPointerException("propertiesFile");
		}
		if (dataRoot == null) {
			throw new NullPointerException("dataRoot");
		}
		Properties properties = new Properties();
		if (Files.exists(propertiesFile)) {
			try (Reader reader = Files.newBufferedReader(propertiesFile)) {
				properties.load(reader);
			}
		}
		String rootValue = dataRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
		properties.setProperty(DATA_ROOT_PROPERTY, rootValue);
		properties.remove(PDF_DATA_ROOT_PROPERTY);
		properties.remove(CURRICULUM_DATA_ROOT_PROPERTY);
		properties.remove(DATABASE_PATH_PROPERTY);
		try (Writer writer = Files.newBufferedWriter(propertiesFile)) {
			properties.store(writer, "Exam Question Bank configuration");
		}
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
