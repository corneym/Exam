package au.edu.eq.questionbank.service.revision;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * A transient student-revision corpus organised beneath one current syllabus.
 */
public final class RevisionCorpus {

	private final Subject subject;
	private final SyllabusVersion syllabusVersion;
	private final List<RevisionCorpusNode> rootNodes;
	private final RevisionCorpusStatistics statistics;

	RevisionCorpus(Subject subject, SyllabusVersion syllabusVersion, List<RevisionCorpusNode> rootNodes,
			RevisionCorpusStatistics statistics) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (rootNodes == null) {
			throw new NullPointerException("rootNodes");
		}
		if (statistics == null) {
			throw new NullPointerException("statistics");
		}
		if (!syllabusVersion.isCurrent()) {
			throw new IllegalArgumentException("syllabusVersion must be current");
		}
		if (!subject.equals(syllabusVersion.getSubject())) {
			throw new IllegalArgumentException("syllabusVersion must belong to subject");
		}
		for (RevisionCorpusNode rootNode : rootNodes) {
			if (rootNode == null) {
				throw new NullPointerException("rootNodes contains null");
			}
			if (rootNode.getCurriculumNode().getLevel() != CurriculumLevel.UNIT) {
				throw new IllegalArgumentException("Corpus roots must be Unit nodes");
			}
			if (!syllabusVersion.equals(rootNode.getCurriculumNode().getSyllabusVersion())) {
				throw new IllegalArgumentException("Corpus root belongs to another syllabus version");
			}
		}
		this.subject = subject;
		this.syllabusVersion = syllabusVersion;
		this.rootNodes = List.copyOf(rootNodes);
		this.statistics = statistics;
	}

	/**
	 * Returns the current syllabus units organising this corpus.
	 *
	 * @return immutable unit roots in revision order
	 */
	public List<RevisionCorpusNode> getRootNodes() {
		return rootNodes;
	}

	/**
	 * Returns completeness counts computed when the corpus was built.
	 *
	 * @return corpus placement and unique-question statistics
	 */
	public RevisionCorpusStatistics getStatistics() {
		return statistics;
	}

	/**
	 * Returns the subject covered by this revision corpus.
	 *
	 * @return revision subject
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Returns the current syllabus used to organise revision content.
	 *
	 * @return current syllabus version
	 */
	public SyllabusVersion getSyllabusVersion() {
		return syllabusVersion;
	}
}
