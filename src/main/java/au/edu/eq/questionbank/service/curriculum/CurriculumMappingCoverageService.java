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

		// Each syllabus hierarchy is loaded once. Both review levels
		// are collected during the same traversal.
		ReviewLevelNodes sourceNodes = findReviewLevelNodes(sourceVersion);
		ReviewLevelNodes targetNodes = findReviewLevelNodes(targetVersion);
		CurriculumMappingLevelCoverage descriptorCoverage = calculateLevelCoverage(sourceNodes.descriptors(),
				targetNodes.descriptors(), targetVersion, CurriculumLevel.DESCRIPTOR, reviewedSourceIds,
				confirmedMappings.sourceIds(), confirmedMappings.targetIds());
		CurriculumMappingLevelCoverage subtopicCoverage = calculateLevelCoverage(sourceNodes.subtopics(),
				targetNodes.subtopics(), targetVersion, CurriculumLevel.SUBTOPIC, reviewedSourceIds,
				confirmedMappings.sourceIds(), confirmedMappings.targetIds());
		return new CurriculumMappingCoverage(sourceVersion, targetVersion, descriptorCoverage, subtopicCoverage);
	}

	private CurriculumMappingLevelCoverage calculateLevelCoverage(List<CurriculumNode> sourceNodes,
			List<CurriculumNode> targetNodes, SyllabusVersion targetVersion, CurriculumLevel level,
			Set<Long> reviewedSourceIds, Set<Long> confirmedMappedSourceIds, Set<Long> confirmedMappedTargetIds) {
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
		int uncoveredTargetCount = 0;
		for (CurriculumNode target : targetNodes) {
			if (!confirmedMappedTargetIds.contains(target.getId())) {
				uncoveredTargetCount++;
			}
		}
		return new CurriculumMappingLevelCoverage(level, sourceNodes.size(), matched, noMatch, unreviewed, inconsistent,
				uncoveredTargetCount);
	}

	private void collectReviewLevelNodes(CurriculumNode node, List<CurriculumNode> subtopics,
			List<CurriculumNode> descriptors) {

		// Preserve the existing hierarchy traversal semantics,
		// collecting both reviewable levels in the same pass.
		switch (node.getLevel()) {
		case SUBTOPIC:
			subtopics.add(node);
			break;
		case DESCRIPTOR:
			descriptors.add(node);
			break;
		default:
			break;
		}
		for (CurriculumNode child : curriculumRepository.findChildren(node)) {
			collectReviewLevelNodes(child, subtopics, descriptors);
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

	private ReviewLevelNodes findReviewLevelNodes(SyllabusVersion syllabusVersion) {
		List<CurriculumNode> subtopics = new ArrayList<>();
		List<CurriculumNode> descriptors = new ArrayList<>();
		for (CurriculumNode root : curriculumRepository.findRootNodes(syllabusVersion)) {
			collectReviewLevelNodes(root, subtopics, descriptors);
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
