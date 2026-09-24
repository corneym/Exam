package au.edu.eq.questionbank.ui.exam;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

/**
 * Hosts the exam metadata import workflow outside the main application window.
 */
public final class ExamImportDialog extends Dialog<Void> {

	/**
	 * Creates a modal wrapper for an exam-metadata pane.
	 *
	 * @param owner            owner stage
	 * @param examMetadataPane pane that validates and persists the selected exam
	 */
	public ExamImportDialog(Stage owner, ExamMetadataPane examMetadataPane) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (examMetadataPane == null) {
			throw new NullPointerException("examMetadataPane");
		}
		initOwner(owner);

		// Present this workflow as opening an exam for capture; persistence details
		// remain an implementation concern of the existing metadata workflow.
		setTitle("Open Exam for Capture");
		setHeaderText("Exam Details");
		getDialogPane().setContent(examMetadataPane);
		ButtonType confirmButtonType = new ButtonType("Open for Capture", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		getDialogPane().getButtonTypes().setAll(confirmButtonType, cancelButtonType);
		Button confirmButton = (Button) getDialogPane().lookupButton(confirmButtonType);
		confirmButton.setId("confirm-exam-details");
		confirmButton.addEventFilter(ActionEvent.ACTION, event -> confirmExamDetails(event, examMetadataPane));
		Button cancelButton = (Button) getDialogPane().lookupButton(cancelButtonType);
		cancelButton.setId("cancel-exam-import");
	}

	private void confirmExamDetails(ActionEvent event, ExamMetadataPane examMetadataPane) {
		if (!examMetadataPane.confirmDetails()) {
			event.consume();
		}
	}
}
