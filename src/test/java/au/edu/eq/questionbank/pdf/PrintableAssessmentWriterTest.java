package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class PrintableAssessmentWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void continuesAnswerGridBeyondTwentyQuestions() throws Exception {
		Path output = tempDir.resolve("extended-grid.pdf");
		try (PDDocument document = new PDDocument()) {
			PrintableMultipleChoiceGrid.appendAnswerGrid(document, createStudentTestFonts(), 23);
			document.save(output.toFile());
		}
		try (PDDocument document = Loader.loadPDF(output.toFile())) {
			assertEquals(2, document.getNumberOfPages());
			String firstPage = extractPageText(document, 1);
			String secondPage = extractPageText(document, 2);
			assertTrue(firstPage.contains("This section has 23 questions " + "and is worth 23 marks."));
			assertTrue(secondPage.contains("Section 1 (continued)"));
			List<String> rowNumbers = new PDFTextStripper().getText(document).lines().map(String::trim)
					.filter(line -> line.matches("\\d+\\.")).toList();
			assertEquals(IntStream.rangeClosed(1, 23).mapToObj(number -> number + ".").toList(), rowNumbers);
			assertFalse(firstPage.contains("Ensure you have filled"));
			assertTrue(secondPage.contains("Ensure you have filled"));
		}
	}

	@Test
	void createsMultipleChoiceBookletAndMatchingAnswerGrid() throws Exception {
		PrintableAssessmentPlan plan = createMultipleChoicePlan();
		byte[] sourceBefore = Files.readAllBytes(tempDir.resolve("questions.pdf"));
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path mcqPath = tempDir.resolve("MCQ.pdf");
		Path paperOnePath = tempDir.resolve("Paper 1.pdf");
		try (PDDocument mcq = new PDDocument(); PDDocument paperOne = new PDDocument()) {
			writer.appendMultipleChoiceBooklet(mcq, createStudentTestFonts(), "Chemistry", 2027, plan);
			writer.appendPaperOneOpening(paperOne, createStudentTestFonts(), "Chemistry", 2027, 5, 60, plan);
			mcq.save(mcqPath.toFile());
			paperOne.save(paperOnePath.toFile());
		}
		try (PDDocument mcq = Loader.loadPDF(mcqPath.toFile())) {
			assertTrue(mcq.getNumberOfPages() > 2);
			String cover = extractPageText(mcq, 1);
			assertTrue(cover.contains("Multiple choice question book"));
			assertTrue(cover.contains("Mock assessment 2027"));
			String firstContentPage = extractPageText(mcq, 2);
			assertTrue(firstContentPage.contains("Section 1"));
			assertTrue(firstContentPage.contains("Respond to these questions"));
			for (int pageNumber = 2; pageNumber <= mcq.getNumberOfPages(); pageNumber++) {
				String text = extractPageText(mcq, pageNumber);
				assertTrue(text.contains("THIS PAGE WILL NOT BE MARKED"));
				if (pageNumber > 2) {
					assertFalse(text.contains("Section 1"));
				}
			}
			String completeText = new PDFTextStripper().getText(mcq);
			List<String> headings = completeText.lines().map(String::trim).filter(line -> line.matches("QUESTION \\d+"))
					.toList();
			assertEquals(IntStream.rangeClosed(1, 10).mapToObj(number -> "QUESTION " + number).toList(), headings);
			for (int number = 1; number <= 10; number++) {
				assertTrue(completeText.contains("MCQ content " + number + "."));
			}
			String stimulus = "Common galvanic cell stimulus.";
			assertTrue(completeText.contains(stimulus));

			// Consecutive MCQs sharing a context reproduce
			// that stimulus only once.
			assertEquals(completeText.indexOf(stimulus), completeText.lastIndexOf(stimulus));
			assertTrue(completeText.indexOf(stimulus) < completeText.indexOf("QUESTION 1"));
		}
		try (PDDocument paperOne = Loader.loadPDF(paperOnePath.toFile())) {
			assertEquals(3, paperOne.getNumberOfPages());
			String cover = extractPageText(paperOne, 1);
			assertTrue(cover.contains("10 multiple choice questions"));
			String hatched = extractPageText(paperOne, 2);
			assertTrue(hatched.contains("DO NOT WRITE ON THIS PAGE"));
			String grid = extractPageText(paperOne, 3);
			assertTrue(grid.contains("This section has 10 questions " + "and is worth 10 marks."));
			assertTrue(grid.contains("Example:"));
			assertTrue(grid.contains("Ensure you have filled an answer " + "bubble for each question."));
			List<String> rowNumbers = grid.lines().map(String::trim).filter(line -> line.matches("\\d+\\.")).toList();
			assertEquals(IntStream.rangeClosed(1, 10).mapToObj(number -> number + ".").toList(), rowNumbers);
		}
		assertArrayEquals(sourceBefore, Files.readAllBytes(tempDir.resolve("questions.pdf")));
	}

	@Test
	void generatesBookletsAndMatchingMarkingGuide() throws Exception {
		PrintableAssessmentPlan plan = createWrittenAssessmentPlan();
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		Path sourceQuestions = tempDir.resolve("questions.pdf");
		Path sourceAnswers = tempDir.resolve("answers.pdf");
		byte[] originalQuestions = Files.readAllBytes(sourceQuestions);
		byte[] originalAnswers = Files.readAllBytes(sourceAnswers);
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path output = tempDir.resolve("generated");
		writer.generate(settings, plan, output,
				(_, guide) -> guide ? createGuideTestFonts() : createStudentTestFonts());
		for (String filename : List.of("MCQ.pdf", "Paper 1.pdf", "Paper 2.pdf", "Marking Guide.pdf")) {
			assertTrue(Files.isRegularFile(output.resolve(filename)));
		}
		String mcq = extractText(output.resolve("MCQ.pdf"));
		String paperOne = extractText(output.resolve("Paper 1.pdf"));
		String paperTwo = extractText(output.resolve("Paper 2.pdf"));
		String guide = extractText(output.resolve("Marking Guide.pdf"));
		assertTrue(mcq.contains("QUESTION 1"));
		assertTrue(mcq.contains("Select the correct species."));
		assertTrue(paperOne.contains("QUESTION 2 (5 marks)"));
		assertTrue(paperOne.contains("Working time — 60 minutes"));
		assertTrue(paperTwo.contains("QUESTION 1 (2 marks)"));
		assertTrue(paperTwo.contains("Working time — 75 minutes"));
		assertTrue(guide.contains("Equilibrium constant = 25"));
		assertTrue(guide.contains("Oxidation state = +3"));
		assertTrue(guide.contains("Short response: Paper 1"));
		assertTrue(guide.contains("Short response: Paper 2"));
		assertArrayEquals(originalQuestions, Files.readAllBytes(sourceQuestions));
		assertArrayEquals(originalAnswers, Files.readAllBytes(sourceAnswers));
		try (PDDocument document = Loader.loadPDF(output.resolve("Paper 1.pdf").toFile())) {
			int numberedPages = document.getNumberOfPages() - 2;
			assertTrue(extractPageText(document, 3).contains("1 of " + numberedPages));
			assertTrue(extractPageText(document, document.getNumberOfPages())
					.contains(numberedPages + " of " + numberedPages));
			verifyAdditionalPages(document);
		}

		// A second generation must not overwrite the original output.
		byte[] originalOutput = Files.readAllBytes(output.resolve("Paper 1.pdf"));
		assertThrows(FileAlreadyExistsException.class, () -> writer.generate(settings, plan, output,
				(_, markingGuide) -> markingGuide ? createGuideTestFonts() : createStudentTestFonts()));
		assertArrayEquals(originalOutput, Files.readAllBytes(output.resolve("Paper 1.pdf")));
	}

	@Test
	void generatesCompleteShortResponsePapers() throws Exception {
		PrintableAssessmentPlan plan = createWrittenAssessmentPlan();
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path paperOnePath = tempDir.resolve("Paper 1.pdf");
		Path paperTwoPath = tempDir.resolve("Paper 2.pdf");
		try (PDDocument paperOne = new PDDocument(); PDDocument paperTwo = new PDDocument()) {
			writer.appendPaperOne(paperOne, createStudentTestFonts(), "Chemistry", 2027, 5, 60, plan);
			writer.appendPaperTwo(paperTwo, createStudentTestFonts(), "Chemistry", 2027, 10, 75, plan);
			paperOne.save(paperOnePath.toFile());
			paperTwo.save(paperTwoPath.toFile());
		}
		try (PDDocument paperOne = Loader.loadPDF(paperOnePath.toFile());
				PDDocument paperTwo = Loader.loadPDF(paperTwoPath.toFile())) {
			String first = new PDFTextStripper().getText(paperOne);
			String second = new PDFTextStripper().getText(paperTwo);
			assertTrue(first.contains("Section 2"));
			assertTrue(first.contains("QUESTION 2 (5 marks)"));
			assertTrue(first.contains("Calculate the equilibrium constant."));
			assertTrue(first.contains("Explain the effect of temperature."));
			assertTrue(second.contains("Section 1"));
			assertTrue(second.contains("QUESTION 1 (2 marks)"));
			assertFalse(second.contains("QUESTION 2 (5 marks)"));
			verifyAdditionalPages(paperOne);
			verifyAdditionalPages(paperTwo);
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void generatesExaminationFromAuthenticQcaaDocuments() throws Exception {
		String directory = System.getProperty("exam.authentic.dir", "");
		Assumptions.assumeTrue(!directory.isBlank(), "Authentic examination directory not supplied.");
		Path sourceDirectory = Path.of(directory);
		String mcqFilename = "snr_chemistry_25_ea_p1_mc_question.pdf";
		String paperOneFilename = "snr_chemistry_25_ea_p1_question_response.pdf";
		String paperTwoFilename = "snr_chemistry_25_ea_p2_question_response.pdf";
		String guideFilename = "snr_chemistry_25_ea_mark_guide.pdf";
		List<String> filenames = List.of(mcqFilename, paperOneFilename, paperTwoFilename, guideFilename);
		Map<String, byte[]> originalBytes = new HashMap<>();
		for (String filename : filenames) {
			Path path = sourceDirectory.resolve(filename);
			assertTrue(Files.isRegularFile(path), "Missing authentic source: " + path);
			originalBytes.put(filename, Files.readAllBytes(path));
		}
		Subject subject = new Subject(1, "Chemistry");
		Exam exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2025, "External");
		ExamBooklet mcqBooklet = new ExamBooklet(1, exam, "Multiple choice", new SourceDocument(1, mcqFilename));
		ExamBooklet paperOneBooklet = new ExamBooklet(2, exam, "Paper 1", new SourceDocument(2, paperOneFilename));
		ExamBooklet paperTwoBooklet = new ExamBooklet(3, exam, "Paper 2", new SourceDocument(3, paperTwoFilename));
		AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", new SourceDocument(4, guideFilename));
		CurriculumNode classification = createClassification(subject);

		// Reverse the original MCQ order to exercise numbering.
		Question mcqTwo = new Question(1, mcqBooklet, "2", "", 1,
				List.of(authenticQuestionRegion(mcqBooklet, 2, 40, 382, 555, 505)), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		mcqTwo.setAnswer(new Answer(1, "C", List.of()));
		Question mcqOne = new Question(2, mcqBooklet, "1", "", 1,
				List.of(authenticQuestionRegion(mcqBooklet, 2, 40, 193, 555, 333)), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		mcqOne.setAnswer(new Answer(2, "A", List.of()));
		SourceQuestion source24 = new SourceQuestion(1, paperOneBooklet, "24");
		SharedQuestionContext context24 = new SharedQuestionContext(1, paperOneBooklet, "Citronellol stimulus",
				List.of(authenticContextRegion(5, 56, 208, 552, 305)));
		Question question24b = new Question(3, paperOneBooklet, "24b", "", 2,
				List.of(authenticQuestionRegion(paperOneBooklet, 5, 68, 407, 552, 600)), classification, false,
				source24, context24, QuestionResponseType.WRITTEN_RESPONSE);
		question24b.setAnswer(new Answer(3, null, List.of(authenticAnswerRegion(answerFile, 7, 85, 130, 786, 352))));
		Question question24a = new Question(4, paperOneBooklet, "24a", "", 2,
				List.of(authenticQuestionRegion(paperOneBooklet, 5, 68, 312, 552, 398)), classification, false,
				source24, context24, QuestionResponseType.WRITTEN_RESPONSE);
		question24a.setAnswer(new Answer(4, null, List.of(authenticAnswerRegion(answerFile, 7, 85, 95, 786, 130))));
		SourceQuestion source1 = new SourceQuestion(2, paperTwoBooklet, "1");
		SharedQuestionContext context1 = new SharedQuestionContext(2, paperTwoBooklet, "Galvanic cell stimulus",
				List.of(authenticContextRegion(4, 42, 90, 540, 315)));
		Question question1b = new Question(5, paperTwoBooklet, "1b", "", 2,
				List.of(authenticQuestionRegion(paperTwoBooklet, 4, 54, 385, 538, 532)), classification, false, source1,
				context1, QuestionResponseType.WRITTEN_RESPONSE);
		question1b.setAnswer(new Answer(5, null, List.of(authenticAnswerRegion(answerFile, 13, 85, 151, 786, 252))));
		PrintableAssessmentPlan plan = new PrintableAssessmentPlan(List.of(mcqTwo, mcqOne),
				List.of(question24b, question24a), List.of(question1b));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 30, 5, 20);
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(sourceDirectory));
		Path output = tempDir.resolve("authentic-generated");
		writer.generate(settings, plan, PrintableExamFonts.windowsFontDirectory(), output);
		String mcq = extractText(output.resolve("MCQ.pdf"));
		String paperOne = extractText(output.resolve("Paper 1.pdf"));
		String paperTwo = extractText(output.resolve("Paper 2.pdf"));
		String guide = extractText(output.resolve("Marking Guide.pdf"));
		assertTrue(mcq.contains("QUESTION 1"));
		assertTrue(mcq.contains("QUESTION 2"));
		assertTrue(paperOne.contains("QUESTION 3 (4 marks)"));
		assertTrue(paperTwo.contains("QUESTION 1 (2 marks)"));
		assertTrue(paperOne.contains("END OF PAPER"));
		assertTrue(paperTwo.contains("END OF PAPER"));
		assertTrue(guide.contains("Multiple choice"));
		assertTrue(guide.contains("Short response: Paper 1"));
		assertTrue(guide.contains("Short response: Paper 2"));
		Path inspection = Path.of("target", "pdf-feasibility", "authentic-examination").toAbsolutePath();
		Files.createDirectories(inspection);
		for (String filename : List.of("MCQ.pdf", "Paper 1.pdf", "Paper 2.pdf", "Marking Guide.pdf")) {
			Files.copy(output.resolve(filename), inspection.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
		}

		// Confirm that the authentic source files remain unchanged.
		for (String filename : filenames) {
			assertArrayEquals(originalBytes.get(filename), Files.readAllBytes(sourceDirectory.resolve(filename)));
		}
		System.out.println("Authentic examination: " + inspection);
	}

	@Test
	void generatesLandscapeMarkingGuideWithMixedProviders() throws Exception {
		PrintableAssessmentPlan plan = createMixedAssessmentPlan();
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path output = tempDir.resolve("Marking Guide.pdf");
		try (PDDocument document = new PDDocument()) {
			writer.appendMarkingGuide(document, createGuideTestFonts(), "Chemistry", 2027, plan);
			document.save(output.toFile());
		}
		try (PDDocument document = Loader.loadPDF(output.toFile())) {
			assertTrue(document.getNumberOfPages() >= 4);

			// Cover and multiple-choice Answer table are portrait.
			for (int index = 0; index < 2; index++) {
				PDRectangle mediaBox = document.getPage(index).getMediaBox();
				assertTrue(mediaBox.getHeight() > mediaBox.getWidth());
			}
			String cover = extractPageText(document, 1);
			assertTrue(cover.contains("Chemistry marking guide"));
			assertTrue(cover.contains("Mock assessment 2027"));
			String multipleChoice = extractPageText(document, 2);
			assertTrue(multipleChoice.contains("Multiple choice"));
			assertTrue(multipleChoice.contains("Response"));
			assertTrue(multipleChoice.matches("(?s).*\\b1\\s+C\\b.*"));
			for (int index = 2; index < document.getNumberOfPages(); index++) {
				PDRectangle mediaBox = document.getPage(index).getMediaBox();
				assertEquals(PDRectangle.A4.getHeight(), mediaBox.getWidth(), 0.01f);
				assertEquals(PDRectangle.A4.getWidth(), mediaBox.getHeight(), 0.01f);
			}
			String text = new PDFTextStripper().getText(document);
			assertTrue(text.contains("Short response: Paper 1"));
			assertTrue(text.contains("Short response: Paper 2"));
			assertTrue(text.contains("Sample response"));
			assertTrue(text.contains("The response:"));
			assertTrue(text.contains("Notes"));
			int first = text.indexOf("Equilibrium constant = 25");
			int nonQcaa = text.indexOf("NEAP worked solution.");
			int second = text.indexOf("The reaction is endothermic.");
			int paperTwo = text.indexOf("Oxidation state = +3");
			assertTrue(first >= 0);
			assertTrue(nonQcaa > first);
			assertTrue(second > nonQcaa);
			assertTrue(paperTwo > second);
			assertTrue(text.contains("Question 3"));
			assertTrue(text.contains("Source: NEAP 2026"));

			// The original QCAA identifiers remain in the
			// underlying PDF data. The new identifiers are visible
			// overlays and must also be present in the document.
			assertTrue(text.contains("Source: QCAA 2026"));
		}
	}

	@Test
	void keepsConsecutiveMultipleChoiceQuestionsWithTheirSharedContext() throws Exception {
		PrintableAssessmentPlan base = createMultipleChoicePlan();
		var original = base.getMultipleChoice();

		// Place two unrelated Questions before the two
		// Questions that share a common stimulus.
		PrintableAssessmentPlan plan = new PrintableAssessmentPlan(List.of(original.get(2).question(),
				original.get(3).question(), original.get(0).question(), original.get(1).question()), List.of(),
				List.of());
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path output = tempDir.resolve("grouped-multiple-choice.pdf");
		try (PDDocument document = new PDDocument()) {
			writer.appendMultipleChoiceBooklet(document, createStudentTestFonts(), "Chemistry", 2027, plan);
			document.save(output.toFile());
		}
		try (PDDocument document = Loader.loadPDF(output.toFile())) {
			assertEquals(3, document.getNumberOfPages());
			String firstContentPage = extractPageText(document, 2);
			String secondContentPage = extractPageText(document, 3);
			assertTrue(firstContentPage.contains("QUESTION 1"));
			assertTrue(firstContentPage.contains("MCQ content 3."));
			assertTrue(firstContentPage.contains("QUESTION 2"));
			assertTrue(firstContentPage.contains("MCQ content 4."));

			// No part of the next group may appear at the
			// bottom of the preceding page.
			assertFalse(firstContentPage.contains("Common galvanic cell stimulus."));
			assertFalse(firstContentPage.contains("QUESTION 3"));
			assertFalse(firstContentPage.contains("QUESTION 4"));

			// The complete stimulus and both dependent
			// Questions must appear on the same page.
			assertTrue(secondContentPage.contains("Common galvanic cell stimulus."));
			assertTrue(secondContentPage.contains("QUESTION 3"));
			assertTrue(secondContentPage.contains("MCQ content 1."));
			assertTrue(secondContentPage.contains("QUESTION 4"));
			assertTrue(secondContentPage.contains("MCQ content 2."));
			assertTrue(secondContentPage.contains("THIS PAGE WILL NOT BE MARKED"));
			assertTrue(secondContentPage.indexOf("Common galvanic cell stimulus.") < secondContentPage
					.indexOf("QUESTION 3"));
		}
	}

	@Test
	void keepsWrittenQuestionHeadingAndSelectedPartsTogether() throws Exception {
		PrintableAssessmentPlan base = createWrittenAssessmentPlan();

		// Place a standalone short-response Question before
		// the two selected parts of a multipart Question.
		PrintableAssessmentPlan plan = new PrintableAssessmentPlan(
				base.getMultipleChoice().stream().map(PrintableQuestionNumbering.NumberedQuestion::question).toList(),
				List.of(base.getPaperTwo().getFirst().question(), base.getPaperOne().get(0).question(),
						base.getPaperOne().get(1).question()),
				List.of());
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path output = tempDir.resolve("grouped-short-response.pdf");
		try (PDDocument document = new PDDocument()) {
			writer.appendPaperOne(document, createStudentTestFonts(), "Chemistry", 2027, 5, 60, plan);
			document.save(output.toFile());
		}
		try (PDDocument document = Loader.loadPDF(output.toFile())) {

			// Page 1: cover.
			// Page 2: hatched page.
			// Page 3: MCQ response grid.
			// Page 4: first short-response Question.
			// Page 5: complete multipart Question.
			String firstResponsePage = extractPageText(document, 4);
			String secondResponsePage = extractPageText(document, 5);
			assertTrue(firstResponsePage.contains("QUESTION 2 (2 marks)"));
			assertTrue(firstResponsePage.contains("Determine the oxidation state."));
			assertFalse(firstResponsePage.contains("QUESTION 3 (5 marks)"));
			assertFalse(firstResponsePage.contains("Calculate the equilibrium constant."));
			assertTrue(secondResponsePage.contains("QUESTION 3 (5 marks)"));
			assertTrue(secondResponsePage.contains("Calculate the equilibrium constant."));
			assertTrue(secondResponsePage.contains("Explain the effect of temperature."));
		}
	}

	@Test
	void rejectsQuestionGroupTooLargeForOnePage() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PrintablePageWriter writer = new PrintablePageWriter(document, createStudentTestFonts());
			IOException failure = assertThrows(IOException.class,
					() -> writer.reserveGroup(10000, "Questions 7–8 and their Shared Context"));
			assertTrue(failure.getMessage().contains("cannot fit together on one examination page"));
			assertEquals(0, document.getNumberOfPages());
		}
	}

	@Test
	void rejectsQuestionWithoutCompleteAnswer() throws Exception {
		ExamBooklet booklet = createBooklet();
		Question question = new Question(1, booklet, "7", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.05, 0.05, 0.9, 0.25)),
				createClassification(booklet.getExam().getSubject()), false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		assertFalse(PrintableAssessmentWriter.canSelect(question));
		assertThrows(IllegalArgumentException.class,
				() -> new PrintableAssessmentPlan(List.of(question), List.of(), List.of()));
		question.setAnswer(new Answer(1, "B", List.of()));
		assertTrue(PrintableAssessmentWriter.canSelect(question));
		PrintableAssessmentPlan plan = new PrintableAssessmentPlan(List.of(question), List.of(), List.of());
		assertEquals(1, plan.getMultipleChoiceCount());
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writesCompleteAssessmentInspectionSamples() throws Exception {
		PrintableAssessmentPlan plan = createMixedAssessmentPlan();
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path generated = tempDir.resolve("complete-examination");
		writer.generate(settings, plan, PrintableExamFonts.windowsFontDirectory(), generated);
		Path inspection = Path.of("target", "pdf-feasibility", "complete-examination").toAbsolutePath();
		Files.createDirectories(inspection);
		for (String filename : List.of("MCQ.pdf", "Paper 1.pdf", "Paper 2.pdf", "Marking Guide.pdf")) {
			Files.copy(generated.resolve(filename), inspection.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
		}
		System.out.println("Complete examination: " + inspection);
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writesMultipleChoiceInspectionSamples() throws Exception {
		PrintableAssessmentPlan plan = createMultipleChoicePlan();
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path directory = Path.of("target", "pdf-feasibility", "mcq-examination").toAbsolutePath();
		Files.createDirectories(directory);
		Path fontDirectory = PrintableExamFonts.windowsFontDirectory();
		try (PDDocument mcq = new PDDocument(); PDDocument paperOne = new PDDocument()) {
			PrintableExamFonts mcqFonts = PrintableExamFonts.loadStudent(mcq, fontDirectory);
			PrintableExamFonts paperOneFonts = PrintableExamFonts.loadStudent(paperOne, fontDirectory);
			writer.appendMultipleChoiceBooklet(mcq, mcqFonts, "Chemistry", 2027, plan);
			writer.appendPaperOneOpening(paperOne, paperOneFonts, "Chemistry", 2027, 5, 60, plan);
			mcq.save(directory.resolve("MCQ.pdf").toFile());
			paperOne.save(directory.resolve("Paper 1.pdf").toFile());
		}
		assertTrue(Files.isRegularFile(directory.resolve("MCQ.pdf")));
		assertTrue(Files.isRegularFile(directory.resolve("Paper 1.pdf")));
		System.out.println("Multiple-choice examination: " + directory);
	}

	private AnswerRegion authenticAnswerRegion(AnswerFile answerFile, int pageNumber, double left, double top,
			double right, double bottom) {

		// Actual page dimensions of the QCAA landscape marking guide.
		double width = 841.92;
		double height = 595.32;
		return new AnswerRegion(answerFile, pageNumber, left / width, top / height, (right - left) / width,
				(bottom - top) / height);
	}

	private SharedQuestionContextRegion authenticContextRegion(int pageNumber, double left, double top, double right,
			double bottom) {
		double width = 595.276;
		double height = 841.890;
		return new SharedQuestionContextRegion(pageNumber, left / width, top / height, (right - left) / width,
				(bottom - top) / height);
	}

	private QuestionRegion authenticQuestionRegion(ExamBooklet booklet, int pageNumber, double left, double top,
			double right, double bottom) {

		// Actual portrait page dimensions in the supplied QCAA PDFs.
		double width = 595.276;
		double height = 841.890;
		return new QuestionRegion(booklet, pageNumber, left / width, top / height, (right - left) / width,
				(bottom - top) / height);
	}

	private ExamBooklet createBooklet() {
		Subject subject = new Subject(1, "Chemistry");
		Exam exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2026, "External");
		return new ExamBooklet(1, exam, "Question booklet", new SourceDocument(1, "questions.pdf"));
	}

	private CurriculumNode createClassification(Subject subject) {
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(2, syllabus, unit, "3.1", "Equilibrium", 1);
		return new Subtopic(3, syllabus, topic, "3.1.1", "Equilibrium", 1);
	}

	private PrintableExamFonts createGuideTestFonts() {
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE));
	}

	private PrintableAssessmentPlan createMixedAssessmentPlan() throws IOException {
		PrintableAssessmentPlan base = createWrittenAssessmentPlan();
		createPdf(tempDir.resolve("neap-answer.pdf"), List.of("NEAP worked solution."));
		Question existing = base.getPaperOne().getFirst().question();
		Exam exam = new Exam(2, existing.getExam().getSubject(), new ExamProvider(2, "NEAP"), 2026, "Trial");
		ExamBooklet booklet = new ExamBooklet(2, exam, "Trial paper", new SourceDocument(3, "questions.pdf"));
		Question nonQcaa = new Question(5, booklet, "9", "", 3,
				List.of(new QuestionRegion(booklet, 1, 0.05, 0.05, 0.9, 0.25)), existing.getClassification(), false,
				null, null, QuestionResponseType.WRITTEN_RESPONSE);
		AnswerFile answerFile = new AnswerFile(2, exam, "Worked solutions", new SourceDocument(4, "neap-answer.pdf"));
		nonQcaa.setAnswer(new Answer(5, null, List.of(new AnswerRegion(answerFile, 1, 0.05, 0.05, 0.9, 0.25))));

		// Deliberately place another provider between two
		// selected QCAA Questions to verify presentation order.
		return new PrintableAssessmentPlan(
				base.getMultipleChoice().stream().map(PrintableQuestionNumbering.NumberedQuestion::question).toList(),
				List.of(base.getPaperOne().get(0).question(), nonQcaa, base.getPaperOne().get(1).question()),
				base.getPaperTwo().stream().map(PrintableQuestionNumbering.NumberedQuestion::question).toList());
	}

	private PrintableAssessmentPlan createMultipleChoicePlan() throws IOException {
		List<String> pageTexts = new ArrayList<>();
		for (int number = 1; number <= 10; number++) {
			pageTexts.add("MCQ content " + number + ".");
		}
		pageTexts.add("Common galvanic cell stimulus.");
		createPdf(tempDir.resolve("questions.pdf"), pageTexts);
		ExamBooklet booklet = createBooklet();
		CurriculumNode classification = createClassification(booklet.getExam().getSubject());
		SharedQuestionContext context = new SharedQuestionContext(1, booklet, "Galvanic cell",
				List.of(new SharedQuestionContextRegion(11, 0.05, 0.05, 0.9, 0.25)));
		List<Question> questions = new ArrayList<>();
		for (int number = 1; number <= 10; number++) {
			Question question = new Question(number, booklet, Integer.toString(40 + number), "", 1,
					List.of(new QuestionRegion(booklet, number, 0.05, 0.05, 0.9, 0.25)), classification, false, null,
					number <= 2 ? context : null, QuestionResponseType.MULTIPLE_CHOICE);
			question.setAnswer(new Answer(number, "C", List.of()));
			questions.add(question);
		}
		return new PrintableAssessmentPlan(questions, List.of(), List.of());
	}

	private void createPdf(Path path, List<String> pageTexts) throws IOException {
		try (PDDocument document = new PDDocument()) {
			for (String text : pageTexts) {
				PDPage page = new PDPage(PDRectangle.A4);
				document.addPage(page);
				try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
					stream.beginText();
					stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					stream.newLineAtOffset(45, 760);
					stream.showText(text);
					stream.endText();
					stream.setLineWidth(0.5f);
					for (int index = 0; index < 4; index++) {
						float y = 730 - index * 20;
						stream.moveTo(45, y);
						stream.lineTo(490, y);
					}
					stream.stroke();
				}
			}
			document.save(path.toFile());
		}
	}

	private PrintableExamFonts createStudentTestFonts() {
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
				new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC));
	}

	private PrintableAssessmentPlan createWrittenAssessmentPlan() throws IOException {
		createPdf(tempDir.resolve("questions.pdf"),
				List.of("Select the correct species.", "b) Calculate the equilibrium constant.",
						"c) Explain the effect of temperature.", "d) Determine the oxidation state."));
		createPdf(tempDir.resolve("answers.pdf"), List.of("12b)    Equilibrium constant = 25",
				"12c)    The reaction is endothermic.", "18d)    Oxidation state = +3"));
		ExamBooklet booklet = createBooklet();
		CurriculumNode classification = createClassification(booklet.getExam().getSubject());
		SourceQuestion source = new SourceQuestion(1, booklet, "12");
		Question mcq = new Question(1, booklet, "7", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.05, 0.05, 0.9, 0.25)), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		mcq.setAnswer(new Answer(1, "C", List.of()));
		AnswerFile answerFile = new AnswerFile(1, booklet.getExam(), "Marking guide",
				new SourceDocument(2, "answers.pdf"));
		Question first = new Question(2, booklet, "12b", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.05, 0.05, 0.9, 0.25)), classification, false, source, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		first.setAnswer(new Answer(2, null, List.of(new AnswerRegion(answerFile, 1, 0.05, 0.05, 0.9, 0.25))));
		Question second = new Question(3, booklet, "12c", "", 3,
				List.of(new QuestionRegion(booklet, 3, 0.05, 0.05, 0.9, 0.25)), classification, false, source, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		second.setAnswer(new Answer(3, null, List.of(new AnswerRegion(answerFile, 2, 0.05, 0.05, 0.9, 0.25))));
		Question third = new Question(4, booklet, "18d", "", 2,
				List.of(new QuestionRegion(booklet, 4, 0.05, 0.05, 0.9, 0.25)), classification, false,
				new SourceQuestion(2, booklet, "18"), null, QuestionResponseType.WRITTEN_RESPONSE);
		third.setAnswer(new Answer(4, null, List.of(new AnswerRegion(answerFile, 3, 0.05, 0.05, 0.9, 0.25))));
		return new PrintableAssessmentPlan(List.of(mcq), List.of(first, second), List.of(third));
	}

	private String extractPageText(PDDocument document, int pageNumber) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setStartPage(pageNumber);
		stripper.setEndPage(pageNumber);
		return stripper.getText(document);
	}

	private String extractText(Path path) throws IOException {
		try (PDDocument document = Loader.loadPDF(path.toFile())) {
			return new PDFTextStripper().getText(document);
		}
	}

	private void verifyAdditionalPages(PDDocument document) throws IOException {
		int lastPage = document.getNumberOfPages();
		assertTrue(extractPageText(document, lastPage - 4).contains("END OF PAPER"));
		PDFRenderer renderer = new PDFRenderer(document);
		for (int index = 0; index < 4; index++) {
			int pageNumber = lastPage - 3 + index;
			String text = extractPageText(document, pageNumber);
			assertTrue(text.contains("ADDITIONAL PAGE FOR STUDENT RESPONSES"));
			assertTrue(text.contains("Write the question number you are responding to."));
			BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, 72);

			// Sample the interior along a vertical line to
			// distinguish ruled and blank response pages.
			int darkPixels = 0;
			for (int y = 130; y <= 720; y++) {
				int rgb = image.getRGB(200, y);
				if ((rgb & 0xff) < 245) {
					darkPixels++;
				}
			}
			if (index < 2) {
				assertTrue(darkPixels > 10);
			} else {
				assertEquals(0, darkPixels);
			}
		}
	}
}
