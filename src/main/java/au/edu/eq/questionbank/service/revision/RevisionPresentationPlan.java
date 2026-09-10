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

	RevisionPresentationPlan(RevisionCorpus sourceCorpus, List<RevisionPresentationNode> rootNodes) {
		if (sourceCorpus == null) {
			throw new NullPointerException("sourceCorpus");
		}
		if (rootNodes == null) {
			throw new NullPointerException("rootNodes");
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
	}

	public List<RevisionPresentationNode> getRootNodes() {
		return rootNodes;
	}

	public RevisionCorpusStatistics getStatistics() {
		return sourceCorpus.getStatistics();
	}

	public Subject getSubject() {
		return sourceCorpus.getSubject();
	}

	public SyllabusVersion getSyllabusVersion() {
		return sourceCorpus.getSyllabusVersion();
	}
}
