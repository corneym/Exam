package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ExamTest {

	@Test
	void retainsItsSubjectAndExamMetadata() {
		Subject subject = new Subject(7, "Physics");
		Exam exam = new Exam(12, subject, new ExamProvider(3, "QCAA"), 2025, "External assessment");

		assertAll(() -> assertEquals(12, exam.getId()), () -> assertSame(subject, exam.getSubject()),
				() -> assertEquals(2025, exam.getYear()), () -> assertEquals("External assessment", exam.getName()));
	}
}
