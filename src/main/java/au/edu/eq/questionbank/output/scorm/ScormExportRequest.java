package au.edu.eq.questionbank.output.scorm;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;

/**
 * Request to export one Subject's revision corpus as a SCORM ZIP.
 */
public final class ScormExportRequest {

	private final Subject subject;
	private final Path destination;
	private final RevisionGroupingMode groupingMode;

	/**
	 * Creates a request using automatic safe revision grouping.
	 *
	 * @param subject     Subject whose revision corpus will be exported
	 * @param destination final SCORM ZIP path
	 */
	public ScormExportRequest(Subject subject, Path destination) {
		this(subject, destination, null);
	}

	/**
	 * Creates a request with an explicit student-facing grouping mode.
	 *
	 * @param subject      Subject whose revision corpus will be exported
	 * @param destination  final SCORM ZIP path
	 * @param groupingMode requested revision grouping mode
	 */
	public ScormExportRequest(Subject subject, Path destination, RevisionGroupingMode groupingMode) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (destination == null) {
			throw new NullPointerException("destination");
		}
		this.subject = subject;
		this.destination = destination;
		this.groupingMode = groupingMode;
	}

	public Path getDestination() {
		return destination;
	}

	public RevisionGroupingMode getGroupingMode() {
		if (groupingMode == null) {
			throw new IllegalStateException("SCORM export request does not specify a grouping mode");
		}
		return groupingMode;
	}

	public Subject getSubject() {
		return subject;
	}

	public boolean hasGroupingMode() {
		return groupingMode != null;
	}
}
