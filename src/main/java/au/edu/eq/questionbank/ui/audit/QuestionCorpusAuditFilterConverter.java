package au.edu.eq.questionbank.ui.audit;

import java.util.function.Function;

import javafx.util.StringConverter;

/**
 * Formats selection-only Corpus Audit filter values.
 *
 * @param <T> filter value type
 */
final class QuestionCorpusAuditFilterConverter<T> extends StringConverter<T> {

	private final Function<T, String> formatter;

	QuestionCorpusAuditFilterConverter(Function<T, String> formatter) {
		if (formatter == null) {
			throw new NullPointerException("formatter");
		}

		// Keep display formatting explicit while retaining one implementation of the
		// selection-only StringConverter contract.
		this.formatter = formatter;
	}

	@Override
	public T fromString(String text) {

		// Corpus Audit ComboBoxes are selection-only; displayed text is never parsed
		// back into domain values.
		return null;
	}

	@Override
	public String toString(T value) {
		if (value == null) {
			return "";
		}

		// Delegate only the domain-specific display wording to the owning audit pane.
		return formatter.apply(value);
	}
}
