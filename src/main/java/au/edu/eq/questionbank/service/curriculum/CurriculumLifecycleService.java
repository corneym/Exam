package au.edu.eq.questionbank.service.curriculum;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumAuthoringWriter;
import au.edu.eq.questionbank.repository.curriculum.CurriculumLifecycleRepository;

/**
 * Coordinates curriculum finalisation and explicit reopening for editing.
 */
public final class CurriculumLifecycleService {

	private final CurriculumAuthoringWriter authoringWriter;
	private final CurriculumLifecycleRepository lifecycleRepository;
	private final Clock clock;

	public CurriculumLifecycleService(CurriculumAuthoringWriter authoringWriter,
			CurriculumLifecycleRepository lifecycleRepository, Clock clock) {
		if (authoringWriter == null) {
			throw new NullPointerException("authoringWriter");
		}
		if (lifecycleRepository == null) {
			throw new NullPointerException("lifecycleRepository");
		}
		if (clock == null) {
			throw new NullPointerException("clock");
		}
		this.authoringWriter = authoringWriter;
		this.lifecycleRepository = lifecycleRepository;
		this.clock = clock;
	}

	public SyllabusVersion finalise(CurriculumAuthoringSession session) {
		if (session == null) {
			throw new NullPointerException("session");
		}
		if (session.syllabusVersion().getCurriculumStatus() != CurriculumStatus.IN_PROGRESS) {
			throw new IllegalStateException("Curriculum is not in progress");
		}
		List<String> problems = session.draft().validationProblems();
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException("Cannot finalise invalid curriculum: " + String.join("; ", problems));
		}
		/*
		 * Finalisation always saves the current draft first. If the later lifecycle
		 * update fails, the safe result is still a persisted IN_PROGRESS curriculum
		 * that can be retried.
		 */
		authoringWriter.save(session);
		Instant finalisedAt = clock.instant();
		SyllabusVersion updated = lifecycleRepository.finalise(session.syllabusVersion(), finalisedAt);
		session.replaceSyllabusVersion(updated);
		return updated;
	}

	public SyllabusVersion reopen(CurriculumAuthoringSession session) {
		if (session == null) {
			throw new NullPointerException("session");
		}
		if (!session.syllabusVersion().isCurriculumFinal()) {
			throw new IllegalStateException("Only a final curriculum can be reopened");
		}
		SyllabusVersion updated = lifecycleRepository.reopen(session.syllabusVersion());
		session.replaceSyllabusVersion(updated);
		return updated;
	}
}
