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
import au.edu.eq.questionbank.ui.exam.ExamSetupDialog;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CaptureWorkspaceLayoutTest extends QuestionBankApplicationUiTestBase {

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
	void acceptedQuestionContentKeepsPreferredHeightAcrossWorkspaceRelayout(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.interact(() -> questionCapturePane().acceptSelection(
				new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 1, 0.10, 0.10, 0.70, 0.05)));
		fireControl(robot, "#add-question-region");
		WaitForAsyncUtils.waitForFxEvents();
		javafx.scene.control.ScrollPane regionsPane = field(questionCapturePane(), "regionsScrollPane",
				javafx.scene.control.ScrollPane.class);
		javafx.scene.layout.VBox regionList = field(questionCapturePane(), "regionPreviewBox",
				javafx.scene.layout.VBox.class);

		// A small accepted region must immediately establish a useful content-derived
		// preferred height rather than collapsing to a few pixels.
		double expectedHeight = Math.min(300.0, regionList.getLayoutBounds().getHeight() + 4.0);
		assertTrue(expectedHeight > 4.0, "Accepted Question content must have a measurable preferred height");
		assertEquals(expectedHeight, regionsPane.getPrefHeight(), 1.0);
		assertEquals(regionsPane.prefHeight(regionsPane.getWidth()), regionsPane.minHeight(regionsPane.getWidth()),
				1.0);
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Force the horizontal workspace to perform the relayout that previously
			// exposed the collapsed Content Parts viewport.
			splitPane.setDividerPosition(0, 0.25);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();
		double requiredHeight = regionsPane.minHeight(regionsPane.getWidth());
		assertTrue(regionsPane.getHeight() + 1.0 >= requiredHeight,
				"Workspace relayout must not shrink accepted Question content below its preferred height");
	}

	@Test
	void activeExamBookletIsVisibleAndCanReturnToExamSetup(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Label activeExam = lookup(robot, "#active-exam-booklet", Label.class);
		Node activeContext = lookup(robot, "#active-exam-context", Node.class);
		Button changeExam = lookup(robot, "#change-exam-assets", Button.class);

		// The compact action names the operation rather than repeating the Assets
		// terminology already represented by Exam Setup.
		assertEquals("Change Exam", changeExam.getText());

		// The visible capture context identifies the authoritative Exam and booklet
		// independently of the current curriculum classification.
		// The standard workflow fixture creates QCAA 2024 External Assessment,
		// Paper 1 MCQ. Assert the complete visible context so a missing or stale part
		// of the active booklet identity cannot pass independently.
		assertEquals("QCAA 2024 External Assessment — Paper 1 MCQ [ACTIVE]", activeExam.getText());
		assertTrue(isDescendantOf(activeExam, activeContext));
		assertFalse(changeExam.isDisabled());
		assertEquals("Change Exam", changeExam.getText());

		VBox activeBox = (VBox) activeContext;

		// Active Exam must use exactly two rows: heading/action followed by the
		// full-width Exam/booklet description.
		assertEquals(2, activeBox.getChildren().size());

		Node headerRow = activeBox.getChildren().getFirst();
		assertTrue(headerRow instanceof javafx.scene.layout.HBox);

		// Change Exam belongs beside the heading on row one.
		assertEquals(headerRow, changeExam.getParent());

		// The potentially long Exam/booklet identity owns row two by itself.
		assertEquals(activeBox, activeExam.getParent());
		assertEquals(activeExam, activeBox.getChildren().get(1));

		assertTrue(((Parent) headerRow).getChildrenUnmodifiable().stream()
				.anyMatch(node -> node instanceof Label label && "Active Exam / Booklet".equals(label.getText())));
		ExamSetupDialog setupDialog = field(application, "examSetupDialog", ExamSetupDialog.class);

		// The visible context action returns directly to the ordinary Exam Setup /
		// asset-management workflow rather than opening another capture mechanism.
		fireControlLater(changeExam);
		WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS, setupDialog::isShowing);
		assertTrue(setupDialog.isShowing());
		fireDialogButton(robot, "Close");
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

		// Exercise a filename deliberately much longer than the supported narrow
		// workspace. Real imported files can contain similarly descriptive names.
		robot.interact(() -> selectedPdf
				.setText("Queensland-Chemistry-External-Assessment-2024-Marking-Guide-With-A-Very-Long-Filename.pdf"));
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Force the capture pane to the application's configured minimum width, then
			// perform a complete CSS/layout pass before measuring controls.
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();

		// The action must not be compressed below its JavaFX minimum readable width.
		assertTrue(choosePdf.getWidth() + 0.5 >= choosePdf.minWidth(choosePdf.getHeight()),
				"Choose PDF must remain fully readable at minimum workspace width");

		// The filename occupies its own wrapping row rather than sharing horizontal
		// space with the action.
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
	void captureModeIsHostedBelowApplicationSubject(FxRobot robot) {
		Node workingSubject = lookup(robot, "#working-subject-context", Node.class);
		Node modeHost = lookup(robot, "#workspace-mode-host", Node.class);
		Node captureMode = lookup(robot, "#capture-workspace-mode", Node.class);
		Node activeExam = lookup(robot, "#active-exam-context", Node.class);
		Node captureWorkspace = lookup(robot, "#capture-workspace", Node.class);

		// Working Subject is application context and must survive future workspace-mode
		// changes rather than becoming part of either Capture or Exam/Assets mode.
		assertFalse(isDescendantOf(workingSubject, modeHost));

		// The host owns one complete mode. Capture mode therefore carries both the
		// active Exam/booklet context and the existing capture workspace together.
		assertEquals(modeHost, captureMode.getParent());
		assertEquals(captureMode, activeExam.getParent());
		assertEquals(captureMode, captureWorkspace.getParent());

		// Capture mode must remain beneath the persistent Subject context rather than
		// accidentally re-parenting Subject into the swappable workspace.
		assertTrue(isDescendantOf(activeExam, modeHost));
		assertTrue(isDescendantOf(captureWorkspace, modeHost));
		assertFalse(isDescendantOf(workingSubject, captureMode));
	}

	@Test
	void captureWorkspaceContainsSeparateClassificationQuestionAndAnswerSections(FxRobot robot) {
		Node captureWorkspace = lookup(robot, "#capture-workspace", Node.class);
		Node workingSubject = lookup(robot, "#working-subject-context", Node.class);
		Node activeExam = lookup(robot, "#active-exam-context", Node.class);
		Node classification = lookup(robot, "#classification-context", Node.class);
		Label classificationHeading = (Label) ((Parent) classification).lookup(".label");
		assertEquals("Classification", classificationHeading.getText());

		// Application-level Subject and Exam/booklet context deliberately remain
		// outside the capture-workspace boundary.
		assertFalse(isDescendantOf(workingSubject, captureWorkspace));
		assertFalse(isDescendantOf(activeExam, captureWorkspace));

		// Classification, Question and Answer are three distinct section containers
		// directly grouped by the larger capture workspace.
		assertEquals(captureWorkspace, classification.getParent());
		assertEquals(captureWorkspace, questionCapturePane().getParent());
		assertEquals(captureWorkspace, answerCapturePane().getParent());

		// Re-parenting Classification must not move Working Subject with it.
		ComboBox<?> subject = lookup(robot, "#curriculum-subject", ComboBox.class);
		ComboBox<?> syllabus = lookup(robot, "#curriculum-syllabus", ComboBox.class);
		assertTrue(isDescendantOf(subject, workingSubject));
		assertTrue(isDescendantOf(syllabus, classification));
	}

	@Test
	void mainWorkspaceUsesExpandedInitialHeight() {

		// The additional application and Exam context introduced above the capture
		// panes requires more initial vertical space than the former 840 px layout.
		assertEquals(900.0, primaryStage.getScene().getHeight(), 1.0);
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

		// Exercise the multiple-choice controls at the minimum supported capture
		// workspace width.
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

		// The descriptive prompt occupies its own row while all compact answer choices
		// share a second row. This prevents the prompt from squeezing the controls.
		assertTrue(controls.isVisible());
		assertEquals(controls, label.getParent());
		assertEquals(answerA.getParent(), answerB.getParent());
		assertEquals(answerA.getParent(), answerC.getParent());
		assertEquals(answerA.getParent(), answerD.getParent());
		assertEquals(answerA.getParent(), clearChoice.getParent());
		assertFalse(label.getParent() == clearChoice.getParent(),
				"Multiple-choice prompt must not compete with the choice controls");

		// Clear choice is the widest action on the choice row and therefore provides
		// the useful regression check that the row is not being compressed.
		assertTrue(clearChoice.getWidth() + 0.5 >= clearChoice.minWidth(clearChoice.getHeight()),
				"Clear choice must remain fully readable at minimum workspace width");
	}

	@Test
	void questionActionsKeepFullWidthAtMinimumWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);

		// Create a pending Question selection so Add Region is in its ordinary
		// actionable state.
		dragRegionOnDisplayedPage(robot);
		javafx.scene.control.SplitPane splitPane = field(application, "workspaceSplitPane",
				javafx.scene.control.SplitPane.class);
		robot.interact(() -> {

			// Force the capture workspace to the application's configured minimum
			// width before measuring the remaining Question actions.
			splitPane.setDividerPosition(0, 0.0);
			primaryStage.getScene().getRoot().applyCss();
			primaryStage.getScene().getRoot().layout();
		});
		WaitForAsyncUtils.waitForFxEvents();
		Button addRegion = lookup(robot, "#add-question-region", Button.class);
		Button addFromClipboard = lookup(robot, "#paste-question-image", Button.class);
		Button saveQuestion = lookup(robot, "#save-question", Button.class);
		assertEquals("Add Region", addRegion.getText());
		assertEquals("Add From Clipboard", addFromClipboard.getText());

		// Both Question-content creation actions must remain fully readable.
		assertTrue(addRegion.getWidth() + 0.5 >= addRegion.minWidth(addRegion.getHeight()),
				"Add Region must remain fully readable at minimum workspace width");
		assertTrue(addFromClipboard.getWidth() + 0.5 >= addFromClipboard.minWidth(addFromClipboard.getHeight()),
				"Add From Clipboard must remain fully readable at minimum workspace width");
		assertTrue(saveQuestion.getWidth() + 0.5 >= saveQuestion.minWidth(saveQuestion.getHeight()),
				"Save Question must remain fully readable at minimum workspace width");

		// The two content-creation actions share a row, while persistence remains
		// separate.
		assertEquals(addRegion.getParent(), addFromClipboard.getParent());
		assertFalse(addRegion.getParent() == saveQuestion.getParent(),
				"Question content actions must not share the Save row");
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
		// edit and shared-context workflow status text.
		String longStatus = "Shared context captured — select the remaining question "
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

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void workingSubjectContextUsesSingleSubjectRow(FxRobot robot) {
		ComboBox<?> subject = lookup(robot, "#curriculum-subject", ComboBox.class);
		Label subjectLabel = lookup(robot, "#working-subject-label", Label.class);
		Button addSubject = lookup(robot, "#add-subject", Button.class);
		Parent workingSubjectContext = lookup(robot, "#working-subject-context", Parent.class);

		// Working Subject still consists of exactly one layout row after adding the
		// compact Subject-creation action.
		assertEquals(1, workingSubjectContext.getChildrenUnmodifiable().size());

		Node subjectRow = workingSubjectContext.getChildrenUnmodifiable().getFirst();
		assertTrue(subjectRow instanceof javafx.scene.layout.GridPane);
		assertEquals(subjectRow, subjectLabel.getParent());
		assertEquals("Subject", subjectLabel.getText());

		// Subject selection and its compact + action share the control side of that
		// same GridPane row rather than introducing a second row.
		assertEquals(subject.getParent(), addSubject.getParent());
		assertEquals(subjectRow, subject.getParent().getParent());
		assertEquals("+", addSubject.getText());
		assertEquals("Add Subject", addSubject.getTooltip().getText());

		// Subject remains the visual heading for the application-level context.
		primaryStage.getScene().getRoot().applyCss();
		assertTrue(subjectLabel.getFont().getStyle().contains("Bold"));
	}

	@Test
	void workingSubjectIsVisuallySeparateFromClassification(FxRobot robot) {
		ComboBox<?> subject = lookup(robot, "#curriculum-subject", ComboBox.class);
		Node workingSubjectContext = lookup(robot, "#working-subject-context", Node.class);
		Node classificationContext = lookup(robot, "#classification-context", Node.class);

		// The Subject selector belongs to application context, not inside the
		// Question-classification section.
		assertTrue(isDescendantOf(subject, workingSubjectContext));
		assertFalse(isDescendantOf(subject, classificationContext));
		ComboBox<?> syllabus = lookup(robot, "#curriculum-syllabus", ComboBox.class);

		// Syllabus selection still belongs to classification within the already
		// selected Subject.
		assertTrue(isDescendantOf(syllabus, classificationContext));
	}

	private boolean isDescendantOf(Node node, Node ancestor) {
		Parent parent = node.getParent();
		while (parent != null) {
			if (parent == ancestor) {
				return true;
			}
			parent = parent.getParent();
		}
		return false;
	}
}
