package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteReadProbeTest {

	@TempDir
	Path tempDirectory;

	@Test
	void countsConnectionsExecutionsAndRows() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("probe.db"));
		database.initialiseSchema();
		SqliteReadProbe.Snapshot snapshot;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			try (Connection connection = database.openConnection()) {
				try (Statement statement = connection.createStatement();
						ResultSet result = statement.executeQuery("SELECT 1 AS value UNION ALL SELECT 2")) {
					assertTrue(result.next());
					assertEquals(1, result.getInt("value"));
					assertTrue(result.next());
					assertEquals(2, result.getInt("value"));
					assertFalse(result.next());
				}
				try (PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
					statement.setInt(1, 3);
					try (ResultSet result = statement.executeQuery()) {
						assertTrue(result.next());
						assertEquals(3, result.getInt(1));
						assertFalse(result.next());
					}
				}
			}
			snapshot = probe.snapshot();
		}
		assertEquals(1, snapshot.connectionsOpened());
		assertEquals(2, snapshot.executions());
		assertEquals(3, snapshot.rowsRead());
		assertEquals(0, snapshot.questionsReconstructed());
		assertTrue(snapshot.connectionOpenNanos() >= 0);
		assertTrue(snapshot.executionNanos() >= 0);
		assertTrue(snapshot.rowNextNanos() >= 0);
		assertTrue(snapshot.elapsedNanos() > 0);
	}

	@Test
	void countsFailedExecutionWithoutChangingJdbcException() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("failure.db"));
		database.initialiseSchema();
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			try (Connection connection = database.openConnection();
					Statement statement = connection.createStatement()) {
				assertThrows(SQLException.class, () -> statement.executeQuery("SELECT missing_column FROM subjects"));
			}
			SqliteReadProbe.Snapshot snapshot = probe.snapshot();
			assertEquals(1, snapshot.connectionsOpened());
			assertEquals(1, snapshot.executions());
			assertEquals(0, snapshot.rowsRead());
			assertEquals(0, snapshot.questionsReconstructed());
		}
	}

	@Test
	void rejectsNestedScopesAndRestoresOrdinaryConnections() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("scope.db"));
		database.initialiseSchema();
		SqliteReadProbe.Snapshot finished;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			assertThrows(IllegalStateException.class, SqliteReadProbe::begin);
			try (Connection connection = database.openConnection();
					Statement statement = connection.createStatement();
					ResultSet result = statement.executeQuery("SELECT 1")) {
				assertTrue(result.next());
			}

			// Verify that the active diagnostic scope recorded the connection.
			assertEquals(1, probe.snapshot().connectionsOpened());
		}

		// A new scope must be possible after the first is closed.
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			try (Connection connection = database.openConnection()) {
				assertFalse(connection.isClosed());
			}
			finished = probe.snapshot();
		}
		assertEquals(1, finished.connectionsOpened());
		assertEquals(0, finished.executions());
		assertEquals(0, finished.rowsRead());

		// Without a scope, the ordinary SQLite connection is returned.
		try (Connection connection = database.openConnection()) {
			assertFalse(Proxy.isProxyClass(connection.getClass()));
		}
	}
}
