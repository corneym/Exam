package au.edu.eq.questionbank.ui.export;

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
 * Collects the Subject and destination parent directory for a static revision
 * HTML export.
 */
public final class RevisionExportDialog extends Dialog<ButtonType> {

	private static final int FILE_CONTROL_SPACING = 6;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private final ComboBox<Subject> subjectBox = new ComboBox<Subject>();
	private final TextField destinationField = new TextField();
	private Path destinationParent;
	private final Button exportButton;

	/**
	 * Creates the subject and destination chooser for a revision HTML export.
	 *
	 * @param owner          window owning the dialog
	 * @param subjects       available export subjects
	 * @param defaultSubject initial subject selection, or null
	 */
	public RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject) {
		if (subjects == null) {
			throw new NullPointerException("subjects");
		}
		for (Subject subject : subjects) {
			if (subject == null) {
				throw new NullPointerException("subjects contains null");
			}
		}
		setTitle("Export Revision HTML");
		setHeaderText("Create a student revision website");
		initOwner(owner);
		ButtonType exportButtonType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
		subjectBox.setId("revision-export-subject");
		subjectBox.setPromptText("Select subject");
		subjectBox.getItems().setAll(subjects);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		if (defaultSubject != null && subjectBox.getItems().contains(defaultSubject)) {
			subjectBox.setValue(defaultSubject);
		}
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
		grid.add(new Label("Destination parent:"), 0, 1);
		grid.add(destinationBox, 1, 1);
		getDialogPane().setContent(grid);
		exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
		exportButton.setId("revision-export-start");
		exportButton.setDisable(true);
		subjectBox.valueProperty().addListener((_, _, _) -> updateExportButton(exportButton));
		updateExportButton(exportButton);
	}

	/**
	 * Returns the parent directory chosen for the revision export.
	 *
	 * @return selected directory, or null before one is chosen
	 */
	public Path getDestinationParent() {
		return destinationParent;
	}

	/**
	 * Returns the subject selected for revision export.
	 *
	 * @return selected subject, or null if no subject is selected
	 */
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
		updateExportButton(exportButton);
	}

	private void updateExportButton(Button exportButton) {
		exportButton.setDisable(subjectBox.getValue() == null || destinationParent == null);
	}
}
