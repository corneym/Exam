package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
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

	@Test
	void acceptAllIdenticalConfirmsRecordsAndAssets(FxRobot robot) throws TimeoutException {
		Button acceptAll = robot.lookup("#examwork-review-accept-identical").queryButton();
		ComboBox<?> filter = robot.lookup("#examwork-review-filter").queryAs(ComboBox.class);
		TableView<?> table = robot.lookup("#examwork-reconciliation-proposals").queryAs(TableView.class);
		assertFalse(acceptAll.isDisabled());
		assertTrue(dialog.review().snapshot().decisions().isEmpty());

		// Include every undecided item before bulk acceptance.
		robot.interact(() -> filter.getSelectionModel().select(1));
		assertEquals(5, table.getItems().size());
		Platform.runLater(acceptAll::fire);
		WaitForAsyncUtils.waitForFxEvents();
		DialogPane confirmation = robot.lookup("#examwork-review-bulk-confirmation").queryAs(DialogPane.class);
		assertTrue(confirmation.getHeaderText().contains("2 identical records"));
		assertTrue(confirmation.getHeaderText().contains("1 identical assets"));
		Button confirm = (Button) confirmation.lookupButton(ButtonType.OK);
		robot.interact(confirm::fire);
		WaitForAsyncUtils.waitForFxEvents();
		var review = dialog.review();
		assertEquals(Decision.ACCEPT,
				review.choice(SubjectReconciliationReview.recordKey("SUBJECT", uuid(1))).decision());
		assertEquals(Decision.ACCEPT, review.choice(SubjectReconciliationReview.recordKey("EXAM", uuid(2))).decision());
		var assetKey = review.reviewKeys().stream().filter(key -> "ASSET".equals(key.kind())).findFirst().orElseThrow();
		assertEquals(Decision.ACCEPT, review.choice(assetKey).decision());
		assertEquals(Decision.UNDECIDED,
				review.choice(SubjectReconciliationReview.recordKey("EXAM_BOOKLET", uuid(3))).decision());
		assertEquals(Decision.UNDECIDED,
				review.choice(SubjectReconciliationReview.recordKey("SOURCE_DOCUMENT", uuid(4))).decision());
		assertFalse(review.snapshot().complete());
		assertTrue(review.snapshot().dependencySafe());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, acceptAll::isDisabled);
		assertTrue(acceptAll.isDisabled());

		// Undecided view now contains only the two non-identical items.
		assertEquals(2, table.getItems().size());
		robot.interact(dialog::close);
	}

	@Test
	void advancesToNextUndecidedRowAfterDecision(FxRobot robot) {
		TableView<?> table = robot.lookup("#examwork-reconciliation-proposals").queryAs(TableView.class);
		Button accept = robot.lookup("#examwork-review-accept").queryButton();
		Button reject = robot.lookup("#examwork-review-reject").queryButton();
		assertEquals(2, table.getItems().size());

		// The compatible booklet precedes the conflicting document.
		robot.interact(() -> table.getSelectionModel().select(0));
		robot.interact(accept::fire);
		assertEquals(Decision.ACCEPT,
				dialog.review().choice(SubjectReconciliationReview.recordKey("EXAM_BOOKLET", uuid(3))).decision());

		// The accepted row disappears; the next undecided row is selected.
		assertEquals(1, table.getItems().size());
		assertEquals(0, table.getSelectionModel().getSelectedIndex());
		assertFalse(reject.isDisabled());
		robot.interact(reject::fire);
		assertEquals(Decision.REJECT,
				dialog.review().choice(SubjectReconciliationReview.recordKey("SOURCE_DOCUMENT", uuid(4))).decision());

		// No further rows need attention.
		assertEquals(0, table.getItems().size());
		assertNull(table.getSelectionModel().getSelectedItem());
		robot.interact(dialog::close);
	}

	@Test
	void cancelBulkAcceptancePreservesAllDecisions(FxRobot robot) {
		Button acceptAll = robot.lookup("#examwork-review-accept-identical").queryButton();
		Platform.runLater(acceptAll::fire);
		WaitForAsyncUtils.waitForFxEvents();
		DialogPane confirmation = robot.lookup("#examwork-review-bulk-confirmation").queryAs(DialogPane.class);
		Button cancel = (Button) confirmation.lookupButton(ButtonType.CANCEL);
		robot.interact(cancel::fire);
		assertTrue(dialog.review().snapshot().decisions().isEmpty());
		assertFalse(acceptAll.isDisabled());
		robot.interact(dialog::close);
	}

	/** Verifies explicit review UI states and no automatic approval. */
	@Test
	void displaysDecisionsAndRequiresUserToAct(FxRobot robot) {
		TableView<?> table = robot.lookup("#examwork-reconciliation-proposals").queryAs(TableView.class);
		Button accept = robot.lookup("#examwork-review-accept").queryButton();
		Button reject = robot.lookup("#examwork-review-reject").queryButton();
		Button reset = robot.lookup("#examwork-review-reset").queryButton();
		ComboBox<?> filter = robot.lookup("#examwork-review-filter").queryAs(ComboBox.class);
		assertEquals(2, table.getItems().size());
		assertTrue(dialog.review().snapshot().decisions().isEmpty());
		assertTrue(accept.isDisabled());
		robot.interact(() -> table.getSelectionModel().select(0));
		assertFalse(accept.isDisabled());
		robot.interact(accept::fire);
		assertEquals(1, table.getItems().size());
		assertFalse(reject.isDisabled());
		robot.interact(reject::fire);
		assertEquals(0, table.getItems().size());
		assertTrue(accept.isDisabled());

		// Switch to All and clear the previously accepted booklet.
		robot.interact(() -> filter.getSelectionModel().select(2));
		assertEquals(5, table.getItems().size());
		robot.interact(() -> table.getSelectionModel().select(2));
		assertFalse(reset.isDisabled());
		robot.interact(reset::fire);
		assertEquals(Decision.UNDECIDED,
				dialog.review().choice(SubjectReconciliationReview.recordKey("EXAM_BOOKLET", uuid(3))).decision());
		robot.interact(dialog::close);
	}

	@Test
	void filtersRetainIdenticalEvidenceWithoutClutteringDefaultView(FxRobot robot) {
		TableView<?> table = robot.lookup("#examwork-reconciliation-proposals").queryAs(TableView.class);
		ComboBox<?> filter = robot.lookup("#examwork-review-filter").queryAs(ComboBox.class);

		// Needs attention is the default.
		assertEquals("Needs attention", filter.getValue().toString());
		assertEquals(2, table.getItems().size());
		robot.interact(() -> filter.getSelectionModel().select(1));
		assertEquals(5, table.getItems().size());
		robot.interact(() -> filter.getSelectionModel().select(2));
		assertEquals(5, table.getItems().size());
		robot.interact(() -> filter.getSelectionModel().select(0));
		assertEquals(2, table.getItems().size());
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
		Proposal booklet = new Proposal("EXAM_BOOKLET", uuid(3), null, uuid(2), uuid(12),
				Classification.COMPATIBLE_ADDITION, "Independent booklet can be added");
		Proposal document = new Proposal("SOURCE_DOCUMENT", uuid(4), uuid(14), null, null, Classification.CONFLICT,
				"Source document content differs");
		AssetEvidence asset = new AssetEvidence("subjects/Biology/exams/booklet.pdf", "QUESTION_PDF", "EXAM", uuid(2),
				"subjects/Biology/exams/booklet.pdf", "a".repeat(64), "a".repeat(64), Classification.IDENTICAL,
				"Matching verified PDF bytes");
		Result preview = new Result(uuid(100), uuid(200), "Biology", List.of(subject, exam, booklet, document),
				List.of(), List.of(asset), List.of());
		dialog = new SubjectReconciliationReviewDialog(stage, new SubjectReconciliationReview(preview),
				java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
		dialog.show();
	}

	/** Creates deterministic UUIDs for the test preview only. */
	private UUID uuid(long number) {
		return new UUID(0L, number);
	}
}
