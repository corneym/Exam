package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumSourcePdfReplacementRegressionTest {

	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private SqliteCurriculumWriter writer;
	private Subject subject;
	private CurriculumSourcePdfService service;

	@BeforeEach
	void createStore() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("curriculum.db"));
		database.initialiseSchema();
		writer = new SqliteCurriculumWriter(database);
		subject = writer.insertSubject("Engineering");
		service = new CurriculumSourcePdfService(new CurriculumSourcePdfStore(tempDir.resolve("curriculum")),
				new SqliteCurriculumSourcePdfRepository(database));
	}

	@Test
	void rejectedReplacementPreservesAuthoritativeBytesAndDatabaseReference() throws Exception {
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);
		CurriculumAuthoringSession session = session(version);
		Path original = pdf("original/syllabus.pdf", 1);
		Path replacement = pdf("replacement/syllabus.pdf", 2);
		Path managed = service.attachPdf(session, original);
		byte[] originalBytes = Files.readAllBytes(managed);
		String originalReference = session.syllabusVersion().getSourcePdfPath();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_pdf_metadata BEFORE UPDATE OF source_pdf_path ON syllabus_versions
					BEGIN SELECT RAISE(ABORT, 'PDF metadata rejected'); END
					""");
		}
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.attachPdf(session, replacement));
		assertTrue(failure.getCause().getMessage().contains("PDF metadata rejected"));
		SyllabusVersion reloaded = new SqliteCurriculumRepository(database).findVersionById(version.getId())
				.orElseThrow();
		assertAll(
				() -> assertEquals(originalReference, reloaded.getSourcePdfPath()),
				() -> assertEquals(originalReference, session.syllabusVersion().getSourcePdfPath()),
				() -> assertArrayEquals(originalBytes, Files.readAllBytes(service.resolvePdf(reloaded).orElseThrow())),
				() -> assertArrayEquals(originalBytes, Files.readAllBytes(managed)));
	}

	@Test
	void distinctSyllabusNamesCannotOverwriteEachOthersManagedPdf() throws Exception {
		CurriculumAuthoringSession first = session(writer.insertSyllabusVersion(subject, "2025/26", false));
		CurriculumAuthoringSession second = session(writer.insertSyllabusVersion(subject, "2025-26", true));
		Path firstSource = pdf("first/syllabus.pdf", 1);
		Path secondSource = pdf("second/syllabus.pdf", 2);
		service.attachPdf(first, firstSource);
		service.attachPdf(second, secondSource);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		SyllabusVersion firstReload = repository.findVersionById(first.syllabusVersion().getId()).orElseThrow();
		SyllabusVersion secondReload = repository.findVersionById(second.syllabusVersion().getId()).orElseThrow();
		assertAll(
				() -> assertArrayEquals(Files.readAllBytes(firstSource),
						Files.readAllBytes(service.resolvePdf(firstReload).orElseThrow())),
				() -> assertArrayEquals(Files.readAllBytes(secondSource),
						Files.readAllBytes(service.resolvePdf(secondReload).orElseThrow())));
	}

	private CurriculumAuthoringSession session(SyllabusVersion version) {
		return new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database)).load(version);
	}

	private Path pdf(String relativePath, int pageCount) throws Exception {
		Path path = tempDir.resolve(relativePath);
		Files.createDirectories(path.getParent());
		try (PDDocument document = new PDDocument()) {
			for (int page = 0; page < pageCount; page++) {
				document.addPage(new PDPage());
			}
			document.save(path.toFile());
		}
		return path;
	}
}
