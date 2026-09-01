package au.edu.eq.questionbank.service.curriculum;

/**
 * Scores the textual similarity of two pieces of curriculum content.
 */
public interface TextSimilarityScorer {
	/**
	 * Returns a normalised similarity score.
	 *
	 * @param sourceText the source curriculum text
	 * @param targetText the candidate target curriculum text
	 * @return a score from 0.0 for no similarity to 1.0 for maximum similarity
	 * @throws NullPointerException if either argument is {@code null}
	 */
	double score(String sourceText, String targetText);
}
