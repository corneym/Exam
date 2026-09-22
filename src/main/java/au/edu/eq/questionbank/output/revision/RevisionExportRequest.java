package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;

/**
 * Request to export one Subject's current revision corpus.
 */
public final class RevisionExportRequest {

	private final Subject subject;
	private final Path destination;
	private final RevisionGroupingMode groupingMode;

	/**
	 * Creates an export request using the exporter's automatic safe grouping
	 * decision.
	 * <p>
	 * This constructor is retained for existing callers. New UI exports should
	 * normally supply an explicit grouping mode.
	 *
	 * @param subject     the subject whose current curriculum is exported
	 * @param destination the new output directory
	 */
	public RevisionExportRequest(Subject subject, Path destination) {
		this(subject, destination, null);
	}

	/**
	 * Creates an export request with an explicit transient grouping mode.
	 *
	 * @param subject      the subject whose current curriculum is exported
	 * @param destination  the new output directory
	 * @param groupingMode requested student-facing grouping mode
	 */
	public RevisionExportRequest(Subject subject, Path destination, RevisionGroupingMode groupingMode) {
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

	/**
	 * Returns the requested destination directory.
	 *
	 * @return requested output directory
	 */
	public Path getDestination() {
		return destination;
	}

	/**
	 * Returns the explicitly requested grouping mode.
	 *
	 * @return grouping mode
	 * @throws IllegalStateException if this legacy-compatible request did not
	 *                               specify one
	 */
	public RevisionGroupingMode getGroupingMode() {
		if (groupingMode == null) {
			throw new IllegalStateException("Revision export request does not specify a grouping mode");
		}
		return groupingMode;
	}

	/**
	 * Returns the subject whose current curriculum will organise the export.
	 *
	 * @return subject to export
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Indicates whether this request explicitly chose a grouping mode.
	 *
	 * @return true when grouping was explicitly supplied
	 */
	public boolean hasGroupingMode() {
		return groupingMode != null;
	}
}
