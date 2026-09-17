package au.edu.eq.questionbank.repository.curriculum;

import java.util.List;
import java.util.Optional;

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
	 * Looks up a subject by its persistent identity.
	 *
	 * @param id the persistent subject identifier
	 * @return the matching subject, or an empty optional
	 */
	Optional<Subject> findSubjectById(long id);

	/**
	 * Returns the syllabus versions belonging to a subject.
	 *
	 * @param subject the subject whose versions are required
	 * @return the subject's syllabus versions
	 */
	List<SyllabusVersion> findVersionsForSubject(Subject subject);

	/**
	 * Looks up a syllabus version by its persistent identity.
	 *
	 * @param id the persistent syllabus-version identifier
	 * @return the matching version, or an empty optional
	 */
	Optional<SyllabusVersion> findVersionById(long id);

	/**
	 * Returns the unit-level roots for a syllabus version.
	 *
	 * @param syllabusVersion the version whose units are required
	 * @return its root curriculum nodes in display order
	 */
	List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion);

	/**
	 * Returns the immediate children of a curriculum node.
	 *
	 * @param parent the parent node
	 * @return its children in display order
	 */
	List<CurriculumNode> findChildren(CurriculumNode parent);

	/**
	 * Finds a curriculum code within one syllabus version.
	 *
	 * @param syllabusVersion the version in which the code is defined
	 * @param code            the syllabus classification code
	 * @return the matching node, or an empty optional
	 */
	Optional<CurriculumNode> findByCode(SyllabusVersion syllabusVersion, String code);
}
