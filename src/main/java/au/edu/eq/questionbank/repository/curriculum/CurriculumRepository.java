package au.edu.eq.questionbank.repository.curriculum;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Read-only lookup boundary for subjects, syllabus versions, and their
 * curriculum hierarchies.
 */
public interface CurriculumRepository {

	/**
	 * Lists subjects available in the curriculum repository.
	 *
	 * @return all available subjects
	 */
	List<Subject> findAllSubjects();

	/**
	 * Finds a curriculum code within one syllabus version.
	 *
	 * @param syllabusVersion the version in which the code is defined
	 * @param code            the syllabus classification code
	 * @return the matching node, or an empty optional
	 */
	Optional<CurriculumNode> findByCode(SyllabusVersion syllabusVersion, String code);

	/**
	 * Returns the immediate children of a curriculum node.
	 *
	 * @param parent the parent node
	 * @return its children in display order
	 */
	List<CurriculumNode> findChildren(CurriculumNode parent);

	/**
	 * Returns all reachable Subtopic and Descriptor nodes for one syllabus version.
	 *
	 * The default implementation traverses the existing hierarchy. Persistent
	 * repositories may override it with a bulk retrieval.
	 *
	 * @param syllabusVersion the syllabus version to inspect
	 * @return its Subtopics and Descriptors
	 * @throws NullPointerException if syllabusVersion is null
	 */
	default List<CurriculumNode> findReviewNodes(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		List<CurriculumNode> nodes = new ArrayList<>();
		for (CurriculumNode root : findRootNodes(syllabusVersion)) {
			collectReviewNodes(root, nodes);
		}
		return List.copyOf(nodes);
	}

	/**
	 * Returns the unit-level roots for a syllabus version.
	 *
	 * @param syllabusVersion the version whose units are required
	 * @return its root curriculum nodes in display order
	 */
	List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion);

	/**
	 * Looks up a subject by its persistent identity.
	 *
	 * @param id the persistent subject identifier
	 * @return the matching subject, or an empty optional
	 */
	Optional<Subject> findSubjectById(long id);

	/**
	 * Looks up a syllabus version by its persistent identity.
	 *
	 * @param id the persistent syllabus-version identifier
	 * @return the matching version, or an empty optional
	 */
	Optional<SyllabusVersion> findVersionById(long id);

	/**
	 * Returns the syllabus versions belonging to a subject.
	 *
	 * @param subject the subject whose versions are required
	 * @return the subject's syllabus versions
	 */
	List<SyllabusVersion> findVersionsForSubject(Subject subject);

	private void collectReviewNodes(CurriculumNode node, List<CurriculumNode> result) {
		if (node.getLevel() == CurriculumLevel.SUBTOPIC || node.getLevel() == CurriculumLevel.DESCRIPTOR) {
			result.add(node);
		}
		for (CurriculumNode child : findChildren(node)) {
			collectReviewNodes(child, result);
		}
	}
}
