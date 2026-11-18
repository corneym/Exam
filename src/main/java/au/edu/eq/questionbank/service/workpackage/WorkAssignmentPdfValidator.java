package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;

/** Checks that an assignment dependency can be opened by the application's PDF reader. */
final class WorkAssignmentPdfValidator {

	private WorkAssignmentPdfValidator() {
	}

	static void validate(Path file, String description) throws IOException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) {
			throw new PackageFormatException("Missing or unreadable assignment PDF: " + description);
		}
		// Use the same tolerant parser as normal viewing; do not impose PDF/A or
		// signature checks that would reject existing, readable examination PDFs.
		try (var document = Loader.loadPDF(file.toFile())) {
			if (document.getNumberOfPages() == 0) {
				throw new IOException("PDF has no pages");
			}
		} catch (IOException failure) {
			throw new PackageFormatException("Invalid or unreadable assignment PDF: " + description, failure);
		}
	}
}
