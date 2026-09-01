package au.edu.eq.questionbank.repository.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads SQL scripts packaged as application resources.
 */
final class SqlResourceLoader {

	/**
	 * Loads a UTF-8 SQL resource from the application class path.
	 *
	 * @param resourcePath an absolute class-path resource name
	 * @return the complete SQL resource text
	 * @throws IOException              if the resource does not exist or cannot be
	 *                                  read
	 * @throws NullPointerException     if {@code resourcePath} is {@code null}
	 * @throws IllegalArgumentException if {@code resourcePath} is blank
	 */
	static String load(String resourcePath) throws IOException {
		if (resourcePath == null) {
			throw new NullPointerException("resourcePath");
		}
		if (resourcePath.isBlank()) {
			throw new IllegalArgumentException("resourcePath must not be blank");
		}
		try (InputStream input = SqlResourceLoader.class.getResourceAsStream(resourcePath)) {
			if (input == null) {
				throw new IOException("SQL resource not found: " + resourcePath);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private SqlResourceLoader() {
	}
}
