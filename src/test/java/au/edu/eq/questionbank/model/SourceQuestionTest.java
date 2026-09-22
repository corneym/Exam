package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceQuestionTest {

	private ExamBooklet booklet;

	@Test
	void rejectsInvalidMetadata() {
		assertAll(() -> assertThrows(IllegalArgumentException.class, () -> new SourceQuestion(0, booklet, "21")),
				() -> assertThrows(NullPointerException.class, () -> new SourceQuestion(1, null, "21")),
				() -> assertThrows(IllegalArgumentException.class, () -> new SourceQuestion(1, booklet, null)),
				() -> assertThrows(IllegalArgumentException.class, () -> new SourceQuestion(1, booklet, " ")),
				() -> assertThrows(NullPointerException.class, () -> new SourceQuestion(1, booklet, "21", null)));
	}

	@Test
	void retainsExplicitPreambleStatus() {
		SourceQuestion sourceQuestion = new SourceQuestion(10, booklet, "21", SharedContextStatus.PRESENT);
		assertEquals(SharedContextStatus.PRESENT, sourceQuestion.getSharedContextStatus());
	}

	@Test
	void retainsPersistentIdentityAndSourceCode() {
		SourceQuestion sourceQuestion = new SourceQuestion(10, booklet, "21");
		assertAll(() -> assertEquals(10, sourceQuestion.getId()),
				() -> assertSame(booklet, sourceQuestion.getBooklet()),
				() -> assertEquals("21", sourceQuestion.getSourceQuestionCode()),
				() -> assertEquals(SharedContextStatus.UNKNOWN, sourceQuestion.getSharedContextStatus()));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2025, "External assessment");
		booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "paper-1.pdf"));
	}
}
