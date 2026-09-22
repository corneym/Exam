package au.edu.eq.questionbank.ui.correction;

import au.edu.eq.questionbank.model.Exam;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * Corrects metadata owned by an existing Exam without changing its persistent
 * identity or the relationships that refer to it.
 */
public final class ExamMetadataCorrectionDialog
		extends javafx.scene.control.Dialog<ExamMetadataCorrectionDialog.Result> {

	private static final int DIALOG_WIDTH = 520;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private final Exam exam;
	private final TextField providerField = new TextField();
	private final TextField yearField = new TextField();
	private final TextField assessmentField = new TextField();
	private final ButtonType saveButtonType = new ButtonType("Save Exam", ButtonBar.ButtonData.OK_DONE);

	/**
	 * Creates an Exam metadata correction dialog.
	 *
	 * @param owner dialog owner
	 * @param exam  persisted Exam being corrected
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public ExamMetadataCorrectionDialog(Window owner, Exam exam) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		this.exam = exam;
		initOwner(owner);
		setTitle("Edit Exam Metadata");
		setHeaderText(exam.getProvider().getName() + " " + exam.getYear() + " — " + exam.getName());
		setResizable(true);
		configureControls();
		getDialogPane().setContent(createContent());
		getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
		configureSaveButton();
		setResultConverter(buttonType -> {
			if (buttonType != saveButtonType) {
				return null;
			}
			return new Result(providerField.getText().trim(), Integer.parseInt(yearField.getText().trim()),
					assessmentField.getText().trim());
		});
		getDialogPane().setPrefWidth(DIALOG_WIDTH);
	}

	private void configureControls() {
		providerField.setId("exam-correction-provider");
		yearField.setId("exam-correction-year");
		assessmentField.setId("exam-correction-assessment");
		providerField.setText(exam.getProvider().getName());
		yearField.setText(Integer.toString(exam.getYear()));
		assessmentField.setText(exam.getName());
		providerField.setMaxWidth(Double.MAX_VALUE);
		yearField.setMaxWidth(Double.MAX_VALUE);
		assessmentField.setMaxWidth(Double.MAX_VALUE);
	}

	private void configureSaveButton() {
		Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
		saveButton.setId("exam-correction-save");
		saveButton.disableProperty().bind(Bindings.createBooleanBinding(() -> !metadataIsValid(),
				providerField.textProperty(), yearField.textProperty(), assessmentField.textProperty()));
	}

	private GridPane createContent() {
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		Label explanation = new Label(
				"""
						This correction applies to the Exam itself.

						All booklets, questions, answers, source questions and shared contexts linked to this Exam keep their existing identities.
						""");
		explanation.setWrapText(true);
		grid.add(createFieldLabel("Subject", "exam-correction-subject-label"), 0, 0);
		grid.add(new Label(exam.getSubject().getName()), 1, 0);
		grid.add(createFieldLabel("Provider", "exam-correction-provider-label"), 0, 1);
		grid.add(providerField, 1, 1);
		grid.add(createFieldLabel("Year", "exam-correction-year-label"), 0, 2);
		grid.add(yearField, 1, 2);
		grid.add(createFieldLabel("Assessment", "exam-correction-assessment-label"), 0, 3);
		grid.add(assessmentField, 1, 3);
		grid.add(explanation, 1, 4);
		GridPane.setHgrow(providerField, Priority.ALWAYS);
		GridPane.setHgrow(yearField, Priority.ALWAYS);
		GridPane.setHgrow(assessmentField, Priority.ALWAYS);
		return grid;
	}

	private Label createFieldLabel(String text, String id) {
		Label label = new Label(text);
		label.setId(id);

		// Correction-field labels must remain readable when the dialog narrows.
		label.setMinWidth(Region.USE_PREF_SIZE);
		return label;
	}

	private boolean metadataIsValid() {
		if (providerField.getText() == null || providerField.getText().isBlank()) {
			return false;
		}
		if (assessmentField.getText() == null || assessmentField.getText().isBlank()) {
			return false;
		}
		if (yearField.getText() == null || yearField.getText().isBlank()) {
			return false;
		}
		try {
			return Integer.parseInt(yearField.getText().trim()) > 0;
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	/**
	 * User-approved replacement Exam metadata.
	 *
	 * @param providerName   corrected provider
	 * @param year           corrected assessment year
	 * @param assessmentName corrected assessment name
	 */
	public record Result(String providerName, int year, String assessmentName) {

		/**
		 * Validates replacement Exam metadata.
		 */
		public Result {
			if (providerName == null || providerName.isBlank()) {
				throw new IllegalArgumentException("providerName must not be blank");
			}
			if (year < 1) {
				throw new IllegalArgumentException("year must be positive");
			}
			if (assessmentName == null || assessmentName.isBlank()) {
				throw new IllegalArgumentException("assessmentName must not be blank");
			}
		}
	}
}
