package au.edu.eq.questionbank.ui.model;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

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
	private CurriculumNode subtopic;
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
	 * Finds a curriculum node by its stored code within the selected syllabus.
	 *
	 * @param code the curriculum code
	 * @return the matching node, or {@code null} when no syllabus is selected or
	 *         the code is unavailable
	 */
	public CurriculumNode findByCode(String code) {
		if (syllabusVersion == null || code == null || code.isBlank()) {
			return null;
		}
		return repository.findByCode(syllabusVersion, code.trim()).orElse(null);
	}

	/**
	 * Returns the selected final curriculum classification.
	 *
	 * @return the selected subtopic or descriptor, or {@code null}
	 */
	public CurriculumNode getClassification() {
		return classification;
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
	 * Returns the selected descriptor when the final classification is a
	 * descriptor.
	 *
	 * @return the selected descriptor, or {@code null}
	 */
	public CurriculumNode getDescriptor() {
		if (classification != null && classification.getLevel() == CurriculumLevel.DESCRIPTOR) {
			return classification;
		}
		return null;
	}

	/**
	 * Returns descriptors valid beneath the current topic path.
	 * <p>
	 * When a subtopic is selected, descriptors beneath that subtopic are returned.
	 * Otherwise descriptors directly beneath the selected topic are returned.
	 *
	 * @return descriptors available at the current hierarchy position
	 */
	public List<CurriculumNode> getDescriptors() {
		CurriculumNode parent = subtopic != null ? subtopic : topic;
		if (parent == null) {
			return List.of();
		}
		return repository.findChildren(parent).stream().filter(node -> node.getLevel() == CurriculumLevel.DESCRIPTOR)
				.toList();
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
	 * Returns all subjects available for selection.
	 *
	 * @return all subjects available from the backing repository
	 */
	public List<Subject> getSubjects() {
		return repository.findAllSubjects();
	}

	/**
	 * Returns the selected subtopic.
	 *
	 * @return the selected subtopic, or {@code null}
	 */
	public CurriculumNode getSubtopic() {
		return subtopic;
	}

	/**
	 * Returns the subtopics immediately beneath the selected topic.
	 *
	 * @return subtopics beneath the selected topic, or an empty list
	 */
	public List<CurriculumNode> getSubtopics() {
		if (topic == null) {
			return List.of();
		}
		return repository.findChildren(topic).stream().filter(node -> node.getLevel() == CurriculumLevel.SUBTOPIC)
				.toList();
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
	 * Returns the selected topic.
	 *
	 * @return the selected topic, or {@code null}
	 */
	public CurriculumNode getTopic() {
		return topic;
	}

	/**
	 * Lists topics beneath the currently selected unit.
	 *
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
	 * Returns the selected unit.
	 *
	 * @return the selected unit, or {@code null}
	 */
	public CurriculumNode getUnit() {
		return unit;
	}

	/**
	 * Lists units belonging to the currently selected syllabus.
	 *
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
	 * Selects the final curriculum node shown beneath the current topic.
	 *
	 * @param classification the node to select, or {@code null} to clear it
	 */
	public void selectClassification(CurriculumNode classification) {
		if (classification == null) {
			subtopic = null;
			this.classification = null;
			return;
		}
		if (classification.getLevel() == CurriculumLevel.SUBTOPIC) {
			subtopic = classification;
			this.classification = classification;
			return;
		}
		if (classification.getLevel() == CurriculumLevel.DESCRIPTOR) {
			CurriculumNode parent = classification.getParent();
			subtopic = parent != null && parent.getLevel() == CurriculumLevel.SUBTOPIC ? parent : null;
			this.classification = classification;
			return;
		}
		throw new IllegalArgumentException("Final classification must be a subtopic or descriptor");
	}

	/**
	 * Selects a descriptor as the final classification. Clearing a descriptor
	 * restores the selected subtopic as the final classification when one exists.
	 *
	 * @param descriptor the descriptor to select, or {@code null} to clear it
	 */
	public void selectDescriptor(CurriculumNode descriptor) {
		if (descriptor != null && descriptor.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Expected a descriptor");
		}
		classification = descriptor != null ? descriptor : subtopic;
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
		subtopic = null;
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
	 * Selects a subtopic. A subtopic is itself a valid final classification until a
	 * descriptor beneath it is selected.
	 *
	 * @param subtopic the subtopic to select, or {@code null} to clear it
	 */
	public void selectSubtopic(CurriculumNode subtopic) {
		if (subtopic != null && subtopic.getLevel() != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("Expected a subtopic");
		}
		this.subtopic = subtopic;
		classification = subtopic;
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
		subtopic = null;
		classification = null;
	}

	/**
	 * Selects a topic and clears the selected final classification.
	 *
	 * @param topic the topic to select, or {@code null} to clear it
	 */
	public void selectTopic(CurriculumNode topic) {
		this.topic = topic;
		subtopic = null;
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
		subtopic = null;
		classification = null;
	}
}
