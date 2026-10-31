package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ExamYearConverterTest {
	@Test
	void suggestedYearsStartAt2020AndAdvanceAcrossCalendarRollover() {
		var before = ExamYearConverter.defaultYears(Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC));
		var after = ExamYearConverter.defaultYears(Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));
		assertEquals(2020, before.getFirst());
		assertEquals(2028, before.getLast());
		assertEquals(2029, after.getLast());
	}

	@Test
	void acceptsFutureAndHistoricalYearsWithoutRestrictingToSuggestions() {
		assertEquals(2030, ExamYearConverter.parse("2030"));
		assertEquals(1998, ExamYearConverter.parse(" 1998 "));
		assertEquals(9999, ExamYearConverter.parse("9999"));
	}

	@Test
	void rejectsBlankMalformedAndNonFourDigitYearsWithoutThrowing() {
		assertNull(ExamYearConverter.parse(null));
		for (String text : new String[] {"", " ", "year", "203", "0203", "20300", "20.30", "+2030", "-2030"}) {
			assertNull(new ExamYearConverter().fromString(text), text);
		}
	}
}
