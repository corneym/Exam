package au.edu.eq.questionbank.importer.legacy;

/**
 * One exam booklet required by a legacy question workbook but not currently
 * present in the database.
 *
 * @param providerName the examination provider named by the worksheet
 * @param year         the examination year
 * @param bookletName  the normalised booklet name used by the application
 */
public record LegacyBookletRequirement(String providerName, int year, String bookletName) {

	public LegacyBookletRequirement {
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (bookletName == null || bookletName.isBlank()) {
			throw new IllegalArgumentException("bookletName must not be blank");
		}
	}
}
