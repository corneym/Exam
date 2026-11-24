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
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;

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
	void refreshRejectsDifferentSubjectIdentityWithinSameInstallation() throws Exception {

		SqliteDatabase source = createDatabase("refresh-wrong-subject-source");

		insertSubject(source, 1, "Biology");

		SqliteDatabase destination = createDatabase("refresh-wrong-subject-destination");

		ApplicationConfig config = ApplicationConfig
				.fromDataRoot(directory.resolve("refresh-wrong-subject-destination"));

		Path original = export(source, "refresh-wrong-subject-source", 1, "refresh-original-subject.examwork");

		var preview = new SubjectSnapshotIntakePreview(destination);

		var initial = preview.inspect(original);

		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, original).applied());

		// Retain the original authoritative Subject under a
		// different name and create a distinct Subject that
		// reuses its former name on the same installation.
		try (Connection connection = source.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE subjects
						SET subject_name = 'Historical Biology'
						WHERE id = 1
						""")) {

			assertEquals(1, statement.executeUpdate());
		}

		insertSubject(source, 2, "Biology");

		Path differentSubject = export(source, "refresh-wrong-subject-source", 2, "refresh-different-subject.examwork");

		var inspection = preview.inspect(differentSubject);

		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());

		assertEquals(initial.fingerprint().originInstallationId(), inspection.fingerprint().originInstallationId());

		assertTrue(!initial.fingerprint().subjectId().equals(inspection.fingerprint().subjectId()));

		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> preview.requireRefreshAuthority(inspection));

		assertTrue(failure.getMessage().contains("different originating Subject identity"));

		assertEquals(1, countSubjects(destination));

		long localSubjectId = inspection.existingSubjectId().longValue();

		var authority = new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow();

		assertEquals(initial.fingerprint().subjectId(), authority.originSubjectPortableId());

		assertEquals(initial.fingerprint().packageId(), authority.latestPackageId());

		destination.verifyIntegrity();
	}

	@Test
	void refreshRejectsLocallyAuthoritativeSubject() throws Exception {

		SqliteDatabase source = createDatabase("refresh-local-source");
		insertSubject(source, 1, "Biology");

		SqliteDatabase destination = createDatabase("refresh-local-destination");

		insertSubject(destination, 51, "Biology");

		Path archive = export(source, "refresh-local-source", 1, "refresh-local.examwork");

		var preview = new SubjectSnapshotIntakePreview(destination);
		var inspection = preview.inspect(archive);

		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());

		assertEquals(51L, inspection.existingSubjectId());

		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> preview.requireRefreshAuthority(inspection));

		assertTrue(failure.getMessage().contains("locally authoritative"));

		assertEquals(1, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, 51));

		assertTrue(new SqliteImportedSubjectAuthorityRepository(destination).find(51L).isEmpty());

		destination.verifyIntegrity();
	}

	@Test
	void refreshRejectsNewSubjectAndPreviouslyAppliedPackage() throws Exception {

		SqliteDatabase source = createDatabase("refresh-replay-source");

		insertSubject(source, 1, "Biology");

		SqliteDatabase destination = createDatabase("refresh-replay-destination");

		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("refresh-replay-destination"));

		Path archive = export(source, "refresh-replay-source", 1, "refresh-replay.examwork");

		var preview = new SubjectSnapshotIntakePreview(destination);

		var initial = preview.inspect(archive);

		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, initial.state());

		assertThrows(PackageFormatException.class, () -> preview.requireRefreshAuthority(initial));

		assertEquals(0, countSubjects(destination));

		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, archive).applied());

		var replay = preview.inspect(archive);

		assertEquals(SubjectSnapshotIntakePreview.State.ALREADY_IMPORTED, replay.state());

		// The ordinary intake route may report an idempotent replay,
		// but it must not prepare another refresh transaction.
		assertThrows(PackageFormatException.class, () -> preview.requireRefreshAuthority(replay));

		assertEquals(1, countSubjects(destination));

		destination.verifyIntegrity();
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

	@Test
	void renamedOriginCannotBecomeDuplicateNewSubject() throws Exception {

		SqliteDatabase source = createDatabase("refresh-rename-source");

		insertSubject(source, 1, "Biology");

		SqliteDatabase destination = createDatabase("refresh-rename-destination");

		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("refresh-rename-destination"));

		Path original = export(source, "refresh-rename-source", 1, "refresh-rename-original.examwork");

		var preview = new SubjectSnapshotIntakePreview(destination);

		var initial = preview.inspect(original);

		SubjectImportCoordinator coordinator = new SubjectImportCoordinator(config);

		assertTrue(coordinator.executeNewSubject(initial, original).applied());

		var originalAuthority = new SqliteImportedSubjectAuthorityRepository(destination)
				.find(new SqliteCurriculumRepository(destination).findAllSubjects().getFirst().getId()).orElseThrow();

		// Rename the authoritative Subject without changing its
		// originating installation or portable Subject identity.
		try (Connection connection = source.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE subjects
						SET subject_name = ?
						WHERE id = ?
						""")) {

			statement.setString(1, "Biological Science");
			statement.setLong(2, 1L);

			assertEquals(1, statement.executeUpdate());
		}

		Path renamed = export(source, "refresh-rename-source", 1, "refresh-renamed.examwork");

		var inspection = preview.inspect(renamed);

		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());

		assertEquals(originalAuthority.subjectLocalId(), inspection.existingSubjectId().longValue());

		assertEquals(originalAuthority.originSubjectPortableId(), inspection.fingerprint().subjectId());

		assertEquals(originalAuthority, preview.requireRefreshAuthority(inspection));

		// Eligibility identifies the established Subject.
		// It does not perform the rename or install another Subject.
		assertThrows(IOException.class, () -> coordinator.executeNewSubject(inspection, renamed));

		assertEquals(1, countSubjects(destination));

		assertEquals("Biology", subjectName(destination, originalAuthority.subjectLocalId()));

		assertEquals(originalAuthority, new SqliteImportedSubjectAuthorityRepository(destination)
				.find(originalAuthority.subjectLocalId()).orElseThrow());

		// A separate local Subject with the incoming name creates
		// an ambiguous destination that must fail closed.
		insertSubject(destination, 99, "Biological Science");

		assertThrows(SQLException.class, () -> preview.inspect(renamed));

		assertEquals(2, countSubjects(destination));
		assertEquals("Biological Science", subjectName(destination, 99));

		destination.verifyIntegrity();
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
