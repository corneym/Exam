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
 * The SQLite database parent is the authoritative application data root.
 * Subject-first managed assets are stored beneath that root.
 * <p>
 * {@code pdfDataRoot} and {@code curriculumDataRoot} identify the former
 * pre-Sprint-14 managed roots. They remain derivable so layout migration and
 * supported legacy-backup compatibility can inspect old data, but ordinary
 * Subject-first runtime storage does not write new managed assets there.
 *
 * @param pdfDataRoot        legacy PDF root retained for migration and
 *                           compatibility
 * @param curriculumDataRoot legacy curriculum root retained for migration and
 *                           compatibility
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
	 * Returns the authoritative application data root.
	 * <p>
	 * New managed assets, the SQLite database, backups and migration-recovery
	 * material are all rooted here. The separate PDF and curriculum paths identify
	 * only former managed locations needed for migration and compatibility.
	 *
	 * @return directory containing the database and managed application data
	 */
	public Path dataRoot() {
		return databasePath.getParent();
	}

	/**
	 * Loads the user configuration, migrating an existing legacy configuration or
	 * creating a default configuration when this is the first application run.
	 *
	 * @param propertiesFile       user-writable configuration file
	 * @param legacyPropertiesFile former working-directory configuration file
	 * @param defaultDataRoot      data root to use when no configuration exists
	 * @return loaded application configuration
	 * @throws IOException          if configuration migration, creation or loading
	 *                              fails
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public static ApplicationConfig loadOrCreate(Path propertiesFile, Path legacyPropertiesFile, Path defaultDataRoot)
			throws IOException {
		if (propertiesFile == null) {
			throw new NullPointerException("propertiesFile");
		}
		if (legacyPropertiesFile == null) {
			throw new NullPointerException("legacyPropertiesFile");
		}
		if (defaultDataRoot == null) {
			throw new NullPointerException("defaultDataRoot");
		}
		Path normalisedPropertiesFile = propertiesFile.toAbsolutePath().normalize();
		Path normalisedLegacyFile = legacyPropertiesFile.toAbsolutePath().normalize();
		Path normalisedDefaultDataRoot = defaultDataRoot.toAbsolutePath().normalize();
		if (Files.isRegularFile(normalisedPropertiesFile)) {

			// Once the user-specific configuration exists it is authoritative. A legacy
			// working-directory file must never overwrite later Options changes.
			return load(normalisedPropertiesFile);
		}
		Path configurationDirectory = normalisedPropertiesFile.getParent();
		if (configurationDirectory == null) {
			throw new IllegalArgumentException("Configuration file must have a parent directory");
		}
		Files.createDirectories(configurationDirectory);
		if (!normalisedLegacyFile.equals(normalisedPropertiesFile) && Files.isRegularFile(normalisedLegacyFile)) {

			// Preserve the existing file verbatim so legacy explicit path properties are
			// not silently converted or lost during the location migration.
			Files.copy(normalisedLegacyFile, normalisedPropertiesFile);
			return load(normalisedPropertiesFile);
		}
		ApplicationConfig config = fromDataRoot(normalisedDefaultDataRoot);

		// A fresh Subject-first installation needs only the application data root.
		// Creating empty legacy pdf/ and curriculum/ directories would falsely suggest
		// that those locations remain part of the active storage contract.
		Files.createDirectories(config.dataRoot());
		saveDataRoot(normalisedPropertiesFile, normalisedDefaultDataRoot);
		return config;
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
