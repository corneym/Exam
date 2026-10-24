package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Publishes permanent, per-checkpoint performance aggregates outside the
 * authoritative Question data root.
 *
 * This store never deletes historical summaries or raw measurements.
 */
public final class PerformanceHistoryStore {

	private static final String HEADER = "checkpoint_at,machine_id,session_id,checkpoint_index,"
			+ "application_version,runtime_kind,schema_version," + "question_count,database_bytes,operation,samples,"
			+ "failures,median_ms,p90_ms,max_ms";

	/**
	 * Creates a store for permanent historical performance summaries.
	 */
	public PerformanceHistoryStore() {
	}

	private static String csvText(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	/**
	 * Writes one checkpoint without replacing an earlier summary.
	 *
	 * Measurements must belong exclusively to the checkpoint's observation window.
	 * Assignment of operations to windows is the caller's responsibility.
	 *
	 * @param historyDirectory permanent history root
	 * @param checkpoint       machine, session and corpus characteristics
	 * @param measurements     completed operations for this checkpoint only
	 * @return published CSV file
	 * @throws IOException if the summary cannot be safely published
	 */
	public Path writeCheckpoint(Path historyDirectory, Checkpoint checkpoint,
			List<PerformanceCsvReader.Entry> measurements) throws IOException {
		Objects.requireNonNull(historyDirectory, "historyDirectory");
		Objects.requireNonNull(checkpoint, "checkpoint");
		Objects.requireNonNull(measurements, "measurements");
		if (measurements.isEmpty()) {
			throw new IllegalArgumentException("A checkpoint needs at least one measurement");
		}
		Map<String, List<Double>> times = new TreeMap<>();
		Map<String, Long> failures = new TreeMap<>();
		for (PerformanceCsvReader.Entry entry : measurements) {
			if (entry == null || !checkpoint.sessionId().equals(entry.sessionId())) {
				throw new IllegalArgumentException("Measurement session does not match checkpoint");
			}
			if (!Double.isFinite(entry.elapsedMillis()) || entry.elapsedMillis() < 0
					|| !entry.operation().matches("[a-zA-Z0-9._-]+")) {
				throw new IllegalArgumentException("Invalid measurement for checkpoint");
			}
			times.computeIfAbsent(entry.operation(), _ -> new ArrayList<>()).add(entry.elapsedMillis());
			if (!entry.success()) {
				failures.merge(entry.operation(), 1L, Long::sum);
			}
		}
		StringBuilder csv = new StringBuilder(HEADER).append('\n');
		for (Map.Entry<String, List<Double>> group : times.entrySet()) {
			List<Double> values = group.getValue();
			Collections.sort(values);
			int count = values.size();
			double median = count % 2 == 0 ? (values.get(count / 2 - 1) + values.get(count / 2)) / 2.0
					: values.get(count / 2);

			// p90 uses the nearest-rank definition.
			double p90 = values.get((int) Math.ceil(count * 0.90) - 1);
			double maximum = values.get(count - 1);
			csv.append(checkpoint.capturedAt()).append(',').append(checkpoint.machineId()).append(',')
					.append(checkpoint.sessionId()).append(',').append(checkpoint.checkpointIndex()).append(',')
					.append(csvText(checkpoint.applicationVersion())).append(',')
					.append(csvText(checkpoint.runtimeKind())).append(',').append(checkpoint.schemaVersion())
					.append(',').append(checkpoint.questionCount()).append(',').append(checkpoint.databaseBytes())
					.append(',').append(csvText(group.getKey())).append(',').append(count).append(',')
					.append(failures.getOrDefault(group.getKey(), 0L)).append(',').append(median).append(',')
					.append(p90).append(',').append(maximum).append('\n');
		}
		Path sessionDirectory = historyDirectory.resolve(checkpoint.machineId().toString())
				.resolve(checkpoint.sessionId().toString());
		Files.createDirectories(sessionDirectory);
		Path destination = sessionDirectory.resolve("checkpoint-%06d.csv".formatted(checkpoint.checkpointIndex()));
		Path temporaryFile = Files.createTempFile(sessionDirectory, ".checkpoint-", ".tmp");
		try {
			Files.writeString(temporaryFile, csv.toString(), StandardCharsets.UTF_8);

			// Publish the staged file without replacing an earlier
			// checkpoint, even if an import or write is repeated.
			Files.move(temporaryFile, destination);
		} finally {
			Files.deleteIfExists(temporaryFile);
		}
		return destination;
	}

	/**
	 * Identifies one bounded performance observation window.
	 *
	 * @param machineId          stable random installation identifier
	 * @param sessionId          unique application-session identifier
	 * @param checkpointIndex    sequential checkpoint index
	 * @param capturedAt         corpus checkpoint timestamp
	 * @param applicationVersion application version
	 * @param runtimeKind        installed or development environment
	 * @param schemaVersion      database schema version, or -1 if unavailable
	 * @param questionCount      Question count, or -1 if unavailable
	 * @param databaseBytes      database size, or -1 if unavailable
	 */
	public record Checkpoint(UUID machineId, UUID sessionId, int checkpointIndex, Instant capturedAt,
			String applicationVersion, String runtimeKind, int schemaVersion, long questionCount, long databaseBytes) {

		/**
		 * Validates the checkpoint's identity, timing and corpus metadata before it is
		 * used to publish a historical performance summary.
		 */
		public Checkpoint {
			Objects.requireNonNull(machineId, "machineId");
			Objects.requireNonNull(sessionId, "sessionId");
			Objects.requireNonNull(capturedAt, "capturedAt");
			if (checkpointIndex < 0 || schemaVersion < -1 || questionCount < -1 || databaseBytes < -1) {
				throw new IllegalArgumentException("Invalid checkpoint counters");
			}
			if (invalidText(applicationVersion) || invalidText(runtimeKind)) {
				throw new IllegalArgumentException("Invalid checkpoint description");
			}
		}

		private static boolean invalidText(String value) {
			return value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
		}
	}
}
