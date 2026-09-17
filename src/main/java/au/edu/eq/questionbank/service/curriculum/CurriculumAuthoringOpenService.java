package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Application-facing service for choosing and opening an existing persisted
 * curriculum for authoring.
 */
public final class CurriculumAuthoringOpenService {

	private final CurriculumRepository curriculumRepository;
	private final CurriculumDraftLoader draftLoader;

	/**
	 * Creates an authoring opener using persisted curriculum lookup and draft
	 * loading.
	 *
	 * @param curriculumRepository subject and syllabus lookup
	 * @param draftLoader          resumable authoring-session loader
	 */
	public CurriculumAuthoringOpenService(CurriculumRepository curriculumRepository,
			CurriculumDraftLoader draftLoader) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (draftLoader == null) {
			throw new NullPointerException("draftLoader");
		}
		this.curriculumRepository = curriculumRepository;
		this.draftLoader = draftLoader;
	}

	/**
	 * Lists persisted syllabuses available for authoring across all subjects.
	 *
	 * @return immutable versions sorted by subject and version name, ignoring case
	 */
	public List<SyllabusVersion> availableVersions() {
		List<SyllabusVersion> versions = new ArrayList<>();
		for (Subject subject : curriculumRepository.findAllSubjects()) {
			versions.addAll(curriculumRepository.findVersionsForSubject(subject));
		}
		versions.sort(Comparator
				.comparing((SyllabusVersion version) -> version.getSubject().getName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(SyllabusVersion::getName, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(versions);
	}

	/**
	 * Loads the selected syllabus into a resumable authoring session.
	 *
	 * @param syllabusVersion persisted syllabus to open
	 * @return loaded draft with its persistent identity bindings
	 */
	public CurriculumAuthoringSession open(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		return draftLoader.load(syllabusVersion);
	}
}
