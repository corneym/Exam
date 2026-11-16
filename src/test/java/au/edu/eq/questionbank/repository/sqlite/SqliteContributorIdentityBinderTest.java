package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorIdentityBinder.Binding;

class SqliteContributorIdentityBinderTest {

	@TempDir
	Path directory;

	@Test
	void collisionRestoresGeneratedIdentityAndTrigger() throws Exception {
		SqliteDatabase database = database("collision.db");
		try (Connection connection = database.openConnection()) {
			insertSubject(connection, 1, "Chemistry");
		}
		String existing = portableId(database, EntityType.SUBJECT, 1);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			insertSubject(connection, 2, "Biology");
			String generated = portableId(connection, EntityType.SUBJECT, 2);

			// An existing Subject already owns this portable UUID.
			assertThrows(SQLException.class, () -> binder.bindNew(
					List.of(new Binding(EntityType.SUBJECT, 2, UUID.fromString(existing), UUID.randomUUID()))));

			// The entire rebind savepoint has rolled back.
			assertEquals(generated, portableId(connection, EntityType.SUBJECT, 2));
			SqlitePortableIdentitySchema.verifyTriggers(connection);
			connection.commit();
		}
		assertEquals(existing, portableId(database, EntityType.SUBJECT, 1));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void completeTransactionRollbackDiscardsInstalledIdentity() throws Exception {
		SqliteDatabase database = database("rollback.db");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			insertSubject(connection, 1, "Chemistry");
			binder.bindNew(List.of(new Binding(EntityType.SUBJECT, 1, UUID.randomUUID(), UUID.randomUUID())));
			connection.rollback();
		}
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*) FROM subjects
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void preservesNewIdentityAndRestoresNormalTriggers() throws Exception {
		SqliteDatabase database = database("preserve.db");
		UUID issuedId = UUID.randomUUID();
		UUID issuedRevision = UUID.randomUUID();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			insertSubject(connection, 1, "Chemistry");
			String generatedId = portableId(connection, EntityType.SUBJECT, 1);
			assertNotEquals(issuedId.toString(), generatedId);
			binder.bindNew(List.of(new Binding(EntityType.SUBJECT, 1, issuedId, issuedRevision)));
			assertEquals(issuedId.toString(), portableId(connection, EntityType.SUBJECT, 1));
			assertEquals(issuedRevision.toString(), revisionId(connection, EntityType.SUBJECT, 1));
			SqlitePortableIdentitySchema.verifyTriggers(connection);
			connection.commit();
		}
		database.verifySchema();
		database.verifyIntegrity();

		// Ordinary inserts continue generating independent identities.
		try (Connection connection = database.openConnection()) {
			insertSubject(connection, 2, "Biology");
			assertNotEquals(issuedId.toString(), portableId(connection, EntityType.SUBJECT, 2));
		}
		assertEquals(issuedId.toString(), portableId(database, EntityType.SUBJECT, 1));
	}

	@Test
	void refusesToRebindPreExistingIdentity() throws Exception {
		SqliteDatabase database = database("existing.db");
		try (Connection connection = database.openConnection()) {
			insertSubject(connection, 1, "Chemistry");
		}
		String original = portableId(database, EntityType.SUBJECT, 1);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			assertThrows(SQLException.class, () -> binder
					.bindNew(List.of(new Binding(EntityType.SUBJECT, 1, UUID.randomUUID(), UUID.randomUUID()))));
			connection.rollback();
		}
		assertEquals(original, portableId(database, EntityType.SUBJECT, 1));
		database.verifySchema();
	}

	@Test
	void refusesToResurrectRetiredPortableIdentity() throws Exception {
		SqliteDatabase database = database("retirement.db");
		try (Connection connection = database.openConnection()) {
			insertSubject(connection, 1, "Chemistry");
		}
		String oldId = portableId(database, EntityType.SUBJECT, 1);
		String oldRevision;
		try (Connection connection = database.openConnection()) {
			oldRevision = revisionId(connection, EntityType.SUBJECT, 1);
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate("""
						DELETE FROM subjects WHERE id = 1
						""");
			}
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqliteContributorIdentityBinder binder = new SqliteContributorIdentityBinder(connection);
			insertSubject(connection, 2, "Biology");
			assertThrows(SQLException.class, () -> binder.bindNew(
					List.of(new Binding(EntityType.SUBJECT, 2, UUID.fromString(oldId), UUID.fromString(oldRevision)))));
			connection.rollback();
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	private SqliteDatabase database(String name) throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve(name));
		database.initialiseSchema();
		return database;
	}

	private void insertSubject(Connection connection, long id, String name) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO subjects (id, subject_name)
				VALUES (?, ?)
				""")) {
			statement.setLong(1, id);
			statement.setString(2, name);
			assertEquals(1, statement.executeUpdate());
		}
	}

	private String portableId(Connection connection, EntityType type, long localId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = ?
				  AND local_id = ?
				""")) {
			statement.setString(1, type.name());
			statement.setLong(2, localId);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				String value = result.getString(1);
				assertFalse(result.next());
				return value;
			}
		}
	}

	private String portableId(SqliteDatabase database, EntityType type, long localId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return portableId(connection, type, localId);
		}
	}

	private String revisionId(Connection connection, EntityType type, long localId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT revision_id
				FROM portable_entity_identities
				WHERE entity_type = ?
				  AND local_id = ?
				""")) {
			statement.setString(1, type.name());
			statement.setLong(2, localId);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				String value = result.getString(1);
				assertFalse(result.next());
				return value;
			}
		}
	}
}
