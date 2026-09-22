package au.edu.eq.questionbank.service.revision;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Student-facing presentation plan derived from a revision corpus.
 */
public final class RevisionPresentationPlan {

	private final RevisionCorpus sourceCorpus;
	private final List<RevisionPresentationNode> rootNodes;
	private final RevisionGroupingMode groupingMode;

	/*
	 * Retain the existing package-level constructor for tests or collaborators that
	 * construct a presentation plan directly. Existing plans represented Descriptor
	 * presentation semantics.
	 */
	RevisionPresentationPlan(RevisionCorpus sourceCorpus, List<RevisionPresentationNode> rootNodes) {
		this(sourceCorpus, rootNodes, RevisionGroupingMode.DESCRIPTOR);
	}

	RevisionPresentationPlan(RevisionCorpus sourceCorpus, List<RevisionPresentationNode> rootNodes,
			RevisionGroupingMode groupingMode) {
		if (sourceCorpus == null) {
			throw new NullPointerException("sourceCorpus");
		}
		if (rootNodes == null) {
			throw new NullPointerException("rootNodes");
		}
		if (groupingMode == null) {
			throw new NullPointerException("groupingMode");
		}
		for (RevisionPresentationNode rootNode : rootNodes) {
			if (rootNode == null) {
				throw new NullPointerException("rootNodes contains null");
			}
			if (rootNode.getCurriculumNode().getLevel() != CurriculumLevel.UNIT) {
				throw new IllegalArgumentException("Presentation roots must be Unit nodes");
			}
			if (!sourceCorpus.getSyllabusVersion().equals(rootNode.getCurriculumNode().getSyllabusVersion())) {
				throw new IllegalArgumentException("Presentation root belongs to another syllabus");
			}
		}
		this.sourceCorpus = sourceCorpus;
		this.rootNodes = List.copyOf(rootNodes);
		this.groupingMode = groupingMode;
	}

	/**
	 * Returns the grouping mode used for this generated presentation plan.
	 *
	 * @return transient export grouping mode
	 */
	public RevisionGroupingMode getGroupingMode() {
		return groupingMode;
	}

	/**
	 * Returns the unit roots organising the student-facing presentation.
	 *
	 * @return immutable ordered presentation roots
	 */
	public List<RevisionPresentationNode> getRootNodes() {
		return rootNodes;
	}

	public RevisionCorpus getSourceCorpus() {
		return sourceCorpus;
	}

	/**
	 * Returns the original corpus statistics before multipart grouping.
	 *
	 * @return source corpus completeness counts
	 */
	public RevisionCorpusStatistics getStatistics() {
		return sourceCorpus.getStatistics();
	}

	/**
	 * Returns the subject represented by the source corpus.
	 *
	 * @return revision subject
	 */
	public Subject getSubject() {
		return sourceCorpus.getSubject();
	}

	/**
	 * Returns the current syllabus organising this presentation plan.
	 *
	 * @return source corpus syllabus version
	 */
	public SyllabusVersion getSyllabusVersion() {
		return sourceCorpus.getSyllabusVersion();
	}
}
