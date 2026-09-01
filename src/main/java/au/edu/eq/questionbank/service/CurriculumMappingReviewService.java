package au.edu.eq.questionbank.service;

import java.sql.SQLException;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.repository.SqliteCurriculumMappingWriter;

/**
 * Applies human review decisions to curriculum mapping suggestions.
 */
public final class CurriculumMappingReviewService {
	private final SqliteCurriculumMappingWriter writer;

	public CurriculumMappingReviewService(SqliteCurriculumMappingWriter writer) {
		if (writer == null) {
			throw new NullPointerException("writer");
		}
		this.writer = writer;
	}

	public CurriculumMapping confirm(CurriculumMappingSuggestion suggestion) throws SQLException {
		if (suggestion == null) {
			throw new NullPointerException("suggestion");
		}
		return writer.insertMapping(suggestion.getSource(), suggestion.getTarget(), MappingStatus.CONFIRMED);
	}
}