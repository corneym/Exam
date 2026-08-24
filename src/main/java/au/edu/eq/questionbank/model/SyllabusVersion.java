package au.edu.eq.questionbank.model;

import java.util.Objects;

/**
 * A named edition of the syllabus for a {@link Subject}.
 * <p>
 * The current flag identifies the version selected by default for new question
 * classifications. Syllabus versions use persistent identifier equality.
 */
public class SyllabusVersion {

	private final long id;
	private final Subject subject;
	private final String name;
	private final boolean current;

	/**
	 * Creates a syllabus version.
	 *
	 * @param id      the positive persistent version identifier
	 * @param subject the subject governed by the syllabus
	 * @param name    the non-blank version name, commonly its commencement year
	 * @param current whether this is the subject's current classification version
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code name} is blank
	 * @throws NullPointerException     if {@code subject} is {@code null}
	 */
	public SyllabusVersion(long id, Subject subject, String name, boolean current) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		this.id = id;
		this.subject = subject;
		this.name = name;
		this.current = current;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof SyllabusVersion other)) {
			return false;
		}
		return id == other.id;
	}

	public long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Subject getSubject() {
		return subject;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	public boolean isCurrent() {
		return current;
	}

	@Override
	public String toString() {
		return name + " " + subject.getName();
	}
}
