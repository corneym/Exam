package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Tests publication progress-dialog lifecycle independently of SQLite or
 * filesystem publication.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class SubjectImportReviewWorkflowTest {

	private Stage owner;

	/**
	 * A completed task must be able to dismiss the modal progress dialog even
	 * though user cancellation was prohibited.
	 */
	@Test
	void completedPublicationDismissesNonCancellableDialog(FxRobot robot) {
		AtomicReference<Alert> reference = new AtomicReference<>();
		robot.interact(() -> {
			Alert progress = new Alert(Alert.AlertType.INFORMATION);
			progress.initOwner(owner);
			progress.setTitle("Publishing approved Subject import");
			progress.getButtonTypes().clear();
			progress.setOnCloseRequest(event -> event.consume());
			progress.show();
			reference.set(progress);
		});
		Alert progress = reference.get();
		assertTrue(progress.isShowing());
		robot.interact(() -> SubjectImportReviewWorkflow.dismissCompletedProgress(progress));
		assertFalse(progress.isShowing());
	}

	/** Creates an ordinary JavaFX owner for the modal progress dialog. */
	@Start
	void start(Stage stage) {
		owner = stage;
		stage.setScene(new Scene(new StackPane(), 300, 160));
		stage.show();
	}
}
