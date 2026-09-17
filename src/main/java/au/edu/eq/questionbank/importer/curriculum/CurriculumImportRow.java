package au.edu.eq.questionbank.importer.curriculum;

/**
 * One non-blank curriculum code and its associated label or descriptor text as
 * read from a curriculum workbook.
 *
 * @param code    the non-blank curriculum code
 * @param content the non-blank curriculum label or descriptor text
 */
public record CurriculumImportRow(String code, String content) {

	/**
	 * Creates and validates this value.
	 *
	 * @throws IllegalArgumentException if either value is {@code null} or blank
	 */
	public CurriculumImportRow {

		// Reject missing values without rewriting the supplied code or authored
		// content.
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
	}
}
