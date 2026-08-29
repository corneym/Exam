package au.edu.eq.questionbank.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * An open PDF document and renderer used for repeated page operations.
 * <p>
 * Domain page numbers remain one-based; this class performs the conversion to
 * PDFBox's zero-based page indexes. Sessions own their PDFBox document and must
 * be closed.
 */
public class PdfSession implements AutoCloseable {

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

	private final PDDocument document;

	private final PDFRenderer renderer;

	private PdfSession(PDDocument document) {
		this.document = document;
		this.renderer = new PDFRenderer(document);
	}

	@Override
	public void close() throws Exception {
		document.close();
	}

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
