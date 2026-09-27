package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationConfigTest {

	@TempDir
	Path tempDir;

	@Test
	void loadOrCreateCreatesFirstRunConfigurationAndDataDirectories() throws IOException {
		Path userFile = tempDir.resolve("user-config/questionbank.properties");
		Path missingLegacyFile = tempDir.resolve("legacy/questionbank.properties");
		Path defaultDataRoot = tempDir.resolve("application-data");
		ApplicationConfig config = ApplicationConfig.loadOrCreate(userFile, missingLegacyFile, defaultDataRoot);

		// A fresh packaged installation must be able to start without a manually
		// prepared properties file or writable installation directory.
		assertEquals(defaultDataRoot.toAbsolutePath().normalize(), config.dataRoot());
		assertTrue(Files.isRegularFile(userFile));
		assertTrue(Files.isDirectory(config.pdfDataRoot()));
		assertTrue(Files.isDirectory(config.curriculumDataRoot()));
		ApplicationConfig reloaded = ApplicationConfig.load(userFile);
		assertEquals(config, reloaded);
	}

	@Test
	void loadOrCreateMigratesLegacyConfigurationWithoutChangingItsPaths() throws IOException {
		Path legacyFile = tempDir.resolve("legacy/questionbank.properties");
		Path userFile = tempDir.resolve("user-config/questionbank.properties");
		Path pdfRoot = tempDir.resolve("existing-pdf");
		Path curriculumRoot = tempDir.resolve("existing-curriculum");
		Path databasePath = tempDir.resolve("existing-db/questionbank.db");
		Files.createDirectories(legacyFile.getParent());
		Files.writeString(legacyFile,
				"pdf.dataRoot=" + pdfRoot.toString().replace('\\', '/') + "\n" + "curriculum.dataRoot="
						+ curriculumRoot.toString().replace('\\', '/') + "\n" + "database.path="
						+ databasePath.toString().replace('\\', '/') + "\n");
		ApplicationConfig config = ApplicationConfig.loadOrCreate(userFile, legacyFile,
				tempDir.resolve("unused-default"));

		// Migration copies the existing configuration rather than deriving new paths
		// from the database parent.
		assertEquals(pdfRoot.toAbsolutePath().normalize(), config.pdfDataRoot());
		assertEquals(curriculumRoot.toAbsolutePath().normalize(), config.curriculumDataRoot());
		assertEquals(databasePath.toAbsolutePath().normalize(), config.databasePath());
		assertEquals(Files.readString(legacyFile), Files.readString(userFile));
	}

	@Test
	void loadsTheConfiguredDataRoots() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=D:/exam-data\n" + "curriculum.dataRoot=D:/curriculum-data\n"
				+ "database.path=D:/questionbank.db\n");
		ApplicationConfig config = ApplicationConfig.load(propertiesFile);
		assertEquals(Path.of("D:/exam-data").toAbsolutePath().normalize(), config.pdfDataRoot());
		assertEquals(Path.of("D:/curriculum-data").toAbsolutePath().normalize(), config.curriculumDataRoot());
		assertEquals(Path.of("D:/questionbank.db").toAbsolutePath().normalize(), config.databasePath());
	}

	@Test
	void rejectsABlankCurriculumDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile,
				"pdf.dataRoot=D:/exam-data\n" + "curriculum.dataRoot=   \n" + "database.path=D:/questionbank.db\n");
		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsABlankDatabasePath() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile,
				"pdf.dataRoot=D:/exam-data\n" + "curriculum.dataRoot=D:/curriculum-data\n" + "database.path=   \n");
		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsABlankPdfDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=   \n" + "curriculum.dataRoot=D:/curriculum-data\n"
				+ "database.path=D:/questionbank.db\n");
		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsANullPropertiesFile() {
		assertThrows(NullPointerException.class, () -> ApplicationConfig.load(null));
	}

	@Test
	void rejectsConfigurationWithoutACurriculumDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=D:/exam-data\n" + "database.path=D:/questionbank.db\n");
		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsConfigurationWithoutADatabasePath() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=D:/exam-data\n" + "curriculum.dataRoot=D:/curriculum-data\n");
		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsConfigurationWithoutAPdfDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile,
				"curriculum.dataRoot=D:/curriculum-data\n" + "database.path=D:/questionbank.db\n");
		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void trimsAndNormalizesTheConfiguredPaths() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		String pdfPath = tempDir.resolve("pdfs/../exams").toString().replace('\\', '/');
		String curriculumPath = tempDir.resolve("old/../curriculum").toString().replace('\\', '/');
		String databasePath = tempDir.resolve("database/../questionbank.db").toString().replace('\\', '/');
		Files.writeString(propertiesFile, "pdf.dataRoot=  " + pdfPath + "  \n" + "curriculum.dataRoot=  "
				+ curriculumPath + "  \n" + "database.path=  " + databasePath + "  \n");
		ApplicationConfig config = ApplicationConfig.load(propertiesFile);
		assertEquals(tempDir.resolve("exams").toAbsolutePath().normalize(), config.pdfDataRoot());
		assertEquals(tempDir.resolve("curriculum").toAbsolutePath().normalize(), config.curriculumDataRoot());
		assertEquals(tempDir.resolve("questionbank.db").toAbsolutePath().normalize(), config.databasePath());
	}
}
