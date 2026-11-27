package au.edu.eq.questionbank.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Generates examination cover pages and the hatched non-response page using the
 * QCAA examination layout.
 *
 * <p>
 * Assessment metadata is supplied by the caller. QCAA logos, registration
 * squares and trimming margins are not reproduced.
 */
public final class PrintableExamCover {

	private static final float RESPONSE_LEFT = 59.5f;
	private static final float RESPONSE_RIGHT = 549.9f;

	private PrintableExamCover() {
	}

	/**
	 * Appends the hatched second page of a question and response book.
	 *
	 * @param document destination PDF document
	 * @param fonts    student examination fonts
	 * @return appended page
	 * @throws IOException if page content cannot be written
	 */
	public static PDPage appendHatchedPage(PDDocument document, PrintableExamFonts fonts) throws IOException {
		PDPage page = appendPage(document);
		float width = page.getMediaBox().getWidth();
		float height = page.getMediaBox().getHeight();
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			stream.saveGraphicsState();

			// Clip diagonal hatching to the physical page.
			stream.addRect(0, 0, width, height);
			stream.clip();
			stream.setStrokingColor(0.90f);
			stream.setLineWidth(0.35f);
			for (float x = -height; x < width; x += 6) {
				stream.moveTo(x, height);
				stream.lineTo(x + height, 0);
			}
			stream.stroke();
			stream.restoreGraphicsState();
			String first = "DO NOT WRITE ON THIS PAGE";
			String second = "THIS PAGE WILL NOT BE MARKED";

			// White strips maintain legibility against the hatching.
			drawWarning(stream, page, fonts.bold(), 85.3f, first);
			drawWarning(stream, page, fonts.bold(), 120.4f, second);
		}
		return page;
	}

	/**
	 * Appends the cover of the assessment marking guide.
	 *
	 * @param document destination PDF document
	 * @param fonts    Arial marking-guide fonts
	 * @param subject  assessment subject
	 * @param year     mock assessment year
	 * @return appended cover page
	 * @throws IOException if page content cannot be written
	 */
	public static PDPage appendMarkingGuideCover(PDDocument document, PrintableExamFonts fonts, String subject,
			int year) throws IOException {
		PDPage page = appendPage(document);
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			PrintableExamPage.writeText(stream, page, fonts.bold(), 28, 70.9f, 178.9f, subject + " marking guide");
			PrintableExamPage.writeText(stream, page, fonts.bold(), 28, 70.9f, 214.4f, "and response");
			PrintableExamPage.writeText(stream, page, fonts.regular(), 16, 70.9f, 244.7f, "Mock assessment " + year);

			// Retain the original marking-guide accent rule.
			stream.saveGraphicsState();
			stream.setStrokingColor(0.835f, 0.169f, 0.118f);
			stream.setLineWidth(4);
			float y = page.getMediaBox().getHeight() - 700;
			stream.moveTo(70.9f, y);
			stream.lineTo(581.1f, y);
			stream.stroke();
			stream.restoreGraphicsState();
		}
		return page;
	}

	/**
	 * Appends the cover of the multiple-choice question book.
	 *
	 * @param document destination PDF document
	 * @param fonts    student examination fonts
	 * @param subject  assessment subject
	 * @param year     mock assessment year
	 * @return appended cover page
	 * @throws IOException if page content cannot be written
	 */
	public static PDPage appendMultipleChoiceCover(PDDocument document, PrintableExamFonts fonts, String subject,
			int year) throws IOException {
		PDPage page = appendPage(document);
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			drawAssessmentHeading(stream, page, fonts, 42.5f, 552.8f, year, "Multiple choice question book");
			drawSubjectHeading(stream, page, fonts, 42.5f, subject, 1);
			PrintableExamPage.writeText(stream, page, fonts.bold(), 13, 42.5f, 533.2f, "General instruction");
			PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 42.5f, 552.7f,
					"•  Work in this book will not be marked.");
		}
		return page;
	}

	/**
	 * Appends the front cover of a question and response book.
	 *
	 * <p>
	 * Multiple-choice Questions contribute one mark each. Paper 2 must contain only
	 * short-response Questions.
	 *
	 * @param document            destination PDF document
	 * @param fonts               student examination fonts
	 * @param subject             assessment subject
	 * @param year                mock assessment year
	 * @param paper               paper number, either 1 or 2
	 * @param perusalMinutes      permitted perusal time in minutes
	 * @param workingMinutes      permitted working time in minutes
	 * @param multipleChoiceCount number of multiple-choice Questions
	 * @param shortResponseCount  number of top-level short-response Questions
	 * @param shortResponseMarks  total short-response marks
	 * @return appended cover page
	 * @throws IllegalArgumentException if the supplied paper number, times or
	 *                                  counts are invalid
	 * @throws IOException              if page content cannot be written
	 */
	public static PDPage appendResponseCover(PDDocument document, PrintableExamFonts fonts, String subject, int year,
			int paper, int perusalMinutes, int workingMinutes, int multipleChoiceCount, int shortResponseCount,
			int shortResponseMarks) throws IOException {
		if (paper != 1 && paper != 2) {
			throw new IllegalArgumentException("Paper number must be 1 or 2.");
		}
		if (perusalMinutes < 0 || workingMinutes < 1 || multipleChoiceCount < 0 || shortResponseCount < 0
				|| shortResponseMarks < 0) {
			throw new IllegalArgumentException("Invalid examination times or counts.");
		}
		if (paper == 2 && multipleChoiceCount != 0) {
			throw new IllegalArgumentException("Paper 2 cannot contain multiple-choice Questions.");
		}
		PDPage page = appendPage(document);
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			drawIdentificationFields(stream, page, fonts);
			drawAssessmentHeading(stream, page, fonts, RESPONSE_LEFT, RESPONSE_RIGHT, year,
					"Question and response book");
			drawSubjectHeading(stream, page, fonts, RESPONSE_LEFT, subject, paper);
			drawGeneralInstructions(stream, page, fonts, paper, perusalMinutes, workingMinutes);
			drawSectionSummary(stream, page, fonts, paper, multipleChoiceCount, shortResponseCount, shortResponseMarks);
		}
		return page;
	}

	private static PDPage appendPage(PDDocument document) {
		PDPage page = new PDPage(PDRectangle.A4);
		document.addPage(page);
		return page;
	}

	private static void drawAssessmentHeading(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts,
			float left, float right, int year, String banner) throws IOException {
		PrintableExamPage.writeText(stream, page, fonts.regular(), 15, left, 265.5f, "Mock assessment " + year);
		stream.saveGraphicsState();
		stream.setNonStrokingColor(0.13f);
		fillRectangle(stream, page, left, 277, right - left, 5);
		stream.setNonStrokingColor(0.427f, 0.433f, 0.442f);
		float bannerWidth = textWidth(fonts.bold(), 15, banner) + 16;
		float bannerLeft = right - bannerWidth;
		fillRectangle(stream, page, bannerLeft, 282, bannerWidth, 24);
		stream.setNonStrokingColor(1.0f);
		stream.beginText();
		stream.setFont(fonts.bold(), 15);
		stream.newLineAtOffset(bannerLeft + 8, page.getMediaBox().getHeight() - 297.4f);
		stream.showText(banner);
		stream.endText();
		stream.restoreGraphicsState();
	}

	private static void drawBox(PDPageContentStream stream, PDPage page, float left, float top, float width,
			float height) throws IOException {
		stream.saveGraphicsState();
		stream.setStrokingColor(0.30f);
		stream.setLineWidth(0.7f);
		stream.addRect(left, page.getMediaBox().getHeight() - top - height, width, height);
		stream.stroke();
		stream.restoreGraphicsState();
	}

	private static void drawGeneralInstructions(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts,
			int paper, int perusalMinutes, int workingMinutes) throws IOException {
		PrintableExamPage.writeText(stream, page, fonts.bold(), 13, RESPONSE_LEFT, 532.5f, "Time allowed");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, 552,
				"•  Perusal time — " + perusalMinutes + " minutes");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, 571.4f,
				"•  Working time — " + workingMinutes + " minutes");
		PrintableExamPage.writeText(stream, page, fonts.bold(), 13, RESPONSE_LEFT, 606.5f, "General instructions");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, 626,
				"•  Answer all questions in this question and");
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT + 11.4f, 639.8f,
				"response book.");
		float baseline = 659.3f;
		if (paper == 2) {
			PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, baseline,
					"•  Write using black or blue pen.");
			baseline += 19.5f;
		}
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, baseline,
				"•  QCAA-approved calculator permitted.");
		baseline += 19.5f;
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, baseline,
				"•  QCAA formula and data book provided.");
		baseline += 19.5f;
		PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, RESPONSE_LEFT, baseline,
				"•  Planning paper will not be marked.");
	}

	private static void drawIdentificationFields(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts)
			throws IOException {
		PDFont font = fonts.regular();
		PrintableExamPage.writeText(stream, page, font, 11.5f, RESPONSE_LEFT, 78.2f, "LUI");
		drawNumberBoxes(stream, page, 86.5f, 59.5f, 9, 27.5f, 28);
		PrintableExamPage.writeText(stream, page, font, 11.5f, 376, 78.2f, "School code");
		drawNumberBoxes(stream, page, 438, 59.5f, 4, 28, 28);
		PrintableExamPage.writeText(stream, page, font, 11.5f, RESPONSE_LEFT, 117, "School name");
		drawBox(stream, page, 127.5f, 98, 422.4f, 28);
		PrintableExamPage.writeText(stream, page, font, 11.5f, RESPONSE_LEFT, 156.1f, "Given name/s");
		drawBox(stream, page, 127.5f, 137, 235.5f, 28);
		PrintableExamPage.writeText(stream, page, font, 11.5f, RESPONSE_LEFT, 194.6f, "Family name");
		drawBox(stream, page, 127.5f, 176, 235.5f, 28);
		drawBox(stream, page, 379, 137, 170.9f, 67.5f);
		PrintableExamPage.writeText(stream, page, font, 11.5f, 437, 165.3f, "Attach your");
		PrintableExamPage.writeText(stream, page, font, 11.5f, 414.6f, 179.3f, "barcode ID label here");
		PrintableExamPage.writeText(stream, page, font, 11.5f, 378.6f, 245.1f, "Book");
		drawBox(stream, page, 410, 227, 29, 29);
		PrintableExamPage.writeText(stream, page, font, 11.5f, 445.5f, 245.1f, "of");
		drawBox(stream, page, 462, 227, 29, 29);
		PrintableExamPage.writeText(stream, page, font, 11.5f, 498, 245.1f, "books used");
	}

	private static void drawNumberBoxes(PDPageContentStream stream, PDPage page, float left, float top, int count,
			float cellWidth, float height) throws IOException {
		drawBox(stream, page, left, top, count * cellWidth, height);
		stream.saveGraphicsState();
		stream.setStrokingColor(0.30f);
		stream.setLineWidth(0.7f);
		float pageHeight = page.getMediaBox().getHeight();

		// Draw each shared cell boundary only once.
		for (int index = 1; index < count; index++) {
			float x = left + index * cellWidth;
			stream.moveTo(x, pageHeight - top);
			stream.lineTo(x, pageHeight - top - height);
		}
		stream.stroke();
		stream.restoreGraphicsState();
	}

	private static void drawSectionSummary(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts, int paper,
			int multipleChoiceCount, int shortResponseCount, int shortResponseMarks) throws IOException {
		stream.saveGraphicsState();
		stream.setStrokingColor(0.13f);
		stream.setLineWidth(1.0f);
		float height = page.getMediaBox().getHeight();
		stream.moveTo(337, height - 524);
		stream.lineTo(337, height - (paper == 1 ? 700 : 720));
		stream.stroke();
		stream.restoreGraphicsState();
		if (paper == 1) {
			PrintableExamPage.writeText(stream, page, fonts.bold(), 13, 351.5f, 532.5f,
					"Section 1  (" + markText(multipleChoiceCount) + ")");
			PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 351.5f, 552,
					"•  " + multipleChoiceCount + " multiple choice " + questionText(multipleChoiceCount));
			PrintableExamPage.writeText(stream, page, fonts.bold(), 13, 351.5f, 587,
					"Section 2  (" + markText(shortResponseMarks) + ")");
			PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 351.5f, 606.5f,
					"•  " + shortResponseCount + " short response " + questionText(shortResponseCount));
		} else {
			PrintableExamPage.writeText(stream, page, fonts.bold(), 13, 351.5f, 532.5f,
					"Section 1  (" + markText(shortResponseMarks) + ")");
			PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, 351.5f, 552,
					"•  " + shortResponseCount + " short response " + questionText(shortResponseCount));
		}
	}

	private static void drawSubjectHeading(PDPageContentStream stream, PDPage page, PrintableExamFonts fonts,
			float left, String subject, int paper) throws IOException {
		PrintableExamPage.writeText(stream, page, fonts.bold(), 36, left, 378.7f, subject);
		stream.saveGraphicsState();
		stream.setNonStrokingColor(0.43f);
		stream.beginText();
		stream.setFont(fonts.regular(), 24);
		stream.newLineAtOffset(left, page.getMediaBox().getHeight() - 418.9f);
		stream.showText("Paper " + paper);
		stream.endText();
		stream.restoreGraphicsState();
	}

	private static void drawWarning(PDPageContentStream stream, PDPage page, PDFont font, float baseline,
			String warning) throws IOException {
		float fontSize = 13;
		float width = textWidth(font, fontSize, warning);
		float left = (page.getMediaBox().getWidth() - width) / 2;
		stream.saveGraphicsState();
		stream.setNonStrokingColor(1.0f);
		fillRectangle(stream, page, left - 6, baseline - 13, width + 12, 18);
		stream.restoreGraphicsState();
		PrintableExamPage.writeText(stream, page, font, fontSize, left, baseline, warning);
	}

	private static void fillRectangle(PDPageContentStream stream, PDPage page, float left, float top, float width,
			float height) throws IOException {
		stream.addRect(left, page.getMediaBox().getHeight() - top - height, width, height);
		stream.fill();
	}

	private static String markText(int marks) {
		return marks + (marks == 1 ? " mark" : " marks");
	}

	private static String questionText(int questions) {
		return questions == 1 ? "question" : "questions";
	}

	private static float textWidth(PDFont font, float size, String text) throws IOException {
		return font.getStringWidth(text) * size / 1000;
	}
}
