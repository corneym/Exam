package au.edu.eq.questionbank.ui.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LegacyQuestionMetadataDialogParsingTest {

	@Test
	void parsesOnlyPositiveWholeNumberMarks() {

		// The dialog's shared conversion boundary accepts valid marks while
		// representing malformed and non-positive entries as no parsed value.
		assertEquals(4, LegacyQuestionMetadataDialog.parsePositiveMarks("4"));
		assertEquals(12, LegacyQuestionMetadataDialog.parsePositiveMarks(" 12 "));
		assertNull(LegacyQuestionMetadataDialog.parsePositiveMarks(null));
		assertNull(LegacyQuestionMetadataDialog.parsePositiveMarks(""));
		assertNull(LegacyQuestionMetadataDialog.parsePositiveMarks("0"));
		assertNull(LegacyQuestionMetadataDialog.parsePositiveMarks("-1"));
		assertNull(LegacyQuestionMetadataDialog.parsePositiveMarks("not a number"));
	}
}
