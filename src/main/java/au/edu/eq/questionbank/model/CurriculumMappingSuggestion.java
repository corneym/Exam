package au.edu.eq.questionbank.model;

/**
 * A scored candidate for a directional source-to-target curriculum mapping.
 * A suggestion is not a confirmed mapping and is not persisted by this type.
 */
public final class CurriculumMappingSuggestion {
	private final CurriculumNode source;
	private final CurriculumNode target;
	private final double score;

	/**
	 * Creates a candidate between nodes at the same curriculum level in different
	 * syllabus versions of the same subject.
	 *
	 * @param source the node being mapped from
	 * @param target the candidate node being mapped to
	 * @param score  the finite similarity score from {@code 0.0} to {@code 1.0}
	 * @throws NullPointerException if either endpoint is {@code null}
	 * @throws IllegalArgumentException if the score is outside its permitted range,
	 *                                  or the endpoints violate mapping invariants
	 */
	public CurriculumMappingSuggestion(CurriculumNode source, CurriculumNode target, double score) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (target == null) {
			throw new NullPointerException("target");
		}
		// Reject NaN and infinity as well as out-of-range scores before candidates are ranked.
		if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
			throw new IllegalArgumentException("score must be between 0.0 and 1.0");
		}
		// A candidate must satisfy the same endpoint rules as a persisted mapping.
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

	/**
	 * Returns the text-similarity score used to rank this suggestion.
	 *
	 * @return the normalised similarity score from {@code 0.0} to {@code 1.0}
	 */
	public double getScore() {
		return score;
	}

	/**
	 * Returns the source node being considered for mapping.
	 *
	 * @return the directional source node
	 */
	public CurriculumNode getSource() {
		return source;
	}

	/**
	 * Returns the candidate node in the target syllabus.
	 *
	 * @return the directional candidate target node
	 */
	public CurriculumNode getTarget() {
		return target;
	}
}
