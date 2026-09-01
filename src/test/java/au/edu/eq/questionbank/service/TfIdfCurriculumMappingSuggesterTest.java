package au.edu.eq.questionbank.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.InMemoryCurriculumRepository;

class TfIdfCurriculumMappingSuggesterTest {
	@Test
	void ranksRelatedDescriptorAheadOfUnrelatedDescriptor() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		Unit unit2019 = new Unit(1, syllabus2019, "1", "Chemical equilibrium", 1);
		Topic topic2019 = new Topic(2, syllabus2019, unit2019, "1.1", "Equilibrium systems", 1);
		Descriptor source = new Descriptor(3, syllabus2019, topic2019, "1.1.1",
				"explain the effect of concentration on chemical equilibrium", 1);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit2025 = new Unit(4, syllabus2025, "2", "Chemical equilibrium", 1);
		Topic topic2025 = new Topic(5, syllabus2025, unit2025, "2.1", "Equilibrium systems", 1);
		Descriptor related = new Descriptor(6, syllabus2025, topic2025, "2.1.1",
				"analyse concentration changes in chemical equilibrium", 1);
		Descriptor unrelated = new Descriptor(7, syllabus2025, topic2025, "2.1.2",
				"describe energy transfer during combustion", 2);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025),
				List.of(unit2019, topic2019, source, unit2025, topic2025, related, unrelated));
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);
		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(source, syllabus2025);
		assertEquals(2, suggestions.size());
		assertEquals(related, suggestions.getFirst().getTarget());
		assertTrue(suggestions.getFirst().getScore() > suggestions.get(1).getScore());
	}

	@Test
	void findsDescriptorsDirectlyUnderTopicAndUnderSubtopic() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		Unit unit2019 = new Unit(1, syllabus2019, "1", "Equilibrium", 1);
		Topic topic2019 = new Topic(2, syllabus2019, unit2019, "1.1", "Equilibrium systems", 1);
		Descriptor source = new Descriptor(3, syllabus2019, topic2019, "1.1.1",
				"explain how concentration affects chemical equilibrium", 1);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit2025 = new Unit(4, syllabus2025, "2", "Equilibrium", 1);
		Topic topic2025 = new Topic(5, syllabus2025, unit2025, "2.1", "Equilibrium systems", 1);
		Descriptor directDescriptor = new Descriptor(6, syllabus2025, topic2025, "2.1.1",
				"describe energy changes in equilibrium systems", 1);
		Subtopic subtopic2025 = new Subtopic(7, syllabus2025, topic2025, "2.1.2", "Factors affecting equilibrium", 2);
		Descriptor nestedDescriptor = new Descriptor(8, syllabus2025, subtopic2025, "2.1.2.1",
				"analyse how concentration changes affect chemical equilibrium", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025), List.of(unit2019, topic2019, source, unit2025, topic2025,
						directDescriptor, subtopic2025, nestedDescriptor));
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);
		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(source, syllabus2025);
		assertEquals(2, suggestions.size());
		assertEquals(nestedDescriptor, suggestions.getFirst().getTarget());
		boolean foundDirect = false;
		boolean foundNested = false;
		for (CurriculumMappingSuggestion suggestion : suggestions) {
			if (directDescriptor.equals(suggestion.getTarget())) {
				foundDirect = true;
			}
			if (nestedDescriptor.equals(suggestion.getTarget())) {
				foundNested = true;
			}
		}
		assertTrue(foundDirect);
		assertTrue(foundNested);
	}

	@Test
	void hierarchyContextBreaksTieBetweenIdenticalDescriptorText() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "Source", false);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Matter", 1);
		Topic sourceTopic = new Topic(2, sourceVersion, sourceUnit, "1.1", "Particle theory", 1);
		Descriptor source = new Descriptor(3, sourceVersion, sourceTopic, "1.1.1", "model particle behaviour", 1);
		SyllabusVersion targetVersion = new SyllabusVersion(2, chemistry, "Target", true);
		Unit unrelatedUnit = new Unit(4, targetVersion, "1", "Energy", 1);
		Topic unrelatedTopic = new Topic(5, targetVersion, unrelatedUnit, "1.1", "Heat transfer", 1);
		Descriptor unrelatedContext = new Descriptor(6, targetVersion, unrelatedTopic, "1.1.1",
				"model particle behaviour", 1);
		Unit relatedUnit = new Unit(7, targetVersion, "2", "Matter", 2);
		Topic relatedTopic = new Topic(8, targetVersion, relatedUnit, "2.1", "Particle theory", 1);
		Descriptor relatedContext = new Descriptor(9, targetVersion, relatedTopic, "2.1.1",
				"model particle behaviour", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(sourceVersion, targetVersion), List.of(sourceUnit, sourceTopic, source, unrelatedUnit,
						unrelatedTopic, unrelatedContext, relatedUnit, relatedTopic, relatedContext));
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);

		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(source, targetVersion);

		assertEquals(relatedContext, suggestions.getFirst().getTarget());
		assertTrue(suggestions.getFirst().getScore() > suggestions.get(1).getScore());
	}

	@Test
	void returnsAtMostFiveDirectionalTargetsFromSelectedVersion() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "Source", false);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Source unit", 1);
		Topic sourceTopic = new Topic(2, sourceVersion, sourceUnit, "1.1", "Source topic", 1);
		Descriptor source = new Descriptor(3, sourceVersion, sourceTopic, "1.1.1", "reaction energy", 1);
		SyllabusVersion targetVersion = new SyllabusVersion(2, chemistry, "Target", true);
		Unit targetUnit = new Unit(10, targetVersion, "1", "Target unit", 1);
		Topic targetTopic = new Topic(11, targetVersion, targetUnit, "1.1", "Target topic", 1);
		List<CurriculumNode> nodes = new ArrayList<>();
		nodes.add(sourceUnit);
		nodes.add(sourceTopic);
		nodes.add(source);
		nodes.add(targetUnit);
		nodes.add(targetTopic);
		for (int index = 0; index < 6; index++) {
			nodes.add(new Descriptor(20 + index, targetVersion, targetTopic, "1.1." + (index + 1),
					"reaction energy candidate " + index, index));
		}
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(sourceVersion, targetVersion), nodes);
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);

		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(source, targetVersion);

		assertEquals(5, suggestions.size());
		for (CurriculumMappingSuggestion suggestion : suggestions) {
			assertEquals(source, suggestion.getSource());
			assertEquals(targetVersion, suggestion.getTarget().getSyllabusVersion());
		}
	}

	@Test
	void rejectsNonDescriptorSource() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "Source", false);
		SyllabusVersion targetVersion = new SyllabusVersion(2, chemistry, "Target", true);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Source unit", 1);
		Unit targetUnit = new Unit(2, targetVersion, "1", "Target unit", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(sourceVersion, targetVersion), List.of(sourceUnit, targetUnit));
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);

		assertThrows(IllegalArgumentException.class, () -> suggester.suggest(sourceUnit, targetVersion));
	}

	@Test
	void rejectsTargetVersionFromDifferentSubject() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "Source", false);
		Unit sourceUnit = new Unit(1, sourceVersion, "1", "Source unit", 1);
		Topic sourceTopic = new Topic(2, sourceVersion, sourceUnit, "1.1", "Source topic", 1);
		Descriptor source = new Descriptor(3, sourceVersion, sourceTopic, "1.1.1", "Source descriptor", 1);
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physicsVersion = new SyllabusVersion(2, physics, "Target", true);
		Unit physicsUnit = new Unit(4, physicsVersion, "1", "Physics unit", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry, physics),
				List.of(sourceVersion, physicsVersion), List.of(sourceUnit, sourceTopic, source, physicsUnit));
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);

		assertThrows(IllegalArgumentException.class, () -> suggester.suggest(source, physicsVersion));
	}

	@Test
	void rejectsSourceVersionAsTargetVersion() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion version = new SyllabusVersion(1, chemistry, "Source", false);
		Unit unit = new Unit(1, version, "1", "Unit", 1);
		Topic topic = new Topic(2, version, unit, "1.1", "Topic", 1);
		Descriptor source = new Descriptor(3, version, topic, "1.1.1", "Descriptor", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(version), List.of(unit, topic, source));
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);

		assertThrows(IllegalArgumentException.class, () -> suggester.suggest(source, version));
	}
}
