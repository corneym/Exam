package au.edu.eq.questionbank.ui.model;

import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.CurriculumRepository;

public class CurriculumSelectionModel {

	private final CurriculumRepository repository;

	private Subject subject;
	private SyllabusVersion syllabusVersion;
	private CurriculumNode unit;
	private CurriculumNode topic;
	private CurriculumNode subtopic;

	public CurriculumSelectionModel(CurriculumRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository");
	}

	public List<Subject> getSubjects() {
		return repository.findAllSubjects();
	}

	public List<CurriculumNode> getUnits() {
		if (syllabusVersion == null) {
			return List.of();
		}

		return repository.findRootNodes(syllabusVersion);
	}

	public List<CurriculumNode> getTopics() {
		if (unit == null) {
			return List.of();
		}

		return repository.findChildren(unit);
	}

	public List<CurriculumNode> getSubtopics() {
		if (topic == null) {
			return List.of();
		}

		return repository.findChildren(topic);
	}

	public void selectSubject(Subject subject) {
		this.subject = subject;

		syllabusVersion = null;
		unit = null;
		topic = null;
		subtopic = null;

		if (subject == null) {
			return;
		}

		syllabusVersion = repository.findVersionsForSubject(subject).stream().filter(SyllabusVersion::isCurrent)
				.findFirst().orElseThrow(() -> new IllegalStateException(
						"No current syllabus version for subject: " + subject.getName()));
	}

	public void selectUnit(CurriculumNode unit) {
		this.unit = unit;
		topic = null;
		subtopic = null;
	}

	public void selectTopic(CurriculumNode topic) {
		this.topic = topic;
		subtopic = null;
	}

	public void selectSubtopic(CurriculumNode subtopic) {
		this.subtopic = subtopic;
	}

	public Subject getSubject() {
		return subject;
	}

	public SyllabusVersion getSyllabusVersion() {
		return syllabusVersion;
	}

	public CurriculumNode getUnit() {
		return unit;
	}

	public CurriculumNode getTopic() {
		return topic;
	}

	public CurriculumNode getSubtopic() {
		return subtopic;
	}
}