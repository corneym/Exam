package au.edu.eq.questionbank.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import au.edu.eq.questionbank.model.Exam;

/**
 * Creates human-readable, portable filenames for whole-Exam assignments and
 * their returned contributions.
 */
final class WorkPackageFileNames {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private WorkPackageFileNames() {
	}

	static String assignment(Exam exam, String contributorName, LocalDateTime localTime) {
		return suggest(exam, contributorName, localTime, "assignment");
	}

	static String contribution(Exam exam, String contributorName, LocalDateTime localTime) {
		return suggest(exam, contributorName, localTime, "contribution");
	}

	private static String suggest(Exam exam, String contributorName, LocalDateTime localTime, String archiveType) {
		Objects.requireNonNull(exam, "exam");
		Objects.requireNonNull(contributorName, "contributorName");
		Objects.requireNonNull(localTime, "localTime");
		String description = String.join("-", exam.getSubject().getName(), exam.getProvider().getName(),
				Integer.toString(exam.getYear()), exam.getName(), contributorName);

		// Preserve the established assignment filename convention
		// while excluding directory separators and unsafe characters.
		String safe = description.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-")
				.replaceAll("^[._-]+|[._-]+$", "");
		if (safe.isBlank()) {
			safe = "Exam";
		}
		if (safe.length() > 140) {
			safe = safe.substring(0, 140);
		}
		return safe + "-" + localTime.format(TIMESTAMP) + "-" + archiveType + ".examwork";
	}
}
