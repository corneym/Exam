package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.Matrix;

import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;

/**
 * Generates the mock examination marking guide from the same numbered
 * assessment plan used by the student booklets.
 *
 * <p>
 * Multiple-choice responses use portrait pages. Short-response Answers use
 * landscape pages and retain their captured content.
 */
public final class PrintableMarkingGuide {

	private static final float GAP = 8;
	private static final float GUIDE_MARGIN = 54;
	private static final float HEADER_HEIGHT = 24;
	private static final float MCQ_ROW_HEIGHT = 23;
	private static final int MCQ_ROWS_PER_PAGE = 26;
	private final PdfStore pdfStore;

	/**
	 * Creates a marking-guide writer.
	 *
	 * @param pdfStore managed source PDF resolver
	 * @throws NullPointerException if the resolver is null
	 */
	public PrintableMarkingGuide(PdfStore pdfStore) {
		this.pdfStore = Objects.requireNonNull(pdfStore, "pdfStore");
	}

	private static void drawHeader(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts, float left,
			float top, float[] widths, List<String> labels) throws IOException {
		float height = page.getMediaBox().getHeight();
		stream.saveGraphicsState();
		float x = left;
		for (int column = 0; column < widths.length; column++) {
			float width = widths[column];
			stream.setNonStrokingColor(0.50f);
			stream.addRect(x, height - top - HEADER_HEIGHT, width, HEADER_HEIGHT);
			stream.fill();
			stream.setNonStrokingColor(1.0f);
			stream.beginText();
			stream.setFont(fonts.bold(), 10);
			stream.newLineAtOffset(x + 5, height - top - 15);
			stream.showText(labels.get(column));
			stream.endText();
			x += width;
		}

		// Match the red rule beneath the QCAA table header.
		stream.setStrokingColor(0.835f, 0.169f, 0.118f);
		stream.setLineWidth(1);
		stream.moveTo(left, height - top - HEADER_HEIGHT);
		stream.lineTo(x, height - top - HEADER_HEIGHT);
		stream.stroke();
		stream.restoreGraphicsState();
	}

	private static void drawOutline(PDPageContentStream stream, PDPage page, float left, float top, float width,
			float height) throws IOException {
		stream.saveGraphicsState();
		stream.setStrokingColor(0.58f);
		stream.setLineWidth(0.5f);
		stream.addRect(left, page.getMediaBox().getHeight() - top - height, width, height);
		stream.stroke();
		stream.restoreGraphicsState();
	}

	private static void drawVerticalLine(PDPageContentStream stream, PDPage page, float x, float top, float bottom)
			throws IOException {
		float height = page.getMediaBox().getHeight();
		stream.saveGraphicsState();
		stream.setStrokingColor(0.58f);
		stream.setLineWidth(0.5f);
		stream.moveTo(x, height - top);
		stream.lineTo(x, height - bottom);
		stream.stroke();
		stream.restoreGraphicsState();
	}

	private static void drawWrittenHeader(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts, float top,
			float tableWidth) throws IOException {

		// Use the proportions of the representative QCAA
		// landscape table. Outer widths remain consistent.
		float[] widths = { tableWidth * 0.043f, tableWidth * 0.375f, tableWidth * 0.332f, tableWidth * 0.250f };
		drawHeader(stream, page, fonts, GUIDE_MARGIN, top, widths,
				List.of("Q", "Sample response", "The response:", "Notes"));
	}

	private static String printedLabel(PrintableQuestionNumbering.NumberedQuestion numbered) {
		return numbered.number() + numbered.replacementPartLabel().replace("(", "");
	}

	private static PDRectangle sourceRectangle(PDDocument source, AnswerRegion region) {
		PDRectangle cropBox = source.getPage(region.pageNumber() - 1).getCropBox();
		float width = cropBox.getWidth();
		float height = cropBox.getHeight();

		// Convert normalized top-left capture coordinates
		// into bottom-left PDF coordinates.
		return new PDRectangle((float) (region.x() * width), (float) ((1 - region.y() - region.height()) * height),
				(float) (region.width() * width), (float) (region.height() * height));
	}

