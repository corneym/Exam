package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.diagnostics.FxPulseGapProbe;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.DescriptorLookupService.DescriptorMatch;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import au.edu.eq.questionbank.ui.exam.ExamMetadataPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class QuestionCaptureWorkflowTest extends QuestionBankApplicationUiTestBase {

	private static long previewImageBytes(Node node) {
		if (node instanceof ImageView view && view.getImage() != null) {

			// Approximate decoded RGBA pixel storage, not total native or
			// JVM allocation. The actual backing format may differ.
			return Math.round(view.getImage().getWidth()) * Math.round(view.getImage().getHeight()) * 4L;
		}
		if (node instanceof Parent parent) {
			long total = 0L;
			for (Node child : parent.getChildrenUnmodifiable()) {
				total += previewImageBytes(child);
			}
			return total;
		}
		return 0L;
	}

	private static long usedHeapBytes() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

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
		long refreshGenerationBefore = field(application, "workingSubjectRefreshGeneration", Long.class).longValue();

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

		// A rejected transition must stop before the application creates a new
		// asynchronous Subject generation.
		assertEquals(refreshGenerationBefore,
				field(application, "workingSubjectRefreshGeneration", Long.class).longValue());
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
	void addingClipboardImageDoesNotReextractExistingPdfRegion(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Path csv = Files.createTempDirectory("question-preview-mixed-").resolve("performance.csv");
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(() -> pane.setPerformanceRecorder(new PerformanceRecorder(true, csv)));
		robot.interact(() -> pane.acceptSelection(
				new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 1, 0.10, 0.10, 0.25, 0.20)));
		fireControl(robot, "#add-question-region");
		assertEquals(1L, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"));
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
			pane.refreshClipboardImageAvailability();
		});
		fireControl(robot, "#paste-question-image");
		assertEquals(2, field(pane, "regionPreviewBox", VBox.class).getChildren().size());
		assertEquals(1L, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"),
				"Adding PNG content must not extract unchanged PDF content");
		fireControl(robot, "#question-content-move-up-1");
		assertEquals(1L, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"),
				"Reordering mixed content must not re-extract PDF content");

		// Remove the pasted image; the retained PDF preview remains unchanged.
		fireControl(robot, "#question-content-remove-0");
		assertEquals(1L, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"));
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
	}

	@Test
	void bookletActivationStartsQuestionCaptureWithoutSecondaryStartAction(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		QuestionCapturePane pane = questionCapturePane();
		Node classification = lookup(robot, "#classification-context", Node.class);
		Node questionWork = lookup(robot, "#question-capture-work", Node.class);
		Button addRegion = lookup(robot, "#add-question-region", Button.class);

		// Choosing a real booklet for capture is already the explicit entry into new
		// Question capture. The Question pane must not ask for the same decision again.
		assertTrue(robot.lookup("#capture-mode-new").tryQuery().isEmpty());
		assertFalse(classification.isDisabled());
		assertFalse(questionWork.isDisabled());
		assertTrue(pane.canCaptureRegions());
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);

		// The selected booklet's Exam PDF is immediately ready for Question-region
		// work.
		dragRegionOnDisplayedPage(robot);
		assertTrue(selectionState.hasPendingSelection());
		assertFalse(addRegion.isDisabled());

		// Leave no transient rectangle behind for test cleanup. Pointer interaction is
		// intentional here because PDF click-away cancellation itself is under test.
		robot.clickOn("#pdf-page-view");
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(selectionState.hasPendingSelection());
	}

	@Test
	void committedQuestionIsNotReportedAsUnsavedWhenEditCompletionFails(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question original = captureQuestion(robot, "72");
		QuestionCapturePane pane = questionCapturePane();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		AtomicInteger completionAttempts = new AtomicInteger();
		boolean[] editStarted = { false };

		// The edit-completion callback executes only after the
		// Question edit has been committed and published.
		robot.interact(() -> editStarted[0] = pane.editQuestion(original, () -> {
			completionAttempts.incrementAndGet();
			throw new IllegalStateException("Injected edit-completion failure");
		}));
		assertTrue(editStarted[0]);

		// Change persisted content to establish that an update,
		// rather than a second insert, took place.
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.interact(() -> marks.setText("3"));
		fireControl(robot, "#save-question");

		// A committed edit with a failed completion callback must
		// show the committed-save warning, not an unsaved error.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Question saved; workspace refresh failed.").tryQuery().isPresent());
		Question persisted = stored.findById(original.getId()).orElseThrow();
		assertEquals(original.getId(), persisted.getId());
		assertEquals("72", persisted.getQuestionCode());
		assertEquals(3, persisted.getMarks());
		assertEquals(1, stored.findAll().size());
		assertEquals(1, completionAttempts.get());
		fireDialogButton(robot, "OK");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		Label status = lookup(robot, "#question-save-status", Label.class);
		assertTrue(status.getText().contains("Saved 72"));
		assertTrue(status.getText().contains("ID " + original.getId()));
		assertTrue(status.getText().contains("workspace refresh failed"));
		Button save = lookup(robot, "#save-question", Button.class);
		assertTrue(save.isDisabled());

		// The failed completion handler must not be invoked
		// again and the committed Question must not be resaved.
		fireControl(robot, save);
		assertEquals(1, stored.findAll().size());
		assertEquals(1, completionAttempts.get());
		assertEquals(3, stored.findById(original.getId()).orElseThrow().getMarks());
	}

	@Test
	void committedQuestionIsNotReportedAsUnsavedWhenPublicationFails(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.interact(() -> {
			questionCode.setText("71");
			marks.setText("1");
		});
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		QuestionCapturePane pane = questionCapturePane();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		AtomicInteger publicationAttempts = new AtomicInteger();

		// Inject a failure at the real post-commit publication
		// boundary, not during validation or SQLite persistence.
		setField(pane, "questionsChangedHandler", (java.util.function.Consumer<List<Question>>) _ -> {
			publicationAttempts.incrementAndGet();
			throw new IllegalStateException("Injected UI publication failure");
		});
		fireControl(robot, "#save-question");

		// This warning proves that the worker reported a committed
		// Question and that the publication recovery path executed.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Question saved; workspace refresh failed.").tryQuery().isPresent());
		List<Question> persisted = stored.findAll();
		assertEquals(1, persisted.size());
		assertEquals("71", persisted.getFirst().getQuestionCode());
		assertEquals(1, persisted.getFirst().getRegions().size());
		long savedId = persisted.getFirst().getId();
		assertEquals(1, publicationAttempts.get());
		fireDialogButton(robot, "OK");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		Label status = lookup(robot, "#question-save-status", Label.class);
		assertTrue(status.getText().contains("Saved 71"));
		assertTrue(status.getText().contains("ID " + savedId));
		assertTrue(status.getText().contains("workspace refresh failed"));
		Button save = lookup(robot, "#save-question", Button.class);
		assertTrue(save.isDisabled());

		// Attempting to fire Save cannot insert a duplicate or
		// repeat persistence after the committed-save warning.
		fireControl(robot, save);
		assertEquals(1, stored.findAll().size());
		assertEquals(1, publicationAttempts.get());
	}

	@Test
	void committedQuestionIsNotReportedAsUnsavedWhenRefreshFails(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.interact(() -> {
			lookup(robot, "#question-code", TextField.class).setText("29");
			lookup(robot, "#question-marks", TextField.class).setText("1");
		});
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AtomicInteger reads = new AtomicInteger();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				assertFalse(Platform.isFxApplicationThread());
				reads.incrementAndGet();
				throw new IllegalStateException("Simulated refresh failure after commit");
			}
		});
		fireControl(robot, "#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Question saved; lists could not be fully refreshed.").tryQuery().isPresent());

		// Failure occurred during the sole full-corpus retrieval,
		// after the transaction committed.
		assertEquals(1, reads.get());
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
		assertFalse(importedQuestions.getItems().isEmpty(), "The incomplete Question must still be queued before Save");
		fireControl(robot, "#save-question");

		// The imported entry action was already hidden when capture started.
		// Wait instead for the asynchronous save to empty the queue and
		// return the workflow to its idle state.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> importedQuestions.getItems().isEmpty()
				&& !importedControls.isVisible() && !importedControls.isManaged() && !pane.canCaptureRegions());
		WaitForAsyncUtils.waitForFxEvents();

		// Check the database independently of the UI queue.
		Question persisted = repository.findById(incomplete.getId()).orElseThrow();
		assertFalse(persisted.getContentParts().isEmpty(), "The completed Question must have persisted content");
		assertTrue(importedQuestions.getItems().isEmpty());
		assertFalse(importedAction.isVisible());
		assertFalse(importedAction.isManaged());
		assertFalse(importedControls.isVisible());
		assertFalse(importedControls.isManaged());

		// Exhausting imported work returns to idle rather than silently starting
		// another Question. New capture can be entered again only through its
		// task-level route.
		assertTrue(robot.lookup("#capture-mode-new").tryQuery().isEmpty());
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
	void dashboardHomeMenusRemoveRedundantNavigation(FxRobot robot) throws Exception {
		MenuBar menuBar = lookup(robot, ".menu-bar", MenuBar.class);
		Menu examMenu = menuBar.getMenus().stream().filter(menu -> "_Exam".equals(menu.getText())).findFirst()
				.orElseThrow();
		Menu questionMenu = menuBar.getMenus().stream().filter(menu -> "_Questions".equals(menu.getText())).findFirst()
				.orElseThrow();

		// Exam/Assets is reached from Dashboard selection rather than a parallel
		// Exam-menu entry.
		assertFalse(examMenu.getItems().stream().anyMatch(item -> "open-exam-for-capture".equals(item.getId())));

		// Exam lifecycle is also Dashboard-selection state now. Only source replacement
		// remains appropriate as active-capture Exam menu work.
		assertFalse(examMenu.getItems().stream().anyMatch(item -> "mark-active-exam-complete".equals(item.getId())));
		assertFalse(examMenu.getItems().stream().anyMatch(item -> "reactivate-active-exam".equals(item.getId())));
		assertTrue(examMenu.getItems().stream().anyMatch(item -> "replace-active-question-pdf".equals(item.getId())));
		assertTrue(examMenu.getItems().stream().anyMatch(item -> "replace-active-answer-pdf".equals(item.getId())));

		// Dashboard is already Home, so Questions contains only specialised Search.
		assertEquals(1, questionMenu.getItems().size());
		assertEquals("_Search...", questionMenu.getItems().getFirst().getText());
		assertFalse(questionMenu.getItems().stream().anyMatch(item -> "question-corpus-audit".equals(item.getId())));
	}

	@Test
	void directImportedCorrectionCompletesAfterSuccessfulSave(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question incomplete = repository.save(booklet, "DASH-Q", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		AtomicInteger completed = new AtomicInteger();

		// Dashboard routing selects exactly one persisted incomplete Question and owns
		// the completion callback for that correction.
		robot.interact(() -> assertTrue(pane.captureImportedQuestion(incomplete, completed::incrementAndGet)));
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		fireControl(robot, "#save-question");

		// Completion is the durable workflow result; do not wait merely for a transient
		// save flag to become false.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> completed.get() == 1);
		Question stored = repository.findById(incomplete.getId()).orElseThrow();
		assertFalse(stored.getContentParts().isEmpty());
		assertEquals(1, completed.get());
	}

	@Test
	void duplicateClipboardImageRequiresConfirmationAndCanBeAddedAfterRemoval(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Label count = lookup(robot, "#question-region-count", Label.class);
		Button addFromClipboard = lookup(robot, "#paste-question-image", Button.class);
		VBox previews = field(questionCapturePane(), "regionPreviewBox", VBox.class);

		// Use the same real JavaFX clipboard route as Windows Snipping Tool.
		robot.interact(() -> {
			WritableImage image = new WritableImage(40, 20);
			for (int y = 0; y < 20; y++) {
				for (int x = 0; x < 40; x++) {
					image.getPixelWriter().setColor(x, y, Color.ORANGE);
				}
			}
			ClipboardContent content = new ClipboardContent();
			content.putImage(image);
			Clipboard.getSystemClipboard().setContent(content);
			questionCapturePane().refreshClipboardImageAvailability();
		});
		assertFalse(addFromClipboard.isDisabled());

		// The first addition is not a duplicate and must not open a dialog.
		fireControl(robot, addFromClipboard);
		assertEquals("Content parts: 1", count.getText());
		assertEquals(1, previews.getChildren().size());
		Node originalPreview = previews.getChildren().getFirst();

		// A second insertion requests explicit consent. Declining must not
		// change the content list or discard the clipboard image.
		fireControlLater(addFromClipboard);
		waitForDialogShowing(robot, "Duplicate clipboard image");
		DialogPane declineDialog = showingDialogPane(robot, "Duplicate clipboard image");
		assertEquals("This image has already been added to this Question.", declineDialog.getHeaderText());
		assertEquals("Add it again?", declineDialog.getContentText());
		Node decline = declineDialog.lookupButton(ButtonType.NO);
		assertTrue(decline instanceof ButtonBase);
		robot.interact(((ButtonBase) decline)::fire);
		waitForDialogHidden(robot, "Duplicate clipboard image");
		assertEquals("Content parts: 1", count.getText());
		assertEquals(1, previews.getChildren().size());
		assertSame(originalPreview, previews.getChildren().getFirst());
		assertTrue(evaluateOnFx(robot, () -> Clipboard.getSystemClipboard().hasImage()));

		// The user may intentionally accept an exact duplicate.
		fireControlLater(addFromClipboard);
		waitForDialogShowing(robot, "Duplicate clipboard image");
		DialogPane acceptDialog = showingDialogPane(robot, "Duplicate clipboard image");
		Node accept = acceptDialog.lookupButton(ButtonType.YES);
		assertTrue(accept instanceof ButtonBase);
		robot.interact(((ButtonBase) accept)::fire);
		waitForDialogHidden(robot, "Duplicate clipboard image");
		assertEquals("Content parts: 2", count.getText());
		assertEquals(2, previews.getChildren().size());
		assertTrue(evaluateOnFx(robot, () -> Clipboard.getSystemClipboard().hasImage()));

		// Removal releases both accepted instances. The same clipboard image
		// can then be added without confirmation because no duplicate remains.
		fireControl(robot, "#question-content-remove-1");
		fireControl(robot, "#question-content-remove-0");
		assertEquals("Content parts: 0", count.getText());
		assertTrue(previews.getChildren().isEmpty());
		fireControl(robot, addFromClipboard);
		assertEquals("Content parts: 1", count.getText());
		assertEquals(1, previews.getChildren().size());

		// Leave the shared test clipboard in the same deterministic non-image
		// state as the existing clipboard workflow tests.
		robot.interact(() -> {
			ClipboardContent content = new ClipboardContent();
			content.putString("Clipboard duplicate confirmation test completed");
			Clipboard.getSystemClipboard().setContent(content);
			questionCapturePane().refreshClipboardImageAvailability();
		});
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
		Button cancel = lookup(robot, "#cancel-question-edit", Button.class);
		assertFalse(classification.isDisabled());
		assertFalse(questionWork.isDisabled());
		assertTrue(cancel.isVisible());
		fireControl(robot, cancel);

		// Ending an edit does not silently start another Question. There is
		// deliberately
		// no local Start New Question Capture control with which to bypass task
		// routing.
		assertTrue(robot.lookup("#capture-mode-new").tryQuery().isEmpty());
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

		// Enter imported capture through the production workflow.
		robot.interact(() -> showImportedQuestionCaptureForTest(pane));
		ComboBox<Question> imported = comboBox(robot, "#imported-question");
		robot.interact(() -> imported.getSelectionModel().selectFirst());
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		AtomicInteger reads = new AtomicInteger();
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				assertFalse(Platform.isFxApplicationThread(), "Post-save refresh must run off the FX thread");
				reads.incrementAndGet();
				return stored.findAll();
			}
		});
		fireControl(robot, "#save-question");

		// The saved Question must leave the capture queue and
		// Question 42 must become the active imported Question.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> imported.getValue() != null && imported.getValue().getId() == second.getId());
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(second.getId(), imported.getValue().getId());
		assertEquals(1, imported.getItems().size());
		assertEquals(1, stored.findById(first.getId()).orElseThrow().getRegions().size());

		// Batch 02 eliminates the pre-save full-corpus read.
		// One authoritative post-save refresh remains.
		assertEquals(1, reads.get(), "Imported capture save must perform exactly " + "one full-corpus refresh");
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
		// this
		// as relevant imported/incomplete work.
		Question incomplete = repository.save(booklet, "41", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		assertTrue(importedAction.isVisible());
		assertTrue(importedAction.isManaged());
		assertEquals("Complete Imported Question", importedAction.getText());

		// Before entry, the action advertises the available task without cluttering
		// ordinary new-Question capture with the queue itself.
		assertFalse(importedControls.isVisible());
		fireControl(robot, importedAction);
		ComboBox<Question> importedQuestions = comboBox(robot, "#imported-question");
		Label queueLabel = lookup(robot, "#imported-question-queue-label", Label.class);
		assertTrue(importedControls.isVisible());
		assertTrue(importedControls.isManaged());
		assertEquals("Question(s) awaiting capture", queueLabel.getText());
		assertEquals(1, importedQuestions.getItems().size());
		assertEquals(incomplete.getId(), importedQuestions.getItems().getFirst().getId());

		// Once the queue owns the workspace, its entry action is redundant and
		// therefore
		// disappears until the teacher deliberately leaves imported capture.
		assertFalse(importedAction.isVisible());
		assertFalse(importedAction.isManaged());
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

		// Create outstanding work only for Chemistry. Subject transitions must filter
		// this persisted Question from the one shared application snapshot.
		repository.save(chemistryBooklet, "43", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		robot.interact(pane::refreshImportedQuestions);
		assertTrue(importedAction.isVisible());
		robot.interact(() -> workingSubjectBox.setValue(physics));

		// The previous Subject's queue is cleared synchronously before the background
		// snapshot is loaded.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !importedAction.isVisible());
		assertEquals(physics, workingSubjectBox.getValue());
		robot.interact(() -> workingSubjectBox.setValue(chemistry));

		// Returning to Chemistry now depends on asynchronous snapshot publication, so
		// wait for the observable queue result rather than merely draining FX events.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, importedAction::isVisible);
		assertEquals(chemistry, workingSubjectBox.getValue());
		assertTrue(importedAction.isManaged());
	}

	@Test
	void importedCaptureEntryRecordsRepairAndNoOpMeasurements(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Persist an incomplete multipart Question without its derived
		// SourceQuestion relationship.
		Question incomplete = repository.save(booklet, "41a", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		assertFalse(repository.findById(incomplete.getId()).orElseThrow().hasSourceQuestion());
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		Path csv = Files.createTempDirectory("imported-entry-performance-").resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
		robot.interact(() -> pane.setPerformanceRecorder(recorder));

		// First entry repairs the missing SourceQuestion relationship.
		robot.interact(() -> showImportedQuestionCaptureForTest(pane));
		Question repaired = repository.findById(incomplete.getId()).orElseThrow();
		assertTrue(repaired.hasSourceQuestion());
		assertEquals("41", repaired.getSourceQuestion().getSourceQuestionCode());
		ComboBox<Question> queue = comboBox(robot, "#imported-question");
		assertEquals(1, queue.getItems().size());
		assertEquals(incomplete.getId(), queue.getItems().getFirst().getId());

		// Re-enter without modifying persistence.
		// Backfill and reconciliation must now be no-ops.
		robot.interact(() -> showImportedQuestionCaptureForTest(pane));
		Question repeated = repository.findById(incomplete.getId()).orElseThrow();
		assertTrue(repeated.hasSourceQuestion());
		assertEquals(repaired.getSourceQuestion().getId(), repeated.getSourceQuestion().getId());
		assertTrue(Files.isRegularFile(csv));
		List<String[]> rows = Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1)).toList();
		List<String[]> entries = rows.stream().filter(row -> row[3].equals("capture.question.imported.entry")).toList();
		assertEquals(2, entries.size());
		for (int index = 0; index < entries.size(); index++) {
			String[] entry = entries.get(index);
			String entryId = entry[1];
			assertEquals("true", entry[5]);
			assertEquals("1", entry[6]);

			// The three workflow phases remain mandatory.
			// SQLite and JavaFX diagnostics are additional children.
			for (String phase : List.of("capture.question.imported.backfill", "capture.question.imported.reconcile",
					"capture.question.imported.refresh")) {
				assertEquals(1,
						rows.stream().filter(row -> row[2].equals(entryId)).filter(row -> row[3].equals(phase)).count(),
						"Missing or duplicate phase: " + phase);
			}
			String[] backfill = rows.stream().filter(row -> row[2].equals(entryId))
					.filter(row -> row[3].equals("capture.question.imported.backfill")).findFirst()
					.orElseThrow(() -> new AssertionError("Missing backfill for entry " + entryId));
			String[] corpus = rows.stream().filter(row -> row[2].equals(backfill[1]))
					.filter(row -> row[3].equals("capture.question.imported.backfill.corpus")).findFirst()
					.orElseThrow(() -> new AssertionError("Missing initial corpus read for entry " + entryId));
			String[] links = rows.stream().filter(row -> row[2].equals(backfill[1]))
					.filter(row -> row[3].equals("capture.question.imported.backfill.links")).findFirst()
					.orElseThrow(() -> new AssertionError("Missing link measurement for entry " + entryId));
			assertEquals("1", corpus[6]);

			// First entry repairs one relationship; second repairs none.
			assertEquals(index == 0 ? "1" : "0", links[6]);
			List<String[]> reloads = rows.stream().filter(row -> row[2].equals(backfill[1]))
					.filter(row -> row[3].equals("capture.question.imported.backfill.reload")).toList();
			assertEquals(index == 0 ? 1 : 0, reloads.size());
			if (index == 0) {
				assertEquals("1", reloads.getFirst()[6]);
			}
			String[] reconciliation = rows.stream().filter(row -> row[2].equals(entryId))
					.filter(row -> row[3].equals("capture.question.imported.reconcile")).findFirst()
					.orElseThrow(() -> new AssertionError("Missing reconciliation for entry " + entryId));
			String[] scan = rows.stream().filter(row -> row[2].equals(reconciliation[1]))
					.filter(row -> row[3].equals("capture.question.imported.reconcile.scan")).findFirst()
					.orElseThrow(() -> new AssertionError("Missing reconciliation scan for entry " + entryId));

			// The fixture has no Shared Context group.
			assertEquals("0", scan[6]);

			// There must be no Shared Context update attempts.
			assertTrue(rows.stream().noneMatch(
					row -> row[3].equals("capture.question.imported.reconcile.apply") && row[2].equals(scan[1])));

			// Optimised reconciliation does not reload the corpus.
			assertTrue(rows.stream().noneMatch(row -> row[3].equals("capture.question.imported.reconcile.corpus")
					&& row[2].equals(reconciliation[1])));
			String[] refresh = rows.stream().filter(row -> row[2].equals(entryId))
					.filter(row -> row[3].equals("capture.question.imported.refresh")).findFirst()
					.orElseThrow(() -> new AssertionError("Missing queue refresh for entry " + entryId));
			assertEquals("1", refresh[6]);

			// No reconciliation writes occurred, so refresh must
			// reuse the authoritative snapshot.
			assertTrue(rows.stream().noneMatch(
					row -> row[3].equals("capture.question.imported.refresh.corpus") && row[2].equals(refresh[1])));
		}
	}

	@Test
	void importedCaptureEntryRecordsSqlAndFxPulseCounts(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question incomplete = repository.save(booklet, "51a", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		assertFalse(repository.findById(incomplete.getId()).orElseThrow().hasSourceQuestion());
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		Path csv = Files.createTempDirectory("imported-sql-fx-").resolve("performance.csv");
		robot.interact(() -> pane.setPerformanceRecorder(new PerformanceRecorder(true, csv)));

		// Establish an actual pulse baseline before entering the
		// synchronous operation. This avoids relying on a sleep.
		FxPulseGapProbe heartbeat = field(pane, "importedEntryPulseProbe", FxPulseGapProbe.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, heartbeat::hasObservedPulse);
		ToggleButton action = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		assertTrue(action.isVisible());
		fireControl(robot, action);
		ComboBox<Question> queue = comboBox(robot, "#imported-question");
		assertEquals(1, queue.getItems().size());
		Question repaired = repository.findById(incomplete.getId()).orElseThrow();
		assertTrue(repaired.hasSourceQuestion());
		assertEquals("51", repaired.getSourceQuestion().getSourceQuestionCode());

		// The pulse measurement is deliberately recorded after
		// the entry method returns. Wait for that actual sample.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			if (!Files.isRegularFile(csv)) {
				return false;
			}
			try {
				return Files.readString(csv).contains("capture.question.imported.fx.pulse-gap-us");
			} catch (IOException exception) {
				return false;
			}
		});
		List<String[]> rows = Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1)).toList();
		String[] entry = rows.stream().filter(row -> row[3].equals("capture.question.imported.entry")).findFirst()
				.orElseThrow();
		assertEquals("true", entry[5]);
		assertEquals("1", entry[6]);
		String parentId = entry[1];
		List<String[]> metrics = rows.stream().filter(row -> row[2].equals(parentId)).toList();
		for (String name : List.of("connections", "executions", "questions", "rows", "open-us", "execute-us")) {
			String operation = "capture.question.imported.sqlite." + name;
			String[] metric = metrics.stream().filter(row -> row[3].equals(operation)).findFirst().orElseThrow();
			assertEquals("true", metric[5]);
			assertTrue(Long.parseLong(metric[6]) >= 0);
		}
		long connections = Long
				.parseLong(metrics.stream().filter(row -> row[3].equals("capture.question.imported.sqlite.connections"))
						.findFirst().orElseThrow()[6]);
		long executions = Long
				.parseLong(metrics.stream().filter(row -> row[3].equals("capture.question.imported.sqlite.executions"))
						.findFirst().orElseThrow()[6]);
		long reconstructed = Long
				.parseLong(metrics.stream().filter(row -> row[3].equals("capture.question.imported.sqlite.questions"))
						.findFirst().orElseThrow()[6]);

		// Backfill, reconciliation and queue refresh each retrieve
		// the one-Question corpus. Repair incurs additional SQL.
		assertTrue(connections >= 3);
		assertTrue(executions >= 3);
		assertTrue(reconstructed >= 3);
		String[] pulseGap = metrics.stream().filter(row -> row[3].equals("capture.question.imported.fx.pulse-gap-us"))
				.findFirst().orElseThrow();
		assertEquals("true", pulseGap[5]);
		assertTrue(Long.parseLong(pulseGap[6]) > 0);
	}

	@Test
	void importedCaptureFailedRepairClearsActiveQueue(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);

		// Establish a valid, incomplete Question so imported capture
		// can be entered successfully before the failure is introduced.
		Question existing = repository.save(booklet, "80", "", 1, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		Path csv = Files.createTempDirectory("imported-repair-failure-").resolve("performance.csv");
		robot.interact(() -> pane.setPerformanceRecorder(new PerformanceRecorder(true, csv)));
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node importedControls = lookup(robot, "#legacy-question-capture", Node.class);
		ComboBox<Question> importedQueue = comboBox(robot, "#imported-question");
		fireControl(robot, importedAction);
		assertTrue(importedControls.isVisible());
		assertEquals(1, importedQueue.getItems().size());
		assertEquals(existing.getId(), importedQueue.getItems().getFirst().getId());

		// Introduce a repairable Shared Context relationship after
		// the first queue snapshot was published.
		SourceQuestion source = new SqliteSourceQuestionRepository(database).save(booklet, "91");
		SharedQuestionContext context = new SqliteSharedQuestionContextRepository(database).save(booklet,
				"Question 91 shared context", List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.70, 0.15)));
		Question linked = repository.save(booklet, "91a", "", 1, List.of(), classification, true, source, context,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question missing = repository.save(booklet, "91b", "", 1, List.of(), classification, true, source, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Inject a database failure into the relationship UPDATE.
		// The trigger affects only the Question requiring repair.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_imported_context_update
					BEFORE UPDATE OF shared_context_id ON questions
					WHEN OLD.id = %d
					     AND OLD.shared_context_id IS NULL
					BEGIN
					    SELECT RAISE(ABORT, 'Injected context repair failure');
					END
					""".formatted(missing.getId()));
		}

		// Re-enter while the previous queue is still active.
		// This path must not leave that old queue accessible.
		Platform.runLater(() -> showImportedQuestionCaptureForTest(pane));
		waitForDialogShowing(robot, "Error");
		DialogPane dialog = showingDialogPane(robot, "Error");
		assertEquals("Shared context links are inconsistent.", dialog.getHeaderText());
		ButtonBase ok = (ButtonBase) dialog.lookupButton(ButtonType.OK);
		robot.interact(ok::fire);
		waitForDialogHidden(robot, "Error");
		WaitForAsyncUtils.waitForFxEvents();

		// The rejected SQLite transaction must not partially attach
		// the missing Shared Context relationship.
		Question storedMissing = repository.findById(missing.getId()).orElseThrow();
		Question storedLinked = repository.findById(linked.getId()).orElseThrow();
		assertFalse(storedMissing.hasSharedContext());
		assertEquals(source.getId(), storedMissing.getSourceQuestion().getId());
		assertEquals(context.getId(), storedLinked.getSharedContext().getId());

		// The old queue must not remain active after a failed re-entry.
		assertFalse(importedControls.isVisible());
		assertFalse(importedControls.isManaged());
		assertFalse(pane.canCaptureRegions());
		assertTrue(importedQueue.getValue() == null);
		assertTrue(importedAction.isVisible());

		// Remove the injected failure so normal repair can resume.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_imported_context_update");
		}
		fireControl(robot, importedAction);
		Question repaired = repository.findById(missing.getId()).orElseThrow();
		assertTrue(repaired.hasSharedContext());
		assertEquals(context.getId(), repaired.getSharedContext().getId());
		assertEquals(source.getId(), repaired.getSourceQuestion().getId());
		assertTrue(importedControls.isVisible());
		assertTrue(importedQueue.getItems().stream().anyMatch(question -> question.getId() == missing.getId()));

		// Diagnostics must distinguish the failed entry from both
		// successful entries.
		List<String[]> entries = Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1))
				.filter(row -> row[3].equals("capture.question.imported.entry")).toList();
		assertEquals(3, entries.size());
		assertEquals("true", entries.get(0)[5]);
		assertEquals("false", entries.get(1)[5]);
		assertEquals("true", entries.get(2)[5]);
	}

	@Test
	void importedCaptureRejectsConflictingSharedContexts(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		SourceQuestion source = new SqliteSourceQuestionRepository(database).save(booklet, "71");
		SqliteSharedQuestionContextRepository contexts = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext firstContext = contexts.save(booklet, "Question 71 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.70, 0.15)));
		SharedQuestionContext conflictingContext = contexts.save(booklet, "Conflicting Question 71 context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.30, 0.70, 0.15)));
		Question first = repository.save(booklet, "71a", "", 1, List.of(), classification, true, source, firstContext,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question second = repository.save(booklet, "71b", "", 1, List.of(), classification, true, source, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Simulate an inconsistent legacy relationship using SQL.
		// Normal repository APIs must not be weakened to create it.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE questions SET shared_context_id = ? WHERE id = ?")) {
			statement.setLong(1, conflictingContext.getId());
			statement.setLong(2, second.getId());
			assertEquals(1, statement.executeUpdate());
		}
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(pane::refreshImportedQuestions);
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node importedControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertTrue(importedAction.isVisible());

		// The repair error produces a modal alert.
		// Do not block robot.interact while that alert is showing.
		fireControlLater(importedAction);
		waitForDialogShowing(robot, "Error");
		DialogPane dialog = showingDialogPane(robot, "Error");
		assertEquals("Shared context links are inconsistent.", dialog.getHeaderText());
		assertTrue(dialog.getContentText().contains("inconsistent shared context links"));
		ButtonBase ok = (ButtonBase) dialog.lookupButton(ButtonType.OK);
		robot.interact(ok::fire);
		waitForDialogHidden(robot, "Error");
		WaitForAsyncUtils.waitForFxEvents();

		// No conflicting relationship may be rewritten.
		Question storedFirst = repository.findById(first.getId()).orElseThrow();
		Question storedSecond = repository.findById(second.getId()).orElseThrow();
		assertEquals(firstContext.getId(), storedFirst.getSharedContext().getId());
		assertEquals(conflictingContext.getId(), storedSecond.getSharedContext().getId());
		assertEquals(source.getId(), storedFirst.getSourceQuestion().getId());
		assertEquals(source.getId(), storedSecond.getSourceQuestion().getId());

		// A failed initial entry must not activate the capture queue.
		assertFalse(importedControls.isVisible());
		assertFalse(importedControls.isManaged());
		assertFalse(pane.canCaptureRegions());

		// Correct the deliberately inconsistent database fixture.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE questions SET shared_context_id = NULL WHERE id = ?")) {
			statement.setLong(1, second.getId());
			assertEquals(1, statement.executeUpdate());
		}

		// Retrying must use freshly persisted Questions rather than
		// the stale pre-error snapshot.
		fireControl(robot, importedAction);
		Question corrected = repository.findById(second.getId()).orElseThrow();
		assertTrue(corrected.hasSharedContext());
		assertEquals(firstContext.getId(), corrected.getSharedContext().getId());
		assertEquals(source.getId(), corrected.getSourceQuestion().getId());
		assertTrue(importedControls.isVisible());
		assertTrue(importedControls.isManaged());
	}

	@Test
	void legacyQuestionControlsAreHiddenDuringNormalCapture(FxRobot robot) throws Exception {
		showCaptureWorkspaceForTest();
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
	}

	@Test
	void measuresAcceptedPreviewPerformance(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Path diagnostics = Files.createTempDirectory("issue-141-preview-").resolve("performance.csv");
		QuestionCapturePane pane = questionCapturePane();
		PerformanceRecorder recorder = new PerformanceRecorder(true, diagnostics);
		Button addRegion = lookup(robot, "#add-question-region", Button.class);
		VBox previews = field(pane, "regionPreviewBox", VBox.class);

		// This test measures the accepted-content refresh path, not PDF-page
		// navigation. Use the same real control action as the UI workflow.
		robot.interact(() -> pane.setPerformanceRecorder(recorder));
		PulseGapSampler pulses = new PulseGapSampler();
		robot.interact(pulses::start);
		WaitForAsyncUtils.waitForFxEvents();
		long heapBefore = usedHeapBytes();
		long[] actionNanos = new long[8];
		int[] pageNumbers = new int[8];
		try {
			for (int i = 0; i < 8; i++) {
				final int index = i;
				final int page = i < 4 ? 1 : 2;
				pageNumbers[i] = page;
				robot.interact(() -> {
					if (index == 0 || index == 4) {

						// Display the source page before timing Add Region.
						pdfWorkspace().showPage(PdfWorkspacePane.DocumentMode.EXAM, page);
					}
					pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, page,
							0.05 + (index % 4) * 0.22, 0.08, 0.18, 0.15));
					long started = System.nanoTime();
					addRegion.fire();
					actionNanos[index] = System.nanoTime() - started;
				});
				WaitForAsyncUtils.waitForFxEvents();
			}
		} finally {
			robot.interact(pulses::stop);
		}
		long[] imageBytes = new long[1];
		robot.interact(() -> imageBytes[0] = previewImageBytes(previews));
		long heapAfter = usedHeapBytes();
		assertEquals("Content parts: 8", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(Files.isRegularFile(diagnostics), "Opt-in performance diagnostics must be written");
		List<String[]> rows = Files.readAllLines(diagnostics).stream().skip(1).map(line -> line.split(",", -1))
				.toList();
		List<String[]> previewsMeasured = rows.stream().filter(row -> row.length >= 7)
				.filter(row -> "capture.question.region.preview".equals(row[3])).toList();
		List<String[]> extractsMeasured = rows.stream().filter(row -> row.length >= 7)
				.filter(row -> "capture.question.region.extract".equals(row[3])).toList();
		assertEquals(8, previewsMeasured.size(), "Exactly one timed accepted-content refresh per region");
		assertEquals(8, extractsMeasured.size(), "Each new PDF region is extracted once; unchanged regions are reused");
		assertTrue(previewsMeasured.stream().allMatch(row -> "true".equals(row[5])));
		assertTrue(extractsMeasured.stream().allMatch(row -> "true".equals(row[5])));
		long uncachedReferenceCrops = 8L * 9L / 2L;
		Path report = Path.of("target", "performance", "issue-141-preview-summary.csv");
		Files.createDirectories(report.toAbsolutePath().getParent());
		StringBuilder summary = new StringBuilder();
		summary.append("step,page,action_ms,preview_ms,optimised_extracts_to_step,")
				.append("uncached_reference_extracts_to_step,").append("estimated_retained_image_mib,heap_change_mib,")
				.append("max_observed_fx_pulse_gap_ms\n");
		double cumulativeActionMs = 0.0;
		double cumulativePreviewMs = 0.0;
		double maximumPreviewMs = 0.0;
		for (int i = 0; i < 8; i++) {
			double actionMs = actionNanos[i] / 1_000_000.0;
			double previewMs = Double.parseDouble(previewsMeasured.get(i)[4]);
			cumulativeActionMs += actionMs;
			cumulativePreviewMs += previewMs;
			maximumPreviewMs = Math.max(maximumPreviewMs, previewMs);

			// The 1+2+...+n reference is a crop-count reconstruction
			// of the pre-optimisation loop, not measured old-code timing.
			long referenceCount = (long) (i + 1) * (i + 2) / 2;
			summary.append(String.format(Locale.ROOT, "%d,%d,%.3f,%.3f,%d,%d,%.3f,%.3f,%.3f%n", i + 1, pageNumbers[i],
					actionMs, previewMs, i + 1, referenceCount, imageBytes[0] / 1_048_576.0,
					(heapAfter - heapBefore) / 1_048_576.0, pulses.maxGapMillis()));
		}
		Files.writeString(report, summary.toString(), StandardCharsets.UTF_8);
		System.out.printf(Locale.ROOT,
				"ISSUE_141_PREVIEW_REPORT %s%n" + "  optimised_crops=8 uncached_reference_crops=%d%n"
						+ "  preview_total_ms=%.3f preview_max_ms=%.3f%n" + "  fx_add_action_total_ms=%.3f%n"
						+ "  max_observed_fx_pulse_gap_ms=%.3f%n" + "  estimated_retained_image_mib=%.3f "
						+ "approx_heap_delta_mib=%.3f%n",
				report.toAbsolutePath(), uncachedReferenceCrops, cumulativePreviewMs, maximumPreviewMs,
				cumulativeActionMs, pulses.maxGapMillis(), imageBytes[0] / 1_048_576.0,
				(heapAfter - heapBefore) / 1_048_576.0);
	}

	@Test
	void mouseSelectingImportedQuestionMayActivateSubjectWithoutRebuildingOpenPopup(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumSelectionModel curriculumModel = field(application, "curriculumSelectionModel",
				CurriculumSelectionModel.class);
		CurriculumNode classification = curriculumModel.getClassification();
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
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			AtomicBoolean restored = new AtomicBoolean();
			robot.interact(() ->

			// Imported-question activation is complete only when the asynchronously
			// activated Subject has also restored the Question's persisted
			// classification.
			restored.set(imported.getValue() != null && imported.getValue().getId() == importedQuestion.getId()
					&& booklet.getExam().getSubject().equals(workingSubject.getValue())
					&& examMetadataPane().getBooklet() != null
					&& examMetadataPane().getBooklet().getId() == booklet.getId()
					&& classification.equals(curriculumModel.getClassification())));
			return restored.get();
		});

		// Processing all queued FX work must not reveal a delayed ComboBox/ListView
		// exception after the popup selection has completed.
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(importedQuestion.getId(), imported.getValue().getId());
		assertEquals(booklet.getExam().getSubject(), workingSubject.getValue());
		assertEquals(booklet.getId(), examMetadataPane().getBooklet().getId());
		assertEquals(classification, curriculumModel.getClassification());

		// Activating a real booklet for the new Subject opens its replacement Exam PDF.
		// The Subject-change clear must not erase that newly established document.
		assertTrue(pdfWorkspace().hasExamPdf());
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
	}

	@Test
	void multipartAndRegionDiagnosticsRetainParentCorrelation(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Path csv = Files.createTempDirectory("question-capture-performance-").resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(() -> pane.setPerformanceRecorder(recorder));
		TextField questionCode = lookup(robot, "#question-code", TextField.class);

		// A multipart suffix must enter the inference path.
		robot.interact(() -> questionCode.setText("42b"));
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertTrue(Files.isRegularFile(csv));
		List<String[]> rows = Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1)).toList();
		String[] codeChange = rows.stream().filter(row -> row[3].equals("capture.question.code-change")).findFirst()
				.orElseThrow();
		String[] inference = rows.stream().filter(row -> row[3].equals("capture.question.code.infer")).findFirst()
				.orElseThrow();
		String[] sourceLookup = rows.stream().filter(row -> row[3].equals("capture.question.code.context.source"))
				.findFirst().orElseThrow();
		assertEquals(codeChange[1], inference[2]);
		assertEquals(inference[1], sourceLookup[2]);

		// If this fixture has an existing Source Question,
		// its corpus and scan phases must share the inference parent.
		for (String[] row : rows) {
			if (row[3].equals("capture.question.code.context.corpus")
					|| row[3].equals("capture.question.code.context.scan")) {
				assertEquals(inference[1], row[2]);
			}
		}
		String[] region = rows.stream().filter(row -> row[3].equals("capture.question.region.accept")).findFirst()
				.orElseThrow();
		for (String phase : List.of("capture.question.region.clear-selection", "capture.question.region.preview",
				"capture.question.region.controls")) {
			String[] child = rows.stream().filter(row -> row[3].equals(phase)).findFirst().orElseThrow();
			assertEquals(region[1], child[2]);
			assertEquals("true", child[5]);
		}
		assertEquals("true", region[5]);
		assertEquals("1", region[6]);
	}

	@Test
	void multipartTypingShowsConflictingContextAndRecoversAfterCorrection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		SourceQuestion source = new SqliteSourceQuestionRepository(database).save(booklet, "73");
		SqliteSharedQuestionContextRepository contexts = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext firstContext = contexts.save(booklet, "Question 73 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.70, 0.15)));
		SharedQuestionContext conflictingContext = contexts.save(booklet, "Conflicting Question 73 context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.40, 0.70, 0.15)));
		Question first = questions.save(booklet, "73a", "", 2, List.of(), classification, true, source, firstContext,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question second = questions.save(booklet, "73b", "", 2, List.of(), classification, true, source, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Deliberately introduce a persisted conflict that normal
		// application capture must never create.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE questions SET shared_context_id = ? WHERE id = ?")) {
			statement.setLong(1, conflictingContext.getId());
			statement.setLong(2, second.getId());
			assertEquals(1, statement.executeUpdate());
		}
		TextField code = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		Label status = lookup(robot, "#shared-context-status", Label.class);
		Button save = lookup(robot, "#save-question", Button.class);

		// Exercise the real typing-time inference listener.
		// The conflict must be presented rather than propagated
		// as an uncaught JavaFX exception.
		robot.interact(() -> {
			code.setText("73c");
			marks.setText("2");
		});
		assertTrue(status.isVisible());
		assertTrue(status.getText().contains("inconsistent shared context links"));

		// Completing the remaining Question details cannot make
		// Save available while persisted context links conflict.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertTrue(save.isDisabled());

		// Typing must not repair or rewrite the conflicting rows.
		assertEquals(firstContext.getId(), questions.findById(first.getId()).orElseThrow().getSharedContext().getId());
		assertEquals(conflictingContext.getId(),
				questions.findById(second.getId()).orElseThrow().getSharedContext().getId());

		// Correct the inconsistent persisted relationship.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE questions SET shared_context_id = ? WHERE id = ?")) {
			statement.setLong(1, firstContext.getId());
			statement.setLong(2, second.getId());
			assertEquals(1, statement.executeUpdate());
		}

		// Re-entering the code is a fresh workflow evaluation.
		// It must not retain the previous failed lookup result.
		robot.interact(() -> {
			code.setText("74");
			code.setText("73c");
		});
		assertTrue(status.isVisible());
		assertTrue(status.getText().contains("Question 73"));
		assertFalse(status.getText().contains("inconsistent"));
		assertFalse(save.isDisabled(), "Corrected Shared Context links should permit capture");

		// The two existing Questions still retain their identities.
		assertEquals(source.getId(), questions.findById(first.getId()).orElseThrow().getSourceQuestion().getId());
		assertEquals(source.getId(), questions.findById(second.getId()).orElseThrow().getSourceQuestion().getId());
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
	void pdfRegionPreviewExtractionsGrowLinearlyAcrossTwoPages(FxRobot robot) throws Exception {
		verifyLinearPdfPreviewExtractionCounts(robot, true);
	}

	@Test
	void pdfRegionPreviewExtractionsGrowLinearlyOnOnePage(FxRobot robot) throws Exception {
		verifyLinearPdfPreviewExtractionCounts(robot, false);
	}

	@Test
	void pendingMultipartStatusTracksQuestionCodeEdits(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		captureQuestion(robot, "24a");
		TextField code = lookup(robot, "#question-code", TextField.class);
		Label pendingStatus = lookup(robot, "#question-save-status", Label.class);
		Label duplicateStatus = lookup(robot, "#question-code-status", Label.class);
		Label regionCount = lookup(robot, "#question-region-count", Label.class);

		// The current unsaved multipart Question has one accepted
		// body part. Its pending status must identify that Question.
		robot.interact(() -> code.setText("24f"));
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Pending 24f — 1 content part(s) accepted", pendingStatus.getText());
		assertFalse(duplicateStatus.isVisible());

		// Changing to another uncaptured multipart Question must
		// update the existing status without losing accepted content.
		robot.interact(() -> code.setText("24g"));
		assertEquals("Pending 24g — 1 content part(s) accepted", pendingStatus.getText());
		assertFalse(duplicateStatus.isVisible());

		// The persisted Question is a duplicate. Its red warning
		// must appear, while the obsolete green pending status clears.
		robot.interact(() -> code.setText("24a"));
		assertTrue(duplicateStatus.isVisible());
		assertEquals("Question 24a has already been captured in this booklet.", duplicateStatus.getText());
		assertEquals("", pendingStatus.getText());
		assertEquals("Content parts: 1", regionCount.getText());

		// Incremental backspacing must never restore an obsolete
		// pending number or leave the previous duplicate warning.
		robot.interact(() -> code.setText("24"));
		assertFalse(duplicateStatus.isVisible());
		assertFalse(pendingStatus.getText().contains("24a"));
		assertFalse(pendingStatus.getText().contains("24g"));
		robot.interact(() -> code.setText("2"));
		assertFalse(duplicateStatus.isVisible());
		assertFalse(pendingStatus.getText().contains("24"));
		robot.interact(() -> code.setText(""));
		assertFalse(duplicateStatus.isVisible());
		assertEquals("", pendingStatus.getText());

		// The accepted part is retained throughout the edits.
		assertEquals("Content parts: 1", regionCount.getText());
		assertTrue(questionCapturePane().hasAcceptedRegions());
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

		// Close the warning through its DialogPane-owned action so a retained or
		// unrelated text-matched Button cannot satisfy the workflow under Xvfb.
		fireDialogButton(robot, "OK");

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
	void questionPaneKeepsOnlyImportedCorrectionEntryAction(FxRobot robot) throws Exception {
		showCaptureWorkspaceForTest();
		ToggleButton importedMode = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);

		// New Question capture is owned by Dashboard or Exam/Assets and must not be
		// duplicated inside the Question pane.
		assertTrue(robot.lookup("#capture-mode-new").tryQuery().isEmpty());

		// Imported/incomplete Question correction remains a conditional local workflow.
		assertEquals("Complete Imported Question", importedMode.getText());
		assertFalse(importedMode.isSelected());
		assertFalse(importedMode.isVisible());
		assertFalse(importedMode.isManaged());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
	}

	@Test
	void questionSaveKeepsFxThreadResponsiveDuringValidationAndRefresh(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.interact(() -> {
			lookup(robot, "#question-code", TextField.class).setText("24a");
			lookup(robot, "#question-marks", TextField.class).setText("2");
		});
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				assertFalse(Platform.isFxApplicationThread(), "Question-bank reads must not block the FX thread");
				assertEquals(1, reads.incrementAndGet(), "Save must perform exactly one full-corpus refresh");
				entered.countDown();
				try {
					assertTrue(release.await(10, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return stored.findAll();
			}
		});
		try {
			fireControl(robot, "#save-question");
			assertTrue(entered.await(5, TimeUnit.SECONDS), "Post-save refresh must begin");

			// The worker is deliberately blocked during its only
			// full-corpus read. JavaFX must remain responsive.
			CountDownLatch pulse = new CountDownLatch(1);
			Platform.runLater(pulse::countDown);
			assertTrue(pulse.await(2, TimeUnit.SECONDS), "FX events must run while repository I/O waits");
			ApplicationBusyOverlay overlay = (ApplicationBusyOverlay) primaryStage.getScene().getRoot();
			PdfWorkspacePane workspace = field(application, "pdfWorkspace", PdfWorkspacePane.class);
			Button nextPage = lookup(robot, "#next-pdf-page", Button.class);
			AtomicInteger pageBefore = new AtomicInteger();
			robot.interact(() -> pageBefore.set(workspace.getCurrentPageNumber()));
			assertTrue(evaluateOnFx(robot, overlay::isBusy));
			assertTrue(evaluateOnFx(robot, nextPage::isDisabled));

			// Programmatic activation must not bypass the
			// application's shared busy state.
			fireControl(robot, nextPage);
			assertTrue(evaluateOnFx(robot, () -> workspace.getCurrentPageNumber() == pageBefore.get()),
					"PDF navigation must remain blocked during Question save");
			release.countDown();
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> stored.findAll().stream().anyMatch(question -> "24a".equals(question.getQuestionCode())));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> !pane.isSaveInProgress() && unansweredQuestions(robot).getItems().size() == 1);
			WaitForAsyncUtils.waitForFxEvents();
			Question saved = stored.findAll().getFirst();
			assertEquals("24a", saved.getQuestionCode());
			assertEquals(2, saved.getRegions().size());
			assertEquals(1, saved.getRegions().getFirst().pageNumber());
			assertEquals(1, unansweredQuestions(robot).getItems().size());
			assertEquals(1, reads.get());
		} finally {
			release.countDown();
		}
	}

	@Test
	void rapidWorkingSubjectChangesDiscardStaleQuestionSnapshot(FxRobot robot) throws Exception {
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
		Question staleChemistryQuestion = repository.save(chemistryBooklet, "ASYNC1", "", 1, List.of(), classification,
				false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
		CountDownLatch firstLoadEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstLoad = new CountDownLatch(1);
		CountDownLatch firstLoadReturned = new CountDownLatch(1);
		CountDownLatch secondLoadReturned = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		setField(application, "workingSubjectQuestionSnapshotLoader",
				(java.util.function.Supplier<List<Question>>) () -> {

					// Every Subject transition must perform exactly one application-level
					// corpus read and it must never run on the JavaFX thread.
					assertFalse(Platform.isFxApplicationThread());
					int read = reads.getAndIncrement();
					if (read == 0) {
						firstLoadEntered.countDown();
						try {
							assertTrue(releaseFirstLoad.await(10, TimeUnit.SECONDS));
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							throw new IllegalStateException(exception);
						}
						firstLoadReturned.countDown();

						// This deliberately stale Chemistry snapshot must be discarded after
						// the later Subject transition has completed.
						return List.of(staleChemistryQuestion);
					}
					if (read == 1) {
						secondLoadReturned.countDown();

						// The later Chemistry transition deliberately publishes an empty
						// snapshot so a subsequent stale overwrite is easy to detect.
						return List.of();
					}
					throw new AssertionError("Expected one corpus read per accepted Subject transition");
				});
		ToggleButton importedAction = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		ComboBox<Question> unanswered = unansweredQuestions(robot);
		try {

			// Begin a slow Physics refresh.
			robot.interact(() -> workingSubjectBox.setValue(physics));
			assertTrue(firstLoadEntered.await(5, TimeUnit.SECONDS));

			// Before Physics finishes, make Chemistry authoritative again. Its later
			// generation is allowed to complete first.
			robot.interact(() -> workingSubjectBox.setValue(chemistry));
			assertTrue(secondLoadReturned.await(5, TimeUnit.SECONDS));
			WaitForAsyncUtils.waitForFxEvents();
			assertEquals(chemistry, workingSubjectBox.getValue());
			assertEquals(chemistry, field(questionCapturePane(), "workingSubject", Subject.class));
			assertEquals(chemistry, field(answerCapturePane(), "workingSubject", Subject.class));
			assertFalse(importedAction.isVisible());
			assertTrue(unanswered.getItems().isEmpty());

			// Now allow the older Physics load to complete after Chemistry is already
			// current.
			releaseFirstLoad.countDown();
			assertTrue(firstLoadReturned.await(5, TimeUnit.SECONDS));
			WaitForAsyncUtils.waitForFxEvents();

			// The stale first generation must not repopulate either pane.
			assertEquals(chemistry, field(questionCapturePane(), "workingSubject", Subject.class));
			assertEquals(chemistry, field(answerCapturePane(), "workingSubject", Subject.class));
			assertFalse(importedAction.isVisible());
			assertTrue(unanswered.getItems().isEmpty());

			// Two accepted Subject transitions cause two repository snapshots, not two
			// independent reads by each capture pane.
			assertEquals(2, reads.get());
		} finally {
			releaseFirstLoad.countDown();
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
		Node classification = lookup(robot, "#classification-context", Node.class);
		Node questionWork = lookup(robot, "#question-capture-work", Node.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		captureQuestion(robot, "31");

		// A successful new-Question save keeps the task-level capture session active so
		// the teacher can proceed directly to the following Question.
		assertTrue(robot.lookup("#capture-mode-new").tryQuery().isEmpty());
		assertFalse(classification.isDisabled());
		assertFalse(questionWork.isDisabled());
		assertTrue(pane.canCaptureRegions());

		// The previous Question itself has been cleared ready for fresh metadata and
		// content while the existing booklet capture session remains active.
		assertEquals("", questionCode.getText());
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		dragRegionOnDisplayedPage(robot);
		assertTrue(selectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));

		// Leave no transient rectangle behind for test cleanup. Pointer interaction is
		// intentional because PDF click-away cancellation is the behaviour being used.
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
	void successfulQuestionSaveClearsStaleDescriptorSearchAndPersistsClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField search = lookup(robot, "#curriculum-descriptor-search", TextField.class);
		ListView<DescriptorMatch> results = listView(robot, "#curriculum-descriptor-search-results");
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);

		// Choose the actual Descriptor through Search descriptors first.
		CurriculumNode initial = model.getClassification();
		robot.interact(() -> search.setText(initial.getCode()));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().stream().anyMatch(match -> initial.equals(match.descriptor())));
		robot.interact(() -> {
			int index = -1;
			for (int i = 0; i < results.getItems().size(); i++) {
				if (initial.equals(results.getItems().get(i).descriptor())) {
					index = i;
					break;
				}
			}
			results.getSelectionModel().select(index);
			results.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
		});
		assertEquals("", search.getText());
		CurriculumNode selected = model.getClassification();
		assertEquals(initial, selected);

		// Simulate a later unused search: it must not survive a successful Save.
		robot.interact(() -> search.setText("stale keywords"));
		assertEquals("stale keywords", search.getText());
		Question saved = captureQuestion(robot, "191");
		assertEquals("", search.getText());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question persisted = repository.findById(saved.getId()).orElseThrow();
		assertEquals(selected, saved.getClassification());
		assertEquals(selected, persisted.getClassification());
	}

	@Test
	void tallAcceptedRegionsExpandWithoutAdditionalInteraction(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		QuestionCapturePane capture = questionCapturePane();
		ScrollPane scroll = field(capture, "regionsScrollPane", ScrollPane.class);
		VBox previews = field(capture, "regionPreviewBox", VBox.class);
		ImageView page = lookup(robot, "#pdf-page-view", ImageView.class);

		// Use a tall PDF selection rather than the normal small
		// rectangle used by the other workflow tests.
		AtomicReference<Double> pageHeightValue = new AtomicReference<>();
		robot.interact(() -> pageHeightValue.set(page.getBoundsInLocal().getHeight()));
		double pageHeight = pageHeightValue.get();
		assertTrue(pageHeight > 210, "The fixture must provide room for a tall region");
		double bottom = Math.min(pageHeight - 20, 400);
		robot.interact(() -> {
			fireMouseEvent(page, MouseEvent.MOUSE_PRESSED, 30, 20, true);
			fireMouseEvent(page, MouseEvent.MOUSE_DRAGGED, 160, bottom, true);
			fireMouseEvent(page, MouseEvent.MOUSE_RELEASED, 160, bottom, false);
		});
		fireControl(robot, "#add-question-region");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());

		// The first accepted preview must establish its layout
		// without a subsequent click or focus change.
		assertTrue(scroll.isVisible());
		assertTrue(scroll.isManaged());
		double firstContentHeight = previews.getLayoutBounds().getHeight();
		double firstExpectedHeight = Math.max(96.0, Math.min(300.0, firstContentHeight + 4.0));

		// The preferred viewport must accommodate the actual rendered
		// content, subject to the established minimum and maximum.
		assertEquals(firstExpectedHeight, scroll.getPrefHeight(), 2.0, "First preview preferred height: content="
				+ firstContentHeight + ", viewport=" + scroll.getPrefHeight());
		assertEquals(firstExpectedHeight, scroll.getHeight(), 3.0, "First preview must expand without another click");

		// Accept another tall region in the same Question.
		robot.interact(() -> {
			fireMouseEvent(page, MouseEvent.MOUSE_PRESSED, 35, 25, true);
			fireMouseEvent(page, MouseEvent.MOUSE_DRAGGED, 165, bottom, true);
			fireMouseEvent(page, MouseEvent.MOUSE_RELEASED, 165, bottom, false);
		});
		fireControl(robot, "#add-question-region");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Content parts: 2", lookup(robot, "#question-region-count", Label.class).getText());
		Node first = lookup(robot, "#question-content-part-0", Node.class);
		Node second = lookup(robot, "#question-content-part-1", Node.class);

		// Both previews must be laid out, without overlapping.
		assertTrue(first.getBoundsInParent().getHeight() > 0);
		assertTrue(second.getBoundsInParent().getHeight() > 0);
		assertTrue(second.getBoundsInParent().getMinY() >= first.getBoundsInParent().getMaxY());

		// The viewport must expand to its cap, rather than
		// clipping the assembled content at its original size.
		double secondContentHeight = previews.getLayoutBounds().getHeight();
		assertTrue(secondContentHeight > firstContentHeight,
				"Adding a second content part must increase " + "the accepted preview height");
		double secondExpectedHeight = Math.max(96.0, Math.min(300.0, secondContentHeight + 4.0));
		assertEquals(secondExpectedHeight, scroll.getPrefHeight(), 2.0, "Second preview preferred height: content="
				+ secondContentHeight + ", viewport=" + scroll.getPrefHeight());
		assertEquals(secondExpectedHeight, scroll.getHeight(), 3.0, "Second preview must expand without another click");

		// Both content parts must remain managed and available
		// for navigation inside the bounded scrolling area.
		assertTrue(first.isManaged());
		assertTrue(second.isManaged());
	}

	@Test
	void unchangedAcceptedPdfPreviewsKeepNodesAcrossAppendReorderAndRemoval(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		QuestionCapturePane capturePane = questionCapturePane();
		VBox previews = field(capturePane, "regionPreviewBox", VBox.class);
		List<Node> acceptedPreviews = new ArrayList<>();

		// Accept four distinct PDF regions. Previously accepted
		// preview nodes must survive each subsequent addition.
		for (int index = 0; index < 4; index++) {
			int regionIndex = index;
			robot.interact(() -> capturePane.acceptSelection(new PdfWorkspacePane.RegionSelection(
					PdfWorkspacePane.DocumentMode.EXAM, 1, 0.10 + regionIndex * 0.15, 0.10, 0.30, 0.20)));
			fireControl(robot, "#add-question-region");
			assertEquals(index + 1, previews.getChildren().size());
			for (int earlier = 0; earlier < index; earlier++) {
				assertSame(acceptedPreviews.get(earlier), previews.getChildren().get(earlier),
						"Adding another region must reuse its predecessors");
			}
			acceptedPreviews.add(previews.getChildren().get(index));
		}
		assertEquals("Content parts: 4", lookup(robot, "#question-region-count", Label.class).getText());

		// Reordering must move existing preview nodes and update
		// their control indices without recreating their images.
		fireControl(robot, "#question-content-move-up-3");
		assertSame(acceptedPreviews.get(0), previews.getChildren().get(0));
		assertSame(acceptedPreviews.get(1), previews.getChildren().get(1));
		assertSame(acceptedPreviews.get(3), previews.getChildren().get(2));
		assertSame(acceptedPreviews.get(2), previews.getChildren().get(3));

		// Remove the original second part. All remaining previews
		// retain their identity and correct ordering.
		fireControl(robot, "#question-content-remove-1");
		assertEquals(3, previews.getChildren().size());
		assertSame(acceptedPreviews.get(0), previews.getChildren().get(0));
		assertSame(acceptedPreviews.get(3), previews.getChildren().get(1));
		assertSame(acceptedPreviews.get(2), previews.getChildren().get(2));
		assertEquals("Content parts: 3", lookup(robot, "#question-region-count", Label.class).getText());

		// Replacing the active source must clear the retained
		// Question preview presentation and its transient content.
		robot.interact(capturePane::clearForNewPdf);
		assertTrue(previews.getChildren().isEmpty());
		assertFalse(capturePane.hasAcceptedRegions());
	}

	@Test
	void unsuccessfulQuestionSaveLeavesDescriptorSearchIntact(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField search = lookup(robot, "#curriculum-descriptor-search", TextField.class);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode original = model.getClassification();
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.interact(() -> {
			search.setText("retain my keywords");
			marks.clear();
		});

		// Invoke the actual Save validation boundary. The button is deliberately
		// disabled for invalid metadata, so this tests its defensive validation path.
		Platform.runLater(() -> {
			try {
				invoke(questionCapturePane(), "saveQuestion", new Class<?>[0]);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("Question is incomplete.").tryQuery().isPresent());
		assertEquals("retain my keywords", search.getText());
		assertEquals(original, model.getClassification());
		fireDialogButton(robot, "OK");
		assertEquals("retain my keywords", search.getText());
		assertEquals(original, model.getClassification());
		assertTrue(new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().isEmpty());
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

	private long countSuccessfulPreviewExtracts(Path csv, String operation) throws IOException {
		if (Files.notExists(csv)) {
			return 0L;
		}
		return Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1))
				.filter(columns -> columns.length == 7).filter(columns -> operation.equals(columns[3]))
				.filter(columns -> "true".equals(columns[5])).count();
	}

	private void verifyLinearPdfPreviewExtractionCounts(FxRobot robot, boolean twoPages) throws Exception {
		prepareExamAndClassification(robot);
		Path csv = Files.createTempDirectory("question-preview-count-").resolve("performance.csv");
		QuestionCapturePane pane = questionCapturePane();
		robot.interact(() -> pane.setPerformanceRecorder(new PerformanceRecorder(true, csv)));
		for (int index = 0; index < 8; index++) {
			int page = twoPages && index % 2 == 1 ? 2 : 1;
			double x = 0.04 + index * 0.10;

			// Exercise genuine navigation and PDF-page switching in the
			// two-page case; the selected rectangle belongs to that page.
			if (twoPages) {
				robot.interact(() -> pdfWorkspace().showPage(PdfWorkspacePane.DocumentMode.EXAM, page));
			}
			robot.interact(
					() -> pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
							page, x, 0.12, 0.08, 0.18)));
			fireControl(robot, "#add-question-region");

			// Check 1, 2, 4 and 8 accepted parts. Old behaviour would
			// have extracted 1, 3, 10 and 36 times respectively.
			int accepted = index + 1;
			if (accepted == 1 || accepted == 2 || accepted == 4 || accepted == 8) {
				assertEquals(accepted, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"),
						"Exactly one new PDF crop is permitted per accepted region");
			}
		}
		assertEquals("Content parts: 8", lookup(robot, "#question-region-count", Label.class).getText());

		// These presentation-only operations must never repeat PDF extraction.
		fireControl(robot, "#question-content-move-up-7");
		assertEquals(8L, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"));
		fireControl(robot, "#question-content-remove-3");
		assertEquals(7, field(pane, "regionPreviewBox", VBox.class).getChildren().size());
		assertEquals(8L, countSuccessfulPreviewExtracts(csv, "capture.question.region.extract"));

		// The second recording category does not participate in an ordinary
		// Question-body-only sequence.
		assertEquals(0L, countSuccessfulPreviewExtracts(csv, "capture.question.shared-context.extract"));
	}

	private static final class PulseGapSampler extends AnimationTimer {

		private volatile long maxGapNanos;
		private long previousPulseNanos;

		@Override
		public void handle(long now) {
			if (previousPulseNanos != 0L) {
				maxGapNanos = Math.max(maxGapNanos, now - previousPulseNanos);
			}
			previousPulseNanos = now;
		}

		private double maxGapMillis() {

			// Zero means no paired pulse observations were obtained; it
			// must not be interpreted as a zero-latency UI.
			return maxGapNanos / 1_000_000.0;
		}
	}
}
