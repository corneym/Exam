package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates a self-contained offline HTML report from permanent historical
 * checkpoint summaries.
 */
public final class PerformanceHistoryHtmlReport {

	private static final Pattern SUMMARY_NAME = Pattern.compile("checkpoint-[0-9]{6}\\.csv");
	private static final String EXPECTED_HEADER = "checkpoint_at,machine_id,session_id,checkpoint_index,"
			+ "application_version,runtime_kind,schema_version," + "question_count,database_bytes,operation,samples,"
			+ "failures,median_ms,p90_ms,max_ms";
	private static final String SQL_HEADER = EXPECTED_HEADER + ",metric_unit,value_median,value_p90,value_max";
	private static final String TEMPLATE_RESOURCE = "performance-history-report.html";

	/**
	 * Creates an offline performance-history report generator.
	 */
	public PerformanceHistoryHtmlReport() {
	}

	private static List<String> csvCells(String row) throws IOException {
		List<String> cells = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < row.length(); i++) {
			char character = row.charAt(i);
			if (character == '"') {
				if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				} else {
					quoted = !quoted;
				}
			} else if (character == ',' && !quoted) {
				cells.add(current.toString());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (quoted) {
			throw new IOException("Unterminated quoted historical CSV field");
		}
		cells.add(current.toString());
		return cells;
	}

