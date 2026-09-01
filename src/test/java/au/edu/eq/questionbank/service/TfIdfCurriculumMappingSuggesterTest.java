package au.edu.eq.questionbank.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
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
}
