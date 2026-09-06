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

	public int getApplicablePlacements() {
		return applicablePlacements;
	}

	public int getMissingQuestionRegionQuestions() {
		return missingQuestionRegionQuestions;
	}

	public int getPreambleReviewQuestions() {
		return preambleReviewQuestions;
	}

	public int getQuestionsWithAnswers() {
		return questionsWithAnswers;
	}

	public int getQuestionsWithoutAnswers() {
		return questionsWithoutAnswers;
	}

	public int getRenderableQuestions() {
		return renderableQuestions;
	}

	public int getUniqueApplicableQuestions() {
		return uniqueApplicableQuestions;
	}
}
