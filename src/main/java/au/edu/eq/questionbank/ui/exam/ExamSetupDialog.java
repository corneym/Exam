package au.edu.eq.questionbank.ui.exam;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

/**
 * Hosts the Exam-level setup and asset-management workflow.
 * <p>
 * Existing Exams and their assets are reviewed through the supplied setup pane.
 * Creation of a new Exam or booklet delegates to the existing import workflow
 * so there remains one authoritative persistence path.
 */
public final class ExamSetupDialog extends Dialog<Void> {

	/**
	 * Creates the Exam setup dialog.
	 *
	 * @param owner          owning application stage
	 * @param examSetupPane  Exam and asset browser
	 * @param addExamHandler action that opens the existing Exam/booklet import
	 *                       workflow
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public ExamSetupDialog(Stage owner, ExamSetupPane examSetupPane, Runnable addExamHandler) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (examSetupPane == null) {
			throw new NullPointerException("examSetupPane");
		}
		if (addExamHandler == null) {
			throw new NullPointerException("addExamHandler");
		}
		initOwner(owner);
		setTitle("Exam Setup / Asset Management");
		setHeaderText("Exam Structure and Assets");
		getDialogPane().setContent(examSetupPane);
		ButtonType addExamButtonType = new ButtonType("Add Exam / Booklet...", ButtonBar.ButtonData.OTHER);
		ButtonType closeButtonType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
		getDialogPane().getButtonTypes().setAll(addExamButtonType, closeButtonType);
		Button addExamButton = (Button) getDialogPane().lookupButton(addExamButtonType);
		addExamButton.setId("exam-setup-add-exam");
		addExamButton.addEventFilter(ActionEvent.ACTION, event -> {

			// The nested import workflow returns to this setup dialog rather
			// than closing the Exam-level management surface.
			event.consume();
			addExamHandler.run();
		});
		Button closeButton = (Button) getDialogPane().lookupButton(closeButtonType);
		closeButton.setId("exam-setup-close");
	}
}
