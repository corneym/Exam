package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.nio.file.Path;

import au.edu.eq.questionbank.ApplicationPaths;
import au.edu.eq.questionbank.diagnostics.PerformanceHistoryHtmlReport;

/**
 * Generates an offline historical performance report.
 */
public final class PerformanceHistoryReportTool {

	private PerformanceHistoryReportTool() {
	}

	/**
	 * Generates a report from this installation's historical summaries.
	 *
	 * Supply one argument: the output HTML file path. The destination must not
	 * already exist.
	 *
	 * @param args one output HTML path
	 * @throws IOException if report generation fails
	 */
	public static void main(String[] args) throws IOException {
		if (args.length != 1 || args[0].isBlank()) {
			throw new IllegalArgumentException("Usage: PerformanceHistoryReportTool <new-report.html>");
		}
		Path history = ApplicationPaths.diagnosticsDirectory().resolve("history");
		Path output = new PerformanceHistoryHtmlReport().generate(history, Path.of(args[0]));
		System.out.println("Performance report generated: " + output);
	}
}
