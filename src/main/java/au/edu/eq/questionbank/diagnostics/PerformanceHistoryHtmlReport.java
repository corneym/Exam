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
			for (String placeholder : List.of("__OBSERVATIONS__", "__LEGACY_OBSERVATIONS__")) {
				int first = template.indexOf(placeholder);
				if (first < 0 || template.indexOf(placeholder, first + placeholder.length()) >= 0) {
					throw new IOException("Performance HTML template must contain exactly one " + placeholder);
				}
			}
			return template;
		}
	}

	private static List<Observation> read(Path historyDirectory) throws IOException {
		if (!Files.isDirectory(historyDirectory)) {
			return new ArrayList<>();
		}
		List<Observation> result = new ArrayList<>();
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
				if (lines.isEmpty() || !EXPECTED_HEADER.equals(lines.getFirst())) {
					throw new IOException("Unsupported historical summary format");
				}
				for (int line = 1; line < lines.size(); line++) {
					if (lines.get(line).isBlank()) {
						continue;
					}
					try {
						List<String> values = csvCells(lines.get(line));
						if (values.size() != 15) {
							throw new IllegalArgumentException("Incorrect summary column count");
						}
						Observation observation = new Observation(Instant.parse(values.get(0)),
								UUID.fromString(values.get(1)), UUID.fromString(values.get(2)), values.get(4),
								values.get(5), Long.parseLong(values.get(7)), values.get(9),
								Long.parseLong(values.get(10)), Long.parseLong(values.get(11)),
								Double.parseDouble(values.get(12)), Double.parseDouble(values.get(13)),
								Double.parseDouble(values.get(14)));
						if (!observation.machineId().equals(directoryMachine)
								|| !observation.sessionId().equals(directorySession) || observation.samples() < 1
								|| observation.failures() < 0 || observation.failures() > observation.samples()
								|| !Double.isFinite(observation.median()) || !Double.isFinite(observation.p90())
								|| !Double.isFinite(observation.maximum()) || observation.median() < 0
								|| observation.p90() < 0 || observation.maximum() < 0) {
							throw new IllegalArgumentException("Invalid historical observation");
						}
						result.add(observation);
					} catch (RuntimeException invalid) {
						throw new IOException("Invalid summary row in " + file.getFileName() + " at line " + (line + 1),
								invalid);
					}
				}
			}
		}
		return result;
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
		List<Observation> observations = read(historyDirectory);
		List<PerformanceLegacyCsvSummary.Group> legacy = legacyCsv == null ? List.of()
				: new PerformanceLegacyCsvSummary().read(legacyCsv);
		if (observations.isEmpty() && legacy.isEmpty()) {
			throw new IOException("No historical performance observations available");
		}
		observations.sort(Comparator.comparing(Observation::operation).thenComparing(o -> o.machineId().toString())
				.thenComparing(Observation::questionCount).thenComparing(Observation::capturedAt));
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

		// Legacy measurements have neither reliable sessions nor corpus counts.
		// Never present them as chart points or as members of modern cohorts.
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

	private record Observation(Instant capturedAt, UUID machineId, UUID sessionId, String version, String runtime,
			long questionCount, String operation, long samples, long failures, double median, double p90,
			double maximum) {
	}
}
