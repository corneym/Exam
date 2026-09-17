package au.edu.eq.questionbank.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * An open PDF document and renderer used for repeated page operations.
 * <p>
 * Domain page numbers remain one-based; this class performs the conversion to
 * PDFBox's zero-based page indexes. Sessions own their PDFBox document and must
 * be closed by their owner. Sessions are not thread-safe; callers must
 * serialize rendering and closing, and must not use a session after closing it.
 */
public class PdfSession implements AutoCloseable {

	private final PDDocument document;
	private final PDFRenderer renderer;

	private PdfSession(PDDocument document) {
		this.document = document;
		this.renderer = new PDFRenderer(document);
	}

	/**
	 * Opens a PDF session for a source file.
	 *
	 * @param pdfPath the PDF file to open
	 * @return an open session
	 * @throws IOException          if PDFBox cannot load the document
	 * @throws NullPointerException if {@code pdfPath} is {@code null}
	 */
	public static PdfSession open(Path pdfPath) throws IOException {
		if (pdfPath == null) {
			throw new NullPointerException("pdfPath");
		}
		PDDocument document = Loader.loadPDF(pdfPath.toFile());
		return new PdfSession(document);
	}

	/**
	 * Closes the owned PDFBox document.
	 *
	 * @throws Exception if the underlying document cannot be closed
	 */
	@Override
	public void close() throws Exception {
		document.close();
	}

	/**
	 * Extracts plain text from one displayed PDF page.
	 * <p>
	 * Page numbers are one-based, matching the rest of the application's PDF model.
	 *
	 * @param pageNumber the one-based page number
	 * @return extracted page text
	 * @throws IOException              if PDFBox cannot extract the text
	 * @throws IllegalArgumentException if the page number is outside the document
	 */
	public String extractPageText(int pageNumber) throws IOException {
		if (pageNumber < 1 || pageNumber > getPageCount()) {
			throw new IllegalArgumentException(
					String.format("Page number must be between 1 and %d: %d", getPageCount(), pageNumber));
		}
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setStartPage(pageNumber);
		stripper.setEndPage(pageNumber);
		return stripper.getText(document);
	}

	/**
	 * Returns the number of pages available to render.
	 *
	 * @return the number of pages in the open PDF document
	 */
	public int getPageCount() {
		return document.getNumberOfPages();
	}

	/**
	 * Renders a displayed PDF page at the requested resolution.
	 *
	 * @param pageNumber the one-based page number
	 * @param dpi        a positive, finite rendering resolution
	 * @return the rendered page image after PDFBox applies page rotation and crop
	 *         handling
	 * @throws IOException              if the page cannot be rendered
	 * @throws IllegalArgumentException if the page number is outside the document
	 *                                  or {@code dpi} is not positive and finite
	 */
	public BufferedImage renderPage(int pageNumber, float dpi) throws IOException {
		if (pageNumber < 1 || pageNumber > getPageCount()) {
			throw new IllegalArgumentException(
					String.format("Page number must be between 1 and %d: %d", getPageCount(), pageNumber));
		}
		if (!Float.isFinite(dpi) || dpi <= 0.0f) {
			throw new IllegalArgumentException("DPI must be positive and finite: " + dpi);
		}
		return renderer.renderImageWithDPI(pageNumber - 1, dpi);
	}
}
