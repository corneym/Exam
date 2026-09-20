package au.edu.eq.questionbank.repository.curriculum;

import java.time.Instant;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Persistence boundary for curriculum lifecycle transitions.
 */
public interface CurriculumLifecycleRepository {

	/**
	 * Marks the persisted curriculum final at the supplied instant.
	 *
	 * @param syllabusVersion syllabus to finalise
	 * @param finalisedAt     finalisation timestamp
	 * @return updated syllabus snapshot
	 */
	SyllabusVersion finalise(SyllabusVersion syllabusVersion, Instant finalisedAt);

	/**
	 * Reopens a final curriculum for editing and clears its finalisation timestamp.
	 *
	 * @param syllabusVersion syllabus to reopen
	 * @return updated in-progress syllabus snapshot
	 */
	SyllabusVersion reopen(SyllabusVersion syllabusVersion);
}
