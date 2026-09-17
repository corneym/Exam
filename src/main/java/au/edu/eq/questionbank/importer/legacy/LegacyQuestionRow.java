package au.edu.eq.questionbank.importer.legacy;

/**
 * Validated question metadata read from one row of a legacy question workbook.
 * <p>
 * Subject, exam provider and syllabus version are import context and therefore
 * are not properties of an individual workbook row.
 *
 * @param year                    the positive examination year
 * @param paperCode               {@code MCQ}, {@code 1}, or {@code 2}
 * @param questionCode            the question identifier, retained as text
 * @param marks                   the positive mark value
 * @param classificationCode      the source syllabus classification code
 * @param answer                  optional answer text supplied by the workbook
 * @param preambleCaptureRequired whether shared or introductory material should
 *                                be included during region capture
 */
public record LegacyQuestionRow(int year, String paperCode, String questionCode, int marks, String classificationCode,
		String answer, boolean preambleCaptureRequired) {

	/**
	 * Validates a legacy question row and converts blank answer text to null.
	 *
	 * @param year                    the positive examination year
	 * @param paperCode               {@code MCQ}, {@code 1}, or {@code 2}
	 * @param questionCode            the question identifier, retained as text
	 * @param marks                   the positive mark value
	 * @param classificationCode      the source syllabus classification code
	 * @param answer                  optional answer text supplied by the workbook
	 * @param preambleCaptureRequired whether shared or introductory material should
	 *                                be included during region capture
	 */
	public LegacyQuestionRow {
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (paperCode == null || paperCode.isBlank()) {
			throw new IllegalArgumentException("paperCode must not be blank");
		}
		if (!paperCode.equals("MCQ") && !paperCode.equals("1") && !paperCode.equals("2")) {
			throw new IllegalArgumentException("Unsupported paper code: " + paperCode);
		}
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (marks < 1) {
			throw new IllegalArgumentException("marks must be positive");
		}
		if (classificationCode == null || classificationCode.isBlank()) {
			throw new IllegalArgumentException("classificationCode must not be blank");
		}
		if (answer != null && answer.isBlank()) {
			answer = null;
		}
	}
}
