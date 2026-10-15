package au.edu.eq.questionbank.importer.legacy;

/**
 * Proposed examination identity inferred from a legacy worksheet.
 *
 * <p>
 * The proposal is not authoritative until confirmed by the user. An empty
 * proposed exam name requires manual resolution.
 *
 * @param worksheetName original worksheet name
 * @param year          examination year
 * @param providerName  proposed examination provider
 * @param examName      proposed examination name, possibly empty
 * @param recognised    whether a known worksheet convention matched
 */
public record LegacyExamIdentityProposal(String worksheetName, int year, String providerName, String examName,
		boolean recognised) {

	/**
	 * Validates and normalises an examination identity proposal.
	 */
	public LegacyExamIdentityProposal {
		if (worksheetName == null || worksheetName.isBlank()) {
			throw new IllegalArgumentException("Worksheet name must not be blank");
		}
		if (year < 1) {
			throw new IllegalArgumentException("Exam year must be positive");
		}
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("Provider name must not be blank");
		}
		if (examName == null) {
			throw new NullPointerException("examName");
		}
		worksheetName = worksheetName.trim();
		providerName = providerName.trim();
		examName = examName.trim();
	}

	/**
	 * Whether the proposed identity has enough information to be presented for
	 * confirmation.
	 *
	 * @return true when both provider and exam name are available
	 */
	public boolean hasCompleteIdentity() {
		return !providerName.isBlank() && !examName.isBlank();
	}
}
