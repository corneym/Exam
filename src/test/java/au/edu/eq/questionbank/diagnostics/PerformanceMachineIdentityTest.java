package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceMachineIdentityTest {

	@TempDir
	Path tempDir;

	@Test
	void damagedIdentityIsNotSilentlyReplaced() throws Exception {
		Path diagnostics = Files.createDirectories(tempDir.resolve("diagnostics"));
		Path identityFile = diagnostics.resolve("machine.id");
		Files.writeString(identityFile, "invalid-identity");
		assertThrows(IOException.class, () -> PerformanceMachineIdentity.loadOrCreate(diagnostics));
		assertEquals("invalid-identity", Files.readString(identityFile));
	}

	@Test
	void identitySurvivesRepeatedApplicationSessions() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID first = PerformanceMachineIdentity.loadOrCreate(diagnostics);
		UUID second = PerformanceMachineIdentity.loadOrCreate(diagnostics);
		assertEquals(first, second);
		Path identityFile = diagnostics.resolve("machine.id");
		assertTrue(Files.isRegularFile(identityFile));
		assertEquals(first.toString(), Files.readString(identityFile).trim());
	}

	@Test
	void independentMachinesHaveDifferentIdentities() throws Exception {
		UUID home = PerformanceMachineIdentity.loadOrCreate(tempDir.resolve("home-diagnostics"));
		UUID work = PerformanceMachineIdentity.loadOrCreate(tempDir.resolve("work-diagnostics"));
		assertNotEquals(home, work);
	}

	@Test
	void simultaneousInitialisationRetainsOneIdentity() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			List<Future<UUID>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				results.add(executor.submit(() -> PerformanceMachineIdentity.loadOrCreate(diagnostics)));
			}
			UUID expected = results.getFirst().get();
			for (Future<UUID> result : results) {
				assertEquals(expected, result.get());
			}
			assertEquals(expected.toString(), Files.readString(diagnostics.resolve("machine.id")).trim());
		}
	}
}
