package au.edu.eq.questionbank.service.curriculum;

/**
 * Descriptor-review evidence available while reviewing one source subtopic
 * against a target syllabus version.
 *
 * @param reviewedDescriptorCount descriptors with a persisted review outcome
 * @param totalDescriptorCount    direct descriptors belonging to the source
 *                                subtopic
 * @param noMatchDescriptorCount  reviewed descriptors whose outcome is
 *                                {@code NO_MATCH}
 */
public record SubtopicMappingEvidence(int reviewedDescriptorCount, int totalDescriptorCount,
		int noMatchDescriptorCount) {

	/**
	 * Validates that the counts describe a possible review state.
	 *
	 * @throws IllegalArgumentException if a count is negative, reviewed descriptors
	 *                                  exceed the total, or no-match descriptors
	 *                                  exceed reviewed descriptors
	 */
	public SubtopicMappingEvidence {
		if (reviewedDescriptorCount < 0 || totalDescriptorCount < 0 || noMatchDescriptorCount < 0) {
			throw new IllegalArgumentException("evidence counts must not be negative");
		}
		if (reviewedDescriptorCount > totalDescriptorCount) {
			throw new IllegalArgumentException("reviewed descriptor count must not exceed the total");
		}
		if (noMatchDescriptorCount > reviewedDescriptorCount) {
			throw new IllegalArgumentException("no-match descriptor count must not exceed reviewed descriptors");
		}
	}
}
