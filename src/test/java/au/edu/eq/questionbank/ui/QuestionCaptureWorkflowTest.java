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
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import au.edu.eq.questionbank.ui.exam.ExamImportDialog;
import au.edu.eq.questionbank.ui.exam.ExamMetadataPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
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
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
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
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
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
	void captureEntryPointsUseTaskBasedLabels(FxRobot robot) throws Exception {
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);

		// Question capture modes now belong only to the visible Question pane.
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "capture-new-questions".equals(item.getId())));
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "capture-imported-questions".equals(item.getId())));
		ToggleButton newMode = lookup(robot, "#capture-mode-new", ToggleButton.class);
		ToggleButton importedMode = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertEquals("Capture New Questions", newMode.getText());
		assertEquals("Capture Imported Questions", importedMode.getText());

		// New-question capture remains the initial Question-pane mode.
		assertTrue(newMode.isSelected());
		assertFalse(importedMode.isSelected());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());

		// Imported-question capture remains available directly from its pane button.
		fireControl(robot, importedMode);
		assertFalse(newMode.isSelected());
		assertTrue(importedMode.isSelected());
		assertTrue(legacyControls.isVisible());
		assertTrue(legacyControls.isManaged());

		// Returning to new capture uses the other pane button rather than a menu
		// action.
		fireControl(robot, newMode);
		assertTrue(newMode.isSelected());
		assertFalse(importedMode.isSelected());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
		MenuItem openExamItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "open-exam-for-capture".equals(item.getId())).findFirst().orElseThrow();
		assertEquals("_Open Exam for Capture...", openExamItem.getText());

		// The dialog uses the same task-oriented wording as its menu entry.
		ExamImportDialog dialog = field(application, "examImportDialog", ExamImportDialog.class);
		assertEquals("Open Exam for Capture", dialog.getTitle());
		Button confirmButton = (Button) dialog.getDialogPane()
				.lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
		assertEquals("Open for Capture", confirmButton.getText());
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
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		TextField questionCodeField = lookup(robot, "#question-code", TextField.class);
		TextField marksField = lookup(robot, "#question-marks", TextField.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		robot.clickOn(questionCodeField).write("Q7");
		robot.clickOn(marksField).write("1");

		// Saving the first Q7 resets the response type, so explicitly select Written
		// response again for the duplicate capture attempt.
		fireControl(robot, writtenResponse);
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
		fireDialogButton(robot, "OK");
		assertEquals("Q7", questionCodeField.getText());
		assertEquals("1", marksField.getText());
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(lookup(robot, "#save-question", Button.class).isDisabled());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		long matchingQuestions = repository.findAll().stream()
				.filter(question -> question.getQuestionCode().equals("Q7")).count();
		assertEquals(1, matchingQuestions);
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
	void legacyQuestionControlsAreHiddenDuringNormalCapture(FxRobot robot) {
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
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
		/*
		 * A rectangle exists, but it has not yet been accepted.
		 */
		assertTrue(save.isDisabled());
		fireControl(robot, "#add-question-region");
		assertFalse(save.isDisabled());
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
