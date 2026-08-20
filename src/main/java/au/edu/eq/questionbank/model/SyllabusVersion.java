package au.edu.eq.questionbank.model;

import java.util.Objects;

public class SyllabusVersion {

	private final long id;
	private final Subject subject;
	private final String name;
	private final boolean current;

	public SyllabusVersion(long id, Subject subject, String name, boolean current) {
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

	public long getId() {
		return id;
	}

	public Subject getSubject() {
		return subject;
	}

	public String getName() {
		return name;
	}

	public boolean isCurrent() {
		return current;
	}

	@Override
	public String toString() {
		return name + " " + subject.getName();
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

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}