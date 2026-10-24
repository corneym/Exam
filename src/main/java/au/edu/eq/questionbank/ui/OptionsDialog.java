package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/**
 * Edits application filesystem options.
 */
final class OptionsDialog extends Dialog<ButtonType> {

	private static final int PATH_FIELD_COLUMNS = 40;
	private static final int FORM_GAP = 8;
	private static final int FORM_PADDING = 10;
	private final TextField dataRootField = new TextField();
	private final CheckBox diagnosticsCheckBox = new CheckBox("Collect routine performance diagnostics");

	OptionsDialog(Stage owner, Path currentDataRoot, boolean diagnosticsEnabled) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (currentDataRoot == null) {
			throw new NullPointerException("currentDataRoot");
		}
		initOwner(owner);
		setTitle("Options");
		setHeaderText("Application options");
		ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
		dataRootField.setText(currentDataRoot.toString());
		dataRootField.setPrefColumnCount(PATH_FIELD_COLUMNS);
		Button browseButton = new Button("Browse...");
		browseButton.setOnAction(_ -> browseForDataRoot(owner));
		diagnosticsCheckBox.setId("performance-diagnostics-enabled");
		diagnosticsCheckBox.setSelected(diagnosticsEnabled);
		Label restartNote = new Label(
				"Changes to performance collection take effect " + "after restarting the application.");
		restartNote.setWrapText(true);
		GridPane grid = new GridPane();
		grid.setHgap(FORM_GAP);
		grid.setVgap(FORM_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		grid.add(new Label("Data root:"), 0, 0);
		grid.add(dataRootField, 1, 0);
		grid.add(browseButton, 2, 0);
		grid.add(new Label("Performance:"), 0, 1);
		grid.add(diagnosticsCheckBox, 1, 1, 2, 1);
		grid.add(restartNote, 1, 2, 2, 1);
		GridPane.setHgrow(dataRootField, Priority.ALWAYS);
		getDialogPane().setContent(grid);
	}

	/**
	 * Returns the entered data-root path.
	 *
	 * @return the normalized absolute data-root path
	 * @throws IllegalArgumentException if the field is blank or is not a valid path
	 */
	Path getDataRoot() {
		String value = dataRootField.getText();
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Data root must not be blank.");
		}
		try {
			return Path.of(value.trim()).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("Data root is not a valid path.");
		}
	}

	boolean isPerformanceDiagnosticsEnabled() {
		return diagnosticsCheckBox.isSelected();
	}

	private void browseForDataRoot(Stage owner) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Select Data Root");
		try {
			File currentDirectory = getDataRoot().toFile();
			if (currentDirectory.isDirectory()) {
				chooser.setInitialDirectory(currentDirectory);
			}
		} catch (IllegalArgumentException e) {

			// Leave the chooser at its default location when the entered path is invalid.
		}
		File selectedDirectory = chooser.showDialog(owner);
		if (selectedDirectory != null) {
			dataRootField.setText(selectedDirectory.toPath().toAbsolutePath().normalize().toString());
		}
	}
}
