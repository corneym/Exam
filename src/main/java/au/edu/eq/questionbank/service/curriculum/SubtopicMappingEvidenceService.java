package au.edu.eq.questionbank.service.curriculum;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Summarises descriptor review coverage for a historical source subtopic and
 * the current target syllabus. Review identity remains specific to the source
 * descriptor and target syllabus version.
 */
public final class SubtopicMappingEvidenceService {

	private final CurriculumRepository curriculumRepository;
	private final CurriculumMappingReviewRepository reviewRepository;

	/**
	 * @param curriculumRepository hierarchy lookup used to find direct descriptors
	 * @param reviewRepository     target-specific descriptor review lookup
	 * @throws NullPointerException if either repository is {@code null}
	 */
	public SubtopicMappingEvidenceService(CurriculumRepository curriculumRepository,
			CurriculumMappingReviewRepository reviewRepository) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (reviewRepository == null) {
			throw new NullPointerException("reviewRepository");
		}
		this.curriculumRepository = curriculumRepository;
		this.reviewRepository = reviewRepository;
	}

	/**
	 * Counts reviewed and explicit no-match outcomes among the source subtopic's
	 * direct descriptors for the selected current target syllabus.
	 *
	 * @param source        the subtopic in a non-current syllabus version
	 * @param targetVersion the current syllabus version against which descriptor
	 *                      reviews were completed
	 * @return immutable descriptor review evidence
	 * @throws NullPointerException if either argument is {@code null}
	 * @throws IllegalArgumentException if the source is not a subtopic, the versions
	 *                                  belong to different subjects or are not
	 *                                  directed from non-current to current
	 * @throws IllegalStateException if persisted review state cannot be read
	 */
	public SubtopicMappingEvidence summarise(CurriculumNode source, SyllabusVersion targetVersion) {
		validateRequest(source, targetVersion);
		List<CurriculumNode> children = curriculumRepository.findChildren(source);
		int totalDescriptorCount = 0;
		int reviewedDescriptorCount = 0;
		int noMatchDescriptorCount = 0;
		for (CurriculumNode child : children) {
			if (child.getLevel() != CurriculumLevel.DESCRIPTOR) {
				continue;
			}
			totalDescriptorCount++;
			Optional<CurriculumMappingReviewOutcome> outcome = reviewRepository.findOutcome(child, targetVersion);
			if (outcome.isEmpty()) {
				continue;
			}
			reviewedDescriptorCount++;
			if (outcome.get() == CurriculumMappingReviewOutcome.NO_MATCH) {
				noMatchDescriptorCount++;
			}
		}
		return new SubtopicMappingEvidence(reviewedDescriptorCount, totalDescriptorCount, noMatchDescriptorCount);
	}

	private void validateRequest(CurriculumNode source, SyllabusVersion targetVersion) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (source.getLevel() != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("source must be a subtopic");
		}
		if (!source.getSyllabusVersion().getSubject().equals(targetVersion.getSubject())) {
			throw new IllegalArgumentException("source and target syllabus must belong to the same subject");
		}
		if (source.getSyllabusVersion().equals(targetVersion)) {
			throw new IllegalArgumentException("source and target syllabus versions must be different");
		}
		if (source.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("source syllabus version must not be current");
		}
		if (!targetVersion.isCurrent()) {
			throw new IllegalArgumentException("target syllabus version must be current");
		}
	}
}
