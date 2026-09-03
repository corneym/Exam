package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class SubtopicMappingEvidenceServiceTest {

	@Test
	void incompleteDescriptorReviewIsReported() {
		Fixture fixture = createFixture();
		CurriculumMappingReviewRepository reviewRepository = new TestReviewRepository(
				Map.of(fixture.firstDescriptor().getId(), CurriculumMappingReviewOutcome.MATCHED));
		SubtopicMappingEvidenceService service = new SubtopicMappingEvidenceService(fixture.repository(),
				reviewRepository);

		SubtopicMappingEvidence evidence = service.summarise(fixture.sourceSubtopic(), fixture.targetVersion());

		assertEquals(1, evidence.reviewedDescriptorCount());
		assertEquals(3, evidence.totalDescriptorCount());
		assertEquals(0, evidence.noMatchDescriptorCount());
	}

	@Test
	void descendantDescriptorNoMatchOutcomesAreCountedCorrectly() {
		Fixture fixture = createFixture();
		CurriculumMappingReviewRepository reviewRepository = new TestReviewRepository(
				Map.of(fixture.firstDescriptor().getId(), CurriculumMappingReviewOutcome.NO_MATCH,
						fixture.secondDescriptor().getId(), CurriculumMappingReviewOutcome.MATCHED,
						999L, CurriculumMappingReviewOutcome.NO_MATCH));
		SubtopicMappingEvidenceService service = new SubtopicMappingEvidenceService(fixture.repository(),
				reviewRepository);

		SubtopicMappingEvidence evidence = service.summarise(fixture.sourceSubtopic(), fixture.targetVersion());

		assertEquals(2, evidence.reviewedDescriptorCount());
		assertEquals(3, evidence.totalDescriptorCount());
		assertEquals(1, evidence.noMatchDescriptorCount());
	}

	private Fixture createFixture() {
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, subject, "2019", false);
		Unit sourceUnit = new Unit(10, sourceVersion, "1", "Source unit", 0);
		Topic sourceTopic = new Topic(11, sourceVersion, sourceUnit, "1.1", "Source topic", 0);
		Subtopic sourceSubtopic = new Subtopic(12, sourceVersion, sourceTopic, "1.1.1", "Source subtopic", 0);
		Descriptor firstDescriptor = new Descriptor(13, sourceVersion, sourceSubtopic, "1.1.1.1",
				"First descriptor", 0);
		Descriptor secondDescriptor = new Descriptor(14, sourceVersion, sourceSubtopic, "1.1.1.2",
				"Second descriptor", 1);
		Descriptor thirdDescriptor = new Descriptor(15, sourceVersion, sourceSubtopic, "1.1.1.3",
				"Third descriptor", 2);
		SyllabusVersion targetVersion = new SyllabusVersion(2, subject, "2025", true);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(subject),
				List.of(sourceVersion, targetVersion),
				List.of(sourceUnit, sourceTopic, sourceSubtopic, firstDescriptor, secondDescriptor, thirdDescriptor));
		return new Fixture(repository, sourceSubtopic, firstDescriptor, secondDescriptor, targetVersion);
	}

	private record Fixture(InMemoryCurriculumRepository repository, Subtopic sourceSubtopic,
			Descriptor firstDescriptor, Descriptor secondDescriptor, SyllabusVersion targetVersion) {
	}

	private static final class TestReviewRepository implements CurriculumMappingReviewRepository {
		private final Map<Long, CurriculumMappingReviewOutcome> outcomes;

		private TestReviewRepository(Map<Long, CurriculumMappingReviewOutcome> outcomes) {
			this.outcomes = Map.copyOf(outcomes);
		}

		@Override
		public Set<Long> findReviewedSourceIds(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {
			return outcomes.keySet();
		}

		@Override
		public Optional<CurriculumMappingReviewOutcome> findOutcome(CurriculumNode source,
				SyllabusVersion targetVersion) {
			return Optional.ofNullable(outcomes.get(source.getId()));
		}
	}
}
