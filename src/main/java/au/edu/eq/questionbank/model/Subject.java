package au.edu.eq.questionbank.model;

import java.util.Objects;

public class Subject {

	private final long id;
	private final String name;

	public Subject(long id, String name) {
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

	@Override
	public String toString() {
		return name;
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

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
