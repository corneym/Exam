package au.edu.eq.questionbank.model;

import java.util.Objects;

public class CurriculumNode {

	private final long id;
	private final SyllabusVersion syllabusVersion;
	private final CurriculumNode parent;
	private final String code;
	private final String name;
	private final CurriculumLevel level;
	private final int displayOrder;

	public CurriculumNode(long id, SyllabusVersion syllabusVersion, CurriculumNode parent, String code, String name,
			CurriculumLevel level, int displayOrder) {

		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (level == null) {
			throw new NullPointerException("level");
		}
		if (displayOrder < 0) {
			throw new IllegalArgumentException("displayOrder must not be negative");
		}
		if (parent != null && !parent.getSyllabusVersion().equals(syllabusVersion)) {
			throw new IllegalArgumentException("parent must belong to the same syllabus version");
		}

		this.id = id;
		this.syllabusVersion = syllabusVersion;
		this.parent = parent;
		this.code = code;
		this.name = name;
		this.level = level;
		this.displayOrder = displayOrder;
	}

	public long getId() {
		return id;
	}

	public SyllabusVersion getSyllabusVersion() {
		return syllabusVersion;
	}

	public CurriculumNode getParent() {
		return parent;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public CurriculumLevel getLevel() {
		return level;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	@Override
	public String toString() {
		return code + " " + name;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof CurriculumNode other)) {
			return false;
		}
		return id == other.id;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}