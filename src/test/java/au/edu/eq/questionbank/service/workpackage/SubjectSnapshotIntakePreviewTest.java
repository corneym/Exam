
package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Verifies one-file Subject inspection against independent installations.
 */
class SubjectSnapshotIntakePreviewTest {

	@TempDir
	Path directory;

	@Test
	void acceptsNewSubjectDespiteCoincidentLocalIds() throws Exception {
		SqliteDatabase biology = createDatabase("biology-source");
		insertSubject(biology, 1, "Biology");

		SqliteDatabase destination = createDatabase("department");
		insertSubject(destination, 1, "Chemistry");

		Path archive = export(biology, "biology-source", 1, "biology.examwork");

		var inspection = new SubjectSnapshotIntakePreview(destination).inspect(archive);

		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, inspection.state());
		assertNull(inspection.existingSubjectId());
		assertEquals("Biology", inspection.fingerprint().subjectName());
		assertEquals(1, inspection.count("SUBJECT"));
		assertTrue(inspection.recordCounts().containsKey("SUBJECT"));

		// The source and destination both use local ID 1.
		// Preview neither conflates those IDs nor changes the destination.
		assertEquals(1, countSubjects(destination));
		assertEquals("Chemistry", subjectName(destination, 1));
	}

	@Test
	void recognisesAnExistingSubjectWithoutMergingIt() throws Exception {
		SqliteDatabase source = createDatabase("external-biology");
		insertSubject(source, 1, "Biology");

		SqliteDatabase destination = createDatabase("department-existing");
		insertSubject(destination, 51, "Biology");

		Path archive = export(source, "external-biology", 1, "biology-existing.examwork");

		var inspection = new SubjectSnapshotIntakePreview(destination).inspect(archive);

		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(51L, inspection.existingSubjectId());
		assertEquals(1, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, 51));
	}

	@Test
	void recognisesTwoDifferentSourceSubjectsIndependently() throws Exception {
		SqliteDatabase biology = createDatabase("bio");
		SqliteDatabase physics = createDatabase("physics");
		SqliteDatabase destination = createDatabase("hod");

		insertSubject(biology, 1, "Biology");
		insertSubject(physics, 1, "Physics");

		Path biologyArchive = export(biology, "bio", 1, "bio.examwork");
		Path physicsArchive = export(physics, "physics", 1, "physics2.examwork");

		var preview = new SubjectSnapshotIntakePreview(destination);
		var bioInspection = preview.inspect(biologyArchive);
		var physicsInspection = preview.inspect(physicsArchive);

		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, bioInspection.state());
		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, physicsInspection.state());

		assertEquals("Biology", bioInspection.fingerprint().subjectName());
		assertEquals("Physics", physicsInspection.fingerprint().subjectName());

		assertEquals(0, countSubjects(destination));
	}

	@Test
	void rejectsCorruptArchiveWithoutChangingDestination() throws Exception {
		SqliteDatabase destination = createDatabase("corrupt-destination");
		insertSubject(destination, 1, "Chemistry");

		Path archive = directory.resolve("broken.examwork");
		Files.writeString(archive, "not an ExamWork archive");

		assertThrows(IOException.class, () -> new SubjectSnapshotIntakePreview(destination).inspect(archive));

		assertEquals(1, countSubjects(destination));
		assertEquals("Chemistry", subjectName(destination, 1));
	}

	@Test
	void rejectsImportingSubjectBackIntoItsOriginInstallation() throws Exception {
		SqliteDatabase source = createDatabase("source");
		insertSubject(source, 1, "Physics");

		Path archive = export(source, "source", 1, "physics.examwork");

		assertThrows(IOException.class, () -> new SubjectSnapshotIntakePreview(source).inspect(archive));

		assertEquals("Physics", subjectName(source, 1));
	}

	private int countSubjects(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM subjects");
				ResultSet result = statement.executeQuery()) {
			return result.next() ? result.getInt(1) : -1;
		}
	}

	private SqliteDatabase createDatabase(String name) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve(name));

		Files.createDirectories(config.dataRoot());

		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		return database;
	}

	private Path export(SqliteDatabase database, String sourceDirectory, long subjectId, String archiveName)
			throws Exception {

		Path destination = directory.resolve(archiveName);

		new SubjectSnapshotExporter(database, new ManagedDataLayout(directory.resolve(sourceDirectory)))
				.export(subjectId, destination);

		assertTrue(Files.isRegularFile(destination));
		return destination;
	}

	private void insertSubject(SqliteDatabase database, long id, String name) throws SQLException {

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO subjects (id, subject_name)
						VALUES (?, ?)
						""")) {
			statement.setLong(1, id);
			statement.setString(2, name);
			assertEquals(1, statement.executeUpdate());
		}
	}

	private String subjectName(SqliteDatabase database, long id) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT subject_name
						FROM subjects
						WHERE id = ?
						""")) {
			statement.setLong(1, id);

			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getString(1);
			}
		}
	}
}
