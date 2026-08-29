package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.repository.InMemoryCurriculumRepository;

class CurriculumMappingBuilderTest {

	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;

	private CurriculumNode source;
	private CurriculumNode target;

	private CurriculumRepository repository;

	@Test
	void rejectsUnknown2019Code() {
		CurriculumMappingBuilder builder = new CurriculumMappingBuilder();

		AtomicLong ids = new AtomicLong(1);

		assertThrows(IllegalArgumentException.class, () -> builder.build(repository, syllabus2019, syllabus2025,
				List.of(new CurriculumMappingImportRow("9.9.9", "3.2.1")), ids::getAndIncrement));
	}

	@Test
	void rejectsUnknown2025Code() {
		CurriculumMappingBuilder builder = new CurriculumMappingBuilder();

		AtomicLong ids = new AtomicLong(1);

		assertThrows(IllegalArgumentException.class, () -> builder.build(repository, syllabus2019, syllabus2025,
				List.of(new CurriculumMappingImportRow("3.1.2", "9.9.9")), ids::getAndIncrement));
	}

	@Test
	void resolvesCodesAndBuildsConfirmedMapping() {
		CurriculumMappingBuilder builder = new CurriculumMappingBuilder();

		AtomicLong ids = new AtomicLong(1);

		List<CurriculumMapping> mappings = builder.build(repository, syllabus2019, syllabus2025,
				List.of(new CurriculumMappingImportRow("3.1.2", "3.2.1")), ids::getAndIncrement);

		assertEquals(1, mappings.size());

		CurriculumMapping mapping = mappings.get(0);

		assertEquals(source, mapping.getSource());
		assertEquals(target, mapping.getTarget());
		assertEquals(MappingStatus.CONFIRMED, mapping.getStatus());
	}

	@BeforeEach
	void setUp() {
		Subject chemistry = new Subject(1, "Chemistry");
		syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit2019 = new Unit(1, syllabus2019, "3", "Unit 3", 1);
		Topic topic2019 = new Topic(2, syllabus2019, unit2019, "3.1", "Topic 3.1", 1);
		source = new Subtopic(3, syllabus2019, topic2019, "3.1.2", "2019 subtopic", 1);
		Unit unit2025 = new Unit(4, syllabus2025, "3", "Unit 3", 1);
		Topic topic2025 = new Topic(5, syllabus2025, unit2025, "3.2", "Topic 3.2", 1);
		target = new Subtopic(6, syllabus2025, topic2025, "3.2.1", "2025 subtopic", 1);
		repository = new InMemoryCurriculumRepository(List.of(chemistry), List.of(syllabus2019, syllabus2025),
				List.of(unit2019, topic2019, source, unit2025, topic2025, target));
	}
}