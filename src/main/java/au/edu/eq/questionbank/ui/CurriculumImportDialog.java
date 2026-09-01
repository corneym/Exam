package au.edu.eq.questionbank.ui;

import java.io.File;
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
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Modal form for choosing a two-column curriculum workbook and the subject and
 * syllabus-version metadata under which it will be imported.
 */
public class CurriculumImportDialog extends Dialog<ButtonType> {

	private final TextField subjectField = new TextField();
	private final TextField versionField = new TextField();
	private final CheckBox currentCheckBox = new CheckBox();
	private final TextField fileField = new TextField();
	private Path selectedFile;

	/**
	 * Creates a curriculum-import dialog owned by the supplied window.
	 *
	 * @param owner the window that owns the modal dialog
	 */
	public CurriculumImportDialog(Window owner) {
		setTitle("Import Curriculum");
		setHeaderText("Import curriculum from Excel");
		initOwner(owner);
		ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
		fileField.setEditable(false);
		Button browseButton = new Button("Browse...");
		browseButton.setOnAction(event -> chooseFile(owner));
		HBox fileBox = new HBox(6, fileField, browseButton);

		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.setPadding(new Insets(10));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectField, 1, 0);
		grid.add(new Label("Curriculum version:"), 0, 1);
		grid.add(versionField, 1, 1);
		grid.add(new Label("Current:"), 0, 2);
		grid.add(currentCheckBox, 1, 2);
		grid.add(new Label("Excel file:"), 0, 3);
		grid.add(fileBox, 1, 3);
		getDialogPane().setContent(grid);

		Button importButton = (Button) getDialogPane().lookupButton(importButtonType);
		importButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
			if (!isValid()) {
				event.consume();
			}
		});
	}

	private void chooseFile(Window owner) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select Curriculum Excel File");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel workbooks", "*.xlsx"));
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return;
		}
		selectedFile = file.toPath();
		fileField.setText(selectedFile.toString());
	}

	/**
	 * Returns the workbook chosen for import.
	 *
	 * @return the workbook selected by the user, or {@code null} before selection
	 */
	public Path getSelectedFile() {
		return selectedFile;
	}

	/**
	 * Returns the subject name entered in the dialog.
	 *
	 * @return the trimmed subject name entered by the user
	 */
	public String getSubjectName() {
		return subjectField.getText().strip();
	}

	/**
	 * Returns the syllabus-version name entered in the dialog.
	 *
	 * @return the trimmed syllabus-version name entered by the user
	 */
	public String getVersionName() {
		return versionField.getText().strip();
	}

	/**
	 * Reports whether the imported syllabus should be marked current.
	 *
	 * @return whether the imported version should become current
	 */
	public boolean isCurrent() {
		return currentCheckBox.isSelected();
	}

	private boolean isValid() {
		if (getSubjectName().isBlank()) {
			subjectField.requestFocus();
			return false;
		}
		if (getVersionName().isBlank()) {
			versionField.requestFocus();
			return false;
		}
		if (selectedFile == null) {
			return false;
		}
		return true;
	}
}
