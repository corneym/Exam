package au.edu.eq.questionbank.service.curriculum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumSourcePdfRepository;

/**
 * Coordinates managed syllabus-PDF storage with persisted syllabus metadata.
 */
public final class CurriculumSourcePdfService {

	private final CurriculumSourcePdfStore store;
	private final CurriculumSourcePdfRepository repository;

	public CurriculumSourcePdfService(CurriculumSourcePdfStore store, CurriculumSourcePdfRepository repository) {
		if (store == null) {
			throw new NullPointerException("store");
		}
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		this.store = store;
		this.repository = repository;
	}

	/**
	 * Copies a syllabus PDF into managed curriculum storage and persists its
	 * managed relative path.
	 *
	 * @param session   current editable authoring session
	 * @param sourcePdf external or already-managed source PDF
	 * @return absolute path of the managed PDF
	 * @throws IOException if the file cannot be copied
	 */
	public Path attachPdf(CurriculumAuthoringSession session, Path sourcePdf) throws IOException {
		if (session == null) {
			throw new NullPointerException("session");
		}
		if (session.syllabusVersion().getCurriculumStatus() != CurriculumStatus.IN_PROGRESS) {
			throw new IllegalStateException("Final curriculum must be reopened before changing its source PDF");
		}
		String relativePath = store.managePdf(session.syllabusVersion(), sourcePdf);
		SyllabusVersion updated = repository.updateSourcePdfPath(session.syllabusVersion(), relativePath);
		session.replaceSyllabusVersion(updated);
		return store.resolveManagedPdf(relativePath);
	}

	/**
	 * Resolves the persisted managed source PDF for a syllabus version.
	 *
	 * @param syllabusVersion persisted syllabus version
	 * @return managed absolute path, or empty when no source PDF is attached
	 */
	public Optional<Path> resolvePdf(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		String relativePath = syllabusVersion.getSourcePdfPath();
		if (relativePath == null) {
			return Optional.empty();
		}
		return Optional.of(store.resolveManagedPdf(relativePath));
	}
}
