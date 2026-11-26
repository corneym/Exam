package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.service.audit.QuestionCorpusAudit;

/**
 * Generates printable examination booklets and their corresponding marking
 * guide from existing Question Bank content.
 *
 * <p>
 * Generation is read-only with respect to authoritative Question Bank records
 * and managed source documents.
 */
public final class PrintableAssessmentWriter {

	private final PdfStore pdfStore;

	/**
	 * Creates an examination writer using the existing managed PDF resolver.
	 *
	 * @param pdfStore managed PDF resolver
	 * @throws NullPointerException if the resolver is null
	 */
	public PrintableAssessmentWriter(PdfStore pdfStore) {
		this.pdfStore = Objects.requireNonNull(pdfStore, "pdfStore");
	}

	/**
	 * Determines whether a Question has the required Answer representation for
	 * examination selection.
	 *
	 * @param question candidate Question
	 * @return whether the Question has a complete Answer
	 */
	public static boolean canSelect(Question question) {
		return QuestionCorpusAudit.assess(question).answerComplete();
	}

	/**
	 * Generates the populated examination booklets and one marking guide containing
	 * multiple-choice and written-response sections.
	 *
	 * <p>
	 * Existing output files are not overwritten.
	 *
	 * @param title           assessment title
	 * @param questions       selected Questions in presentation order
	 * @param outputDirectory destination directory
	 * @throws IOException              if generation or file writing fails, or an
	 *                                  output file already exists
	 * @throws IllegalArgumentException if no Questions are selected or a selected
	 *                                  Question has an incomplete Answer
	 */
	public void generate(String title, List<Question> questions, Path outputDirectory) throws IOException {
		if (questions.isEmpty()) {
			throw new IllegalArgumentException("Select at least one Question.");
		}
		for (Question question : questions) {
			if (!canSelect(question)) {
				throw new IllegalArgumentException("Question has no complete Answer: " + question.getQuestionCode());
			}
		}
		List<Question> multipleChoice = questions.stream()
				.filter(question -> question.getResponseType() == QuestionResponseType.MULTIPLE_CHOICE).toList();
		List<Question> writtenResponse = questions.stream()
				.filter(question -> question.getResponseType() == QuestionResponseType.WRITTEN_RESPONSE).toList();
		var numberedMultipleChoice = PrintableQuestionNumbering.number(multipleChoice);
		var numberedWrittenResponse = PrintableQuestionNumbering.number(writtenResponse);
		Files.createDirectories(outputDirectory);
		Path bookletOne = outputDirectory.resolve("Booklet 1.pdf");
		Path bookletTwo = outputDirectory.resolve("Booklet 2.pdf");
		Path markingGuide = outputDirectory.resolve("Marking Guide.pdf");

		// Check destinations before writing any generated documents.
		// Existing files, including managed source PDFs, are protected.
		if (!multipleChoice.isEmpty() && Files.exists(bookletOne)) {
			throw new FileAlreadyExistsException(bookletOne.toString());
		}
		if (!writtenResponse.isEmpty() && Files.exists(bookletTwo)) {
			throw new FileAlreadyExistsException(bookletTwo.toString());
		}
		if (Files.exists(markingGuide)) {
			throw new FileAlreadyExistsException(markingGuide.toString());
		}
		try (PDDocument mcqDocument = new PDDocument();
				PDDocument writtenDocument = new PDDocument();
				PDDocument guideDocument = new PDDocument()) {
			if (!multipleChoice.isEmpty()) {
				writeBooklet(mcqDocument, title, "Booklet 1 - Multiple choice", numberedMultipleChoice);
			}
			if (!writtenResponse.isEmpty()) {
				writeBooklet(writtenDocument, title, "Booklet 2 - Written response", numberedWrittenResponse);
			}
			PrintablePageWriter guideWriter = new PrintablePageWriter(guideDocument);
			guideWriter.appendText(title);
			guideWriter.appendText("Marking Guide");
			writeMarkingSection(guideWriter, "Section 1 - Multiple choice", numberedMultipleChoice);
			writeMarkingSection(guideWriter, "Section 2 - Written response", numberedWrittenResponse);

			// Finish generating all content before saving any document.
			if (!multipleChoice.isEmpty()) {
				saveNewDocument(mcqDocument, bookletOne);
			}
			if (!writtenResponse.isEmpty()) {
				saveNewDocument(writtenDocument, bookletTwo);
			}
			saveNewDocument(guideDocument, markingGuide);
		}
	}

	private void appendPdfRegion(PrintablePageWriter writer, String relativePath, int pageNumber, double x, double y,
			double width, double height, String originalLabel, String replacementLabel) throws IOException {
		Path path = pdfStore.resolve(relativePath);
		try (PDDocument source = Loader.loadPDF(path.toFile())) {
			PDRectangle cropBox = source.getPage(pageNumber - 1).getCropBox();
			float pageWidth = cropBox.getWidth();
			float pageHeight = cropBox.getHeight();

			// Captured regions use normalized top-left coordinates.
			// Convert them to PDF bottom-left coordinates in points.
			PDRectangle rectangle = new PDRectangle((float) (x * pageWidth), (float) ((1.0 - y - height) * pageHeight),
					(float) (width * pageWidth), (float) (height * pageHeight));
			writer.appendRegion(source, pageNumber, rectangle, originalLabel, replacementLabel);
		}
	}

