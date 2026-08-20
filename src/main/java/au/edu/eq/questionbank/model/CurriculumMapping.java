package au.edu.eq.questionbank.model;

import java.util.Objects;

public class CurriculumMapping {

	private final long id;
	private final CurriculumNode source;
	private final CurriculumNode target;
	private final MappingStatus status;

	public CurriculumMapping(long id, CurriculumNode source, CurriculumNode target, MappingStatus status) {

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