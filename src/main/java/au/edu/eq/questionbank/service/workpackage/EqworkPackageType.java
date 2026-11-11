package au.edu.eq.questionbank.service.workpackage;

/**
 * Identifies the purpose of an offline .eqwork exchange package.
 */
public enum EqworkPackageType {
	/** A self-contained Subject dataset. */
	SUBJECT_SNAPSHOT,
	/** A coordinator-issued offline work allocation. */
	WORK_ASSIGNMENT,
	/** Work returned by a contributor. */
	CONTRIBUTION,
	/** The coordinator's acknowledgement of processed work. */
	IMPORT_RECEIPT
}
