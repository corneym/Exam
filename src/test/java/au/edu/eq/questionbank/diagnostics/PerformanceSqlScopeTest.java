package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;

class PerformanceSqlScopeTest {

	@TempDir
	Path tempDir;

	private static void assertMetric(List<String> lines, String operation, long expected) {
		String line = lines.stream().filter(candidate -> candidate.contains("," + operation + ",")).findFirst()
				.orElseThrow();
		String[] fields = line.split(",");
		assertEquals(expected, Long.parseLong(fields[fields.length - 1]));
	}

	@Test
	void countsJdbcInvocationsAndRowsWithParentCorrelation() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		Path csv = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv)) {
			try (PerformanceOperation parent = recorder.start("dashboard.refresh")) {
				int rows = PerformanceSqlScope.measure(recorder, "dashboard.snapshot", parent.id(), () -> {
					int count = 0;
					try (Connection connection = database.openConnection();
							Statement statement = connection.createStatement();
							ResultSet result = statement.executeQuery("SELECT 1 UNION ALL SELECT 2")) {
						while (result.next()) {
							count++;
						}
					}
					return count;
				});
				assertEquals(2, rows);
			}
		}
		List<String> lines = Files.readAllLines(csv);
		assertMetric(lines, "dashboard.snapshot.sql.connections", 1);
		assertMetric(lines, "dashboard.snapshot.sql.execute-invocations", 1);
		assertMetric(lines, "dashboard.snapshot.sql.rows-read", 2);
		assertMetric(lines, "dashboard.snapshot.sql.questions-reconstructed", 0);

		// SQL text and row values must never appear in metrics.
		assertFalse(Files.readString(csv).contains("SELECT 1 UNION ALL SELECT 2"));
		String scope = lines.stream().filter(line -> line.contains(",dashboard.snapshot.sql.scope,")).findFirst()
				.orElseThrow();
		String parent = lines.stream().filter(line -> line.contains(",dashboard.refresh,")).findFirst().orElseThrow();
		assertEquals(parent.split(",")[1], scope.split(",")[2]);
	}

	@Test
	void existingProbeIsNotNestedOrCountedTwice() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		Path csv = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
				SqliteReadProbe outer = SqliteReadProbe.begin()) {
			assertTrue(SqliteReadProbe.isActive());
			PerformanceSqlScope.measure(recorder, "dashboard.snapshot", 0, () -> {
				try (Connection connection = database.openConnection();
						Statement statement = connection.createStatement();
						ResultSet result = statement.executeQuery("SELECT 1")) {
					assertTrue(result.next());
				}
				return null;
			});
			assertEquals(1, outer.snapshot().executions());
			assertEquals(1, outer.snapshot().rowsRead());
		}
		assertFalse(Files.exists(csv));
		assertFalse(SqliteReadProbe.isActive());
	}

	@Test
	void failedSqlKeepsTheOriginalExceptionAndRecordsFailure() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		Path csv = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv)) {
			assertThrows(SQLException.class,
					() -> PerformanceSqlScope.measure(recorder, "capture.answer.save.persist", 0, () -> {
						try (Connection connection = database.openConnection();
								Statement statement = connection.createStatement()) {
							statement.executeQuery("SELECT nonexistent_column " + "FROM questions");
						}
						return null;
					}));
		}
		List<String> lines = Files.readAllLines(csv);
		assertTrue(lines.stream().anyMatch(
				line -> line.contains(",capture.answer.save.persist.sql.scope,") && line.contains(",false,")));
		assertMetric(lines, "capture.answer.save.persist.sql.execute-invocations", 1);
		assertFalse(SqliteReadProbe.isActive());
	}

	@Test
	void uncheckedWorkflowDoesNotNestExistingProbe() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("nested-scope.db"));
		database.initialiseSchema();
		Path csv = tempDir.resolve("nested-scope.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
				SqliteReadProbe outer = SqliteReadProbe.begin()) {
			int count = PerformanceSqlScope.measureUnchecked(recorder, "workingSubject.questions", 0, () -> {
				try (Connection connection = database.openConnection();
						Statement statement = connection.createStatement();
						ResultSet results = statement.executeQuery("SELECT 1")) {
					return results.next() ? 1 : 0;
				} catch (SQLException failure) {
					throw new IllegalStateException(failure);
				}
			});
			assertEquals(1, count);
			assertEquals(1, outer.snapshot().executions());
			assertEquals(1, outer.snapshot().rowsRead());
		}
		assertFalse(Files.exists(csv));
		assertFalse(SqliteReadProbe.isActive());
	}

	@Test
	void uncheckedWorkflowKeepsRuntimeExceptionAndClosesProbe() throws Exception {
		Path csv = tempDir.resolve("unchecked-failure.csv");
		IllegalStateException expected = new IllegalStateException("simulated snapshot failure");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv)) {
			IllegalStateException actual = assertThrows(IllegalStateException.class,
					() -> PerformanceSqlScope.measureUnchecked(recorder, "examAssets.snapshot", 0, () -> {
						throw expected;
					}));
			assertSame(expected, actual);
			assertFalse(SqliteReadProbe.isActive());
		}
		List<String> rows = Files.readAllLines(csv);
		assertTrue(rows.stream()
				.anyMatch(row -> row.contains(",examAssets.snapshot.sql.scope,") && row.contains(",false,")));
	}

	@Test
	void uncheckedWorkflowPublishesCorrelatedSqlCounts() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("unchecked-metrics.db"));
		database.initialiseSchema();
		Path csv = tempDir.resolve("unchecked-metrics.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv)) {
			try (PerformanceOperation parent = recorder.start("examAssets.refresh")) {
				int count = PerformanceSqlScope.measureUnchecked(recorder, "examAssets.snapshot", parent.id(), () -> {
					try (Connection connection = database.openConnection();
							Statement statement = connection.createStatement();
							ResultSet results = statement.executeQuery("SELECT 1 UNION ALL SELECT 2")) {
						int rows = 0;
						while (results.next()) {
							rows++;
						}
						return rows;
					} catch (SQLException failure) {
						throw new IllegalStateException(failure);
					}
				});
				assertEquals(2, count);
			}
		}
		List<String> rows = Files.readAllLines(csv);
		assertMetric(rows, "examAssets.snapshot.sql.connections", 1);
		assertMetric(rows, "examAssets.snapshot.sql.execute-invocations", 1);
		assertMetric(rows, "examAssets.snapshot.sql.rows-read", 2);
		assertMetric(rows, "examAssets.snapshot.sql.questions-reconstructed", 0);
		String scope = rows.stream().filter(row -> row.contains(",examAssets.snapshot.sql.scope,")).findFirst()
				.orElseThrow();
		String parent = rows.stream().filter(row -> row.contains(",examAssets.refresh,")).findFirst().orElseThrow();
		assertEquals(parent.split(",")[1], scope.split(",")[2]);
		assertFalse(SqliteReadProbe.isActive());
	}

	@Test
	void uncheckedWorkflowRespectsDisabledRecorder() {
		Path csv = tempDir.resolve("disabled-scope.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(false, csv)) {
			String value = PerformanceSqlScope.measureUnchecked(recorder, "examAssets.snapshot", 0,
					() -> "same-result");
			assertEquals("same-result", value);
			assertFalse(SqliteReadProbe.isActive());
		}
		assertFalse(Files.exists(csv));
	}
}
