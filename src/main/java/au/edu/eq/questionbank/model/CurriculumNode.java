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

		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
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
		if (level == CurriculumLevel.UNIT) {
			if (parent != null) {
				throw new IllegalArgumentException("UNIT must not have a parent");
			}
		} else {
			if (parent == null) {
				throw new IllegalArgumentException(level + " must have a parent");
			}

			if (!parent.getSyllabusVersion().equals(syllabusVersion)) {
				throw new IllegalArgumentException("parent must belong to the same syllabus version");
			}

			CurriculumLevel expectedParentLevel = switch (level) {
			case TOPIC -> CurriculumLevel.UNIT;
			case SUBTOPIC -> CurriculumLevel.TOPIC;
			case SUBSUBTOPIC -> CurriculumLevel.SUBTOPIC;
			case UNIT -> throw new IllegalStateException();
			};

			if (parent.getLevel() != expectedParentLevel) {
				throw new IllegalArgumentException(level + " must have a " + expectedParentLevel + " parent");
			}
		}
		if (displayOrder < 0) {
			throw new IllegalArgumentException("displayOrder must not be negative");
		}

		this.id = id;
		this.syllabusVersion = syllabusVersion;
		this.parent = parent;
		this.code = code;
		this.name = name;
		this.level = level;
		this.displayOrder = displayOrder;
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

	public String getCode() {
		return code;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	public long getId() {
		return id;
	}

	public CurriculumLevel getLevel() {
		return level;
	}

	public String getName() {
		return name;
	}

	public CurriculumNode getParent() {
		return parent;
	}

	public SyllabusVersion getSyllabusVersion() {
		return syllabusVersion;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return code + " " + name;
	}
}