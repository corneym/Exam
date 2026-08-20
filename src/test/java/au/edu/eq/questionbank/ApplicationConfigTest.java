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
	void loadsTheConfiguredPdfDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=D:/exam-data\n");

		ApplicationConfig config = ApplicationConfig.load(propertiesFile);

		assertEquals(Path.of("D:/exam-data"), config.pdfDataRoot());
	}

	@Test
	void rejectsConfigurationWithoutAPdfDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "unrelated.property=value\n");

		assertThrows(IllegalArgumentException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void trimsAndNormalizesTheConfiguredRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		String configuredPath = tempDir.resolve("pdfs/../exams").toString().replace('\\', '/');
		Files.writeString(propertiesFile, "pdf.dataRoot=  " + configuredPath + "  \n");

		ApplicationConfig config = ApplicationConfig.load(propertiesFile);

		assertEquals(tempDir.resolve("exams").toAbsolutePath().normalize(), config.pdfDataRoot());
	}

	@Test
	void rejectsABlankPdfDataRoot() throws IOException {
		Path propertiesFile = tempDir.resolve("questionbank.properties");
		Files.writeString(propertiesFile, "pdf.dataRoot=   \n");

		assertThrows(IllegalArgumentException.class, () -> ApplicationConfig.load(propertiesFile));
	}

	@Test
	void rejectsANullPropertiesFile() {
		assertThrows(NullPointerException.class, () -> ApplicationConfig.load(null));
	}
}
