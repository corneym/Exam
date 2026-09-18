package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.Question;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CaptureWorkspaceLayoutTest extends QuestionBankApplicationUiTestBase {

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void acceptedAnswerRegionUsesContentHeightImmediately(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q1");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);

		// Use a shallow region so the required preview area remains clearly below
		// the 300 px maximum viewport height.
		robot.interact(() -> answerCapturePane().acceptSelection(
				new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.ANSWER, 1, 0.10, 0.10, 0.70, 0.05)));
		robot.clickOn("#add-answer-region");
		WaitForAsyncUtils.waitForFxEvents();
		javafx.scene.control.ScrollPane regionsPane = field(answerCapturePane(), "answerRegionsScrollPane",
				javafx.scene.control.ScrollPane.class);
		javafx.scene.layout.VBox regionList = field(answerCapturePane(), "answerRegionListBox",
				javafx.scene.layout.VBox.class);
		assertTrue(regionsPane.isVisible());
		assertTrue(regionsPane.isManaged());

		// The preview must acquire its content-derived height without requiring an
		// unrelated window or SplitPane resize to force another layout pass.
		double expectedHeight = Math.min(300.0, regionList.prefHeight(regionList.getWidth()) + 4.0);
		assertTrue(expectedHeight > 4.0, "Accepted Answer content must have a measurable preferred height");
		assertEquals(expectedHeight, regionsPane.getPrefHeight(), 1.0);
		assertTrue(regionsPane.getPrefHeight() < 300.0,
				"One small Answer region must not claim the full maximum viewport");
	}

	@Test
	void answerActionsKeepFullWidthAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q1");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);

		// Force the capture workspace down to its configured minimum width so this test
		// exercises the narrowest supported production layout.
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();

		// Create a pending Answer selection so selection actions, status and save
		// controls are simultaneously present in their real capture state.
		dragRegionOnDisplayedPage(robot);
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		Button clearSelection = lookup(robot, "#clear-answer-selection", Button.class);
		Button saveAnswer = lookup(robot, "#save-answer", Button.class);
		Label status = lookup(robot, "#answer-region-status", Label.class);

		// JavaFX HBox layout honours each child's minimum width. These controls use
		// USE_PREF_SIZE as their minimum, so compare their allocated width with the
		// actual minimum width JavaFX computes for their laid-out height.
		assertTrue(addRegion.getWidth() + 0.5 >= addRegion.minWidth(addRegion.getHeight()),
				"Add Region must not be compressed below its readable minimum width");
		assertTrue(clearSelection.getWidth() + 0.5 >= clearSelection.minWidth(clearSelection.getHeight()),
				"Clear must not be compressed below its readable minimum width");
		assertTrue(saveAnswer.getWidth() + 0.5 >= saveAnswer.minWidth(saveAnswer.getHeight()),
				"Save Answer must not be compressed below its readable minimum width");

		// Variable-length status text belongs on its own wrapping row so it cannot
		// consume horizontal space needed by either set of action buttons.
		assertTrue(status.isWrapText());
		assertFalse(status.getParent() == addRegion.getParent(),
				"Answer status must not share the selection-action row");
		assertFalse(status.getParent() == saveAnswer.getParent(), "Answer status must not share the save-action row");
	}

	@Test
	void answerPdfControlsRemainReadableAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q1");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		Button choosePdf = lookup(robot, "#choose-answer-pdf", Button.class);
		Label selectedPdf = lookup(robot, "#selected-answer-pdf", Label.class);
		javafx.scene.layout.VBox pdfControls = lookup(robot, "#answer-pdf-controls", javafx.scene.layout.VBox.class);
		/*
		 * Exercise a filename deliberately much longer than the supported narrow
		 * workspace. Real imported files can contain similarly descriptive names.
		 */
		robot.interact(() -> selectedPdf
				.setText("Queensland-Chemistry-External-Assessment-2024-Marking-Guide-With-A-Very-Long-Filename.pdf"));
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {
			/*
			 * Force the capture pane to the application's configured minimum width, then
			 * perform a complete CSS/layout pass before measuring controls.
			 */
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();
		/*
		 * The action must not be compressed below its JavaFX minimum readable width.
		 */
		assertTrue(choosePdf.getWidth() + 0.5 >= choosePdf.minWidth(choosePdf.getHeight()),
				"Choose PDF must remain fully readable at minimum workspace width");
		/*
		 * The filename occupies its own wrapping row rather than sharing horizontal
		 * space with the action.
		 */
		assertTrue(selectedPdf.isWrapText());
		assertEquals(pdfControls, choosePdf.getParent());
		assertEquals(pdfControls, selectedPdf.getParent());
		assertTrue(selectedPdf.getWidth() <= pdfControls.getWidth() + 0.5,
				"Selected PDF filename must stay within the Answer controls width");
	}

	@Test
	void answerQuestionStatusWrapsAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q1");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		Label status = lookup(robot, "#selected-answer-question", Label.class);

		// Use a deliberately long status representative of the longer Answer workflow
		// messages that must remain readable at the supported minimum workspace width.
		String longStatus = "Answering Q1 — 1 mark — response type unresolved; "
				+ "use Edit Metadata before capturing an answer.";
		robot.interact(() -> status.setText(longStatus));
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Force the capture workspace to its configured minimum width.
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();

		// The status must retain its complete logical text and use wrapping rather
		// than relying on ellipsis truncation.
		assertEquals(longStatus, status.getText());
		assertTrue(status.isWrapText());

		// The label must remain contained within the Answer pane rather than forcing
		// the narrow workspace wider.
		assertTrue(status.getWidth() <= answerCapturePane().getWidth() + 0.5,
				"Answer question status must remain within the Answer pane width");
	}

	@Test
	void multipleChoiceControlsRemainReadableAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);

		// Select MCQ before using the existing capture helper. The helper supplies its
		// written-response default only when no response type has already been chosen.
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		robot.clickOn(multipleChoice);
		Question question = captureQuestion(robot, "MC1");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		/*
		 * Exercise the multiple-choice controls at the minimum supported capture
		 * workspace width.
		 */
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();
		javafx.scene.layout.VBox controls = lookup(robot, "#multiple-choice-answer-controls",
				javafx.scene.layout.VBox.class);
		Label label = lookup(robot, "#multiple-choice-answer-label", Label.class);
		Button clearChoice = lookup(robot, "#clear-answer-choice", Button.class);
		Node answerA = lookup(robot, "#answer-choice-a", Node.class);
		Node answerB = lookup(robot, "#answer-choice-b", Node.class);
		Node answerC = lookup(robot, "#answer-choice-c", Node.class);
		Node answerD = lookup(robot, "#answer-choice-d", Node.class);
		/*
		 * The descriptive prompt occupies its own row while all compact answer choices
		 * share a second row. This prevents the prompt from squeezing the controls.
		 */
		assertTrue(controls.isVisible());
		assertEquals(controls, label.getParent());
		assertEquals(answerA.getParent(), answerB.getParent());
		assertEquals(answerA.getParent(), answerC.getParent());
		assertEquals(answerA.getParent(), answerD.getParent());
		assertEquals(answerA.getParent(), clearChoice.getParent());
		assertFalse(label.getParent() == clearChoice.getParent(),
				"Multiple-choice prompt must not compete with the choice controls");
		/*
		 * Clear choice is the widest action on the choice row and therefore provides
		 * the useful regression check that the row is not being compressed.
		 */
		assertTrue(clearChoice.getWidth() + 0.5 >= clearChoice.minWidth(clearChoice.getHeight()),
				"Clear choice must remain fully readable at minimum workspace width");
	}

	@Test
	void questionActionsKeepFullWidthAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);

		// Create a pending Question selection so Add Region and Clear are in their
		// normal actionable capture state.
		dragRegionOnDisplayedPage(robot);
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Force the capture workspace to its configured minimum width.
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();
		Button addRegion = lookup(robot, "#add-question-region", Button.class);
		Button clearSelection = lookup(robot, "#clear-question-selection", Button.class);
		Button saveQuestion = lookup(robot, "#save-question", Button.class);

		// Each action must retain at least its JavaFX minimum readable width.
		assertTrue(addRegion.getWidth() + 0.5 >= addRegion.minWidth(addRegion.getHeight()),
				"Add Region must remain fully readable at minimum workspace width");
		assertTrue(clearSelection.getWidth() + 0.5 >= clearSelection.minWidth(clearSelection.getHeight()),
				"Clear must remain fully readable at minimum workspace width");
		assertTrue(saveQuestion.getWidth() + 0.5 >= saveQuestion.minWidth(saveQuestion.getHeight()),
				"Save Question must remain fully readable at minimum workspace width");

		// Selection and persistence actions must be on separate rows so neither
		// group can compress the other.
		assertEquals(addRegion.getParent(), clearSelection.getParent());
		assertFalse(addRegion.getParent() == saveQuestion.getParent(),
				"Question selection actions must not share the Save row");
	}

	@Test
	void questionMetadataRemainsReadableAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Exercise Question metadata at the minimum supported workspace width.
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();
		Label questionLabel = lookup(robot, "#question-code-label", Label.class);
		Label marksLabel = lookup(robot, "#question-marks-label", Label.class);
		Label responseTypeLabel = lookup(robot, "#question-response-type-label", Label.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);

		// The shorter Question label and compact field leave sufficient room for
		// all first-row metadata without compressing its labels.
		assertEquals("Question", questionLabel.getText());
		assertTrue(questionCode.getPrefWidth() <= 80.0);
		assertTrue(questionLabel.getWidth() + 0.5 >= questionLabel.minWidth(questionLabel.getHeight()));
		assertTrue(marksLabel.getWidth() + 0.5 >= marksLabel.minWidth(marksLabel.getHeight()));

		// Response type has its own row and all three labels remain fully readable.
		assertEquals(multipleChoice.getParent(), writtenResponse.getParent());
		assertEquals(multipleChoice.getParent(), responseTypeLabel.getParent());
		assertTrue(multipleChoice.getWidth() + 0.5 >= multipleChoice.minWidth(multipleChoice.getHeight()));
		assertTrue(writtenResponse.getWidth() + 0.5 >= writtenResponse.minWidth(writtenResponse.getHeight()));
	}

	@Test
	void questionStatusWrapsAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Label status = lookup(robot, "#question-save-status", Label.class);

		// Use a deliberately long message representative of Question capture,
		// edit and shared-preamble workflow status text.
		String longStatus = "Shared preamble captured — select the remaining question "
				+ "region or regions before saving this question.";
		robot.interact(() -> status.setText(longStatus));
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Exercise the status label at the minimum supported workspace width.
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();

		// The complete logical status message must remain present and wrapping enabled.
		assertEquals(longStatus, status.getText());
		assertTrue(status.isWrapText());

		// The status must remain within the Question pane rather than forcing the
		// narrow workspace wider.
		assertTrue(status.getWidth() <= questionCapturePane().getWidth() + 0.5,
				"Question status must remain within the Question pane width");
	}
}
