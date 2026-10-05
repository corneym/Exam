package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SourceDocumentTest {

	@Test
	void rejectsInvalidPersistentValues() {
		assertAll(() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(0, "exam.pdf")),
				() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(1, null)),
				() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(1, " ")));
	}

	@Test
	void retainsCanonicalContentHashAndRejectsMalformedHashes() {
		String hash = "0123456789abcdef".repeat(4);
		SourceDocument document = new SourceDocument(9, "chemistry/QCAA/2025/paper-1.pdf", hash);
		assertEquals(hash, document.getContentSha256());

		// SHA-256 persistence uses one canonical representation so equality and
		// database lookup do not need case normalisation.
		assertAll(() -> assertThrows(IllegalArgumentException.class, () -> new SourceDocument(1, "exam.pdf", "abc")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SourceDocument(1, "exam.pdf", "g".repeat(64))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SourceDocument(1, "exam.pdf", "A".repeat(64))));
	}

	@Test
	void retainsItsIdAndDataRootRelativePath() {
		SourceDocument document = new SourceDocument(9, "chemistry/QCAA/2025/paper-1.pdf");
		assertAll(() -> assertEquals(9, document.getId()),
				() -> assertEquals("chemistry/QCAA/2025/paper-1.pdf", document.getRelativePath()),
				() -> assertNull(document.getContentSha256()));
	}
}
