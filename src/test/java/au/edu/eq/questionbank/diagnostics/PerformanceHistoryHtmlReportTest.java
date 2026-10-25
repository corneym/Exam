package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceHistoryHtmlReportTest {

	@TempDir
	Path tempDir;

	@Test
	void absentHistoryDoesNotProduceMisleadingReport() {
		Path output = tempDir.resolve("report.html");
		assertThrows(IOException.class,
				() -> new PerformanceHistoryHtmlReport().generate(tempDir.resolve("missing-history"), output));
		assertFalse(Files.exists(output));
	}

	@Test
	void escapedVersionCannotInjectMarkupIntoReport() throws Exception {
		Path history = tempDir.resolve("history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(history,
				checkpoint(machine, session, "<img src=x onerror=alert(1)>", 100), List.of(measurement(session, 100)));
		Path output = tempDir.resolve("safe-report.html");
		new PerformanceHistoryHtmlReport().generate(history, output);
		String html = Files.readString(output);
		assertFalse(html.contains("<img src=x"));
		assertTrue(html.contains("\\u003cimg"));
	}

	@Test
	void generatesIndependentMachineTrendsFromPermanentHistory() throws Exception {
		Path history = tempDir.resolve("history");
		UUID homeMachine = UUID.randomUUID();
		UUID workMachine = UUID.randomUUID();
		UUID homeSession = UUID.randomUUID();
		UUID workSession = UUID.randomUUID();
		PerformanceHistoryStore store = new PerformanceHistoryStore();
		store.writeCheckpoint(history, checkpoint(homeMachine, homeSession, "0.4", 800),
				List.of(measurement(homeSession, 200)));
		store.writeCheckpoint(history, checkpoint(workMachine, workSession, "0.4", 800),
				List.of(measurement(workSession, 800)));
		Path output = tempDir.resolve("performance-report.html");
		new PerformanceHistoryHtmlReport().generate(history, output);
		String html = Files.readString(output);
		assertTrue(html.contains("<svg"));
		assertTrue(html.contains("Questions in corpus"));
		assertTrue(html.contains("dashboard.refresh"));
		assertTrue(html.contains(homeMachine.toString()));
		assertTrue(html.contains(workMachine.toString()));
		assertTrue(html.contains("\"median\":200.0"));
		assertTrue(html.contains("\"median\":800.0"));

		// The report must not fetch resources from the network.
		assertFalse(html.contains("<script src="));
		assertFalse(html.contains("<link rel=\"stylesheet\""));
	}

	@Test
	void htmlTemplateIsPackagedAndOffline() throws Exception {
		try (InputStream input = PerformanceHistoryHtmlReport.class
				.getResourceAsStream("performance-history-report.html")) {
			assertTrue(input != null);
			String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(template.contains("<svg"));
			assertTrue(template.contains("__OBSERVATIONS__"));
			assertTrue(template.contains("Questions in corpus"));
			assertTrue(template.contains("id=\"runtime\""));
			assertFalse(template.contains("<script src="));
			assertFalse(template.contains("<link rel=\"stylesheet\""));
		}
	}

	@Test
	void reportDoesNotOverwriteExistingFile() throws Exception {
		Path history = tempDir.resolve("history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(history, checkpoint(machine, session, "0.4", 800),
				List.of(measurement(session, 100)));
		Path output = tempDir.resolve("existing.html");
		Files.writeString(output, "existing report");
		assertThrows(IOException.class, () -> new PerformanceHistoryHtmlReport().generate(history, output));
		assertTrue(Files.readString(output).equals("existing report"));
	}

	private PerformanceHistoryStore.Checkpoint checkpoint(UUID machine, UUID session, String version,
			long questionCount) {
		return new PerformanceHistoryStore.Checkpoint(machine, session, 0, Instant.parse("2026-10-24T00:00:00Z"),
				version, "eclipse", 24, questionCount, 819200);
	}

	private PerformanceCsvReader.Entry measurement(UUID session, double elapsedMillis) {
		return new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 1, 0, "dashboard.refresh",
				elapsedMillis, true, 1);
	}
}
