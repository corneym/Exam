package au.edu.eq.questionbank.model;

/**
 * Review state of a mapping between curriculum nodes from different syllabus
 * versions.
 */
public enum MappingStatus {
	/** Candidate mapping awaiting human confirmation. */
	SUGGESTED,
	/** Reviewed mapping used when deriving current applicability. */
	CONFIRMED
}
