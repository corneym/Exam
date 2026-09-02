package au.edu.eq.questionbank.repository.curriculum;

/**
 * Indicates that an import reuses an existing subject and syllabus-version
 * identity but supplies different curriculum data or current status.
 */
public final class CurriculumImportConflictException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a curriculum import conflict with a user-facing explanation.
	 *
	 * @param message explanation of the conflicting stored and incoming data
	 */
	public CurriculumImportConflictException(String message) {
		super(message);
	}
}
