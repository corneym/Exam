package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CurriculumMappingSuggestionTest {
	private Subject chemistry;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;
	private Unit unit2019;
	private Unit unit2025;
	private Topic topic2025;

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		unit2019 = new Unit(1, syllabus2019, "3", "Old Unit", 1);
		unit2025 = new Unit(2, syllabus2025, "4", "New Unit", 1);
		topic2025 = new Topic(3, syllabus2025, unit2025, "4.1", "New Topic", 1);
	}

	@Test
	void representsCandidateMapping() {
		CurriculumMappingSuggestion suggestion = new CurriculumMappingSuggestion(unit2019, unit2025, 0.82);
		assertEquals(unit2019, suggestion.getSource());
		assertEquals(unit2025, suggestion.getTarget());
		assertEquals(0.82, suggestion.getScore());
	}

	@Test
	void rejectsDifferentCurriculumLevels() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumMappingSuggestion(unit2019, topic2025, 0.5));
	}

	@Test
	void rejectsSameSyllabusVersion() {
		Unit another2019Unit = new Unit(4, syllabus2019, "4", "Another old unit", 2);
		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMappingSuggestion(unit2019, another2019Unit, 0.5));
	}

	@Test
	void rejectsDifferentSubjects() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physics2025 = new SyllabusVersion(3, physics, "2025", true);
		Unit physicsUnit = new Unit(5, physics2025, "1", "Physics Unit", 1);
		assertThrows(IllegalArgumentException.class, () -> new CurriculumMappingSuggestion(unit2019, physicsUnit, 0.5));
	}

	@Test
	void rejectsScoreBelowZero() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumMappingSuggestion(unit2019, unit2025, -0.01));
	}

	@Test
	void rejectsScoreAboveOne() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumMappingSuggestion(unit2019, unit2025, 1.01));
	}

	@Test
	void rejectsNonFiniteScore() {
		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMappingSuggestion(unit2019, unit2025, Double.NaN));
	}
}
