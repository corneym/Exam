package au.edu.eq.questionbank.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Creates common A4 examination pages and draws fixed page elements using
 * top-relative coordinates.
 */
public final class PrintableExamPage {

	private static final float MCQ_HEADER_BASELINE = 42;
	private static final float MCQ_MARGIN = 42.5f;
	private static final float RESPONSE_BORDER_BOTTOM = 748;
	private static final float RESPONSE_BORDER_LEFT = 35.5f;
	private static final float RESPONSE_BORDER_TOP = 60;

	private PrintableExamPage() {
	}

	/**
	 * Appends an A4 portrait page with the multiple-choice running header.
	 *
	 * @param document destination PDF document
	 * @param fonts    student examination fonts
	 * @return appended page
	 * @throws IOException if page content cannot be written
	 */
	public static PDPage appendMultipleChoicePage(PDDocument document, PrintableExamFonts fonts) throws IOException {
		PDPage page = new PDPage(PDRectangle.A4);
		document.addPage(page);
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			writeText(stream, page, fonts.bold(), 13, MCQ_MARGIN, MCQ_HEADER_BASELINE, "THIS PAGE WILL NOT BE MARKED");
		}
		return page;
	}

	/**
	 * Appends an A4 portrait page with an outlined response area and the warning
	 * beneath the outline.
	 *
	 * @param document destination PDF document
	 * @param fonts    student examination fonts
	 * @return appended response page
	 * @throws IOException if page content cannot be written
	 */
	public static PDPage appendResponsePage(PDDocument document, PrintableExamFonts fonts) throws IOException {
		PDPage page = new PDPage(PDRectangle.A4);
		document.addPage(page);
		float pageWidth = page.getMediaBox().getWidth();
		float pageHeight = page.getMediaBox().getHeight();
		float borderWidth = pageWidth - 2 * RESPONSE_BORDER_LEFT;
		try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
			stream.saveGraphicsState();

			// Match the fine grey outline used in the original
			// question and response booklets.
			stream.setStrokingColor(0.58f, 0.59f, 0.60f);
			stream.setLineWidth(0.5f);
			stream.addRect(RESPONSE_BORDER_LEFT, pageHeight - RESPONSE_BORDER_BOTTOM, borderWidth,
					RESPONSE_BORDER_BOTTOM - RESPONSE_BORDER_TOP);
			stream.stroke();
			stream.restoreGraphicsState();
			writeText(stream, page, fonts.regular(), 9, RESPONSE_BORDER_LEFT, 758, "Do not write outside this box.");
		}
		return page;
	}

	/**
	 * Draws a horizontal rule at the supplied top-relative page position.
	 *
	 * @param stream destination page content stream
	 * @param page   destination page
	 * @param left   left endpoint in PDF points
	 * @param right  right endpoint in PDF points
	 * @param top    distance from the page top in PDF points
	 * @throws IOException if the rule cannot be written
	 */
	public static void drawHorizontalRule(PDPageContentStream stream, PDPage page, float left, float right, float top)
			throws IOException {
		float y = page.getMediaBox().getHeight() - top;
		stream.saveGraphicsState();
		stream.setStrokingColor(0.13f, 0.13f, 0.13f);
		stream.setLineWidth(1.0f);
		stream.moveTo(left, y);
		stream.lineTo(right, y);
		stream.stroke();
		stream.restoreGraphicsState();
	}

	/**
	 * Writes a single line of text at a top-relative baseline position.
	 *
	 * @param stream      destination page content stream
	 * @param page        destination page
	 * @param font        font to use
	 * @param fontSize    font size in PDF points
	 * @param x           left position in PDF points
	 * @param topBaseline baseline distance from the page top
	 * @param text        text to write
	 * @throws IOException if the text cannot be written
	 */
	public static void writeText(PDPageContentStream stream, PDPage page, PDFont font, float fontSize, float x,
			float topBaseline, String text) throws IOException {
		float y = page.getMediaBox().getHeight() - topBaseline;
		stream.saveGraphicsState();
		stream.setNonStrokingColor(0.0f);
		stream.beginText();
		stream.setFont(font, fontSize);
		stream.newLineAtOffset(x, y);
		stream.showText(text);
		stream.endText();
		stream.restoreGraphicsState();
	}
}
