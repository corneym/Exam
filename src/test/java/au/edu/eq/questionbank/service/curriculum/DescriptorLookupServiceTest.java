package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.curriculum.DescriptorLookupService.DescriptorMatch;

class DescriptorLookupServiceTest {

	// Test environment and collaborators.
	private DescriptorLookupService service;

	// Curriculum and assessment fixtures.
	private Descriptor comparisonDescriptor;
	private SyllabusVersion current;
	private Unit currentUnit;
	private SyllabusVersion historical;
	private Descriptor historicalDescriptor;
	private Descriptor physicsDescriptor;
	private SyllabusVersion physicsVersion;
	private Descriptor ratesDescriptor;
	private Subtopic ratesSubtopic;
	private Topic reactionTopic;

	@Test
	void acceptsCaseInsensitivePartialWordsAndMultipleTerms() {
		List<DescriptorMatch> matches = service.findMatches(current, service.loadDescriptors(current), "rEaCt rAT", 20);
		assertEquals(2, matches.size());
		assertEquals(ratesDescriptor, matches.get(0).descriptor());
		assertEquals(comparisonDescriptor, matches.get(1).descriptor());
	}

	@Test
	void ambiguousDescriptorsHaveDistinctHierarchyPaths() {
		List<DescriptorMatch> matches = service.findMatches(current, service.loadDescriptors(current), "reaction rate",
				20);
		assertEquals(2, matches.size());
		String firstPath = matches.get(0).hierarchyPath();
		String secondPath = matches.get(1).hierarchyPath();
		assertTrue(firstPath.contains("Chemical reactions"));
		assertTrue(firstPath.contains("Acids and bases"));
		assertTrue(firstPath.contains("Reaction rates"));
		assertTrue(firstPath.contains("3.1.1.1"));
		assertTrue(secondPath.contains("Equilibrium"));
		assertTrue(secondPath.contains("3.2.1"));
		assertFalse(firstPath.equals(secondPath));
	}

	@Test
	void descriptorMatchesExposeCompactDisplayLabels() {
		List<DescriptorMatch> matches = service.findMatches(current, service.loadDescriptors(current), "reaction rate",
				20);
		assertEquals("3.1.1.1 — Explain the reaction rate of metals", matches.getFirst().displayLabel());
		assertFalse(matches.getFirst().displayLabel().contains("Chemical reactions"));
		assertFalse(matches.getFirst().displayLabel().contains("Acids and bases"));
	}

	@Test
	void blankAndUnmatchedQueriesReturnNoResults() {
		List<CurriculumNode> descriptors = service.loadDescriptors(current);
		assertTrue(service.findMatches(current, descriptors, "", 20).isEmpty());
		assertTrue(service.findMatches(current, descriptors, "   ", 20).isEmpty());
		assertTrue(service.findMatches(current, descriptors, null, 20).isEmpty());
		assertTrue(service.findMatches(current, descriptors, "radioactivity", 20).isEmpty());

		// All terms must match, not merely one of them.
		assertTrue(service.findMatches(current, descriptors, "reaction radioactivity", 20).isEmpty());
	}

	@Test
	void codePrefixMatchesOnlyItsOwnHierarchy() {
		Topic adjacentTopic = new Topic(500, current, currentUnit, "3.10", "Adjacent topic", 10);
		Descriptor adjacentDescriptor = new Descriptor(501, current, adjacentTopic, "3.10.1", "Compare reaction rates",
				1);
		List<CurriculumNode> candidates = List.of(ratesDescriptor, comparisonDescriptor, adjacentDescriptor);
		List<DescriptorMatch> matches = service.findMatches(current, candidates, "reaction rat", "3.1", 5);
		assertEquals(1, matches.size());
		assertEquals(ratesDescriptor, matches.getFirst().descriptor());

		// A full code may select the Descriptor itself.
		assertEquals(1, service.findMatches(current, candidates, "reaction", "3.1.1.1", 5).size());
	}

	@Test
	void handlesLargeDescriptorSnapshotWithBoundedResults() {
		List<CurriculumNode> descriptors = new ArrayList<>();
		for (int index = 0; index < 12000; index++) {
			descriptors.add(new Descriptor(1000 + index, current, ratesSubtopic, "3.1.1." + (index + 1),
					"Analyse reaction rate scenario " + index, index + 1));
		}
		List<DescriptorMatch> matches = service.findMatches(current, descriptors, "reaction rate", 30);
		assertEquals(30, matches.size());
		assertEquals(descriptors.getFirst(), matches.getFirst().descriptor());
		assertEquals(descriptors.get(29), matches.getLast().descriptor());
	}

	@Test
	void limitsResultsWithoutDiscardingDeterministicOrder() {
		List<DescriptorMatch> matches = service.findMatches(current, List.of(comparisonDescriptor, ratesDescriptor),
				"reaction", 1);
		assertEquals(1, matches.size());
		assertEquals(ratesDescriptor, matches.getFirst().descriptor());
		assertThrows(IllegalArgumentException.class,
				() -> service.findMatches(current, List.of(ratesDescriptor), "reaction", 0));
	}

