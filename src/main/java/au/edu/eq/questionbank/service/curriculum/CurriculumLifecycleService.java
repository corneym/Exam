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

	/**
	 * Creates a coordinator for saving drafts and changing curriculum lifecycle
	 * state.
	 *
	 * @param authoringWriter     transactional draft writer
	 * @param lifecycleRepository persisted finalisation and reopening operations
	 * @param clock               source of finalisation timestamps
	 */
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

	/**
	 * Validates and saves an in-progress draft, then marks its curriculum final
	 * using this service's clock. Replaces the session's syllabus snapshot on
	 * success; the current/historical flag is unchanged.
	 * <p>
	 * Draft saving and the lifecycle update are separate transactions. Failure of
	 * the latter does not undo a successfully saved draft.
	 *
	 * @param session in-progress authoring session to finalise
	 * @return final syllabus snapshot, also installed in the session
	 * @throws NullPointerException     if {@code session} is {@code null}
	 * @throws IllegalArgumentException if the draft is structurally invalid
	 * @throws IllegalStateException    if the curriculum is not in progress or
	 *                                  saving or the lifecycle update is rejected
	 */
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

		// Save first so a failed lifecycle update leaves the authored draft persisted.
		// These are separate transactions; retry finalisation after resolving the
		// failure.
		authoringWriter.save(session);
		Instant finalisedAt = clock.instant();
		SyllabusVersion updated = lifecycleRepository.finalise(session.syllabusVersion(), finalisedAt);
		session.replaceSyllabusVersion(updated);
		return updated;
	}

	/**
	 * Explicitly returns a final curriculum to in-progress authoring and clears its
	 * finalisation timestamp. Does not reload or save the draft, change its
	 * persistent node identities, or change the current/historical flag.
	 *
	 * @param session session for the final curriculum
	 * @return in-progress syllabus snapshot, also installed in the session
	 * @throws NullPointerException  if {@code session} is {@code null}
	 * @throws IllegalStateException if the curriculum is not final or the persisted
	 *                               transition is rejected
	 */
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
