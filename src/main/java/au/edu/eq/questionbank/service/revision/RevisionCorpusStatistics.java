package au.edu.eq.questionbank.service.revision;

/**
 * Completeness statistics for one revision corpus.
 * <p>
 * Placement count may exceed unique-question count when one stored question is
 * legitimately applicable to more than one current curriculum node.
 */
public final class RevisionCorpusStatistics {

	private final int applicablePlacements;
	private final int uniqueApplicableQuestions;
	private final int renderableQuestions;
	private final int missingQuestionRegionQuestions;
	private final int questionsWithAnswers;
	private final int questionsWithoutAnswers;
	private final int preambleReviewQuestions;

	RevisionCorpusStatistics(int applicablePlacements, int uniqueApplicableQuestions, int renderableQuestions,
			int missingQuestionRegionQuestions, int questionsWithAnswers, int questionsWithoutAnswers,
			int preambleReviewQuestions) {
		if (applicablePlacements < 0 || uniqueApplicableQuestions < 0 || renderableQuestions < 0
				|| missingQuestionRegionQuestions < 0 || questionsWithAnswers < 0 || questionsWithoutAnswers < 0
				|| preambleReviewQuestions < 0) {
			throw new IllegalArgumentException("Corpus statistics must not be negative");
		}
		if (renderableQuestions + missingQuestionRegionQuestions != uniqueApplicableQuestions) {
			throw new IllegalArgumentException(
					"Renderable and missing-region counts must equal unique applicable questions");
		}
		if (questionsWithAnswers + questionsWithoutAnswers != uniqueApplicableQuestions) {
			throw new IllegalArgumentException("Answer counts must equal unique applicable questions");
		}
		this.applicablePlacements = applicablePlacements;
		this.uniqueApplicableQuestions = uniqueApplicableQuestions;
		this.renderableQuestions = renderableQuestions;
		this.missingQuestionRegionQuestions = missingQuestionRegionQuestions;
		this.questionsWithAnswers = questionsWithAnswers;
		this.questionsWithoutAnswers = questionsWithoutAnswers;
		this.preambleReviewQuestions = preambleReviewQuestions;
	}

	/**
	 * Counts question assignments across current curriculum nodes.
	 *
	 * @return placement count, including repeated questions in distinct buckets
	 */
	public int getApplicablePlacements() {
		return applicablePlacements;
	}

	/**
	 * Counts unique applicable questions with no captured source regions.
	 *
	 * @return non-renderable question count
	 */
	public int getMissingQuestionRegionQuestions() {
		return missingQuestionRegionQuestions;
	}

	/**
	 * Counts unique questions carrying the historical legacy preamble hint. This
	 * count does not subtract questions whose shared context is now linked.
	 *
	 * @return the number of questions with legacy preamble evidence
	 */
	public int getSharedContextReviewQuestions() {
		return preambleReviewQuestions;
	}

	/**
	 * Counts unique applicable questions with an associated answer.
	 *
	 * @return question count with text or region answers
	 */
	public int getQuestionsWithAnswers() {
		return questionsWithAnswers;
	}

	/**
	 * Counts unique applicable questions without an associated answer.
	 *
	 * @return question count lacking answers
	 */
	public int getQuestionsWithoutAnswers() {
		return questionsWithoutAnswers;
	}

	/**
	 * Counts unique applicable questions with captured source regions.
	 *
	 * @return renderable question count
	 */
	public int getRenderableQuestions() {
		return renderableQuestions;
	}

	/**
	 * Counts distinct stored questions across all corpus placements.
	 *
	 * @return unique applicable question count
	 */
	public int getUniqueApplicableQuestions() {
		return uniqueApplicableQuestions;
	}
}
