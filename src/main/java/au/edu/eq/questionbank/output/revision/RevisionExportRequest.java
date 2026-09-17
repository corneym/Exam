package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;

/**
 * Request to export one Subject's current revision corpus.
 */
public final class RevisionExportRequest {

	private final Subject subject;
	private final Path destination;

	/**
	 * Creates an export request. Destination existence is checked when exporting.
	 *
	 * @param subject the subject whose current curriculum is exported
	 * @param destination the new output directory, absolute or relative to the working directory
	 * @throws NullPointerException if either argument is null
	 */
	public RevisionExportRequest(Subject subject, Path destination) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (destination == null) {
			throw new NullPointerException("destination");
		}
		this.subject = subject;
		this.destination = destination;
	}

	/**
	 * Returns the requested destination directory for the generated revision site.
	 *
	 * @return the requested output directory, as supplied
	 */
	public Path getDestination() {
		return destination;
	}

	/**
	 * Returns the subject whose current curriculum will organise the export.
	 *
	 * @return the subject to export
	 */
	public Subject getSubject() {
		return subject;
	}
}
