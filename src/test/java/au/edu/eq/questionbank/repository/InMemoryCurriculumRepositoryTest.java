package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class InMemoryCurriculumRepositoryTest {

	private Subject chemistry;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;
	private CurriculumNode unit3;
	private CurriculumNode topic31;
	private CurriculumNode subtopic311;

	private CurriculumRepository repository;

	@Test
	void doesNotFindCodeInWrongVersion() {
		assertTrue(repository.findByCode(syllabus2019, "3.1.1").isEmpty());
	}

	@Test
	void findsChildren() {
		assertEquals(List.of(topic31), repository.findChildren(unit3));
		assertEquals(List.of(subtopic311), repository.findChildren(topic31));
	}

	@Test
	void findsNodeByCodeWithinVersion() {
		assertEquals(subtopic311, repository.findByCode(syllabus2025, "3.1.1").orElseThrow());
	}

	@Test
	void findsRootNodesForVersion() {
		List<CurriculumNode> roots = repository.findRootNodes(syllabus2025);

		assertEquals(List.of(unit3), roots);
	}

	@Test
	void findsSubjectsAndVersionsById() {
		assertEquals(List.of(chemistry), repository.findAllSubjects());
		assertEquals(chemistry, repository.findSubjectById(chemistry.getId()).orElseThrow());
		assertEquals(syllabus2025, repository.findVersionById(syllabus2025.getId()).orElseThrow());
	}

	@Test
	void findsVersionsForSubject() {
		List<SyllabusVersion> versions = repository.findVersionsForSubject(chemistry);

		assertEquals(2, versions.size());
	}

	@Test
	void rejectsDuplicateCurriculumNodeIds() {
		CurriculumNode duplicate = new CurriculumNode(unit3.getId(), syllabus2025, null, "4", "Unit 4",
				CurriculumLevel.UNIT, 4);

		assertThrows(IllegalArgumentException.class, () -> new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2025), List.of(unit3, duplicate)));
	}

	@Test
	void returnsNodesInDisplayOrderThenCodeOrder() {
		CurriculumNode laterCode = new CurriculumNode(4, syllabus2025, unit3, "3.2", "Topic 3.2", CurriculumLevel.TOPIC,
				2);
		CurriculumNode earlierCode = new CurriculumNode(5, syllabus2025, unit3, "3.0", "Topic 3.0",
				CurriculumLevel.TOPIC, 2);
		CurriculumNode first = new CurriculumNode(6, syllabus2025, unit3, "3.3", "Topic 3.3", CurriculumLevel.TOPIC, 1);
		CurriculumRepository orderedRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2025), List.of(unit3, laterCode, earlierCode, first));

		assertEquals(List.of(first, earlierCode, laterCode), orderedRepository.findChildren(unit3));
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");

		syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);

		unit3 = new CurriculumNode(1, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 3);

		topic31 = new CurriculumNode(2, syllabus2025, unit3, "3.1", "Topic 3.1", CurriculumLevel.TOPIC, 1);

		subtopic311 = new CurriculumNode(3, syllabus2025, topic31, "3.1.1", "Subtopic 3.1.1", CurriculumLevel.SUBTOPIC,
				1);

		repository = new InMemoryCurriculumRepository(List.of(chemistry), List.of(syllabus2019, syllabus2025),
				List.of(unit3, topic31, subtopic311));
	}

	@Test
	void takesImmutableSnapshotsOfConstructorLists() {
		List<Subject> subjects = new java.util.ArrayList<>(List.of(chemistry));
		InMemoryCurriculumRepository snapshotRepository = new InMemoryCurriculumRepository(subjects,
				List.of(syllabus2025), List.of(unit3));

		subjects.clear();

		assertEquals(List.of(chemistry), snapshotRepository.findAllSubjects());
		assertThrows(UnsupportedOperationException.class,
				() -> snapshotRepository.findAllSubjects().add(new Subject(2, "Physics")));
	}
}
