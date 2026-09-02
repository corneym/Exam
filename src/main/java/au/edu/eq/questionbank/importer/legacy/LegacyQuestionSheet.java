package au.edu.eq.questionbank.importer.legacy;

import java.util.List;

public record LegacyQuestionSheet(String providerName, List<LegacyQuestionRow> questions) {

	public LegacyQuestionSheet {
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		questions = List.copyOf(questions);
	}
}
