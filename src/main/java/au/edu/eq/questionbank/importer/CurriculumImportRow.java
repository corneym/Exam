package au.edu.eq.questionbank.importer;

public record CurriculumImportRow(String code, String content) {

	public CurriculumImportRow {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}

		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
	}
}