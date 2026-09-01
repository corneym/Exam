package au.edu.eq.questionbank.ui;

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
		getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
	}
}
