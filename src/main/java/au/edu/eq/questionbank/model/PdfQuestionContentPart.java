package au.edu.eq.questionbank.model;

/**
 * Question content reconstructed from one rectangular source-PDF region.
 *
 * @param region persisted PDF region
 */
public record PdfQuestionContentPart(QuestionRegion region) implements QuestionContentPart {

	/**
	 * Validates the PDF content part.
	 *
	 * @throws NullPointerException if {@code region} is {@code null}
	 */
	public PdfQuestionContentPart {
		if (region == null) {
			throw new NullPointerException("region");
		}
	}
}
