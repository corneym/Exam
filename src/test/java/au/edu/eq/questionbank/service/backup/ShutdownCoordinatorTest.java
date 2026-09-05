package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShutdownCoordinatorTest {

	@TempDir
	Path tempDir;

	@Test
	void backupFailureLeavesResourcesOpenAndCanBeRetried() throws Exception {
		BackupRequest request = automaticRequest();
		CountingBackupService backupService = new CountingBackupService();
		backupService.failNextBackup = true;
		CountingCloseable resources = new CountingCloseable();
		ShutdownCoordinator coordinator = new ShutdownCoordinator(backupService, request,
				new AutomaticBackupRetention(), resources);
		ShutdownResult first = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.BACKUP_FAILED, first.status());
		assertFalse(coordinator.isReadyToExit());
		assertFalse(first.exitAllowed());
		assertEquals(1, backupService.callCount);
		assertEquals(0, resources.closeCount);
		ShutdownResult second = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.READY_TO_EXIT, second.status());
		assertTrue(second.exitAllowed());
		assertEquals(2, backupService.callCount);
		assertEquals(1, resources.closeCount);
	}

	@Test
	void duplicateShutdownDoesNotRepeatBackupOrResourceClose() throws Exception {
		BackupRequest request = automaticRequest();
		CountingBackupService backupService = new CountingBackupService();
		CountingCloseable resources = new CountingCloseable();
		ShutdownCoordinator coordinator = new ShutdownCoordinator(backupService, request,
				new AutomaticBackupRetention(), resources);
		ShutdownResult first = coordinator.prepareForExit();
		ShutdownResult second = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.READY_TO_EXIT, first.status());
		assertEquals(ShutdownStatus.ALREADY_READY, second.status());
		assertEquals(1, backupService.callCount);
		assertEquals(1, resources.closeCount);
	}

	@Test
	void exitWithoutBackupClosesResourcesWithoutCreatingBackup() throws Exception {
		BackupRequest request = automaticRequest();
		CountingBackupService backupService = new CountingBackupService();
		CountingCloseable resources = new CountingCloseable();
		ShutdownCoordinator coordinator = new ShutdownCoordinator(backupService, request,
				new AutomaticBackupRetention(), resources);
		ShutdownResult result = coordinator.exitWithoutBackup();
		assertEquals(ShutdownStatus.READY_TO_EXIT, result.status());
		assertTrue(result.exitAllowed());
		assertEquals(0, backupService.callCount);
		assertEquals(1, resources.closeCount);
	}

	@Test
	void rejectsNonAutomaticBackupRequest() {
		BackupRequest fullRequest = BackupRequest.full(tempDir);
		assertThrows(IllegalArgumentException.class, () -> new ShutdownCoordinator(new CountingBackupService(),
				fullRequest, new AutomaticBackupRetention(), new CountingCloseable()));
	}

	@Test
	void resourceCloseFailureDoesNotCreateSecondBackupOnRetry() throws Exception {
		BackupRequest request = automaticRequest();
		CountingBackupService backupService = new CountingBackupService();
		CountingCloseable resources = new CountingCloseable();
		resources.failNextClose = true;
		ShutdownCoordinator coordinator = new ShutdownCoordinator(backupService, request,
				new AutomaticBackupRetention(), resources);
		ShutdownResult first = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.RESOURCE_CLOSE_FAILED, first.status());
		assertFalse(first.exitAllowed());
		assertEquals(1, backupService.callCount);
		assertEquals(1, resources.closeCount);
		ShutdownResult second = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.READY_TO_EXIT, second.status());
		assertTrue(second.exitAllowed());
		assertEquals(1, backupService.callCount);
		assertEquals(2, resources.closeCount);
	}

	@Test
	void retentionFailureStillAllowsExitAfterSuccessfulBackup() throws Exception {
		Path notDirectory = tempDir.resolve("automatic");
		Files.writeString(notDirectory, "not a directory");
		BackupRequest request = new BackupRequest(BackupKind.AUTOMATIC_DATABASE, notDirectory);
		CountingBackupService backupService = new CountingBackupService();
		CountingCloseable resources = new CountingCloseable();
		ShutdownCoordinator coordinator = new ShutdownCoordinator(backupService, request,
				new AutomaticBackupRetention(), resources);
		ShutdownResult result = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.READY_TO_EXIT_WITH_RETENTION_WARNING, result.status());
		assertTrue(result.exitAllowed());
		assertNotNull(result.failure());
		assertEquals(1, backupService.callCount);
		assertEquals(1, resources.closeCount);
	}

	@Test
	void successfulShutdownCreatesBackupPrunesAndClosesResources() throws Exception {
		Path automaticDirectory = tempDir.resolve("automatic");
		Files.createDirectories(automaticDirectory);
		for (int index = 0; index < 11; index++) {
			Files.writeString(
					automaticDirectory.resolve(String.format("question-bank-auto-2026-09-05T1200%02d000Z.zip", index)),
					"old");
		}
		BackupRequest request = new BackupRequest(BackupKind.AUTOMATIC_DATABASE, automaticDirectory);
		CountingBackupService backupService = new CountingBackupService();
		CountingCloseable resources = new CountingCloseable();
		ShutdownCoordinator coordinator = new ShutdownCoordinator(backupService, request,
				new AutomaticBackupRetention(), resources);
		ShutdownResult result = coordinator.prepareForExit();
		assertEquals(ShutdownStatus.READY_TO_EXIT, result.status());
		assertTrue(result.exitAllowed());
		assertEquals(1, backupService.callCount);
		assertEquals(1, resources.closeCount);
		assertTrue(coordinator.isReadyToExit());
		long automaticBackupCount;
		try (Stream<Path> stream = Files.list(automaticDirectory)) {
			automaticBackupCount = stream
					.filter(path -> path.getFileName().toString().startsWith("question-bank-auto-")).count();
		}
		assertEquals(10, automaticBackupCount);
	}

	private BackupRequest automaticRequest() throws IOException {
		Path directory = tempDir.resolve("automatic");
		Files.createDirectories(directory);
		return new BackupRequest(BackupKind.AUTOMATIC_DATABASE, directory);
	}

	private static final class CountingBackupService implements BackupService {

		private int callCount;
		private boolean failNextBackup;

		@Override
		public BackupResult createBackup(BackupRequest request) throws BackupException {
			callCount++;
			if (failNextBackup) {
				failNextBackup = false;
				throw new BackupException("Deliberate test backup failure");
			}
			BackupManifest manifest = BackupManifest.current(request.kind(), Instant.parse("2026-09-05T03:00:00Z"), 4,
					"Test");
			return new BackupResult(request.destinationDirectory().resolve("question-bank-auto-test.zip"), manifest);
		}
	}

	private static final class CountingCloseable implements AutoCloseable {

		private int closeCount;
		private boolean failNextClose;

		@Override
		public void close() throws Exception {
			closeCount++;
			if (failNextClose) {
				failNextClose = false;
				throw new IOException("Deliberate test close failure");
			}
		}
	}
}
