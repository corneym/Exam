package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void htmlTemplateContainsAutomaticFindingsWithoutOnlineDependencies() throws Exception {
		try (InputStream input = PerformanceHistoryHtmlReport.class
				.getResourceAsStream("performance-history-report.html")) {
			assertTrue(input != null);
			String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(html.contains("Automatic performance review"));
			assertTrue(html.contains("id=\"automatic-findings\""));
			assertTrue(html.contains("legacyObservations = __LEGACY_OBSERVATIONS__"));
			assertFalse(html.contains("<script src="));
			assertFalse(html.contains("<link rel=\"stylesheet\""));
		}
	}

	@Test
	void htmlTemplateIsPackagedAndOffline() throws Exception {
		try (InputStream input = PerformanceHistoryHtmlReport.class
				.getResourceAsStream("performance-history-report.html")) {
			assertTrue(input != null);
			String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(template.contains("<svg"));
			assertTrue(template.contains("__OBSERVATIONS__"));
			assertTrue(template.contains("__SQL_OBSERVATIONS__"));
			assertTrue(template.contains("__LEGACY_OBSERVATIONS__"));
			assertTrue(template.contains("Questions in corpus"));
			assertTrue(template.contains("id=\"runtime\""));
			assertFalse(template.contains("<script src="));
			assertFalse(template.contains("<link rel=\"stylesheet\""));
		}
	}

	@Test
	void invalidLegacyInputNeverCreatesOrOverwritesReport() throws Exception {
		Path history = tempDir.resolve("history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(history, checkpoint(machine, session, "0.6", 626),
				List.of(measurement(session, 100)));
		Path invalid = tempDir.resolve("not-legacy.csv");
		Files.writeString(invalid, PerformanceRecorder.SESSION_HEADER + "\n");
		Path output = tempDir.resolve("invalid.html");
		assertThrows(IOException.class, () -> new PerformanceHistoryHtmlReport().generate(history, output, invalid));
		assertFalse(Files.exists(output));
		Files.writeString(output, "Original report");
		Path valid = tempDir.resolve("performance.csv");
		Files.writeString(valid,
				PerformanceRecorder.HEADER + "\n" + "2026-10-01T00:00:00Z,1,0,dashboard.refresh,20.0,true,1\n");
		assertThrows(IOException.class, () -> new PerformanceHistoryHtmlReport().generate(history, output, valid));
		assertTrue(Files.readString(output).equals("Original report"));
	}

	@Test
	void legacyOnlyReportIsClearlySeparatedAndDoesNotInventCorpusSizes() throws Exception {
		Path csv = tempDir.resolve("performance.csv");
		Files.writeString(csv,
				PerformanceRecorder.HEADER + "\n" + "2026-10-01T00:00:00Z,1,0,dashboard.refresh,10.0,true,1\n"
						+ "2026-10-02T00:00:00Z,1,0,dashboard.refresh,30.0,false,-1\n");
		Path output = tempDir.resolve("legacy-only.html");
		new PerformanceHistoryHtmlReport().generate(tempDir.resolve("no-modern-history"), output, csv);
		String html = Files.readString(output);
		assertTrue(html.contains("Legacy performance.csv (unattributed)"));
		assertTrue(html.contains("\"operation\":\"dashboard.refresh\""));
		assertTrue(html.contains("\"samples\":2"));
		assertTrue(html.contains("\"failures\":1"));
		assertTrue(html.contains("\"median\":20.0"));
		assertTrue(html.contains("const observations = [];"));
		assertFalse(html.contains("__LEGACY_OBSERVATIONS__"));
		assertFalse(html.contains("\"questions\":-1"));
		try (var rows = Files.lines(csv)) {
			assertEquals(3L, rows.count());
		}
	}

	@Test
	void legacySqlMetricElapsedTimeIsNotMisreportedAsSqlActivity() throws Exception {
		Path history = tempDir.resolve("legacy-sql-history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(history, checkpoint(machine, session, "0.6", 626),
				List.of(new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 1, 0,
						"dashboard.audit.subject.sql.execute-invocations", 0.002, true, 15),
						measurement(session, 150.0)));
		Path output = tempDir.resolve("legacy-sql.html");
		new PerformanceHistoryHtmlReport().generate(history, output);
		String html = Files.readString(output);
		assertTrue(html.contains("const sqlObservations = [];"));
		String timings = html.substring(html.indexOf("const observations = "),
				html.indexOf("const legacyObservations = "));
		assertFalse(timings.contains(".sql.execute-invocations"));
		assertTrue(timings.contains("dashboard.refresh"));
	}

	@Test
	void mixedReportKeepsModernTrendSeparateFromLegacyAggregates() throws Exception {
		Path history = tempDir.resolve("history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(history, checkpoint(machine, session, "0.6", 626),
				List.of(measurement(session, 120)));
		Path legacy = tempDir.resolve("old-performance.csv");
		Files.writeString(legacy,
				PerformanceRecorder.HEADER + "\n" + "2026-09-01T00:00:01Z,1,0,search.refresh,42.0,true,1\n");
		Path output = tempDir.resolve("combined.html");
		new PerformanceHistoryHtmlReport().generate(history, output, legacy);
		String html = Files.readString(output);
		assertTrue(html.contains("\"questions\":626"));
		assertTrue(html.contains("\"operation\":\"search.refresh\""));
		assertTrue(html.contains("\"median\":42.0"));
		assertTrue(html.contains("const legacyObservations = ["));
		assertFalse(html.contains(legacy.toAbsolutePath().toString()));
	}

	@Test
	void pairedPercentilesAreRenderedForBothModernCharts() throws Exception {
		try (InputStream input = PerformanceHistoryHtmlReport.class
				.getResourceAsStream("performance-history-report.html")) {
			assertTrue(input != null);
			String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			// Both views use the same paired-percentile renderer and distinct cohort
			// legends.
			assertTrue(template.contains("function renderPercentileChart("));
			assertTrue(template.contains("{field:\"median\", label:\"Median\", dash:null}"));
			assertTrue(template.contains("{field:\"p90\", label:\"P90\", dash:\"7 5\"}"));
			assertTrue(template.contains("line[\"stroke-dasharray\"] = percentile.dash"));
			assertTrue(template.contains("renderPercentileChart(document.getElementById(\"chart\"),"));
			assertTrue(template.contains("renderPercentileChart(document.getElementById(\"sql-chart\"),"));
			assertTrue(template.contains("id=\"legend\""));
			assertTrue(template.contains("id=\"sql-legend\""));
			assertFalse(template.contains("id=\"metric\""));

			// Data remain separately attributed and contain both saved percentiles.
			assertTrue(template.contains("o.median.toFixed(2), o.p90.toFixed(2)"));
			assertTrue(template.contains("description + \" — Median / P90 (\" + unit"));
			assertFalse(template.contains("<script src="));
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

	@Test
	void typedSqlActivityUsesRecordedValuesRatherThanMetricRecorderDurations() throws Exception {
		Path history = tempDir.resolve("typed-sql-history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		PerformanceHistoryStore.Checkpoint checkpoint = checkpoint(machine, session, "0.6", 626);
		String operation = "dashboard.audit.subject.sql.execute-invocations";
		new PerformanceHistoryStore().writeCheckpoint(history, checkpoint,
				List.of(new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 1, 0, operation,
						0.001, true, 2),
						new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 2, 0, operation,
								0.002, true, 6),
						new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 3, 0,
								"dashboard.refresh", 120.0, true, 1)),
				true);
		Path output = tempDir.resolve("typed-sql.html");
		new PerformanceHistoryHtmlReport().generate(history, output);
		String html = Files.readString(output);
		assertTrue(html.contains("const sqlObservations = ["));
		assertTrue(html.contains("\"workflow\":\"dashboard.audit.subject\""));
		assertTrue(html.contains("\"metric\":\"execute-invocations\""));
		assertTrue(html.contains("\"unit\":\"count\""));
		assertTrue(html.contains("\"median\":4.0"));
		assertTrue(html.contains("\"p90\":6.0"));
		assertTrue(html.contains("SQL Activity"));

		// The SQL metric name never leaks into the conventional timing array.
		String timings = html.substring(html.indexOf("const observations = "),
				html.indexOf("const legacyObservations = "));
		assertFalse(timings.contains(operation));
		assertTrue(timings.contains("dashboard.refresh"));
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
