package au.edu.eq.questionbank.model;

import java.util.List;

/**
 * An answer to an examination question.
 * <p>
 * An answer may be represented by text, one or more regions from answer
 * material, or both.
 */
public class Answer {

	private final long id;
	private final String answerText;
	private final List<AnswerRegion> regions;

	/**
	 * Creates an answer.
	 *
	 * @param id         the positive persistent answer identifier
	 * @param answerText optional textual answer, such as {@code B}
	 * @param regions    optional answer regions
	 * @throws IllegalArgumentException if {@code id} is not positive or both answer
	 *                                  text and regions are absent
	 * @throws NullPointerException     if {@code regions} or an element of
	 *                                  {@code regions} is {@code null}
	 */
	public Answer(long id, String answerText, List<AnswerRegion> regions) {

		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		for (AnswerRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions must not contain null");
			}
		}

		// Require some answer content here; response-type-specific completeness is assessed separately.
		boolean hasText = answerText != null && !answerText.isBlank();

		if (!hasText && regions.isEmpty()) {
			throw new IllegalArgumentException("Answer must contain text, at least one region, or both");
		}

		this.id = id;
		this.answerText = answerText;
		// Freeze presentation order without sorting regions by page or position.
		this.regions = List.copyOf(regions);
	}

	/**
	 * Returns the textual part of this answer, if supplied.
	 *
	 * @return answer text, possibly null or blank for a region-only answer
	 */
	public String getAnswerText() {
		return answerText;
	}

	/**
	 * Returns the persistent identity of this answer.
	 *
	 * @return positive answer-row identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns source regions in answer presentation order.
	 *
	 * @return immutable ordered regions, empty for a text-only answer
	 */
	public List<AnswerRegion> getRegions() {
		return regions;
	}
}
