package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomaticBackupRetentionTest {

	@TempDir
	Path tempDir;

	@Test
	void doesNothingWhenBackupDirectoryDoesNotExist() throws Exception {
		Path missingDirectory = tempDir.resolve("automatic");
		AutomaticBackupRetention retention = new AutomaticBackupRetention();
		assertDoesNotThrow(() -> retention.prune(missingDirectory));
		assertFalse(Files.exists(missingDirectory));
	}

	@Test
	void ignoresFilesThatOnlyResembleAutomaticBackups() throws Exception {
		Path olderBackup = tempDir.resolve("question-bank-auto-2026-09-05T120000000Z.zip");
		Path newerBackup = tempDir.resolve("question-bank-auto-2026-09-05T120001000Z.zip");
		Path invalidLookalike = tempDir.resolve("question-bank-auto-ZZZ.zip");
		Files.writeString(olderBackup, "older");
		Files.writeString(newerBackup, "newer");
		Files.writeString(invalidLookalike, "unrelated");
		AutomaticBackupRetention retention = new AutomaticBackupRetention(1);
		retention.prune(tempDir);
		assertFalse(Files.exists(olderBackup));
		assertTrue(Files.exists(newerBackup));
		assertTrue(Files.exists(invalidLookalike));
	}

	@Test
	void ignoresFullBackupsAndUnrelatedFiles() throws Exception {
		for (int index = 0; index < 3; index++) {
			Files.writeString(tempDir.resolve("question-bank-auto-2026-09-05T12000" + index + "000Z.zip"), "automatic");
		}
		Path fullBackup = tempDir.resolve("question-bank-full-2026-09-05T120100000Z.zip");
		Path unrelatedZip = tempDir.resolve("other.zip");
		Path textFile = tempDir.resolve("notes.txt");
		Files.writeString(fullBackup, "full");
		Files.writeString(unrelatedZip, "other");
		Files.writeString(textFile, "notes");
		AutomaticBackupRetention retention = new AutomaticBackupRetention(1);
		retention.prune(tempDir);
		assertTrue(Files.exists(fullBackup));
		assertTrue(Files.exists(unrelatedZip));
		assertTrue(Files.exists(textFile));
		long automaticCount;
		try (Stream<Path> stream = Files.list(tempDir)) {
			automaticCount = stream.filter(path -> path.getFileName().toString().startsWith("question-bank-auto-"))
					.count();
		}
		assertEquals(1, automaticCount);
	}

	@Test
	void keepsTenMostRecentAutomaticBackups() throws Exception {
		for (int index = 0; index < 12; index++) {
			String fileName = String.format("question-bank-auto-2026-09-05T1200%02d000Z.zip", index);
			Files.writeString(tempDir.resolve(fileName), "backup-" + index);
		}
		AutomaticBackupRetention retention = new AutomaticBackupRetention();
		retention.prune(tempDir);
		List<String> remaining;
		try (Stream<Path> stream = Files.list(tempDir)) {
			remaining = stream.map(path -> path.getFileName().toString()).sorted().toList();
		}
		assertEquals(10, remaining.size());
		assertFalse(remaining.contains("question-bank-auto-2026-09-05T120000000Z.zip"));
		assertFalse(remaining.contains("question-bank-auto-2026-09-05T120001000Z.zip"));
		assertTrue(remaining.contains("question-bank-auto-2026-09-05T120011000Z.zip"));
	}

	@Test
	void rejectsBackupLocationThatIsNotDirectory() throws Exception {
		Path file = tempDir.resolve("automatic");
		Files.writeString(file, "not a directory");
		AutomaticBackupRetention retention = new AutomaticBackupRetention();
		assertThrows(IOException.class, () -> retention.prune(file));
	}

	@Test
	void rejectsInvalidRetentionLimit() {
		assertThrows(IllegalArgumentException.class, () -> new AutomaticBackupRetention(0));
		assertThrows(IllegalArgumentException.class, () -> new AutomaticBackupRetention(-1));
	}

	@Test
	void rejectsNullDirectory() {
		AutomaticBackupRetention retention = new AutomaticBackupRetention();
		assertThrows(NullPointerException.class, () -> retention.prune(null));
	}
}
