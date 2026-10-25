package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceLegacyCsvSummaryTest {

	@TempDir
	Path tempDir;

	@Test
	void aggregatesAnOldMultiLaunchCsvWithoutMakingUpSessions() throws Exception {
		Path csv = tempDir.resolve("performance.csv");
		String original = PerformanceRecorder.HEADER + "\n" + "2026-09-01T01:00:00Z,1,0,dashboard.refresh,10.0,true,1\n"
				+ "2026-09-01T02:00:00Z,2,0,dashboard.refresh,20.0,false,-1\n"
				+ "2026-10-01T01:00:00Z,1,0,dashboard.refresh,30.0,true,1\n"
				+ "2026-10-01T01:01:00Z,2,0,search.refresh,7.0,true,2\n";
		Files.writeString(csv, original);
		List<PerformanceLegacyCsvSummary.Group> groups = new PerformanceLegacyCsvSummary().read(csv);
		assertEquals(2, groups.size());
		PerformanceLegacyCsvSummary.Group dashboard = groups.getFirst();
		assertEquals("dashboard.refresh", dashboard.operation());
		assertEquals(3L, dashboard.samples());
		assertEquals(1L, dashboard.failures());
		assertEquals(20.0, dashboard.median());
		assertEquals(30.0, dashboard.p90());
		assertEquals(30.0, dashboard.maximum());
		assertEquals("2026-09-01T01:00:00Z", dashboard.earliest().toString());
		assertEquals("2026-10-01T01:00:00Z", dashboard.latest().toString());
		assertEquals("search.refresh", groups.get(1).operation());
		assertEquals(original, Files.readString(csv));
	}

	@Test
	void refusesMalformedOrEmptyLegacyCsv() throws Exception {
		Path csv = tempDir.resolve("performance.csv");
		Files.writeString(csv, PerformanceRecorder.HEADER + "\n");
		assertThrows(IOException.class, () -> new PerformanceLegacyCsvSummary().read(csv));
		Files.writeString(csv, PerformanceRecorder.HEADER + "\nnot-a-valid-row\n");
		assertThrows(IOException.class, () -> new PerformanceLegacyCsvSummary().read(csv));
		assertFalse(Files.exists(tempDir.resolve("history")));
		assertTrue(Files.exists(csv));
	}

	@Test
	void refusesSessionCsvWithoutModifyingIt() throws Exception {
		Path csv = tempDir.resolve("operations.csv");
		String original = PerformanceRecorder.SESSION_HEADER + "\n";
		Files.writeString(csv, original);
		assertThrows(IOException.class, () -> new PerformanceLegacyCsvSummary().read(csv));
		assertEquals(original, Files.readString(csv));
	}
}
