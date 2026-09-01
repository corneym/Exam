package au.edu.eq.questionbank.repository;

import java.util.Optional;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Read-only access to completed curriculum-mapping reviews.
 */
public interface CurriculumMappingReviewRepository {
	Set<Long> findReviewedSourceIds(SyllabusVersion sourceVersion, SyllabusVersion targetVersion);

	Optional<CurriculumMappingReviewOutcome> findOutcome(CurriculumNode source, SyllabusVersion targetVersion);
}