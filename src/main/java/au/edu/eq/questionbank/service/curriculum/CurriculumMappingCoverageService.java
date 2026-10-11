package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Calculates mapping-review completion for an ordered historical-to-current
 * syllabus-version pair.
 * <p>
 * Only Descriptor and Subtopic reviews participate because those are the levels
 * supported by the mapping-review workflow.
 */
public final class CurriculumMappingCoverageService {

	private final CurriculumRepository curriculumRepository;
	private final CurriculumMappingRepository mappingRepository;
	private final CurriculumMappingReviewRepository reviewRepository;

	/**
	 * Creates a read-only mapping coverage service.
	 *
	 * @param curriculumRepository curriculum hierarchy lookup
	 * @param mappingRepository    directional mapping lookup
	 * @param reviewRepository     completed-review lookup
	 */
	public CurriculumMappingCoverageService(CurriculumRepository curriculumRepository,
			CurriculumMappingRepository mappingRepository, CurriculumMappingReviewRepository reviewRepository) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (mappingRepository == null) {
			throw new NullPointerException("mappingRepository");
		}
		if (reviewRepository == null) {
			throw new NullPointerException("reviewRepository");
		}
		this.curriculumRepository = curriculumRepository;
		this.mappingRepository = mappingRepository;
		this.reviewRepository = reviewRepository;
	}

	/**
	 * Calculates review coverage for one historical source and current target
	 * syllabus pair.
	 *
	 * @param sourceVersion historical, non-current source syllabus
	 * @param targetVersion current target syllabus of the same subject
	 * @return descriptor, subtopic and overall coverage
	 * @throws NullPointerException     if either version is null
	 * @throws IllegalArgumentException if the versions do not represent a valid
	 *                                  historical-to-current mapping direction
	 */
	public CurriculumMappingCoverage calculateCoverage(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {
		validateVersions(sourceVersion, targetVersion);

		// One retrieval supplies both the reviewed source IDs and their
		// authoritative MATCHED or NO_MATCH outcomes.
		Map<Long, CurriculumMappingReviewOutcome> reviewOutcomes = reviewRepository
				.findOutcomesForVersions(sourceVersion, targetVersion);
		ConfirmedPairMappings confirmedMappings = confirmedPairMappings(sourceVersion, targetVersion);
		ReviewLevelNodes sourceNodes = findReviewLevelNodes(sourceVersion);
		ReviewLevelNodes targetNodes = findReviewLevelNodes(targetVersion);
		CurriculumMappingLevelCoverage descriptorCoverage = calculateLevelCoverage(sourceNodes.descriptors(),
				targetNodes.descriptors(), CurriculumLevel.DESCRIPTOR, reviewOutcomes, confirmedMappings.sourceIds(),
				confirmedMappings.targetIds());
		CurriculumMappingLevelCoverage subtopicCoverage = calculateLevelCoverage(sourceNodes.subtopics(),
				targetNodes.subtopics(), CurriculumLevel.SUBTOPIC, reviewOutcomes, confirmedMappings.sourceIds(),
				confirmedMappings.targetIds());
		return new CurriculumMappingCoverage(sourceVersion, targetVersion, descriptorCoverage, subtopicCoverage);
	}

	private CurriculumMappingLevelCoverage calculateLevelCoverage(List<CurriculumNode> sourceNodes,
			List<CurriculumNode> targetNodes, CurriculumLevel level,
			Map<Long, CurriculumMappingReviewOutcome> reviewOutcomes, Set<Long> confirmedMappedSourceIds,
			Set<Long> confirmedMappedTargetIds) {
		int matched = 0;
		int noMatch = 0;
		int unreviewed = 0;
		int inconsistent = 0;
		for (CurriculumNode source : sourceNodes) {
			CurriculumMappingSourceState state = determineSourceState(source, reviewOutcomes, confirmedMappedSourceIds);
			switch (state) {
			case MATCHED:
				matched++;
				break;
			case NO_MATCH:
				noMatch++;
				break;
			case UNREVIEWED:
				unreviewed++;
				break;
			case INCONSISTENT:
				inconsistent++;
				break;
			}
		}
		int uncoveredTargetCount = 0;
		for (CurriculumNode target : targetNodes) {
			if (!confirmedMappedTargetIds.contains(target.getId())) {
				uncoveredTargetCount++;
			}
		}
		return new CurriculumMappingLevelCoverage(level, sourceNodes.size(), matched, noMatch, unreviewed, inconsistent,
				uncoveredTargetCount);
	}

	private ConfirmedPairMappings confirmedPairMappings(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {

		// Suggestions and mappings for other syllabus pairs cannot establish this
		// pair's coverage.
		Set<Long> sourceIds = new HashSet<>();
		Set<Long> targetIds = new HashSet<>();
		for (CurriculumMapping mapping : mappingRepository.findAll()) {
			if (mapping.getStatus() != MappingStatus.CONFIRMED) {
				continue;
			}
			if (!mapping.getSource().getSyllabusVersion().equals(sourceVersion)) {
				continue;
			}
			if (!mapping.getTarget().getSyllabusVersion().equals(targetVersion)) {
				continue;
			}
			sourceIds.add(mapping.getSource().getId());
			targetIds.add(mapping.getTarget().getId());
		}
		return new ConfirmedPairMappings(Set.copyOf(sourceIds), Set.copyOf(targetIds));
	}

	private CurriculumMappingSourceState determineSourceState(CurriculumNode source,
			Map<Long, CurriculumMappingReviewOutcome> reviewOutcomes, Set<Long> confirmedMappedSourceIds) {
		CurriculumMappingReviewOutcome outcome = reviewOutcomes.get(source.getId());
		boolean confirmedMapping = confirmedMappedSourceIds.contains(source.getId());

		// Missing review record means unreviewed. A confirmed mapping
		// without a completed review remains inconsistent.
		if (outcome == null) {
			return confirmedMapping ? CurriculumMappingSourceState.INCONSISTENT
					: CurriculumMappingSourceState.UNREVIEWED;
		}
		if (outcome == CurriculumMappingReviewOutcome.MATCHED) {
			return confirmedMapping ? CurriculumMappingSourceState.MATCHED : CurriculumMappingSourceState.INCONSISTENT;
		}

		// NO_MATCH is valid only when no confirmed target mapping
		// contradicts the explicit review decision.
		return confirmedMapping ? CurriculumMappingSourceState.INCONSISTENT : CurriculumMappingSourceState.NO_MATCH;
	}

	private ReviewLevelNodes findReviewLevelNodes(SyllabusVersion syllabusVersion) {
		List<CurriculumNode> subtopics = new ArrayList<>();
		List<CurriculumNode> descriptors = new ArrayList<>();

		// The repository supplies both reviewable levels through one
		// bulk operation for SQLite, or its standard hierarchy traversal
		// for other implementations.
		for (CurriculumNode node : curriculumRepository.findReviewNodes(syllabusVersion)) {
			switch (node.getLevel()) {
			case SUBTOPIC:
				subtopics.add(node);
				break;
			case DESCRIPTOR:
				descriptors.add(node);
				break;
			default:
				throw new IllegalStateException("Unexpected curriculum review node level: " + node.getLevel());
			}
		}
		return new ReviewLevelNodes(List.copyOf(subtopics), List.copyOf(descriptors));
	}

	private void validateVersions(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {
		if (sourceVersion == null) {
			throw new NullPointerException("sourceVersion");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (!sourceVersion.getSubject().equals(targetVersion.getSubject())) {
			throw new IllegalArgumentException("source and target syllabus must belong to the same subject");
		}
		if (sourceVersion.equals(targetVersion)) {
			throw new IllegalArgumentException("source and target syllabus must be different versions");
		}
		if (sourceVersion.isCurrent()) {
			throw new IllegalArgumentException("source syllabus version must not be current");
		}
		if (!targetVersion.isCurrent()) {
			throw new IllegalArgumentException("target syllabus version must be current");
		}
	}

	private record ReviewLevelNodes(List<CurriculumNode> subtopics, List<CurriculumNode> descriptors) {
	}

	private record ConfirmedPairMappings(Set<Long> sourceIds, Set<Long> targetIds) {
	}
}
