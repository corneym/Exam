package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationConfigTest {

	@TempDir
	Path tempDir;

	@Test
	void loadsTheConfiguredDataRoots() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");

		Files.writeString(propertiesFile, "pdf.dataRoot=D:/exam-data\n" + "curriculum.dataRoot=D:/curriculum-data\n");

		ApplicationConfig config = ApplicationConfig.load(propertiesFile);

		assertEquals(Path.of("D:/exam-data").toAbsolutePath().normalize(), config.pdfDataRoot());

		assertEquals(Path.of("D:/curriculum-data").toAbsolutePath().normalize(), config.curriculumDataRoot());
	}

	@Test
	void rejectsConfigurationWithoutAPdfDataRoot() throws IOException, ConfigurationException {

		Path propertiesFile = tempDir.resolve("questionbank.properties");

		Files.writeString(propertiesFile, "curriculum.dataRoot=D:/curriculum-data\n");

		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsConfigurationWithoutACurriculumDataRoot() throws IOException, ConfigurationException {

		Path propertiesFile = tempDir.resolve("questionbank.properties");

		Files.writeString(propertiesFile, "pdf.dataRoot=D:/exam-data\n");

		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void trimsAndNormalizesTheConfiguredRoots() throws IOException, ConfigurationException {

		Path propertiesFile = tempDir.resolve("questionbank.properties");

		String pdfPath = tempDir.resolve("pdfs/../exams").toString().replace('\\', '/');

		String curriculumPath = tempDir.resolve("old/../curriculum").toString().replace('\\', '/');

		Files.writeString(propertiesFile,
				"pdf.dataRoot=  " + pdfPath + "  \n" + "curriculum.dataRoot=  " + curriculumPath + "  \n");

		ApplicationConfig config = ApplicationConfig.load(propertiesFile);

		assertEquals(tempDir.resolve("exams").toAbsolutePath().normalize(), config.pdfDataRoot());

		assertEquals(tempDir.resolve("curriculum").toAbsolutePath().normalize(), config.curriculumDataRoot());
	}

	@Test
	void rejectsABlankPdfDataRoot() throws IOException, ConfigurationException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=   \n");

		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsABlankCurriculumDataRoot() throws IOException, ConfigurationException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "curriculum.dataRoot=   \n");

		assertThrows(ConfigurationException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsANullPropertiesFile() {
		assertThrows(NullPointerException.class, () -> ApplicationConfig.load(null));
	}
}
