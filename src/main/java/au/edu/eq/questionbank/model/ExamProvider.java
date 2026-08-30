package au.edu.eq.questionbank.model;

/**
 * An organisation that issues examinations, such as an assessment authority
 * or a commercial examination provider.
 */
public class ExamProvider {
	private final long id;
	private final String name;

	/**
	 * Creates an examination provider.
	 *
	 * @param id   the persistent provider identifier
	 * @param name the provider's human-readable name
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code name} is null or blank
	 */
	public ExamProvider(long id, String name) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		this.id = id;
		this.name = name;
	}

	public long getId() {
		return id;
	}

	public String getName() {
		return name;
	}
}
