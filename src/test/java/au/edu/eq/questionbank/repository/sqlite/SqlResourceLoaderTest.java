package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class SqlResourceLoaderTest {

	@Test
	void loadsSchemaResource() throws Exception {
		String sql = SqlResourceLoader.load("/db/schema-v1.sql");
		assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS schema_version"));
		assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS questions"));
		assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS answers"));
	}

	@Test
	void loadsMigrationResource() throws Exception {
		String sql = SqlResourceLoader.load("/db/migration-v1-to-v2.sql");
		assertTrue(sql.contains("CREATE TABLE curriculum_mappings"));
		assertTrue(sql.contains("WHERE version = 1"));
	}

	@Test
	void rejectsMissingAndInvalidResourceNames() {
		assertThrows(IOException.class, () -> SqlResourceLoader.load("/db/missing.sql"));
		assertThrows(IllegalArgumentException.class, () -> SqlResourceLoader.load(" "));
		assertThrows(NullPointerException.class, () -> SqlResourceLoader.load(null));
	}
}
