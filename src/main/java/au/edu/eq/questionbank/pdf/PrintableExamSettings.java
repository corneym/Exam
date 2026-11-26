package au.edu.eq.questionbank.pdf;

/**
 * User-supplied metadata for a generated mock examination.
 *
 * @param subject                assessment subject
 * @param year                   mock assessment year
 * @param paperOnePerusalMinutes Paper 1 perusal time
 * @param paperOneWorkingMinutes Paper 1 working time
 * @param paperTwoPerusalMinutes Paper 2 perusal time
 * @param paperTwoWorkingMinutes Paper 2 working time
 */
public record PrintableExamSettings(String subject, int year, int paperOnePerusalMinutes, int paperOneWorkingMinutes,
		int paperTwoPerusalMinutes, int paperTwoWorkingMinutes) {

	/**
	 * Creates validated examination metadata.
	 *
	 * @param subject                assessment subject
	 * @param year                   mock assessment year
	 * @param paperOnePerusalMinutes Paper 1 perusal time
	 * @param paperOneWorkingMinutes Paper 1 working time
	 * @param paperTwoPerusalMinutes Paper 2 perusal time
	 * @param paperTwoWorkingMinutes Paper 2 working time
	 * @throws IllegalArgumentException if the subject, year or examination times
	 *                                  are invalid
	 */
	public PrintableExamSettings {
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("Assessment subject is required.");
		}
		if (year < 1) {
			throw new IllegalArgumentException("Assessment year must be positive.");
		}
		if (paperOnePerusalMinutes < 0 || paperTwoPerusalMinutes < 0 || paperOneWorkingMinutes < 1
				|| paperTwoWorkingMinutes < 1) {
			throw new IllegalArgumentException("Invalid examination times.");
		}
		subject = subject.trim();
	}
}
