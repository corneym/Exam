package au.edu.eq.questionbank.importer;

/**
 * A syllabus mapping reduced to the classification level used by questions.
 *
 * sourceCode is the 2019 subtopic code. targetCode is the 2025 subtopic code.
 */
public record CurriculumMappingImportRow(String sourceCode, String targetCode) {

	public CurriculumMappingImportRow {
		if (sourceCode == null || sourceCode.isBlank()) {
			throw new IllegalArgumentException("sourceCode must not be blank");
		}
		if (targetCode == null || targetCode.isBlank()) {
			throw new IllegalArgumentException("targetCode must not be blank");
		}
	}
}