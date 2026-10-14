package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * Filters current-syllabus results by persisted revision-output exclusions
 * within the placements matched by Search.
 *
 * A Question can match Included and Excluded simultaneously when different
 * current placements have different states.
 */
enum QuestionSearchInclusionFilter {

	ALL("All"), INCLUDED("Included"), EXCLUDED("Excluded");

	private final String displayText;

	QuestionSearchInclusionFilter(String displayText) {
		this.displayText = displayText;
	}

	String displayText() {
		return displayText;
	}

	List<QuestionSearchResult> filter(List<QuestionSearchResult> results, Map<Long, Set<Long>> exclusionsByQuestionId) {
		Objects.requireNonNull(results, "results");
		Objects.requireNonNull(exclusionsByQuestionId, "exclusionsByQuestionId");
		if (this == ALL) {
			return List.copyOf(results);
		}

		// Preserve the retrieval service's deterministic order.
		return results.stream().filter(
				result -> matches(result, exclusionsByQuestionId.getOrDefault(result.question().getId(), Set.of())))
				.toList();
	}

	boolean matches(QuestionSearchResult result, Set<Long> excludedNodeIds) {
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(excludedNodeIds, "excludedNodeIds");
		if (this == ALL) {
			return true;
		}
		if (result.scope() != QuestionSearchScope.CURRENT_SYLLABUS) {
			throw new IllegalArgumentException("Inclusion filtering requires Current syllabus");
		}

		// Search results already contain exactly the matching
		// placements within the selected curriculum scope.
		// Never inspect unrelated current placements.
		boolean wantExcluded = this == EXCLUDED;
		for (CurriculumNode placement : result.currentApplicability()) {
			boolean placementExcluded = excludedNodeIds.contains(placement.getId());
			if (placementExcluded == wantExcluded) {
				return true;
			}
		}
		return false;
	}
}
