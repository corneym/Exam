package au.edu.eq.questionbank.importer.legacy;

/**
 * Confirmed provider and examination name for legacy import.
 *
 * <p>
 * Subject is supplied by the import context and year by the LegacyExamKey.
 *
 * @param providerName authoritative provider name
 * @param examName     authoritative examination name
 */
public record LegacyExamIdentity(String providerName, String examName) {

	/**
	 * Validates and normalises the confirmed examination identity.
	 *
	 * @throws IllegalArgumentException if the provider or examination name is blank
	 */
	public LegacyExamIdentity {
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("Provider must not be blank");
		}
		if (examName == null || examName.isBlank()) {
			throw new IllegalArgumentException("Examination name must not be blank");
		}
		providerName = providerName.trim();
		examName = examName.trim();
	}
}
