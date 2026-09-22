package au.edu.eq.questionbank.ui.export;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * Collects the Subject, grouping depth and destination for revision HTML
 * export.
 */
public final class RevisionExportDialog extends Dialog<ButtonType> {

	private static final int FILE_CONTROL_SPACING = 6;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<RevisionGroupingMode> groupingBox = new ComboBox<>();
	private final TextField destinationField = new TextField();
	private final Predicate<Subject> descriptorGroupingAvailable;
	private Path destinationParent;
	private final Button exportButton;

	/**
	 * Creates a safe legacy-compatible dialog. Without an eligibility provider,
	 * only Subtopic grouping is offered.
	 */
	public RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject) {
		this(owner, subjects, defaultSubject, _ -> false);
	}

	/**
	 * Creates the revision export chooser.
	 *
	 * @param owner                       owning window
	 * @param subjects                    available Subjects
	 * @param defaultSubject              initially selected Subject
	 * @param descriptorGroupingAvailable determines whether Descriptor grouping may
	 *                                    be offered for a Subject
	 */
	public RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject,
			Predicate<Subject> descriptorGroupingAvailable) {
		if (subjects == null) {
			throw new NullPointerException("subjects");
		}
		if (descriptorGroupingAvailable == null) {
			throw new NullPointerException("descriptorGroupingAvailable");
		}
		for (Subject subject : subjects) {
			if (subject == null) {
				throw new NullPointerException("subjects contains null");
			}
		}
		this.descriptorGroupingAvailable = descriptorGroupingAvailable;
		setTitle("Export Revision HTML");
		setHeaderText("Create a student revision website");
		initOwner(owner);
		ButtonType exportButtonType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
		subjectBox.setId("revision-export-subject");
		subjectBox.setPromptText("Select subject");
		subjectBox.getItems().setAll(subjects);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		groupingBox.setId("revision-export-grouping");
		groupingBox.setPromptText("Select grouping");
		groupingBox.setMaxWidth(Double.MAX_VALUE);
		destinationField.setId("revision-export-destination");
		destinationField.setEditable(false);
		destinationField.setPromptText("Choose destination folder");
		Button browseButton = new Button("Browse...");
		browseButton.setId("revision-export-browse");
		browseButton.setOnAction(_ -> chooseDestination(owner));
		HBox destinationBox = new HBox(FILE_CONTROL_SPACING, destinationField, browseButton);
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectBox, 1, 0);
		grid.add(new Label("Group questions by:"), 0, 1);
		grid.add(groupingBox, 1, 1);
		grid.add(new Label("Destination parent:"), 0, 2);
		grid.add(destinationBox, 1, 2);
		getDialogPane().setContent(grid);
		exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
		exportButton.setId("revision-export-start");
		exportButton.setDisable(true);
		subjectBox.valueProperty().addListener((_, _, _) -> {
			refreshGroupingModes();
			updateExportButton();
		});
		groupingBox.valueProperty().addListener((_, _, _) -> updateExportButton());
		if (defaultSubject != null && subjectBox.getItems().contains(defaultSubject)) {
			subjectBox.setValue(defaultSubject);
		} else {
			refreshGroupingModes();
		}
		updateExportButton();
	}

	public Path getDestinationParent() {
		return destinationParent;
	}

	public RevisionGroupingMode getGroupingMode() {
		return groupingBox.getValue();
	}

	public Subject getSelectedSubject() {
		return subjectBox.getValue();
	}

	private void chooseDestination(Window owner) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Choose Revision Export Destination");
		if (destinationParent != null) {
			File current = destinationParent.toFile();
			if (current.isDirectory()) {
				chooser.setInitialDirectory(current);
			}
		}
		File selected = chooser.showDialog(owner);
		if (selected != null) {
			setDestinationParent(selected.toPath());
		}
	}

	private void refreshGroupingModes() {
		Subject subject = subjectBox.getValue();
		RevisionGroupingMode previous = groupingBox.getValue();
		if (subject == null) {
			groupingBox.getItems().clear();
			groupingBox.setValue(null);
			return;
		}
		boolean descriptorAvailable = descriptorGroupingAvailable.test(subject);
		if (descriptorAvailable) {
			groupingBox.getItems().setAll(RevisionGroupingMode.DESCRIPTOR, RevisionGroupingMode.SUBTOPIC);
		} else {
			/*
			 * Descriptor is not merely disabled: it is not presented as an available output
			 * choice when corpus coverage is incomplete.
			 */
			groupingBox.getItems().setAll(RevisionGroupingMode.SUBTOPIC);
		}
		if (previous != null && groupingBox.getItems().contains(previous)) {
			groupingBox.setValue(previous);
		} else if (descriptorAvailable) {

			// Preserve existing Descriptor-style output when the complete corpus allows it.
			groupingBox.setValue(RevisionGroupingMode.DESCRIPTOR);
		} else {
			groupingBox.setValue(RevisionGroupingMode.SUBTOPIC);
		}
	}

	private void setDestinationParent(Path destinationParent) {
		if (destinationParent == null) {
			throw new NullPointerException("destinationParent");
		}
		Path normalized = destinationParent.toAbsolutePath().normalize();
		this.destinationParent = normalized;
		destinationField.setText(normalized.toString());
		updateExportButton();
	}

	private void updateExportButton() {
		exportButton.setDisable(
				subjectBox.getValue() == null || groupingBox.getValue() == null || destinationParent == null);
	}
}
