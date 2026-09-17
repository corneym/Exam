package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExamBookletTest {

	private Exam exam;
	private SourceDocument sourceDocument;

	@Test
	void linksAnExamToItsSourceDocument() {
		ExamBooklet booklet = new ExamBooklet(1, exam, "Question and response booklet", sourceDocument);
		assertAll(() -> assertEquals(1, booklet.getId()), () -> assertSame(exam, booklet.getExam()),
				() -> assertEquals("Question and response booklet", booklet.getName()),
				() -> assertSame(sourceDocument, booklet.getSourceDocument()));
	}

	@Test
	void rejectsBlankNames() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new ExamBooklet(1, exam, null, sourceDocument)),
				() -> assertThrows(IllegalArgumentException.class, () -> new ExamBooklet(1, exam, "", sourceDocument)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new ExamBooklet(1, exam, " \t", sourceDocument)));
	}

	@Test
	void rejectsNonPositiveIds() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new ExamBooklet(0, exam, "Question booklet", sourceDocument)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new ExamBooklet(-1, exam, "Question booklet", sourceDocument)));
	}

	@Test
	void rejectsNullExam() {
		assertThrows(NullPointerException.class, () -> new ExamBooklet(1, null, "Question booklet", sourceDocument));
	}

	@Test
	void rejectsNullSourceDocument() {
		assertThrows(NullPointerException.class, () -> new ExamBooklet(1, exam, "Question booklet", null));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(2, "Chemistry");
		exam = new Exam(8, subject, new ExamProvider(3, "QCAA"), 2025, "External assessment");
		sourceDocument = new SourceDocument(5, "chemistry/QCAA/2025/question-booklet.pdf");
	}
}
