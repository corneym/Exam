package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
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
	void activeExamBookletIsVisibleAndCanOpenExamAssetsWorkspace(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Label activeExam = lookup(robot, "#active-exam-booklet", Label.class);
		Node activeContext = lookup(robot, "#active-exam-context", Node.class);
		Button changeExam = lookup(robot, "#change-exam-assets", Button.class);

		// Capture mode continues to identify the authoritative active Exam/booklet.
		assertEquals("QCAA 2024 External Assessment — Paper 1 MCQ [ACTIVE]", activeExam.getText());
		assertTrue(isDescendantOf(activeExam, activeContext));
		assertFalse(changeExam.isDisabled());
		assertEquals("Change Exam", changeExam.getText());
		VBox activeBox = (VBox) activeContext;
		assertEquals(2, activeBox.getChildren().size());
		Node headerRow = activeBox.getChildren().getFirst();
		assertTrue(headerRow instanceof javafx.scene.layout.HBox);
		assertEquals(headerRow, changeExam.getParent());
		assertEquals(activeBox, activeExam.getParent());

		// The production Change Exam action now replaces Capture mode with the
		// main-window Exam/Assets workspace.
		fireControl(robot, changeExam);
		WaitForAsyncUtils.waitForFxEvents();
		Parent modeHost = lookup(robot, "#workspace-mode-host", Parent.class);
		Node examAssets = lookup(robot, "#exam-assets-workspace", Node.class);
		assertEquals(modeHost, examAssets.getParent());

		// Working Subject remains permanent application context outside the swappable
		// workspace.
		Node workingSubject = lookup(robot, "#working-subject-context", Node.class);
		assertFalse(isDescendantOf(workingSubject, examAssets));
		ComboBox<?> examSelector = lookup(robot, "#exam-assets-exam", ComboBox.class);
		Label state = lookup(robot, "#exam-assets-state", Label.class);
		ComboBox<?> provider = lookup(robot, "#exam-assets-provider", ComboBox.class);
		ComboBox<?> year = lookup(robot, "#exam-assets-year", ComboBox.class);
		ComboBox<?> assessment = lookup(robot, "#exam-assets-assessment", ComboBox.class);
		VBox questionBooklets = lookup(robot, "#exam-assets-question-booklets", VBox.class);

		// The screen must be populated from the persisted Exam hierarchy, not merely
		// display an empty shell.
		assertFalse(examSelector.getItems().isEmpty());
		assertFalse(examSelector.getSelectionModel().isEmpty());
		assertEquals("State: ACTIVE", state.getText());

		// Exam Details initially display persisted values but cannot be modified until
		// the explicit Edit action is entered.
		assertEquals("QCAA", provider.getValue());
		assertEquals(2024, year.getValue());
		assertEquals("External Assessment", assessment.getValue());
		assertTrue(provider.isDisabled());
		assertTrue(year.isDisabled());
		assertTrue(assessment.isDisabled());
		assertEquals(1, questionBooklets.getChildren().size());
		assertEquals("QCAA 2024 External Assessment — Paper 1 MCQ [ACTIVE]", activeExam.getText());
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
	void answerExplanationMetadataCanBeChangedAndReloaded(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet, "Marking guide",
				"Chemistry/2024/marking-guide.pdf");

		// Enter the real Exam/Assets workspace after the AnswerFile has been persisted.
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		CheckBox explanations = lookup(robot, "#exam-assets-answer-explanations-" + answerFile.getId(), CheckBox.class);

		// Newly created AnswerFiles default to no recorded explanations.
		assertFalse(explanations.isSelected());

		// Use semantic activation because this test concerns metadata behaviour rather
		// than pointer hit-testing.
		fireControl(robot, explanations);
		WaitForAsyncUtils.waitForFxEvents();
		AnswerFile persisted = answerWriter.findAnswerFiles(booklet.getExam()).stream()
				.filter(candidate -> candidate.getId() == answerFile.getId()).findFirst().orElseThrow();
		assertTrue(persisted.hasAnswerExplanations());

		// Return to Capture through the production transition, then reopen Exam/Assets
		// so the checkbox must be reconstructed from persistence rather than retained
		// local control state.
		fireControl(robot, "#exam-assets-use-selected-booklet");
		WaitForAsyncUtils.waitForFxEvents();
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		CheckBox reloaded = lookup(robot, "#exam-assets-answer-explanations-" + answerFile.getId(), CheckBox.class);
		assertTrue(reloaded.isSelected());
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
	void cancellingExamDetailsEditRestoresMetadataAndStaysInExamAssets(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		@SuppressWarnings("unchecked")
		ComboBox<String> provider = lookup(robot, "#exam-assets-provider", ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Integer> year = lookup(robot, "#exam-assets-year", ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<String> assessment = lookup(robot, "#exam-assets-assessment", ComboBox.class);
		Button edit = lookup(robot, "#exam-assets-edit", Button.class);
		Button cancel = lookup(robot, "#exam-assets-cancel", Button.class);

		// Persisted Exam metadata must be visible before editing begins.
		assertEquals("QCAA", provider.getValue());
		assertEquals("QCAA", provider.getEditor().getText());
		assertEquals(Integer.valueOf(2024), year.getValue());
		assertEquals("External Assessment", assessment.getValue());
		assertEquals("External Assessment", assessment.getEditor().getText());
		fireControl(robot, edit);

		// Entering Edit must preserve rather than clear the existing Exam metadata.
		assertEquals("QCAA", provider.getEditor().getText());
		assertEquals(Integer.valueOf(2024), year.getValue());
		assertEquals("External Assessment", assessment.getEditor().getText());
		robot.interact(() -> {
			provider.getEditor().setText("Temporary Provider");
			assessment.getEditor().setText("Temporary Assessment");
		});
		fireControl(robot, cancel);
		WaitForAsyncUtils.waitForFxEvents();

		// Cancel discards staged changes but remains in the Exam/Assets workspace.
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		assertTrue(robot.lookup("#capture-workspace-mode").tryQuery().isEmpty());
		assertEquals("QCAA", provider.getValue());
		assertEquals("QCAA", provider.getEditor().getText());
		assertEquals(Integer.valueOf(2024), year.getValue());
		assertEquals("External Assessment", assessment.getValue());
		assertEquals("External Assessment", assessment.getEditor().getText());

		// Re-entering Edit must still begin from the persisted values.
		fireControl(robot, edit);
		assertEquals("QCAA", provider.getEditor().getText());
		assertEquals(Integer.valueOf(2024), year.getValue());
		assertEquals("External Assessment", assessment.getEditor().getText());
	}

	@Test
	void cancellingNewExamRestoresPreviousExam(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		@SuppressWarnings("unchecked")
		ComboBox<Exam> examSelector = lookup(robot, "#exam-assets-exam", ComboBox.class);
		Exam original = examSelector.getValue();
		Button addNewExam = lookup(robot, "#exam-assets-add-new-exam", Button.class);
		Button cancel = lookup(robot, "#exam-assets-new-exam-cancel", Button.class);
		fireControl(robot, addNewExam);
		@SuppressWarnings("unchecked")
		ComboBox<String> assessment = lookup(robot, "#exam-assets-assessment", ComboBox.class);
		robot.interact(() -> assessment.getEditor().setText("Unsaved Exam"));
		fireControl(robot, cancel);
		WaitForAsyncUtils.waitForFxEvents();

		// Cancel restores the exact persisted Exam that was selected before creation
		// began and must not leave the staged metadata behind.
		assertNotNull(examSelector.getValue());
		assertEquals(original.getId(), examSelector.getValue().getId());
		assertEquals(original.getName(), assessment.getValue());
		assertTrue(assessment.isDisabled());
		assertFalse(examSelector.isDisabled());
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
	void editingQuestionBookletUsesInspectionWithoutChangingCaptureBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		fireControl(robot, "#exam-assets-question-edit-" + activeBooklet.getId());
		WaitForAsyncUtils.waitForFxEvents();

		// Structural configuration opens the authoritative booklet for read-only
		// inspection but must not change capture identity.
		assertEquals(activeBooklet.getId(), examMetadataPane().getBooklet().getId());
		PdfWorkspacePane.DocumentMode displayedDocument = field(pdfWorkspace(), "displayedDocument",
				PdfWorkspacePane.DocumentMode.class);
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, displayedDocument);
	}

	@Test
	void examAssetsCanAddAnswerBookletWithExplanationMetadata(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		Path answerSource = pdfDataRoot.resolve("marking-guide.pdf");
		Files.copy(examPdf, answerSource);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		ExamAssetsPane examAssetsPane = field(application, "examAssetsPane", ExamAssetsPane.class);

		// Bypass only the native chooser. The rest of the real Exam/Assets editing
		// and application persistence workflow remains under test.
		robot.interact(() -> {
			try {
				invoke(examAssetsPane, "beginAnswerBookletAdd", new Class<?>[] { Path.class }, answerSource);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		TextField name = lookup(robot, "#exam-assets-new-answer-name", TextField.class);
		CheckBox explanations = lookup(robot, "#exam-assets-new-answer-explanations", CheckBox.class);
		Button save = lookup(robot, "#exam-assets-new-answer-save", Button.class);
		robot.interact(() -> name.setText("Marking Guide"));
		fireControl(robot, explanations);
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(booklet.getExam());
		assertEquals(1, answerFiles.size());
		AnswerFile stored = answerFiles.getFirst();
		assertEquals("Marking Guide", stored.getName());
		assertTrue(stored.hasAnswerExplanations());

		// The normal persisted Answer row replaces the temporary editor after Save.
		CheckBox persistedExplanations = lookup(robot, "#exam-assets-answer-explanations-" + stored.getId(),
				CheckBox.class);
		assertTrue(persistedExplanations.isSelected());
		@SuppressWarnings("unchecked")
		ComboBox<Object> answerChoice = lookup(robot, "#exam-assets-question-answer-" + booklet.getId(),
				ComboBox.class);

		// Reloading the Exam after creation must also publish the new asset immediately
		// to Question-booklet assignment choices.
		assertTrue(answerChoice.getItems().stream().anyMatch(item -> "Marking Guide".equals(item.toString())));
	}

	@Test
	void examAssetsCanAddQuestionBookletAndEnterInspection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		Path questionSource = pdfDataRoot.resolve("paper2.pdf");
		Files.copy(examPdf, questionSource);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		ExamAssetsPane examAssetsPane = field(application, "examAssetsPane", ExamAssetsPane.class);

		// Bypass only the native FileChooser. The real pending-editor, persistence,
		// reload and inspection workflow remains under test.
		robot.interact(() -> {
			try {
				invoke(examAssetsPane, "beginQuestionBookletAdd", new Class<?>[] { Path.class }, questionSource);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		WaitForAsyncUtils.waitForFxEvents();
		PdfWorkspacePane.DocumentMode pendingDocument = field(pdfWorkspace(), "displayedDocument",
				PdfWorkspacePane.DocumentMode.class);

		// Inspection begins before Save so Expected Questions can be entered while the
		// selected source booklet is visible.
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pendingDocument);

		// Merely inspecting the proposed source must not activate it for capture.
		assertEquals(activeBooklet.getId(), examMetadataPane().getBooklet().getId());
		@SuppressWarnings("unchecked")
		ComboBox<String> name = lookup(robot, "#exam-assets-new-question-name", ComboBox.class);
		RadioButton written = lookup(robot, "#exam-assets-new-question-format-written", RadioButton.class);
		TextField expected = lookup(robot, "#exam-assets-new-question-expected", TextField.class);
		Button save = lookup(robot, "#exam-assets-new-question-save", Button.class);
		robot.interact(() -> {
			name.getEditor().setText("Paper 2");

			// Three-digit values are outside the agreed expected-count range.
			expected.setText("100");
		});
		fireControl(robot, written);
		assertTrue(save.isDisabled());
		robot.interact(() -> expected.setText("12"));
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		List<ExamBooklet> booklets = examWriter.findExamBooklets(activeBooklet.getExam());
		ExamBooklet created = booklets.stream().filter(booklet -> "Paper 2".equals(booklet.getName())).findFirst()
				.orElseThrow();
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, created.getQuestionFormat());
		assertEquals(Integer.valueOf(12), created.getExpectedQuestionCount());
		assertNotNull(created.getSourceDocument().getContentSha256());
		RadioButton selected = lookup(robot, "#exam-assets-question-select-" + created.getId(), RadioButton.class);
		assertTrue(selected.isSelected());

		// Inspection must not silently replace the authoritative capture booklet.
		assertEquals(activeBooklet.getId(), examMetadataPane().getBooklet().getId());
		PdfWorkspacePane.DocumentMode displayedDocument = field(pdfWorkspace(), "displayedDocument",
				PdfWorkspacePane.DocumentMode.class);

		// Newly persisted Question booklets enter read-only inspection immediately.
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, displayedDocument);
	}

	@Test
	void examAssetsCanCorrectAndReloadAssessment(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		@SuppressWarnings("unchecked")
		ComboBox<String> assessment = lookup(robot, "#exam-assets-assessment", ComboBox.class);
		Button edit = lookup(robot, "#exam-assets-edit", Button.class);
		Button save = lookup(robot, "#exam-assets-save", Button.class);

		// Existing metadata is protected and there is initially nothing to save.
		assertTrue(assessment.isDisabled());
		assertTrue(save.isDisabled());
		fireControl(robot, edit);

		// Entering Edit alone is not a data change.
		assertFalse(assessment.isDisabled());
		assertTrue(save.isDisabled());
		robot.interact(() -> assessment.getEditor().setText("Topic Test 1"));

		// Save becomes available only after the staged value differs from persistence.
		assertFalse(save.isDisabled());
		robot.interact(() -> assessment.getEditor().setText("External Assessment"));

		// Reverting the edit to the persisted value removes the dirty state.
		assertTrue(save.isDisabled());
		robot.interact(() -> assessment.getEditor().setText("Topic Test 1"));
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Topic Test 1", assessment.getValue());
		assertTrue(assessment.isDisabled());
		assertTrue(save.isDisabled());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject workingSubject = subjects.getValue();
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		Exam reloaded = writer.findExamByProviderAndYear(workingSubject, "QCAA", 2024);

		// A fresh repository read proves Save corrected persistence rather than merely
		// changing the visible editor.
		assertNotNull(reloaded);
		assertEquals("Topic Test 1", reloaded.getName());
	}

	@Test
	void examAssetsCanCreateNewExamWithoutDuplicateSubjectSelector(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		Exam existingExam = examMetadataPane().getBooklet().getExam();
		Node workspace = lookup(robot, "#exam-assets-workspace", Node.class);
		Node workingSubjectContext = lookup(robot, "#working-subject-context", Node.class);
		Button addNewExam = lookup(robot, "#exam-assets-add-new-exam", Button.class);
		@SuppressWarnings("unchecked")
		ComboBox<Exam> examSelector = lookup(robot, "#exam-assets-exam", ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<String> provider = lookup(robot, "#exam-assets-provider", ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Integer> year = lookup(robot, "#exam-assets-year", ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<String> assessment = lookup(robot, "#exam-assets-assessment", ComboBox.class);
		Button clear = lookup(robot, "#exam-assets-new-exam-clear", Button.class);
		Button save = lookup(robot, "#exam-assets-new-exam-save", Button.class);
		Button cancel = lookup(robot, "#exam-assets-new-exam-cancel", Button.class);
		HBox newExamActions = lookup(robot, "#exam-assets-new-exam-actions", HBox.class);
		VBox examSection = lookup(robot, "#exam-assets-exam-section", VBox.class);
		Button addQuestion = lookup(robot, "#exam-assets-add-question-booklet", Button.class);
		Button addAnswer = lookup(robot, "#exam-assets-add-answer-booklet", Button.class);

		// Working Subject remains permanent application context rather than becoming a
		// second selector owned by New Exam.
		assertFalse(isDescendantOf(workingSubjectContext, workspace));
		fireControl(robot, addNewExam);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(examSelector.isDisabled());
		assertFalse(provider.isDisabled());
		assertFalse(year.isDisabled());
		assertFalse(assessment.isDisabled());
		assertTrue(addQuestion.isDisabled());
		assertTrue(addAnswer.isDisabled());
		assertTrue(save.isDisabled());
		Label state = lookup(robot, "#exam-assets-state", Label.class);
		assertEquals("NEW EXAM", state.getText());

		// Clear, Cancel and Save Exam operate on the EXAM transaction and therefore
		// belong inside the bordered EXAM section rather than below all asset sections.
		assertTrue(isDescendantOf(newExamActions, examSection));
		assertEquals(newExamActions, clear.getParent());
		assertEquals(newExamActions, cancel.getParent());
		assertEquals(newExamActions, save.getParent());
		robot.interact(() -> {
			provider.getEditor().setText("QCAA");
			year.getSelectionModel().select(Integer.valueOf(2023));
			assessment.getEditor().setText("Temporary Exam");
		});
		assertFalse(save.isDisabled());

		// Clear remains inside the New Exam transaction and removes all staged identity
		// values without restoring the previous Exam.
		fireControl(robot, clear);
		assertTrue(provider.getEditor().getText().isBlank());
		assertNull(year.getValue());
		assertTrue(assessment.getEditor().getText().isBlank());
		assertTrue(save.isDisabled());
		robot.interact(() -> {
			provider.getEditor().setText("QCAA");
			year.getSelectionModel().select(Integer.valueOf(2023));
			assessment.getEditor().setText("Mock Exam");
		});
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		Exam selected = examSelector.getValue();
		assertNotNull(selected);
		assertNotEquals(existingExam.getId(), selected.getId());
		assertEquals(2023, selected.getYear());
		assertEquals("QCAA", selected.getProvider().getName());
		assertEquals("Mock Exam", selected.getName());
		assertEquals("State: ACTIVE", state.getText());

		// Once the Exam has a real persistent identity its ordinary asset workflows
		// become immediately available.
		assertFalse(addQuestion.isDisabled());
		assertFalse(addAnswer.isDisabled());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		Exam reloaded = writer.findExamByProviderAndYear(subjects.getValue(), "QCAA", 2023);

		// A fresh repository lookup proves Save Exam created the authoritative Exam
		// rather than only adding a selector item.
		assertNotNull(reloaded);
		assertEquals("Mock Exam", reloaded.getName());
	}

	@Test
	void examAssetsCanEditQuestionBookletMetadata(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet original = examMetadataPane().getBooklet();
		assertNotNull(original);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		@SuppressWarnings("unchecked")
		ComboBox<String> name = lookup(robot, "#exam-assets-question-name-" + original.getId(), ComboBox.class);
		RadioButton written = lookup(robot, "#exam-assets-question-format-written-" + original.getId(),
				RadioButton.class);
		RadioButton mcq = lookup(robot, "#exam-assets-question-format-mcq-" + original.getId(), RadioButton.class);
		RadioButton both = lookup(robot, "#exam-assets-question-format-both-" + original.getId(), RadioButton.class);
		HBox formatRow = lookup(robot, "#exam-assets-question-format-row-" + original.getId(), HBox.class);

		// MCQ, Written Response and Both are alternative values for one field and must
		// therefore occupy one compact horizontal row rather than three vertical rows.
		assertEquals(formatRow, mcq.getParent());
		assertEquals(formatRow, written.getParent());
		assertEquals(formatRow, both.getParent());
		assertEquals(3, formatRow.getChildren().size());
		TextField expected = lookup(robot, "#exam-assets-question-expected-" + original.getId(), TextField.class);
		Button edit = lookup(robot, "#exam-assets-question-edit-" + original.getId(), Button.class);
		Button save = lookup(robot, "#exam-assets-question-save-" + original.getId(), Button.class);

		// Persisted structural metadata starts protected and Save has no work to do.
		assertTrue(name.isDisabled());
		assertTrue(written.isDisabled());
		assertTrue(expected.isDisabled());
		assertTrue(save.isDisabled());
		fireControl(robot, edit);

		// Entering Edit alone must not create a dirty row.
		assertFalse(name.isDisabled());
		assertFalse(written.isDisabled());
		assertFalse(expected.isDisabled());
		assertTrue(save.isDisabled());
		robot.interact(() -> name.getEditor().setText("Paper 1"));
		fireControl(robot, written);
		robot.interact(() -> expected.setText("10"));
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter writer = new SqliteExamWriter(database);
		ExamBooklet reloaded = writer.findExamBooklets(original.getExam()).stream()
				.filter(booklet -> booklet.getId() == original.getId()).findFirst().orElseThrow();

		// A fresh persistence read proves that all three editable structural values
		// were committed under the original booklet identity and source document.
		assertEquals("Paper 1", reloaded.getName());
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, reloaded.getQuestionFormat());
		assertEquals(Integer.valueOf(10), reloaded.getExpectedQuestionCount());
		assertEquals(original.getSourceDocument().getId(), reloaded.getSourceDocument().getId());

		// Because this was the active booklet, Capture context must also hold the
		// authoritative replacement object rather than its stale pre-edit snapshot.
		assertEquals("Paper 1", examMetadataPane().getBooklet().getName());
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, examMetadataPane().getBooklet().getQuestionFormat());

		// Saving booklet metadata deliberately leaves the user in Exam/Assets. Verify
		// the rebuilt row shows the authoritative persisted values rather than looking
		// for Capture-only controls that are not currently in the scene graph.
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		@SuppressWarnings("unchecked")
		ComboBox<String> reloadedName = lookup(robot, "#exam-assets-question-name-" + original.getId(), ComboBox.class);
		RadioButton reloadedWritten = lookup(robot, "#exam-assets-question-format-written-" + original.getId(),
				RadioButton.class);
		TextField reloadedExpected = lookup(robot, "#exam-assets-question-expected-" + original.getId(),
				TextField.class);
		assertEquals("Paper 1", reloadedName.getValue());
		assertTrue(reloadedWritten.isSelected());
		assertEquals("10", reloadedExpected.getText());

		// The rebuilt row returns to protected view mode after its successful save.
		assertTrue(reloadedName.isDisabled());
		assertTrue(reloadedWritten.isDisabled());
		assertTrue(reloadedExpected.isDisabled());
	}

	@Test
	void examAssetsCanViewQuestionAndAnswerBookletsWithoutChangingCaptureBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		assertNotNull(activeBooklet);

		// Create one persisted AnswerFile so this test exercises both asset types in
		// the real Exam/Assets hierarchy.
		Question question = captureQuestion(robot, "Q1");
		openAnswerPdfForTest(question);

		// Answer persistence shares the same Exam writer because both operate on the
		// authoritative Exam/source-document hierarchy.
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findAnswerFiles(activeBooklet.getExam()).getFirst();
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		Node examAssets = lookup(robot, "#exam-assets-workspace", Node.class);
		Button questionView = lookup(robot, "#exam-assets-question-view-" + activeBooklet.getId(), Button.class);
		fireControl(robot, questionView);
		WaitForAsyncUtils.waitForFxEvents();

		// View uses the shared read-only PDF mode while leaving Exam/Assets visible.
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace().getDisplayedDocument());
		assertTrue(examAssets.getScene() != null);
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isPresent());

		// Inspection must not silently replace the active capture booklet.
		assertEquals(activeBooklet.getId(), examMetadataPane().getBooklet().getId());
		Button answerView = lookup(robot, "#exam-assets-answer-view-" + answerFile.getId(), Button.class);
		fireControl(robot, answerView);
		WaitForAsyncUtils.waitForFxEvents();

		// Answer assets use the same VIEWER mode rather than entering Answer capture.
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace().getDisplayedDocument());
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		assertEquals(activeBooklet.getId(), examMetadataPane().getBooklet().getId());
	}

	@Test
	void examAssetsPrimarySectionsOwnTheirHeadingsAndControls(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		VBox examSection = lookup(robot, "#exam-assets-exam-section", VBox.class);
		VBox questionSection = lookup(robot, "#exam-assets-question-section", VBox.class);
		VBox answerSection = lookup(robot, "#exam-assets-answer-section", VBox.class);
		Node examHeading = lookup(robot, "#exam-assets-exam-section-heading", Node.class);
		Node questionHeading = lookup(robot, "#exam-assets-question-section-heading", Node.class);
		Node answerHeading = lookup(robot, "#exam-assets-answer-section-heading", Node.class);
		HBox questionHeadingRow = lookup(robot, "#exam-assets-question-section-heading-row", HBox.class);
		HBox answerHeadingRow = lookup(robot, "#exam-assets-answer-section-heading-row", HBox.class);
		Node examSelector = lookup(robot, "#exam-assets-exam", Node.class);
		Node examDetails = lookup(robot, "#exam-assets-details-section", Node.class);
		Node questionBooklets = lookup(robot, "#exam-assets-question-booklets", Node.class);
		Button addQuestionBooklet = lookup(robot, "#exam-assets-add-question-booklet", Button.class);
		Node answerBooklets = lookup(robot, "#exam-assets-answer-booklets", Node.class);
		Button addAnswerBooklet = lookup(robot, "#exam-assets-add-answer-booklet", Button.class);

		// Each logical area owns its heading, contents and section-specific actions
		// inside the same bordered container.
		assertTrue(isDescendantOf(examHeading, examSection));
		assertTrue(isDescendantOf(examSelector, examSection));
		assertTrue(isDescendantOf(examDetails, examSection));
		assertTrue(isDescendantOf(questionHeading, questionSection));
		assertTrue(isDescendantOf(questionBooklets, questionSection));
		assertTrue(isDescendantOf(addQuestionBooklet, questionSection));
		assertTrue(isDescendantOf(answerHeading, answerSection));
		assertTrue(isDescendantOf(answerBooklets, answerSection));
		assertTrue(isDescendantOf(addAnswerBooklet, answerSection));

		// Section creation actions share the heading row rather than using a separate
		// action row below the persisted asset rows.
		assertEquals(questionHeadingRow, questionHeading.getParent());
		assertEquals(questionHeadingRow, addQuestionBooklet.getParent());
		assertEquals(answerHeadingRow, answerHeading.getParent());
		assertEquals(answerHeadingRow, addAnswerBooklet.getParent());

		// The primary section itself owns the visible border; individual persisted
		// asset rows may retain their own more local borders.
		assertTrue(examSection.getStyle().contains("-fx-border-width: 1"));
		assertTrue(questionSection.getStyle().contains("-fx-border-width: 1"));
		assertTrue(answerSection.getStyle().contains("-fx-border-width: 1"));
	}

	@Test
	void examDetailsOwnEditCancelAndSaveActions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		Node detailsSection = lookup(robot, "#exam-assets-details-section", Node.class);
		Node actionRow = lookup(robot, "#exam-assets-details-actions", Node.class);
		Button edit = lookup(robot, "#exam-assets-edit", Button.class);
		Button cancel = lookup(robot, "#exam-assets-cancel", Button.class);
		Button save = lookup(robot, "#exam-assets-save", Button.class);

		// All three controls operate on the same Exam Details edit transaction and
		// therefore belong to the same action row inside that section.
		assertEquals(actionRow, edit.getParent());
		assertEquals(actionRow, cancel.getParent());
		assertEquals(actionRow, save.getParent());
		assertTrue(isDescendantOf(actionRow, detailsSection));

		// The persisted Exam starts in view mode: Edit begins a transaction while
		// Cancel and Save have nothing to act on yet.
		assertFalse(edit.isDisabled());
		assertTrue(cancel.isDisabled());
		assertTrue(save.isDisabled());
		fireControl(robot, edit);

		// Entering Edit makes cancellation available, but Save remains unavailable
		// until one of the persisted metadata values actually changes.
		assertTrue(edit.isDisabled());
		assertFalse(cancel.isDisabled());
		assertTrue(save.isDisabled());
		fireControl(robot, cancel);

		// Cancelling restores the normal Exam Details state without leaving the
		// Exam/Assets workspace.
		assertFalse(edit.isDisabled());
		assertTrue(cancel.isDisabled());
		assertTrue(save.isDisabled());
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
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
	void oneAnswerBookletCanBeAssignedToSeveralQuestionBooklets(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet firstBooklet = examMetadataPane().getBooklet();
		assertNotNull(firstBooklet);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);

		// Create another managed Question PDF for a second booklet belonging to the
		// same Exam.
		Path firstQuestionPdf = pdfDataRoot.resolve(firstBooklet.getSourceDocument().getRelativePath());
		Path secondQuestionPdf = firstQuestionPdf.resolveSibling("paper-2-answer-assignment-test.pdf");
		Files.copy(firstQuestionPdf, secondQuestionPdf);
		ExamBooklet secondBooklet = examImporter.importExam(firstBooklet.getExam().getSubject(),
				firstBooklet.getExam().getProvider().getName(), firstBooklet.getExam().getYear(),
				firstBooklet.getExam().getName(), "Paper 2", pdfDataRoot.relativize(secondQuestionPdf).toString(),
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);

		// Register one independent Answer asset without assigning it to either
		// Question booklet.
		Path answerPdf = firstQuestionPdf.resolveSibling("marking-guide-assignment-test.pdf");
		Files.copy(firstQuestionPdf, answerPdf);
		AnswerFile markingGuide = answerWriter.findOrCreateAnswerFile(firstBooklet.getExam(), "Marking Guide",
				pdfDataRoot.relativize(answerPdf).toString());
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		@SuppressWarnings("unchecked")
		ComboBox<Object> firstAnswer = lookup(robot, "#exam-assets-question-answer-" + firstBooklet.getId(),
				ComboBox.class);
		Button firstEdit = lookup(robot, "#exam-assets-question-edit-" + firstBooklet.getId(), Button.class);
		Button firstSave = lookup(robot, "#exam-assets-question-save-" + firstBooklet.getId(), Button.class);

		// No Answer Booklet must be the first deliberate choice.
		assertEquals("No Answer Booklet", firstAnswer.getItems().getFirst().toString());
		fireControl(robot, firstEdit);

		// The only registered Answer asset follows No Answer Booklet.
		robot.interact(() -> firstAnswer.getSelectionModel().select(1));
		assertEquals("Marking Guide", firstAnswer.getValue().toString());
		assertFalse(firstSave.isDisabled());
		fireControl(robot, firstSave);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(markingGuide.getId(), answerWriter.findAnswerFile(firstBooklet).getId());

		// Saving the first row rebuilds the workspace, so look up the second row from
		// the new scene graph before assigning the same AnswerFile.
		@SuppressWarnings("unchecked")
		ComboBox<Object> secondAnswer = lookup(robot, "#exam-assets-question-answer-" + secondBooklet.getId(),
				ComboBox.class);
		Button secondEdit = lookup(robot, "#exam-assets-question-edit-" + secondBooklet.getId(), Button.class);
		Button secondSave = lookup(robot, "#exam-assets-question-save-" + secondBooklet.getId(), Button.class);
		fireControl(robot, secondEdit);
		robot.interact(() -> secondAnswer.getSelectionModel().select(1));
		fireControl(robot, secondSave);
		WaitForAsyncUtils.waitForFxEvents();

		// The relationship is many-booklets-to-one-AnswerFile rather than exclusive
		// ownership by one Question booklet.
		assertEquals(markingGuide.getId(), answerWriter.findAnswerFile(firstBooklet).getId());
		assertEquals(markingGuide.getId(), answerWriter.findAnswerFile(secondBooklet).getId());

		// The assignment can also be removed explicitly while no persisted Answer
		// regions depend on it.
		@SuppressWarnings("unchecked")
		ComboBox<Object> reloadedFirstAnswer = lookup(robot, "#exam-assets-question-answer-" + firstBooklet.getId(),
				ComboBox.class);
		Button reloadedFirstEdit = lookup(robot, "#exam-assets-question-edit-" + firstBooklet.getId(), Button.class);
		Button reloadedFirstSave = lookup(robot, "#exam-assets-question-save-" + firstBooklet.getId(), Button.class);
		fireControl(robot, reloadedFirstEdit);
		robot.interact(() -> reloadedFirstAnswer.getSelectionModel().select(0));
		assertEquals("No Answer Booklet", reloadedFirstAnswer.getValue().toString());
		fireControl(robot, reloadedFirstSave);
		WaitForAsyncUtils.waitForFxEvents();
		assertNull(answerWriter.findAnswerFile(firstBooklet));

		// Removing it from one booklet must not disturb another booklet that shares
		// the same AnswerFile.
		assertEquals(markingGuide.getId(), answerWriter.findAnswerFile(secondBooklet).getId());
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

	@Test
	void selectedExamAssetsBookletCanBecomeActiveCaptureBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);

		// Add a second persisted booklet to the same Exam so the test proves that row
		// selection, rather than the previously active booklet, drives the transition.
		Path firstStoredPdf = pdfDataRoot.resolve(originalBooklet.getSourceDocument().getRelativePath());
		Path secondStoredPdf = firstStoredPdf.resolveSibling("paper-2-test.pdf");
		Files.copy(firstStoredPdf, secondStoredPdf);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet secondBooklet = examImporter.importExam(originalBooklet.getExam().getSubject(),
				originalBooklet.getExam().getProvider().getName(), originalBooklet.getExam().getYear(),
				originalBooklet.getExam().getName(), "Paper 2", pdfDataRoot.relativize(secondStoredPdf).toString(),
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		RadioButton secondSelection = lookup(robot, "#exam-assets-question-select-" + secondBooklet.getId(),
				RadioButton.class);
		Button useSelected = lookup(robot, "#exam-assets-use-selected-booklet", Button.class);

		// The existing active booklet is preselected, but the user can explicitly
		// choose
		// another persisted Question booklet.
		assertFalse(useSelected.isDisabled());
		fireControl(robot, secondSelection);
		assertTrue(secondSelection.isSelected());

		// Inspecting the selected booklet must remain separate from activating it.
		fireControl(robot, "#exam-assets-question-view-" + secondBooklet.getId());
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace().getDisplayedDocument());
		assertEquals(originalBooklet.getId(), examMetadataPane().getBooklet().getId());
		fireControl(robot, useSelected);
		WaitForAsyncUtils.waitForFxEvents();

		// Successful activation closes VIEWER mode, opens the selected Question source
		// for capture and returns the existing live Capture workspace to the mode host.
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
		assertEquals(secondBooklet.getId(), examMetadataPane().getBooklet().getId());
		assertTrue(robot.lookup("#capture-workspace-mode").tryQuery().isPresent());
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isEmpty());
		Label activeExam = lookup(robot, "#active-exam-booklet", Label.class);
		assertEquals("QCAA 2024 External Assessment — Paper 2 [ACTIVE]", activeExam.getText());
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
