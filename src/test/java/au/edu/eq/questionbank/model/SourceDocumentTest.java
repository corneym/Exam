package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SourceDocumentTest {

	@Test
	void retainsItsIdAndDataRootRelativePath() {
		SourceDocument document = new SourceDocument(9, "chemistry/QCAA/2025/paper-1.pdf");

		assertAll(() -> assertEquals(9, document.getId()),
				() -> assertEquals("chemistry/QCAA/2025/paper-1.pdf", document.getRelativePath()));
	}
}
