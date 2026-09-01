package au.edu.eq.questionbank.repository.curriculum;

import java.util.Optional;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Read-only access to completed curriculum-mapping reviews. Review identity is
 * the directional pair of a source node and a target syllabus version; no row
 * means that the source remains unreviewed for that target version.
 */
public interface CurriculumMappingReviewRepository {
	/**
	 * Finds source-node identifiers reviewed for one ordered pair of syllabus
	 * versions.
	 *
	 * @param sourceVersion the version containing the source nodes
	 * @param targetVersion the version against which they were reviewed
	 * @return the reviewed source-node identifiers
	 * @throws NullPointerException if either version is {@code null}
	 * @throws IllegalArgumentException if the versions have different subjects or
	 *                                  are the same version
	 * @throws IllegalStateException if persisted review state cannot be read
	 */
	Set<Long> findReviewedSourceIds(SyllabusVersion sourceVersion, SyllabusVersion targetVersion);

	/**
	 * Finds the completed outcome for one source and target-version pair.
	 *
	 * @param source        the directional source node
	 * @param targetVersion the syllabus version against which it was reviewed
	 * @return the outcome, or an empty value when this pair is unreviewed
	 * @throws NullPointerException if either argument is {@code null}
	 * @throws IllegalArgumentException if the source and target version have
	 *                                  different subjects or use the same version
	 * @throws IllegalStateException if persisted review state cannot be read
	 */
	Optional<CurriculumMappingReviewOutcome> findOutcome(CurriculumNode source, SyllabusVersion targetVersion);
}
