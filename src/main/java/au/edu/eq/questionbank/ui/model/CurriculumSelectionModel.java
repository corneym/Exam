package au.edu.eq.questionbank.ui.model;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.CurriculumRepository;

/**
 * Selection state for navigating a subject's current syllabus hierarchy.
 * <p>
 * Selecting a value clears all dependent selections beneath it. Selecting a
 * subject automatically chooses that subject's current syllabus version.
 */
public class CurriculumSelectionModel {

	private final CurriculumRepository repository;

	private Subject subject;
	private SyllabusVersion syllabusVersion;
	private CurriculumNode unit;
	private CurriculumNode topic;
	private CurriculumNode subtopic;

	/**
	 * Creates a selection model backed by curriculum lookups from a repository.
	 *
	 * @param repository the curriculum repository
	 * @throws NullPointerException if {@code repository} is {@code null}
	 */
	public CurriculumSelectionModel(CurriculumRepository repository) {
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		this.repository = repository;
	}

	/**
	 * Returns all subjects available for selection.
	 *
	 * @return all subjects available from the backing repository
	 */
	public List<Subject> getSubjects() {
		return repository.findAllSubjects();
	}

	/**
	 * @return units for the selected current syllabus, or an empty list when no
	 *         subject is selected
	 */
	public List<CurriculumNode> getUnits() {
		if (syllabusVersion == null) {
			return List.of();
		}

		return repository.findRootNodes(syllabusVersion);
	}

	/**
	 * @return children of the selected unit, or an empty list when no unit is
	 *         selected
	 */
	public List<CurriculumNode> getTopics() {
		if (unit == null) {
			return List.of();
		}

		return repository.findChildren(unit);
	}

	/**
	 * @return children of the selected topic, which may be subtopics or descriptors
	 *         attached directly to the topic; an empty list when no topic is
	 *         selected
	 */
	public List<CurriculumNode> getSubtopics() {
		if (topic == null) {
			return List.of();
		}

		return repository.findChildren(topic);
	}

	/**
	 * Selects a subject and its current syllabus version, clearing all curriculum
	 * node selections. Passing {@code null} clears the complete selection.
	 *
	 * @param subject the subject to select, or {@code null} to clear it
	 * @throws IllegalStateException if the subject has no current syllabus version
	 */
	public void selectSubject(Subject subject) {
		this.subject = null;
		syllabusVersion = null;
		unit = null;
		topic = null;
		subtopic = null;

		if (subject == null) {
			return;
		}

		List<SyllabusVersion> versions = repository.findVersionsForSubject(subject);
		for (SyllabusVersion version : versions) {
			if (version.isCurrent()) {
				this.subject = subject;
				syllabusVersion = version;
				return;
			}
		}

		throw new IllegalStateException("No current syllabus version for subject: " + subject.getName());
	}

	/**
	 * Selects a unit and clears the selected topic and subtopic.
	 *
	 * @param unit the unit to select, or {@code null} to clear it
	 */
	public void selectUnit(CurriculumNode unit) {
		this.unit = unit;
		topic = null;
		subtopic = null;
	}

	/**
	 * Selects a topic and clears the selected subtopic.
	 *
	 * @param topic the topic to select, or {@code null} to clear it
	 */
	public void selectTopic(CurriculumNode topic) {
		this.topic = topic;
		subtopic = null;
	}

	/**
	 * Selects the final curriculum node shown beneath the current topic.
	 *
	 * @param subtopic the node to select, or {@code null} to clear it
	 */
	public void selectSubtopic(CurriculumNode subtopic) {
		this.subtopic = subtopic;
	}

	/**
	 * Returns the selected subject.
	 *
	 * @return the selected subject, or {@code null} when no subject is selected
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Returns the current syllabus version for the selected subject.
	 *
	 * @return the selected subject's current syllabus version, or {@code null}
	 */
	public SyllabusVersion getSyllabusVersion() {
		return syllabusVersion;
	}

	/**
	 * Returns the selected unit.
	 *
	 * @return the selected unit, or {@code null}
	 */
	public CurriculumNode getUnit() {
		return unit;
	}

	/**
	 * Returns the selected topic.
	 *
	 * @return the selected topic, or {@code null}
	 */
	public CurriculumNode getTopic() {
		return topic;
	}

	/**
	 * Returns the selected final curriculum node.
	 *
	 * @return the selected final curriculum node, or {@code null}
	 */
	public CurriculumNode getSubtopic() {
		return subtopic;
	}
}
