package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

class SqliteConnectionTest {

	@Test
	void opensInMemorySqliteDatabase() throws Exception {
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
			assertFalse(connection.isClosed());
		}
	}
}