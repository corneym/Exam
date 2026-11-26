package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
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
	void generatesBookletsAndMatchingMarkingGuide() throws Exception {
		createPdf(tempDir.resolve("questions.pdf"),
				List.of("Which statement is correct?", "Use the following equilibrium information.",
						"(b) Calculate the equilibrium constant.", "(c) Explain the effect of temperature."));
		createPdf(tempDir.resolve("answers.pdf"),
				List.of("Equilibrium constant = 25", "Increasing temperature favours the endothermic reaction."));
		byte[] originalQuestionPdf = Files.readAllBytes(tempDir.resolve("questions.pdf"));
		byte[] originalAnswerPdf = Files.readAllBytes(tempDir.resolve("answers.pdf"));
		ExamBooklet booklet = createBooklet();
		CurriculumNode classification = createClassification(booklet.getExam().getSubject());
		SourceQuestion source = new SourceQuestion(1, booklet, "12");
		SharedQuestionContext context = new SharedQuestionContext(1, booklet, "Equilibrium information",
				List.of(new SharedQuestionContextRegion(2, 0.05, 0.05, 0.9, 0.25)));
		Question mcq = new Question(1, booklet, "7", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.05, 0.05, 0.9, 0.25)), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		mcq.setAnswer(new Answer(1, "C", List.of()));
		Question firstPart = new Question(2, booklet, "12b", "", 2,
				List.of(new QuestionRegion(booklet, 3, 0.05, 0.05, 0.9, 0.25)), classification, false, source, context,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question secondPart = new Question(3, booklet, "12c", "", 3,
				List.of(new QuestionRegion(booklet, 4, 0.05, 0.05, 0.9, 0.25)), classification, false, source, context,
				QuestionResponseType.WRITTEN_RESPONSE);
		AnswerFile answerFile = new AnswerFile(1, booklet.getExam(), "Marking guide",
				new SourceDocument(2, "answers.pdf"));
		firstPart.setAnswer(new Answer(2, null, List.of(new AnswerRegion(answerFile, 1, 0.05, 0.05, 0.9, 0.25))));
		secondPart.setAnswer(new Answer(3, null, List.of(new AnswerRegion(answerFile, 2, 0.05, 0.05, 0.9, 0.25))));
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		Path outputDirectory = tempDir.resolve("generated");
		writer.generate("Chemistry Mock Examination", List.of(mcq, firstPart, secondPart), outputDirectory);
		Path bookletOne = outputDirectory.resolve("Booklet 1.pdf");
		Path bookletTwo = outputDirectory.resolve("Booklet 2.pdf");
		Path markingGuide = outputDirectory.resolve("Marking Guide.pdf");
		assertTrue(Files.isRegularFile(bookletOne));
		assertTrue(Files.isRegularFile(bookletTwo));
		assertTrue(Files.isRegularFile(markingGuide));
		String mcqText = extractText(bookletOne);
		assertTrue(mcqText.contains("Booklet 1 - Multiple choice"));
		assertTrue(mcqText.contains("Question 1 (1 marks)"));
		assertTrue(mcqText.contains("Total marks: 1"));
		assertTrue(mcqText.contains("Which statement is correct?"));
		String writtenText = extractText(bookletTwo);
		assertTrue(writtenText.contains("Booklet 2 - Written response"));
		assertTrue(writtenText.contains("Question 1 (5 marks)"));
		assertTrue(writtenText.contains("Total marks: 5"));
		assertTrue(writtenText.contains("Use the following equilibrium information."));
		assertEquals(writtenText.indexOf("Use the following equilibrium information."),
				writtenText.lastIndexOf("Use the following equilibrium information."));
		assertTrue(writtenText.contains("Calculate the equilibrium constant."));
		assertTrue(writtenText.contains("Explain the effect of temperature."));
		String guideText = extractText(markingGuide);
		assertTrue(guideText.contains("Section 1 - Multiple choice"));
		assertTrue(guideText.contains("Section 2 - Written response"));
		assertTrue(guideText.contains("Question 1 (1 marks)"));
		assertTrue(guideText.contains("Answer: C"));
		assertTrue(guideText.contains("Question 1(a) (2 marks)"));
		assertTrue(guideText.contains("Question 1(b) (3 marks)"));
		assertTrue(guideText.contains("Equilibrium constant = 25"));
		assertTrue(guideText.contains("Increasing temperature favours the endothermic reaction."));
		assertTrue(guideText.contains("Q12b"));
		assertTrue(guideText.contains("Q12c"));

		// Verify that neither managed source PDF has been changed.
		assertArrayEquals(originalQuestionPdf, Files.readAllBytes(tempDir.resolve("questions.pdf")));
		assertArrayEquals(originalAnswerPdf, Files.readAllBytes(tempDir.resolve("answers.pdf")));

		// Retain the generated examination for visual inspection.
		Path inspectionDirectory = Path.of("target", "pdf-feasibility", "generated-exam").toAbsolutePath();
		Files.createDirectories(inspectionDirectory);
		for (Path file : List.of(bookletOne, bookletTwo, markingGuide)) {
			Files.copy(file, inspectionDirectory.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}
		System.out.println("Generated examination: " + inspectionDirectory);
	}

	@Test
	void rejectsQuestionWithoutCompleteAnswer() throws Exception {
		ExamBooklet booklet = createBooklet();
		Question question = new Question(1, booklet, "7", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.05, 0.05, 0.9, 0.25)),
				createClassification(booklet.getExam().getSubject()), false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		assertFalse(PrintableAssessmentWriter.canSelect(question));
		PrintableAssessmentWriter writer = new PrintableAssessmentWriter(new PdfStore(tempDir));
		assertThrows(IllegalArgumentException.class,
				() -> writer.generate("Mock Examination", List.of(question), tempDir.resolve("generated")));
		assertFalse(Files.exists(tempDir.resolve("generated")));
		question.setAnswer(new Answer(1, "B", List.of()));
		assertTrue(PrintableAssessmentWriter.canSelect(question));
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

	private String extractText(Path path) throws IOException {
		try (PDDocument document = Loader.loadPDF(path.toFile())) {
			return new PDFTextStripper().getText(document);
		}
	}
}
