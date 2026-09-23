package au.edu.eq.questionbank.output.scorm;

import java.nio.file.Path;
import java.util.Set;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;

public final class ScormExportRequest {

	private final Subject subject;
	private final Path destination;
	private final RevisionGroupingMode groupingMode;
	private final Set<Long> selectedUnitIds;

	public ScormExportRequest(Subject subject, Path destination) {
		this(subject, destination, null, null);
	}

	public ScormExportRequest(Subject subject, Path destination, RevisionGroupingMode groupingMode) {
		this(subject, destination, groupingMode, null);
	}

	public ScormExportRequest(Subject subject, Path destination, RevisionGroupingMode groupingMode,
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

	public Path getDestination() {
		return destination;
	}

	public RevisionGroupingMode getGroupingMode() {
		if (groupingMode == null) {
			throw new IllegalStateException("SCORM export request does not specify a grouping mode");
		}
		return groupingMode;
	}

	public Set<Long> getSelectedUnitIds() {
		if (selectedUnitIds == null) {
			throw new IllegalStateException("SCORM export request does not specify a Unit selection");
		}
		return selectedUnitIds;
	}

	public Subject getSubject() {
		return subject;
	}

	public boolean hasGroupingMode() {
		return groupingMode != null;
	}

	public boolean hasUnitSelection() {
		return selectedUnitIds != null;
	}
}
