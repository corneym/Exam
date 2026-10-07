package au.edu.eq.questionbank.ui.curriculum;

import java.io.File;
import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;
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

	private static final int FILE_CONTROL_SPACING = 6;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private final TextField subjectField = new TextField();
	private final TextField versionField = new TextField();
	private final CheckBox currentCheckBox = new CheckBox();
	private final TextField fileField = new TextField();
	private Path selectedFile;
	private final Path curriculumDataRoot;
	private final Subject fixedSubject;

	/**
	 * Creates a curriculum-import dialog owned by the supplied window.
	 *
	 * @param owner              the window that owns the modal dialog
	 * @param curriculumDataRoot the managed curriculum directory used as the
	 *                           initial file-chooser location
	 * @throws NullPointerException if {@code curriculumDataRoot} is {@code null}
	 */
	public CurriculumImportDialog(Window owner, Path curriculumDataRoot) {
		this(owner, curriculumDataRoot, null, false);
	}

	/**
	 * Creates an Excel curriculum-import dialog fixed to one authoritative
	 * application Subject.
	 *
	 * @param owner              owner window
	 * @param curriculumDataRoot managed curriculum data directory
	 * @param subject            Subject that will own the imported syllabus
	 * @throws NullPointerException if {@code curriculumDataRoot} or {@code subject}
	 *                              is {@code null}
	 */
	public CurriculumImportDialog(Window owner, Path curriculumDataRoot, Subject subject) {
		this(owner, curriculumDataRoot, subject, true);
	}

	private CurriculumImportDialog(Window owner, Path curriculumDataRoot, Subject fixedSubject, boolean subjectFixed) {
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		if (subjectFixed && fixedSubject == null) {
			throw new NullPointerException("subject");
		}
		this.fixedSubject = fixedSubject;
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
		setTitle("Import Curriculum");
		setHeaderText("Import curriculum from Excel");
		initOwner(owner);
		ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
		subjectField.setId("curriculum-import-subject");
		versionField.setId("curriculum-import-version");
		currentCheckBox.setId("curriculum-import-current");
		fileField.setId("curriculum-import-file");
		if (fixedSubject != null) {

			// Subject is inherited from the Dashboard and displayed as read-only context.
			subjectField.setText(fixedSubject.getName());
			subjectField.setEditable(false);
			subjectField.setFocusTraversable(false);
		}
		fileField.setEditable(false);
		Button browseButton = new Button("Browse...");
		browseButton.setId("curriculum-import-browse");
		browseButton.setOnAction(_ -> chooseFile(owner));
		HBox fileBox = new HBox(FILE_CONTROL_SPACING, fileField, browseButton);
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
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
		importButton.setId("import-curriculum");
		importButton.addEventFilter(javafx.event.ActionEvent.ACTION, this::validateImportAction);
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

		// Fixed-Subject Dashboard import cannot be redirected by editing presentation
		// state.
		if (fixedSubject != null) {
			return fixedSubject.getName();
		}
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

	private void chooseFile(Window owner) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select Curriculum Excel File");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel workbooks", "*.xlsx"));

		// Managed curriculum storage remains the useful default browse location, but
		// users may select a source workbook from anywhere on the filesystem.
		File root = curriculumDataRoot.toFile();
		if (root.isDirectory()) {
			chooser.setInitialDirectory(root);
		}
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return;
		}
		selectedFile = file.toPath().toAbsolutePath().normalize();
		fileField.setText(selectedFile.toString());
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

	private void validateImportAction(javafx.event.ActionEvent event) {
		if (!isValid()) {
			event.consume();
		}
	}
}
