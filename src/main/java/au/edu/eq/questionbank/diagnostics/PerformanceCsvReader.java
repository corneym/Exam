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
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads legacy and session-aware performance diagnostic CSV files.
 *
 * Legacy records have no reliable session identifier. The reader leaves their
 * session identifier null rather than inventing one.
 */
public final class PerformanceCsvReader {

	private static final Pattern SEGMENT_FILE = Pattern.compile("operations-[0-9]{6}\\.csv");

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

	/**
	 * Reads all operation segments for one session, in recorded order.
	 *
	 * Legacy single-file sessions remain supported.
	 *
	 * @param sessionDirectory UUID-named raw session directory
	 * @return all measurements from its operation segments
	 * @throws IOException if files or session identities are invalid
	 */
	public static List<Entry> readSession(Path sessionDirectory) throws IOException {
		UUID sessionId;
		try {
			sessionId = UUID.fromString(sessionDirectory.getFileName().toString());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid session directory identity", invalid);
		}
		List<Entry> merged = new ArrayList<>();
		for (Path file : sessionOperationFiles(sessionDirectory)) {
			for (Entry entry : read(file)) {
				if (!sessionId.equals(entry.sessionId())) {
					throw new IOException("Operation belongs to another session");
				}
				merged.add(entry);
			}
		}
		return List.copyOf(merged);
	}

	/**
	 * Returns the ordered operation CSV files for a raw session.
	 *
	 * Segment numbering must be contiguous, beginning at 000001. Unexpected
	 * operation filenames and symbolic links are rejected.
	 *
	 * @param sessionDirectory session directory
	 * @return immutable ordered list, including operations.csv
	 * @throws IOException if the segment layout is incomplete or unsafe
	 */
	public static List<Path> sessionOperationFiles(Path sessionDirectory) throws IOException {
		Path base = sessionDirectory.resolve("operations.csv");
		if (Files.isSymbolicLink(sessionDirectory) || !Files.isDirectory(sessionDirectory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(base, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing or unsafe session operations CSV");
		}
		List<Path> segments = new ArrayList<>();
		try (Stream<Path> stream = Files.list(sessionDirectory)) {
			for (Path path : stream.toList()) {
				String name = path.getFileName().toString();
				if (!name.startsWith("operations-") || !name.endsWith(".csv")) {
					if (name.startsWith("operations-")) {
						throw new IOException("Unexpected operations segment filename");
					}
					continue;
				}
				if (!SEGMENT_FILE.matcher(name).matches() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Invalid operations segment");
				}
				segments.add(path);
			}
		}
		segments.sort(Comparator.comparing(path -> path.getFileName().toString()));
		List<Path> ordered = new ArrayList<>();
		ordered.add(base);
		int expected = 1;
		for (Path file : segments) {
			String expectedName = "operations-%06d.csv".formatted(expected++);
			if (!expectedName.equals(file.getFileName().toString())) {
				throw new IOException("Missing or out-of-order operations segment");
			}
			ordered.add(file);
		}
		return List.copyOf(ordered);
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
