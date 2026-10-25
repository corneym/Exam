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
	 * Generates an offline report in an output directory, using a unique
	 * timestamped HTML filename. The directory is the sole program argument.
	 *
	 * @param args exactly one output directory path
	 */
	public static void main(String[] args) {
		if (args.length != 1 || args[0] == null || args[0].isBlank()
				|| args[0].trim().toLowerCase(Locale.ROOT).endsWith(".html")) {
			System.err.println("Usage: PerformanceHistoryReportTool <output-directory>");
			System.err.println("Example: \"D:\\ExamData\\Exam Performance Reports\"");
			return;
		}
		Path history = ApplicationPaths.diagnosticsDirectory().resolve("history");
		try {
			Path output = generateInDirectory(history, Path.of(args[0]), Clock.systemDefaultZone());
			System.out.println("Performance report generated: " + output);
		} catch (IOException | IllegalArgumentException failure) {
			System.err.println("Performance report not generated: " + failure.getMessage());
		}
	}

	static Path generateInDirectory(Path historyDirectory, Path outputDirectory, Clock clock) throws IOException {
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

				// The report writer uses a create-new copy, without replacement.
				// A competing invocation that wins the filename is harmless.
				return report.generate(historyDirectory, candidate);
			} catch (FileAlreadyExistsException collision) {

				// Same-second collisions receive a numbered suffix.
			}
		}
		throw new IOException("No available report filename for second " + timestamp);
	}
}
