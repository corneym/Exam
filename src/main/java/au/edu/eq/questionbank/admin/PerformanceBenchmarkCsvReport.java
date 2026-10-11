package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Writes reproducible benchmark results using the stable Issue #110 CSV format.
 *
 * One row is written per selected workload. The report includes snapshot
 * identity, corpus metadata, summary timings and individual measured samples.
 *
 * Existing output files are never overwritten.
 */
public final class PerformanceBenchmarkCsvReport {

	static final String HEADER = "timestamp_utc,code_label,snapshot_path,snapshot_sha256,"
			+ "database_bytes,question_count,schema_version," + "sqlite_version,java_version,operation,warmups,"
			+ "iterations,result_count,min_ms,median_ms," + "p90_ms,max_ms,elapsed_ns_samples";

	private PerformanceBenchmarkCsvReport() {
	}

	/**
	 * Calculates the SHA-256 fingerprint of a database snapshot.
	 *
	 * @param snapshot existing database snapshot
	 * @return lowercase hexadecimal SHA-256 fingerprint
	 * @throws IOException if the snapshot cannot be read
	 */
	static String sha256(Path snapshot) throws IOException {
		Objects.requireNonNull(snapshot, "snapshot");
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
		try (InputStream input = Files.newInputStream(snapshot)) {
			byte[] buffer = new byte[16384];
			int length;
			while ((length = input.read(buffer)) != -1) {
				digest.update(buffer, 0, length);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Writes a complete CSV report to a new destination file.
	 *
	 * @param destination  absolute output path
	 * @param context      shared run and snapshot metadata
	 * @param measurements successfully completed benchmark results
	 * @throws IOException if the output cannot be created or written
	 */
	static void write(Path destination, Context context, List<Measurement> measurements) throws IOException {
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(measurements, "measurements");
		if (measurements.isEmpty()) {
			throw new IllegalArgumentException("A CSV report requires at least one benchmark result");
		}
		StringBuilder csv = new StringBuilder();
		csv.append(HEADER).append('\n');
		for (Measurement measurement : measurements) {
			PerformanceBenchmarkResult result = measurement.result();
			String samples = result.elapsedNanos().stream().map(String::valueOf).collect(Collectors.joining(";"));
			appendRow(csv, context.timestampUtc().toString(), context.codeLabel(), context.snapshotPath().toString(),
					context.snapshotSha256(), Long.toString(context.databaseBytes()),
					Long.toString(context.questionCount()), Integer.toString(context.schemaVersion()),
					context.sqliteVersion(), context.javaVersion(), measurement.operation(),
					Integer.toString(result.warmupIterations()), Integer.toString(result.measuredIterations()),
					Long.toString(result.resultCount()), milliseconds(result.minMilliseconds()),
					milliseconds(result.medianMilliseconds()), milliseconds(result.p90Milliseconds()),
					milliseconds(result.maxMilliseconds()), samples);
		}

		// CREATE_NEW is essential: an existing baseline result must
		// never be silently replaced by a later benchmark run.
		Files.writeString(destination, csv.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
	}

	private static void appendRow(StringBuilder csv, String... fields) {
		for (int index = 0; index < fields.length; index++) {
			if (index > 0) {
				csv.append(',');
			}
			String field = fields[index];

			// RFC-style CSV escaping preserves paths containing commas,
			// quotes or line breaks as a single field.
			if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0
					|| field.indexOf('\r') >= 0) {
				csv.append('"');
				csv.append(field.replace("\"", "\"\""));
				csv.append('"');
			} else {
				csv.append(field);
			}
		}
		csv.append('\n');
	}

	private static String milliseconds(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	record Context(Instant timestampUtc, String codeLabel, Path snapshotPath, String snapshotSha256, long databaseBytes,
			long questionCount, int schemaVersion, String sqliteVersion, String javaVersion) {
	}

	record Measurement(String operation, PerformanceBenchmarkResult result) {
	}
}
