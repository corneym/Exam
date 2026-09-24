package au.edu.eq.questionbank.service.revision;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Unit;

/**
 * Applies export-time Unit selection to an immutable revision corpus.
 */
public final class RevisionCorpusScope {

	/** Creates a stateless corpus-scope service. */
	public RevisionCorpusScope() {
	}

	/**
	 * Returns corpus Units containing at least one renderable placement.
	 *
	 * @param corpus corpus to inspect
	 * @return immutable Units in corpus order
	 */
	public List<Unit> findNonEmptyUnits(RevisionCorpus corpus) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		List<Unit> units = new ArrayList<>();
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			if (root.getCurriculumNode().getLevel() != CurriculumLevel.UNIT
					|| !(root.getCurriculumNode() instanceof Unit unit)) {
				throw new IllegalStateException("Revision corpus root must be a Unit");
			}
			if (hasRenderablePlacement(root)) {
				units.add(unit);
			}
		}
		return List.copyOf(units);
	}

	/**
	 * Restricts a corpus to the requested Units and recalculates its statistics.
	 *
	 * @param corpus          source corpus
	 * @param selectedUnitIds identifiers of Units to retain
	 * @return immutable corpus containing the selected Unit roots
	 * @throws IllegalArgumentException if no Unit is selected or an identifier is
	 *                                  invalid or absent from the corpus
	 */
	public RevisionCorpus selectUnits(RevisionCorpus corpus, Set<Long> selectedUnitIds) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (selectedUnitIds == null) {
			throw new NullPointerException("selectedUnitIds");
		}
		if (selectedUnitIds.isEmpty()) {
			throw new IllegalArgumentException("At least one Unit must be selected");
		}
		Set<Long> requestedIds = new HashSet<>();
		for (Long unitId : selectedUnitIds) {
			if (unitId == null) {
				throw new NullPointerException("selectedUnitIds contains null");
			}
			if (unitId.longValue() < 1) {
				throw new IllegalArgumentException("Selected Unit IDs must be positive");
			}
			requestedIds.add(unitId);
		}
		List<RevisionCorpusNode> selectedRoots = new ArrayList<>();
		Set<Long> foundIds = new HashSet<>();
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			long unitId = root.getCurriculumNode().getId();
			if (!requestedIds.contains(unitId)) {
				continue;
			}
			selectedRoots.add(root);
			foundIds.add(unitId);
		}
		if (!foundIds.equals(requestedIds)) {
			Set<Long> missingIds = new HashSet<>(requestedIds);
			missingIds.removeAll(foundIds);
			throw new IllegalArgumentException(
					"Selected Unit does not belong to this revision corpus: " + missingIds.iterator().next());
		}
		RevisionCorpusStatistics statistics = buildStatistics(selectedRoots);
		return new RevisionCorpus(corpus.getSubject(), corpus.getSyllabusVersion(), selectedRoots, statistics);
	}

	private void accumulateStatistics(RevisionCorpusNode node, StatisticsAccumulator accumulator) {
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			accumulator.applicablePlacements++;
			Question question = placement.getQuestion();
			long questionId = question.getId();
			if (!accumulator.uniqueQuestionIds.add(questionId)) {
				continue;
			}
			if (placement.isRenderable()) {
				accumulator.renderableQuestionIds.add(questionId);
			} else {
				accumulator.missingRegionQuestionIds.add(questionId);
			}
			if (question.hasAnswer()) {
				accumulator.questionIdsWithAnswers.add(questionId);
			} else {
				accumulator.questionIdsWithoutAnswers.add(questionId);
			}
			if (question.isSharedContextCaptureRequired()) {
				accumulator.sharedContextReviewQuestionIds.add(questionId);
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			accumulateStatistics(child, accumulator);
		}
	}

	private RevisionCorpusStatistics buildStatistics(List<RevisionCorpusNode> rootNodes) {
		StatisticsAccumulator accumulator = new StatisticsAccumulator();
		for (RevisionCorpusNode root : rootNodes) {
			accumulateStatistics(root, accumulator);
		}
		return new RevisionCorpusStatistics(accumulator.applicablePlacements, accumulator.uniqueQuestionIds.size(),
				accumulator.renderableQuestionIds.size(), accumulator.missingRegionQuestionIds.size(),
				accumulator.questionIdsWithAnswers.size(), accumulator.questionIdsWithoutAnswers.size(),
				accumulator.sharedContextReviewQuestionIds.size());
	}

	private boolean hasRenderablePlacement(RevisionCorpusNode node) {
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			if (placement.isRenderable()) {
				return true;
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			if (hasRenderablePlacement(child)) {
				return true;
			}
		}
		return false;
	}

	private static final class StatisticsAccumulator {

		private int applicablePlacements;
		private final Set<Long> uniqueQuestionIds = new HashSet<>();
		private final Set<Long> renderableQuestionIds = new HashSet<>();
		private final Set<Long> missingRegionQuestionIds = new HashSet<>();
		private final Set<Long> questionIdsWithAnswers = new HashSet<>();
		private final Set<Long> questionIdsWithoutAnswers = new HashSet<>();
		private final Set<Long> sharedContextReviewQuestionIds = new HashSet<>();
	}
}
