package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * Writes examination content sequentially onto A4 PDF pages.
 *
 * <p>
 * Content retains its original scale whenever it fits within the printable page
 * area. A new page is started when the next content item cannot fit on the
 * current page.
 *
 * <p>
 * The caller owns and closes the destination document.
 */
public final class PrintablePageWriter {

	private static final float GAP = 8;
	private static final float MARGIN = 28;
	private static final float TEXT_HEIGHT = 18;
	private float cursorY;
	private final PDDocument document;
	private PDPage page;
	private final PrintableExamFonts fonts;
	private int responseMarks;
	private int responsePaper;
	private int responseQuestionCount;

	/**
	 * Creates a plain page writer for a destination PDF document.
	 *
	 * @param document destination PDF document
	 */
	public PrintablePageWriter(PDDocument document) {
		this.document = Objects.requireNonNull(document, "document");
		this.fonts = null;
	}

	/**
	 * Creates a multiple-choice examination page writer.
	 *
	 * <p>
	 * Every generated content page includes the multiple-choice running header. The
	 * first content page also includes the Section 1 instructions.
	 *
	 * @param document destination PDF document
	 * @param fonts    student examination fonts
	 * @throws NullPointerException if either argument is null
	 */
	public PrintablePageWriter(PDDocument document, PrintableExamFonts fonts) {
		this.document = Objects.requireNonNull(document, "document");
		this.fonts = Objects.requireNonNull(fonts, "fonts");
	}

	/**
	 * Creates a short-response examination page writer.
	 *
	 * @param document      destination PDF document
	 * @param fonts         student examination fonts
	 * @param paper         examination paper number, either 1 or 2
	 * @param questionCount number of top-level short-response Questions
	 * @param marks         total short-response marks
	 * @throws IllegalArgumentException if the paper number or counts are invalid
	 */
	public PrintablePageWriter(PDDocument document, PrintableExamFonts fonts, int paper, int questionCount, int marks) {
		this(document, fonts);
		if ((paper != 1 && paper != 2) || questionCount < 0 || marks < 0) {
			throw new IllegalArgumentException("Invalid short-response section.");
		}
		this.responsePaper = paper;
		this.responseQuestionCount = questionCount;
		this.responseMarks = marks;
	}

