package au.edu.eq.questionbank;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public record ApplicationConfig(Path pdfDataRoot) {

	private static final String PDF_DATA_ROOT_PROPERTY = "pdf.dataRoot";

	public ApplicationConfig {
		Objects.requireNonNull(pdfDataRoot, "pdfDataRoot");
	}

	public static ApplicationConfig load(Path propertiesFile) throws IOException {
		Objects.requireNonNull(propertiesFile, "propertiesFile");
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(propertiesFile)) {
			properties.load(reader);
		}

		String configuredRoot = properties.getProperty(PDF_DATA_ROOT_PROPERTY);
		if (configuredRoot == null || configuredRoot.isBlank()) {
			throw new IllegalArgumentException(
					"Missing required property '" + PDF_DATA_ROOT_PROPERTY + "' in " + propertiesFile);
		}

		return new ApplicationConfig(Path.of(configuredRoot.trim()));
	}
}
