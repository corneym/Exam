package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		/*
		 * This represents an existing curriculum such as one previously imported from
		 * Excel. It has no source PDF yet.
		 */
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
		assertEquals("Engineering/2025/sources/" + "Engineering-General-Senior-Syllabus-2025.pdf",
				session.syllabusVersion().getSourcePdfPath());
		/*
		 * Once attached, the original external location is irrelevant.
		 */
		Files.delete(externalPdf);
		assertFalse(Files.exists(externalPdf));
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SyllabusVersion reloaded = curriculumRepository.findVersionById(syllabus.getId()).orElseThrow();
		assertEquals("Engineering/2025/sources/" + "Engineering-General-Senior-Syllabus-2025.pdf",
				reloaded.getSourcePdfPath());
		Path reopenedPdf = service.resolvePdf(reloaded).orElseThrow();
		assertEquals(managedPdf, reopenedPdf);
		assertTrue(Files.isRegularFile(reopenedPdf));
		assertArrayEquals(content, Files.readAllBytes(reopenedPdf));
	}
}
