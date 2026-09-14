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

	public int deliberatelyReviewed() {
		return matched + noMatch;
	}

	public double reviewedPercentage() {
		if (total == 0) {
			return 0.0;
		}
		return deliberatelyReviewed() * 100.0 / total;
	}

	public CurriculumMappingCoverageStatus status() {
		if (total == 0) {
			return CurriculumMappingCoverageStatus.NOT_APPLICABLE;
		}
		if (unreviewed == 0 && inconsistent == 0) {
			return CurriculumMappingCoverageStatus.COMPLETE;
		}
		return CurriculumMappingCoverageStatus.INCOMPLETE;
	}
}
