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

	/**
	 * Creates a booklet import request without an answer PDF.
	 *
	 * @param requirement missing booklet identity
	 * @param assessmentName exam name used for lookup or creation
	 * @param pdfPath source booklet PDF
	 */
	public LegacyBookletImportRequest(LegacyBookletRequirement requirement, String assessmentName, Path pdfPath) {
		this(requirement, assessmentName, pdfPath, null);
	}

	/**
	 * Creates a booklet import request, trimming the assessment name and normalising file paths.
	 *
	 * @param requirement    the missing provider, year, and booklet identity
	 * @param assessmentName the assessment name used to find or create the exam
	 * @param pdfPath        the source PDF selected for the booklet
	 * @param answerPdfPath  optional answer or marking-guide PDF for the exam
	 */
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
		// These are local input files; conversion to managed relative paths happens during exam import.
		assessmentName = assessmentName.trim();
		pdfPath = pdfPath.toAbsolutePath().normalize();
		if (answerPdfPath != null) {
			answerPdfPath = answerPdfPath.toAbsolutePath().normalize();
		}
	}
}
