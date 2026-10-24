package au.edu.eq.questionbank.diagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads legacy and session-aware performance diagnostic CSV files.
 *
 * Legacy records have no reliable session identifier. The reader leaves their
 * session identifier null rather than inventing one.
 */
public final class PerformanceCsvReader {

	private PerformanceCsvReader() {
	}

	/**
	 * Reads a performance diagnostic CSV file.
	 *
	 * @param file CSV file to read
	 * @return immutable list of measurements
	 * @throws IOException if the file cannot be read or is malformed
	 */
	public static List<Entry> read(Path file) throws IOException {
		List<Entry> entries = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String header = reader.readLine();
			boolean sessionFormat = PerformanceRecorder.SESSION_HEADER.equals(header);
			if (!sessionFormat && !PerformanceRecorder.HEADER.equals(header)) {
				throw new IOException("Unsupported performance CSV header");
			}
			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				entries.add(parse(line, sessionFormat, lineNumber));
			}
		}
		return List.copyOf(entries);
	}

	private static Entry parse(String line, boolean sessionFormat, int lineNumber) throws IOException {
		String[] values = line.split(",", -1);
		int expectedColumns = sessionFormat ? 8 : 7;
		if (values.length != expectedColumns) {
			throw new IOException("Invalid performance CSV column count at line " + lineNumber);
		}
		try {
			int offset = sessionFormat ? 1 : 0;
			Instant timestamp = Instant.parse(values[0]);
			UUID sessionId = sessionFormat ? UUID.fromString(values[1]) : null;
			long operationId = Long.parseLong(values[offset + 1]);
			long parentId = Long.parseLong(values[offset + 2]);
			String operation = values[offset + 3];
			double elapsedMillis = Double.parseDouble(values[offset + 4]);
			String successText = values[offset + 5];
			if (!"true".equals(successText) && !"false".equals(successText)) {
				throw new IllegalArgumentException("Invalid success indicator");
			}
			boolean success = Boolean.parseBoolean(successText);
			long resultCount = Long.parseLong(values[offset + 6]);
			if (!Double.isFinite(elapsedMillis) || elapsedMillis < 0 || operation.isBlank()) {
				throw new IllegalArgumentException("Invalid operation measurement");
			}
			return new Entry(timestamp, sessionId, operationId, parentId, operation, elapsedMillis, success,
					resultCount);
		} catch (RuntimeException failure) {
			throw new IOException("Invalid performance CSV measurement at line " + lineNumber, failure);
		}
	}

	/**
	 * One operation measurement from a diagnostic CSV file.
	 *
	 * @param timestamp     completion timestamp
	 * @param sessionId     session UUID, or null for legacy measurements
	 * @param operationId   identifier unique within the recording session
	 * @param parentId      parent operation identifier, or zero
	 * @param operation     stable operation name
	 * @param elapsedMillis elapsed time in milliseconds
	 * @param success       whether the operation succeeded
	 * @param resultCount   result count, or -1 when unspecified
	 */
	public record Entry(Instant timestamp, UUID sessionId, long operationId, long parentId, String operation,
			double elapsedMillis, boolean success, long resultCount) {
	}
}
