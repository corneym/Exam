package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;

class WorkPackageFileNamesTest {

	@Test
	void assignmentAndContributionShareHumanReadableIdentity() {
		Subject subject = new Subject(1, "Biology");
		ExamProvider provider = new ExamProvider(2, "QCAA");
		Exam exam = new Exam(3, subject, provider, 2025, "External Assessment");
		LocalDateTime time = LocalDateTime.of(2026, 11, 24, 14, 30, 25);
		String assignment = WorkPackageFileNames.assignment(exam, "Test Contributor", time);
		String contribution = WorkPackageFileNames.contribution(exam, "Test Contributor", time);
		String commonPrefix = "Biology-QCAA-2025-External-Assessment" + "-Test-Contributor-20261124-143025";
		assertEquals(commonPrefix + "-assignment.examwork", assignment);
		assertEquals(commonPrefix + "-contribution.examwork", contribution);
	}

	@Test
	void filenamesRemoveUnsafeCharacters() {
		Exam exam = new Exam(3, new Subject(1, "Science / Biology"), new ExamProvider(2, "QCAA: Provider"), 2025,
				"External Assessment?");
		LocalDateTime time = LocalDateTime.of(2026, 11, 24, 14, 30, 25);
		String contribution = WorkPackageFileNames.contribution(exam, "Alex / Smith", time);
		assertEquals("Science-Biology-QCAA-Provider-2025" + "-External-Assessment-Alex-Smith"
				+ "-20261124-143025-contribution.examwork", contribution);
		assertTrue(contribution.matches("[A-Za-z0-9._-]+"));
		assertFalse(contribution.contains("/"));
		assertFalse(contribution.contains("\\"));
		assertFalse(contribution.contains(":"));
		assertFalse(contribution.contains("?"));
	}

	@Test
	void longDescriptionsRetainTimestampAndArchiveType() {
		Exam exam = new Exam(3, new Subject(1, "Biology"), new ExamProvider(2, "QCAA"), 2025,
				"Assessment-" + "A".repeat(180));
		LocalDateTime time = LocalDateTime.of(2026, 11, 24, 14, 30, 25);
		String assignment = WorkPackageFileNames.assignment(exam, "Contributor", time);
		String contribution = WorkPackageFileNames.contribution(exam, "Contributor", time);
		assertEquals(140 + "-20261124-143025-assignment.examwork".length(), assignment.length());
		assertEquals(140 + "-20261124-143025-contribution.examwork".length(), contribution.length());
		assertEquals(assignment.substring(0, 140), contribution.substring(0, 140));
		assertTrue(assignment.endsWith("-20261124-143025-assignment.examwork"));
		assertTrue(contribution.endsWith("-20261124-143025-contribution.examwork"));
	}
}
