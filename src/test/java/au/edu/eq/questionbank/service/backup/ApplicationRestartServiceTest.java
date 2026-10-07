package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationRestartServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void installedLauncherCanBeRestarted() throws Exception {
		Path executable = Files.writeString(tempDir.resolve("Exam Question Bank.exe"), "launcher");
		AtomicReference<Path> launched = new AtomicReference<>();
		ApplicationRestartService service = new ApplicationRestartService(() -> Optional.of(executable.toString()),
				launched::set);
		assertTrue(service.isRestartSupported());
		service.restart();
		assertEquals(executable.toAbsolutePath().normalize(), launched.get());
	}

	@Test
	void javaDevelopmentLauncherIsNotRestarted() throws Exception {
		Path executable = Files.writeString(tempDir.resolve("java.exe"), "java");
		AtomicInteger launchCount = new AtomicInteger();
		ApplicationRestartService service = new ApplicationRestartService(() -> Optional.of(executable.toString()),
				_ -> launchCount.incrementAndGet());
		assertFalse(service.isRestartSupported());
		IOException exception = assertThrows(IOException.class, service::restart);
		assertTrue(exception.getMessage().contains("installed Exam Question Bank"));
		assertEquals(0, launchCount.get());
	}

	@Test
	void launcherFailureIsReportedToCaller() throws Exception {
		Path executable = Files.writeString(tempDir.resolve("Exam Question Bank.exe"), "launcher");
		ApplicationRestartService service = new ApplicationRestartService(() -> Optional.of(executable.toString()),
				_ -> {
					throw new IOException("Deliberate launch failure");
				});
		IOException exception = assertThrows(IOException.class, service::restart);
		assertEquals("Deliberate launch failure", exception.getMessage());
	}

	@Test
	void missingProcessCommandIsNotRestarted() {
		AtomicInteger launchCount = new AtomicInteger();
		ApplicationRestartService service = new ApplicationRestartService(Optional::empty,
				_ -> launchCount.incrementAndGet());
		assertFalse(service.isRestartSupported());
		assertThrows(IOException.class, service::restart);
		assertEquals(0, launchCount.get());
	}
}
