package au.edu.eq.questionbank;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * Generates disposable, parseable PDF assets without restricted source
 * material.
 */
public final class TestPdf {

	private TestPdf() {
	}

	/**
	 * Creates a one-page PDF with a distinguishing document title.
	 *
	 * @param title fixture title
	 * @return generated PDF bytes
	 * @throws IOException if PDF generation fails
	 */
	public static byte[] bytes(String title) throws IOException {
		try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.getDocumentInformation().setTitle(title);
			document.save(output);
			return output.toByteArray();
		}
	}
}
