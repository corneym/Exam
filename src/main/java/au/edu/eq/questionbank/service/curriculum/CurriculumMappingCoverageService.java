package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
		Set<Long> reviewedSourceIds = reviewRepository.findReviewedSourceIds(sourceVersion, targetVersion);
		ConfirmedPairMappings confirmedMappings = confirmedPairMappings(sourceVersion, targetVersion);
		CurriculumMappingLevelCoverage descriptorCoverage = calculateLevelCoverage(sourceVersion, targetVersion,
				CurriculumLevel.DESCRIPTOR, reviewedSourceIds, confirmedMappings.sourceIds(),
				confirmedMappings.targetIds());
		CurriculumMappingLevelCoverage subtopicCoverage = calculateLevelCoverage(sourceVersion, targetVersion,
				CurriculumLevel.SUBTOPIC, reviewedSourceIds, confirmedMappings.sourceIds(),
				confirmedMappings.targetIds());
		return new CurriculumMappingCoverage(sourceVersion, targetVersion, descriptorCoverage, subtopicCoverage);
	}

	private CurriculumMappingLevelCoverage calculateLevelCoverage(SyllabusVersion sourceVersion,
			SyllabusVersion targetVersion, CurriculumLevel level, Set<Long> reviewedSourceIds,
			Set<Long> confirmedMappedSourceIds, Set<Long> confirmedMappedTargetIds) {
		List<CurriculumNode> sourceNodes = findNodesAtLevel(sourceVersion, level);
		List<CurriculumNode> targetNodes = findNodesAtLevel(targetVersion, level);
		int matched = 0;
		int noMatch = 0;
		int unreviewed = 0;
		int inconsistent = 0;
		for (CurriculumNode source : sourceNodes) {
			CurriculumMappingSourceState state = determineSourceState(source, targetVersion, reviewedSourceIds,
					confirmedMappedSourceIds);
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

		// New target content may have no predecessor even when all source reviews are
		// complete.
		int uncoveredTargetCount = 0;
		for (CurriculumNode target : targetNodes) {
			if (!confirmedMappedTargetIds.contains(target.getId())) {
				uncoveredTargetCount++;
			}
		}
		return new CurriculumMappingLevelCoverage(level, sourceNodes.size(), matched, noMatch, unreviewed, inconsistent,
				uncoveredTargetCount);
	}

	private void collectNodesAtLevel(CurriculumNode node, CurriculumLevel level, List<CurriculumNode> result) {
		if (node.getLevel() == level) {
			result.add(node);
		}
		for (CurriculumNode child : curriculumRepository.findChildren(node)) {
			collectNodesAtLevel(child, level, result);
		}
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

	private CurriculumMappingSourceState determineSourceState(CurriculumNode source, SyllabusVersion targetVersion,
			Set<Long> reviewedSourceIds, Set<Long> confirmedMappedSourceIds) {
		boolean reviewed = reviewedSourceIds.contains(source.getId());
		boolean confirmedMapping = confirmedMappedSourceIds.contains(source.getId());

		// A mapping alone is not a completed review; the explicit outcome must agree
		// with it.
		if (!reviewed) {
			return confirmedMapping ? CurriculumMappingSourceState.INCONSISTENT
					: CurriculumMappingSourceState.UNREVIEWED;
		}
		Optional<CurriculumMappingReviewOutcome> outcome = reviewRepository.findOutcome(source, targetVersion);
		if (outcome.isEmpty()) {
			return CurriculumMappingSourceState.INCONSISTENT;
		}
		if (outcome.get() == CurriculumMappingReviewOutcome.MATCHED) {
			return confirmedMapping ? CurriculumMappingSourceState.MATCHED : CurriculumMappingSourceState.INCONSISTENT;
		}

		// An explicit no-match decision is complete only when no confirmed target
		// contradicts it.
		return confirmedMapping ? CurriculumMappingSourceState.INCONSISTENT : CurriculumMappingSourceState.NO_MATCH;
	}

	private List<CurriculumNode> findNodesAtLevel(SyllabusVersion syllabusVersion, CurriculumLevel level) {
		List<CurriculumNode> result = new ArrayList<>();
		for (CurriculumNode root : curriculumRepository.findRootNodes(syllabusVersion)) {
			collectNodesAtLevel(root, level, result);
		}
		return List.copyOf(result);
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

	private record ConfirmedPairMappings(Set<Long> sourceIds, Set<Long> targetIds) {
	}
}
