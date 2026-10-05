package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ExpectedQuestionCountDialogTest {

	@Test
	void parsesOnlyPositiveWholeNumberCounts() {

		// The dialog's shared conversion boundary accepts valid planning counts and
		// converts every invalid user entry to an absent parsed value.
		assertEquals(8, ExpectedQuestionCountDialog.parsePositiveCount("8"));
		assertEquals(12, ExpectedQuestionCountDialog.parsePositiveCount(" 12 "));
		assertNull(ExpectedQuestionCountDialog.parsePositiveCount(null));
		assertNull(ExpectedQuestionCountDialog.parsePositiveCount(""));
		assertNull(ExpectedQuestionCountDialog.parsePositiveCount("0"));
		assertNull(ExpectedQuestionCountDialog.parsePositiveCount("-1"));
		assertNull(ExpectedQuestionCountDialog.parsePositiveCount("abc"));
	}
}
