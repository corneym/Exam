package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class SubjectSnapshotFileNamesTest {

	@Test
	void avoidsEmptyFilenameComponents() {
		assertEquals("Subject_SubjectSnapshot_20261112-164700.examwork",
				SubjectSnapshotFileNames.suggest("...", LocalDateTime.of(2026, 11, 12, 16, 47)));
	}

	@Test
	void stripsUnsafeFilenameCharacters() {
		assertEquals("Biology_2025_EA_SubjectSnapshot_20261112-164700.examwork",
				SubjectSnapshotFileNames.suggest("Biology: 2025 / EA", LocalDateTime.of(2026, 11, 12, 16, 47)));
	}

	@Test
	void suggestsSubjectAndTimestamp() {
		assertEquals("Biology_SubjectSnapshot_20261112-164700.examwork",
				SubjectSnapshotFileNames.suggest("Biology", LocalDateTime.of(2026, 11, 12, 16, 47)));
	}
}
