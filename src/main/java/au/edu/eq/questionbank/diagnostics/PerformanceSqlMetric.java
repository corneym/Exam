package au.edu.eq.questionbank.diagnostics;

/**
 * Classifies SQL counter operations without misclassifying timed SQL scopes.
 */
final class PerformanceSqlMetric {

	private PerformanceSqlMetric() {
	}

	static String unit(String operation) {
		if (operation == null) {
			return null;
		}
		int marker = operation.lastIndexOf(".sql.");
		if (marker < 0) {
			return null;
		}
		String metric = operation.substring(marker + ".sql.".length());
		return switch (metric) {
		case "connections", "execute-invocations", "rows-read", "questions-reconstructed" -> "count";
		case "connection-open-us", "execution-us", "row-next-us" -> "us";
		default -> null;
		};
	}
}
