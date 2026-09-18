package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.Path;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Collects the subject, historical syllabus version, and workbook for a legacy
 * question metadata import.
 */
public final class LegacyQuestionImportDialog extends Dialog<ButtonType> {

	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private static final int FILE_FIELD_WIDTH = 300;
	private static final int FILE_CONTROL_SPACING = 6;
	private static final int SELECTOR_WIDTH = 220;

	private final CurriculumRepository curriculumRepository;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<SyllabusVersion> syllabusBox = new ComboBox<>();
	private final TextField fileField = new TextField();
	private Path selectedFile;

	/**
	 * Creates a legacy import dialog backed by the available persisted curriculum.
	 *
	 * @param owner                the owning window
	 * @param curriculumRepository source of subjects and syllabus versions
	 * @throws NullPointerException if {@code curriculumRepository} is {@code null}
	 */
	public LegacyQuestionImportDialog(Window owner, CurriculumRepository curriculumRepository) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		this.curriculumRepository = curriculumRepository;
		ButtonType importButtonType = configureDialog(owner);
		configureSelectors();
		HBox fileBox = configureFileControls(owner);
		buildContent(fileBox);
		wireValidation(importButtonType);
	}

	/**
	 * Returns the selected workbook, or {@code null} before a valid selection.
	 *
	 * @return the selected workbook path, or {@code null}
	 */
	public Path getSelectedFile() {
		return selectedFile;
	}

	/**
	 * Returns the selected subject, or {@code null} before selection.
	 *
	 * @return the selected subject, or {@code null}
	 */
	public Subject getSelectedSubject() {
		return subjectBox.getValue();
	}

	/**
	 * Returns the explicitly selected source syllabus version.
	 *
	 * @return the selected syllabus version, or {@code null} before selection
	 */
	public SyllabusVersion getSelectedSyllabusVersion() {
		return syllabusBox.getValue();
	}

	private void buildContent(HBox fileBox) {
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectBox, 1, 0);
		grid.add(new Label("Syllabus version:"), 0, 1);
		grid.add(syllabusBox, 1, 1);
		grid.add(new Label("Excel file:"), 0, 2);
		grid.add(fileBox, 1, 2);
		getDialogPane().setContent(grid);
	}

	private void chooseFile(Window owner) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select Legacy Question Metadata Workbook");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel workbooks", "*.xlsx"));
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return;
		}
		selectedFile = file.toPath();
		fileField.setText(selectedFile.toString());
	}

	private ButtonType configureDialog(Window owner) {
		setTitle("Import Legacy Question Metadata");
		setHeaderText("Import legacy question metadata from Excel");
		initOwner(owner);
		ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
		return importButtonType;
	}

	private HBox configureFileControls(Window owner) {
		fileField.setEditable(false);
		fileField.setPrefWidth(FILE_FIELD_WIDTH);
		Button browseButton = new Button("Browse...");
		browseButton.setOnAction(_ -> chooseFile(owner));
		return new HBox(FILE_CONTROL_SPACING, fileField, browseButton);
	}

	private void configureSelectors() {
		subjectBox.getItems().setAll(curriculumRepository.findAllSubjects());
		subjectBox.setPrefWidth(SELECTOR_WIDTH);
		subjectBox.valueProperty().addListener((_, _, newSubject) -> loadSyllabusVersions(newSubject));
		syllabusBox.setPrefWidth(SELECTOR_WIDTH);
		syllabusBox.setDisable(true);
	}

	private boolean isValid() {
		if (subjectBox.getItems().isEmpty()) {
			showValidationError("No subjects are available.",
					"Import a curriculum before importing legacy question metadata.");
			return false;
		}
		if (subjectBox.getValue() == null) {
			showValidationError("No subject selected.", "Select the subject for the legacy question metadata.");
			subjectBox.requestFocus();
			return false;
		}
		if (syllabusBox.getItems().isEmpty()) {
			showValidationError("No syllabus versions are available.", "Import the required curriculum for "
					+ subjectBox.getValue().getName() + " before importing legacy question metadata.");
			return false;
		}
		if (syllabusBox.getValue() == null) {
			showValidationError("No syllabus version selected.",
					"Select the syllabus version used by the legacy question metadata.");
			syllabusBox.requestFocus();
			return false;
		}
		if (selectedFile == null) {
			showValidationError("No Excel workbook selected.",
					"Choose the legacy question metadata workbook to import.");
			return false;
		}
		return true;
	}

	private void loadSyllabusVersions(Subject subject) {
		syllabusBox.getItems().clear();
		syllabusBox.setValue(null);
		if (subject == null) {
			syllabusBox.setDisable(true);
			return;
		}
		syllabusBox.getItems().setAll(curriculumRepository.findVersionsForSubject(subject));
		syllabusBox.setDisable(false);
	}

	private void showValidationError(String header, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(getOwner());
		alert.setTitle("Legacy Question Import");
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void validateImportAction(javafx.event.ActionEvent event) {
		if (!isValid()) {
			event.consume();
		}
	}

	private void wireValidation(ButtonType importButtonType) {
		Button importButton = (Button) getDialogPane().lookupButton(importButtonType);
		importButton.addEventFilter(javafx.event.ActionEvent.ACTION, this::validateImportAction);
	}
}
