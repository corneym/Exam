package au.edu.eq.questionbank.ui.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LegacyQuestionSplitDialogParsingTest {

	@Test
	void parsesOnlyPositiveWholeNumberMarks() {

		// Split-part parsing accepts valid positive marks and converts every
		// malformed or non-positive entry to an absent parsed value.
		assertEquals(3, LegacyQuestionSplitDialog.parsePositiveMarks("3"));
		assertEquals(12, LegacyQuestionSplitDialog.parsePositiveMarks(" 12 "));
		assertNull(LegacyQuestionSplitDialog.parsePositiveMarks(null));
		assertNull(LegacyQuestionSplitDialog.parsePositiveMarks(""));
		assertNull(LegacyQuestionSplitDialog.parsePositiveMarks("0"));
		assertNull(LegacyQuestionSplitDialog.parsePositiveMarks("-1"));
		assertNull(LegacyQuestionSplitDialog.parsePositiveMarks("abc"));
	}
}
