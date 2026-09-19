package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CaptureSelectionWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void answerRegionControlsResetAcrossSelectionAndPdfModeChanges(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q2");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		Button addAnswerRegion = lookup(robot, "#add-answer-region", Button.class);
		Button clearAnswerSelection = lookup(robot, "#clear-answer-selection", Button.class);
		assertFalse(addAnswerRegion.isDisabled());
		assertFalse(clearAnswerSelection.isDisabled());
		robot.clickOn(clearAnswerSelection);
		assertTrue(addAnswerRegion.isDisabled());
		assertTrue(clearAnswerSelection.isDisabled());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn(addAnswerRegion);
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		assertTrue(addAnswerRegion.isDisabled());
		assertTrue(clearAnswerSelection.isDisabled());
		showPdfMode("EXAM");
		assertTrue(addAnswerRegion.isDisabled());
		assertTrue(clearAnswerSelection.isDisabled());
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
	}

	@Test
	void answerSelectionSupersedesPendingQuestionSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		/*
		 * First create a persisted unanswered Question so that Answer capture has a
		 * legitimate target.
		 */
		Question question = captureQuestion(robot, "Q1");
		/*
		 * Draw another Exam-PDF rectangle without accepting it. Question capture now
		 * owns the application's single pending selection.
		 */
		dragRegionOnDisplayedPage(robot);
		Button addQuestionRegion = lookup(robot, "#add-question-region", Button.class);
		Button clearQuestionSelection = lookup(robot, "#clear-question-selection", Button.class);
		assertFalse(addQuestionRegion.isDisabled());
		assertFalse(clearQuestionSelection.isDisabled());
		/*
		 * Activate Answer capture for the stored Question and display its answer PDF.
		 * The old Question pane still has local state until another completed rectangle
		 * explicitly takes ownership.
		 */
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		/*
		 * Completing an Answer rectangle transfers global ownership to Answer capture
		 * and must discard the stale pending Question rectangle.
		 */
		dragRegionOnDisplayedPage(robot);
		Button addAnswerRegion = lookup(robot, "#add-answer-region", Button.class);
		Button clearAnswerSelection = lookup(robot, "#clear-answer-selection", Button.class);
		assertTrue(addQuestionRegion.isDisabled());
		assertTrue(clearQuestionSelection.isDisabled());
		assertFalse(addAnswerRegion.isDisabled());
		assertFalse(clearAnswerSelection.isDisabled());
		/*
		 * The central ownership state must agree with the controls presented to the
		 * user.
		 */
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.ANSWER));
	}

	@Test
	void changingFullWidthSelectionClearsPendingAnswerSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "FW1");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.ANSWER));
		assertNotNull(field(answerCapturePane(), "currentAnswerSelection", Object.class));
		CheckBox fullWidth = field(pdfWorkspace(), "fullWidthSelectionCheckBox", CheckBox.class);
		Rectangle selectionRectangle = field(pdfWorkspace(), "selectionRectangle", Rectangle.class);
		assertTrue(selectionRectangle.isVisible());
		robot.interact(fullWidth::fire);
		assertFalse(selectionState.hasPendingSelection());
		assertNull(field(answerCapturePane(), "currentAnswerSelection", Object.class));
		assertFalse(selectionRectangle.isVisible());
	}

	@Test
	void changingFullWidthSelectionClearsPendingQuestionSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		dragRegionOnDisplayedPage(robot);
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));
		assertNotNull(field(questionCapturePane(), "currentSelection", QuestionRegion.class));
		CheckBox fullWidth = field(pdfWorkspace(), "fullWidthSelectionCheckBox", CheckBox.class);
		Rectangle selectionRectangle = field(pdfWorkspace(), "selectionRectangle", Rectangle.class);
		assertTrue(selectionRectangle.isVisible());
		robot.interact(fullWidth::fire);
		assertFalse(selectionState.hasPendingSelection());
		assertNull(field(questionCapturePane(), "currentSelection", QuestionRegion.class));
		assertFalse(selectionRectangle.isVisible());
	}

	@Test
	void changingFullWidthSelectionClearsPendingSharedContextSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(questionCode).write("24a");
		robot.clickOn(marks).write("2");
		CheckBox preamble = lookup(robot, "#first-region-shared-preamble", CheckBox.class);
		assertTrue(preamble.isVisible());
		assertFalse(preamble.isSelected());
		robot.clickOn(preamble);
		assertTrue(preamble.isSelected());
		assertTrue(questionCapturePane().isCapturingSharedContext());
		dragRegionOnDisplayedPage(robot);
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		SharedContextCapturePane sharedContextPane = field(questionCapturePane(), "sharedContextCapturePane",
				SharedContextCapturePane.class);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.SHARED_CONTEXT));
		assertTrue(sharedContextPane.hasCurrentSelection());
		CheckBox fullWidth = field(pdfWorkspace(), "fullWidthSelectionCheckBox", CheckBox.class);
		Rectangle selectionRectangle = field(pdfWorkspace(), "selectionRectangle", Rectangle.class);
		assertTrue(selectionRectangle.isVisible());
		robot.interact(fullWidth::fire);
		assertFalse(selectionState.hasPendingSelection());
		assertFalse(sharedContextPane.hasCurrentSelection());
		assertFalse(selectionRectangle.isVisible());
	}

	@Test
	void movingToNextAnswerPageIsBlockedForUnacceptedSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q3");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		Button addAnswerRegion = lookup(robot, "#add-answer-region", Button.class);
		Button clearAnswerSelection = lookup(robot, "#clear-answer-selection", Button.class);
		assertFalse(addAnswerRegion.isDisabled());
		assertFalse(clearAnswerSelection.isDisabled());
		PdfWorkspacePane workspace = field(application, "pdfWorkspace", PdfWorkspacePane.class);
		int originalPage = workspace.getCurrentPageNumber();
		robot.clickOn("#next-pdf-page");
		WaitForAsyncUtils.waitForFxEvents();
		Button okButton = robot.lookup("OK").queryButton();
		robot.clickOn(okButton);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(originalPage, workspace.getCurrentPageNumber());
		assertFalse(addAnswerRegion.isDisabled());
		assertFalse(clearAnswerSelection.isDisabled());
		robot.clickOn(addAnswerRegion);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(addAnswerRegion.isDisabled());
		assertTrue(clearAnswerSelection.isDisabled());
		robot.clickOn("#next-pdf-page");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(originalPage + 1, workspace.getCurrentPageNumber());
	}

	@Test
	void questionRegionControlsRequirePendingSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Button addRegion = lookup(robot, "#add-question-region", Button.class);
		Button clearSelection = lookup(robot, "#clear-question-selection", Button.class);
		/*
		 * With no pending PDF rectangle, neither action has anything compatible to
		 * operate on and both controls must remain disabled.
		 */
		assertTrue(addRegion.isDisabled());
		assertTrue(clearSelection.isDisabled());
		/*
		 * Completing a Question selection creates the one pending rectangle owned by
		 * Question capture, so both actions become available.
		 */
		dragRegionOnDisplayedPage(robot);
		assertFalse(addRegion.isDisabled());
		assertFalse(clearSelection.isDisabled());
		/*
		 * Clearing that pending rectangle removes the actionable selection and must
		 * immediately disable both controls again.
		 */
		robot.clickOn(clearSelection);
		assertTrue(addRegion.isDisabled());
		assertTrue(clearSelection.isDisabled());
	}

	private void showPdfMode(String modeName) throws Exception {
		PdfWorkspacePane.DocumentMode mode = PdfWorkspacePane.DocumentMode.valueOf(modeName);
		WaitForAsyncUtils.asyncFx(() -> pdfWorkspace().showDocument(mode)).get();
	}
}
