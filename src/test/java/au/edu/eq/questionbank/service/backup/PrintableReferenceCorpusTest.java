package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableAssessmentWriter;
import au.edu.eq.questionbank.pdf.PrintableExamFonts;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class PrintableReferenceCorpusTest {

	@TempDir
	Path tempDir;

	@Test
	void auditsAllEligibleReferenceQuestions() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean("exam.reference.sweep"), "Reference corpus sweep not requested.");
		Path archive = requireReferenceArchive();
		byte[] archiveBefore = sha256(archive);
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("isolated-sweep-data"));
		List<String> failures = new ArrayList<>();
		int attempted = 0;
		try (RestorePreparation preparation = new DefaultRestoreService(config).prepareRestore(archive)) {
			assertEquals(BackupKind.FULL, preparation.manifest().kind());
			SqliteDatabase database = new SqliteDatabase(preparation.databasePath());
			SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
			PrintableAssessmentWriter writer = new PrintableAssessmentWriter(
					new PdfStore(new ManagedDataLayout(preparation.stagingRoot())));
			List<Question> questions = repository.findAll().stream()
					.filter(question -> question.getResponseType() == QuestionResponseType.MULTIPLE_CHOICE
							|| question.getResponseType() == QuestionResponseType.WRITTEN_RESPONSE)
					.filter(PrintableAssessmentWriter::canSelect).toList();
			assertFalse(questions.isEmpty());
			for (Question question : questions) {
				attempted++;
				try {
					auditQuestion(writer, question);
				} catch (IOException | RuntimeException failure) {
					String message = describeQuestion(question) + ": " + failure.getMessage();
					failures.add(message);
				}
			}
			database.verifyIntegrity();
		} finally {
			assertArrayEquals(archiveBefore, sha256(archive));
		}
		System.out.println("Reference Questions attempted: " + attempted);
		System.out.println("Reference Question failures: " + failures.size());
		failures.forEach(System.out::println);
		assertTrue(failures.isEmpty(), () -> String.join(System.lineSeparator(), failures));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void generatesMixedReferenceExamination() throws Exception {
		Path archive = requireReferenceArchive();
		byte[] archiveBefore = sha256(archive);
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("isolated-data"));
		Path outputRoot = Path.of(System.getProperty("exam.reference.output",
				archive.getParent().resolveSibling("generated").toString()));
		Files.createDirectories(outputRoot);
		Path output = Files.createTempDirectory(outputRoot, "reference-");
		try (RestorePreparation preparation = new DefaultRestoreService(config).prepareRestore(archive)) {
			assertEquals(BackupKind.FULL, preparation.manifest().kind());
			assertEquals(49, preparation.manifest().databaseSchemaVersion());
			SqliteDatabase database = new SqliteDatabase(preparation.databasePath());
			database.verifySchema();
			database.verifyIntegrity();
			SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
			Map<Path, byte[]> sourceHashes = hashSourcePdfs(preparation.subjectsRoot());
			List<Question> multipleChoice = List.of(requireQuestion(repository, 1, "QCAA", 2020, "1"),
					requireQuestion(repository, 338, "NEAP", 2021, "1"),
					requireQuestion(repository, 542, "Oxford", 2025, "1"),
					requireQuestion(repository, 522, "Sylligence", 2026, "1"));
			Question olderQ22 = requireQuestion(repository, 81, "QCAA", 2021, "22");
			assertTrue(olderQ22.getContentParts().size() >= 3);
			assertTrue(olderQ22.hasSharedContext());
			List<Question> paperOne = List.of(olderQ22, requireQuestion(repository, 404, "NEAP", 2021, "30"),
					requireQuestion(repository, 142, "QCAA", 2022, "22"));
			List<Question> paperTwo = List.of(requireQuestion(repository, 460, "QCAA", 2025, "22"),
					requireQuestion(repository, 690, "Oxford", 2025, "27"),
					requireQuestion(repository, 578, "Sylligence", 2026, "28"));
			PrintableAssessmentPlan plan = new PrintableAssessmentPlan(multipleChoice, paperOne, paperTwo);
			assertEquals(4, plan.getMultipleChoiceCount());
			assertEquals(14, plan.getPaperOneTotalMarks());
			assertEquals(14, plan.getPaperTwoTotalMarks());
			assertEquals(28, plan.getTotalMarks());
			PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 5, 60);
			PdfStore pdfStore = new PdfStore(new ManagedDataLayout(preparation.stagingRoot()));
			PrintableAssessmentWriter writer = new PrintableAssessmentWriter(pdfStore);
			writer.generate(settings, plan, PrintableExamFonts.windowsFontDirectory(), output);
			for (String filename : List.of("MCQ.pdf", "Paper 1.pdf", "Paper 2.pdf", "Marking Guide.pdf")) {
				Path generated = output.resolve(filename);
				assertTrue(Files.isRegularFile(generated), "Missing generated document: " + filename);
				try (PDDocument document = Loader.loadPDF(generated.toFile())) {
					assertTrue(document.getNumberOfPages() > 0);
				}
			}
			String paperOneText = readPdfText(output.resolve("Paper 1.pdf"));
			String guideText = readPdfText(output.resolve("Marking Guide.pdf"));
			assertTrue(paperOneText.contains("QUESTION 5 (4 marks)"));
			assertTrue(guideText.contains("Source: QCAA 2021 — Q22"));
			assertTrue(guideText.contains("Source: QCAA 2022 — Q22"));
			assertTrue(guideText.contains("Source: QCAA 2025 — Q22"));
			assertTrue(guideText.contains("Source: NEAP 2021"));
			assertTrue(guideText.contains("Source: Oxford 2025"));
			assertTrue(guideText.contains("Source: Sylligence 2026"));

			// Generation must not modify any staged source PDF.
			for (var entry : sourceHashes.entrySet()) {
				assertArrayEquals(entry.getValue(), sha256(entry.getKey()), "Source PDF changed: " + entry.getKey());
			}
			database.verifyIntegrity();
		} finally {

			// The reference backup is itself immutable.
			assertArrayEquals(archiveBefore, sha256(archive));
		}
		assertFalse(Files.exists(config.databasePath()));
		System.out.println("Reference examination generated at: " + output);
	}

	private void auditQuestion(PrintableAssessmentWriter writer, Question question) throws IOException {
		boolean multipleChoice = question.getResponseType() == QuestionResponseType.MULTIPLE_CHOICE;
		PrintableAssessmentPlan plan = new PrintableAssessmentPlan(multipleChoice ? List.of(question) : List.of(),
				List.of(), multipleChoice ? List.of() : List.of(question));
		String subject = question.getExam().getSubject().getName();
		try (PDDocument student = new PDDocument(); PDDocument guide = new PDDocument()) {
			if (multipleChoice) {
				writer.appendMultipleChoiceBooklet(student, studentFonts(), subject, 2027, plan);
			} else {
				writer.appendPaperTwo(student, studentFonts(), subject, 2027, 5, 60, plan);
			}
			writer.appendMarkingGuide(guide, guideFonts(), subject, 2027, plan);

			// Exercise PDF serialization without leaving hundreds
			// of generated documents on the filesystem.
			student.save(OutputStream.nullOutputStream());
			guide.save(OutputStream.nullOutputStream());
		}
	}

	private String describeQuestion(Question question) {
		Exam exam = question.getExam();
		return "%s — %s %d — %s — %s — Q%s [ID %d]".formatted(exam.getSubject().getName(), exam.getProvider().getName(),
				exam.getYear(), exam.getName(), question.getBooklet().getName(), question.getQuestionCode(),
				question.getId());
	}

	private PrintableExamFonts guideFonts() {
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE));
	}

	private Map<Path, byte[]> hashSourcePdfs(Path root) throws Exception {
		List<Path> paths;
		try (var stream = Files.walk(root)) {
			paths = stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted()
					.toList();
		}
		Map<Path, byte[]> hashes = new LinkedHashMap<>();
		for (Path path : paths) {
			hashes.put(path, sha256(path));
		}
		return hashes;
	}

	private String readPdfText(Path file) throws IOException {
		try (PDDocument document = Loader.loadPDF(file.toFile())) {
			return new PDFTextStripper().getText(document);
		}
	}

	private Question requireQuestion(SqliteQuestionRepository repository, long id, String provider, int year,
			String code) {
		Question question = repository.findById(id)
				.orElseThrow(() -> new AssertionError("Missing reference Question: " + id));
		assertEquals("Chemistry", question.getExam().getSubject().getName());
		assertEquals(provider, question.getExam().getProvider().getName());
		assertEquals(year, question.getExam().getYear());
		assertEquals(code, question.getQuestionCode());
		assertTrue(PrintableAssessmentWriter.canSelect(question), describeQuestion(question));
		return question;
	}

	private Path requireReferenceArchive() {
		String configuredPath = System.getProperty("exam.reference.backup", "");
		Assumptions.assumeTrue(!configuredPath.isBlank(), "Reference corpus backup not supplied.");
		Path archive = Path.of(configuredPath).toAbsolutePath().normalize();
		assertTrue(Files.isRegularFile(archive), "Missing reference corpus backup: " + archive);
		return archive;
	}

	private byte[] sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
			input.transferTo(OutputStream.nullOutputStream());
		}
		return digest.digest();
	}

	private PrintableExamFonts studentFonts() {
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
				new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC));
	}
}
