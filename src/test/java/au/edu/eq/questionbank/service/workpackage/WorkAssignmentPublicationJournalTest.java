package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentAssetExtractor.Extraction;

class WorkAssignmentPublicationJournalTest {

	@TempDir
	Path directory;

	@Test
	void changedDestinationIsNeverDeletedDuringRecovery() throws Exception {
		Fixture fixture = fixture();
		var extraction = fixture.extraction();
		var journal = fixture.journal();
		var packageId = extraction.staged().inspection().manifest().packageId();
		journal.prepare(extraction);
		journal.publishFiles(packageId);
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);

		// Simulate external replacement of a published file.
		Files.delete(question);
		Files.writeString(question, "External replacement");
		assertThrows(IOException.class, () -> journal.recover(packageId));
		assertEquals("External replacement", Files.readString(question));

		// Unsafe recovery preserves private evidence for investigation.
		assertTrue(Files.isRegularFile(extraction.staged().archive()));
	}

	@Test
	void changedStagedFilePreventsPublication() throws Exception {
		Fixture fixture = fixture();
		var extraction = fixture.extraction();
		var journal = fixture.journal();
		journal.prepare(extraction);
		Path stagedAsset = extraction.assets().getFirst().stagedPath();
		Files.writeString(stagedAsset, "Changed private file");
		var packageId = extraction.staged().inspection().manifest().packageId();
		assertThrows(IOException.class, () -> journal.publishFiles(packageId));
		assertFalse(Files.exists(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		assertFalse(Files.exists(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH)));
	}

	@Test
	void existingIdenticalFilesSurviveFailedInstallation() throws Exception {
		Fixture fixture = fixture();
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		Path answer = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH);
		Files.createDirectories(question.getParent());
		Files.write(question, WorkAssignmentArchiveFixture.QUESTION_BYTES);
		Files.write(answer, WorkAssignmentArchiveFixture.ANSWER_BYTES);
		var extraction = fixture.extraction();
		var journal = fixture.journal();
		var packageId = extraction.staged().inspection().manifest().packageId();
		journal.prepare(extraction);
		journal.publishFiles(packageId);
		journal.verifyPublished(packageId);
		assertFalse(journal.recover(packageId));

		// Recovery must not delete files that existed before
		// the assignment was installed.
		assertArrayEquals(WorkAssignmentArchiveFixture.QUESTION_BYTES, Files.readAllBytes(question));
		assertArrayEquals(WorkAssignmentArchiveFixture.ANSWER_BYTES, Files.readAllBytes(answer));
	}

	@Test
	void failedInstallationRemovesOnlyNewlyPublishedFiles() throws Exception {
		Fixture fixture = fixture();
		var journal = fixture.journal();
		var extraction = fixture.extraction();
		var packageId = extraction.staged().inspection().manifest().packageId();
		journal.prepare(extraction);
		journal.publishFiles(packageId);
		journal.verifyPublished(packageId);
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		Path answer = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH);
		assertArrayEquals(WorkAssignmentArchiveFixture.QUESTION_BYTES, Files.readAllBytes(question));
		assertArrayEquals(WorkAssignmentArchiveFixture.ANSWER_BYTES, Files.readAllBytes(answer));
		assertFalse(journal.recover(packageId));
		assertFalse(Files.exists(question));
		assertFalse(Files.exists(answer));
		assertFalse(Files.exists(extraction.staged().directory()));
		fixture.database().verifyIntegrity();
	}

	@Test
	void journalMustPrecedeManagedFilePublication() throws Exception {
		Fixture fixture = fixture();
		var packageId = fixture.extraction().staged().inspection().manifest().packageId();
		assertThrows(IOException.class, () -> fixture.journal().publishFiles(packageId));
		assertFalse(Files.exists(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
	}

	@Test
	void secondPreparationCannotReplaceExistingJournal() throws Exception {
		Fixture fixture = fixture();
		var extraction = fixture.extraction();
		var journal = fixture.journal();
		journal.prepare(extraction);
		assertThrows(IOException.class, () -> journal.prepare(extraction));
		assertTrue(Files.isRegularFile(extraction.staged().directory().resolve("publication.properties")));
	}

	private Fixture fixture() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		Path incoming = WorkAssignmentArchiveFixture.write(directory.resolve("issued.examwork"));
		var staged = new WorkAssignmentArchiveStager(config).stage(incoming);
		var extraction = new WorkAssignmentAssetExtractor(config).extract(staged);
		return new Fixture(config, database, extraction, new WorkAssignmentPublicationJournal(config));
	}

	private record Fixture(ApplicationConfig config, SqliteDatabase database, Extraction extraction,
			WorkAssignmentPublicationJournal journal) {
	}
}
