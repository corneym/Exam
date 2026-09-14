package au.edu.eq.questionbank.repository.curriculum;

import java.time.Instant;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Persistence boundary for curriculum lifecycle transitions.
 */
public interface CurriculumLifecycleRepository {

	SyllabusVersion finalise(SyllabusVersion syllabusVersion, Instant finalisedAt);

	SyllabusVersion reopen(SyllabusVersion syllabusVersion);
}
