package au.edu.eq.questionbank.service.revision;

/**
 * Controls the curriculum depth used to group student-facing revision
 * questions.
 * <p>
 * This is transient export configuration. It does not alter persisted Question
 * classification or curriculum mapping.
 */
public enum RevisionGroupingMode {

	SUBTOPIC("Subtopic"), DESCRIPTOR("Descriptor");

	private final String displayName;

	RevisionGroupingMode(String displayName) {
		this.displayName = displayName;
	}

	/**
	 * Returns the user-facing grouping label.
	 *
	 * @return grouping label
	 */
	public String getDisplayName() {
		return displayName;
	}

	@Override
	public String toString() {
		return displayName;
	}
}
