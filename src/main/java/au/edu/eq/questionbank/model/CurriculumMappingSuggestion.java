package au.edu.eq.questionbank.model;

/**
 * A candidate directional mapping between curriculum nodes that has not yet
 * been persisted as a CurriculumMapping.
 */
public final class CurriculumMappingSuggestion {
	private final CurriculumNode source;
	private final CurriculumNode target;
	private final double score;

	public CurriculumMappingSuggestion(CurriculumNode source, CurriculumNode target, double score) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (target == null) {
			throw new NullPointerException("target");
		}
		if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
			throw new IllegalArgumentException("score must be between 0.0 and 1.0");
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
		this.source = source;
		this.target = target;
		this.score = score;
	}

	public double getScore() {
		return score;
	}

	public CurriculumNode getSource() {
		return source;
	}

	public CurriculumNode getTarget() {
		return target;
	}
}
