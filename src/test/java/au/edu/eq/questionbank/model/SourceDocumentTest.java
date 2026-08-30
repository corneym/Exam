package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SourceDocumentTest {

	@Test
	void retainsItsIdAndDataRootRelativePath() {
		SourceDocument document = new SourceDocument(9, "chemistry/QCAA/2025/paper-1.pdf");

		assertAll(() -> assertEquals(9, document.getId()),
				() -> assertEquals("chemistry/QCAA/2025/paper-1.pdf", document.getRelativePath()));
	}

	@Test
	void rejectsInvalidPersistentValues() {
		assertAll(() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(0, "exam.pdf")),
				() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(1, null)),
				() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(1, " ")));
	}
}
