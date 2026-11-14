package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Integration tests for verified Subject snapshot fingerprints.
 */
class SubjectImportSnapshotFingerprintTest {

	@TempDir
	Path directory;

	/**
	 * Confirms a material change to the Subject invalidates the previously reviewed
	 * master snapshot.
	 */
	@Test
	void changedMasterSubjectInvalidatesReview() throws Exception {
		SubjectSnapshotExporter exporter = exporter();
		Path beforeArchive = directory.resolve("before.examwork");
		exporter.export(1, beforeArchive);
		SubjectImportSnapshotFingerprint service = new SubjectImportSnapshotFingerprint();
		var reviewed = service.inspect(beforeArchive);
		SqliteDatabase database = new SqliteDatabase(directory.resolve("data/questionbank.db"));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE subjects
					SET subject_name = 'Biology Revised'
					WHERE id = 1
					""");
		}
		Path afterArchive = directory.resolve("after.examwork");
		exporter.export(1, afterArchive);
		var changed = service.inspect(afterArchive);
		assertNotEquals(reviewed.contentSha256(), changed.contentSha256());
		assertThrows(IllegalStateException.class, () -> service.requireSameContent(reviewed, afterArchive));
	}

	/**
	 * Confirms repeated exports have stable semantic content despite different
	 * package identities and archive bytes.
	 */
	@Test
	void repeatedExportOfUnchangedSubjectHasSameContentFingerprint() throws Exception {
		SubjectSnapshotExporter exporter = exporter();
		Path firstArchive = directory.resolve("first.examwork");
		Path secondArchive = directory.resolve("second.examwork");
		exporter.export(1, firstArchive);
		exporter.export(1, secondArchive);
		SubjectImportSnapshotFingerprint service = new SubjectImportSnapshotFingerprint();
		var first = service.inspect(firstArchive);
		var second = service.inspect(secondArchive);
		assertNotEquals(first.packageId(), second.packageId());
		assertNotEquals(first.archiveSha256(), second.archiveSha256());
		assertEquals(first.contentSha256(), second.contentSha256());
		service.requireSameContent(first, secondArchive);
		assertThrows(IllegalStateException.class, () -> service.requireSamePackage(first, secondArchive));
	}

	/**
	 * Confirms the same original file remains valid when it is revalidated without
	 * modification.
	 */
	@Test
	void unchangedIncomingArchivePassesExactPackageCheck() throws Exception {
		SubjectSnapshotExporter exporter = exporter();
		Path archive = directory.resolve("incoming.examwork");
		exporter.export(1, archive);
		SubjectImportSnapshotFingerprint service = new SubjectImportSnapshotFingerprint();
		var inspected = service.inspect(archive);
		service.requireSamePackage(inspected, archive);
	}

	/**
	 * Creates a real schema-v27 Biology database and exporter.
	 */
	private SubjectSnapshotExporter exporter() throws Exception {
		Path dataRoot = directory.resolve("data");
		Files.createDirectories(dataRoot);
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
		}
		return new SubjectSnapshotExporter(database, new ManagedDataLayout(dataRoot));
	}
}
