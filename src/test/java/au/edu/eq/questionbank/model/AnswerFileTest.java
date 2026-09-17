package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnswerFileTest {

	private Exam exam;
	private SourceDocument sourceDocument;

	@Test
	void createsValidAnswerFile() {
		AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", sourceDocument);
		assertEquals(1, answerFile.getId());
		assertSame(exam, answerFile.getExam());
		assertEquals("Marking guide", answerFile.getName());
		assertSame(sourceDocument, answerFile.getSourceDocument());
	}

	@Test
	void rejectsBlankName() {
		assertThrows(IllegalArgumentException.class, () -> new AnswerFile(1, exam, " ", sourceDocument));
	}

	@Test
	void rejectsNonPositiveId() {
		assertThrows(IllegalArgumentException.class, () -> new AnswerFile(0, exam, "Marking guide", sourceDocument));
	}

	@Test
	void rejectsNullExam() {
		assertThrows(NullPointerException.class, () -> new AnswerFile(1, null, "Marking guide", sourceDocument));
	}

	@Test
	void rejectsNullSourceDocument() {
		assertThrows(NullPointerException.class, () -> new AnswerFile(1, exam, "Marking guide", null));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		exam = new Exam(1, subject, provider, 2025, "External Assessment");
		sourceDocument = new SourceDocument(1, "chemistry/QCAA/2025/marking-guide.pdf");
	}
}