	/**
	 * Appends the centred end-of-paper heading.
	 *
	 * @throws IOException           if the heading cannot be written
	 * @throws IllegalStateException if this is not a response writer
	 */
	public void appendEndOfPaper() throws IOException {
		if (responsePaper == 0) {
			throw new IllegalStateException("Not a short-response page writer.");
		}
		ensureSpace(32);
		String text = "END OF PAPER";
		float width = fonts.bold().getStringWidth(text) * 13 / 1000;
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			PrintableExamPage.writeText(stream, page, fonts.bold(), 13, (page.getMediaBox().getWidth() - width) / 2,
					page.getMediaBox().getHeight() - cursorY + 22, text);
		}
		cursorY -= 32;
	}

	/**
	 * Appends stored PNG content, preserving its aspect ratio and fitting it within
	 * the printable page area.
	 *
	 * @param pngBytes encoded PNG image content
	 * @throws IOException if the image cannot be read or written
	 */
	public void appendImage(byte[] pngBytes) throws IOException {
		PDImageXObject image = PDImageXObject.createFromByteArray(document, pngBytes, "question-image");
		float availableWidth = PDRectangle.A4.getWidth() - 2 * leftMargin();
		float scale = Math.min(1.0f,
				Math.min(availableWidth / image.getWidth(), availableHeight() / image.getHeight()));
		float width = image.getWidth() * scale;
		float height = image.getHeight() * scale;
		ensureSpace(height);
		float bottom = cursorY - height;
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			stream.drawImage(image, leftMargin(), bottom, width, height);
		}
		cursorY = bottom - GAP;
	}

	/**
	 * Appends captured PDF content, starting a new page if required.
	 *
	 * <p>
	 * Short-response part labels are reproduced using the supplied student font and
	 * the generated examination label format.
	 *
	 * @param source           source PDF document
	 * @param pageNumber       one-based source page number
	 * @param region           source rectangle in PDF points
	 * @param originalLabel    original part label, or empty
	 * @param replacementLabel replacement part label, or empty to remove
	 * @throws IOException if PDF content cannot be reproduced
	 */
	public void appendRegion(PDDocument source, int pageNumber, PDRectangle region, String originalLabel,
			String replacementLabel) throws IOException {
		float availableWidth = PDRectangle.A4.getWidth() - 2 * leftMargin();
		float scale = Math.min(1.0f,
				Math.min(availableWidth / region.getWidth(), availableHeight() / region.getHeight()));
		float height = region.getHeight() * scale;
		ensureSpace(height);
		float bottom = cursorY - height;
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			stream.saveGraphicsState();
			stream.transform(new Matrix(scale, 0, 0, scale, leftMargin(), bottom));
			if (responsePaper != 0 && !originalLabel.isEmpty()) {

				// Existing numbering uses (a). Original providers
				// may print either (a) or a).
				List<String> alternatives = List.of(originalLabel, originalLabel.replace("(", ""));
				PrintablePdfRegionWriter.appendRegionWithLabel(source, pageNumber, document, stream, region, region, 0,
						0, alternatives, replacementLabel.replace("(", ""), fonts.regular(), true);
			} else {
				PrintablePdfRegionWriter.appendRegion(source, pageNumber, document, stream, region, 0, 0, originalLabel,
						replacementLabel);
			}
			stream.restoreGraphicsState();
		}
		cursorY = bottom - GAP;
	}

	/**
	 * Appends a single line of examination text.
	 *
	 * <p>
	 * Multiple-choice examination headings use the supplied bold student font.
	 * Plain pages retain their original text style.
	 *
	 * @param text text to append
	 * @throws IOException if the text cannot be written
	 */
	public void appendText(String text) throws IOException {
		ensureSpace(TEXT_HEIGHT);
		PDFont font = fonts == null ? new PDType1Font(Standard14Fonts.FontName.HELVETICA) : fonts.bold();
		float size = fonts == null ? 12 : 13;
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			stream.beginText();
			stream.setFont(font, size);
			stream.newLineAtOffset(leftMargin(), cursorY - size);
			stream.showText(text);
			stream.endText();
		}
		cursorY -= TEXT_HEIGHT + GAP;
	}

	private float availableHeight() {
		if (responsePaper != 0) {

			// Interior of the response outline, with clearance
			// at both the top and bottom.
			return 736 - 75;
		}
		if (fonts == null) {
			return PDRectangle.A4.getHeight() - 2 * MARGIN;
		}
		return PDRectangle.A4.getHeight() - 70 - leftMargin();
	}

	private float bottomMargin() {
		return responsePaper == 0 ? leftMargin() : PDRectangle.A4.getHeight() - 736;
	}

	private void ensureSpace(float height) throws IOException {
		if (page == null) {
			startPage();
		}
		if (cursorY - height < bottomMargin()) {
			startPage();
		}
	}

	private float leftMargin() {
		if (responsePaper != 0) {
			return 48;
		}
		return fonts == null ? MARGIN : 42.5f;
	}

	private void startPage() throws IOException {
		boolean firstPage = page == null;
		if (fonts == null) {
			page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			cursorY = page.getMediaBox().getHeight() - MARGIN;
			return;
		}
		if (responsePaper != 0) {
			page = PrintableExamPage.appendResponsePage(document, fonts);
			if (firstPage) {
				try (PDPageContentStream stream = new PDPageContentStream(document, page,
						PDPageContentStream.AppendMode.APPEND, true, true)) {
					writeResponseInstructions(stream);
				}
			}
			cursorY = page.getMediaBox().getHeight() - (firstPage ? 220 : 75);
			return;
		}
		page = PrintableExamPage.appendMultipleChoicePage(document, fonts);
		float top = 70;
		if (firstPage) {
			try (PDPageContentStream stream = new PDPageContentStream(document, page,
					PDPageContentStream.AppendMode.APPEND, true, true)) {
				PrintableExamPage.writeText(stream, page, fonts.bold(), 16, leftMargin(), 82, "Section 1");
				PrintableExamPage.writeText(stream, page, fonts.bold(), 13, leftMargin(), 108, "Instruction");
				PrintableExamPage.writeText(stream, page, fonts.regular(), 11.5f, leftMargin(), 130,
						"•  Respond to these questions in the " + "question and response book.");
				PrintableExamPage.drawHorizontalRule(stream, page, leftMargin(),
						page.getMediaBox().getWidth() - leftMargin(), 147.3f);
			}
			top = 164;
		}
		cursorY = page.getMediaBox().getHeight() - top;
	}

	private void writeResponseInstructions(PDPageContentStream stream) throws IOException {
		float left = leftMargin();
		PrintableExamPage.writeText(stream, page, fonts.bold(), 16, left, 85,
				responsePaper == 1 ? "Section 2" : "Section 1");
		PrintableExamPage.writeText(stream, page, fonts.bold(), 13, left, 106, "Instructions");
		List<String> lines = List.of("•  Write using black or blue pen.",
				"•  If you need more space for a response, use the " + "additional pages at the back of this book.",
				"    – On the additional pages, write the question " + "number you are responding to.",
				"    – Cancel any incorrect response by ruling a " + "single diagonal line through your work.",
				"    – Write the page number of your " + "alternative/additional response, i.e. See page …",
				"    – If you do not do this, your original response " + "will be marked.",
				"•  This section has " + responseQuestionCount
						+ (responseQuestionCount == 1 ? " question" : " questions") + " and is worth " + responseMarks
						+ (responseMarks == 1 ? " mark." : " marks."));
		float baseline = 122;
		for (String line : lines) {
			PrintableExamPage.writeText(stream, page, fonts.regular(), 10.5f, left, baseline, line);
			baseline += 12;
		}
		PrintableExamPage.drawHorizontalRule(stream, page, left, page.getMediaBox().getWidth() - left, 209);
	}
}
