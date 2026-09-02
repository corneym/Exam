package au.edu.eq.questionbank.importer.legacy;

public record LegacyQuestionImportResult(int insertedQuestions, int existingQuestions, int insertedAnswers) {

	public LegacyQuestionImportResult {
		if (insertedQuestions < 0) {
			throw new IllegalArgumentException("insertedQuestions must not be negative");
		}
		if (existingQuestions < 0) {
			throw new IllegalArgumentException("existingQuestions must not be negative");
		}
		if (insertedAnswers < 0) {
			throw new IllegalArgumentException("insertedAnswers must not be negative");
		}
	}
}
