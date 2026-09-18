package au.edu.eq.questionbank.service.curriculum;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * Aggregate mapping-review coverage for one curriculum level.
 *
 * @param level                DESCRIPTOR or SUBTOPIC
 * @param total                total historical source nodes at this level
 * @param matched              sources with a valid MATCHED review
 * @param noMatch              sources with a valid NO_MATCH review
 * @param unreviewed           sources with neither a review nor confirmed
 *                             mapping
 * @param inconsistent         sources whose review/mapping state contradicts
 *                             itself
 * @param uncoveredTargetCount current target nodes with no confirmed
 *                             predecessor from the selected historical syllabus
 */
public record CurriculumMappingLevelCoverage(CurriculumLevel level, int total, int matched, int noMatch, int unreviewed,
		int inconsistent, int uncoveredTargetCount) {

	/**
	 * Creates level coverage with non-negative counts that partition the source
	 * total.
	 *
	 * @param level                DESCRIPTOR or SUBTOPIC
	 * @param total                total historical source nodes at this level
	 * @param matched              sources with a valid MATCHED review
	 * @param noMatch              sources with a valid NO_MATCH review
	 * @param unreviewed           sources with neither a review nor confirmed
	 *                             mapping
	 * @param inconsistent         sources whose review/mapping state contradicts
	 *                             itself
	 * @param uncoveredTargetCount current target nodes with no confirmed
	 *                             predecessor from the selected historical syllabus
	 */
	public CurriculumMappingLevelCoverage {
		if (level == null) {
			throw new NullPointerException("level");
		}
		if (level != CurriculumLevel.DESCRIPTOR && level != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("Mapping coverage supports DESCRIPTOR and SUBTOPIC only");
		}
		if (total < 0 || matched < 0 || noMatch < 0 || unreviewed < 0 || inconsistent < 0 || uncoveredTargetCount < 0) {
			throw new IllegalArgumentException("Coverage counts must not be negative");
		}
		if (matched + noMatch + unreviewed + inconsistent != total) {
			throw new IllegalArgumentException("Coverage state counts must equal total");
		}
	}

	/**
	 * Counts historical nodes with a valid matched or explicit no-match decision.
	 *
	 * @return matched plus no-match source count
	 */
	public int deliberatelyReviewed() {
		return matched + noMatch;
	}

	/**
	 * Calculates the share of historical nodes with deliberate review decisions.
	 *
	 * @return reviewed percentage from 0 to 100, or zero when there are no source
	 *         nodes
	 */
	public double reviewedPercentage() {
		if (total == 0) {
			return 0.0;
		}
		return deliberatelyReviewed() * 100.0 / total;
	}

	/**
	 * Summarises review completeness for this curriculum level.
	 *
	 * @return not applicable for no sources, complete for fully reviewed sources,
	 *         otherwise incomplete
	 */
	public CurriculumMappingCoverageStatus status() {
		// Completion measures source review decisions, not coverage of newly introduced target content.
		if (total == 0) {
			return CurriculumMappingCoverageStatus.NOT_APPLICABLE;
		}
		if (unreviewed == 0 && inconsistent == 0) {
			return CurriculumMappingCoverageStatus.COMPLETE;
		}
		return CurriculumMappingCoverageStatus.INCOMPLETE;
	}
}
