package au.edu.eq.questionbank.model;

import java.util.Objects;

/**
 * A directional relationship from a node in one syllabus version to a node in
 * another version of the same subject.
 * <p>
 * Mappings support translating classifications across syllabus changes and use
 * persistent identifier equality.
 */
public class CurriculumMapping {

	private final long id;
	private final CurriculumNode source;
	private final CurriculumNode target;
	private final MappingStatus status;

	/**
	 * Creates a cross-version curriculum mapping.
	 *
	 * @param id     the positive persistent mapping identifier
	 * @param source the node being translated from
	 * @param target the corresponding node being translated to
	 * @param status the mapping's review state
	 * @throws NullPointerException     if {@code source}, {@code target}, or
	 *                                  {@code status} is {@code null}
	 * @throws IllegalArgumentException if the identifier is not positive, the nodes
	 *                                  have different subjects, or the nodes belong
	 *                                  to the same syllabus version
	 */
	public CurriculumMapping(long id, CurriculumNode source, CurriculumNode target, MappingStatus status) {

		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (target == null) {
			throw new NullPointerException("target");
		}
		if (status == null) {
			throw new NullPointerException("status");
		}

		if (!source.getSyllabusVersion().getSubject().equals(target.getSyllabusVersion().getSubject())) {

			throw new IllegalArgumentException("source and target must belong to the same subject");
		}

		if (source.getSyllabusVersion().equals(target.getSyllabusVersion())) {

			throw new IllegalArgumentException("source and target must belong to different syllabus versions");
		}
		if (source.getLevel() != target.getLevel()) {
			throw new IllegalArgumentException("source and target must be the same curriculum level");
		}

		this.id = id;
		this.source = source;
		this.target = target;
		this.status = status;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof CurriculumMapping other)) {
			return false;
		}
		return id == other.id;
	}

	public long getId() {
		return id;
	}

	public CurriculumNode getSource() {
		return source;
	}

	public MappingStatus getStatus() {
		return status;
	}

	public CurriculumNode getTarget() {
		return target;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
