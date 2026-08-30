package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExamTest {

	@Test
	void retainsItsSubjectAndExamMetadata() {
		Subject subject = new Subject(7, "Physics");
		Exam exam = new Exam(12, subject, new ExamProvider(3, "QCAA"), 2025, "External assessment");

		assertAll(() -> assertEquals(12, exam.getId()), () -> assertSame(subject, exam.getSubject()),
				() -> assertEquals(2025, exam.getYear()), () -> assertEquals("External assessment", exam.getName()));
	}

	@Test
	void rejectsInvalidPersistentValues() {
		Subject subject = new Subject(7, "Physics");
		ExamProvider provider = new ExamProvider(3, "QCAA");

		assertAll(() -> assertThrows(IllegalArgumentException.class,
				() -> new Exam(0, subject, provider, 2025, "External assessment")),
				() -> assertThrows(NullPointerException.class,
						() -> new Exam(1, null, provider, 2025, "External assessment")),
				() -> assertThrows(NullPointerException.class,
						() -> new Exam(1, subject, null, 2025, "External assessment")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Exam(1, subject, provider, 0, "External assessment")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Exam(1, subject, provider, 2025, " ")));
	}
}
