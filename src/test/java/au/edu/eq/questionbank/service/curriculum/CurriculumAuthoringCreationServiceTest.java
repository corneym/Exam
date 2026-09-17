package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumImporter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumAuthoringCreationServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void createsNewSubjectAndEmptyInProgressSyllabus() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("new-curriculum.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		CurriculumAuthoringCreationService service = new CurriculumAuthoringCreationService(
				new SqliteCurriculumImporter(database, writer),
				new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database)));
		CurriculumAuthoringSession session = service.create("Engineering", "2025", true);
		assertEquals("Engineering", session.syllabusVersion().getSubject().getName());
		assertEquals("2025", session.syllabusVersion().getName());
		assertTrue(session.syllabusVersion().isCurrent());
		assertEquals(CurriculumStatus.IN_PROGRESS, session.syllabusVersion().getCurriculumStatus());
		assertTrue(session.draft().nodes().isEmpty());
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		assertEquals(1, repository.findAllSubjects().size());
		Subject engineering = repository.findAllSubjects().get(0);
		assertEquals("Engineering", engineering.getName());
		SyllabusVersion stored = repository.findVersionsForSubject(engineering).get(0);
		assertEquals(session.syllabusVersion().getId(), stored.getId());
		assertEquals(CurriculumStatus.IN_PROGRESS, stored.getCurriculumStatus());
		assertTrue(stored.isCurrent());
	}

	@Test
	void newCurrentVersionMakesPreviousVersionHistorical() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("current-version.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion previous = writer.insertSyllabusVersion(engineering, "2025", true);
		CurriculumAuthoringCreationService service = new CurriculumAuthoringCreationService(
				new SqliteCurriculumImporter(database, writer),
				new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database)));
		CurriculumAuthoringSession session = service.create("Engineering", "2026", true);
		assertTrue(session.syllabusVersion().isCurrent());
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		SyllabusVersion reloadedPrevious = repository.findVersionById(previous.getId()).orElseThrow();
		assertFalse(reloadedPrevious.isCurrent());
		SyllabusVersion reloadedNew = repository.findVersionById(session.syllabusVersion().getId()).orElseThrow();
		assertTrue(reloadedNew.isCurrent());
	}

	@Test
	void refusesToCreateDuplicateSyllabusVersion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("duplicate-version.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		CurriculumAuthoringCreationService service = new CurriculumAuthoringCreationService(
				new SqliteCurriculumImporter(database, writer),
				new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database)));
		service.create("Engineering", "2025", true);
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.create("Engineering", "2025", true));
		assertTrue(exception.getMessage().contains("already exists"));
	}
}
