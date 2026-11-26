package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

	/**
	 * Creates a page writer for a destination PDF document.
	 *
	 * @param document destination PDF document
	 */
	public PrintablePageWriter(PDDocument document) {
		this.document = Objects.requireNonNull(document, "document");
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
		float availableWidth = PDRectangle.A4.getWidth() - 2 * MARGIN;
		float availableHeight = PDRectangle.A4.getHeight() - 2 * MARGIN;
		float scale = Math.min(1.0f, Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight()));
		float width = image.getWidth() * scale;
		float height = image.getHeight() * scale;
		ensureSpace(height);
		float bottom = cursorY - height;
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			stream.drawImage(image, MARGIN, bottom, width, height);
		}
		cursorY = bottom - GAP;
	}

	/**
	 * Appends a captured PDF region, starting a new page if required.
	 *
	 * @param source           source PDF document
	 * @param pageNumber       one-based source page number
	 * @param region           source rectangle in PDF points
	 * @param originalLabel    original part label, or an empty string
	 * @param replacementLabel replacement part label, or an empty string to remove
	 *                         the original label
	 * @throws IOException if PDF content cannot be reproduced
	 */
	public void appendRegion(PDDocument source, int pageNumber, PDRectangle region, String originalLabel,
			String replacementLabel) throws IOException {
		float availableWidth = PDRectangle.A4.getWidth() - 2 * MARGIN;
		float availableHeight = PDRectangle.A4.getHeight() - 2 * MARGIN;

		// Preserve physical dimensions unless the region is too
		// large to fit on an empty printable page.
		float scale = Math.min(1.0f,
				Math.min(availableWidth / region.getWidth(), availableHeight / region.getHeight()));
		float height = region.getHeight() * scale;
		ensureSpace(height);
		float bottom = cursorY - height;
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			stream.saveGraphicsState();

			// Position the region within the printable area,
			// applying uniform scaling only when necessary.
			stream.transform(new Matrix(scale, 0, 0, scale, MARGIN, bottom));
			PrintablePdfRegionWriter.appendRegion(source, pageNumber, document, stream, region, 0, 0, originalLabel,
					replacementLabel);
			stream.restoreGraphicsState();
		}
		cursorY = bottom - GAP;
	}

	/**
	 * Appends a single line of examination text, such as a Question number, booklet
	 * heading or multiple-choice Answer.
	 *
	 * @param text text to append
	 * @throws IOException if the text cannot be written
	 */
	public void appendText(String text) throws IOException {
		ensureSpace(TEXT_HEIGHT);
		try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
				true, true)) {
			stream.beginText();
			stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
			stream.newLineAtOffset(MARGIN, cursorY - 12);
			stream.showText(text);
			stream.endText();
		}
		cursorY -= TEXT_HEIGHT + GAP;
	}

	private void ensureSpace(float height) {
		if (page == null || cursorY - height < MARGIN) {
			page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			cursorY = page.getMediaBox().getHeight() - MARGIN;
		}
	}
}
