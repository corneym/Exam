package au.edu.eq.questionbank.ui.correction;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SyllabusVersion;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * Edits the correctable metadata of an existing legacy question without
 * changing its captured question/answer regions or document identity.
 */
public final class LegacyQuestionMetadataDialog
		extends javafx.scene.control.Dialog<LegacyQuestionMetadataDialog.Result> {

	private static final int DIALOG_WIDTH = 650;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private final Question question;
	private final TextField questionCodeField = new TextField();
	private final TextField marksField = new TextField();
	private final ComboBox<QuestionResponseType> responseTypeBox = new ComboBox<>();
	private final CheckBox sharedContextRequiredCheckBox = new CheckBox("Legacy shared-context capture required");
	private final ButtonType saveButtonType = new ButtonType("Save Metadata", ButtonBar.ButtonData.OK_DONE);

	/**
	 * Creates the legacy metadata editor.
	 *
	 * @param owner                dialog owner
	 * @param question             question being corrected
	 * @param curriculumRepository curriculum hierarchy lookup
	 */
	/**
	 * Creates the legacy metadata editor.
	 *
	 * @param owner    dialog owner
	 * @param question question being corrected
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public LegacyQuestionMetadataDialog(Window owner, Question question) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (question == null) {
			throw new NullPointerException("question");
		}
		this.question = question;
		initOwner(owner);
		setTitle("Edit Question Metadata");
		setHeaderText(question.getExam().getProvider().getName() + " " + question.getExam().getYear() + " — "
				+ question.getBooklet().getName() + " — " + question.getQuestionCode());
		setResizable(true);
		configureControls();
		getDialogPane().setContent(createContent());
		getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
		configureSaveButton();
		setResultConverter(buttonType -> {
			if (buttonType != saveButtonType) {
				return null;
			}

			// Classification is deliberately excluded from metadata correction.
			// Full curriculum reclassification is handled by Edit Question.
			return new Result(questionCodeField.getText().trim(), Integer.parseInt(marksField.getText().trim()),
					sharedContextRequiredCheckBox.isSelected(), responseTypeBox.getValue());
		});
		getDialogPane().setPrefWidth(DIALOG_WIDTH);
	}

	private void configureControls() {
		questionCodeField.setId("legacy-metadata-question-code");
		marksField.setId("legacy-metadata-marks");
		responseTypeBox.setId("legacy-metadata-response-type");
		sharedContextRequiredCheckBox.setId("legacy-metadata-shared-context-required");
		questionCodeField.setText(question.getQuestionCode());
		marksField.setText(Integer.toString(question.getMarks()));
		responseTypeBox.getItems().setAll(QuestionResponseType.values());
		responseTypeBox.setValue(question.getResponseType());
		responseTypeBox.setMaxWidth(Double.MAX_VALUE);
		responseTypeBox.setCellFactory(_ -> createResponseTypeCell());
		responseTypeBox.setButtonCell(createResponseTypeCell());

		// Metadata correction retains the Question's existing classification.
		// Curriculum changes are made through the full Question editor.
		sharedContextRequiredCheckBox.setSelected(question.isSharedContextCaptureRequired());
	}

	private void configureSaveButton() {
		Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
		saveButton.setId("legacy-metadata-save");

		// Save validity now depends only on metadata that this dialog actually edits.
		saveButton.disableProperty().bind(Bindings.createBooleanBinding(() -> !metadataIsValid(),
				questionCodeField.textProperty(), marksField.textProperty(), responseTypeBox.valueProperty()));
	}

	private GridPane createContent() {
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		SyllabusVersion syllabusVersion = question.getClassification().getSyllabusVersion();
		Label subjectValue = new Label(syllabusVersion.getSubject().getName());
		Label syllabusValue = new Label(syllabusVersion.getName());
		Label bookletValue = new Label(question.getBooklet().getName());
		Label hintExplanation = new Label("""
				This is historical capture evidence and may be corrected.

				For a single-part question, changing this from required to not required \
				converts any captured shared context into ordinary question regions. You will \
				then be offered the option to recapture the complete question.

				A captured shared context cannot be removed from only one part of a \
				multipart question.
				""");
		hintExplanation.setWrapText(true);

		// Subject and syllabus remain visible context, but are not editable metadata.
		grid.add(createFieldLabel("Subject", "legacy-metadata-subject-label"), 0, 0);
		grid.add(subjectValue, 1, 0);
		grid.add(createFieldLabel("Syllabus", "legacy-metadata-syllabus-label"), 0, 1);
		grid.add(syllabusValue, 1, 1);
		grid.add(createFieldLabel("Booklet", "legacy-metadata-booklet-label"), 0, 2);
		grid.add(bookletValue, 1, 2);
		grid.add(createFieldLabel("Question code", "legacy-metadata-question-code-label"), 0, 3);
		grid.add(questionCodeField, 1, 3);
		grid.add(createFieldLabel("Marks", "legacy-metadata-marks-label"), 0, 4);
		grid.add(marksField, 1, 4);
		grid.add(createFieldLabel("Response type", "legacy-metadata-response-type-label"), 0, 5);
		grid.add(responseTypeBox, 1, 5);
		grid.add(sharedContextRequiredCheckBox, 1, 6);
		grid.add(hintExplanation, 1, 7);
		GridPane.setHgrow(questionCodeField, Priority.ALWAYS);
		GridPane.setHgrow(marksField, Priority.ALWAYS);
		GridPane.setHgrow(responseTypeBox, Priority.ALWAYS);
		return grid;
	}

	private Label createFieldLabel(String text, String id) {
		Label label = new Label(text);
		label.setId(id);

		// Metadata labels must retain their complete text when the dialog narrows.
		label.setMinWidth(Region.USE_PREF_SIZE);
		return label;
	}

	private ListCell<QuestionResponseType> createResponseTypeCell() {
		return new ListCell<>() {

			@Override
			protected void updateItem(QuestionResponseType responseType, boolean empty) {
				super.updateItem(responseType, empty);
				if (empty || responseType == null) {
					setText(null);
					return;
				}
				setText(responseTypeLabel(responseType));
			}
		};
	}

	private boolean metadataIsValid() {
		if (questionCodeField.getText() == null || questionCodeField.getText().isBlank()) {
			return false;
		}
		if (responseTypeBox.getValue() == null) {
			return false;
		}
		try {

			// Marks are the only numeric editable metadata and must remain positive.
			return Integer.parseInt(marksField.getText().trim()) > 0;
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	private String responseTypeLabel(QuestionResponseType responseType) {
		return switch (responseType) {
		case MULTIPLE_CHOICE -> "Multiple choice";
		case WRITTEN_RESPONSE -> "Written response";
		case UNKNOWN -> "Unknown";
		};
	}

	/**
	 * User-approved replacement metadata.
	 *
	 * @param questionCode                 corrected question code
	 * @param marks                        corrected positive mark value
	 * @param sharedContextCaptureRequired corrected historical shared context hint
	 * @param responseType                 corrected Question response type
	 */
	public record Result(String questionCode, int marks, boolean sharedContextCaptureRequired,
			QuestionResponseType responseType) {

		/**
		 * Validates the replacement metadata accepted by the dialog.
		 *
		 * @param questionCode                 corrected question code
		 * @param marks                        corrected positive mark value
		 * @param sharedContextCaptureRequired corrected historical shared context hint
		 * @param responseType                 corrected Question response type
		 */
		public Result {
			if (questionCode == null || questionCode.isBlank()) {
				throw new IllegalArgumentException("questionCode must not be blank");
			}
			if (marks < 1) {
				throw new IllegalArgumentException("marks must be positive");
			}
			if (responseType == null) {
				throw new NullPointerException("responseType");
			}
		}
	}
}
