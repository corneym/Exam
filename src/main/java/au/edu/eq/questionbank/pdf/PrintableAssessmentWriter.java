package au.edu.eq.questionbank.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
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
	 * Appends the marking guide for the numbered examination.
	 *
	 * @param document destination PDF document
	 * @param fonts    Arial marking-guide fonts
	 * @param subject  assessment subject
	 * @param year     mock assessment year
	 * @param plan     numbered assessment selections
	 * @throws IOException if Answer content cannot be reproduced
	 */
	public void appendMarkingGuide(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			PrintableAssessmentPlan plan) throws IOException {
		new PrintableMarkingGuide(pdfStore).append(document, fonts, subject, year, plan);
	}

	/**
	 * Appends the multiple-choice booklet with generated Question numbering.
	 *
	 * <p>
	 * Consecutive Questions sharing a stimulus are kept together. An oversized
	 * individual Question may continue onto another page.
	 *
	 * @param document destination PDF document
	 * @param fonts    student examination fonts
	 * @param subject  examination Subject name
	 * @param year     assessment year
	 * @param plan     ordered examination selections
	 * @throws IOException if content cannot be reproduced
	 */
	public void appendMultipleChoiceBooklet(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			PrintableAssessmentPlan plan) throws IOException {
		if (plan.getMultipleChoiceCount() == 0) {
			return;
		}
		PrintableExamCover.appendMultipleChoiceCover(document, fonts, subject, year);
		PrintablePageWriter writer = new PrintablePageWriter(document, fonts);
		var questions = plan.getMultipleChoice();
		int start = 0;
		while (start < questions.size()) {
			var first = questions.get(start);
			SharedQuestionContext context = first.question().getSharedContext();
			int end = start + 1;
			if (context != null) {

				// Collect consecutive Questions sharing the same stimulus.
				while (end < questions.size()) {
					SharedQuestionContext nextContext = questions.get(end).question().getSharedContext();
					if (nextContext == null || nextContext.getId() != context.getId()) {
						break;
					}
					end++;
				}
			}
			float requiredHeight = context == null ? 0 : measureSharedContext(writer, context);
			for (int index = start; index < end; index++) {
				requiredHeight += writer.measureText();
				requiredHeight += measureQuestionBody(writer, questions.get(index).question());
			}
			String description = end - start == 1 ? "Question " + first.number()
					: "Questions " + first.number() + "–" + questions.get(end - 1).number()
							+ " and their Shared Context";
			boolean keepTogether = writer.canFitGroup(requiredHeight);
			if (keepTogether || end - start > 1) {

				// Shared stimuli for multiple MCQs must not be
				// separated from their dependent Questions.
				writer.reserveGroup(requiredHeight, description);
			}
			if (context != null) {
				appendSharedContext(writer, context);
			}
			if (!keepTogether) {
				writer.reserveQuestionStart(measureFirstQuestionContent(writer, first.question()), description);
			}
			for (int index = start; index < end; index++) {
				var numbered = questions.get(index);
				writer.appendText("QUESTION " + numbered.number());
				appendQuestionBody(writer, numbered);
			}
			start = end;
		}
	}

	/**
	 * Appends the complete Paper 1 question and response book.
	 *
	 * @param document       destination PDF document
	 * @param fonts          student examination fonts
	 * @param subject        assessment subject
	 * @param year           mock assessment year
	 * @param perusalMinutes perusal time in minutes
	 * @param workingMinutes working time in minutes
	 * @param plan           numbered assessment selections
	 * @throws IOException if PDF processing fails
	 */
	public void appendPaperOne(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			int perusalMinutes, int workingMinutes, PrintableAssessmentPlan plan) throws IOException {
		appendPaperOneOpening(document, fonts, subject, year, perusalMinutes, workingMinutes, plan);
		appendShortResponse(document, fonts, 1, plan.getPaperOneQuestionCount(), plan.getPaperOneShortResponseMarks(),
				plan.getPaperOne());
	}

	/**
	 * Appends the Paper 1 cover, hatched page and multiple-choice answer grid.
	 *
	 * <p>
	 * Question counts and mark totals are taken from the same assessment plan used
	 * to generate the question booklet.
	 *
	 * @param document       destination Paper 1 PDF document
	 * @param fonts          student examination fonts
	 * @param subject        assessment subject
	 * @param year           mock assessment year
	 * @param perusalMinutes permitted perusal time
	 * @param workingMinutes permitted working time
	 * @param plan           numbered assessment selections
	 * @throws IOException if page content cannot be written
	 */
	public void appendPaperOneOpening(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			int perusalMinutes, int workingMinutes, PrintableAssessmentPlan plan) throws IOException {
		PrintableExamCover.appendResponseCover(document, fonts, subject, year, 1, perusalMinutes, workingMinutes,
				plan.getMultipleChoiceCount(), plan.getPaperOneQuestionCount(), plan.getPaperOneShortResponseMarks());
		PrintableExamCover.appendHatchedPage(document, fonts);
		PrintableMultipleChoiceGrid.appendAnswerGrid(document, fonts, plan.getMultipleChoiceCount());
	}

	/**
	 * Appends the complete Paper 2 question and response book.
	 *
	 * @param document       destination PDF document
	 * @param fonts          student examination fonts
	 * @param subject        assessment subject
	 * @param year           mock assessment year
	 * @param perusalMinutes perusal time in minutes
	 * @param workingMinutes working time in minutes
	 * @param plan           numbered assessment selections
	 * @throws IOException if PDF processing fails
	 */
	public void appendPaperTwo(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			int perusalMinutes, int workingMinutes, PrintableAssessmentPlan plan) throws IOException {
		PrintableExamCover.appendResponseCover(document, fonts, subject, year, 2, perusalMinutes, workingMinutes, 0,
				plan.getPaperTwoQuestionCount(), plan.getPaperTwoTotalMarks());
		PrintableExamCover.appendHatchedPage(document, fonts);
		appendShortResponse(document, fonts, 2, plan.getPaperTwoQuestionCount(), plan.getPaperTwoTotalMarks(),
				plan.getPaperTwo());
	}

	/**
	 * Generates the examination documents using installed Times New Roman and Arial
	 * fonts.
	 *
	 * <p>
	 * Only populated student booklets are generated. The marking guide is always
	 * generated. Existing output files are not overwritten.
	 *
	 * @param settings        assessment metadata
	 * @param plan            numbered assessment selections
	 * @param fontDirectory   directory containing installed font files
	 * @param outputDirectory destination directory
	 * @throws IOException if fonts, source PDFs or output files cannot be processed
	 */
	public void generate(PrintableExamSettings settings, PrintableAssessmentPlan plan, Path fontDirectory,
			Path outputDirectory) throws IOException {
		generate(settings, plan, outputDirectory,
				(document, markingGuide) -> markingGuide ? PrintableExamFonts.loadMarkingGuide(document, fontDirectory)
						: PrintableExamFonts.loadStudent(document, fontDirectory));
	}

	void generate(PrintableExamSettings settings, PrintableAssessmentPlan plan, Path outputDirectory,
			FontLoader fontLoader) throws IOException {
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(outputDirectory, "outputDirectory");
		Objects.requireNonNull(fontLoader, "fontLoader");
		boolean hasMcq = plan.getMultipleChoiceCount() > 0;
		boolean hasPaperOne = hasMcq || !plan.getPaperOne().isEmpty();
		boolean hasPaperTwo = !plan.getPaperTwo().isEmpty();
		Path mcqPath = outputDirectory.resolve("MCQ.pdf");
		Path paperOnePath = outputDirectory.resolve("Paper 1.pdf");
		Path paperTwoPath = outputDirectory.resolve("Paper 2.pdf");
		Path guidePath = outputDirectory.resolve("Marking Guide.pdf");

		// Protect all recognised examination outputs, including
		// omitted booklets. A previous Paper 2 must not be mistaken
		// for part of a newly generated Paper 1-only examination.
		for (Path destination : List.of(mcqPath, paperOnePath, paperTwoPath, guidePath)) {
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw new FileAlreadyExistsException(destination.toString(), null,
						"Select a directory without existing examination PDFs.");
			}
		}
		try (PDDocument mcq = new PDDocument();
				PDDocument paperOne = new PDDocument();
				PDDocument paperTwo = new PDDocument();
				PDDocument guide = new PDDocument()) {
			if (hasMcq) {
				PrintableExamFonts fonts = fontLoader.load(mcq, false);
				appendMultipleChoiceBooklet(mcq, fonts, settings.subject(), settings.year(), plan);
				appendPageNumbers(mcq, fonts, 1, false);
				appendSourceCredit(mcq, fonts, plan.getMultipleChoice());
			}
			if (hasPaperOne) {
				PrintableExamFonts fonts = fontLoader.load(paperOne, false);
				appendPaperOne(paperOne, fonts, settings.subject(), settings.year(), settings.paperOnePerusalMinutes(),
						settings.paperOneWorkingMinutes(), plan);
				appendPageNumbers(paperOne, fonts, 2, false);
				List<PrintableQuestionNumbering.NumberedQuestion> questions = new ArrayList<>(plan.getMultipleChoice());
				questions.addAll(plan.getPaperOne());
				appendSourceCredit(paperOne, fonts, questions);
			}
			if (hasPaperTwo) {
				PrintableExamFonts fonts = fontLoader.load(paperTwo, false);
				appendPaperTwo(paperTwo, fonts, settings.subject(), settings.year(), settings.paperTwoPerusalMinutes(),
						settings.paperTwoWorkingMinutes(), plan);
				appendPageNumbers(paperTwo, fonts, 2, false);
				appendSourceCredit(paperTwo, fonts, plan.getPaperTwo());
			}

			// All selected Answers remain in one marking guide,
			// regardless of the number of student booklets.
			PrintableExamFonts guideFonts = fontLoader.load(guide, true);
			appendMarkingGuide(guide, guideFonts, settings.subject(), settings.year(), plan);
			appendPageNumbers(guide, guideFonts, 1, true);
			List<PrintableQuestionNumbering.NumberedQuestion> allQuestions = new ArrayList<>(plan.getMultipleChoice());
			allQuestions.addAll(plan.getPaperOne());
			allQuestions.addAll(plan.getPaperTwo());
			appendSourceCredit(guide, guideFonts, allQuestions);

			// Finish constructing every document before
			// publishing the examination files.
			Files.createDirectories(outputDirectory);
			if (hasMcq) {
				saveNewDocument(mcq, mcqPath);
			}
			if (hasPaperOne) {
				saveNewDocument(paperOne, paperOnePath);
			}
			if (hasPaperTwo) {
				saveNewDocument(paperTwo, paperTwoPath);
			}
			saveNewDocument(guide, guidePath);
		}
	}

	private void appendPageNumbers(PDDocument document, PrintableExamFonts fonts, int unnumberedPages,
			boolean markingGuide) throws IOException {
		int total = document.getNumberOfPages() - unnumberedPages;
		for (int index = unnumberedPages; index < document.getNumberOfPages(); index++) {
			PDPage page = document.getPage(index);
			int number = index - unnumberedPages + 1;
			String label = (markingGuide ? "Page " : "") + number + " of " + total;
			float size = 10;
			float width = fonts.regular().getStringWidth(label) * size / 1000;
			try (PDPageContentStream stream = new PDPageContentStream(document, page,
					PDPageContentStream.AppendMode.APPEND, true, true)) {
				PrintableExamPage.writeText(stream, page, fonts.regular(), size,
						(page.getMediaBox().getWidth() - width) / 2, page.getMediaBox().getHeight() - 25, label);
			}
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
		boolean labelPending = !originalLabel.isEmpty();
		Exam exam = question.getExam();
		boolean allowBareLabel = "Oxford".equalsIgnoreCase(exam.getProvider().getName());
		for (QuestionContentPart part : question.getContentParts()) {
			if (part instanceof PdfQuestionContentPart pdfPart) {
				QuestionRegion region = pdfPart.region();
				Path path = pdfStore.resolve(region.booklet().getSourceDocument().getRelativePath());
				try (PDDocument source = Loader.loadPDF(path.toFile())) {
					PDRectangle cropBox = source.getPage(region.pageNumber() - 1).getCropBox();
					float pageWidth = cropBox.getWidth();
					float pageHeight = cropBox.getHeight();
					PDRectangle rectangle = new PDRectangle((float) (region.x() * pageWidth),
							(float) ((1.0 - region.y() - region.height()) * pageHeight),
							(float) (region.width() * pageWidth), (float) (region.height() * pageHeight));
					String matchedLabel = "";
					if (labelPending) {

						// Earlier captured regions may contain
						// stimulus material rather than the part label.
						matchedLabel = PrintablePdfRegionWriter.findQuestionPartLabel(source, region.pageNumber(),
								rectangle, originalLabel, allowBareLabel);
					}
					writer.appendRegion(source, region.pageNumber(), rectangle, matchedLabel,
							matchedLabel.isEmpty() ? "" : replacementLabel);
					if (!matchedLabel.isEmpty()) {
						labelPending = false;
					}
				}
			} else if (part instanceof ImageQuestionContentPart imagePart) {
				writer.appendImage(imagePart.pngBytes());
			}
		}
		if (labelPending) {

			// Do not silently accept a different original part
			// identifier. It may indicate an incorrect capture.
			throw new IOException("%s %d — %s — %s — Q%s [ID %d]: ".formatted(exam.getProvider().getName(),
					exam.getYear(), exam.getName(), question.getBooklet().getName(), question.getQuestionCode(),
					question.getId()) + "Cannot locate original part identifier " + originalLabel
					+ " in any captured Question region.");
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

	private void appendShortResponse(PDDocument document, PrintableExamFonts fonts, int paper, int questionCount,
			int marks, List<PrintableQuestionNumbering.NumberedQuestion> questions) throws IOException {
		PrintablePageWriter writer = new PrintablePageWriter(document, fonts, paper, questionCount, marks);
		int start = 0;
		while (start < questions.size()) {
			int number = questions.get(start).number();
			int end = start + 1;

			// Collect all selected parts belonging to this
			// generated top-level Question number.
			while (end < questions.size() && questions.get(end).number() == number) {
				end++;
			}
			int questionMarks = 0;
			float requiredHeight = writer.measureText();
			Set<Long> includedContexts = new HashSet<>();
			for (int index = start; index < end; index++) {
				Question question = questions.get(index).question();
				questionMarks += question.getMarks();
				if (question.hasSharedContext()) {
					SharedQuestionContext context = question.getSharedContext();
					if (includedContexts.add(context.getId())) {
						requiredHeight += measureSharedContext(writer, context);
					}
				}
				requiredHeight += measureQuestionBody(writer, question);
			}
			String description = "Paper " + paper + ", Question " + number;
			if (writer.canFitGroup(requiredHeight)) {
				writer.reserveGroup(requiredHeight, description);
			} else {

				// An oversized Question may span pages, but
				// its heading must accompany its initial content.
				Question first = questions.get(start).question();
				float firstContentHeight = first.hasSharedContext()
						? measureFirstSharedContextContent(writer, first.getSharedContext())
						: measureFirstQuestionContent(writer, first);
				writer.reserveQuestionStart(firstContentHeight, description);
			}
			writer.appendText(
					"QUESTION " + number + " (" + questionMarks + (questionMarks == 1 ? " mark)" : " marks)"));
			includedContexts.clear();
			for (int index = start; index < end; index++) {
				var numbered = questions.get(index);
				Question question = numbered.question();
				if (question.hasSharedContext()) {
					SharedQuestionContext context = question.getSharedContext();
					if (includedContexts.add(context.getId())) {
						appendSharedContext(writer, context);
					}
				}
				appendQuestionBody(writer, numbered);
			}
			start = end;
		}
		writer.appendEndOfPaper();
		PrintableExamPage.appendAdditionalResponsePages(document, fonts);
	}

	private void appendSourceCredit(PDDocument document, PrintableExamFonts fonts,
			List<PrintableQuestionNumbering.NumberedQuestion> questions) throws IOException {
		List<String> sources = questions.stream().map(item -> item.question().getExam())
				.map(exam -> exam.getProvider().getName() + " " + exam.getYear()).distinct().sorted().toList();
		document.getDocumentInformation().setCustomMetadataValue("Source examinations", String.join("; ", sources));
		List<String> qcaaYears = questions.stream().map(item -> item.question().getExam())
				.filter(exam -> "QCAA".equalsIgnoreCase(exam.getProvider().getName()))
				.map(exam -> Integer.toString(exam.getYear())).distinct().sorted().toList();
		if (qcaaYears.isEmpty()) {
			return;
		}
		PDPage cover = document.getPage(0);
		try (PDPageContentStream stream = new PDPageContentStream(document, cover,
				PDPageContentStream.AppendMode.APPEND, true, true)) {

			// Attribute adapted source material without reproducing
			// the original examination provider's logo.
			PrintableExamPage.writeText(stream, cover, fonts.regular(), 8, 42.5f, 785,
					"Adapted material: © State of Queensland (QCAA) " + String.join(", ", qcaaYears));
			PrintableExamPage.writeText(stream, cover, fonts.regular(), 8, 42.5f, 797,
					"CC BY 4.0 — exceptions apply: " + "www.qcaa.qld.edu.au/copyright");
		}
	}

	private float measureFirstQuestionContent(PrintablePageWriter writer, Question question) throws IOException {
		for (QuestionContentPart part : question.getContentParts()) {
			if (part instanceof PdfQuestionContentPart pdfPart) {
				QuestionRegion region = pdfPart.region();
				return measurePdfRegion(writer, region.booklet().getSourceDocument().getRelativePath(),
						region.pageNumber(), region.width(), region.height());
			}
			if (part instanceof ImageQuestionContentPart imagePart) {
				return measurePngContent(writer, imagePart.pngBytes());
			}
		}
		throw new IOException("Missing content for Question " + question.getQuestionCode());
	}

	private float measureFirstSharedContextContent(PrintablePageWriter writer, SharedQuestionContext context)
			throws IOException {
		String relativePath = context.getBooklet().getSourceDocument().getRelativePath();
		for (SharedContextContentPart part : context.getContentParts()) {
			if (part instanceof SharedQuestionContextRegion region) {
				return measurePdfRegion(writer, relativePath, region.pageNumber(), region.width(), region.height());
			}
			if (part instanceof ImageSharedContextContentPart imagePart) {
				return measurePngContent(writer, imagePart.pngBytes());
			}
		}
		throw new IOException("Missing Shared Context content: " + context.getId());
	}

	private float measurePdfRegion(PrintablePageWriter writer, String relativePath, int pageNumber, double width,
			double height) throws IOException {
		Path path = pdfStore.resolve(relativePath);
		try (PDDocument source = Loader.loadPDF(path.toFile())) {
			PDRectangle cropBox = source.getPage(pageNumber - 1).getCropBox();

			// Use the same physical dimensions and scaling
			// as the actual captured-region writing operation.
			return writer.measureContent((float) (width * cropBox.getWidth()), (float) (height * cropBox.getHeight()));
		}
	}

	private float measurePngContent(PrintablePageWriter writer, byte[] pngBytes) throws IOException {
		try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
			BufferedImage image = ImageIO.read(input);
			if (image == null) {
				throw new IOException("Cannot measure captured Question image.");
			}
			return writer.measureContent(image.getWidth(), image.getHeight());
		}
	}

	private float measureQuestionBody(PrintablePageWriter writer, Question question) throws IOException {
		float height = 0;
		for (QuestionContentPart part : question.getContentParts()) {
			if (part instanceof PdfQuestionContentPart pdfPart) {
				QuestionRegion region = pdfPart.region();
				height += measurePdfRegion(writer, region.booklet().getSourceDocument().getRelativePath(),
						region.pageNumber(), region.width(), region.height());
			} else if (part instanceof ImageQuestionContentPart imagePart) {
				height += measurePngContent(writer, imagePart.pngBytes());
			}
		}
		return height;
	}

	private float measureSharedContext(PrintablePageWriter writer, SharedQuestionContext context) throws IOException {
		float height = 0;
		String relativePath = context.getBooklet().getSourceDocument().getRelativePath();
		for (SharedContextContentPart part : context.getContentParts()) {
			if (part instanceof SharedQuestionContextRegion region) {
				height += measurePdfRegion(writer, relativePath, region.pageNumber(), region.width(), region.height());
			} else if (part instanceof ImageSharedContextContentPart imagePart) {
				height += measurePngContent(writer, imagePart.pngBytes());
			}
		}
		return height;
	}

	private void saveNewDocument(PDDocument document, Path destination) throws IOException {

		// CREATE_NEW also prevents accidental source-file overwrite
		// if the destination appears after the initial existence check.
		try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
			document.save(output);
		}
	}

	@FunctionalInterface
	interface FontLoader {

		PrintableExamFonts load(PDDocument document, boolean markingGuide) throws IOException;
	}
}
