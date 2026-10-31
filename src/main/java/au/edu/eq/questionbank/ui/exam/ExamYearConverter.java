package au.edu.eq.questionbank.ui.exam;

import java.time.Clock;
import java.time.Year;
import java.util.List;
import java.util.stream.IntStream;

import javafx.util.StringConverter;

/** Non-throwing editor conversion; the pane displays validation for invalid input. */
final class ExamYearConverter extends StringConverter<Integer> {
	static List<Integer> defaultYears(Clock clock) {
		return IntStream.rangeClosed(2020, Math.min(9999, Math.max(2020, Year.now(clock).getValue() + 2)))
				.boxed().toList();
	}

	static Integer parse(String text) {
		return text != null && text.strip().matches("[1-9][0-9]{3}") ? Integer.valueOf(text.strip()) : null;
	}

	@Override
	public Integer fromString(String text) {
		return parse(text);
	}

	@Override
	public String toString(Integer year) {
		return year == null ? "" : year.toString();
	}
}
