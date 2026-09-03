package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class ConfirmedDescriptorSubtopicMappingSuggesterTest {

	@Test
	void countsOneSourceDescriptorOnlyOncePerTargetSubtopicAndIgnoresSuggestedMappings() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Source unit", 1);
		Topic sourceTopic = new Topic(2, sourceVersion, sourceUnit, "1.1", "Source topic", 1);
		Subtopic sourceSubtopic = new Subtopic(3, sourceVersion, sourceTopic, "1.1.1", "Source subtopic", 1);
		Descriptor sourceDescriptor1 = new Descriptor(4, sourceVersion, sourceSubtopic, "1.1.1.1",
				"Source descriptor 1", 1);
		Descriptor sourceDescriptor2 = new Descriptor(5, sourceVersion, sourceSubtopic, "1.1.1.2",
				"Source descriptor 2", 2);

		SyllabusVersion targetVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit targetUnit = new Unit(10, targetVersion, "2", "Target unit", 1);
		Topic targetTopic = new Topic(11, targetVersion, targetUnit, "2.1", "Target topic", 1);
		Subtopic targetSubtopic = new Subtopic(12, targetVersion, targetTopic, "2.1.1", "Target subtopic", 1);
		Descriptor targetDescriptor1 = new Descriptor(13, targetVersion, targetSubtopic, "2.1.1.1",
				"Target descriptor 1", 1);
		Descriptor targetDescriptor2 = new Descriptor(14, targetVersion, targetSubtopic, "2.1.1.2",
				"Target descriptor 2", 2);

		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(sourceVersion, targetVersion),
				List.of(sourceUnit, sourceTopic, sourceSubtopic, sourceDescriptor1, sourceDescriptor2, targetUnit,
						targetTopic, targetSubtopic, targetDescriptor1, targetDescriptor2));

		InMemoryCurriculumMappingRepository mappingRepository = new InMemoryCurriculumMappingRepository(
				List.of(new CurriculumMapping(1, sourceDescriptor1, targetDescriptor1, MappingStatus.CONFIRMED),
						new CurriculumMapping(2, sourceDescriptor1, targetDescriptor2, MappingStatus.CONFIRMED),
						new CurriculumMapping(3, sourceDescriptor2, targetDescriptor1, MappingStatus.SUGGESTED)));

		ConfirmedDescriptorSubtopicMappingSuggester suggester = new ConfirmedDescriptorSubtopicMappingSuggester(
				curriculumRepository, mappingRepository);

		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(sourceSubtopic, targetVersion);

		assertEquals(1, suggestions.size());
		assertEquals(targetSubtopic, suggestions.get(0).getTarget());
		assertEquals(0.5, suggestions.get(0).getScore(), 0.000001);
	}

	@Test
	void ranksSubtopicsByConfirmedDescriptorEvidence() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Source unit", 1);
		Topic sourceTopic = new Topic(2, sourceVersion, sourceUnit, "1.1", "Source topic", 1);
		Subtopic sourceSubtopic = new Subtopic(3, sourceVersion, sourceTopic, "1.1.1", "Source subtopic", 1);
		Descriptor sourceDescriptor1 = new Descriptor(4, sourceVersion, sourceSubtopic, "1.1.1.1",
				"Source descriptor 1", 1);
		Descriptor sourceDescriptor2 = new Descriptor(5, sourceVersion, sourceSubtopic, "1.1.1.2",
				"Source descriptor 2", 2);
		Descriptor sourceDescriptor3 = new Descriptor(6, sourceVersion, sourceSubtopic, "1.1.1.3",
				"Source descriptor 3", 3);

		SyllabusVersion targetVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit targetUnit = new Unit(10, targetVersion, "2", "Target unit", 1);
		Topic targetTopic = new Topic(11, targetVersion, targetUnit, "2.1", "Target topic", 1);
		Subtopic strongTarget = new Subtopic(12, targetVersion, targetTopic, "2.1.1", "Strong target", 1);
		Descriptor strongDescriptor1 = new Descriptor(13, targetVersion, strongTarget, "2.1.1.1", "Strong descriptor 1",
				1);
		Descriptor strongDescriptor2 = new Descriptor(14, targetVersion, strongTarget, "2.1.1.2", "Strong descriptor 2",
				2);
		Subtopic weakerTarget = new Subtopic(15, targetVersion, targetTopic, "2.1.2", "Weaker target", 2);
		Descriptor weakerDescriptor = new Descriptor(16, targetVersion, weakerTarget, "2.1.2.1", "Weaker descriptor",
				1);

		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(sourceVersion, targetVersion),
				List.of(sourceUnit, sourceTopic, sourceSubtopic, sourceDescriptor1, sourceDescriptor2,
						sourceDescriptor3, targetUnit, targetTopic, strongTarget, strongDescriptor1, strongDescriptor2,
						weakerTarget, weakerDescriptor));

		InMemoryCurriculumMappingRepository mappingRepository = new InMemoryCurriculumMappingRepository(
				List.of(new CurriculumMapping(1, sourceDescriptor1, strongDescriptor1, MappingStatus.CONFIRMED),
						new CurriculumMapping(2, sourceDescriptor2, strongDescriptor2, MappingStatus.CONFIRMED),
						new CurriculumMapping(3, sourceDescriptor3, weakerDescriptor, MappingStatus.CONFIRMED)));

		ConfirmedDescriptorSubtopicMappingSuggester suggester = new ConfirmedDescriptorSubtopicMappingSuggester(
				curriculumRepository, mappingRepository);

		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(sourceSubtopic, targetVersion);

		assertEquals(2, suggestions.size());
		assertEquals(strongTarget, suggestions.get(0).getTarget());
		assertEquals(2.0 / 3.0, suggestions.get(0).getScore(), 0.000001);
		assertEquals(weakerTarget, suggestions.get(1).getTarget());
		assertEquals(1.0 / 3.0, suggestions.get(1).getScore(), 0.000001);
	}

	@Test
	void rejectsNonSubtopicSource() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion targetVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Source unit", 1);
		Unit targetUnit = new Unit(2, targetVersion, "1", "Target unit", 1);

		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(sourceVersion, targetVersion), List.of(sourceUnit, targetUnit));

		InMemoryCurriculumMappingRepository mappingRepository = new InMemoryCurriculumMappingRepository(List.of());

		ConfirmedDescriptorSubtopicMappingSuggester suggester = new ConfirmedDescriptorSubtopicMappingSuggester(
				curriculumRepository, mappingRepository);

		assertThrows(IllegalArgumentException.class, () -> suggester.suggest(sourceUnit, targetVersion));
	}
}