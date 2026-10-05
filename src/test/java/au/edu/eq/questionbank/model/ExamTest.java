package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExamTest {

	@Test
	void legacyConstructorCreatesActiveExam() {
		Subject subject = new Subject(7, "Physics");
		Exam exam = new Exam(12, subject, new ExamProvider(3, "QCAA"), 2025, "External assessment");

		// Existing callers represent editable Exams unless persistence says otherwise.
		assertEquals(ExamCaptureState.ACTIVE, exam.getCaptureState());
		assertFalse(exam.isComplete());
	}

	@Test
	void rejectsInvalidPersistentValues() {
		Subject subject = new Subject(7, "Physics");
		ExamProvider provider = new ExamProvider(3, "QCAA");
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Exam(0, subject, provider, 2025, "External assessment")),
				() -> assertThrows(NullPointerException.class,
						() -> new Exam(1, null, provider, 2025, "External assessment")),
				() -> assertThrows(NullPointerException.class,
						() -> new Exam(1, subject, null, 2025, "External assessment")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Exam(1, subject, provider, 0, "External assessment")),
				() -> assertThrows(IllegalArgumentException.class, () -> new Exam(1, subject, provider, 2025, " ")));
	}

	@Test
	void rejectsNullCaptureState() {
		Subject subject = new Subject(7, "Physics");
		ExamProvider provider = new ExamProvider(3, "QCAA");

		// Every reconstructed Exam must carry one explicit lifecycle state.
		assertThrows(NullPointerException.class,
				() -> new Exam(12, subject, provider, 2025, "External assessment", null));
	}

	@Test
	void retainsItsSubjectAndExamMetadata() {
		Subject subject = new Subject(7, "Physics");
		Exam exam = new Exam(12, subject, new ExamProvider(3, "QCAA"), 2025, "External assessment");
		assertAll(() -> assertEquals(12, exam.getId()), () -> assertSame(subject, exam.getSubject()),
				() -> assertEquals(2025, exam.getYear()), () -> assertEquals("External assessment", exam.getName()));
	}

	@Test
	void storesExplicitCompleteState() {
		Subject subject = new Subject(7, "Physics");
		Exam exam = new Exam(12, subject, new ExamProvider(3, "QCAA"), 2025, "External assessment",
				ExamCaptureState.COMPLETE);

		// Completion is a deliberate domain state rather than a calculated property.
		assertEquals(ExamCaptureState.COMPLETE, exam.getCaptureState());
		assertTrue(exam.isComplete());
	}
}
