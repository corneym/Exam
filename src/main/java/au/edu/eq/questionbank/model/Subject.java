package au.edu.eq.questionbank.model;

import java.util.Objects;

/**
 * A school subject whose syllabuses and examination questions are held in the
 * question bank.
 * <p>
 * Subjects use persistent identifier equality.
 */
public class Subject {

	private final long id;
	private final String name;

	/**
	 * Creates a subject.
	 *
	 * @param id   the positive persistent subject identifier
	 * @param name the non-blank subject name
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code name} is blank
	 */
	public Subject(long id, String name) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		this.id = id;
		this.name = name;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof Subject other)) {
			return false;
		}
		return id == other.id;
	}

	/**
	 * Returns the persistent identity of this school subject.
	 *
	 * @return positive subject identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the subject name used in curriculum and examination selection.
	 *
	 * @return non-blank subject name
	 */
	public String getName() {
		return name;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return name;
	}
}
