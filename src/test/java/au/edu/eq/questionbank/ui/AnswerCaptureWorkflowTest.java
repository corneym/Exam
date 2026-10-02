package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.SelectedPdf;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class AnswerCaptureWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void acceptedAnswerRegionBlocksWorkingSubjectChange(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "Q4");
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);

		// Accept one Answer region without saving it. This state must remain attached
		// to the current Working Subject until the Answer is saved or cancelled.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		assertTrue(answerCapturePane().hasAcceptedRegions());
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject chemistry = workingSubjectBox.getValue();
		Subject physics = workingSubjectBox.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();

		// Attempting to leave Chemistry must be rejected while unsaved Answer regions
		// remain in the active capture workflow.
		Platform.runLater(() -> workingSubjectBox.setValue(physics));

		// Wait for the actual JavaFX warning window rather than asking TestFX to
		// traverse
		// the scene graph while the modal dialog is still being constructed.
		waitForDialogShowing(robot, "Working Subject");
		DialogPane warningDialog = showingDialogPane(robot, "Working Subject");
		assertNotNull(warningDialog);
		assertEquals("Capture work is in progress", warningDialog.getHeaderText());

		// Fire the real DialogPane-owned OK button without TestFX scene-graph lookup.
		robot.interact(() -> ((Button) warningDialog.lookupButton(ButtonType.OK)).fire());
		WaitForAsyncUtils.waitForFxEvents();

		// The rejected change must leave the Answer target and its accepted regions
		// intact as well as restoring the visible Working Subject.
		assertEquals(chemistry, workingSubjectBox.getValue());
		assertTrue(answerCapturePane().hasAcceptedRegions());
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		assertNotNull(unansweredQuestions.getValue());
		assertEquals(question.getId(), unansweredQuestions.getValue().getId());
		assertEquals(chemistry, field(answerCapturePane(), "workingSubject", Subject.class));
	}

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
	void answerPdfFollowsBookletMappingWithinSameExam(FxRobot robot) throws Exception {
		BookletAnswerFixture fixture = createBookletAnswerFixture(robot);
		ComboBox<Question> questions = unansweredQuestions(robot);
		Label selectedPdf = lookup(robot, "#selected-answer-pdf", Label.class);
		Node pdfControls = field(answerCapturePane(), "answerPdfControls", Node.class);
		robot.interact(() -> questions.getSelectionModel().select(fixture.mcqQuestion()));
		WaitForAsyncUtils.waitForFxEvents();

		// The MCQ booklet resolves to Answer PDF A.
		assertEquals("Answers A", selectedPdf.getText());
		assertEquals(fixture.answersA().getId(), field(answerCapturePane(), "answerFile", AnswerFile.class).getId());
		robot.interact(() -> questions.getSelectionModel().select(fixture.paper1Question()));
		WaitForAsyncUtils.waitForFxEvents();

		// Paper 1 deliberately shares the same persisted AnswerFile.
		assertEquals("Answers A", selectedPdf.getText());
		assertEquals(fixture.answersA().getId(), field(answerCapturePane(), "answerFile", AnswerFile.class).getId());
		robot.interact(() -> questions.getSelectionModel().select(fixture.paper2Question()));
		WaitForAsyncUtils.waitForFxEvents();

		// Paper 2 belongs to the same Exam but must switch to its own mapped file.
		assertEquals("Answers B", selectedPdf.getText());
		assertEquals(fixture.answersB().getId(), field(answerCapturePane(), "answerFile", AnswerFile.class).getId());
		robot.interact(() -> questions.getSelectionModel().select(fixture.unmappedQuestion()));
		WaitForAsyncUtils.waitForFxEvents();

		// An unresolved booklet must not inherit Paper 2's file merely because both
		// Questions belong to the same Exam.
		assertNull(field(answerCapturePane(), "answerFile", AnswerFile.class));
		assertEquals("Choose an answer PDF", selectedPdf.getText());
		assertTrue(pdfControls.isVisible());
		assertTrue(pdfControls.isManaged());
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
		fireControl(robot, "#add-answer-region");
		CountDownLatch loading = new CountDownLatch(1);
		AtomicReference<Consumer<Throwable>> completeLoad = new AtomicReference<>();
		setField(answerCapturePane(), "answerPdfLoader",
				(BiConsumer<SelectedPdf, Consumer<Throwable>>) (_, callback) -> {
					completeLoad.set(callback);
					loading.countDown();
				});
		fireControl(robot, "#save-answer");
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
		fireControl(robot, "#add-answer-region");
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-answer-region");
		assertEquals("Regions: 2", lookup(robot, "#answer-region-count", Label.class).getText());
		Button firstRemoveButton = robot.lookup("Remove").queryButton();
		fireControl(robot, firstRemoveButton);
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		Button remainingRemoveButton = robot.lookup("Remove").queryButton();
		fireControl(robot, remainingRemoveButton);
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
		assertEquals("Saved Q1 (1 mark(s), 1 content part(s))", saveStatus.getText());
		assertEquals("", questionCode.getText());
		assertEquals("Content parts: 0", questionRegionCount.getText());
		assertEquals(1, unansweredQuestions.getItems().size());
		assertEquals(savedQuestion, unansweredQuestions.getItems().getFirst());
		robot.interact(() -> unansweredQuestions.getSelectionModel().select(savedQuestion));
		assertAnswerEntryControlsEnabled(robot);
		openAnswerPdfForTest(savedQuestion);
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		setField(answerCapturePane(), "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				throw new AssertionError("Saving an answer must not reload the bank");
			}
		});
		fireControl(robot, "#save-answer");
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
	void directAnswerCaptureCompletionRunsAfterSaveTransitionFinishes(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "DASH-A");
		AtomicBoolean callbackRan = new AtomicBoolean();
		AtomicBoolean saveInProgressAtCompletion = new AtomicBoolean();
		robot.interact(() -> assertTrue(answerCapturePane().captureAnswer(question, () -> {
			saveInProgressAtCompletion.set(answerCapturePane().isSaveInProgress());
			callbackRan.set(true);
		})));
		WaitForAsyncUtils.asyncFx(() -> invoke(answerCapturePane(), "saveAnswer",
				new Class<?>[] { Question.class, String.class }, question, "A")).get();

		// Dashboard return must occur after the complete Answer save transition rather
		// than merely after the SQLite write succeeds.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, callbackRan::get);
		assertFalse(saveInProgressAtCompletion.get());
		assertFalse(answerCapturePane().isSaveInProgress());
		assertTrue(new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow().hasAnswer());
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
	void existingMcqAnswerCanReceiveExplanationRegionWithoutChangingStoredChoice(FxRobot robot) throws Exception {
		BookletAnswerFixture fixture = createBookletAnswerFixture(robot);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);

		// Retrofit eligibility comes from persisted AnswerFile metadata and existing
		// authoritative A-D Answers.
		AnswerFile explanationFile = answerWriter.setContainsAnswerExplanations(fixture.answersA(), true);
		long originalAnswerId = answerWriter.insertAnswer(fixture.mcqQuestion(), "B", List.of()).getId();
		Question secondMcq = repository.save(fixture.mcqQuestion().getBooklet(), "2", "", 1,
				List.of(new QuestionRegion(fixture.mcqQuestion().getBooklet(), 1, 0.10, 0.35, 0.50, 0.20)),
				fixture.mcqQuestion().getClassification(), false, null, null, QuestionResponseType.MULTIPLE_CHOICE);
		answerWriter.insertAnswer(secondMcq, "C", List.of());

		// Create another eligible MCQ in the same Subject and Exam but a different
		// booklet. It proves that retrofit is scoped to the active Question booklet,
		// not merely to Working Subject or Exam.
		Exam exam = fixture.mcqQuestion().getExam();
		ExamBooklet otherMcqBooklet = new SqliteExamImporter(database, examWriter).importExam(exam.getSubject(),
				exam.getProvider().getName(), exam.getYear(), exam.getName(), "Other MCQ booklet",
				"Chemistry/2024/other-mcq.pdf", ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		Question otherBookletMcq = repository.save(otherMcqBooklet, "99", "", 1,
				List.of(new QuestionRegion(otherMcqBooklet, 1, 0.10, 0.10, 0.50, 0.20)),
				fixture.mcqQuestion().getClassification(), false, null, null, QuestionResponseType.MULTIPLE_CHOICE);
		answerWriter.assignAnswerFile(otherMcqBooklet, explanationFile);
		answerWriter.insertAnswer(otherBookletMcq, "D", List.of());

		// Make the MCQ booklet authoritative exactly as the Exam/Assets transition
		// does before entering Capture.
		robot.interact(() -> examMetadataPane().activateExistingBooklet(fixture.mcqQuestion().getBooklet(), examPdf));
		robot.interact(() -> refreshAnswerQuestionsForTest(repository.findAll()));
		WaitForAsyncUtils.waitForFxEvents();
		Button beginRetrofit = lookup(robot, "#capture-mcq-explanations", Button.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !beginRetrofit.isDisabled());
		robot.interact(beginRetrofit::fire);
		@SuppressWarnings("unchecked")
		ComboBox<Question> retrofitQuestions = lookup(robot, "#mcq-explanation-question", ComboBox.class);
		Node retrofitControls = retrofitQuestions.getParent();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> retrofitControls.isVisible() && retrofitQuestions.getItems().size() == 2);

		// Only answered MCQs from the active MCQ booklet are candidates.
		assertTrue(retrofitQuestions.getItems().stream()
				.anyMatch(question -> question.getId() == fixture.mcqQuestion().getId()));
		assertTrue(retrofitQuestions.getItems().stream().anyMatch(question -> question.getId() == secondMcq.getId()));
		assertTrue(retrofitQuestions.getItems().stream()
				.noneMatch(question -> question.getId() == otherBookletMcq.getId()));
		Question firstCandidate = retrofitQuestions.getItems().stream()
				.filter(question -> question.getId() == fixture.mcqQuestion().getId()).findFirst().orElseThrow();
		robot.interact(() -> retrofitQuestions.getSelectionModel().select(firstCandidate));
		WaitForAsyncUtils.waitForFxEvents();
		RadioButton answerB = lookup(robot, "#answer-choice-b", RadioButton.class);
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		assertTrue(answerB.isSelected());
		assertTrue(addRegion.isVisible());
		assertTrue(answerCapturePane().isEditingAnswer());

		// PDF dragging remains intentional because region geometry is the behaviour
		// under test.
		dragRegionOnDisplayedPage(robot);
		robot.interact(addRegion::fire);
		robot.interact(() -> lookup(robot, "#save-answer", Button.class).fire());
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findById(firstCandidate.getId()).filter(Question::hasAnswer)
						.map(question -> "B".equals(question.getAnswer().getAnswerText())
								&& question.getAnswer().getId() == originalAnswerId
								&& question.getAnswer().getRegions().size() == 1)
						.orElse(false));

		// A successful save consumes the first session candidate and immediately opens
		// the next one.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> retrofitQuestions.getItems().size() == 1 && retrofitQuestions.getValue() != null
						&& retrofitQuestions.getValue().getId() == secondMcq.getId()
						&& answerCapturePane().isEditingAnswer());
		assertTrue(retrofitQuestions.getItems().stream()
				.noneMatch(question -> question.getId() == firstCandidate.getId()));
		assertEquals(secondMcq.getId(), retrofitQuestions.getValue().getId());
		RadioButton answerC = lookup(robot, "#answer-choice-c", RadioButton.class);
		assertTrue(answerC.isSelected(), "Automatic advancement must restore the next candidate's stored letter");

		// Cancelling does not consume the candidate because no successful update was
		// persisted for it.
		Button cancel = lookup(robot, "#cancel-answer-edit", Button.class);
		robot.interact(cancel::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(retrofitQuestions.getItems().stream().anyMatch(question -> question.getId() == secondMcq.getId()));
		Button done = lookup(robot, "#finish-mcq-explanations", Button.class);
		robot.interact(done::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(retrofitControls.isVisible());
		assertTrue(beginRetrofit.isVisible());
	}

	@Test
	void failedAnswerWriteRetainsTheQuestionAndAcceptedRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "53");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		try (var connection = new SqliteDatabase(databasePath).openConnection();
				var statement = connection.createStatement()) {
			statement.execute(
					"CREATE TRIGGER reject_answer BEFORE INSERT ON answers BEGIN SELECT RAISE(ABORT, 'Test write failure'); END");
		}
		fireControl(robot, "#save-answer");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("Answer could not be saved.").tryQuery().isPresent());

		// Resolve the action through the actual DialogPane rather than rendered text.
		fireDialogButton(robot, "OK");
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

		// Selecting an Answer PDF is also the explicit booklet-to-AnswerFile mapping.
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(new SqliteDatabase(databasePath),
				new SqliteExamWriter(new SqliteDatabase(databasePath)));
		AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(question.getBooklet());
		assertNotNull(assignedAnswerFile);
		assertEquals("exam.pdf", assignedAnswerFile.getName());

		// The managed Answer PDF must have its byte identity persisted at selection
		// time, not only its filename and path.
		Path managedAnswerPath = pdfDataRoot.resolve(assignedAnswerFile.getSourceDocument().getRelativePath());
		String expectedHash = new SourceDocumentHashService().sha256(managedAnswerPath);
		assertEquals(expectedHash, assignedAnswerFile.getSourceDocument().getContentSha256());
		assertFalse(pdfControls.isVisible());
		assertFalse(pdfControls.isManaged());
	}

	@Test
	void multipleChoiceCanSaveChoiceWithOptionalExplanationRegion(FxRobot robot) throws Exception {
		BookletAnswerFixture fixture = createBookletAnswerFixture(robot);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);

		// Explanation capture is enabled by authoritative AnswerFile metadata rather
		// than by the Question, filename or temporary UI state.
		AnswerFile explanationFile = answerWriter.setContainsAnswerExplanations(fixture.answersA(), true);
		assertTrue(explanationFile.hasAnswerExplanations());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		List<Question> refreshedQuestions = repository.findAll();
		robot.interact(() -> refreshAnswerQuestionsForTest(refreshedQuestions));
		ComboBox<Question> questions = unansweredQuestions(robot);
		Question mcq = questions.getItems().stream()
				.filter(candidate -> candidate.getId() == fixture.mcqQuestion().getId()).findFirst().orElseThrow();
		robot.interact(() -> questions.getSelectionModel().select(mcq));
		WaitForAsyncUtils.waitForFxEvents();
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		RadioButton answerB = lookup(robot, "#answer-choice-b", RadioButton.class);
		Button save = lookup(robot, "#save-answer", Button.class);

		// A flagged MCQ keeps the normal A-D controls while also exposing optional
		// marking-PDF region capture.
		assertTrue(addRegion.isVisible());
		assertTrue(addRegion.isManaged());
		robot.interact(answerB::fire);
		assertTrue(answerB.isSelected());

		// PDF dragging is intentionally pointer-driven because region geometry itself
		// is the behaviour under test.
		dragRegionOnDisplayedPage(robot);
		robot.interact(addRegion::fire);
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		robot.interact(save::fire);

		// Persistence of both the A-D choice and region is the completion condition.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findById(mcq.getId()).filter(Question::hasAnswer)
						.map(candidate -> "B".equals(candidate.getAnswer().getAnswerText())
								&& candidate.getAnswer().getRegions().size() == 1)
						.orElse(false));
		Question stored = repository.findById(mcq.getId()).orElseThrow();
		assertEquals("B", stored.getAnswer().getAnswerText());
		assertEquals(1, stored.getAnswer().getRegions().size());
		assertEquals(explanationFile.getId(), stored.getAnswer().getRegions().getFirst().answerFile().getId());
	}

	@Test
	void questionListRefreshPreservesActiveAnswerEdit(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "59");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		fireControl(robot, "#save-answer");

		// The Question receives its Answer after the initial save completes.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, question::hasAnswer);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(question.hasAnswer());
		assertEquals(1, question.getAnswer().getRegions().size());
		long answerId = question.getAnswer().getId();
		AtomicInteger editCompleted = new AtomicInteger();

		// Completion gives the test an observable end-state for the later edit save.
		robot.interact(() -> assertTrue(answerCapturePane().editAnswer(question, editCompleted::incrementAndGet)));
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
		fireControl(robot, "#save-answer");

		// Do not use !isSaveInProgress() as the sole completion condition.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> editCompleted.get() == 1);
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
	void replacingActiveAnswerPdfReturnsRegionOnlyQuestionToAnswerQueue(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "ANSWER-REPLACE");
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		openAnswerPdfForTest(question);

		// Persist a region-only written Answer against the currently assigned
		// AnswerFile.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		fireControl(robot, "#save-answer");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question beforeReplacement = new SqliteQuestionRepository(new SqliteDatabase(databasePath))
				.findById(question.getId()).orElseThrow();
		assertTrue(beforeReplacement.hasAnswer());
		assertEquals(1, beforeReplacement.getAnswer().getRegions().size());
		Path replacementPdf = createReplacementAnswerPdf(databasePath.getParent().resolve("replacement-answer.pdf"));
		String replacementHash = new SourceDocumentHashService().sha256(replacementPdf);

		// Invoke the real application workflow with an explicit path so the test does
		// not automate a native FileChooser.
		Platform.runLater(() -> {
			try {
				invoke(application, "replaceActiveAnswerPdf",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Path.class }, primaryStage,
						applicationConfig, replacementPdf);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Replace Answer PDF");
		DialogPane confirmation = showingDialogPane(robot, "Replace Answer PDF");
		assertNotNull(confirmation);
		assertEquals("Existing Answer regions will be invalidated.", confirmation.getHeaderText());
		ButtonType replaceButton = confirmation.getButtonTypes().stream()
				.filter(buttonType -> "Replace Answer PDF".equals(buttonType.getText())).findFirst().orElseThrow();
		Node replaceNode = confirmation.lookupButton(replaceButton);
		assertTrue(replaceNode instanceof Button);

		// Fire the DialogPane-owned semantic action rather than relying on text lookup
		// or pointer hit-testing.
		robot.interact(((Button) replaceNode)::fire);
		waitForDialogShowing(robot, "Answer PDF Replaced");
		DialogPane success = showingDialogPane(robot, "Answer PDF Replaced");
		assertNotNull(success);
		Node okNode = success.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);
		robot.interact(((Button) okNode)::fire);
		waitForDialogHidden(robot, "Answer PDF Replaced");
		WaitForAsyncUtils.waitForFxEvents();
		Question reloaded = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertFalse(reloaded.hasAnswer());
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile assigned = answerWriter.findAnswerFile(question.getBooklet());
		assertNotNull(assigned);
		assertEquals(replacementHash, assigned.getSourceDocument().getContentSha256());

		// refreshAfterAnswerFileCorrection clears the stale locally-answered
		// suppression
		// state, so the invalidated written-response Question is immediately
		// actionable.
		assertTrue(unansweredQuestions(robot).getItems().stream()
				.anyMatch(candidate -> candidate.getId() == question.getId()));
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
		fireControl(robot, "#add-answer-region");
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
		fireControl(robot, "#save-answer");

		// Queue advancement proves that the save actually started and completed.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> questions.getValue() != null && questions.getValue().getId() == second.getId()
						&& questions.getItems().stream().noneMatch(question -> question.getId() == first.getId()));
		WaitForAsyncUtils.waitForFxEvents();
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(first.getId())
				.orElseThrow();
		assertTrue(stored.hasAnswer());
		assertTrue(questions.getItems().stream().noneMatch(question -> question.getId() == first.getId()));
		assertNotNull(questions.getValue());
		assertEquals(second.getId(), questions.getValue().getId());
		assertEquals(0, transitions.get(), "Programmatic queue removal must not fire the selection transition");
		assertSame(previousImage, lookup(robot, "#pdf-page-view", ImageView.class).getImage(),
				"The displayed answer PDF must not be re-rendered");
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertEquals("Regions: 0", lookup(robot, "#answer-region-count", Label.class).getText());
	}

	@Test
	void savedAnswersFollowBookletPdfMappingsAcrossQueue(FxRobot robot) throws Exception {
		BookletAnswerFixture fixture = createBookletAnswerFixture(robot);
		ComboBox<Question> questions = unansweredQuestions(robot);
		Label selectedPdf = lookup(robot, "#selected-answer-pdf", Label.class);
		RadioButton answerA = lookup(robot, "#answer-choice-a", RadioButton.class);
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		Button saveAnswer = lookup(robot, "#save-answer", Button.class);
		robot.interact(() -> questions.getSelectionModel().select(fixture.mcqQuestion()));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Answers A", selectedPdf.getText());

		// This test verifies Answer workflow state rather than mouse hit-testing.
		// Fire the actual JavaFX controls so headless CI and desktop exercise the same
		// application actions.
		robot.interact(answerA::fire);
		assertTrue(answerA.isSelected());
		assertFalse(saveAnswer.isDisabled());
		robot.interact(saveAnswer::fire);

		// Wait for the observable workflow result, not merely for saveInProgress to be
		// false. A "not saving" condition is also true if a click never started a save.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress()
				&& questions.getValue() != null && questions.getValue().getId() == fixture.paper1Question().getId());
		WaitForAsyncUtils.waitForFxEvents();

		// Source ordering advances from the MCQ booklet to Paper 1. Because both
		// booklets map to Answers A, the Answer workflow remains on that source.
		assertEquals(fixture.paper1Question().getId(), questions.getValue().getId());
		assertEquals("Answers A", selectedPdf.getText());
		assertEquals(fixture.answersA().getId(), field(answerCapturePane(), "answerFile", AnswerFile.class).getId());

		// Paper 1 is written response, so capture its region from Answers A.
		dragRegionOnDisplayedPage(robot);
		robot.interact(addRegion::fire);
		assertTrue(answerCapturePane().hasAcceptedRegions());
		robot.interact(saveAnswer::fire);

		// Again wait for the actual queue transition so the test cannot pass a wait
		// merely because the asynchronous save failed to start.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress()
				&& questions.getValue() != null && questions.getValue().getId() == fixture.paper2Question().getId());
		WaitForAsyncUtils.waitForFxEvents();

		// The next Question is Paper 2. It belongs to the same Exam but has a different
		// booklet mapping, so the workflow must automatically switch to Answers B.
		assertEquals(fixture.paper2Question().getId(), questions.getValue().getId());
		assertEquals("Answers B", selectedPdf.getText());
		assertEquals(fixture.answersB().getId(), field(answerCapturePane(), "answerFile", AnswerFile.class).getId());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question storedMcq = repository.findById(fixture.mcqQuestion().getId()).orElseThrow();
		Question storedPaper1 = repository.findById(fixture.paper1Question().getId()).orElseThrow();

		// Confirm both preceding Answers were committed before the automatic PDF
		// transition advanced the queue.
		assertEquals("A", storedMcq.getAnswer().getAnswerText());
		assertEquals(1, storedPaper1.getAnswer().getRegions().size());
		assertEquals(fixture.answersA().getId(), storedPaper1.getAnswer().getRegions().getFirst().answerFile().getId());
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

	private BookletAnswerFixture createBookletAnswerFixture(FxRobot robot) throws Exception {

		// Establish the Exam and a valid selected curriculum classification through the
		// ordinary application workflow before adding the additional booklets directly.
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Setup",
				ExamBookletQuestionFormat.MIXED);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		Subject chemistry = examMetadataPane().getBooklet().getExam().getSubject();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();

		// Model the actual assessment structure: MCQ, Paper 1 and Paper 2 are separate
		// source booklets belonging to the same Exam.
		ExamBooklet mcqBooklet = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment", "MCQ booklet",
				"Chemistry/2024/mcq.pdf", ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		ExamBooklet paper1 = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment", "Paper 1",
				"Chemistry/2024/paper1.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		ExamBooklet paper2 = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment", "Paper 2",
				"Chemistry/2024/paper2.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		ExamBooklet unmappedBooklet = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment",
				"Unmapped paper", "Chemistry/2024/unmapped.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		Question mcqQuestion = questionRepository.save(mcqBooklet, "1", "", 1,
				List.of(new QuestionRegion(mcqBooklet, 1, 0.10, 0.10, 0.50, 0.20)), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		Question paper1Question = questionRepository.save(paper1, "1", "", 2,
				List.of(new QuestionRegion(paper1, 1, 0.10, 0.10, 0.50, 0.20)), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question paper2Question = questionRepository.save(paper2, "1", "", 2,
				List.of(new QuestionRegion(paper2, 1, 0.10, 0.10, 0.50, 0.20)), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question unmappedQuestion = questionRepository.save(unmappedBooklet, "1", "", 2,
				List.of(new QuestionRegion(unmappedBooklet, 1, 0.10, 0.10, 0.50, 0.20)), classification, false, null,
				null, QuestionResponseType.WRITTEN_RESPONSE);

		// The mapped PDFs must physically exist because AnswerCapturePane validates the
		// persisted managed path before opening a registered AnswerFile.
		Path answersAPath = pdfDataRoot.resolve("answers-a.pdf");
		Path answersBPath = pdfDataRoot.resolve("answers-b.pdf");
		Files.copy(examPdf, answersAPath);
		Files.copy(examPdf, answersBPath);
		String answersARelativePath = pdfDataRoot.relativize(answersAPath).toString().replace('\\', '/');
		String answersBRelativePath = pdfDataRoot.relativize(answersBPath).toString().replace('\\', '/');

		// MCQ and Paper 1 deliberately share one AnswerFile. The booklet-aware writer
		// reuses the first file while persisting a mapping for each booklet.
		AnswerFile answersA = answerWriter.findOrCreateAnswerFile(mcqBooklet, "Answers A", answersARelativePath);
		answerWriter.findOrCreateAnswerFile(paper1, "Answers A", answersARelativePath);

		// Paper 2 has a different answer document. The fourth booklet intentionally
		// remains unresolved to prove that same-Exam state is never reused by accident.
		AnswerFile answersB = answerWriter.findOrCreateAnswerFile(paper2, "Answers B", answersBRelativePath);
		robot.interact(() -> refreshAnswerQuestionsForTest(questionRepository.findAll()));
		WaitForAsyncUtils.waitForFxEvents();
		return new BookletAnswerFixture(mcqQuestion, paper1Question, paper2Question, unmappedQuestion, answersA,
				answersB);
	}

	private Path createReplacementAnswerPdf(Path path) throws Exception {
		try (PDDocument document = new PDDocument()) {

			// Three pages ensure a valid PDF whose bytes differ from the normal two-page
			// workflow fixture.
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		return path;
	}

	// Bundles the three-booklet Answer-PDF arrangement exercised by the workflow
	// regressions: MCQ and Paper 1 share A, while Paper 2 uses B.
	private record BookletAnswerFixture(Question mcqQuestion, Question paper1Question, Question paper2Question,
			Question unmappedQuestion, AnswerFile answersA, AnswerFile answersB) {
	}
}
