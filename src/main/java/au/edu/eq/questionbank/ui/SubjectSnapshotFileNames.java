package au.edu.eq.questionbank.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Creates portable, Subject-specific filenames for new snapshot packages. */
final class SubjectSnapshotFileNames {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private SubjectSnapshotFileNames() {
	}

	static String suggest(String subjectName, LocalDateTime localTime) {
		Objects.requireNonNull(subjectName, "subjectName");
		Objects.requireNonNull(localTime, "localTime");

		// Retain Unicode letters/digits while excluding characters unsafe in
		// Windows filenames and separators that could imply directory traversal.
		String safeSubject = subjectName.strip().replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^[._-]+|[._-]+$",
				"");
		if (safeSubject.isEmpty()) {
			safeSubject = "Subject";
		}
		return safeSubject + "_SubjectSnapshot_" + localTime.format(TIMESTAMP) + ".examwork";
	}
}
