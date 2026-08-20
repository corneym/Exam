package au.edu.eq.questionbank.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

public class PdfSession implements AutoCloseable {

	public static PdfSession open(Path pdfPath) throws IOException {
		Objects.requireNonNull(pdfPath, "pdfPath");
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
