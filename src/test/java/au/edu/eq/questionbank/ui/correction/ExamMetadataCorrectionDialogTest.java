package au.edu.eq.questionbank.ui.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ExamMetadataCorrectionDialogTest {

	@Test
	void parsesOnlyPositiveWholeNumberYears() {

		// The shared conversion boundary accepts valid Exam years and represents
		// malformed or non-positive user input as no parsed value.
		assertEquals(2025, ExamMetadataCorrectionDialog.parsePositiveYear("2025"));
		assertEquals(2026, ExamMetadataCorrectionDialog.parsePositiveYear(" 2026 "));
		assertNull(ExamMetadataCorrectionDialog.parsePositiveYear(null));
		assertNull(ExamMetadataCorrectionDialog.parsePositiveYear(""));
		assertNull(ExamMetadataCorrectionDialog.parsePositiveYear("0"));
		assertNull(ExamMetadataCorrectionDialog.parsePositiveYear("-1"));
		assertNull(ExamMetadataCorrectionDialog.parsePositiveYear("twenty twenty-five"));
	}
}
