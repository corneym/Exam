package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumSourcePdfServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void attachesAndReopensManagedPdfForExistingPersistedSyllabus() throws Exception {
		Path dataRoot = tempDir.resolve("data");
		Path curriculumRoot = dataRoot.resolve("curriculum");
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		Files.createDirectories(dataRoot);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);

		// This represents an existing curriculum such as one previously imported from
		// Excel. It has no source PDF yet.
		var unit = writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		var topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		writer.insertDescriptor(topic, "1.1.1", "Resolve forces", 0);
		CurriculumAuthoringSession session = new CurriculumDraftLoader(
				new SqliteCurriculumAuthoringRepository(database)).load(syllabus);
		Path downloads = tempDir.resolve("downloads");
		Files.createDirectories(downloads);
		Path externalPdf = downloads.resolve("Engineering General Senior Syllabus 2025.pdf");
		byte[] content = """
				%PDF-1.4
				authoritative syllabus
				%%EOF
				""".getBytes(StandardCharsets.UTF_8);
		Files.write(externalPdf, content);
		CurriculumSourcePdfService service = new CurriculumSourcePdfService(
				new CurriculumSourcePdfStore(curriculumRoot), new SqliteCurriculumSourcePdfRepository(database));
		Path managedPdf = service.attachPdf(session, externalPdf);
		assertTrue(managedPdf.startsWith(curriculumRoot.toAbsolutePath().normalize()));
		assertTrue(Files.isRegularFile(managedPdf));
		assertArrayEquals(content, Files.readAllBytes(managedPdf));
		String managedRelativePath = session.syllabusVersion().getSourcePdfPath();
		assertTrue(managedRelativePath.startsWith(
				"Engineering--subject-" + engineering.getId() + "/2025--syllabus-" + syllabus.getId() + "/sources/"));
		assertTrue(managedRelativePath.endsWith("Engineering-General-Senior-Syllabus-2025.pdf"));

		// Once attached, the original external location is irrelevant.
		Files.delete(externalPdf);
		assertFalse(Files.exists(externalPdf));
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SyllabusVersion reloaded = curriculumRepository.findVersionById(syllabus.getId()).orElseThrow();
		assertEquals(managedRelativePath, reloaded.getSourcePdfPath());
		Path reopenedPdf = service.resolvePdf(reloaded).orElseThrow();
		assertEquals(managedPdf, reopenedPdf);
		assertTrue(Files.isRegularFile(reopenedPdf));
		assertArrayEquals(content, Files.readAllBytes(reopenedPdf));
	}

	@Test
	void failedReplacementKeepsExistingManagedPdfAndPersistedPath() throws Exception {
		Path dataRoot = tempDir.resolve("data");
		Path curriculumRoot = dataRoot.resolve("curriculum");
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		Files.createDirectories(dataRoot);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);
		var unit = writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		var topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		writer.insertDescriptor(topic, "1.1.1", "Resolve forces", 0);
		CurriculumAuthoringSession session = new CurriculumDraftLoader(
				new SqliteCurriculumAuthoringRepository(database)).load(syllabus);
		Path downloads = tempDir.resolve("downloads");
		Files.createDirectories(downloads);
		Path originalPdf = downloads.resolve("original.pdf");
		byte[] originalContent = """
				%PDF-1.4
				original syllabus
				%%EOF
				""".getBytes(StandardCharsets.UTF_8);
		Files.write(originalPdf, originalContent);
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(curriculumRoot);
		SqliteCurriculumSourcePdfRepository repository = new SqliteCurriculumSourcePdfRepository(database);
		CurriculumSourcePdfService service = new CurriculumSourcePdfService(store, repository);
		Path originalManagedPdf = service.attachPdf(session, originalPdf);
		String originalRelativePath = session.syllabusVersion().getSourcePdfPath();
		Path replacementPdf = downloads.resolve("replacement.pdf");
		Files.write(replacementPdf, """
				%PDF-1.4
				replacement syllabus
				%%EOF
				""".getBytes(StandardCharsets.UTF_8));
		CurriculumSourcePdfService failingService = new CurriculumSourcePdfService(store, (_, _) -> {
			throw new IllegalStateException("deliberate metadata failure");
		});
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> failingService.attachPdf(session, replacementPdf));
		assertEquals("deliberate metadata failure", failure.getMessage());
		assertEquals(originalRelativePath, session.syllabusVersion().getSourcePdfPath());
		assertTrue(Files.isRegularFile(originalManagedPdf));
		assertArrayEquals(originalContent, Files.readAllBytes(originalManagedPdf));
		SyllabusVersion reloaded = new SqliteCurriculumRepository(database).findVersionById(syllabus.getId())
				.orElseThrow();
		assertEquals(originalRelativePath, reloaded.getSourcePdfPath());
		try (var managedFiles = Files.list(originalManagedPdf.getParent())) {
			assertEquals(1L, managedFiles.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void failedSubjectFirstReplacementDeletesUnpublishedManagedCopy() throws Exception {
		Path dataRoot = Files.createDirectories(tempDir.resolve("failed-subject-first-data"));
		Path curriculumRoot = dataRoot.resolve("curriculum");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(chemistry, "2025", true);
		var unit = writer.insertUnit(syllabus, "1", "Chemical fundamentals", 0);
		var topic = writer.insertTopic(unit, "1.1", "Structure", 0);
		writer.insertDescriptor(topic, "1.1.1", "Describe atomic structure", 0);
		CurriculumAuthoringSession session = new CurriculumDraftLoader(
				new SqliteCurriculumAuthoringRepository(database)).load(syllabus);
		Path externalDirectory = Files.createDirectories(tempDir.resolve("failed-subject-first-downloads"));
		Path originalPdf = externalDirectory.resolve("original.pdf");
		Path replacementPdf = externalDirectory.resolve("replacement.pdf");
		Files.writeString(originalPdf, "%PDF-1.4\noriginal\n%%EOF");
		Files.writeString(replacementPdf, "%PDF-1.4\nreplacement\n%%EOF");
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(layout, curriculumRoot);
		SqliteCurriculumSourcePdfRepository repository = new SqliteCurriculumSourcePdfRepository(database);
		CurriculumSourcePdfService service = new CurriculumSourcePdfService(store, repository);
		Path originalManagedPdf = service.attachPdf(session, originalPdf);
		String originalRelativePath = session.syllabusVersion().getSourcePdfPath();
		CurriculumSourcePdfService failingService = new CurriculumSourcePdfService(store, (_, _) -> {
			throw new IllegalStateException("deliberate metadata failure");
		});
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> failingService.attachPdf(session, replacementPdf));
		assertEquals("deliberate metadata failure", failure.getMessage());
		assertEquals(originalRelativePath, session.syllabusVersion().getSourcePdfPath());
		SyllabusVersion reloaded = new SqliteCurriculumRepository(database).findVersionById(syllabus.getId())
				.orElseThrow();
		assertEquals(originalRelativePath, reloaded.getSourcePdfPath());
		assertTrue(Files.isRegularFile(originalManagedPdf));

		// The replacement received its own UUID-qualified file before persistence
		// failed. Cleanup must remove that unpublished copy without touching the
		// authoritative original.
		Path sourceDirectory = layout.curriculumSourceDirectory("Chemistry", "2025");
		try (var managedFiles = Files.list(sourceDirectory)) {
			assertEquals(1L, managedFiles.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void subjectFirstAttachmentPersistsDataRootRelativePathAndReopens() throws Exception {
		Path dataRoot = Files.createDirectories(tempDir.resolve("subject-first-service-data"));
		Path curriculumRoot = dataRoot.resolve("curriculum");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(chemistry, "2025", true);
		var unit = writer.insertUnit(syllabus, "1", "Chemical fundamentals", 0);
		var topic = writer.insertTopic(unit, "1.1", "Structure", 0);
		writer.insertDescriptor(topic, "1.1.1", "Describe atomic structure", 0);
		CurriculumAuthoringSession session = new CurriculumDraftLoader(
				new SqliteCurriculumAuthoringRepository(database)).load(syllabus);
		Path externalDirectory = Files.createDirectories(tempDir.resolve("subject-first-service-downloads"));
		Path externalPdf = externalDirectory.resolve("Chemistry Syllabus.pdf");
		byte[] content = """
				%PDF-1.4
				subject-first curriculum source
				%%EOF
				""".getBytes(StandardCharsets.UTF_8);
		Files.write(externalPdf, content);
		CurriculumSourcePdfService service = new CurriculumSourcePdfService(
				new CurriculumSourcePdfStore(layout, curriculumRoot),
				new SqliteCurriculumSourcePdfRepository(database));
		Path managedPdf = service.attachPdf(session, externalPdf);
		String persistedPath = session.syllabusVersion().getSourcePdfPath();
		assertTrue(persistedPath.startsWith("subjects/Chemistry/curriculum/2025/sources/"));
		assertEquals(layout.resolve(persistedPath), managedPdf);
		assertTrue(managedPdf.startsWith(layout.curriculumSourceDirectory("Chemistry", "2025")));
		assertArrayEquals(content, Files.readAllBytes(managedPdf));

		// Prove the database carries the portable data-root-relative representation,
		// not merely the in-memory authoring session.
		SyllabusVersion reloaded = new SqliteCurriculumRepository(database).findVersionById(syllabus.getId())
				.orElseThrow();
		assertEquals(persistedPath, reloaded.getSourcePdfPath());
		assertEquals(managedPdf, service.resolvePdf(reloaded).orElseThrow());

		// Once managed, reopening must not depend on the original selected location.
		Files.delete(externalPdf);
		assertFalse(Files.exists(externalPdf));
		assertArrayEquals(content, Files.readAllBytes(service.resolvePdf(reloaded).orElseThrow()));
	}
}
