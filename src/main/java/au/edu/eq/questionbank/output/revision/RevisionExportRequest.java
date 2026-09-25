package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;
import java.util.Set;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;

/**
 * Immutable configuration for generating a static HTML revision site.
 * <p>
 * Grouping and Unit selection are optional so older callers can request the
 * service's safe defaults. When supplied, selected Unit identifiers are copied.
 */
public final class RevisionExportRequest {

	private final Subject subject;
	private final Path destination;
	private final RevisionGroupingMode groupingMode;
	private final Set<Long> selectedUnitIds;

	/**
	 * Creates a request using automatic grouping and all exportable Units.
	 *
	 * @param subject     subject whose current revision corpus will be exported
	 * @param destination destination directory to create
	 */
	public RevisionExportRequest(Subject subject, Path destination) {
		this(subject, destination, null, null);
	}

	/**
	 * Creates a request for all exportable Units using explicit grouping.
	 *
	 * @param subject      subject whose current revision corpus will be exported
	 * @param destination  destination directory to create
	 * @param groupingMode grouping mode to use
	 */
	public RevisionExportRequest(Subject subject, Path destination, RevisionGroupingMode groupingMode) {
		this(subject, destination, groupingMode, null);
	}

	/**
	 * Creates a fully configured request.
	 *
	 * @param subject         subject whose current revision corpus will be exported
	 * @param destination     destination directory to create
	 * @param groupingMode    grouping mode, or {@code null} to select it
	 *                        automatically
	 * @param selectedUnitIds Unit identifiers to include, or {@code null} for all
	 *                        exportable Units
	 * @throws NullPointerException     if the subject or destination is
	 *                                  {@code null}, or the Unit selection contains
	 *                                  {@code null}
	 * @throws IllegalArgumentException if the Unit selection is empty or contains a
	 *                                  non-positive identifier
	 */
	public RevisionExportRequest(Subject subject, Path destination, RevisionGroupingMode groupingMode,
			Set<Long> selectedUnitIds) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (destination == null) {
			throw new NullPointerException("destination");
		}
		if (selectedUnitIds != null) {
			if (selectedUnitIds.isEmpty()) {
				throw new IllegalArgumentException("At least one Unit must be selected");
			}
			for (Long unitId : selectedUnitIds) {
				if (unitId == null) {
					throw new NullPointerException("selectedUnitIds contains null");
				}
				if (unitId.longValue() < 1) {
					throw new IllegalArgumentException("Selected Unit IDs must be positive");
				}
			}
		}
		this.subject = subject;
		this.destination = destination;
		this.groupingMode = groupingMode;
		this.selectedUnitIds = selectedUnitIds == null ? null : Set.copyOf(selectedUnitIds);
	}

	/**
	 * Returns the requested destination directory.
	 *
	 * @return export destination
	 */
	public Path getDestination() {
		return destination;
	}

	/**
	 * Returns the explicitly requested grouping mode.
	 *
	 * @return explicit grouping mode
	 * @throws IllegalStateException if this request uses automatic grouping
	 */
	public RevisionGroupingMode getGroupingMode() {
		if (groupingMode == null) {
			throw new IllegalStateException("Revision export request does not specify a grouping mode");
		}
		return groupingMode;
	}

	/**
	 * Returns the explicitly selected Unit identifiers.
	 *
	 * @return immutable selected Unit identifiers
	 * @throws IllegalStateException if this request includes all exportable Units
	 */
	public Set<Long> getSelectedUnitIds() {
		if (selectedUnitIds == null) {
			throw new IllegalStateException("Revision export request does not specify a Unit selection");
		}
		return selectedUnitIds;
	}

	/**
	 * Returns the subject to export.
	 *
	 * @return export subject
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Returns whether grouping was selected explicitly.
	 *
	 * @return {@code true} when {@link #getGroupingMode()} is available
	 */
	public boolean hasGroupingMode() {
		return groupingMode != null;
	}

	/**
	 * Returns whether the request is restricted to selected Units.
	 *
	 * @return {@code true} when {@link #getSelectedUnitIds()} is available
	 */
	public boolean hasUnitSelection() {
		return selectedUnitIds != null;
	}
}
