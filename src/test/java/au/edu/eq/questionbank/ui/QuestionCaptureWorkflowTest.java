package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import au.edu.eq.questionbank.ui.exam.ExamMetadataPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class QuestionCaptureWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void acceptedQuestionRegionBlocksWorkingSubjectChange(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject chemistry = workingSubjectBox.getValue();
		Subject physics = workingSubjectBox.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		CurriculumNode originalClassification = field(application, "curriculumSelectionModel",
				CurriculumSelectionModel.class).getClassification();
		TextField curriculumCode = lookup(robot, "#curriculum-code", TextField.class);
		String originalCode = curriculumCode.getText();

		// Accept a Question region. The PDF rectangle is no longer pending, but the
		// accepted region is still unsaved Question-capture work.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertTrue(questionCapturePane().hasAcceptedRegions());
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertFalse(selectionState.hasPendingSelection());

		// Attempting to leave Chemistry must be rejected because the accepted region
		// still depends on the current Exam and curriculum context.
		Platform.runLater(() -> workingSubjectBox.setValue(physics));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("Capture work is in progress").tryQuery().isPresent());
		fireDialogButton(robot, "OK");
		WaitForAsyncUtils.waitForFxEvents();

		// Rejection must preserve both the accepted region and the complete
		// classification context that will be used when the Question is saved.
		assertEquals(chemistry, workingSubjectBox.getValue());
		assertTrue(questionCapturePane().hasAcceptedRegions());
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertEquals(originalCode, curriculumCode.getText());
		assertEquals(originalClassification,
				field(application, "curriculumSelectionModel", CurriculumSelectionModel.class).getClassification());
		assertClassificationControlShows(robot, originalClassification);
	}

	@Test
	void acceptedQuestionRegionDoesNotLockNewQuestionNumber(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(marks).write("2");
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertFalse(questionCode.isDisable(), "Accepted regions must not lock the question number for a new question");
		robot.clickOn(questionCode).write("27");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		fireControl(robot, "#save-question");

		// Persisted Question appearance proves that the save completed.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findAll().stream().anyMatch(question -> "27".equals(question.getQuestionCode())));
		WaitForAsyncUtils.waitForFxEvents();
		Question restored = repository.findAll().stream().filter(question -> "27".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		assertEquals(1, restored.getRegions().size());
	}

	@Test
	void activatingAnotherBookletClearsPreviousQuestionCaptureState(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		Label regionCount = lookup(robot, "#question-region-count", Label.class);
		Label saveStatus = lookup(robot, "#question-save-status", Label.class);
		Button save = lookup(robot, "#save-question", Button.class);
		robot.clickOn(questionCode).write("31");
		robot.clickOn(marks).write("2");

		// Accept one region so the Question pane contains unsaved capture belonging to
		// the currently active booklet.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Content parts: 1", regionCount.getText());
		assertTrue(questionCapturePane().hasAcceptedRegions());

		// Leave a second rectangle unaccepted as well. This proves that booklet
		// activation clears both accepted Question state and the single pending PDF
		// selection.
		dragRegionOnDisplayedPage(robot);
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));
		assertFalse(saveStatus.getText().isBlank());
		ExamBooklet firstBooklet = examMetadataPane().getBooklet();

		// A distinct booklet identity is sufficient because this test exercises the
		// successful activation boundary rather than booklet persistence.
		ExamBooklet secondBooklet = new ExamBooklet(firstBooklet.getId() + 1000, firstBooklet.getExam(), "Paper 2",
				firstBooklet.getSourceDocument(), firstBooklet.getQuestionFormat());
		robot.interact(() -> examMetadataPane().activateExistingBooklet(secondBooklet, examPdf));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(secondBooklet.getId(), examMetadataPane().getBooklet().getId());
		assertEquals("", questionCode.getText());
		assertEquals("", marks.getText());
		assertEquals("Content parts: 0", regionCount.getText());
		assertFalse(questionCapturePane().hasAcceptedRegions());
		assertFalse(selectionState.hasPendingSelection());
		assertEquals("", saveStatus.getText());
		assertTrue(save.isDisabled());
	}

	@Test
	void addFromClipboardRequiresClipboardImage(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Button addFromClipboard = lookup(robot, "#paste-question-image", Button.class);
		robot.interact(() -> {
			ClipboardContent textContent = new ClipboardContent();
			textContent.putString("Clipboard text is not Question image content");
			Clipboard.getSystemClipboard().setContent(textContent);

			// Clipboard availability is refreshed explicitly here because this test does
			// not leave and return to the application window.
			questionCapturePane().refreshClipboardImageAvailability();
		});
		assertTrue(addFromClipboard.isDisabled());
		robot.interact(() -> {
			WritableImage image = new WritableImage(40, 20);
			ClipboardContent imageContent = new ClipboardContent();
			imageContent.putImage(image);
			Clipboard.getSystemClipboard().setContent(imageContent);

			// A readable JavaFX image is the only clipboard format that enables the
			// Question-image action.
			questionCapturePane().refreshClipboardImageAvailability();
		});
		assertFalse(addFromClipboard.isDisabled());
		robot.interact(() -> {
			ClipboardContent textContent = new ClipboardContent();
			textContent.putString("Restore a non-image clipboard state");
			Clipboard.getSystemClipboard().setContent(textContent);

			// Leave the shared test clipboard in a deterministic non-image state.
			questionCapturePane().refreshClipboardImageAvailability();
		});
	}

	@Test
	void captureEntryPointsUseTaskBasedLabels(FxRobot robot) throws Exception {
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);

		// Question capture entry points belong to the visible Question pane rather
		// than duplicating navigation in the application menu.
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "capture-new-questions".equals(item.getId())));
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "capture-imported-questions".equals(item.getId())));
		ToggleButton newMode = lookup(robot, "#capture-mode-new", ToggleButton.class);
		ToggleButton importedMode = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertEquals("Start New Question Capture", newMode.getText());
		assertEquals("Complete Imported Question", importedMode.getText());

		// Ordinary capture begins idle.
		assertFalse(newMode.isSelected());
		assertFalse(importedMode.isSelected());

		// Imported capture is operational work and therefore does not clutter the
		// workspace when its filtered queue is empty.
		assertFalse(importedMode.isVisible());
		assertFalse(importedMode.isManaged());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
		fireControl(robot, newMode);
		assertTrue(newMode.isSelected());
		assertFalse(importedMode.isSelected());
		MenuItem examAssetsItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "open-exam-for-capture".equals(item.getId())).findFirst().orElseThrow();

		// Exam management now enters the main-window workspace directly. The former
		// modal Exam Setup / Add Exam wording is no longer part of the normal workflow.
		assertEquals("_Exam / Assets...", examAssetsItem.getText());
	}

	@Test
	void committedQuestionIsNotReportedAsUnsavedWhenRefreshFails(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.clickOn(lookup(robot, "#question-code", TextField.class)).write("29");
		robot.clickOn(lookup(robot, "#question-marks", TextField.class)).write("1");
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AtomicInteger reads = new AtomicInteger();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				assertFalse(Platform.isFxApplicationThread());
				if (reads.incrementAndGet() == 2) {
					throw new IllegalStateException("Simulated refresh failure after commit");
				}
				return stored.findAll();
			}
		});
		fireControl(robot, "#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Question saved; lists could not be fully refreshed.").tryQuery().isPresent());
		assertEquals(1, stored.findAll().size());
		fireDialogButton(robot, "OK");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		assertEquals("", lookup(robot, "#question-code", TextField.class).getText());
		assertTrue(lookup(robot, "#question-save-status", Label.class).getText().contains("list refresh failed"));
		assertEquals(1, unansweredQuestions(robot).getItems().size());
	}

	@Test
	void completingLastImportedQuestionHidesImportedWorkflow(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question incomplete = repository.save(booklet, "42", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node importedControls = lookup(robot, "#legacy-question-capture", Node.class);
		fireControl(robot, importedAction);
		ComboBox<Question> importedQuestions = comboBox(robot, "#imported-question");

		// Direct selection is appropriate because this regression concerns workflow
		// availability rather than ComboBox pointer behaviour.
		robot.interact(() -> importedQuestions.getSelectionModel().select(incomplete));
		WaitForAsyncUtils.waitForFxEvents();
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		fireControl(robot, "#save-question");

		// Completion of the only queued Question is the observable workflow result.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !importedAction.isVisible());
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(importedQuestions.getItems().isEmpty());
		assertFalse(importedAction.isVisible());
		assertFalse(importedAction.isManaged());
		assertFalse(importedControls.isVisible());
		assertFalse(importedControls.isManaged());

		// Exhausting imported work returns to explicit idle rather than silently
		// starting ordinary new-Question capture.
		ToggleButton newQuestionAction = lookup(robot, "#capture-mode-new", ToggleButton.class);
		assertFalse(newQuestionAction.isSelected());
		assertFalse(pane.canCaptureRegions());
	}

	@Test
	void curriculumSubjectBecomesWorkingSubjectForBothCaptureQueues(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject workingSubject = workingSubjectBox.getValue();
		QuestionCapturePane questionPane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AnswerCapturePane answerPane = field(application, "answerCapturePane", AnswerCapturePane.class);

		// One workspace Subject must drive both capture queues so Question capture and
		// Answer capture cannot silently operate against different Subjects.
		assertEquals(workingSubject, field(questionPane, "workingSubject", Subject.class));
		assertEquals(workingSubject, field(answerPane, "workingSubject", Subject.class));
	}

	@Test
	void duplicateQuestionCodeIsRejectedWithoutLosingAcceptedRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		captureQuestion(robot, "Q7");

		// Prepare another otherwise valid Question and accept a region before changing
		// its code to a duplicate. This proves duplicate detection does not discard
		// work
		// already completed for the current capture.
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		TextField questionCodeField = lookup(robot, "#question-code", TextField.class);
		TextField marksField = lookup(robot, "#question-marks", TextField.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		Label duplicateStatus = lookup(robot, "#question-code-status", Label.class);
		Button save = lookup(robot, "#save-question", Button.class);
		robot.clickOn(questionCodeField).write("Q8");
		robot.clickOn(marksField).write("1");
		fireControl(robot, writtenResponse);
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(save.isDisabled());

		// Changing only the Question number to an already-persisted code now produces
		// immediate non-modal feedback. Save is disabled before any persistence
		// attempt, so no warning dialog is expected.
		robot.interact(() -> questionCodeField.setText("Q7"));
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(duplicateStatus.isVisible());
		assertTrue(duplicateStatus.isManaged());
		assertEquals("Question Q7 has already been captured in this booklet.", duplicateStatus.getText());
		assertTrue(save.isDisabled());

		// Duplicate detection must not discard the accepted region or other metadata.
		assertEquals("Q7", questionCodeField.getText());
		assertEquals("1", marksField.getText());
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		long matchingQuestions = repository.findAll().stream()
				.filter(question -> question.getQuestionCode().equals("Q7")).count();
		assertEquals(1, matchingQuestions);

		// Correcting the number removes the warning and makes the intact capture
		// saveable again.
		robot.interact(() -> questionCodeField.setText("Q8"));
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(duplicateStatus.isVisible());
		assertFalse(duplicateStatus.isManaged());
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(save.isDisabled());
	}

	@Test
	void duplicateQuestionCodeIsReportedBeforeClassificationOrCapture(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		captureQuestion(robot, "29");
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Label duplicateStatus = lookup(robot, "#question-code-status", Label.class);
		Button save = lookup(robot, "#save-question", Button.class);

		// The previous save resets classification below Subject. Typing the duplicate
		// code must therefore detect the problem before the teacher repeats any
		// classification or region work.
		robot.clickOn(questionCode).write("29");
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(duplicateStatus.isVisible());
		assertTrue(duplicateStatus.isManaged());
		assertEquals("Question 29 has already been captured in this booklet.", duplicateStatus.getText());
		assertTrue(save.isDisabled());

		// Correcting the Question number removes the warning immediately.
		robot.interact(() -> questionCode.setText("30"));
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(duplicateStatus.isVisible());
		assertFalse(duplicateStatus.isManaged());
	}

	@Test
	void examMenuRoutesDirectlyToExamAssetsWorkspace(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		MenuBar menuBar = lookup(robot, ".menu-bar", MenuBar.class);
		MenuItem examAssetsItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "open-exam-for-capture".equals(item.getId())).findFirst().orElseThrow();
		assertEquals("_Exam / Assets...", examAssetsItem.getText());

		// Menu activation must switch the existing main-window host rather than opening
		// the superseded modal Exam Setup workflow.
		robot.interact(examAssetsItem::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		assertTrue(robot.lookup("#workspace-mode-host").tryQuery().isPresent());
	}

	@Test
	void finishingQuestionEditReturnsCaptureWorkspaceToIdle(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "32");
		QuestionCapturePane pane = questionCapturePane();
		boolean[] editStarted = { false };
		robot.interact(() -> editStarted[0] = pane.editQuestion(question, () -> {
		}));
		assertTrue(editStarted[0]);
		Node classification = lookup(robot, "#classification-context", Node.class);
		Node questionWork = lookup(robot, "#question-capture-work", Node.class);
		ToggleButton newMode = lookup(robot, "#capture-mode-new", ToggleButton.class);
		Button cancel = lookup(robot, "#cancel-question-edit", Button.class);
		assertFalse(classification.isDisabled());
		assertFalse(questionWork.isDisabled());
		assertTrue(cancel.isVisible());
		fireControl(robot, cancel);

		// Ending the edit does not silently begin a new Question.
		assertFalse(newMode.isSelected());
		assertTrue(classification.isDisabled());
		assertTrue(questionWork.isDisabled());
		assertFalse(pane.canCaptureRegions());
	}

	@Test
	void importedCaptureAdvancesUsingTheBackgroundSnapshot(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = field(application, "examMetadataPane", ExamMetadataPane.class).getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question first = stored.save(booklet, "41", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question second = stored.save(booklet, "42", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);

		// Enter imported-Question capture through the production pane workflow.
		robot.interact(() -> showImportedQuestionCaptureForTest(pane));
		ComboBox<Question> imported = comboBox(robot, "#imported-question");

		// Select the first imported Question directly because pointer behaviour is not
		// part of what this workflow test is verifying.
		robot.interact(() -> imported.getSelectionModel().selectFirst());
		dragRegionOnDisplayedPage(robot);

		// Accept the selected PDF region through the real JavaFX control action.
		fireControl(robot, "#add-question-region");
		AtomicInteger reads = new AtomicInteger();
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {

				// Post-save queue refresh must remain off the JavaFX thread and reuse the
				// loaded snapshot rather than performing an extra bank reload.
				assertFalse(Platform.isFxApplicationThread(), "Advancing the queue must reuse the loaded questions");
				reads.incrementAndGet();
				return stored.findAll();
			}
		});

		// Saving should advance imported capture directly to Question 42. No dialog is
		// part of the successful workflow.
		fireControl(robot, "#save-question");

		// Wait for the observable queue transition rather than merely waiting for the
		// transient save-in-progress flag to become false.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> imported.getValue() != null && imported.getValue().getId() == second.getId());
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(second.getId(), imported.getValue().getId());
		assertEquals(1, imported.getItems().size());
		assertEquals(1, stored.findById(first.getId()).orElseThrow().getRegions().size());
		assertEquals(2, reads.get());
		assertEquals(2, unansweredQuestions(robot).getItems().size());
	}

	@Test
	void importedCaptureAppearsOnlyWhenCurrentContextHasWork(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node importedControls = lookup(robot, "#legacy-question-capture", Node.class);

		// A normal workspace with no incomplete imported Questions does not expose
		// imported capture at all.
		assertFalse(importedAction.isVisible());
		assertFalse(importedAction.isManaged());
		assertFalse(importedControls.isVisible());
		assertFalse(importedControls.isManaged());
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Persist metadata without Question content. The existing queue rules define
		// this as relevant imported/incomplete work.
		Question incomplete = repository.save(booklet, "41", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		assertTrue(importedAction.isVisible());
		assertTrue(importedAction.isManaged());
		assertEquals("Complete Imported Question", importedAction.getText());

		// The selector is still kept out of the ordinary new-Question workspace until
		// the imported workflow itself is chosen.
		assertFalse(importedControls.isVisible());
		fireControl(robot, importedAction);
		ComboBox<Question> importedQuestions = comboBox(robot, "#imported-question");
		assertTrue(importedControls.isVisible());
		assertTrue(importedControls.isManaged());
		assertEquals(1, importedQuestions.getItems().size());
		assertEquals(incomplete.getId(), importedQuestions.getItems().getFirst().getId());
	}

	@Test
	void importedCaptureAvailabilityTracksWorkingSubject(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject chemistry = workingSubjectBox.getValue();
		Subject physics = workingSubjectBox.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		ExamBooklet chemistryBooklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Create outstanding work only for Chemistry. The imported-work action must
		// therefore follow Working Subject rather than remaining globally visible.
		repository.save(chemistryBooklet, "43", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		robot.interact(pane::refreshImportedQuestions);
		assertTrue(importedAction.isVisible());

		// Changing to a Subject with no outstanding imported/incomplete work removes
		// the operational entry point immediately.
		robot.interact(() -> workingSubjectBox.setValue(physics));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(physics, workingSubjectBox.getValue());
		assertFalse(importedAction.isVisible());
		assertFalse(importedAction.isManaged());

		// Returning to Chemistry restores the action because the persisted incomplete
		// Question was filtered out, not deleted or otherwise modified.
		robot.interact(() -> workingSubjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(chemistry, workingSubjectBox.getValue());
		assertTrue(importedAction.isVisible());
		assertTrue(importedAction.isManaged());
	}

	@Test
	void legacyQuestionControlsAreHiddenDuringNormalCapture(FxRobot robot) {
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
	}

	@Test
	void mouseSelectingImportedQuestionMayActivateSubjectWithoutRebuildingOpenPopup(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question importedQuestion = stored.save(booklet, "41", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubject = lookup(robot, "#curriculum-subject", ComboBox.class);

		// Remove the active Working Subject so selecting the imported Question must
		// reactivate its booklet Subject, matching the live imported-capture workflow.
		robot.interact(() -> workingSubject.setValue(null));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(null, examMetadataPane().getBooklet());
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(() -> showImportedQuestionCaptureForTest(pane));
		ComboBox<Question> imported = comboBox(robot, "#imported-question");
		assertEquals(1, imported.getItems().size());

		// This test deliberately uses the real popup mouse path because the regression
		// occurs while JavaFX's internal ListView is completing a pointer selection.
		robot.clickOn(imported);
		WaitForAsyncUtils.waitForFxEvents();
		Node importedCell = robot.lookup(".list-cell").match(node -> {
			if (!(node instanceof ListCell<?> cell) || !node.isVisible()) {
				return false;
			}
			Object item = cell.getItem();
			return item instanceof Question question && question.getId() == importedQuestion.getId();
		}).query();
		robot.clickOn(importedCell);

		// Processing all queued FX work also surfaces any exception raised by the
		// ComboBox/ListView selection transition.
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(importedQuestion.getId(), imported.getValue().getId());
		assertEquals(booklet.getExam().getSubject(), workingSubject.getValue());
		assertEquals(booklet.getId(), examMetadataPane().getBooklet().getId());
	}

	@Test
	void newQuestionCaptureRequiresExplicitStart(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		QuestionCapturePane pane = questionCapturePane();

		// Re-enter the state produced by activating a booklet before the teacher
		// explicitly starts a Question.
		robot.interact(pane::refreshForActiveBooklet);
		ToggleButton startCapture = lookup(robot, "#capture-mode-new", ToggleButton.class);
		Node workingSubject = lookup(robot, "#working-subject-context", Node.class);
		Node classification = lookup(robot, "#classification-context", Node.class);
		Node questionWork = lookup(robot, "#question-capture-work", Node.class);
		Button addRegion = lookup(robot, "#add-question-region", Button.class);
		assertEquals("Start New Question Capture", startCapture.getText());
		assertFalse(startCapture.isSelected());

		// Working Subject is application context and remains available.
		assertFalse(workingSubject.isDisabled());
		assertTrue(classification.isDisabled());
		assertTrue(questionWork.isDisabled());
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);

		// Merely drawing on the Exam PDF must not silently begin Question capture.
		dragRegionOnDisplayedPage(robot);
		assertFalse(selectionState.hasPendingSelection());
		assertTrue(addRegion.isDisabled());
		fireControl(robot, startCapture);
		assertTrue(startCapture.isSelected());
		assertFalse(classification.isDisabled());
		assertFalse(questionWork.isDisabled());

		// Once capture is explicitly active, the Exam PDF again accepts Question
		// selections.
		dragRegionOnDisplayedPage(robot);
		assertTrue(selectionState.hasPendingSelection());
		assertFalse(addRegion.isDisabled());
	}

	@Test
	void pastedImageCanBeOrderedWithPdfRegionAndSavedAsMixedQuestion(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(questionCode).write("Q40");
		robot.clickOn(marks).write("2");

		// Start with a PDF part.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());

		// Place a deterministic image on the real JavaFX clipboard, matching the path
		// used by Snipping Tool.
		robot.interact(() -> {
			WritableImage image = new WritableImage(40, 20);
			for (int y = 0; y < 20; y++) {
				for (int x = 0; x < 40; x++) {
					image.getPixelWriter().setColor(x, y, Color.ORANGE);
				}
			}
			ClipboardContent clipboardContent = new ClipboardContent();
			clipboardContent.putImage(image);
			Clipboard.getSystemClipboard().setContent(clipboardContent);

			// This test remains inside one application window, so explicitly perform the
			// same availability refresh that window-focus restoration performs in use.
			questionCapturePane().refreshClipboardImageAvailability();
		});
		Button pasteImageButton = lookup(robot, "#paste-question-image", Button.class);
		assertFalse(pasteImageButton.isDisabled());
		assertEquals("Add clipboard image content to the Question; it is persisted when the Question is saved.",
				pasteImageButton.getTooltip().getText());
		fireControl(robot, "#paste-question-image");
		assertEquals("Content parts: 2", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(robot.lookup("#question-content-part-0").tryQuery().isPresent());
		assertTrue(robot.lookup("#question-content-part-1").tryQuery().isPresent());

		// The image was appended after the PDF region. Move it ahead of the PDF part.
		fireControl(robot, "#question-content-move-up-1");
		WaitForAsyncUtils.waitForFxEvents();

		// Prove removal operates on the mixed assembly rather than only PDF regions.
		fireControl(robot, "#question-content-remove-1");
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());

		// Add a fresh PDF part after the retained image. The final assembly is
		// therefore
		// IMAGE -> PDF_REGION.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Content parts: 2", lookup(robot, "#question-region-count", Label.class).getText());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		fireControl(robot, "#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findAll().stream().anyMatch(question -> "Q40".equals(question.getQuestionCode())));
		Question reloaded = repository.findAll().stream().filter(question -> "Q40".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		assertEquals(2, reloaded.getContentParts().size());
		assertTrue(reloaded.getContentParts().get(0) instanceof ImageQuestionContentPart);
		assertTrue(reloaded.getContentParts().get(1) instanceof PdfQuestionContentPart);
		assertEquals(1, reloaded.getRegions().size());
	}

	@Test
	void pendingQuestionSelectionBlocksWorkingSubjectChange(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject chemistry = workingSubjectBox.getValue();
		Subject physics = workingSubjectBox.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		TextField curriculumCode = lookup(robot, "#curriculum-code", TextField.class);
		String originalCode = curriculumCode.getText();
		CurriculumNode originalClassification = field(application, "curriculumSelectionModel",
				CurriculumSelectionModel.class).getClassification();

		// Create an unaccepted PDF selection owned by Question capture.
		dragRegionOnDisplayedPage(robot);
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));

		// Schedule the Subject change without blocking the test thread because the
		// rejection deliberately opens a modal warning dialog.
		Platform.runLater(() -> workingSubjectBox.setValue(physics));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("Capture work is in progress").tryQuery().isPresent());
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
		WaitForAsyncUtils.waitForFxEvents();

		// The rejected transition must preserve the complete workspace context, not
		// merely restore the Subject name.
		assertEquals(chemistry, workingSubjectBox.getValue());
		assertEquals(chemistry, field(questionCapturePane(), "workingSubject", Subject.class));
		assertEquals(chemistry, field(answerCapturePane(), "workingSubject", Subject.class));
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));
		assertEquals(originalCode, curriculumCode.getText());
		assertEquals(originalClassification,
				field(application, "curriculumSelectionModel", CurriculumSelectionModel.class).getClassification());
		assertClassificationControlShows(robot, originalClassification);
	}

	@Test
	void questionSaveKeepsFxThreadResponsiveDuringValidationAndRefresh(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.clickOn(lookup(robot, "#question-code", TextField.class)).write("24a");
		robot.clickOn(lookup(robot, "#question-marks", TextField.class)).write("2");
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		CountDownLatch[] entered = { new CountDownLatch(1), new CountDownLatch(1) };
		CountDownLatch[] release = { new CountDownLatch(1), new CountDownLatch(1) };
		AtomicInteger reads = new AtomicInteger();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				assertFalse(Platform.isFxApplicationThread(), "Question-bank reads must not block the FX thread");
				int index = reads.getAndIncrement();
				assertTrue(index < 2, "The two queues must share one post-save snapshot");
				entered[index].countDown();
				try {
					assertTrue(release[index].await(10, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return stored.findAll();
			}
		});
		try {
			fireControl(robot, "#save-question");
			for (int index = 0; index < 2; index++) {
				assertTrue(entered[index].await(5, TimeUnit.SECONDS));
				CountDownLatch pulse = new CountDownLatch(1);
				Platform.runLater(pulse::countDown);
				assertTrue(pulse.await(2, TimeUnit.SECONDS), "FX events must run while repository I/O waits");
				if (index == 0) {
					fireControl(robot, "#next-pdf-page");
					PdfWorkspacePane workspace = field(application, "pdfWorkspace", PdfWorkspacePane.class);
					assertEquals(2, field(workspace, "currentPageNumber", Integer.class));
				}
				release[index].countDown();
			}
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
			WaitForAsyncUtils.waitForFxEvents();
			Question saved = stored.findAll().getFirst();
			assertEquals("24a", saved.getQuestionCode());
			assertEquals(2, saved.getRegions().size());
			assertEquals(1, saved.getRegions().getFirst().pageNumber());
			assertEquals(1, unansweredQuestions(robot).getItems().size());
			assertEquals(2, reads.get());
		} finally {
			for (CountDownLatch gate : release) {
				gate.countDown();
			}
		}
	}

	@Test
	void saveQuestionEnablesOnlyWhenCaptureIsComplete(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Button save = lookup(robot, "#save-question", Button.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		assertTrue(save.isDisabled());
		robot.clickOn(questionCode).write("27");
		assertTrue(save.isDisabled());
		robot.clickOn(marks).write("2");
		assertTrue(save.isDisabled());
		dragRegionOnDisplayedPage(robot);

		// A rectangle exists, but it has not yet been accepted.
		assertTrue(save.isDisabled());
		fireControl(robot, "#add-question-region");
		assertFalse(save.isDisabled());
	}

	@Test
	void savingNewQuestionKeepsNewCaptureActiveForNextQuestion(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		QuestionCapturePane pane = questionCapturePane();
		ToggleButton newMode = lookup(robot, "#capture-mode-new", ToggleButton.class);
		Node classification = lookup(robot, "#classification-context", Node.class);
		Node questionWork = lookup(robot, "#question-capture-work", Node.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		captureQuestion(robot, "31");

		// A successful new-Question save deliberately keeps the capture session
		// active so the teacher can proceed directly to the following Question.
		assertTrue(newMode.isSelected());
		assertFalse(classification.isDisabled());
		assertFalse(questionWork.isDisabled());
		assertTrue(pane.canCaptureRegions());

		// The previous Question itself has been cleared ready for fresh metadata and
		// content, while the new-Question workflow remains active.
		assertEquals("", questionCode.getText());
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		dragRegionOnDisplayedPage(robot);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));

		// Leave no transient rectangle behind for test cleanup.
		// Click away using the production cancellation path rather than a dedicated
		// clear-selection action.
		robot.clickOn("#pdf-page-view");
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(selectionState.hasPendingSelection());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void workingSubjectChangesWhenNoCaptureWorkIsPending(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject physics = workingSubjectBox.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();

		// With no pending selection or accepted regions, changing Working Subject is a
		// valid workspace transition and must update both capture queues.
		robot.interact(() -> workingSubjectBox.setValue(physics));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(physics, workingSubjectBox.getValue());
		assertEquals(physics, field(questionCapturePane(), "workingSubject", Subject.class));
		assertEquals(physics, field(answerCapturePane(), "workingSubject", Subject.class));

		// A different Subject cannot retain the previous Chemistry classification.
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		assertEquals(physics, model.getSubject());
		assertEquals(null, model.getClassification());
		assertEquals("", lookup(robot, "#curriculum-code", TextField.class).getText());
	}
}
