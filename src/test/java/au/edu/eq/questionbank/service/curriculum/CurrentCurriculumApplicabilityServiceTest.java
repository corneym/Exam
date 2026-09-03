package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumMappingRepository;

class CurrentCurriculumApplicabilityServiceTest {

	@Test
	void currentClassificationIsAlreadyApplicable() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion currentVersion = new SyllabusVersion(1, chemistry, "2025", true);
		Unit unit = new Unit(1, currentVersion, "1", "Unit", 1);
		Topic topic = new Topic(2, currentVersion, unit, "1.1", "Topic", 1);
		Descriptor descriptor = new Descriptor(3, currentVersion, topic, "1.1.1", "Current descriptor", 1);

		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(
				new InMemoryCurriculumMappingRepository(List.of()));

		List<CurriculumNode> currentNodes = service.findCurrentNodes(descriptor);

		assertEquals(List.of(descriptor), currentNodes);
	}

	@Test
	void historicalClassificationWithoutConfirmedCurrentMappingHasNoCurrentApplicability() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit unit = new Unit(1, historicalVersion, "1", "Unit", 1);
		Topic topic = new Topic(2, historicalVersion, unit, "1.1", "Topic", 1);
		Descriptor source = new Descriptor(3, historicalVersion, topic, "1.1.1", "Historical descriptor", 1);

		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(
				new InMemoryCurriculumMappingRepository(List.of()));

		assertTrue(service.findCurrentNodes(source).isEmpty());
	}

	@Test
	void historicalDescriptorUsesOnlyConfirmedCurrentTargets() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor source = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1", "Historical descriptor", 1);

		SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, currentVersion, "2", "Current unit", 1);
		Topic currentTopic = new Topic(11, currentVersion, currentUnit, "2.1", "Current topic", 1);
		Descriptor confirmedCurrent = new Descriptor(12, currentVersion, currentTopic, "2.1.1", "Confirmed current", 1);
		Descriptor suggestedCurrent = new Descriptor(13, currentVersion, currentTopic, "2.1.2", "Suggested current", 2);
		Descriptor secondConfirmedCurrent = new Descriptor(14, currentVersion, currentTopic, "2.1.3",
				"Second confirmed current", 3);

		SyllabusVersion otherHistoricalVersion = new SyllabusVersion(3, chemistry, "2022", false);
		Unit otherUnit = new Unit(20, otherHistoricalVersion, "3", "Other unit", 1);
		Topic otherTopic = new Topic(21, otherHistoricalVersion, otherUnit, "3.1", "Other topic", 1);
		Descriptor confirmedNonCurrent = new Descriptor(22, otherHistoricalVersion, otherTopic, "3.1.1",
				"Non-current target", 1);

		InMemoryCurriculumMappingRepository mappingRepository = new InMemoryCurriculumMappingRepository(
				List.of(new CurriculumMapping(1, source, confirmedCurrent, MappingStatus.CONFIRMED),
						new CurriculumMapping(2, source, suggestedCurrent, MappingStatus.SUGGESTED),
						new CurriculumMapping(3, source, confirmedNonCurrent, MappingStatus.CONFIRMED),
						new CurriculumMapping(4, source, confirmedCurrent, MappingStatus.CONFIRMED),
						new CurriculumMapping(5, source, secondConfirmedCurrent, MappingStatus.CONFIRMED)));

		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(mappingRepository);

		List<CurriculumNode> currentNodes = service.findCurrentNodes(source);

		assertEquals(List.of(confirmedCurrent, secondConfirmedCurrent), currentNodes);
	}

	@Test
	void historicalSubtopicCanApplyToMultipleCurrentSubtopics() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
		Subtopic source = new Subtopic(3, historicalVersion, historicalTopic, "1.1.1", "Historical subtopic", 1);

		SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, currentVersion, "2", "Current unit", 1);
		Topic currentTopic = new Topic(11, currentVersion, currentUnit, "2.1", "Current topic", 1);
		Subtopic firstTarget = new Subtopic(12, currentVersion, currentTopic, "2.1.1", "First target", 1);
		Subtopic secondTarget = new Subtopic(13, currentVersion, currentTopic, "2.1.2", "Second target", 2);

		InMemoryCurriculumMappingRepository mappingRepository = new InMemoryCurriculumMappingRepository(
				List.of(new CurriculumMapping(1, source, firstTarget, MappingStatus.CONFIRMED),
						new CurriculumMapping(2, source, secondTarget, MappingStatus.CONFIRMED)));

		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(mappingRepository);

		List<CurriculumNode> currentNodes = service.findCurrentNodes(source);

		assertEquals(List.of(firstTarget, secondTarget), currentNodes);
	}

	@Test
	void questionRetainsHistoricalClassificationWhileReportingCurrentApplicability() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor historicalDescriptor = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1",
				"Historical descriptor", 1);

		SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, currentVersion, "2", "Current unit", 1);
		Topic currentTopic = new Topic(11, currentVersion, currentUnit, "2.1", "Current topic", 1);
		Descriptor currentDescriptor = new Descriptor(12, currentVersion, currentTopic, "2.1.1", "Current descriptor",
				1);

		InMemoryCurriculumMappingRepository mappingRepository = new InMemoryCurriculumMappingRepository(
				List.of(new CurriculumMapping(1, historicalDescriptor, currentDescriptor, MappingStatus.CONFIRMED)));

		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, chemistry, provider, 2020, "2020 Chemistry");
		SourceDocument sourceDocument = new SourceDocument(1, "Chemistry/QCAA/2020/paper1.pdf");
		ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);

		Question question = new Question(1, booklet, "3", "", 2, List.of(), historicalDescriptor, false);

		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(mappingRepository);

		List<CurriculumNode> currentNodes = service.findCurrentNodes(question);

		assertEquals(List.of(currentDescriptor), currentNodes);
		assertSame(historicalDescriptor, question.getClassification());
	}

	@Test
	void rejectsNullInputs() {
		InMemoryCurriculumMappingRepository repository = new InMemoryCurriculumMappingRepository(List.of());
		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(repository);

		assertThrows(NullPointerException.class, () -> new CurrentCurriculumApplicabilityService(null));
		assertThrows(NullPointerException.class, () -> service.findCurrentNodes((CurriculumNode) null));
		assertThrows(NullPointerException.class, () -> service.findCurrentNodes((Question) null));
	}
}
