package au.edu.eq.questionbank.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

public class PdfSession implements AutoCloseable {

	private final PDDocument document;
	private final PDFRenderer renderer;

	private PdfSession(PDDocument document) {
		this.document = document;
		this.renderer = new PDFRenderer(document);
	}

	public static PdfSession open(Path pdfPath) throws IOException {
		Objects.requireNonNull(pdfPath, "pdfPath");
		PDDocument document = Loader.loadPDF(pdfPath.toFile());
		return new PdfSession(document);
	}

	public int getPageCount() {
		return document.getNumberOfPages();
	}

	public BufferedImage renderPage(int pageNumber, float dpi) throws IOException {
		if (pageNumber < 1 || pageNumber > getPageCount()) {
			throw new IllegalArgumentException(
					String.format("Page number must be between 1 and %d: %d", getPageCount(), pageNumber));
		}
		if (dpi <= 0) {
			throw new IllegalArgumentException(String.format("DPI must be greater than zero: %d", dpi));
		}

		return renderer.renderImageWithDPI(pageNumber - 1, dpi);
	}

	@Override
	public void close() throws Exception {
		document.close();
	}

}
