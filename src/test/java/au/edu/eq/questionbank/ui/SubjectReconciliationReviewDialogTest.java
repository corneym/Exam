package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Deterministic TestFX checks for coordinator decisions in a read-only preview.
 *
 * <p>
 * The tests use semantic JavaFX selection and Button.fire, never pointer-based
 * activation of ordinary controls. No persistence or PDF processing occurs.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class SubjectReconciliationReviewDialogTest {

	private SubjectReconciliationReviewDialog dialog;

	/** Verifies explicit review UI states and no automatic approval. */
	@Test
	void displaysDecisionsAndRequiresUserToAct(FxRobot robot) {
		TableView<?> table = robot.lookup("#examwork-reconciliation-proposals").queryAs(TableView.class);
		Button accept = robot.lookup("#examwork-review-accept").queryButton();
		Button reject = robot.lookup("#examwork-review-reject").queryButton();
		Button reset = robot.lookup("#examwork-review-reset").queryButton();
		assertEquals(2, table.getItems().size());
		assertTrue(dialog.review().snapshot().decisions().isEmpty());
		assertTrue(accept.isDisabled());
		robot.interact(() -> table.getSelectionModel().select(0));
		assertFalse(accept.isDisabled());
		robot.interact(accept::fire);
		assertEquals(1, dialog.review().snapshot().decisions().size());
		assertEquals(Decision.ACCEPT, dialog.review().snapshot().decisions().values().iterator().next().decision());
		robot.interact(reject::fire);
		assertEquals(Decision.REJECT, dialog.review().snapshot().decisions().values().iterator().next().decision());
		robot.interact(reset::fire);
		assertTrue(dialog.review().snapshot().decisions().isEmpty());
		robot.interact(dialog::close);
	}

	/**
	 * Opens the read-only dialog against a real JavaFX owner without any database.
	 */
	@Start
	void start(Stage stage) {
		stage.setScene(new Scene(new StackPane(), 320, 180));
		stage.show();
		Proposal subject = new Proposal("SUBJECT", uuid(1), uuid(11), null, null, Classification.IDENTICAL,
				"Biology Subject identity agrees");
		Proposal exam = new Proposal("EXAM", uuid(2), uuid(12), uuid(1), uuid(11), Classification.IDENTICAL,
				"Exam metadata and identity agree");
		Result preview = new Result(uuid(100), uuid(200), "Biology", List.of(subject, exam), List.of(), List.of(),
				List.of());
		dialog = new SubjectReconciliationReviewDialog(stage, new SubjectReconciliationReview(preview),
				java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
		dialog.show();
	}

	/** Creates deterministic UUIDs for the test preview only. */
	private UUID uuid(long number) {
		return new UUID(0L, number);
	}
}
