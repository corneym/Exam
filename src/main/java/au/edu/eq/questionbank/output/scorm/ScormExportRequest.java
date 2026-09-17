package au.edu.eq.questionbank.output.scorm;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;

/**
 * Request to export one Subject's revision corpus as a SCORM ZIP.
 */
public final class ScormExportRequest {

	private final Subject subject;
	private final Path destination;

	/**
	 * Creates a request for one Subject and final ZIP destination.
	 *
	 * @param subject     the Subject whose current revision corpus will be exported
	 * @param destination the final SCORM ZIP path
	 * @throws NullPointerException if either argument is null
	 */
	public ScormExportRequest(Subject subject, Path destination) {
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
	 * Returns the requested final ZIP destination.
	 *
	 * @return the destination path
	 */
	public Path getDestination() {
		return destination;
	}

	/**
	 * Returns the Subject whose current revision corpus will be exported.
	 *
	 * @return the Subject to export
	 */
	public Subject getSubject() {
		return subject;
	}
}
