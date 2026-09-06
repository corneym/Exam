package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;

/**
 * Request to export one Subject's current revision corpus.
 */
public final class RevisionExportRequest {

	private final Subject subject;
	private final Path destination;

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

	public Path getDestination() {
		return destination;
	}

	public Subject getSubject() {
		return subject;
	}
}
