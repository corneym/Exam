package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveValidator;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest;
import au.edu.eq.questionbank.service.workpackage.ExamWorkPackageType;
import au.edu.eq.questionbank.service.workpackage.PackageFormatException;

/**
 * Associates explicit application import operations with validated ExamWork
 * manifest types.
 *
 * <p>
 * Archive filenames do not determine which workflow is permitted.
 */
final class WorkPackageImportRoute {

	private WorkPackageImportRoute() {
	}

	static String title(ExamWorkPackageType type) {
		Objects.requireNonNull(type, "type");
		return switch (type) {
		case SUBJECT_SNAPSHOT -> "Import Subject Snapshot";
		case WORK_ASSIGNMENT -> "Import Work Assignment";
		case CONTRIBUTION -> "Import Contribution";
		case IMPORT_RECEIPT ->
			throw new IllegalArgumentException("Import receipt acknowledgement " + "is not available.");
		};
	}

	static ExamWorkManifest validate(Path archive, String applicationVersion, int schemaVersion,
			ExamWorkPackageType expectedType) throws IOException {
		String requestedOperation = title(expectedType);
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archive, applicationVersion, schemaVersion);
		ExamWorkPackageType actualType = manifest.packageType();
		if (actualType != expectedType) {
			String guidance = actualType == ExamWorkPackageType.IMPORT_RECEIPT
					? "Import receipt acknowledgement " + "is not available."
					: "Use " + title(actualType) + " for this archive instead.";

			// A valid archive of the wrong type must not be
			// silently redirected into a different import workflow.
			throw new PackageFormatException(requestedOperation + " requires a " + description(expectedType)
					+ " archive, but the selected file contains a " + description(actualType) + " archive.\n\n"
					+ guidance);
		}
		return manifest;
	}

	private static String description(ExamWorkPackageType type) {
		return switch (type) {
		case SUBJECT_SNAPSHOT -> "Subject Snapshot";
		case WORK_ASSIGNMENT -> "Work Assignment";
		case CONTRIBUTION -> "Contribution";
		case IMPORT_RECEIPT -> "Receipt";
		};
	}
}
