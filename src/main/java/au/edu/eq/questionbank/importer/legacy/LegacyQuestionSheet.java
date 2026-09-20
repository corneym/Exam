package au.edu.eq.questionbank.importer.legacy;

import java.util.List;

/**
 * Validated question rows from one provider worksheet.
 *
 * @param providerName the provider identified by the worksheet name
 * @param questions    immutable question rows in workbook order
 */
public record LegacyQuestionSheet(String providerName, List<LegacyQuestionRow> questions) {

	/**
	 * Creates a provider worksheet with an immutable copy of its ordered question
	 * rows.
	 *
	 * @param providerName the provider identified by the worksheet name
	 * @param questions    immutable question rows in workbook order
	 */
	public LegacyQuestionSheet {
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (questions == null) {
			throw new NullPointerException("questions");
		}

		// Preserve worksheet order and prevent later caller changes to the validated
		// row collection.
		questions = List.copyOf(questions);
	}
}
