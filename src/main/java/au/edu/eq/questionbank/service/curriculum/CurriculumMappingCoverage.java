package au.edu.eq.questionbank.service.curriculum;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Mapping-review coverage for one ordered historical-to-current syllabus pair.
 *
 * @param sourceVersion      historical source syllabus
 * @param targetVersion      current target syllabus
 * @param descriptorCoverage descriptor review coverage
 * @param subtopicCoverage   subtopic review coverage
 */
public record CurriculumMappingCoverage(SyllabusVersion sourceVersion, SyllabusVersion targetVersion,
		CurriculumMappingLevelCoverage descriptorCoverage, CurriculumMappingLevelCoverage subtopicCoverage) {

	/**
	 * Creates coverage for a syllabus pair, validating the reported node levels.
	 *
	 * @param sourceVersion      historical source syllabus
	 * @param targetVersion      current target syllabus
	 * @param descriptorCoverage descriptor review coverage
	 * @param subtopicCoverage   subtopic review coverage
	 */
	public CurriculumMappingCoverage {
		if (sourceVersion == null) {
			throw new NullPointerException("sourceVersion");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (descriptorCoverage == null) {
			throw new NullPointerException("descriptorCoverage");
		}
		if (subtopicCoverage == null) {
			throw new NullPointerException("subtopicCoverage");
		}
		if (descriptorCoverage.level() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("descriptorCoverage must describe DESCRIPTOR nodes");
		}
		if (subtopicCoverage.level() != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("subtopicCoverage must describe SUBTOPIC nodes");
		}
	}

	/**
	 * Returns the aggregate status across all applicable mapping levels.
	 *
	 * @return pair-wide coverage status
	 */
	public CurriculumMappingCoverageStatus status() {
		CurriculumMappingCoverageStatus descriptorStatus = descriptorCoverage.status();
		CurriculumMappingCoverageStatus subtopicStatus = subtopicCoverage.status();
		if (descriptorStatus == CurriculumMappingCoverageStatus.INCOMPLETE
				|| subtopicStatus == CurriculumMappingCoverageStatus.INCOMPLETE) {
			return CurriculumMappingCoverageStatus.INCOMPLETE;
		}
		if (descriptorStatus == CurriculumMappingCoverageStatus.NOT_APPLICABLE
				&& subtopicStatus == CurriculumMappingCoverageStatus.NOT_APPLICABLE) {
			return CurriculumMappingCoverageStatus.NOT_APPLICABLE;
		}

		// An absent optional level must not prevent the other level from being
		// complete.
		return CurriculumMappingCoverageStatus.COMPLETE;
	}

	/**
	 * Indicates whether every applicable mapping level has complete review
	 * coverage.
	 *
	 * @return true only for COMPLETE coverage
	 */
	public boolean complete() {
		return status() == CurriculumMappingCoverageStatus.COMPLETE;
	}
}
