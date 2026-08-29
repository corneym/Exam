package au.edu.eq.questionbank.model;

import java.util.Objects;

/**
 * One node in a versioned syllabus hierarchy.
 * <p>
 * Units are roots; topics belong to units, subtopics belong to topics, and
 * descriptors belong to topics or subtopics. A child and parent must belong to
 * the same {@link SyllabusVersion}. Nodes use persistent identifier equality.
 */
public abstract class CurriculumNode {

	private final long id;
	private final SyllabusVersion syllabusVersion;
	private final CurriculumNode parent;
	private final String code;
	private final String name;
	private final CurriculumLevel level;
	private final int displayOrder;

	/**
	 * Creates a curriculum node and validates its place in the hierarchy.
	 *
	 * @param id              the positive persistent node identifier
	 * @param syllabusVersion the syllabus version containing the node
	 * @param parent          {@code null} for a unit; otherwise the immediately
	 *                        preceding hierarchy level
	 * @param code            the non-blank syllabus classification code
	 * @param name            the non-blank curriculum label or descriptor
	 * @param level           the node's hierarchy level
	 * @param displayOrder    the non-negative order among sibling nodes
	 * @throws NullPointerException     if {@code syllabusVersion} or {@code level}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if identity, text, ordering, parent level,
	 *                                  or syllabus ownership is invalid
	 */
	protected CurriculumNode(long id, SyllabusVersion syllabusVersion, CurriculumNode parent, String code, String name,
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
			boolean validParent = false;
			switch (level) {
			case TOPIC:
				validParent = parent.getLevel() == CurriculumLevel.UNIT;
				break;
			case SUBTOPIC:
				validParent = parent.getLevel() == CurriculumLevel.TOPIC;
				break;
			case DESCRIPTOR:
				validParent = parent.getLevel() == CurriculumLevel.TOPIC
						|| parent.getLevel() == CurriculumLevel.SUBTOPIC;
				break;
			case UNIT:
				throw new IllegalStateException();
			}
			if (!validParent) {
				throw new IllegalArgumentException(level + " has invalid parent level " + parent.getLevel());
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
