package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import au.edu.eq.questionbank.model.Subject;
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
 * Collects the Subject and destination parent directory for a SCORM revision
 * export.
 */
public final class ScormExportDialog extends Dialog<ButtonType> {

	private final ComboBox<Subject> subjectBox = new ComboBox<Subject>();
	private final TextField destinationField = new TextField();
	private Path destinationParent;
	private final Button exportButton;

	/**
	 * Creates a SCORM export dialog for the available Subjects.
	 *
	 * @param owner          owner for this dialog and its directory chooser
	 * @param subjects       Subjects available for export
	 * @param defaultSubject Subject initially selected when it is in
	 *                       {@code subjects}; otherwise no Subject is selected
	 * @throws NullPointerException if {@code subjects} or one of its elements is
	 *                              null
	 */
	public ScormExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject) {
		if (subjects == null) {
			throw new NullPointerException("subjects");
		}
		for (Subject subject : subjects) {
			if (subject == null) {
				throw new NullPointerException("subjects contains null");
			}
		}
		setTitle("Export Revision SCORM");
		setHeaderText("Create a SCORM 1.2 revision package for QLearn");
		initOwner(owner);
		ButtonType exportButtonType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
		subjectBox.setId("scorm-export-subject");
		subjectBox.setPromptText("Select subject");
		subjectBox.getItems().setAll(subjects);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		if (defaultSubject != null && subjectBox.getItems().contains(defaultSubject)) {
			subjectBox.setValue(defaultSubject);
		}
		destinationField.setId("scorm-export-destination");
		destinationField.setEditable(false);
		destinationField.setPromptText("Choose destination folder");
		Button browseButton = new Button("Browse...");
		browseButton.setId("scorm-export-browse");
		browseButton.setOnAction(_ -> chooseDestination(owner));
		HBox destinationBox = new HBox(6, destinationField, browseButton);
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.setPadding(new Insets(10));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectBox, 1, 0);
		grid.add(new Label("Destination parent:"), 0, 1);
		grid.add(destinationBox, 1, 1);
		getDialogPane().setContent(grid);
		exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
		exportButton.setId("scorm-export-start");
		exportButton.setDisable(true);
		subjectBox.valueProperty().addListener((_, _, _) -> updateExportButton());
		updateExportButton();
	}

	/**
	 * Returns the selected directory in which the application will name the ZIP.
	 *
	 * @return the absolute normalised directory, or {@code null} until selected
	 */
	public Path getDestinationParent() {
		return destinationParent;
	}

	/**
	 * Returns the Subject selected for export.
	 *
	 * @return the selected Subject, or {@code null} when none is selected
	 */
	public Subject getSelectedSubject() {
		return subjectBox.getValue();
	}

	private void chooseDestination(Window owner) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Choose SCORM Export Destination");
		if (destinationParent != null) {
			File current = destinationParent.toFile();
			if (current.isDirectory()) {
				chooser.setInitialDirectory(current);
			}
		}
		File selected = chooser.showDialog(owner);
		if (selected == null) {
			return;
		}
		setDestinationParent(selected.toPath());
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
		exportButton.setDisable(subjectBox.getValue() == null || destinationParent == null);
	}
}
