package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class QuestionEditingWorkflowTest extends QuestionBankApplicationUiTestBase {

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
	void questionEditLoadsAndUpdatesResponseType(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "R2");
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, question.getResponseType());
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		robot.interact(() -> assertTrue(pane.editQuestion(question, () -> {
		})));
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);

		// Editing must restore the persisted response type into the radio-button group.
		assertTrue(writtenResponse.isSelected());
		assertFalse(multipleChoice.isSelected());

		// Change the persisted response type through the production UI.
		robot.clickOn(multipleChoice);
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, stored.getResponseType());
		assertEquals(1, stored.getRegions().size());
	}

	@Test
	void questionEditOpensFirstStoredRegionPage(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);

		// Capture the Question while page 2 is displayed so its persisted first
		// source region belongs to page 2.
		robot.interact(() -> pdfWorkspace().showPage(PdfWorkspacePane.DocumentMode.EXAM, 2));
		assertEquals(2, pdfWorkspace().getCurrentPageNumber());
		Question question = captureQuestion(robot, "PAGE2");
		assertEquals(1, question.getRegions().size());
		assertEquals(2, question.getRegions().getFirst().pageNumber());

		// Move away from the Question's source page before starting the edit. This
		// proves that editQuestion performs the navigation rather than merely
		// inheriting the page that was used during capture.
		robot.interact(() -> pdfWorkspace().showPage(PdfWorkspacePane.DocumentMode.EXAM, 1));
		assertEquals(1, pdfWorkspace().getCurrentPageNumber());
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> assertTrue(questionCapturePane().editQuestion(question, completed::incrementAndGet)));
		WaitForAsyncUtils.waitForFxEvents();

		// Editing an existing Question should start on the page containing its first
		// persisted Question region.
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
		assertEquals(2, pdfWorkspace().getCurrentPageNumber());
		assertEquals(0, completed.get());

		// Finish through the real edit workflow so the test leaves no active edit
		// state behind.
		robot.clickOn("#cancel-question-edit");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, completed.get());
	}

	@Test
	void recaptureQuestionStartsEmptyWithoutChangingStoredRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "59");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		assertEquals(1, repository.findById(question.getId()).orElseThrow().getRegions().size());
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> assertTrue(pane.recaptureQuestion(question, completed::incrementAndGet)));
		WaitForAsyncUtils.waitForFxEvents();
		/*
		 * Recapture starts with no transient replacement regions.
		 */
		assertEquals("Regions: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(lookup(robot, "#save-question", Button.class).isDisable());
		assertTrue(lookup(robot, "#cancel-question-edit", Button.class).isVisible());
		/*
		 * Nothing has yet changed in SQLite.
		 */
		assertEquals(1, repository.findById(question.getId()).orElseThrow().getRegions().size());
		assertEquals(0, completed.get());
		/*
		 * Cancelling recapture leaves the persisted question untouched.
		 */
		robot.clickOn("#cancel-question-edit");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, completed.get());
		assertEquals(1, repository.findById(question.getId()).orElseThrow().getRegions().size());
	}

	// @SuppressWarnings("unchecked")
	@Test
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
		ListView<QuestionRetrievalResult> results = listView(robot, "#question-search-results");
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
		ComboBox<QuestionResponseType> responseType = comboBox(robot, "#legacy-metadata-response-type");
		assertEquals("61", questionCode.getText());
		assertEquals("2", marks.getText());
		assertTrue(preamble.isSelected());
		robot.interact(() -> {
			questionCode.setText("61a");
			marks.setText("4");
			preamble.setSelected(false);
			responseType.setValue(QuestionResponseType.WRITTEN_RESPONSE);
		});
		robot.clickOn("#legacy-metadata-save");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> repository.findById(metadataOnlyQuestion.getId())
				.map(question -> "61a".equals(question.getQuestionCode())).orElse(false));
		Question updated = repository.findById(metadataOnlyQuestion.getId()).orElseThrow();
		assertEquals("61a", updated.getQuestionCode());
		assertEquals(4, updated.getMarks());
		assertFalse(updated.isPreambleCaptureRequired());
		assertTrue(updated.getRegions().isEmpty());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.getResponseType());
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

	@Test
	@SuppressWarnings("unchecked")
	void searchSplitQuestionRequiresExplicitAnswerOwnerAndRetainsAnswerOnThatPart(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question original = captureQuestion(robot, "69");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, new SqliteExamWriter(database));
		var originalAnswer = answerWriter.insertAnswer(original, "Original combined answer", List.of());

		// Reopen through the repository so Search sees the persisted Answer
		// relationship rather than the pre-answer Question object.
		Question answeredOriginal = repository.findById(original.getId()).orElseThrow();
		assertTrue(answeredOriginal.hasAnswer());
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
		ListView<QuestionRetrievalResult> results = listView(robot, "#question-search-results");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> results.getItems().stream().anyMatch(result -> result.getQuestion().getId() == original.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == original.getId()).findFirst().orElseThrow();
		assertTrue(selectedResult.getQuestion().hasAnswer());
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		robot.clickOn("#question-search-split-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-answer-part").tryQuery().isPresent());
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		ComboBox<LegacyQuestionSplitDialog.PreambleChoice> preambleChoice = comboBox(robot,
				"#legacy-split-preamble-choice");
		ComboBox<String> answerPart = comboBox(robot, "#legacy-split-answer-part");
		Button continueButton = lookup(robot, "#legacy-split-continue", Button.class);
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			preambleChoice.setValue(LegacyQuestionSplitDialog.PreambleChoice.NO_SHARED_PREAMBLE);
		});

		// Even with all other metadata complete, an answered legacy Question cannot
		// proceed until the user explicitly nominates the resulting Answer owner.
		assertTrue(continueButton.isDisabled());
		assertEquals(List.of("69a", "69b"), answerPart.getItems());
		robot.interact(() -> answerPart.setValue("69b"));
		assertFalse(continueButton.isDisabled());
		robot.clickOn(continueButton);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);
		assertEquals("69a", questionCode.getText());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("69b", questionCode.getText());

		// No persistence occurs after only the first staged part.
		Question stillOriginal = repository.findById(original.getId()).orElseThrow();
		assertEquals("69", stillOriginal.getQuestionCode());
		assertTrue(stillOriginal.hasAnswer());
		assertEquals(originalAnswer.getId(), stillOriginal.getAnswer().getId());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn(save);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		List<Question> stored = repository.findAll();
		Question partA = stored.stream().filter(question -> "69a".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();
		Question partB = stored.stream().filter(question -> "69b".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();

		// The explicitly nominated Answer owner reuses the original Question row.
		assertFalse(partA.getId() == original.getId());
		assertEquals(original.getId(), partB.getId());
		assertFalse(partA.hasAnswer());
		assertTrue(partB.hasAnswer());
		assertEquals(originalAnswer.getId(), partB.getAnswer().getId());
		assertEquals("Original combined answer", partB.getAnswer().getAnswerText());
		assertEquals(partA.getSourceQuestion().getId(), partB.getSourceQuestion().getId());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Test
	@SuppressWarnings("unchecked")
	void searchSplitQuestionStartsStagedCaptureWithoutChangingOriginal(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question original = captureQuestion(robot, "66");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Open Search through the actual application workflow.
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
		ListView<QuestionRetrievalResult> results = listView(robot, "#question-search-results");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> results.getItems().stream().anyMatch(result -> result.getQuestion().getId() == original.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == original.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		Button splitButton = lookup(robot, "#question-search-split-question", Button.class);
		assertFalse(splitButton.isDisabled());
		robot.clickOn(splitButton);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-part-0-marks").tryQuery().isPresent());
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		ComboBox<LegacyQuestionSplitDialog.PreambleChoice> preambleChoice = comboBox(robot,
				"#legacy-split-preamble-choice");
		Button continueButton = lookup(robot, "#legacy-split-continue", Button.class);
		robot.interact(() -> {
			partAMarks.setText("1");
			partBMarks.setText("1");
			preambleChoice.setValue(LegacyQuestionSplitDialog.PreambleChoice.NO_SHARED_PREAMBLE);
		});
		assertFalse(continueButton.isDisabled());
		robot.clickOn(continueButton);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);
		Button cancel = lookup(robot, "#cancel-question-edit", Button.class);

		// Search has handed control to staged split capture. The first part is loaded,
		// but no persistence has occurred.
		assertEquals("66a", questionCode.getText());
		assertEquals("Next Part", save.getText());
		assertTrue(cancel.isVisible());
		Question beforeCancel = repository.findById(original.getId()).orElseThrow();
		assertEquals("66", beforeCancel.getQuestionCode());
		assertEquals(1, repository.findAll().size());

		// Cancelling the staged workflow must discard only transient split state and
		// return to the existing Search dialog.
		robot.clickOn(cancel);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		Question afterCancel = repository.findById(original.getId()).orElseThrow();
		assertEquals("66", afterCancel.getQuestionCode());
		assertEquals(1, repository.findAll().size());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Test
	void stagesAllSplitPartsBeforePersistingAtomicSplit(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question original = repository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), classification, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		LegacyQuestionSplitDialog.Result definition = new LegacyQuestionSplitDialog.Result("3",
				List.of(new LegacyQuestionSplitDialog.PartDefinition("3a", 2, classification,
						QuestionResponseType.WRITTEN_RESPONSE),
						new LegacyQuestionSplitDialog.PartDefinition("3b", 3, classification,
								QuestionResponseType.WRITTEN_RESPONSE)),
				0, LegacyQuestionSplitDialog.PreambleChoice.NO_SHARED_PREAMBLE, null);
		QuestionCapturePane pane = questionCapturePane();
		AtomicInteger completed = new AtomicInteger();
		robot.interact(
				() -> assertTrue(pane.beginLegacyQuestionSplit(original, definition, completed::incrementAndGet)));
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);
		assertEquals("3a", questionCode.getText());
		assertEquals("2", marks.getText());
		assertTrue(questionCode.isDisabled());
		assertTrue(marks.isDisabled());
		assertEquals("Next Part", save.getText());
		assertTrue(save.isDisabled());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();

		// Moving to 3b stages 3a only in memory. SQLite must still contain the
		// original unsplit Question.
		assertEquals("3b", questionCode.getText());
		assertEquals("3", marks.getText());
		assertEquals("Save Split", save.getText());
		assertEquals(1, repository.findAll().size());
		assertEquals("3", repository.findById(original.getId()).orElseThrow().getQuestionCode());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> completed.get() == 1);
		List<Question> stored = repository.findAll();
		assertEquals(2, stored.size());
		assertEquals(List.of("3a", "3b"), stored.stream().map(Question::getQuestionCode).sorted().toList());
		Question storedA = stored.stream().filter(question -> "3a".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();
		Question storedB = stored.stream().filter(question -> "3b".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();
		assertTrue(storedA.hasSourceQuestion());
		assertTrue(storedB.hasSourceQuestion());
		assertEquals(storedA.getSourceQuestion().getId(), storedB.getSourceQuestion().getId());
		assertFalse(storedA.hasSharedContext());
		assertFalse(storedB.hasSharedContext());
		assertEquals(1, storedA.getRegions().size());
		assertEquals(1, storedB.getRegions().size());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