	@Test
	void sortsNumericCodeComponentsBeforeBoundingAndUsesStableFallbacks() {
		Descriptor codeTen = new Descriptor(200, current, ratesSubtopic, "4.1.10", "Natural descriptor", 1);
		Descriptor codeTwo = new Descriptor(201, current, ratesSubtopic, "4.1.2", "Natural descriptor", 2);
		Descriptor deeperCodeTwo = new Descriptor(202, current, ratesSubtopic, "4.1.2.1", "Natural descriptor", 3);
		Descriptor duplicateCodeBeta = new Descriptor(203, current, ratesSubtopic, "4.1.10", "Natural beta", 4);
		Descriptor duplicateCodeAlpha = new Descriptor(204, current, ratesSubtopic, "4.1.10", "Natural alpha", 5);
		Descriptor nonNumericCode = new Descriptor(205, current, ratesSubtopic, "A.1", "Natural descriptor", 6);
		List<CurriculumNode> candidates = List.of(codeTen, nonNumericCode, duplicateCodeBeta, deeperCodeTwo, codeTwo,
				duplicateCodeAlpha);

		List<DescriptorMatch> matches = service.findMatches(current, candidates, "natural", 4);
		assertEquals(List.of(codeTwo, deeperCodeTwo, duplicateCodeAlpha, duplicateCodeBeta),
				matches.stream().map(DescriptorMatch::descriptor).toList());

		List<DescriptorMatch> allMatches = service.findMatches(current, candidates, "natural", 10);
		assertEquals(nonNumericCode, allMatches.getLast().descriptor());
	}

	@Test
	void loadsOnlyDescriptorsFromRequestedSyllabus() {
		assertEquals(List.of(ratesDescriptor, comparisonDescriptor), service.loadDescriptors(current));
		assertEquals(List.of(historicalDescriptor), service.loadDescriptors(historical));
		assertEquals(List.of(physicsDescriptor), service.loadDescriptors(physicsVersion));
	}

	@Test
	void matchesCodeAndWordingTogether() {
		List<DescriptorMatch> matches = service.findMatches(current, service.loadDescriptors(current), "3.2 COMPAr",
				20);
		assertEquals(1, matches.size());
		assertEquals(comparisonDescriptor, matches.getFirst().descriptor());
	}

	@Test
	void rejectsDescriptorsFromOtherSubjectsAndSyllabuses() {
		List<CurriculumNode> mixedCandidates = List.of(historicalDescriptor, physicsDescriptor, ratesDescriptor,
				comparisonDescriptor);
		List<DescriptorMatch> matches = service.findMatches(current, mixedCandidates, "reaction", 20);
		assertEquals(2, matches.size());
		assertTrue(matches.stream().allMatch(match -> current.equals(match.descriptor().getSyllabusVersion())));
		assertFalse(matches.stream().anyMatch(match -> match.descriptor().equals(historicalDescriptor)));
		assertFalse(matches.stream().anyMatch(match -> match.descriptor().equals(physicsDescriptor)));
	}

	@Test
	void scopedLookupReturnsOnlyFirstFiveMatches() {
		List<CurriculumNode> candidates = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			candidates.add(new Descriptor(600 + index, current, ratesSubtopic, "3.1.1." + (index + 10),
					"Analyse reaction rate " + index, index + 10));
		}
		List<DescriptorMatch> matches = service.findMatches(current, candidates, "reaction rate", "3.1", 5);
		assertEquals(5, matches.size());
		assertEquals(candidates.getFirst(), matches.getFirst().descriptor());
		assertEquals(candidates.get(4), matches.getLast().descriptor());
	}

	@BeforeEach
	void setUp() {
		Subject chemistry = new Subject(1, "Chemistry");
		Subject physics = new Subject(2, "Physics");
		current = new SyllabusVersion(10, chemistry, "2025", true);
		historical = new SyllabusVersion(11, chemistry, "2019", false);
		physicsVersion = new SyllabusVersion(12, physics, "2025", true);
		currentUnit = new Unit(100, current, "3", "Chemical reactions", 1);
		reactionTopic = new Topic(101, current, currentUnit, "3.1", "Acids and bases", 1);
		ratesSubtopic = new Subtopic(102, current, reactionTopic, "3.1.1", "Reaction rates", 1);
		ratesDescriptor = new Descriptor(103, current, ratesSubtopic, "3.1.1.1", "Explain the reaction rate of metals",
				1);
		Topic equilibriumTopic = new Topic(104, current, currentUnit, "3.2", "Equilibrium", 2);

		// A Descriptor may belong directly to a Topic.
		comparisonDescriptor = new Descriptor(105, current, equilibriumTopic, "3.2.1",
				"Compare reaction rates in systems", 1);
		Unit historicalUnit = new Unit(106, historical, "2", "Historical chemistry", 1);
		Topic historicalTopic = new Topic(107, historical, historicalUnit, "2.1", "Historical reactions", 1);
		historicalDescriptor = new Descriptor(108, historical, historicalTopic, "2.1.1", "Explain the reaction rate",
				1);
		Unit physicsUnit = new Unit(109, physicsVersion, "3", "Physics systems", 1);
		Topic physicsTopic = new Topic(110, physicsVersion, physicsUnit, "3.1", "Motion", 1);
		physicsDescriptor = new Descriptor(111, physicsVersion, physicsTopic, "3.1.1", "Explain reaction rates", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry, physics),
				List.of(current, historical, physicsVersion),
				List.of(currentUnit, reactionTopic, ratesSubtopic, ratesDescriptor, equilibriumTopic,
						comparisonDescriptor, historicalUnit, historicalTopic, historicalDescriptor, physicsUnit,
						physicsTopic, physicsDescriptor));
		service = new DescriptorLookupService(repository);
	}
}
