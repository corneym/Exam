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
}
