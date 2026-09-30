package au.edu.eq.questionbank.ui.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	// A successfully loaded Subject snapshot supplies the Subject-transition
	// choices without repeating their persistence reads on the JavaFX thread.
	private SubjectSnapshot subjectSnapshot;

	// The desktop application owns asynchronous Working Subject transitions. A
	// standalone selector keeps its existing synchronous Subject behaviour.
	private boolean subjectRefreshManagedExternally;

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

	private static boolean isDescriptor(CurriculumNode node) {
		return node.getLevel() == CurriculumLevel.DESCRIPTOR;
	}

	private static boolean isSubtopic(CurriculumNode node) {
		return node.getLevel() == CurriculumLevel.SUBTOPIC;
	}

	/**
	 * Publishes a previously loaded Subject snapshot into this selection model.
	 *
	 * @param snapshot snapshot for the already accepted Subject
	 * @throws NullPointerException  if {@code snapshot} is {@code null}
	 * @throws IllegalStateException if the model has since moved to another Subject
	 */
	public void applySubjectSnapshot(SubjectSnapshot snapshot) {
		if (snapshot == null) {
			throw new NullPointerException("snapshot");
		}
		if (!snapshot.subject().equals(subject)) {

			// Application generation checking should normally discard stale work before
			// this boundary, but the model also protects itself from cross-Subject data.
			throw new IllegalStateException("Curriculum snapshot does not match the selected Subject");
		}
		subjectSnapshot = snapshot;
		syllabusVersion = snapshot.currentSyllabusVersion();

		// A newly published Subject snapshot starts with no Question classification.
		clearCurriculumNodeSelections();
	}

	/**
	 * Establishes the new Subject immediately while clearing curriculum state that
	 * belonged to the previous Subject.
	 *
	 * @param subject newly accepted Subject, or {@code null} to clear it
	 */
	public void beginSubjectRefresh(Subject subject) {
		this.subject = subject;
		subjectSnapshot = null;
		syllabusVersion = null;

		// Nothing beneath Subject may survive while replacement persistence data is
		// loading.
		clearCurriculumNodeSelections();
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

		// A syllabus may place Descriptors directly under Topics, with no Subtopic
		// level.
		CurriculumNode parent = subtopic != null ? subtopic : topic;
		if (parent == null) {
			return List.of();
		}
		return repository.findChildren(parent).stream().filter(CurriculumSelectionModel::isDescriptor).toList();
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
		return repository.findChildren(topic).stream().filter(CurriculumSelectionModel::isSubtopic).toList();
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
		if (subjectSnapshot != null && subjectSnapshot.subject().equals(subject)) {

			// The accepted Working Subject already supplied these values off-thread.
			return subjectSnapshot.syllabusVersions();
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
		if (subjectSnapshot != null && subjectSnapshot.subject().equals(subject)
				&& subjectSnapshot.syllabusVersions().contains(syllabusVersion)) {

			// All root Units for this Working Subject were loaded before snapshot
			// publication, including roots belonging to historical syllabuses.
			return subjectSnapshot.unitsFor(syllabusVersion);
		}

		// Standalone model use without an applied Subject snapshot retains the existing
		// repository-backed behaviour.
		return repository.findRootNodes(syllabusVersion);
	}

	/**
	 * Loads the persistence-backed curriculum state needed when a Subject becomes
	 * the Working Subject. This method changes no selection state and may therefore
	 * be called by an application-owned background task.
	 *
	 * @param subject Subject whose initial curriculum state is required
	 * @return immutable Subject snapshot
	 * @throws NullPointerException if {@code subject} is {@code null}
	 */
	public SubjectSnapshot loadSubjectSnapshot(Subject subject) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		List<SyllabusVersion> versions = List.copyOf(repository.findVersionsForSubject(subject));
		SyllabusVersion currentVersion = null;
		Map<Long, List<CurriculumNode>> unitsByVersionId = new HashMap<>();
		for (SyllabusVersion version : versions) {
			if (currentVersion == null && version.isCurrent()) {

				// Preserve the established rule that the first current version becomes
				// the default selection.
				currentVersion = version;
			}

			// Root Units for every available version are loaded while this method is
			// running on the application worker. Later syllabus switching therefore
			// requires no SQLite root-node lookup on the JavaFX thread.
			unitsByVersionId.put(version.getId(), List.copyOf(repository.findRootNodes(version)));
		}
		return new SubjectSnapshot(subject, versions, currentVersion, unitsByVersionId);
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

			// Recover the optional Subtopic from the chosen Descriptor, clearing any stale
			// Subtopic.
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

		// Clearing the finer selection retains the broader Subtopic classification when
		// available.
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
		beginSubjectRefresh(subject);
		if (subject == null) {
			return;
		}

		// Standalone selector users retain synchronous behaviour, but now use exactly
		// the same immutable snapshot contract as the application-level async path.
		applySubjectSnapshot(loadSubjectSnapshot(subject));
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

		// Validate before mutating the path so a rejected version leaves the user's
		// selection intact.
		if (syllabusVersion != null) {
			if (subject == null) {
				throw new IllegalStateException("Select a subject before selecting a syllabus version");
			}
			if (!syllabusVersion.getSubject().equals(subject) || !getSyllabusVersions().contains(syllabusVersion)) {
				throw new IllegalArgumentException("Syllabus version is not available for the selected subject");
			}
		}
		this.syllabusVersion = syllabusVersion;
		clearCurriculumNodeSelections();
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

	/**
	 * Chooses whether Subject-dependent persistence refresh is coordinated by the
	 * containing application rather than by this pane's synchronous action handler.
	 *
	 * @param managedExternally whether the application owns Subject refresh
	 */
	public void setSubjectRefreshManagedExternally(boolean managedExternally) {

		// Only Subject transition ownership changes; ordinary classification controls
		// continue to be handled by this pane.
		subjectRefreshManagedExternally = managedExternally;
	}

	private void clearCurriculumNodeSelections() {
		unit = null;
		topic = null;
		subtopic = null;
		classification = null;
	}

	/**
	 * Immutable persistence snapshot needed to establish a Subject's initial
	 * curriculum-selection state.
	 *
	 * @param subject                  Subject represented by the snapshot
	 * @param syllabusVersions         available syllabus versions for that Subject
	 * @param currentSyllabusVersion   current version, or {@code null} when none is
	 *                                 marked current
	 * @param unitsBySyllabusVersionId root Units for every available syllabus
	 *                                 version, keyed by persistent syllabus-version
	 *                                 identifier
	 */
	public record SubjectSnapshot(Subject subject, List<SyllabusVersion> syllabusVersions,
			SyllabusVersion currentSyllabusVersion, Map<Long, List<CurriculumNode>> unitsBySyllabusVersionId) {

		/**
		 * Validates and freezes the persistence data carried across the
		 * worker-to-JavaFX boundary.
		 */
		public SubjectSnapshot {
			if (subject == null) {
				throw new NullPointerException("subject");
			}
			if (syllabusVersions == null) {
				throw new NullPointerException("syllabusVersions");
			}
			if (unitsBySyllabusVersionId == null) {
				throw new NullPointerException("unitsBySyllabusVersionId");
			}

			// Freeze the version list before the snapshot crosses the worker-to-JavaFX
			// boundary.
			syllabusVersions = List.copyOf(syllabusVersions);
			Map<Long, List<CurriculumNode>> copiedUnitsByVersionId = new HashMap<>();
			for (SyllabusVersion version : syllabusVersions) {
				if (!version.getSubject().equals(subject)) {
					throw new IllegalArgumentException("Syllabus version does not belong to the snapshot Subject");
				}
				List<CurriculumNode> roots = unitsBySyllabusVersionId.get(version.getId());
				if (roots == null) {
					throw new IllegalArgumentException(
							"Snapshot is missing root Units for syllabus version " + version.getId());
				}

				// Root data is validated once before it is reused on the JavaFX thread.
				for (CurriculumNode root : roots) {
					if (!root.getSyllabusVersion().equals(version) || root.getLevel() != CurriculumLevel.UNIT
							|| root.getParent() != null) {
						throw new IllegalArgumentException(
								"Snapshot contains an invalid root Unit for syllabus version " + version.getId());
					}
				}
				copiedUnitsByVersionId.put(version.getId(), List.copyOf(roots));
			}
			if (copiedUnitsByVersionId.size() != unitsBySyllabusVersionId.size()) {

				// A snapshot may contain roots only for versions that belong to this
				// Subject.
				throw new IllegalArgumentException("Snapshot contains Units for an unavailable syllabus version");
			}
			unitsBySyllabusVersionId = Map.copyOf(copiedUnitsByVersionId);
			if (currentSyllabusVersion != null && (!currentSyllabusVersion.getSubject().equals(subject)
					|| !syllabusVersions.contains(currentSyllabusVersion))) {
				throw new IllegalArgumentException("Current syllabus does not belong to the snapshot Subject");
			}
		}

		/**
		 * Returns root Units belonging to the snapshot's current syllabus.
		 *
		 * @return current-syllabus root Units, or an empty list when there is no
		 *         current syllabus
		 */
		public List<CurriculumNode> currentUnits() {

			// Keep current-version access explicit because the initial pane publication
			// uses this value directly.
			return unitsFor(currentSyllabusVersion);
		}

		/**
		 * Returns the already-loaded root Units for one available syllabus version.
		 *
		 * @param syllabusVersion syllabus whose root Units are required
		 * @return immutable root-Unit list, or an empty list when the supplied version
		 *         is null or unavailable in this snapshot
		 */
		public List<CurriculumNode> unitsFor(SyllabusVersion syllabusVersion) {
			if (syllabusVersion == null || !syllabusVersion.getSubject().equals(subject)
					|| !syllabusVersions.contains(syllabusVersion)) {
				return List.of();
			}

			// Every available version was loaded when this snapshot was built, so this
			// is an in-memory lookup only.
			return unitsBySyllabusVersionId.getOrDefault(syllabusVersion.getId(), List.of());
		}
	}
}
