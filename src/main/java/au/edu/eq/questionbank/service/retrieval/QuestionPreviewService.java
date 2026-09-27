package au.edu.eq.questionbank.service.retrieval;

import java.awt.image.BufferedImage;
import java.util.Optional;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.service.render.QuestionContentRenderer;

/**
 * Reconstructs a stored question image from its source PDF regions.
 */
public final class QuestionPreviewService {

	private final QuestionContentRenderer contentRenderer;

	/**
	 * Creates a Question preview service from the existing PDF rendering
	 * dependencies.
	 * <p>
	 * This constructor is retained for callers that already provide a
	 * {@link PdfStore} and {@link QuestionExtractor}. Mixed Question content is
	 * delegated internally to a {@link QuestionContentRenderer}.
	 *
	 * @param pdfStore          stored PDF path resolver
	 * @param questionExtractor renderer for PDF-backed Question regions
	 * @throws NullPointerException if either dependency is {@code null}
	 */
	public QuestionPreviewService(PdfStore pdfStore, QuestionExtractor questionExtractor) {
		this(new QuestionContentRenderer(pdfStore, questionExtractor));
	}

	/**
	 * Creates a Question preview service using the supplied mixed-content renderer.
	 *
	 * @param contentRenderer renderer for ordered PDF and stored-image Question
	 *                        content
	 * @throws NullPointerException if {@code contentRenderer} is {@code null}
	 */
	public QuestionPreviewService(QuestionContentRenderer contentRenderer) {
		if (contentRenderer == null) {
			throw new NullPointerException("contentRenderer");
		}

		// Store the mixed-content renderer as the single preview rendering boundary.
		this.contentRenderer = contentRenderer;
	}

	/**
	 * Reconstructs the assembled Question preview.
	 * <p>
	 * Linked shared context is shown first, followed by the Question's
	 * authoritative ordered content, which may mix PDF regions and stored images.
	 *
	 * @param question Question to preview
	 * @return reconstructed image, or empty for metadata-only Questions
	 * @throws NullPointerException if {@code question} is {@code null}
	 * @throws Exception            if required source material cannot be rendered
	 */
	public Optional<BufferedImage> loadPreview(Question question) throws Exception {
		if (question == null) {
			throw new NullPointerException("question");
		}

		// Metadata-only Questions have no body content. Image-only Questions remain
		// renderable even though they have no PDF regions.
		if (question.getContentParts().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(contentRenderer.renderQuestionPreview(question));
	}
}