	private static void writeCentredText(PDPageContentStream stream, PDPage page, PDFont font, float size, float centre,
			float baseline, String text) throws IOException {
		float width = font.getStringWidth(text) * size / 1000;
		PrintableExamPage.writeText(stream, page, font, size, centre - width / 2, baseline, text);
	}

	/**
	 * Appends a complete marking guide.
	 *
	 * @param document destination PDF document
	 * @param fonts    Arial marking-guide fonts
	 * @param subject  assessment subject
	 * @param year     mock assessment year
	 * @param plan     numbered assessment selections
	 * @throws IOException if captured Answers cannot be reproduced
	 */
	public void append(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			PrintableAssessmentPlan plan) throws IOException {
		PrintableExamCover.appendMarkingGuideCover(document, fonts, subject, year);
		appendMultipleChoice(document, fonts, plan.getMultipleChoice());
		appendWrittenSection(document, fonts, "Short response: Paper 1", plan.getPaperOne());
		appendWrittenSection(document, fonts, "Short response: Paper 2", plan.getPaperTwo());
	}

	private void appendAnswerRegion(PDDocument document, PDPage page, PDPageContentStream stream, PDDocument source,
			PrintableExamFonts fonts, int pageNumber, PDRectangle region,
			PrintableQuestionNumbering.NumberedQuestion numbered, boolean qcaa, boolean firstRegion, float top,
			float scale) throws IOException {
		float bottom = page.getMediaBox().getHeight() - top - region.getHeight() * scale;
		stream.saveGraphicsState();
		stream.transform(new Matrix(scale, 0, 0, scale, GUIDE_MARGIN, bottom));
		if (qcaa) {
			String code = numbered.question().getQuestionCode().trim().replaceFirst("^[Qq]", "");
			List<String> alternatives;
			if (SourceQuestionCodeParser.derive(code) != null) {
				String sourceCode = code.substring(0, code.length() - 1);
				String part = code.substring(code.length() - 1);
				alternatives = List.of(code + ")", sourceCode + "(" + part + ")", code);
			} else {
				alternatives = List.of(code);
			}

			// The original identifier must be in the narrow
			// Question column, not in the marking criteria.
			PDRectangle identifierColumn = new PDRectangle(region.getLowerLeftX(), region.getLowerLeftY(),
					region.getWidth() * 0.09f, region.getHeight());
			PrintablePdfRegionWriter.appendRegionWithLabel(source, pageNumber, document, stream, region,
					identifierColumn, 0, 0, alternatives, printedLabel(numbered), fonts.regular(), firstRegion);
		} else {

			// Other providers retain their original Answer
			// layout. A generated identifier precedes the box.
			PrintablePdfRegionWriter.appendRegion(source, pageNumber, document, stream, region, 0, 0, "", "");
		}
		stream.restoreGraphicsState();
	}

