package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import au.edu.eq.questionbank.ApplicationPaths;
import au.edu.eq.questionbank.diagnostics.PerformanceHistoryHtmlReport;

/**
 * Generates an offline historical performance report.
 */
public final class PerformanceHistoryReportTool {

	private static final DateTimeFormatter REPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

	private PerformanceHistoryReportTool() {
	}

	/**
	 * Creates a uniquely named offline HTML report from modern history and,
	 * optionally, a user-selected seven-column legacy performance CSV.
	 *
	 * @param args output directory, optionally followed by --legacy-csv and CSV
	 *             path
	 */
	public static void main(String[] args) {
		boolean legacyRequested = args.length == 3 && "--legacy-csv".equals(args[1]);
		if ((args.length != 1 && !legacyRequested) || args[0] == null || args[0].isBlank()
				|| args[0].trim().toLowerCase(Locale.ROOT).endsWith(".html")
				|| (legacyRequested && (args[2] == null || args[2].isBlank()))) {
			System.err.println("Usage: PerformanceHistoryReportTool <output-directory>"
					+ " [--legacy-csv <original-performance.csv>]");
			System.err.println("Example: \"D:\\ExamData\\Exam Performance Reports\"");
			return;
		}
		Path history = ApplicationPaths.diagnosticsDirectory().resolve("history");
		try {
			Path legacyCsv = legacyRequested ? Path.of(args[2]) : null;
			Path output = generateInDirectory(history, Path.of(args[0]), Clock.systemDefaultZone(), legacyCsv);
			System.out.println("Performance report generated: " + output);
		} catch (IOException | IllegalArgumentException failure) {
			System.err.println("Performance report not generated: " + failure.getMessage());
		}
	}

	static Path generateInDirectory(Path historyDirectory, Path outputDirectory, Clock clock) throws IOException {
		return generateInDirectory(historyDirectory, outputDirectory, clock, null);
	}

	static Path generateInDirectory(Path historyDirectory, Path outputDirectory, Clock clock, Path legacyCsv)
			throws IOException {
		Objects.requireNonNull(historyDirectory, "historyDirectory");
		Objects.requireNonNull(outputDirectory, "outputDirectory");
		Objects.requireNonNull(clock, "clock");
		Path directory = outputDirectory.toAbsolutePath().normalize();
		Files.createDirectories(directory);
		if (!Files.isDirectory(directory)) {
			throw new IOException("Report output path is not a directory: " + directory);
		}
		String timestamp = LocalDateTime.now(clock).format(REPORT_TIMESTAMP);
		String stem = "performance-history-" + timestamp;
		PerformanceHistoryHtmlReport report = new PerformanceHistoryHtmlReport();
		for (int attempt = 0; attempt < 1000; attempt++) {
			String suffix = attempt == 0 ? "" : "-%03d".formatted(attempt);
			Path candidate = directory.resolve(stem + suffix + ".html");
			try {
				return report.generate(historyDirectory, candidate, legacyCsv);
			} catch (FileAlreadyExistsException collision) {

				// A concurrent report generated within the same second wins its
				// filename, so use the next numbered candidate.
			}
		}
		throw new IOException("No available report filename for second " + timestamp);
	}
}
