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

		boolean hasText = answerText != null && !answerText.isBlank();

		if (!hasText && regions.isEmpty()) {
			throw new IllegalArgumentException("Answer must contain text, at least one region, or both");
		}

		this.id = id;
		this.answerText = answerText;
		this.regions = List.copyOf(regions);
	}

	public String getAnswerText() {
		return answerText;
	}

	public long getId() {
		return id;
	}

	public List<AnswerRegion> getRegions() {
		return regions;
	}
}
