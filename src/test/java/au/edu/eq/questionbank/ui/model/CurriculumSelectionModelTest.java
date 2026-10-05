package au.edu.eq.questionbank.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class CurriculumSelectionModelTest {

	private Subject chemistry;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;
	private Unit unit3;
	private Topic topic31;
	private Subtopic subtopic311;
	private Descriptor descriptor3111;
	private Unit historicalUnit;
	private Topic historicalTopic;
	private Descriptor historicalDescriptor;
	private CurriculumSelectionModel model;

	@Test
	void allowsSubjectWithOnlyHistoricalSyllabus() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion oldPhysics = new SyllabusVersion(3, physics, "2019", false);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(physics),
				List.of(oldPhysics), List.of());
		CurriculumSelectionModel physicsModel = new CurriculumSelectionModel(repository);
		physicsModel.selectSubject(physics);
		assertEquals(physics, physicsModel.getSubject());
		assertNull(physicsModel.getSyllabusVersion());
		assertEquals(List.of(oldPhysics), physicsModel.getSyllabusVersions());
		physicsModel.selectSyllabusVersion(oldPhysics);
		assertEquals(oldPhysics, physicsModel.getSyllabusVersion());
	}

	@Test
	void appliedSubjectSnapshotCachesRootUnitsForEverySyllabusVersion() {
		AtomicInteger rootReads = new AtomicInteger();
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, subtopic311, descriptor3111,
						historicalUnit, historicalTopic, historicalDescriptor)) {

			@Override
			public List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion) {

				// Count persistence-boundary calls so the test can distinguish snapshot
				// loading from later selection-model navigation.
				rootReads.incrementAndGet();
				return super.findRootNodes(syllabusVersion);
			}
		};
		CurriculumSelectionModel snapshotModel = new CurriculumSelectionModel(repository);
		CurriculumSelectionModel.SubjectSnapshot snapshot = snapshotModel.loadSubjectSnapshot(chemistry);

		// One worker-side read per syllabus loads both current and historical roots.
		assertEquals(2, rootReads.get());
		assertEquals(List.of(unit3), snapshot.currentUnits());
		assertEquals(List.of(historicalUnit), snapshot.unitsFor(syllabus2019));
		assertEquals(List.of(unit3), snapshot.unitsFor(syllabus2025));
		snapshotModel.beginSubjectRefresh(chemistry);
		snapshotModel.applySubjectSnapshot(snapshot);

		// Moving to the historical syllabus must reuse its already-loaded roots.
		snapshotModel.selectSyllabusVersion(syllabus2019);
		assertEquals(List.of(historicalUnit), snapshotModel.getUnits());

		// Moving back to the current syllabus likewise remains entirely in memory.
		snapshotModel.selectSyllabusVersion(syllabus2025);
		assertEquals(List.of(unit3), snapshotModel.getUnits());

		// No root-node reads occurred after snapshot publication.
		assertEquals(2, rootReads.get());
	}

	@Test
	void cascadesUnitTopicAndClassificationSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		assertEquals(List.of(topic31), model.getTopics());
		model.selectTopic(topic31);
		assertEquals(List.of(subtopic311), model.getClassifications());
		model.selectClassification(subtopic311);
		assertEquals(subtopic311, model.getClassification());
	}

	@Test
	void changingSubjectClearsLowerSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);
		model.selectSubject(null);
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(), model.getSyllabusVersions());
		assertEquals(List.of(), model.getUnits());
		assertEquals(List.of(), model.getTopics());
		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void changingTopicClearsClassificationSelection() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);
		model.selectTopic(null);
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void changingUnitClearsLowerSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);
		model.selectUnit(null);
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(), model.getTopics());
		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void clearingDescriptorRestoresSubtopicAsFinalClassification() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectSubtopic(subtopic311);
		model.selectDescriptor(descriptor3111);
		model.selectDescriptor(null);
		assertEquals(subtopic311, model.getSubtopic());
		assertNull(model.getDescriptor());
		assertEquals(subtopic311, model.getClassification());
	}

	@Test
	void clearingSyllabusRetainsSubjectButClearsHierarchy() {
		selectCurrentClassification();
		model.selectSyllabusVersion(null);
		assertEquals(chemistry, model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(syllabus2019, syllabus2025), model.getSyllabusVersions());
		assertEquals(List.of(), model.getUnits());
		assertEquals(List.of(), model.getTopics());
		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void codeLookupReturnsNullForBlankOrUnknownCode() {
		model.selectSubject(chemistry);
		assertNull(model.findByCode(null));
		assertNull(model.findByCode(" "));
		assertNull(model.findByCode("9.9.9"));
	}

	@Test
	void explicitlySelectsHistoricalSyllabus() {
		selectCurrentClassification();
		model.selectSyllabusVersion(syllabus2019);
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2019, model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(historicalUnit), model.getUnits());
		model.selectUnit(historicalUnit);
		assertEquals(List.of(historicalTopic), model.getTopics());
		model.selectTopic(historicalTopic);
		assertEquals(List.of(historicalDescriptor), model.getClassifications());
		model.selectClassification(historicalDescriptor);
		model.selectSyllabusVersion(syllabus2025);
		assertEquals(List.of(unit3), model.getUnits());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
	}

	@Test
	void exposesDescriptorsAttachedDirectlyToATopic() {
		Descriptor descriptor = new Descriptor(4, syllabus2025, topic31, "3.1.1", "Descriptor 3.1.1", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, descriptor));
		CurriculumSelectionModel descriptorModel = new CurriculumSelectionModel(repository);
		descriptorModel.selectSubject(chemistry);
		descriptorModel.selectUnit(unit3);
		descriptorModel.selectTopic(topic31);
		assertEquals(List.of(descriptor), descriptorModel.getClassifications());
		descriptorModel.selectClassification(descriptor);
		assertEquals(descriptor, descriptorModel.getClassification());
	}

	@Test
	void exposesSubjects() {
		assertEquals(List.of(chemistry), model.getSubjects());
	}

	@Test
	void exposesSubtopicsSeparatelyFromDescriptors() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		assertEquals(List.of(subtopic311), model.getSubtopics());
		assertEquals(List.of(), model.getDescriptors());
		model.selectSubtopic(subtopic311);
		assertEquals(List.of(descriptor3111), model.getDescriptors());
	}

	@Test
	void exposesSyllabusVersionsForSelectedSubject() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physics2019 = new SyllabusVersion(3, physics, "2019", false);
		model = new CurriculumSelectionModel(new InMemoryCurriculumRepository(List.of(physics, chemistry),
				List.of(physics2019, syllabus2019, syllabus2025), List.of()));
		assertEquals(List.of(), model.getSyllabusVersions());
		model.selectSubject(chemistry);
		assertEquals(List.of(syllabus2019, syllabus2025), model.getSyllabusVersions());
		model.selectSubject(physics);
		assertEquals(physics, model.getSubject());
		assertEquals(List.of(physics2019), model.getSyllabusVersions());
		assertNull(model.getSyllabusVersion());
		assertEquals(List.of(), model.getUnits());
	}

	@Test
	void exposesUnitsForDefaultSyllabus() {
		model.selectSubject(chemistry);
		assertEquals(List.of(unit3), model.getUnits());
	}

	@Test
	void findsTrimmedCodeOnlyWithinSelectedSyllabus() {
		assertNull(model.findByCode("3.1.1.1"));
		model.selectSubject(chemistry);
		assertEquals(descriptor3111, model.findByCode(" 3.1.1.1 "));
		assertNull(model.findByCode("1.1.1"));
		model.selectSyllabusVersion(syllabus2019);
		assertEquals(historicalDescriptor, model.findByCode("1.1.1"));
		assertNull(model.findByCode("3.1.1.1"));
	}

	@Test
	void rejectsForeignSubjectEvenWhenVersionIdentifierMatches() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion conflictingVersion = new SyllabusVersion(syllabus2025.getId(), physics, "2025", true);
		selectCurrentClassification();
		assertThrows(IllegalArgumentException.class, () -> model.selectSyllabusVersion(conflictingVersion));
		assertCurrentClassificationRetained();
	}

	@Test
	void rejectsSyllabusFromAnotherSubject() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physics2019 = new SyllabusVersion(3, physics, "2019", false);
		selectCurrentClassification();
		assertThrows(IllegalArgumentException.class, () -> model.selectSyllabusVersion(physics2019));
		assertCurrentClassificationRetained();
	}

	@Test
	void rejectsUnavailableVersionWithoutChangingSelection() {
		SyllabusVersion unavailable = new SyllabusVersion(99, chemistry, "Unimported", false);
		selectCurrentClassification();
		assertThrows(IllegalArgumentException.class, () -> model.selectSyllabusVersion(unavailable));
		assertCurrentClassificationRetained();
	}

	@Test
	void requiresSubjectBeforeSelectingSyllabus() {
		assertThrows(IllegalStateException.class, () -> model.selectSyllabusVersion(syllabus2019));
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertEquals(List.of(), model.getSyllabusVersions());
		model.selectSyllabusVersion(null);
		assertEquals(List.of(), model.getUnits());
	}

	@Test
	void selectClassificationReconstructsSubtopicPathForDescriptor() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(descriptor3111);
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(descriptor3111, model.getDescriptor());
		assertEquals(descriptor3111, model.getClassification());
	}

	@Test
	void selectingDescriptorBeneathSubtopicMakesDescriptorFinal() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectSubtopic(subtopic311);
		model.selectDescriptor(descriptor3111);
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(descriptor3111, model.getDescriptor());
		assertEquals(descriptor3111, model.getClassification());
	}

	@Test
	void selectingSubjectWithoutAnySyllabusesClearsPreviousHierarchy() {
		Subject biology = new Subject(3, "Biology");
		model = new CurriculumSelectionModel(new InMemoryCurriculumRepository(List.of(chemistry, biology),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, subtopic311)));
		selectCurrentClassification();
		model.selectSubject(biology);
		assertEquals(biology, model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(), model.getSyllabusVersions());
		assertEquals(List.of(), model.getUnits());
	}

	@Test
	void selectingSubtopicMakesItTheFinalClassification() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectSubtopic(subtopic311);
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(subtopic311, model.getClassification());
		assertNull(model.getDescriptor());
	}

	@Test
	void selectsCurrentSyllabusForSubject() {
		model.selectSubject(chemistry);
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2025, model.getSyllabusVersion());
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		unit3 = new Unit(1, syllabus2025, "3", "Unit 3", 1);
		topic31 = new Topic(2, syllabus2025, unit3, "3.1", "Topic 3.1", 1);
		subtopic311 = new Subtopic(3, syllabus2025, topic31, "3.1.1", "Subtopic 3.1.1", 1);
		descriptor3111 = new Descriptor(4, syllabus2025, subtopic311, "3.1.1.1", "Descriptor 3.1.1.1", 1);
		historicalUnit = new Unit(5, syllabus2019, "1", "Historical unit", 1);
		historicalTopic = new Topic(6, syllabus2019, historicalUnit, "1.1", "Historical topic", 1);
		historicalDescriptor = new Descriptor(7, syllabus2019, historicalTopic, "1.1.1", "Historical descriptor", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, subtopic311, descriptor3111,
						historicalUnit, historicalTopic, historicalDescriptor));
		model = new CurriculumSelectionModel(repository);
	}

	@Test
	void subjectSnapshotLoadsWithoutMutatingSelectionAndAppliesLater() {
		CurriculumSelectionModel.SubjectSnapshot snapshot = model.loadSubjectSnapshot(chemistry);

		// Persistence loading itself must be side-effect free so a worker thread can
		// construct the snapshot without touching current JavaFX-owned selection state.
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertEquals(List.of(syllabus2019, syllabus2025), snapshot.syllabusVersions());
		assertEquals(syllabus2025, snapshot.currentSyllabusVersion());
		assertEquals(List.of(unit3), snapshot.currentUnits());

		// The accepted Subject is established immediately before asynchronous data is
		// published.
		model.beginSubjectRefresh(chemistry);
		assertEquals(chemistry, model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertEquals(List.of(syllabus2019, syllabus2025), model.getSyllabusVersions());
		model.applySubjectSnapshot(snapshot);

		// Publication restores only the new Subject's initial syllabus context.
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2025, model.getSyllabusVersion());
		assertEquals(List.of(unit3), model.getUnits());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
	}

	private void assertCurrentClassificationRetained() {
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2025, model.getSyllabusVersion());
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertEquals(subtopic311, model.getClassification());
	}

	private void selectCurrentClassification() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);
	}
}
