package au.edu.eq.questionbank.importer.legacy;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyQuestionRowTest {

	@Test
	void acceptsWholeNumberQuestionCodeAsText() {
		LegacyQuestionRow row = new LegacyQuestionRow(2023, "1", "1", 2, "2.1.4", null, false);
		assertEquals("1", row.questionCode());
	}

	@Test
	void convertsBlankAnswerToNull() {
		LegacyQuestionRow row = new LegacyQuestionRow(2022, "2", "4b", 2, "3.1.2", " ", false);
		assertNull(row.answer());
	}

	@Test
	void rejectsInvalidMarks() {
		assertThrows(IllegalArgumentException.class,
				() -> new LegacyQuestionRow(2023, "1", "21a", 0, "2.1.4", null, false));
	}

	@Test
	void rejectsUnknownPaperCode() {
		assertThrows(IllegalArgumentException.class,
				() -> new LegacyQuestionRow(2023, "3", "21a", 3, "2.1.4", null, false));
	}

	@Test
	void retainsLegacyQuestionMetadata() {
		LegacyQuestionRow row = new LegacyQuestionRow(2023, "1", "21a", 3, "2.1.4", null, true);
		assertAll(() -> assertEquals(2023, row.year()), () -> assertEquals("1", row.paperCode()),
				() -> assertEquals("21a", row.questionCode()), () -> assertEquals(3, row.marks()),
				() -> assertEquals("2.1.4", row.classificationCode()), () -> assertNull(row.answer()),
				() -> assertTrue(row.sharedContextCaptureRequired()));
	}

	@Test
	void retainsMcqAnswer() {
		LegacyQuestionRow row = new LegacyQuestionRow(2022, "MCQ", "7", 1, "1.2.3", "C", false);
		assertEquals("C", row.answer());
		assertFalse(row.sharedContextCaptureRequired());
	}
}
