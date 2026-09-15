package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class QuestionBankApplicationWorkflowTest {

	private QuestionBankApplication application;
	private ApplicationConfig applicationConfig;
	private Stage primaryStage;
	private Path examPdf;
	private Path pdfDataRoot;
	private Path databasePath;

	private static <T> T field(Object owner, String fieldName, Class<T> type) throws Exception {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return type.cast(field.get(owner));
	}

	private static void fireMouseEvent(ImageView pageView, javafx.event.EventType<MouseEvent> eventType, double x,
			double y, boolean primaryButtonDown) {
		Event.fireEvent(pageView, new MouseEvent(eventType, x, y, x, y, MouseButton.PRIMARY, 1, false, false, false,
				false, primaryButtonDown, false, false, false, false, false, null));
	}

	private static Object invoke(Object owner, String methodName, Class<?>[] parameterTypes, Object... arguments)
			throws Exception {
		Method method = owner.getClass().getDeclaredMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return method.invoke(owner, arguments);
	}

	private static <T extends Node> T lookup(FxRobot robot, String selector, Class<T> type) {
		return robot.lookup(selector).queryAs(type);
	}

	private static void setField(Object owner, String fieldName, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(owner, value);
	}

	@Test
	void acceptedQuestionRegionDoesNotLockNewQuestionNumber(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(marks).write("2");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertFalse(questionCode.isDisable(), "Accepted regions must not lock the question number for a new question");
		robot.clickOn(questionCode).write("27");
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitForFxEvents();
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().stream()
				.filter(question -> "27".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertEquals(1, restored.getRegions().size());
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
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot
				.lookup("Answer saved, but the next question's PDF could not be loaded.").tryQuery().isPresent());
		robot.clickOn("OK");
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
	void canSelectAndPersistHistoricalSyllabusClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		assertEquals(2, syllabuses.getItems().size());
		assertNotNull(syllabuses.getValue());
		assertEquals("2025", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("1", units.getItems().getFirst().getCode());
		assertTrue(subtopics.getItems().isEmpty());
		assertNotNull(descriptors.getValue());
		assertEquals("2025", model.getClassification().getSyllabusVersion().getName());
		SyllabusVersion historicalSelection = selectSyllabus(robot, "2019");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("2019", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("3", units.getItems().getFirst().getCode());
		assertTrue(topics.getItems().isEmpty());
		assertTrue(subtopics.getItems().isEmpty());
		assertTrue(descriptors.getItems().isEmpty());
		assertFalse(units.isDisabled());
		assertTrue(topics.isDisabled());
		assertTrue(subtopics.isDisabled());
		assertTrue(descriptors.isDisabled());
		assertNull(units.getValue());
		assertNull(topics.getValue());
		assertNull(subtopics.getValue());
		assertNull(descriptors.getValue());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumNode historicalClassification = model.getClassification();
		assertNotNull(historicalClassification);
		assertEquals("3.1.1", historicalClassification.getCode());
		assertEquals(historicalSelection, historicalClassification.getSyllabusVersion());
		Question question = captureQuestion(robot, "H1");
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertEquals(historicalClassification.getId(), restored.getClassification().getId());
		assertEquals(historicalSelection, restored.getClassification().getSyllabusVersion());
		assertFalse(restored.getClassification().getSyllabusVersion().isCurrent());
		assertEquals(2024, restored.getExam().getYear());
		assertEquals(historicalSelection, syllabuses.getValue());
		assertEquals(historicalSelection, model.getSyllabusVersion());
		assertNull(model.getClassification());
		assertFalse(units.isDisabled());
	}

	@Test
	void canSelectSubjectWithOnlyHistoricalSyllabus(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		selectSubject(robot, "Biology");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Biology", subjects.getValue().getName());
		assertNull(examMetadataPane().getBooklet());
		assertEquals(1, syllabuses.getItems().size());
		assertEquals("2019", syllabuses.getItems().getFirst().getName());
		assertNull(syllabuses.getValue());
		assertFalse(syllabuses.isDisabled());
		assertTrue(units.getItems().isEmpty());
		assertTrue(units.isDisabled());
		SyllabusVersion historical = syllabuses.getItems().getFirst();
		robot.interact(() -> syllabuses.setValue(historical));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(historical, syllabuses.getValue());
		assertEquals(1, units.getItems().size());
		assertEquals("2", units.getItems().getFirst().getCode());
		assertFalse(units.isDisabled());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		assertEquals(historical, model.getClassification().getSyllabusVersion());
		assertEquals("2.1.1", model.getClassification().getCode());
	}

	@Test
	void capturesMultipartQuestionWithSharedPreamble(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);
		/*
		 * Deliberately draw first. The eventual multipart intent must be allowed to
		 * reinterpret this pending rectangle as the preamble.
		 */
		dragRegionOnDisplayedPage(robot);
		assertTrue(save.isDisabled());
		robot.clickOn(questionCode).write("24a");
		robot.clickOn(marks).write("2");
		CheckBox preamble = lookup(robot, "#first-region-shared-preamble", CheckBox.class);
		assertTrue(preamble.isVisible());
		assertFalse(preamble.isSelected());
		robot.clickOn(preamble);
		assertTrue(preamble.isSelected());
		assertTrue(save.isDisabled());
		/*
		 * The already-drawn rectangle is now accepted as the shared preamble rather
		 * than as an ordinary question region.
		 */
		robot.clickOn("#add-question-region");
		assertEquals("Regions: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(save.isDisabled());
		/*
		 * Now capture the actual 24a question region.
		 */
		dragRegionOnDisplayedPage(robot);
		assertTrue(save.isDisabled());
		robot.clickOn("#add-question-region");
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().stream()
				.filter(question -> "24a".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertTrue(restored.hasSourceQuestion());
		assertEquals("24", restored.getSourceQuestion().getSourceQuestionCode());
		assertEquals(PreambleStatus.PRESENT, restored.getSourceQuestion().getPreambleStatus());
		assertTrue(restored.hasSharedContext());
		assertEquals(1, restored.getSharedContext().getRegions().size());
		assertEquals(1, restored.getRegions().size());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		Question secondPart = captureQuestion(robot, "24b");
		Question restoredSecondPart = new SqliteQuestionRepository(new SqliteDatabase(databasePath))
				.findById(secondPart.getId()).orElseThrow();
		assertTrue(restoredSecondPart.hasSourceQuestion());
		assertTrue(restoredSecondPart.hasSharedContext());
		assertEquals(restored.getSourceQuestion().getId(), restoredSecondPart.getSourceQuestion().getId());
		assertEquals(restored.getSharedContext().getId(), restoredSecondPart.getSharedContext().getId());
		assertEquals(1, new SqliteSharedQuestionContextRepository(new SqliteDatabase(databasePath))
				.findByBooklet(restored.getBooklet()).size());
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
	void capturesQuestionWithSubtopicClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Physics");
		Question savedQuestion = captureQuestion(robot, "P1");
		assertEquals("Physics", savedQuestion.getExam().getSubject().getName());
		assertEquals(CurriculumLevel.SUBTOPIC, savedQuestion.getClassification().getLevel());
		assertEquals(savedQuestion.getExam().getSubject(),
				savedQuestion.getClassification().getSyllabusVersion().getSubject());
	}

	@Test
	void changingSubjectInvalidatesPreviouslySetExamMetadata(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		assertNotNull(examMetadataPane().getBooklet());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		selectSubject(robot, "Physics");
		WaitForAsyncUtils.waitForFxEvents();
		assertNull(examMetadataPane().getBooklet());
		assertEquals("Physics", subjects.getValue().getName());
	}

	@Test
	void clearingSyllabusThenSubjectClearsDependentControls(FxRobot robot) throws Exception {
		selectSubject(robot, "Chemistry");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		robot.interact(() -> syllabuses.getSelectionModel().clearSelection());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertNull(syllabuses.getValue());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertFalse(syllabuses.isDisabled());
		assertEquals(2, syllabuses.getItems().size());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, subtopics, descriptors)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
		selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		robot.interact(() -> subjects.getSelectionModel().clearSelection());
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertNull(syllabuses.getValue());
		assertTrue(syllabuses.getItems().isEmpty());
		assertTrue(syllabuses.isDisabled());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, subtopics, descriptors)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
	}

	@Test
	void committedQuestionIsNotReportedAsUnsavedWhenRefreshFails(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.clickOn(lookup(robot, "#question-code", TextField.class)).write("29");
		robot.clickOn(lookup(robot, "#question-marks", TextField.class)).write("1");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
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
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Question saved; lists could not be fully refreshed.").tryQuery().isPresent());
		assertEquals(1, stored.findAll().size());
		robot.clickOn("OK");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		assertEquals("", lookup(robot, "#question-code", TextField.class).getText());
		assertTrue(lookup(robot, "#question-save-status", Label.class).getText().contains("list refresh failed"));
		assertEquals(1, unansweredQuestions(robot).getItems().size());
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
		robot.clickOn(questionCodeField).write("Q7");
		robot.clickOn(marksField).write("1");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitForFxEvents();
		Button okButton = robot.lookup("OK").queryButton();
		robot.clickOn(okButton);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Q7", questionCodeField.getText());
		assertEquals("1", marksField.getText());
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		long matchingQuestions = repository.findAll().stream()
				.filter(question -> question.getQuestionCode().equals("Q7")).count();
		assertEquals(1, matchingQuestions);
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
	void exportMenuContainsRevisionHtmlCommand(FxRobot robot) {
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		MenuItem exportItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "export-revision-html".equals(item.getId())).findFirst().orElseThrow();
		assertEquals("_Revision HTML...", exportItem.getText());
		assertFalse(exportItem.isDisable());
	}

	@Test
	void exportMenuContainsRevisionScormCommand(FxRobot robot) {
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		MenuItem exportItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "export-revision-scorm".equals(item.getId())).findFirst().orElseThrow();
		assertEquals("Revision _SCORM...", exportItem.getText());
		assertFalse(exportItem.isDisable());
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
		robot.clickOn("OK");
		assertFalse(answerCapturePane().isSaveInProgress());
		assertEquals(question.getId(), questions.getValue().getId());
		assertEquals(1, questions.getItems().size());
		assertEquals("Regions: 1", lookup(robot, "#answer-region-count", Label.class).getText());
		assertFalse(new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow().hasAnswer());
	}

	@Test
	void failedScormExportRestoresMenu(FxRobot robot) throws Exception {
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		Subject chemistry = model.getSubjects().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		Path exportParent = Files.createTempDirectory("scorm-ui-failure-");
		Path destination = exportParent.resolve("not-a-zip.txt");
		MenuItem exportItem = field(application, "scormExportMenuItem", MenuItem.class);
		AtomicBoolean disabledWhileStarting = new AtomicBoolean();
		robot.interact(() -> {
			try {
				invoke(application, "startScormExport",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class },
						primaryStage, applicationConfig, chemistry, destination);
				disabledWhileStarting.set(exportItem.isDisable());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertTrue(disabledWhileStarting.get());
		WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
				() -> robot.lookup("OK").tryQuery().isPresent());
		robot.clickOn("OK");
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(Files.exists(destination));
		assertFalse(field(application, "scormExportRunning", Boolean.class).booleanValue());
		assertFalse(exportItem.isDisable());
	}

	@Test
	void fileExitCreatesAutomaticBackupAndRequestsApplicationExit(FxRobot robot) throws Exception {
		AtomicInteger exitCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		assertEquals(0, automaticBackupCount());
		MenuItem exitItem = fileExitMenuItem();
		robot.interact(exitItem::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, exitCount.get());
		assertEquals(1, automaticBackupCount());
	}

	@Test
	void fileExitIsBlockedWhileQuestionSaveIsInProgress(FxRobot robot) throws Exception {
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AtomicInteger exitCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		assertEquals(0, automaticBackupCount());
		setField(pane, "questionSaveInProgress", true);
		try {
			MenuItem exitItem = fileExitMenuItem();
			Platform.runLater(exitItem::fire);
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> robot.lookup("Save in progress").tryQuery().isPresent());
			assertEquals(0, exitCount.get());
			assertEquals(0, automaticBackupCount());
			robot.clickOn("OK");
		} finally {
			setField(pane, "questionSaveInProgress", false);
		}
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
	void importedCaptureAdvancesUsingTheBackgroundSnapshot(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = field(application, "examMetadataPane", ExamMetadataPane.class).getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question first = stored.save(booklet, "41", "", 1, List.of(), classification, false, null, null);
		Question second = stored.save(booklet, "42", "", 1, List.of(), classification, false, null, null);
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		robot.interact(pane::showImportedQuestionCapture);
		ComboBox<Question> imported = comboBox(robot, "#imported-question");
		robot.interact(() -> imported.getSelectionModel().selectFirst());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		AtomicInteger reads = new AtomicInteger();
		setField(pane, "questionRepository", new InMemoryQuestionRepository() {

			@Override
			public List<Question> findAll() {
				assertFalse(Platform.isFxApplicationThread(), "Advancing the queue must reuse the loaded questions");
				reads.incrementAndGet();
				return stored.findAll();
			}
		});
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(second.getId(), imported.getValue().getId());
		assertEquals(1, imported.getItems().size());
		assertEquals(1, stored.findById(first.getId()).orElseThrow().getRegions().size());
		assertEquals(2, reads.get());
		assertEquals(2, unansweredQuestions(robot).getItems().size());
	}

	@Test
	void importedExamPdfUsesSubjectProviderYearHierarchy(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Path expectedPath = pdfDataRoot.resolve("Chemistry").resolve("QCAA").resolve("2024")
				.resolve(examPdf.getFileName());
		assertTrue(Files.isRegularFile(expectedPath));
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		assertEquals(pdfDataRoot.relativize(expectedPath).toString(), booklet.getSourceDocument().getRelativePath());
	}

	@Test
	void legacyQuestionControlsAreHiddenDuringNormalCapture(FxRobot robot) {
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
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
	void multipartQuestionAutomaticallyCreatesSourceQuestion(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question saved = captureQuestion(robot, "24a");
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(saved.getId())
				.orElseThrow();
		assertTrue(restored.hasSourceQuestion());
		assertEquals("24", restored.getSourceQuestion().getSourceQuestionCode());
	}

	@Test
	void multipartQuestionWithoutPreambleRecordsNone(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(questionCode).write("25a");
		robot.clickOn(marks).write("1");
		CheckBox preamble = lookup(robot, "#first-region-shared-preamble", CheckBox.class);
		assertTrue(preamble.isVisible());
		assertFalse(preamble.isSelected());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitForFxEvents();
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().stream()
				.filter(question -> "25a".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertTrue(restored.hasSourceQuestion());
		assertEquals(PreambleStatus.NONE, restored.getSourceQuestion().getPreambleStatus());
		assertFalse(restored.hasSharedContext());
	}

	@Test
	void questionCaptureModesCanBeSelectedFromPaneAndMenu(FxRobot robot) {
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		MenuItem captureNewItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "capture-new-questions".equals(item.getId())).findFirst().orElseThrow();
		MenuItem captureImportedItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "capture-imported-questions".equals(item.getId())).findFirst().orElseThrow();
		ToggleButton newMode = lookup(robot, "#capture-mode-new", ToggleButton.class);
		ToggleButton importedMode = lookup(robot, "#capture-mode-imported", ToggleButton.class);
		Node legacyControls = lookup(robot, "#legacy-question-capture", Node.class);
		assertEquals("Capture _New Questions", captureNewItem.getText());
		assertEquals("Capture _Imported Questions", captureImportedItem.getText());
		assertTrue(newMode.isSelected());
		assertFalse(importedMode.isSelected());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
		robot.interact(captureImportedItem::fire);
		assertFalse(newMode.isSelected());
		assertTrue(importedMode.isSelected());
		assertTrue(legacyControls.isVisible());
		assertTrue(legacyControls.isManaged());
		robot.interact(captureNewItem::fire);
		assertTrue(newMode.isSelected());
		assertFalse(importedMode.isSelected());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
		robot.clickOn(importedMode);
		assertFalse(newMode.isSelected());
		assertTrue(importedMode.isSelected());
		assertTrue(legacyControls.isVisible());
		assertTrue(legacyControls.isManaged());
		robot.clickOn(newMode);
		assertTrue(newMode.isSelected());
		assertFalse(importedMode.isSelected());
		assertFalse(legacyControls.isVisible());
		assertFalse(legacyControls.isManaged());
	}

	@Test
	void questionEditCompletionRunsAfterSaveTransitionFinishes(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "57");
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AtomicBoolean callbackRan = new AtomicBoolean();
		AtomicBoolean saveInProgressAtCompletion = new AtomicBoolean();
		robot.interact(() -> assertTrue(pane.editQuestion(question, () -> {
			saveInProgressAtCompletion.set(pane.isSaveInProgress());
			callbackRan.set(true);
		})));
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, callbackRan::get);
		assertFalse(saveInProgressAtCompletion.get(),
				"Question edit completion must run after the save-in-progress state is cleared");
		assertFalse(pane.isSaveInProgress());
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
		robot.interact(() -> answerCapturePane().refreshQuestions(refreshedQuestions));
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
		robot.interact(() -> answers.refreshQuestions(oldSnapshot));
		assertTrue(unansweredQuestions(robot).getItems().isEmpty());
	}

	@Test
	void questionSaveKeepsFxThreadResponsiveDuringValidationAndRefresh(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		robot.clickOn(lookup(robot, "#question-code", TextField.class)).write("24a");
		robot.clickOn(lookup(robot, "#question-marks", TextField.class)).write("2");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
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
			robot.interact(() -> lookup(robot, "#save-question", Button.class).fire());
			for (int index = 0; index < 2; index++) {
				assertTrue(entered[index].await(5, TimeUnit.SECONDS));
				CountDownLatch pulse = new CountDownLatch(1);
				Platform.runLater(pulse::countDown);
				assertTrue(pulse.await(2, TimeUnit.SECONDS), "FX events must run while repository I/O waits");
				if (index == 0) {
					robot.interact(() -> lookup(robot, "#next-pdf-page", Button.class).fire());
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
	void refreshingSubjectsReloadsVersionsWithoutReplacingHistoricalSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		SyllabusVersion historical = selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode classification = model.getClassification();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(new SqliteDatabase(databasePath));
		SyllabusVersion imported = writer.insertSyllabusVersion(historical.getSubject(), "2015", false);
		writer.insertSubject("Astronomy");
		CurriculumSelectorPane pane = field(application, "curriculumSelectorPane", CurriculumSelectorPane.class);
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		robot.interact(pane::refreshSubjects);
		assertEquals(3, syllabuses.getItems().size());
		assertTrue(syllabuses.getItems().contains(imported));
		assertEquals(historical, syllabuses.getValue());
		assertEquals(historical, model.getSyllabusVersion());
		assertEquals(historical.getSubject(), model.getSubject());
		assertEquals(classification, model.getClassification());
		assertClassificationControlShows(robot, classification);
		assertEquals("3", units.getItems().getFirst().getCode());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		assertEquals(4, comboBox(robot, "#curriculum-subject").getItems().size());
	}

	@Test
	void resettingClassificationWithoutSyllabusKeepsUnitsDisabled(FxRobot robot) throws Exception {
		CurriculumSelectorPane pane = field(application, "curriculumSelectorPane", CurriculumSelectorPane.class);
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		robot.interact(pane::clearClassificationBelowSubject);
		assertTrue(units.isDisabled());
		selectSubject(robot, "Biology");
		robot.interact(pane::clearClassificationBelowSubject);
		assertTrue(units.isDisabled());
		assertNull(syllabuses.getValue());
		assertFalse(syllabuses.isDisabled());
	}

	@Test
	void restoreIsBlockedWhileAnswerSaveIsInProgress(FxRobot robot) throws Exception {
		AnswerCapturePane pane = answerCapturePane();
		AtomicInteger exitCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		setField(pane, "answerSaveInProgress", true);
		try {
			MenuItem restoreItem = fileRestoreMenuItem();
			Platform.runLater(restoreItem::fire);
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> robot.lookup("Save in progress").tryQuery().isPresent());
			assertEquals(0, exitCount.get());
			robot.clickOn("OK");
		} finally {
			setField(pane, "answerSaveInProgress", false);
		}
	}

	@Test
	void revisionExportDestinationAvoidsExistingExport() throws Exception {
		Subject chemistry = new Subject(500, "Chemistry");
		Path parent = Files.createTempDirectory("revision-destination-");
		Path first = (Path) invoke(application, "revisionExportDestination",
				new Class<?>[] { Path.class, Subject.class }, parent, chemistry);
		assertEquals(parent.resolve("chemistry-revision"), first);
		Files.createDirectories(first);
		Path second = (Path) invoke(application, "revisionExportDestination",
				new Class<?>[] { Path.class, Subject.class }, parent, chemistry);
		assertEquals(parent.resolve("chemistry-revision-2"), second);
	}

	@Test
	void revisionExportDoesNotStartWhenOneIsAlreadyRunning(FxRobot robot) throws Exception {
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		Subject chemistry = model.getSubjects().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		Path exportParent = Files.createTempDirectory("revision-ui-duplicate-");
		Path destination = exportParent.resolve("should-not-exist");
		setField(application, "revisionExportRunning", Boolean.TRUE);
		robot.interact(() -> {
			try {
				invoke(application, "startRevisionExport",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class },
						primaryStage, applicationConfig, chemistry, destination);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertFalse(Files.exists(destination));
		setField(application, "revisionExportRunning", Boolean.FALSE);
	}

	@Test
	void revisionHtmlExportRunsFromApplicationAndRestoresMenu(FxRobot robot) throws Exception {
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		Subject chemistry = model.getSubjects().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		Path exportParent = Files.createTempDirectory("revision-ui-export-");
		Path destination = exportParent.resolve("chemistry-revision");
		MenuItem exportItem = field(application, "revisionExportMenuItem", MenuItem.class);
		AtomicBoolean disabledWhileStarting = new AtomicBoolean();
		robot.interact(() -> {
			try {
				invoke(application, "startRevisionExport",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class },
						primaryStage, applicationConfig, chemistry, destination);
				disabledWhileStarting.set(exportItem.isDisable());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertTrue(disabledWhileStarting.get());
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
		while (!Files.isRegularFile(destination.resolve("index.html")) && System.nanoTime() < deadline) {
			Thread.sleep(25);
		}
		assertTrue(Files.isRegularFile(destination.resolve("index.html")), "Revision export did not complete");
		assertTrue(Files.isRegularFile(destination.resolve(Path.of("assets", "revision.css"))));
		/*
		 * The successful export displays its normal information alert. Close it so the
		 * FX success handler can finish.
		 */
		robot.clickOn("OK");
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(field(application, "revisionExportRunning", Boolean.class).booleanValue());
		assertFalse(exportItem.isDisable());
	}

	@Test
	void revisionScormExportRunsFromApplicationAndRestoresMenu(FxRobot robot) throws Exception {
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		Subject chemistry = model.getSubjects().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		Path exportParent = Files.createTempDirectory("scorm-ui-export-");
		Path destination = exportParent.resolve("chemistry-revision-scorm.zip");
		MenuItem exportItem = field(application, "scormExportMenuItem", MenuItem.class);
		AtomicBoolean disabledWhileStarting = new AtomicBoolean();
		robot.interact(() -> {
			try {
				invoke(application, "startScormExport",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class },
						primaryStage, applicationConfig, chemistry, destination);
				disabledWhileStarting.set(exportItem.isDisable());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertTrue(disabledWhileStarting.get());
		WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
				() -> robot.lookup("OK").tryQuery().isPresent());
		assertTrue(Files.isRegularFile(destination), "SCORM export did not complete");
		assertTrue(Files.size(destination) > 0, "SCORM export produced an empty ZIP");
		robot.clickOn("OK");
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(field(application, "scormExportRunning", Boolean.class).booleanValue());
		assertFalse(exportItem.isDisable());
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
		robot.clickOn("#add-question-region");
		assertFalse(save.isDisabled());
	}

	@Test
	void scormExportDestinationAvoidsExistingZip() throws Exception {
		Subject chemistry = new Subject(500, "Chemistry");
		Path parent = Files.createTempDirectory("scorm-destination-");
		Path first = (Path) invoke(application, "scormExportDestination", new Class<?>[] { Path.class, Subject.class },
				parent, chemistry);
		assertEquals(parent.resolve("chemistry-revision-scorm.zip"), first);
		Files.writeString(first, "existing");
		Path second = (Path) invoke(application, "scormExportDestination", new Class<?>[] { Path.class, Subject.class },
				parent, chemistry);
		assertEquals(parent.resolve("chemistry-revision-scorm-2.zip"), second);
	}

	@Test
	void scormExportDoesNotStartWhenOneIsAlreadyRunning(FxRobot robot) throws Exception {
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		Subject chemistry = model.getSubjects().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		Path exportParent = Files.createTempDirectory("scorm-ui-duplicate-");
		Path destination = exportParent.resolve("should-not-exist.zip");
		MenuItem exportItem = field(application, "scormExportMenuItem", MenuItem.class);
		AtomicBoolean disabledAfterAttempt = new AtomicBoolean();
		setField(application, "scormExportRunning", Boolean.TRUE);
		robot.interact(() -> {
			try {
				invoke(application, "startScormExport",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class },
						primaryStage, applicationConfig, chemistry, destination);
				disabledAfterAttempt.set(exportItem.isDisable());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertFalse(disabledAfterAttempt.get());
		assertFalse(Files.exists(destination));
		assertTrue(field(application, "scormExportRunning", Boolean.class).booleanValue());
		setField(application, "scormExportRunning", Boolean.FALSE);
	}

	@Test
	@SuppressWarnings("unchecked")
	void searchEditMetadataCorrectsMetadataOnlyQuestion(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode classification = model.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question metadataOnlyQuestion = repository.save(booklet, "61", "", 2, List.of(), classification, true, null,
				null);
		assertTrue(metadataOnlyQuestion.getRegions().isEmpty());
		/*
		 * Open Search Questions through the real application method. showAndWait()
		 * enters a nested JavaFX event loop, so schedule it rather than blocking the
		 * TestFX interaction thread.
		 */
		Platform.runLater(() -> {
			try {
				invoke(application, "showQuestionSearch", new Class<?>[] { Stage.class, ApplicationConfig.class },
						primaryStage, applicationConfig);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-subject").tryQuery().isPresent());
		ComboBox<Subject> subjectBox = comboBox(robot, "#question-search-subject");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> subjectBox.getItems().stream().anyMatch(subject -> "Chemistry".equals(subject.getName())));
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjectBox.setValue(chemistry));
		ListView<QuestionRetrievalResult> results = robot.lookup("#question-search-results").queryAs(ListView.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().stream()
				.anyMatch(result -> result.getQuestion().getId() == metadataOnlyQuestion.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == metadataOnlyQuestion.getId()).findFirst()
				.orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		Button editMetadata = lookup(robot, "#question-search-edit-metadata", Button.class);
		assertFalse(editMetadata.isDisabled());
		robot.clickOn(editMetadata);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-metadata-question-code").tryQuery().isPresent());
		TextField questionCode = lookup(robot, "#legacy-metadata-question-code", TextField.class);
		TextField marks = lookup(robot, "#legacy-metadata-marks", TextField.class);
		CheckBox preamble = lookup(robot, "#legacy-metadata-preamble-required", CheckBox.class);
		assertEquals("61", questionCode.getText());
		assertEquals("2", marks.getText());
		assertTrue(preamble.isSelected());
		robot.interact(() -> {
			questionCode.setText("61a");
			marks.setText("4");
			preamble.setSelected(false);
		});
		robot.clickOn("#legacy-metadata-save");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> repository.findById(metadataOnlyQuestion.getId())
				.map(question -> "61a".equals(question.getQuestionCode())).orElse(false));
		Question updated = repository.findById(metadataOnlyQuestion.getId()).orElseThrow();
		assertEquals("61a", updated.getQuestionCode());
		assertEquals(4, updated.getMarks());
		assertFalse(updated.isPreambleCaptureRequired());
		assertTrue(updated.getRegions().isEmpty());
		assertEquals(classification.getId(), updated.getClassification().getId());
		/*
		 * Saving metadata reopens Search Questions. Close it so the application
		 * workflow unwinds cleanly before the test ends.
		 */
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Start
	void start(Stage stage) throws Exception {
		Path testRoot = Files.createTempDirectory("question-bank-ui-");
		pdfDataRoot = Files.createDirectories(testRoot.resolve("exams"));
		Path curriculumDataRoot = Files.createDirectories(testRoot.resolve("curriculum"));
		databasePath = testRoot.resolve("questionbank.db");
		createCurriculumDatabase(databasePath);
		examPdf = createTwoPagePdf(pdfDataRoot.resolve("exam.pdf"));
		applicationConfig = new ApplicationConfig(pdfDataRoot, curriculumDataRoot, databasePath);
		primaryStage = stage;
		application = new QuestionBankApplication();
		invoke(application, "startApplication", new Class<?>[] { Stage.class, ApplicationConfig.class }, stage,
				applicationConfig);
		openExamPdfForTest();
	}

	@AfterEach
	void stopApplication() throws Exception {
		application.stop();
	}

	@Test
	void windowCloseCreatesAutomaticBackupAndRequestsApplicationExit(FxRobot robot) throws Exception {
		AtomicInteger exitCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		assertEquals(0, automaticBackupCount());
		robot.interact(
				() -> Event.fireEvent(primaryStage, new WindowEvent(primaryStage, WindowEvent.WINDOW_CLOSE_REQUEST)));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, exitCount.get());
		assertEquals(1, automaticBackupCount());
	}

	private AnswerCapturePane answerCapturePane() {
		try {
			return field(application, "answerCapturePane", AnswerCapturePane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void assertAnswerEntryControlsEnabled(FxRobot robot) {
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertFalse(lookup(robot, "#choose-answer-pdf", Button.class).isDisabled());
	}

	private void assertClassificationControlShows(FxRobot robot, CurriculumNode classification) {
		assertNotNull(classification);
		if (classification.getLevel() == CurriculumLevel.SUBTOPIC) {
			assertEquals(classification, comboBox(robot, "#curriculum-subtopic").getValue());
			return;
		}
		if (classification.getLevel() == CurriculumLevel.DESCRIPTOR) {
			assertEquals(classification, comboBox(robot, "#curriculum-descriptor").getValue());
			return;
		}
		throw new AssertionError("Unexpected final classification level: " + classification.getLevel());
	}

	private void assertInitialAnswerControlsDisabled(FxRobot robot) {
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertTrue(lookup(robot, "#choose-answer-pdf", Button.class).isDisabled());
		assertTrue(lookup(robot, "#add-answer-region", Button.class).isDisabled());
		assertTrue(lookup(robot, "#clear-answer-selection", Button.class).isDisabled());
	}

	private long automaticBackupCount() throws IOException {
		Path automaticBackupDirectory = applicationConfig.dataRoot().resolve("backups").resolve("automatic");
		if (!Files.isDirectory(automaticBackupDirectory)) {
			return 0;
		}
		try (Stream<Path> stream = Files.list(automaticBackupDirectory)) {
			return stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().startsWith("question-bank-auto-"))
					.filter(path -> path.getFileName().toString().endsWith(".zip")).count();
		}
	}

	private Question captureQuestion(FxRobot robot, String questionCode) throws Exception {
		TextField questionCodeField = lookup(robot, "#question-code", TextField.class);
		robot.clickOn(questionCodeField).write(questionCode);
		TextField marksField = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(marksField).write("1");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn("#save-question");
		QuestionCapturePane questionCapturePane = field(application, "questionCapturePane", QuestionCapturePane.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !questionCapturePane.isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question savedQuestion = null;
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);
		for (Question question : unansweredQuestions.getItems()) {
			if (questionCode.equals(question.getQuestionCode())) {
				savedQuestion = question;
				break;
			}
		}
		Label saveStatus = lookup(robot, "#question-save-status", Label.class);
		assertNotNull(savedQuestion, "Question save status: " + saveStatus.getText());
		return savedQuestion;
	}

	@SuppressWarnings("unchecked")
	private <T> ComboBox<T> comboBox(FxRobot robot, String selector) {
		return robot.lookup(selector).queryAs(ComboBox.class);
	}

	private void createCurriculumDatabase(Path databasePath) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = writer.insertUnit(syllabus2025, "1", "Unit one", 1);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic one", 1);
		writer.insertDescriptor(topic, "1.1.1", "Descriptor one", 1);
		SyllabusVersion syllabus2019 = writer.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = writer.insertUnit(syllabus2019, "3", "Historical unit", 1);
		Topic historicalTopic = writer.insertTopic(historicalUnit, "3.1", "Historical topic", 1);
		writer.insertDescriptor(historicalTopic, "3.1.1", "Historical descriptor", 1);
		Subject physics = writer.insertSubject("Physics");
		SyllabusVersion physicsSyllabus = writer.insertSyllabusVersion(physics, "2025", true);
		Unit physicsUnit = writer.insertUnit(physicsSyllabus, "1", "Unit one", 1);
		Topic physicsTopic = writer.insertTopic(physicsUnit, "1.1", "Topic one", 1);
		writer.insertSubtopic(physicsTopic, "1.1.1", "Subtopic one", 1);
		Subject biology = writer.insertSubject("Biology");
		SyllabusVersion biology2019 = writer.insertSyllabusVersion(biology, "2019", false);
		Unit biologyUnit = writer.insertUnit(biology2019, "2", "Biology historical unit", 1);
		Topic biologyTopic = writer.insertTopic(biologyUnit, "2.1", "Biology historical topic", 1);
		writer.insertDescriptor(biologyTopic, "2.1.1", "Biology historical descriptor", 1);
	}

	private Path createTwoPagePdf(Path path) throws Exception {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		try (PDDocument _ = Loader.loadPDF(path.toFile())) {
			return path;
		}
	}

	private void dragRegionOnDisplayedPage(FxRobot robot) throws Exception {
		ImageView pageView = lookup(robot, "#pdf-page-view", ImageView.class);
		robot.interact(() -> {
			fireMouseEvent(pageView, MouseEvent.MOUSE_PRESSED, 30, 30, true);
			fireMouseEvent(pageView, MouseEvent.MOUSE_DRAGGED, 160, 150, true);
			fireMouseEvent(pageView, MouseEvent.MOUSE_RELEASED, 160, 150, false);
		});
		WaitForAsyncUtils.waitForFxEvents();
	}

	private ExamImportDialog examImportDialog() {
		try {
			return field(application, "examImportDialog", ExamImportDialog.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private ExamMetadataPane examMetadataPane() {
		try {
			return field(application, "examMetadataPane", ExamMetadataPane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private MenuItem fileExitMenuItem() {
		BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
		MenuBar menuBar = (MenuBar) root.getTop();
		for (MenuItem item : menuBar.getMenus().get(0).getItems()) {
			if ("E_xit".equals(item.getText())) {
				return item;
			}
		}
		throw new AssertionError("File -> Exit menu item not found");
	}

	private MenuItem fileRestoreMenuItem() {
		BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
		MenuBar menuBar = (MenuBar) root.getTop();
		for (MenuItem item : menuBar.getMenus().get(0).getItems()) {
			if ("_Restore Backup...".equals(item.getText())) {
				return item;
			}
		}
		throw new AssertionError("File -> Restore Backup menu item not found");
	}

	private void openAnswerPdfForTest(Question question) throws Exception {
		SelectedPdf selectedPdf = new SelectedPdf(examPdf.toFile(), examPdf, pdfDataRoot);
		WaitForAsyncUtils.asyncFx(() -> answerCapturePane().selectAnswerPdf(question, selectedPdf)).get();
	}

	private void openExamPdfForTest() throws Exception {
		examMetadataPane().selectExamPdf(new SelectedPdf(examPdf.toFile(), examPdf, pdfDataRoot));
	}

	private PdfWorkspacePane pdfWorkspace() {
		try {
			return field(application, "pdfWorkspace", PdfWorkspacePane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void prepareExamAndClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry");
	}

	private void prepareExamAndClassification(FxRobot robot, String subjectName) throws Exception {
		WaitForAsyncUtils.asyncFx(() -> examImportDialog().show()).get();
		WaitForAsyncUtils.asyncFx(() -> examMetadataPane().stageExamPdf(examPdf)).get();
		ComboBox<Subject> examSubject = comboBox(robot, "#exam-subject");
		Subject selectedSubject = null;
		for (Subject subject : examSubject.getItems()) {
			if (subjectName.equals(subject.getName())) {
				selectedSubject = subject;
				break;
			}
		}
		assertNotNull(selectedSubject);
		Subject subjectSelection = selectedSubject;
		ComboBox<String> provider = comboBox(robot, "#exam-provider");
		ComboBox<Integer> year = comboBox(robot, "#exam-year");
		ComboBox<String> assessment = comboBox(robot, "#exam-assessment");
		ComboBox<String> booklet = comboBox(robot, "#exam-booklet");
		robot.interact(() -> {
			examSubject.setValue(subjectSelection);
			provider.getEditor().setText("QCAA");
			year.getSelectionModel().select(Integer.valueOf(2024));
			assessment.getEditor().setText("External Assessment");
			booklet.getEditor().setText("Paper 1 MCQ");
		});
		robot.clickOn("#confirm-exam-details");
		WaitForAsyncUtils.waitForFxEvents();
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
	}

	private void selectFirst(FxRobot robot, String selector) throws Exception {
		ComboBox<Object> comboBox = comboBox(robot, selector);
		WaitForAsyncUtils.asyncFx(() -> comboBox.getSelectionModel().selectFirst()).get();
	}

	private void selectFirstFinalClassification(FxRobot robot) throws Exception {
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		if (subtopics.isVisible() && !subtopics.getItems().isEmpty()) {
			WaitForAsyncUtils.asyncFx(() -> subtopics.getSelectionModel().selectFirst()).get();
			WaitForAsyncUtils.waitForFxEvents();
		}
		if (descriptors.isVisible() && !descriptors.getItems().isEmpty()) {
			WaitForAsyncUtils.asyncFx(() -> descriptors.getSelectionModel().selectFirst()).get();
			WaitForAsyncUtils.waitForFxEvents();
		}
	}

	private void selectSubject(FxRobot robot, String subjectName) {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject selectedSubject = null;
		for (Subject subject : subjects.getItems()) {
			if (subjectName.equals(subject.getName())) {
				selectedSubject = subject;
				break;
			}
		}
		assertNotNull(selectedSubject);
		Subject subjectSelection = selectedSubject;
		robot.interact(() -> subjects.setValue(subjectSelection));
	}

	private SyllabusVersion selectSyllabus(FxRobot robot, String name) {
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		for (SyllabusVersion version : syllabuses.getItems()) {
			if (name.equals(version.getName())) {
				robot.interact(() -> syllabuses.setValue(version));
				return version;
			}
		}
		throw new AssertionError("Syllabus not found: " + name);
	}

	private void showPdfMode(String modeName) throws Exception {
		PdfWorkspacePane.DocumentMode mode = PdfWorkspacePane.DocumentMode.valueOf(modeName);
		WaitForAsyncUtils.asyncFx(() -> pdfWorkspace().showDocument(mode)).get();
	}

	private ComboBox<Question> unansweredQuestions(FxRobot robot) {
		return comboBox(robot, "#unanswered-question");
	}
}
