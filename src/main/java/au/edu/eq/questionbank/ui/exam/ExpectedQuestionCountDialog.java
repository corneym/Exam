package au.edu.eq.questionbank.ui.exam;

import au.edu.eq.questionbank.model.ExamBooklet;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Records the expected number of top-level Questions in an inspected Question
 * booklet.
 * <p>
 * Multipart labels such as 21a, 21b and 21c contribute one top-level Question,
 * Question 21.
 */
public final class ExpectedQuestionCountDialog extends Dialog<Integer> {

	private static final double SPACING = 10.0;
	private final TextField countField = new TextField();
	private final Label validationLabel = new Label();

	/**
	 * Creates the expected-Question-count dialog for one inspected booklet.
	 *
	 * @param owner   owning application window
	 * @param booklet persisted booklet that has just been inspected
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public ExpectedQuestionCountDialog(Window owner, ExamBooklet booklet) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		initOwner(owner);
		setTitle("Expected Question Count");
		setHeaderText("Record the top-level Questions in " + booklet.getName());
		Label instructions = new Label("""
				Count top-level Questions only.

				Multipart parts such as 21a, 21b and 21c count as one
				top-level Question 21.
				""".strip());
		instructions.setWrapText(true);
		countField.setId("expected-question-count");
		countField.setPromptText("Expected top-level Questions");
		if (booklet.getExpectedQuestionCount() != null) {

			// Existing planning remains visible when an inspected booklet is being
			// reviewed or corrected.
			countField.setText(booklet.getExpectedQuestionCount().toString());
		}
		validationLabel.setId("expected-question-count-error");
		validationLabel.setWrapText(true);
		validationLabel.setVisible(false);
		validationLabel.managedProperty().bind(validationLabel.visibleProperty());
		VBox content = new VBox(SPACING, instructions, new Label("Expected top-level Questions:"), countField,
				validationLabel);
		getDialogPane().setContent(content);
		ButtonType saveButtonType = new ButtonType("Save Count", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		getDialogPane().getButtonTypes().setAll(saveButtonType, cancelButtonType);
		Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
		saveButton.setId("save-expected-question-count");
		saveButton.addEventFilter(ActionEvent.ACTION, event -> {

			// Keep the dialog open until a positive integer has been entered.
			// Zero, negative and non-numeric values are not valid Exam
			// planning metadata.
			if (!validateCount()) {
				event.consume();
			}
		});
		setResultConverter(buttonType -> {
			if (buttonType != saveButtonType) {
				return null;
			}

			// Validation has already guaranteed that this conversion succeeds.
			return Integer.valueOf(countField.getText().strip());
		});
	}

	private boolean validateCount() {
		String text = countField.getText().strip();
		if (text.isEmpty()) {
			validationLabel.setText("Enter the number of top-level Questions.");
			validationLabel.setVisible(true);
			return false;
		}
		try {
			int count = Integer.parseInt(text);
			if (count < 1) {
				validationLabel.setText("Expected Question count must be positive.");
				validationLabel.setVisible(true);
				return false;
			}
		} catch (NumberFormatException exception) {
			validationLabel.setText("Expected Question count must be a whole number.");
			validationLabel.setVisible(true);
			return false;
		}
		validationLabel.setVisible(false);
		return true;
	}
}
