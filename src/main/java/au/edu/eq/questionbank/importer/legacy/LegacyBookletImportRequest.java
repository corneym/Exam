package au.edu.eq.questionbank.importer.legacy;

import java.nio.file.Path;

/**
 * User-supplied information needed to create one missing legacy exam booklet.
 *
 * @param requirement    the missing provider, year, and booklet identity
 * @param assessmentName the assessment name used to find or create the exam
 * @param pdfPath        the source PDF selected for the booklet
 * @param answerPdfPath  optional answer or marking-guide PDF for the exam
 */
public record LegacyBookletImportRequest(LegacyBookletRequirement requirement, String assessmentName, Path pdfPath,
		Path answerPdfPath) {

	public LegacyBookletImportRequest(LegacyBookletRequirement requirement, String assessmentName, Path pdfPath) {
		this(requirement, assessmentName, pdfPath, null);
	}

	public LegacyBookletImportRequest {
		if (requirement == null) {
			throw new NullPointerException("requirement");
		}
		if (assessmentName == null || assessmentName.isBlank()) {
			throw new IllegalArgumentException("assessmentName must not be blank");
		}
		if (pdfPath == null) {
			throw new NullPointerException("pdfPath");
		}
		assessmentName = assessmentName.trim();
		pdfPath = pdfPath.toAbsolutePath().normalize();
		if (answerPdfPath != null) {
			answerPdfPath = answerPdfPath.toAbsolutePath().normalize();
		}
	}
}