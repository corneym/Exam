package au.edu.eq.questionbank.service.curriculum;

/**
 * Completion state of one historical curriculum node when reviewed against a
 * particular target syllabus version.
 */
public enum CurriculumMappingSourceState {
	/**
	 * No review and no confirmed mapping exist for this source/target-version pair.
	 */
	UNREVIEWED,
	/**
	 * The review is MATCHED and at least one confirmed mapping reaches the selected
	 * target syllabus.
	 */
	MATCHED,
	/**
	 * The review explicitly records NO_MATCH and no confirmed mapping reaches the
	 * selected target syllabus.
	 */
	NO_MATCH,
	/**
	 * Persisted review and mapping state contradict each other.
	 */
	INCONSISTENT
}
