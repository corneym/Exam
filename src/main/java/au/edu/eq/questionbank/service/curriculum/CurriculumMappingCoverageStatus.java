package au.edu.eq.questionbank.service.curriculum;

/**
 * Completion state of mapping review coverage for a curriculum level or
 * syllabus-version pair.
 */
public enum CurriculumMappingCoverageStatus {
	/**
	 * No source nodes exist at the applicable curriculum level.
	 */
	NOT_APPLICABLE,
	/**
	 * Every applicable source node has a valid completed review.
	 */
	COMPLETE,
	/**
	 * At least one source node is unreviewed or inconsistent.
	 */
	INCOMPLETE
}