	private void appendQuestionBody(PrintablePageWriter writer, PrintableQuestionNumbering.NumberedQuestion numbered)
			throws IOException {
		Question question = numbered.question();
		String originalLabel = numbered.originalPartLabel();
		String replacementLabel = numbered.replacementPartLabel();
		boolean firstPart = true;
		for (QuestionContentPart part : question.getContentParts()) {
			if (part instanceof PdfQuestionContentPart pdfPart) {
				QuestionRegion region = pdfPart.region();
				appendPdfRegion(writer, region.booklet().getSourceDocument().getRelativePath(), region.pageNumber(),
						region.x(), region.y(), region.width(), region.height(), firstPart ? originalLabel : "",
						firstPart ? replacementLabel : "");
			} else if (part instanceof ImageQuestionContentPart imagePart) {

				// Text-based part-label replacement requires a PDF
				// region. Do not silently retain an incorrect label.
				if (firstPart && !originalLabel.isEmpty()) {
					throw new IOException("Cannot replace part label in image content: " + question.getQuestionCode());
				}
				writer.appendImage(imagePart.pngBytes());
			}
			firstPart = false;
		}
	}

	private void appendSharedContext(PrintablePageWriter writer, SharedQuestionContext context) throws IOException {
		String relativePath = context.getBooklet().getSourceDocument().getRelativePath();
		for (SharedContextContentPart part : context.getContentParts()) {
			if (part instanceof SharedQuestionContextRegion region) {
				appendPdfRegion(writer, relativePath, region.pageNumber(), region.x(), region.y(), region.width(),
						region.height(), "", "");
			} else if (part instanceof ImageSharedContextContentPart imagePart) {
				writer.appendImage(imagePart.pngBytes());
			}
		}
	}

	private void saveNewDocument(PDDocument document, Path destination) throws IOException {

		// CREATE_NEW also prevents accidental source-file overwrite
		// if the destination appears after the initial existence check.
		try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
			document.save(output);
		}
	}

	private void writeBooklet(PDDocument document, String title, String bookletTitle,
			List<PrintableQuestionNumbering.NumberedQuestion> questions) throws IOException {
		PrintablePageWriter writer = new PrintablePageWriter(document);
		int totalMarks = questions.stream().mapToInt(numbered -> numbered.question().getMarks()).sum();
		writer.appendText(title);
		writer.appendText(bookletTitle);
		writer.appendText("Total marks: " + totalMarks);
		int previousNumber = 0;
		Set<Long> includedContexts = new HashSet<>();
		for (var numbered : questions) {
			Question question = numbered.question();
			if (numbered.number() != previousNumber) {
				int questionMarks = questions.stream().filter(item -> item.number() == numbered.number())
						.mapToInt(item -> item.question().getMarks()).sum();
				writer.appendText("Question " + numbered.number() + " (" + questionMarks + " marks)");
				previousNumber = numbered.number();
				includedContexts.clear();
			}

			// Include shared stimulus once within each generated
			// top-level Question, before its dependent content.
			if (question.hasSharedContext()) {
				SharedQuestionContext context = question.getSharedContext();
				if (includedContexts.add(context.getId())) {
					appendSharedContext(writer, context);
				}
			}
			appendQuestionBody(writer, numbered);
		}
	}

	private void writeMarkingSection(PrintablePageWriter writer, String sectionTitle,
			List<PrintableQuestionNumbering.NumberedQuestion> questions) throws IOException {
		writer.appendText(sectionTitle);
		int totalMarks = questions.stream().mapToInt(numbered -> numbered.question().getMarks()).sum();
		writer.appendText("Total marks: " + totalMarks);
		for (var numbered : questions) {
			Question question = numbered.question();
			Answer answer = question.getAnswer();
			writer.appendText("Question " + numbered.label() + " (" + question.getMarks() + " marks)");
			if (question.getResponseType() == QuestionResponseType.MULTIPLE_CHOICE) {
				writer.appendText("Answer: " + answer.getAnswerText().trim().toUpperCase(Locale.ROOT));
			}
			for (AnswerRegion region : answer.getRegions()) {
				appendPdfRegion(writer, region.answerFile().getSourceDocument().getRelativePath(), region.pageNumber(),
						region.x(), region.y(), region.width(), region.height(), "", "");
			}
			Exam exam = question.getExam();
			writer.appendText("Source: " + exam.getProvider().getName() + " " + exam.getYear() + " - "
					+ question.getBooklet().getName() + " - Q" + question.getQuestionCode());
		}
	}
}
