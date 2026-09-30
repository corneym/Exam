package au.edu.eq.questionbank.ui.exam;

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

	// Subject identity comes from the application Working Subject. This dialog may
	// choose only the historical syllabus used by the workbook.
	private final Subject subject;
	private final CurriculumRepository curriculumRepository;
	private final Label subjectLabel = new Label();
	private final ComboBox<SyllabusVersion> syllabusBox = new ComboBox<>();
	private final TextField fileField = new TextField();
	private Path selectedFile;

	/**
	 * Creates legacy Question-metadata intake for one authoritative Working
	 * Subject.
	 *
	 * @param owner                owning window
	 * @param subject              authoritative application Working Subject
	 * @param curriculumRepository source of syllabus versions for that Subject
	 * @throws NullPointerException if {@code subject} or
	 *                              {@code curriculumRepository} is {@code null}
	 */
	public LegacyQuestionImportDialog(Window owner, Subject subject, CurriculumRepository curriculumRepository) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		this.subject = subject;
		this.curriculumRepository = curriculumRepository;

		// Configure the immutable Working Subject context before offering historical
		// syllabus and workbook choices.
		ButtonType importButtonType = configureDialog(owner);
		configureSyllabusSelector();
		HBox fileBox = configureFileControls(owner);
		buildContent(fileBox);
		wireValidation(importButtonType);
	}

	/**
	 * Returns the selected workbook, or {@code null} before a valid selection.
	 *
	 * @return selected workbook path, or {@code null}
	 */
	public Path getSelectedFile() {
		return selectedFile;
	}

	/**
	 * Returns the explicitly selected source syllabus version.
	 *
	 * @return selected syllabus version, or {@code null} before selection
	 */
	public SyllabusVersion getSelectedSyllabusVersion() {
		return syllabusBox.getValue();
	}

	private void buildContent(HBox fileBox) {
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));

		// Subject is displayed as inherited context rather than as a second selector.
		grid.add(new Label("Working Subject:"), 0, 0);
		grid.add(subjectLabel, 1, 0);
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

			// Cancelling the native chooser leaves the current dialog selections intact.
			return;
		}
		selectedFile = file.toPath();
		fileField.setText(selectedFile.toString());
	}

	private ButtonType configureDialog(Window owner) {
		setTitle("Import Legacy Question Metadata");
		setHeaderText("Import legacy Question metadata for " + subject.getName());
		initOwner(owner);
		ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
		return importButtonType;
	}

	private HBox configureFileControls(Window owner) {
		fileField.setId("legacy-question-import-workbook");
		fileField.setEditable(false);
		fileField.setPrefWidth(FILE_FIELD_WIDTH);
		Button browseButton = new Button("Browse...");
		browseButton.setId("legacy-question-import-browse");
		browseButton.setOnAction(_ -> chooseFile(owner));
		return new HBox(FILE_CONTROL_SPACING, fileField, browseButton);
	}

	private void configureSyllabusSelector() {
		subjectLabel.setId("legacy-question-import-subject");
		subjectLabel.setText(subject.getName());
		syllabusBox.setId("legacy-question-import-syllabus");
		syllabusBox.setPrefWidth(SELECTOR_WIDTH);

		// Historical and current versions remain valid choices, but every choice is
		// constrained to the already authoritative Working Subject.
		syllabusBox.getItems().setAll(curriculumRepository.findVersionsForSubject(subject));
		syllabusBox.setDisable(syllabusBox.getItems().isEmpty());
	}

	private boolean isValid() {
		if (syllabusBox.getItems().isEmpty()) {
			showValidationError("No syllabus versions are available.", "Import the required curriculum for "
					+ subject.getName() + " before importing legacy Question metadata.");
			return false;
		}
		if (syllabusBox.getValue() == null) {
			showValidationError("No syllabus version selected.",
					"Select the syllabus version used by the legacy Question metadata.");
			syllabusBox.requestFocus();
			return false;
		}
		if (selectedFile == null) {
			showValidationError("No Excel workbook selected.",
					"Choose the legacy Question metadata workbook to import.");
			return false;
		}
		return true;
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

			// Invalid intake remains open so the missing syllabus or workbook can be
			// supplied without restarting the workflow.
			event.consume();
		}
	}

	private void wireValidation(ButtonType importButtonType) {
		Button importButton = (Button) getDialogPane().lookupButton(importButtonType);

		// Dialog validation runs before JavaFX is allowed to complete the Import
		// action.
		importButton.addEventFilter(javafx.event.ActionEvent.ACTION, this::validateImportAction);
	}
}
