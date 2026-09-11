package au.edu.eq.questionbank.ui;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

/**
 * Hosts the exam metadata import workflow outside the main application window.
 */
final class ExamImportDialog extends Dialog<Void> {

	ExamImportDialog(Stage owner, ExamMetadataPane examMetadataPane) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (examMetadataPane == null) {
			throw new NullPointerException("examMetadataPane");
		}
		initOwner(owner);
		setTitle("Import Exam");
		setHeaderText("Exam Details");
		getDialogPane().setContent(examMetadataPane);
		ButtonType confirmButtonType = new ButtonType("Confirm Details", ButtonBar.ButtonData.OK_DONE);
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
