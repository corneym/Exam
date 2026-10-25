package au.edu.eq.questionbank.diagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Summarises explicit seven-column legacy performance recordings without
 * inventing application sessions, machine identities or corpus metadata.
 *
 * The CSV may contain multiple launches with reused numeric operation IDs; this
 * class deliberately aggregates all rows by operation name.
 */
public final class PerformanceLegacyCsvSummary {

	private static final long MAX_INPUT_BYTES = 128L * 1024 * 1024;

	/** Creates a read-only legacy CSV summariser. */
	public PerformanceLegacyCsvSummary() {
	}

	/**
	 * Reads and aggregates a user-selected legacy performance CSV.
	 *
	 * @param csv original seven-column CSV, never modified
	 * @return immutable per-operation statistics in operation-name order
	 * @throws IOException if the file is unsafe, too large, empty or malformed
	 */
	public List<Group> read(Path csv) throws IOException {
		Objects.requireNonNull(csv, "csv");
		if (!Files.isRegularFile(csv, LinkOption.NOFOLLOW_LINKS) || Files.size(csv) > MAX_INPUT_BYTES) {
			throw new IOException("Legacy performance CSV is missing, unsafe or larger than 128 MiB");
		}
		try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
			if (!PerformanceRecorder.HEADER.equals(reader.readLine())) {
				throw new IOException("Expected the original seven-column performance.csv format");
			}
		}

		// Reuse the existing validated CSV parser; never infer legacy sessions
		// from recurring operation IDs or timestamps.
		Map<String, MutableGroup> groups = new HashMap<>();
		for (PerformanceCsvReader.Entry entry : PerformanceCsvReader.read(csv)) {
			if (entry.sessionId() != null) {
				throw new IOException("Legacy CSV must not contain session-aware measurements");
			}
			groups.computeIfAbsent(entry.operation(), _ -> new MutableGroup()).add(entry);
		}
		if (groups.isEmpty()) {
			throw new IOException("Legacy performance CSV has no measurements");
		}
		List<Group> results = new ArrayList<>();
		for (Map.Entry<String, MutableGroup> entry : groups.entrySet()) {
			results.add(entry.getValue().complete(entry.getKey()));
		}
		results.sort(Comparator.comparing(Group::operation));
		return List.copyOf(results);
	}

	/**
	 * An operation aggregate from the entire unsegmented legacy file.
	 *
	 * @param operation recorded operation name
	 * @param earliest  earliest measurement completion in UTC
	 * @param latest    latest measurement completion in UTC
	 * @param samples   number of measurements
	 * @param failures  unsuccessful measurements
	 * @param median    median elapsed milliseconds
	 * @param p90       nearest-rank 90th percentile milliseconds
	 * @param maximum   maximum elapsed milliseconds
	 */
	public record Group(String operation, Instant earliest, Instant latest, long samples, long failures, double median,
			double p90, double maximum) {
	}

	private static final class MutableGroup {

		private Instant earliest;
		private Instant latest;
		private long failures;
		private final List<Double> timings = new ArrayList<>();

		private void add(PerformanceCsvReader.Entry entry) {
			timings.add(entry.elapsedMillis());
			if (!entry.success()) {
				failures++;
			}
			if (earliest == null || entry.timestamp().isBefore(earliest)) {
				earliest = entry.timestamp();
			}
			if (latest == null || entry.timestamp().isAfter(latest)) {
				latest = entry.timestamp();
			}
		}

		private Group complete(String operation) {
			timings.sort(Double::compare);
			int count = timings.size();
			double median = count % 2 == 0 ? (timings.get(count / 2 - 1) + timings.get(count / 2)) / 2.0
					: timings.get(count / 2);
			double p90 = timings.get((int) Math.ceil(count * 0.90) - 1);
			return new Group(operation, earliest, latest, count, failures, median, p90, timings.getLast());
		}
	}
}
