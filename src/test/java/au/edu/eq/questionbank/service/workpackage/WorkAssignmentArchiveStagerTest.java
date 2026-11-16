package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;

class WorkAssignmentArchiveStagerTest {

	@TempDir
	Path directory;

	@Test
	void rejectsInvalidArchiveBeforeCreatingPackageStage() throws Exception {
		Path incoming = directory.resolve("assignment.examwork");
		Files.writeString(incoming, "Not a valid archive");
		assertThrows(IOException.class, () -> stager().stage(incoming));
		Path root = directory.resolve(".assignment-install-staging");
		if (Files.exists(root)) {
			try (var entries = Files.list(root)) {
				assertEquals(0, entries.count());
			}
		}
	}

	@Test
	void secondStageDoesNotOverwritePendingEvidence() throws Exception {
		Path incoming = WorkAssignmentArchiveFixture.write(directory.resolve("assignment.examwork"));
		WorkAssignmentArchiveStager stager = stager();
		var staged = stager.stage(incoming);
		assertThrows(IOException.class, () -> stager.stage(incoming));
		assertTrue(Files.isRegularFile(staged.archive()));
		stager.discard(staged);
	}

	@Test
	void stageCanBeRepeatedAfterSafeDiscard() throws Exception {
		Path incoming = WorkAssignmentArchiveFixture.write(directory.resolve("assignment.examwork"));
		WorkAssignmentArchiveStager stager = stager();
		var first = stager.stage(incoming);
		stager.discard(first);
		var second = stager.stage(incoming);
		assertEquals(first.inspection().manifest().packageId(), second.inspection().manifest().packageId());
		assertTrue(Files.isRegularFile(second.archive()));
		stager.discard(second);
	}

	@Test
	void stagesVerifiedWholeExamWithoutPublishingAssets() throws Exception {
		Path incoming = WorkAssignmentArchiveFixture.write(directory.resolve("assignment.examwork"));
		WorkAssignmentArchiveStager stager = stager();
		var staged = stager.stage(incoming);
		assertTrue(Files.isRegularFile(staged.archive()));
		assertArrayEquals(Files.readAllBytes(incoming), Files.readAllBytes(staged.archive()));
		assertEquals(2, staged.inspection().assets().references().size());
		assertEquals(2, staged.inspection().records().stream()
				.filter(record -> record.recordType().equals("EXAM_BOOKLET")).count());
		assertFalse(Files.exists(directory.resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		assertFalse(Files.exists(directory.resolve(WorkAssignmentArchiveFixture.ANSWER_PATH)));
		stager.discard(staged);
		assertFalse(Files.exists(staged.directory()));
	}

	private WorkAssignmentArchiveStager stager() {
		return new WorkAssignmentArchiveStager(ApplicationConfig.fromDataRoot(directory));
	}
}
