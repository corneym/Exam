package au.edu.eq.questionbank.service.retrieval;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Optional;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;

/**
 * Reconstructs a stored question image from its source PDF regions.
 */
public final class QuestionPreviewService {

	private final PdfStore pdfStore;
	private final QuestionExtractor questionExtractor;

	/**
	 * Creates a question preview service.
	 *
	 * @param pdfStore          stored PDF path resolver
	 * @param questionExtractor question-region renderer
	 * @throws NullPointerException if either dependency is {@code null}
	 */
	public QuestionPreviewService(PdfStore pdfStore, QuestionExtractor questionExtractor) {
		if (pdfStore == null) {
			throw new NullPointerException("pdfStore");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		this.pdfStore = pdfStore;
		this.questionExtractor = questionExtractor;
	}

	/**
	 * Reconstructs the assembled question image from its stored source regions.
	 * When shared context is linked, its ordered regions precede the question's own
	 * ordered regions.
	 *
	 * @param question question to preview
	 * @return the reconstructed image, or empty when the question has no stored
	 *         regions
	 * @throws Exception if the source PDF cannot be opened, rendered or closed
	 */
	public Optional<BufferedImage> loadPreview(Question question) throws Exception {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (question.getRegions().isEmpty()) {
			return Optional.empty();
		}
		Path pdfPath = pdfStore.resolve(question.getBooklet().getSourceDocument().getRelativePath());
		try (PdfSession session = PdfSession.open(pdfPath)) {
			return Optional.of(questionExtractor.extractQuestion(session, question));
		}
	}
}