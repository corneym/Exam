package au.edu.eq.questionbank.model;

/**
 * The persisted result of reviewing one source descriptor against one target
 * syllabus version. Absence of a review record represents an unreviewed source.
 */
public enum CurriculumMappingReviewOutcome {
	/** One or more directional mappings were confirmed for the review. */
	MATCHED,
	/** The reviewer confirmed that the target syllabus has no equivalent descriptor. */
	NO_MATCH
}