	private PDPage appendLandscapePage(PDDocument document, PrintableExamFonts fonts, String title) throws IOException {
		PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
		document.addPage(page);
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			PrintableExamPage.writeText(stream, page, fonts.bold(), 14, GUIDE_MARGIN, 62, title);
		}
		return page;
	}

	private void appendMultipleChoice(PDDocument document, PrintableExamFonts fonts,
			List<PrintableQuestionNumbering.NumberedQuestion> questions) throws IOException {
		for (int first = 0; first < questions.size(); first += MCQ_ROWS_PER_PAGE) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			int end = Math.min(questions.size(), first + MCQ_ROWS_PER_PAGE);
			try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
				PrintableExamPage.writeText(stream, page, fonts.bold(), 18, GUIDE_MARGIN, 66, "Marking guide");
				PrintableExamPage.writeText(stream, page, fonts.bold(), 14, GUIDE_MARGIN, 91, "Multiple choice");
				float top = 102;
				float left = GUIDE_MARGIN;
				float columnWidth = 100;
				drawHeader(stream, page, fonts, left, top, new float[] { columnWidth, columnWidth },
						List.of("Question", "Response"));
				top += HEADER_HEIGHT;
				for (int index = first; index < end; index++) {
					var numbered = questions.get(index);
					String response = numbered.question().getAnswer().getAnswerText().trim().toUpperCase(Locale.ROOT);
					drawOutline(stream, page, left, top, columnWidth * 2, MCQ_ROW_HEIGHT);
					drawVerticalLine(stream, page, left + columnWidth, top, top + MCQ_ROW_HEIGHT);
					writeCentredText(stream, page, fonts.regular(), 11, left + columnWidth / 2, top + 15,
							Integer.toString(numbered.number()));
					writeCentredText(stream, page, fonts.regular(), 11, left + columnWidth * 1.5f, top + 15, response);
					top += MCQ_ROW_HEIGHT;
				}
			}
		}
	}

	private void appendWrittenSection(PDDocument document, PrintableExamFonts fonts, String sectionTitle,
			List<PrintableQuestionNumbering.NumberedQuestion> questions) throws IOException {
		PDPage page = null;
		float top = 0;
		boolean tableOpen = false;
		float pageWidth = PDRectangle.A4.getHeight();
		float pageHeight = PDRectangle.A4.getWidth();
		float tableWidth = pageWidth - 2 * GUIDE_MARGIN;
		float bottomLimit = pageHeight - GUIDE_MARGIN;
		for (var numbered : questions) {
			Question question = numbered.question();
			boolean qcaa = "QCAA".equalsIgnoreCase(question.getExam().getProvider().getName());
			boolean firstRegion = true;
			for (AnswerRegion answerRegion : question.getAnswer().getRegions()) {
				Path path = pdfStore.resolve(answerRegion.answerFile().getSourceDocument().getRelativePath());
				try (PDDocument source = Loader.loadPDF(path.toFile())) {
					PDRectangle region = sourceRectangle(source, answerRegion);
					float scale = qcaa ? tableWidth / region.getWidth()
							: Math.min(1.0f, tableWidth / region.getWidth());
					float maximumHeight = bottomLimit - 82 - HEADER_HEIGHT - 24;
					if (!qcaa) {

						// Trial baseline for non-QCAA material:
						// preserve original size unless reducing
						// it is necessary to fit the page.
						scale = Math.min(scale, maximumHeight / region.getHeight());
					}
					float height = region.getHeight() * scale;
					float width = region.getWidth() * scale;
					if (height > maximumHeight) {
						throw new IOException("QCAA Answer region cannot fit at " + "the required table width: "
								+ question.getQuestionCode());
					}
					float headingHeight = qcaa ? (tableOpen ? 0 : HEADER_HEIGHT) : 22;

					// Leave room for the source attribution line.
					float requiredHeight = headingHeight + height + 24;
					if (page == null || top + requiredHeight > bottomLimit) {
						page = appendLandscapePage(document, fonts, sectionTitle);
						top = 82;
						tableOpen = false;
					}
					try (PDPageContentStream stream = new PDPageContentStream(document, page,
							PDPageContentStream.AppendMode.APPEND, true, true)) {
						if (qcaa && !tableOpen) {
							drawWrittenHeader(stream, page, fonts, top, tableWidth);
							top += HEADER_HEIGHT;
							tableOpen = true;
						} else if (!qcaa) {
							String heading = "Question " + printedLabel(numbered) + (firstRegion ? "" : " (continued)");
							PrintableExamPage.writeText(stream, page, fonts.bold(), 12, GUIDE_MARGIN, top + 13,
									heading);
							top += 22;
							tableOpen = false;
						}
						appendAnswerRegion(document, page, stream, source, fonts, answerRegion.pageNumber(), region,
								numbered, qcaa, firstRegion, top, scale);
						drawOutline(stream, page, GUIDE_MARGIN, top, width, height);
						top += height;
						if (firstRegion) {
							Exam exam = question.getExam();
							PrintableExamPage.writeText(stream, page, fonts.regular(), 8, GUIDE_MARGIN, top + 11,
									"Source: " + exam.getProvider().getName() + " " + exam.getYear() + " — Q"
											+ question.getQuestionCode());
						}
						top += 16 + GAP;
					}
				}
				firstRegion = false;
			}
		}
	}
}
