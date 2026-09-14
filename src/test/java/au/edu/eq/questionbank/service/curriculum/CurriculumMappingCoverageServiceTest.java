package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumMappingRepository;

class CurriculumMappingCoverageServiceTest {

	@Test
	void distinguishesMatchedNoMatchUnreviewedAndInconsistentSources() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historical = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion current = new SyllabusVersion(2, chemistry, "2025", true);
		SyllabusVersion otherHistorical = new SyllabusVersion(3, chemistry, "2022", false);
		Unit historicalUnit = new Unit(1, historical, "1", "Historical unit", 0);
		Topic historicalTopic = new Topic(2, historical, historicalUnit, "1.1", "Historical topic", 0);
		Subtopic firstHistoricalSubtopic = new Subtopic(3, historical, historicalTopic, "1.1.1",
				"First historical subtopic", 0);
		Descriptor matched = new Descriptor(4, historical, firstHistoricalSubtopic, "1.1.1.1", "Matched", 0);
		Descriptor noMatch = new Descriptor(5, historical, firstHistoricalSubtopic, "1.1.1.2", "No match", 1);
		Descriptor mappingWithoutReview = new Descriptor(6, historical, firstHistoricalSubtopic, "1.1.1.3",
				"Mapping without review", 2);
		Subtopic secondHistoricalSubtopic = new Subtopic(7, historical, historicalTopic, "1.1.2",
				"Second historical subtopic", 1);
		Descriptor matchedReviewWithoutConfirmedMapping = new Descriptor(8, historical, secondHistoricalSubtopic,
				"1.1.2.1", "Matched review without confirmed mapping", 0);
		Descriptor noMatchWithConfirmedMapping = new Descriptor(9, historical, secondHistoricalSubtopic, "1.1.2.2",
				"No match with confirmed mapping", 1);
		Descriptor unreviewed = new Descriptor(10, historical, secondHistoricalSubtopic, "1.1.2.3", "Unreviewed", 2);
		Unit currentUnit = new Unit(20, current, "1", "Current unit", 0);
		Topic currentTopic = new Topic(21, current, currentUnit, "1.1", "Current topic", 0);
		Subtopic currentSubtopic = new Subtopic(22, current, currentTopic, "1.1.1", "Current subtopic", 0);
		Descriptor firstCurrentDescriptor = new Descriptor(23, current, currentSubtopic, "1.1.1.1",
				"First current descriptor", 0);
		Descriptor secondCurrentDescriptor = new Descriptor(24, current, currentSubtopic, "1.1.1.2",
				"Second current descriptor", 1);
		Unit otherUnit = new Unit(30, otherHistorical, "1", "Other unit", 0);
		Topic otherTopic = new Topic(31, otherHistorical, otherUnit, "1.1", "Other topic", 0);
		Subtopic otherSubtopic = new Subtopic(32, otherHistorical, otherTopic, "1.1.1", "Other subtopic", 0);
		Descriptor otherDescriptor = new Descriptor(33, otherHistorical, otherSubtopic, "1.1.1.1", "Other descriptor",
				0);
		List<CurriculumNode> nodes = List.of(historicalUnit, historicalTopic, firstHistoricalSubtopic, matched, noMatch,
				mappingWithoutReview, secondHistoricalSubtopic, matchedReviewWithoutConfirmedMapping,
				noMatchWithConfirmedMapping, unreviewed, currentUnit, currentTopic, currentSubtopic,
				firstCurrentDescriptor, secondCurrentDescriptor, otherUnit, otherTopic, otherSubtopic, otherDescriptor);
		List<CurriculumMapping> mappings = List.of(
				new CurriculumMapping(1, matched, firstCurrentDescriptor, MappingStatus.CONFIRMED),
				new CurriculumMapping(2, matched, secondCurrentDescriptor, MappingStatus.CONFIRMED),
				new CurriculumMapping(3, noMatch, firstCurrentDescriptor, MappingStatus.SUGGESTED),
				new CurriculumMapping(4, mappingWithoutReview, firstCurrentDescriptor, MappingStatus.CONFIRMED),
				new CurriculumMapping(5, matchedReviewWithoutConfirmedMapping, firstCurrentDescriptor,
						MappingStatus.SUGGESTED),
				new CurriculumMapping(6, matchedReviewWithoutConfirmedMapping, otherDescriptor,
						MappingStatus.CONFIRMED),
				new CurriculumMapping(7, noMatchWithConfirmedMapping, secondCurrentDescriptor, MappingStatus.CONFIRMED),
				new CurriculumMapping(8, firstHistoricalSubtopic, currentSubtopic, MappingStatus.CONFIRMED));
		Map<ReviewKey, CurriculumMappingReviewOutcome> reviews = new HashMap<>();
		reviews.put(new ReviewKey(matched.getId(), current.getId()), CurriculumMappingReviewOutcome.MATCHED);
		reviews.put(new ReviewKey(noMatch.getId(), current.getId()), CurriculumMappingReviewOutcome.NO_MATCH);
		reviews.put(new ReviewKey(matchedReviewWithoutConfirmedMapping.getId(), current.getId()),
				CurriculumMappingReviewOutcome.MATCHED);
		reviews.put(new ReviewKey(noMatchWithConfirmedMapping.getId(), current.getId()),
				CurriculumMappingReviewOutcome.NO_MATCH);
		reviews.put(new ReviewKey(firstHistoricalSubtopic.getId(), current.getId()),
				CurriculumMappingReviewOutcome.MATCHED);
		reviews.put(new ReviewKey(secondHistoricalSubtopic.getId(), current.getId()),
				CurriculumMappingReviewOutcome.NO_MATCH);
		CurriculumMappingCoverageService service = new CurriculumMappingCoverageService(
				new TestCurriculumRepository(List.of(historical, current, otherHistorical), nodes),
				new InMemoryCurriculumMappingRepository(mappings), new TestReviewRepository(nodes, reviews));
		CurriculumMappingCoverage coverage = service.calculateCoverage(historical, current);
		CurriculumMappingLevelCoverage descriptors = coverage.descriptorCoverage();
		assertEquals(6, descriptors.total());
		assertEquals(1, descriptors.matched());
		assertEquals(1, descriptors.noMatch());
		assertEquals(1, descriptors.unreviewed());
		assertEquals(3, descriptors.inconsistent());
		assertEquals(CurriculumMappingCoverageStatus.INCOMPLETE, descriptors.status());
		CurriculumMappingLevelCoverage subtopics = coverage.subtopicCoverage();
		assertEquals(2, subtopics.total());
		assertEquals(1, subtopics.matched());
		assertEquals(1, subtopics.noMatch());
		assertEquals(0, subtopics.unreviewed());
		assertEquals(0, subtopics.inconsistent());
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, subtopics.status());
		assertEquals(CurriculumMappingCoverageStatus.INCOMPLETE, coverage.status());
		assertFalse(coverage.complete());
	}

	@Test
	void rejectsInvalidMappingDirection() {
		Subject chemistry = new Subject(1, "Chemistry");
		Subject engineering = new Subject(2, "Engineering");
		SyllabusVersion historical = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion current = new SyllabusVersion(2, chemistry, "2025", true);
		SyllabusVersion secondHistorical = new SyllabusVersion(3, chemistry, "2022", false);
		SyllabusVersion engineeringCurrent = new SyllabusVersion(4, engineering, "2025", true);
		CurriculumMappingCoverageService service = new CurriculumMappingCoverageService(
				new TestCurriculumRepository(List.of(historical, current, secondHistorical, engineeringCurrent),
						List.of()),
				new InMemoryCurriculumMappingRepository(List.of()), new TestReviewRepository(List.of(), Map.of()));
		assertThrows(IllegalArgumentException.class, () -> service.calculateCoverage(current, historical));
		assertThrows(IllegalArgumentException.class, () -> service.calculateCoverage(historical, secondHistorical));
		assertThrows(IllegalArgumentException.class, () -> service.calculateCoverage(historical, engineeringCurrent));
		assertThrows(IllegalArgumentException.class, () -> service.calculateCoverage(historical, historical));
		assertThrows(NullPointerException.class, () -> service.calculateCoverage(null, current));
		assertThrows(NullPointerException.class, () -> service.calculateCoverage(historical, null));
	}

	@Test
	void threeLevelCurriculumTreatsSubtopicsAsNotApplicable() {
		Subject engineering = new Subject(1, "Engineering");
		SyllabusVersion historical = new SyllabusVersion(1, engineering, "2019", false);
		SyllabusVersion current = new SyllabusVersion(2, engineering, "2025", true);
		Unit unit = new Unit(1, historical, "1", "Unit", 0);
		Topic topic = new Topic(2, historical, unit, "1.1", "Topic", 0);
		Descriptor descriptor = new Descriptor(3, historical, topic, "1.1.1", "Historical descriptor", 0);
		Map<ReviewKey, CurriculumMappingReviewOutcome> reviews = Map
				.of(new ReviewKey(descriptor.getId(), current.getId()), CurriculumMappingReviewOutcome.NO_MATCH);
		CurriculumMappingCoverageService service = new CurriculumMappingCoverageService(
				new TestCurriculumRepository(List.of(historical, current), List.of(unit, topic, descriptor)),
				new InMemoryCurriculumMappingRepository(List.of()),
				new TestReviewRepository(List.of(unit, topic, descriptor), reviews));
		CurriculumMappingCoverage coverage = service.calculateCoverage(historical, current);
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, coverage.descriptorCoverage().status());
		assertEquals(CurriculumMappingCoverageStatus.NOT_APPLICABLE, coverage.subtopicCoverage().status());
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, coverage.status());
		assertTrue(coverage.complete());
	}

	private record ReviewKey(long sourceNodeId, long targetVersionId) {
	}

	private static final class TestCurriculumRepository implements CurriculumRepository {

		private final List<SyllabusVersion> versions;
		private final List<CurriculumNode> nodes;

		TestCurriculumRepository(List<SyllabusVersion> versions, List<CurriculumNode> nodes) {
			this.versions = List.copyOf(versions);
			this.nodes = List.copyOf(nodes);
		}

		@Override
		public List<Subject> findAllSubjects() {
			return versions.stream().map(SyllabusVersion::getSubject).distinct().toList();
		}

		@Override
		public Optional<CurriculumNode> findByCode(SyllabusVersion syllabusVersion, String code) {
			return nodes.stream().filter(node -> node.getSyllabusVersion().equals(syllabusVersion))
					.filter(node -> node.getCode().equals(code)).findFirst();
		}

		@Override
		public List<CurriculumNode> findChildren(CurriculumNode parent) {
			return nodes.stream().filter(node -> parent.equals(node.getParent())).toList();
		}

		@Override
		public List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion) {
			return nodes.stream().filter(node -> node.getSyllabusVersion().equals(syllabusVersion))
					.filter(node -> node.getLevel() == CurriculumLevel.UNIT).toList();
		}

		@Override
		public Optional<Subject> findSubjectById(long id) {
			return findAllSubjects().stream().filter(subject -> subject.getId() == id).findFirst();
		}

		@Override
		public Optional<SyllabusVersion> findVersionById(long id) {
			return versions.stream().filter(version -> version.getId() == id).findFirst();
		}

		@Override
		public List<SyllabusVersion> findVersionsForSubject(Subject subject) {
			return versions.stream().filter(version -> version.getSubject().equals(subject)).toList();
		}
	}

	private static final class TestReviewRepository implements CurriculumMappingReviewRepository {

		private final Map<Long, CurriculumNode> nodesById;
		private final Map<ReviewKey, CurriculumMappingReviewOutcome> outcomes;

		TestReviewRepository(List<CurriculumNode> nodes, Map<ReviewKey, CurriculumMappingReviewOutcome> outcomes) {
			nodesById = nodes.stream().collect(Collectors.toMap(CurriculumNode::getId, node -> node));
			this.outcomes = Map.copyOf(outcomes);
		}

		@Override
		public Optional<CurriculumMappingReviewOutcome> findOutcome(CurriculumNode source,
				SyllabusVersion targetVersion) {
			return Optional.ofNullable(outcomes.get(new ReviewKey(source.getId(), targetVersion.getId())));
		}

		@Override
		public Set<Long> findReviewedSourceIds(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {
			return outcomes.keySet().stream().filter(key -> key.targetVersionId() == targetVersion.getId())
					.filter(key -> {
						CurriculumNode source = nodesById.get(key.sourceNodeId());
						return source != null && source.getSyllabusVersion().equals(sourceVersion);
					}).map(ReviewKey::sourceNodeId).collect(Collectors.toUnmodifiableSet());
		}
	}
}
