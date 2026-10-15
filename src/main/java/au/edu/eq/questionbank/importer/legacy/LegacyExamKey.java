package au.edu.eq.questionbank.importer.legacy;

/**
 * Identifies one examination grouping in a legacy workbook.
 *
 * @param worksheetName original worksheet name
 * @param year          examination year
 */
public record LegacyExamKey(String worksheetName, int year) {

	/**
	 * Validates and normalises the worksheet and examination year.
	 *
	 * @throws IllegalArgumentException if the worksheet name is blank or the
	 *                                  examination year is invalid
	 */
	public LegacyExamKey {
		if (worksheetName == null || worksheetName.isBlank()) {
			throw new IllegalArgumentException("Worksheet name must not be blank");
		}
		if (year < 1) {
			throw new IllegalArgumentException("Examination year must be positive");
		}
		worksheetName = worksheetName.trim();
	}
}
