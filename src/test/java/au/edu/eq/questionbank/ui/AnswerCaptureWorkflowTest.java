package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import au.edu.eq.questionbank.ui.pdf.SelectedPdf;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class AnswerCaptureWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void answerEditCompletionRunsAfterSaveTransitionFinishes(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "58");
		WaitForAsyncUtils.asyncFx(() -> invoke(answerCapturePane(), "saveAnswer",
				new Class<?>[] { Question.class, String.class }, question, "A")).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		AtomicBoolean callbackRan = new AtomicBoolean();
		AtomicBoolean saveInProgressAtCompletion = new AtomicBoolean();
		robot.interact(() -> assertTrue(answerCapturePane().editAnswer(question, () -> {
			saveInProgressAtCompletion.set(answerCapturePane().isSaveInProgress());
			callbackRan.set(true);
		})));
		WaitForAsyncUtils.asyncFx(() -> invoke(answerCapturePane(), "saveAnswer",
				new Class<?>[] { Question.class, String.class }, question, "B")).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, callbackRan::get);
		assertFalse(saveInProgressAtCompletion.get(),
				"Answer edit completion must run after the save-in-progress state is cleared");
		assertFalse(answerCapturePane().isSaveInProgress());
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertEquals("B", stored.getAnswer().getAnswerText());
	}

	@Test
	void answerEditOpensFirstStoredRegionPage(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "ANSWER-PAGE2");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);

		// Capture the persisted Answer region on page 2.
		robot.interact(() -> pdfWorkspace().showPage(PdfWorkspacePane.DocumentMode.ANSWER, 2));
		assertEquals(2, pdfWorkspace().getCurrentPageNumber());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(question.hasAnswer());
		assertEquals(1, question.getAnswer().getRegions().size());
		assertEquals(2, question.getAnswer().getRegions().getFirst().pageNumber());

		// Move away from the stored source page before starting the edit so the test
		// proves that editAnswer performs the page navigation itself.
		robot.interact(() -> pdfWorkspace().showPage(PdfWorkspacePane.DocumentMode.ANSWER, 1));
		assertEquals(1, pdfWorkspace().getCurrentPageNumber());
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> assertTrue(answerCapturePane().editAnswer(question, completed::incrementAndGet)));
		WaitForAsyncUtils.waitForFxEvents();

		// Editing should restore the registered Answer PDF and position it at the
		// first persisted Answer region.
		assertEquals(PdfWorkspacePane.DocumentMode.ANSWER, pdfWorkspace().getDisplayedDocument());
		assertEquals(2, pdfWorkspace().getCurrentPageNumber());
		assertEquals(0, completed.get());
		robot.clickOn("#cancel-answer-edit");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, completed.get());
	}

	@Test
	void answerRemainsSavedWhenNextPdfLoadingFailsAndFxEventsKeepRunning(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question first = captureQuestion(robot, "51");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		Question second = captureQuestion(robot, "52");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(first));
		openAnswerPdfForTest(first);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		CountDownLatch loading = new CountDownLatch(1);
		AtomicReference<Consumer<Throwable>> completeLoad = new AtomicReference<>();
		setField(answerCapturePane(), "answerPdfLoader",
				(BiConsumer<SelectedPdf, Consumer<Throwable>>) (_, callback) -> {
					completeLoad.set(callback);
					loading.countDown();
				});
		robot.clickOn("#save-answer");
		assertTrue(loading.await(10, TimeUnit.SECONDS));
		CountDownLatch pulse = new CountDownLatch(1);
		Platform.runLater(pulse::countDown);
		assertTrue(pulse.await(2, TimeUnit.SECONDS));
		robot.interact(() -> lookup(robot, "#next-pdf-page", Button.class).fire());
		assertEquals(2, field(pdfWorkspace(), "currentPageNumber", Integer.class));
		assertEquals(second.getId(), questions.getValue().getId());
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(first.getId())
				.orElseThrow();
		assertTrue(stored.hasAnswer(), "PDF loading follows the committed transaction");
		Platform.runLater(() -> completeLoad.get().accept(new IOException("Simulated next-PDF failure")));
		AtomicReference<DialogPane> failureDialog = new AtomicReference<>();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			DialogPane dialog = robot.lookup(".dialog-pane").queryAll().stream().filter(DialogPane.class::isInstance)
					.map(DialogPane.class::cast).filter(DialogPane::isVisible)
					.filter(candidate -> "Answer saved, but the next question's PDF could not be loaded."
							.equals(candidate.getHeaderText()))
					.findFirst().orElse(null);
			failureDialog.set(dialog);
			return dialog != null;
		});
		robot.interact(() -> ((Button) failureDialog.get().lookupButton(ButtonType.OK)).fire());
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(answerCapturePane().isSaveInProgress());
		assertTrue(questions.getItems().stream().noneMatch(question -> question.getId() == first.getId()));
		assertEquals("Regions: 0", lookup(robot, "#answer-region-count", Label.class).getText());
	}

	@Test
	void canRemoveAcceptedAnswerRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q3");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		assertEquals("Regions: 2", lookup(robot, "#answer-region-count", Label.class).getText());
		Button firstRemoveButton = robot.lookup("Remove").queryButton();
		robot.clickOn(firstRemoveButton);
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		Button remainingRemoveButton = robot.lookup("Remove").queryButton();
		robot.clickOn(remainingRemoveButton);
		assertEquals("Regions: 0", lookup(robot, "#answer-region-count", Label.class).getText());
	}

	@Test
	void capturesQuestionThenSavesRegionAnswer(FxRobot robot) throws Exception {
		assertInitialAnswerControlsDisabled(robot);
		prepareExamAndClassification(robot);
		Question savedQuestion = captureQuestion(robot, "Q1");
		assertEquals(CurriculumLevel.DESCRIPTOR, savedQuestion.getClassification().getLevel());
		assertFalse(savedQuestion.hasSourceQuestion());
		Label saveStatus = lookup(robot, "#question-save-status", Label.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Label questionRegionCount = lookup(robot, "#question-region-count", Label.class);
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		assertEquals("Saved Q1 (1 mark(s), 1 region(s))", saveStatus.getText());
		assertEquals("", questionCode.getText());
		assertEquals("Regions: 0", questionRegionCount.getText());
		assertEquals(1, unansweredQuestions.getItems().size());
		assertEquals(savedQuestion, unansweredQuestions.getItems().getFirst());
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(savedQuestion));
		assertAnswerEntryControlsEnabled(robot);
		openAnswerPdfForTest(savedQuestion);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		setField(answerCapturePane(), "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				throw new AssertionError("Saving an answer must not reload the bank");
			}
		});
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(savedQuestion.hasAnswer());
		assertNull(savedQuestion.getAnswer().getAnswerText());
		assertEquals(1, savedQuestion.getAnswer().getRegions().size());
		assertTrue(unansweredQuestions.getItems().isEmpty());
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertNull(unansweredQuestions.getValue());
	}

	@Test
	void editingAnswerCompletesWithoutReloadingTheQuestionBank(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "54");
		WaitForAsyncUtils.asyncFx(() -> invoke(answerCapturePane(), "saveAnswer",
				new Class<?>[] { Question.class, String.class }, question, "A")).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		long answerId = question.getAnswer().getId();
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> assertTrue(answerCapturePane().editAnswer(question, completed::incrementAndGet)));
		setField(answerCapturePane(), "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				throw new AssertionError("Updating an answer must not reload the bank");
			}
		});
		WaitForAsyncUtils.asyncFx(() -> invoke(answerCapturePane(), "saveAnswer",
				new Class<?>[] { Question.class, String.class }, question, "B")).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		assertEquals(1, completed.get());
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertEquals(answerId, stored.getAnswer().getId());
		assertEquals("B", stored.getAnswer().getAnswerText());
		assertTrue(unansweredQuestions(robot).getItems().isEmpty());
	}

	@Test
	void failedAnswerWriteRetainsTheQuestionAndAcceptedRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "53");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		try (var connection = new SqliteDatabase(databasePath).openConnection();
				var statement = connection.createStatement()) {
			statement.execute(
					"CREATE TRIGGER reject_answer BEFORE INSERT ON answers BEGIN SELECT RAISE(ABORT, 'Test write failure'); END");
		}
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("Answer could not be saved.").tryQuery().isPresent());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot.lookup("OK").tryQuery().isPresent());
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(answerCapturePane().isSaveInProgress());
		assertEquals(question.getId(), questions.getValue().getId());
		assertEquals(1, questions.getItems().size());
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		assertFalse(new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow().hasAnswer());
	}

	@Test
	void hidesAnswerPdfControlsWhenAnswerPdfIsKnown(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "56");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		Node pdfControls = field(answerCapturePane(), "answerPdfControls", Node.class);
		assertTrue(pdfControls.isVisible());
		assertTrue(pdfControls.isManaged());
		openAnswerPdfForTest(question);
		WaitForAsyncUtils.waitForFxEvents();

		// Visibility and layout participation are controlled by the containing HBox.
		assertFalse(pdfControls.isVisible());
		assertFalse(pdfControls.isManaged());
	}

	@Test
	void questionListRefreshPreservesActiveAnswerEdit(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "59");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(question.hasAnswer());
		assertEquals(1, question.getAnswer().getRegions().size());
		long answerId = question.getAnswer().getId();
		robot.interact(() -> assertTrue(answerCapturePane().editAnswer(question, () -> {
		})));
		assertNotNull(questions.getValue());
		assertEquals(question.getId(), questions.getValue().getId());
		assertTrue(questions.isDisable());
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		assertFalse(lookup(robot, "#save-answer", Button.class).isDisabled());
		List<Question> refreshedQuestions = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll();
		robot.interact(() -> refreshAnswerQuestionsForTest(refreshedQuestions));
		assertNotNull(questions.getValue(),
				"Refreshing the unanswered queue must not clear the active Answer-edit target");
		assertEquals(question.getId(), questions.getValue().getId());
		assertTrue(questions.isDisable(), "The Answer selector must remain locked while editing");
		assertTrue(questions.getItems().stream().noneMatch(candidate -> candidate.getId() == question.getId()),
				"The answered edit target must not be added to the unanswered queue");
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText(),
				"Refreshing Questions must not discard loaded Answer regions");
		assertFalse(lookup(robot, "#save-answer", Button.class).isDisabled());
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertTrue(stored.hasAnswer());
		assertEquals(answerId, stored.getAnswer().getId());
		assertEquals(1, stored.getAnswer().getRegions().size());
	}

	@Test
	void questionRefreshDoesNotRestoreAnAnswerSavedSinceSnapshot(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "30");
		List<Question> oldSnapshot = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll();
		AnswerCapturePane answers = field(application, "answerCapturePane", AnswerCapturePane.class);
		robot.interact(() -> {
			try {
				invoke(answers, "saveAnswer", new Class<?>[] { Question.class, String.class }, question, "A");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answers.isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> refreshAnswerQuestionsForTest(oldSnapshot));
		assertTrue(unansweredQuestions(robot).getItems().isEmpty());
	}

	@Test
	void savedAnswerLeavesQueueAndSelectsNextQuestion(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question first = captureQuestion(robot, "A1");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		Question second = captureQuestion(robot, "A2");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(first));
		Label selectedQuestion = lookup(robot, "#selected-answer-question", Label.class);
		assertTrue(selectedQuestion.getText().contains("1 mark"));
		openAnswerPdfForTest(first);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		javafx.scene.image.Image previousImage = lookup(robot, "#pdf-page-view", ImageView.class).getImage();
		AtomicInteger transitions = new AtomicInteger();
		setField(answerCapturePane(), "answerTransitionAllowed", (java.util.function.BooleanSupplier) () -> {
			transitions.incrementAndGet();
			return true;
		});
		setField(answerCapturePane(), "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				throw new AssertionError("Saving an answer must not reload the bank");
			}
		});
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(first.getId())
				.orElseThrow();
		assertTrue(stored.hasAnswer());
		assertTrue(questions.getItems().stream().noneMatch(question -> question.getId() == first.getId()));
		assertNotNull(questions.getValue());
		assertEquals(second.getId(), questions.getValue().getId());
		assertEquals(0, transitions.get(), "Programmatic queue removal must not fire the selection transition");
		org.junit.jupiter.api.Assertions.assertSame(previousImage,
				lookup(robot, "#pdf-page-view", ImageView.class).getImage(),
				"The displayed answer PDF must not be re-rendered");
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertEquals("Regions: 0", lookup(robot, "#answer-region-count", Label.class).getText());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	private void assertAnswerEntryControlsEnabled(FxRobot robot) {
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertFalse(lookup(robot, "#choose-answer-pdf", Button.class).isDisabled());
	}

	private void assertInitialAnswerControlsDisabled(FxRobot robot) {
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertTrue(lookup(robot, "#choose-answer-pdf", Button.class).isDisabled());
		assertTrue(lookup(robot, "#add-answer-region", Button.class).isDisabled());
		assertTrue(lookup(robot, "#clear-answer-selection", Button.class).isDisabled());
	}
}
