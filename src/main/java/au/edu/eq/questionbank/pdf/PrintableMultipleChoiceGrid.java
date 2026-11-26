package au.edu.eq.questionbank.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Generates the multiple-choice response pages for Paper 1.
 *
 * <p>
 * The answer grid contains one numbered row for each selected multiple-choice
 * Question. All student response bubbles are initially empty.
 */
public final class PrintableMultipleChoiceGrid {

	private static final float COLUMN_WIDTH = 36;
	private static final int QUESTIONS_PER_PAGE = 20;
	private static final float QUESTION_COLUMN_WIDTH = 42;
	private static final float ROW_HEIGHT = 17;

	private PrintableMultipleChoiceGrid() {
	}

	/**
	 * Appends the multiple-choice instructions and answer grid.
	 *
	 * <p>
	 * Each page contains at most twenty response rows. Numbering continues across
	 * additional pages. No page is appended when the Question count is zero.
	 *
	 * @param document      destination Paper 1 PDF
	 * @param fonts         student examination fonts
	 * @param questionCount selected multiple-choice Question count
	 * @throws IllegalArgumentException if questionCount is negative
	 * @throws IOException              if page content cannot be written
	 */
	public static void appendAnswerGrid(PDDocument document, PrintableExamFonts fonts, int questionCount)
			throws IOException {
		if (questionCount < 0) {
			throw new IllegalArgumentException("Question count cannot be negative.");
		}
		for (int first = 1; first <= questionCount; first += QUESTIONS_PER_PAGE) {
			int rows = Math.min(QUESTIONS_PER_PAGE, questionCount - first + 1);
			PDPage page = PrintableExamPage.appendResponsePage(document, fonts);
			try (PDPageContentStream stream = new PDPageContentStream(document, page,
					PDPageContentStream.AppendMode.APPEND, true, true)) {
				float tableTop;
				if (first == 1) {
					drawInstructions(stream, page, fonts, questionCount);
					drawTable(stream, page, fonts, 217, 0, 1, true);
					PrintableExamPage.drawHorizontalRule(stream, page, 52, page.getMediaBox().getWidth() - 52, 264);
					tableTop = 285;
				} else {
					PrintableExamPage.writeText(stream, page, fonts.bold(), 16, 52, 85, "Section 1 (continued)");
					tableTop = 112;
				}
				drawTable(stream, page, fonts, tableTop, first, rows, false);

				// Place the completion reminder immediately
				// beneath the last answer grid.
				if (first + rows - 1 == questionCount) {
					float reminderBaseline = tableTop + (rows + 1) * ROW_HEIGHT + 24;
					PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 52, reminderBaseline,
							"Ensure you have filled an answer " + "bubble for each question.");
				}
			}
		}
	}

	private static void drawBubble(PDPageContentStream stream, PDPage page, float centreX, float centreTop,
			boolean filled) throws IOException {
		float centreY = page.getMediaBox().getHeight() - centreTop;
		float radiusX = 6.5f;
		float radiusY = 3.5f;

		// Approximate an ellipse using four cubic Bezier curves.
		float k = 0.55228475f;
		stream.saveGraphicsState();
		stream.setLineWidth(0.5f);
		stream.setStrokingColor(0.35f);
		stream.setNonStrokingColor(0.0f);
		stream.moveTo(centreX + radiusX, centreY);
		stream.curveTo(centreX + radiusX, centreY + k * radiusY, centreX + k * radiusX, centreY + radiusY, centreX,
				centreY + radiusY);
		stream.curveTo(centreX - k * radiusX, centreY + radiusY, centreX - radiusX, centreY + k * radiusY,
				centreX - radiusX, centreY);
		stream.curveTo(centreX - radiusX, centreY - k * radiusY, centreX - k * radiusX, centreY - radiusY, centreX,
				centreY - radiusY);
		stream.curveTo(centreX + k * radiusX, centreY - radiusY, centreX + radiusX, centreY - k * radiusY,
				centreX + radiusX, centreY);
		stream.closePath();
		if (filled) {
			stream.fill();
		} else {
			stream.stroke();
		}
		stream.restoreGraphicsState();
	}

	private static void drawInstructions(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts,
			int questionCount) throws IOException {
		PrintableExamPage.writeText(stream, page, fonts.bold(), 16, 52, 85, "Section 1");
		PrintableExamPage.writeText(stream, page, fonts.bold(), 13, 52, 110, "Instructions");
		String questionWord = questionCount == 1 ? "question" : "questions";
		String markWord = questionCount == 1 ? "mark" : "marks";
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 52, 130, "•  This section has "
				+ questionCount + " " + questionWord + " and is worth " + questionCount + " " + markWord + ".");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 52, 147,
				"•  Use a 2B pencil to fill in the A, B, C " + "or D answer bubble completely.");
		String questionRange = questionCount == 1 ? "Question 1" : "Questions 1–" + questionCount;
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 52, 164,
				"•  Choose the best answer for " + questionRange + ".");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 52, 181,
				"•  If you change your mind or make a mistake, " + "use an eraser to remove your");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 63.5f, 196,
				"response and fill in the new answer bubble completely.");
	}

	private static void drawTable(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts, float top,
			int firstQuestion, int rows, boolean example) throws IOException {
		float pageHeight = page.getMediaBox().getHeight();
		float gridWidth = QUESTION_COLUMN_WIDTH + 4 * COLUMN_WIDTH;
		float gridLeft = (page.getMediaBox().getWidth() - gridWidth) / 2;
		float optionLeft = gridLeft + QUESTION_COLUMN_WIDTH;

		// Extend the example label cell while keeping the
		// example bubbles aligned with the actual answer grid.
		float labelWidth = example ? 64 : QUESTION_COLUMN_WIDTH;
		float left = optionLeft - labelWidth;
		float width = labelWidth + 4 * COLUMN_WIDTH;
		float height = (rows + 1) * ROW_HEIGHT;
		stream.saveGraphicsState();

		// Shade alternate answer-option columns.
		stream.setNonStrokingColor(0.92f);
		for (int column = 0; column < 4; column += 2) {
			stream.addRect(optionLeft + column * COLUMN_WIDTH, pageHeight - top - height, COLUMN_WIDTH, height);
		}
		stream.fill();
		stream.setStrokingColor(0.35f);
		stream.setLineWidth(0.6f);
		stream.addRect(left, pageHeight - top - height, width, height);

		// Draw the shared vertical column boundaries.
		for (int column = 0; column < 4; column++) {
			float x = optionLeft + column * COLUMN_WIDTH;
			stream.moveTo(x, pageHeight - top);
			stream.lineTo(x, pageHeight - top - height);
		}

		// Separate the option headings from the response rows.
		float headerY = pageHeight - top - ROW_HEIGHT;
		stream.moveTo(left, headerY);
		stream.lineTo(left + width, headerY);

		// Match the QCAA five-Question grouping.
		for (int row = 5; row < rows; row += 5) {
			float y = pageHeight - top - (row + 1) * ROW_HEIGHT;
			stream.moveTo(left, y);
			stream.lineTo(left + width, y);
		}
		stream.stroke();
		stream.restoreGraphicsState();
		for (int column = 0; column < 4; column++) {
			float centreX = optionLeft + (column + 0.5f) * COLUMN_WIDTH;
			writeCentredText(stream, page, fonts.bold(), 11.5f, centreX, top + 12,
					String.valueOf((char) ('A' + column)));
		}
		for (int row = 0; row < rows; row++) {
			float rowTop = top + (row + 1) * ROW_HEIGHT;
			String label = example ? "Example:" : (firstQuestion + row) + ".";
			writeCentredText(stream, page, fonts.bold(), 11.5f, left + labelWidth / 2, rowTop + 12, label);
			for (int column = 0; column < 4; column++) {
				float centreX = optionLeft + (column + 0.5f) * COLUMN_WIDTH;

				// Only the example A bubble is filled.
				drawBubble(stream, page, centreX, rowTop + ROW_HEIGHT / 2, example && column == 0);
			}
		}
	}

	private static void writeCentredText(PDPageContentStream stream, PDPage page, PDFont font, float fontSize,
			float centreX, float baseline, String text) throws IOException {
		float width = font.getStringWidth(text) * fontSize / 1000;
		PrintableExamPage.writeText(stream, page, font, fontSize, centreX - width / 2, baseline, text);
	}
}