	private static String jsonString(String value) {
		StringBuilder escaped = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> escaped.append("\\\"");
			case '\\' -> escaped.append("\\\\");
			case '\n' -> escaped.append("\\n");
			case '\r' -> escaped.append("\\r");
			case '\t' -> escaped.append("\\t");
			case '<' -> escaped.append("\\u003c");
			case '>' -> escaped.append("\\u003e");
			case '&' -> escaped.append("\\u0026");
			default -> {
				if (c < 32 || c == '\u2028' || c == '\u2029') {
					escaped.append("\\u%04x".formatted((int) c));
				} else {
					escaped.append(c);
				}
			}
			}
		}
		return escaped.append('"').toString();
	}

	private static String loadTemplate() throws IOException {
		try (InputStream input = PerformanceHistoryHtmlReport.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
			if (input == null) {
				throw new IOException("Packaged performance HTML template is missing");
			}
			String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			for (String placeholder : List.of("__OBSERVATIONS__", "__SQL_OBSERVATIONS__", "__LEGACY_OBSERVATIONS__")) {
				int first = template.indexOf(placeholder);
				if (first < 0 || template.indexOf(placeholder, first + placeholder.length()) >= 0) {
					throw new IOException("Performance HTML template must contain exactly one " + placeholder);
				}
			}
			return template;
		}
	}

	private static HistoryData read(Path historyDirectory) throws IOException {
		List<Observation> timings = new ArrayList<>();
		List<SqlObservation> sql = new ArrayList<>();
		if (!Files.isDirectory(historyDirectory)) {
			return new HistoryData(timings, sql);
		}
		try (Stream<Path> candidates = Files.walk(historyDirectory, 3)) {
			for (Path file : candidates.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path))
					.filter(path -> SUMMARY_NAME.matcher(path.getFileName().toString()).matches()).toList()) {
				Path relative = historyDirectory.relativize(file);
				if (relative.getNameCount() != 3) {
					continue;
				}
				UUID directoryMachine;
				UUID directorySession;
				try {
					directoryMachine = UUID.fromString(relative.getName(0).toString());
					directorySession = UUID.fromString(relative.getName(1).toString());
				} catch (IllegalArgumentException invalid) {
					throw new IOException("Invalid history directory identity", invalid);
				}
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				if (lines.isEmpty()) {
					throw new IOException("Empty historical summary");
				}
				boolean typedSql = SQL_HEADER.equals(lines.getFirst());
				if (!typedSql && !EXPECTED_HEADER.equals(lines.getFirst())) {
					throw new IOException("Unsupported historical summary format");
				}
				for (int line = 1; line < lines.size(); line++) {
					if (lines.get(line).isBlank()) {
						continue;
					}
					try {
						List<String> values = csvCells(lines.get(line));
						if (values.size() != (typedSql ? 19 : 15)) {
							throw new IllegalArgumentException("Incorrect summary column count");
						}
						Instant captured = Instant.parse(values.get(0));
						UUID machine = UUID.fromString(values.get(1));
						UUID session = UUID.fromString(values.get(2));
						String version = values.get(4);
						String runtime = values.get(5);
						long questionCount = Long.parseLong(values.get(7));
						String operation = values.get(9);
						long samples = Long.parseLong(values.get(10));
						long failures = Long.parseLong(values.get(11));
						double medianMs = Double.parseDouble(values.get(12));
						double p90Ms = Double.parseDouble(values.get(13));
						double maxMs = Double.parseDouble(values.get(14));
						if (!machine.equals(directoryMachine) || !session.equals(directorySession) || samples < 1
								|| failures < 0 || failures > samples || questionCount < -1
								|| !validStatistic(medianMs, p90Ms, maxMs)) {
							throw new IllegalArgumentException("Invalid historical observation");
						}
						String expectedUnit = PerformanceSqlMetric.unit(operation);
						if (expectedUnit != null) {

							// Legacy summaries do not contain SQL counter values; their
							// elapsed_ms is recorder overhead and must NOT be charted.
							if (!typedSql) {
								continue;
							}
							if (!expectedUnit.equals(values.get(15))) {
								throw new IllegalArgumentException("SQL statistic has the wrong unit");
							}
							double valueMedian = Double.parseDouble(values.get(16));
							double valueP90 = Double.parseDouble(values.get(17));
							double valueMax = Double.parseDouble(values.get(18));
							if (!validStatistic(valueMedian, valueP90, valueMax)) {
								throw new IllegalArgumentException("Invalid SQL metric values");
							}
							String workflow = operation.substring(0, operation.lastIndexOf(".sql."));
							String metric = operation.substring(operation.lastIndexOf(".sql.") + 5);
							sql.add(new SqlObservation(captured, machine, session, version, runtime, questionCount,
									workflow, metric, expectedUnit, samples, failures, valueMedian, valueP90,
									valueMax));
						} else {
							if (typedSql && (!values.get(15).isEmpty() || !values.get(16).isEmpty()
									|| !values.get(17).isEmpty() || !values.get(18).isEmpty())) {
								throw new IllegalArgumentException("Timed operation incorrectly contains SQL values");
							}
							timings.add(new Observation(captured, machine, session, version, runtime, questionCount,
									operation, samples, failures, medianMs, p90Ms, maxMs));
						}
					} catch (RuntimeException invalid) {
						throw new IOException("Invalid summary row in " + file.getFileName() + " at line " + (line + 1),
								invalid);
					}
				}
			}
		}
		return new HistoryData(timings, sql);
	}

	private static boolean validStatistic(double median, double p90, double maximum) {
		return Double.isFinite(median) && Double.isFinite(p90) && Double.isFinite(maximum) && median >= 0
				&& p90 >= median && maximum >= p90;
	}

	/**
	 * Generates the original modern-history-only offline report.
	 *
	 * @param historyDirectory permanent history root
	 * @param destination      new HTML output file
	 * @return output path
	 * @throws IOException if history is unavailable or writing fails
	 */
	public Path generate(Path historyDirectory, Path destination) throws IOException {
		return generate(historyDirectory, destination, null);
	}

	/**
	 * Generates a modern historical report with optional explicitly selected legacy
	 * CSV observations, displayed separately from modern session data.
	 *
	 * @param historyDirectory permanent modern history root
	 * @param destination      new HTML output file
	 * @param legacyCsv        optional original seven-column performance.csv, or
	 *                         null
	 * @return newly generated HTML path
	 * @throws IOException if either source is invalid or output cannot be created
	 */
	public Path generate(Path historyDirectory, Path destination, Path legacyCsv) throws IOException {
		HistoryData history = read(historyDirectory);
		List<Observation> observations = new ArrayList<>(history.timings());
		List<SqlObservation> sql = new ArrayList<>(history.sql());
		List<PerformanceLegacyCsvSummary.Group> legacy = legacyCsv == null ? List.of()
				: new PerformanceLegacyCsvSummary().read(legacyCsv);
		if (observations.isEmpty() && sql.isEmpty() && legacy.isEmpty()) {
			throw new IOException("No historical performance observations available");
		}
		observations.sort(Comparator.comparing(Observation::operation).thenComparing(o -> o.machineId().toString())
				.thenComparing(Observation::questionCount).thenComparing(Observation::capturedAt));
		sql.sort(Comparator.comparing(SqlObservation::workflow).thenComparing(SqlObservation::metric)
				.thenComparing(o -> o.machineId().toString()).thenComparing(SqlObservation::questionCount)
				.thenComparing(SqlObservation::capturedAt));
		StringBuilder modernJson = new StringBuilder("[");
		for (Observation observation : observations) {
			if (modernJson.length() > 1) {
				modernJson.append(',');
			}
			modernJson.append('{').append("\"machine\":").append(jsonString(observation.machineId().toString()))
					.append(",\"session\":").append(jsonString(observation.sessionId().toString()))
					.append(",\"version\":").append(jsonString(observation.version())).append(",\"runtime\":")
					.append(jsonString(observation.runtime())).append(",\"operation\":")
					.append(jsonString(observation.operation())).append(",\"captured\":")
					.append(jsonString(observation.capturedAt().toString())).append(",\"questions\":")
					.append(observation.questionCount()).append(",\"samples\":").append(observation.samples())
					.append(",\"failures\":").append(observation.failures()).append(",\"median\":")
					.append(observation.median()).append(",\"p90\":").append(observation.p90()).append(",\"maximum\":")
					.append(observation.maximum()).append('}');
		}
		modernJson.append(']');

		// SQL values have their own units and MUST NOT be mixed with elapsed_ms.
		StringBuilder sqlJson = new StringBuilder("[");
		for (SqlObservation observation : sql) {
			if (sqlJson.length() > 1) {
				sqlJson.append(',');
			}
			sqlJson.append('{').append("\"machine\":").append(jsonString(observation.machineId().toString()))
					.append(",\"session\":").append(jsonString(observation.sessionId().toString()))
					.append(",\"version\":").append(jsonString(observation.version())).append(",\"runtime\":")
					.append(jsonString(observation.runtime())).append(",\"workflow\":")
					.append(jsonString(observation.workflow())).append(",\"metric\":")
					.append(jsonString(observation.metric())).append(",\"unit\":")
					.append(jsonString(observation.unit())).append(",\"captured\":")
					.append(jsonString(observation.capturedAt().toString())).append(",\"questions\":")
					.append(observation.questionCount()).append(",\"samples\":").append(observation.samples())
					.append(",\"failures\":").append(observation.failures()).append(",\"median\":")
					.append(observation.median()).append(",\"p90\":").append(observation.p90()).append(",\"maximum\":")
					.append(observation.maximum()).append('}');
		}
		sqlJson.append(']');
		StringBuilder legacyJson = new StringBuilder("[");
		for (PerformanceLegacyCsvSummary.Group group : legacy) {
			if (legacyJson.length() > 1) {
				legacyJson.append(',');
			}
			legacyJson.append('{').append("\"operation\":").append(jsonString(group.operation()))
					.append(",\"earliest\":").append(jsonString(group.earliest().toString())).append(",\"latest\":")
					.append(jsonString(group.latest().toString())).append(",\"samples\":").append(group.samples())
					.append(",\"failures\":").append(group.failures()).append(",\"median\":").append(group.median())
					.append(",\"p90\":").append(group.p90()).append(",\"maximum\":").append(group.maximum())
					.append('}');
		}
		legacyJson.append(']');
		String html = loadTemplate().replace("__OBSERVATIONS__", modernJson.toString())
				.replace("__SQL_OBSERVATIONS__", sqlJson.toString())
				.replace("__LEGACY_OBSERVATIONS__", legacyJson.toString());
		Path output = destination.toAbsolutePath().normalize();
		Path parent = output.getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".performance-report-", ".tmp");
		try {
			Files.writeString(temporary, html, StandardCharsets.UTF_8);
			Files.copy(temporary, output);
		} finally {
			Files.deleteIfExists(temporary);
		}
		return output;
	}

	private record HistoryData(List<Observation> timings, List<SqlObservation> sql) {
	}

	private record SqlObservation(Instant capturedAt, UUID machineId, UUID sessionId, String version, String runtime,
			long questionCount, String workflow, String metric, String unit, long samples, long failures, double median,
			double p90, double maximum) {
	}

	private record Observation(Instant capturedAt, UUID machineId, UUID sessionId, String version, String runtime,
			long questionCount, String operation, long samples, long failures, double median, double p90,
			double maximum) {
	}
}
