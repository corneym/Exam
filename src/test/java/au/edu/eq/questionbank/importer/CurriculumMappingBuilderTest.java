package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
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

		source = new CurriculumNode(1, syllabus2019, null, "3.1.2", "2019 subtopic", CurriculumLevel.SUBTOPIC, 1);

		target = new CurriculumNode(2, syllabus2025, null, "3.2.1", "2025 subtopic", CurriculumLevel.SUBTOPIC, 1);

		repository = new InMemoryCurriculumRepository(List.of(chemistry), List.of(syllabus2019, syllabus2025),
				List.of(source, target));
	}
}