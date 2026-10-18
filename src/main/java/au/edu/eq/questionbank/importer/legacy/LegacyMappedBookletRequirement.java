package au.edu.eq.questionbank.importer.legacy;

/**
 * One booklet required by a confirmed legacy examination mapping.
 *
 * @param key         source worksheet and year
 * @param identity    confirmed examination provider and name
 * @param bookletName normalised booklet name
 */
public record LegacyMappedBookletRequirement(LegacyExamKey key, LegacyExamIdentity identity, String bookletName) {

	/**
	 * Validates and normalises a mapped booklet requirement.
	 *
	 * @throws NullPointerException     if the examination key or identity is null
	 * @throws IllegalArgumentException if the booklet name is blank
	 */
	public LegacyMappedBookletRequirement {
		if (key == null) {
			throw new NullPointerException("key");
		}
		if (identity == null) {
			throw new NullPointerException("identity");
		}
		if (bookletName == null || bookletName.isBlank()) {
			throw new IllegalArgumentException("Booklet name must not be blank");
		}
		bookletName = bookletName.trim();
	}
}
