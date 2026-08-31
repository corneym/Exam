package au.edu.eq.questionbank.ui.model;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.CurriculumRepository;

/**
 * Selection state for navigating a subject's syllabus hierarchy.
 * <p>
 * Selecting a subject defaults to its current syllabus version when one exists.
 * A historical syllabus version may then be selected explicitly. Selecting a
 * value clears all dependent selections beneath it.
 */
public class CurriculumSelectionModel {

	private final CurriculumRepository repository;

	private Subject subject;
	private SyllabusVersion syllabusVersion;
	private CurriculumNode unit;
	private CurriculumNode topic;
	private CurriculumNode classification;

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
	 * Returns the syllabus versions available for the selected subject.
	 *
	 * @return syllabus versions for the selected subject, or an empty list when no
	 *         subject is selected
	 */
	public List<SyllabusVersion> getSyllabusVersions() {
		if (subject == null) {
			return List.of();
		}
		return repository.findVersionsForSubject(subject);
	}

	/**
	 * @return units for the selected syllabus, or an empty list when no syllabus is
	 *         selected
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
	 * Returns the valid final classifications beneath the selected topic. These may
	 * be subtopics or descriptors.
	 *
	 * @return final classification nodes, or an empty list when no topic is
	 *         selected
	 */
	public List<CurriculumNode> getClassifications() {
		if (topic == null) {
			return List.of();
		}
		return repository.findChildren(topic);
	}

	/**
	 * Selects a subject, clears all curriculum node selections, and defaults to the
	 * subject's current syllabus version when one exists. Otherwise the subject is
	 * retained without a selected syllabus. Passing {@code null} clears the
	 * complete selection.
	 *
	 * @param subject the subject to select, or {@code null} to clear it
	 */
	public void selectSubject(Subject subject) {
		this.subject = subject;
		syllabusVersion = null;
		unit = null;
		topic = null;
		classification = null;
		if (subject == null) {
			return;
		}
		for (SyllabusVersion version : repository.findVersionsForSubject(subject)) {
			if (version.isCurrent()) {
				syllabusVersion = version;
				return;
			}
		}
	}

	/**
	 * Selects a syllabus version for the selected subject and clears all curriculum
	 * node selections beneath it. Rejected versions leave the selection unchanged.
	 *
	 * @param syllabusVersion the syllabus version to select, or {@code null} to
	 *                        clear the syllabus selection
	 * @throws IllegalStateException    if a non-null version is selected without a
	 *                                  subject
	 * @throws IllegalArgumentException if the version is not available for the
	 *                                  selected subject
	 */
	public void selectSyllabusVersion(SyllabusVersion syllabusVersion) {
		if (syllabusVersion != null) {
			if (subject == null) {
				throw new IllegalStateException("Select a subject before selecting a syllabus version");
			}
			if (!syllabusVersion.getSubject().equals(subject)
					|| !repository.findVersionsForSubject(subject).contains(syllabusVersion)) {
				throw new IllegalArgumentException("Syllabus version is not available for the selected subject");
			}
		}
		this.syllabusVersion = syllabusVersion;
		unit = null;
		topic = null;
		classification = null;
	}

	/**
	 * Selects a unit and clears the selected topic and final classification.
	 *
	 * @param unit the unit to select, or {@code null} to clear it
	 */
	public void selectUnit(CurriculumNode unit) {
		this.unit = unit;
		topic = null;
		classification = null;
	}

	/**
	 * Selects a topic and clears the selected final classification.
	 *
	 * @param topic the topic to select, or {@code null} to clear it
	 */
	public void selectTopic(CurriculumNode topic) {
		this.topic = topic;
		classification = null;
	}

	/**
	 * Selects the final curriculum node shown beneath the current topic.
	 *
	 * @param classification the node to select, or {@code null} to clear it
	 */
	public void selectClassification(CurriculumNode classification) {
		this.classification = classification;
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
	 * Returns the selected syllabus version.
	 *
	 * @return the selected syllabus version, or {@code null}
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
	 * Returns the selected final curriculum classification.
	 *
	 * @return the selected subtopic or descriptor, or {@code null}
	 */
	public CurriculumNode getClassification() {
		return classification;
	}
}
