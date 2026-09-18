package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
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
class SharedContextWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void capturesMultipartQuestionWithSharedPreamble(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);

		// Deliberately draw first. The eventual multipart intent must be allowed to
		// reinterpret this pending rectangle as the preamble.
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

	// @SuppressWarnings("unchecked")
	@Test
	void metadataPreambleConversionMayKeepConvertedQuestionRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode classification = model.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q62 introductory material",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "62", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.40, 0.80, 0.30)), classification, true, null, context);
		assertTrue(question.isPreambleCaptureRequired());
		assertTrue(question.hasSharedContext());
		assertEquals(1, question.getRegions().size());
		/*
		 * Open Search Questions through the real application workflow.
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
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> results.getItems().stream().anyMatch(result -> result.getQuestion().getId() == question.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == question.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		robot.clickOn("#question-search-edit-metadata");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-metadata-preamble-required").tryQuery().isPresent());
		CheckBox preamble = lookup(robot, "#legacy-metadata-preamble-required", CheckBox.class);
		assertTrue(preamble.isSelected());
		robot.interact(() -> preamble.setSelected(false));
		robot.clickOn("#legacy-metadata-save");
		/*
		 * The metadata transaction has already converted and persisted the regions
		 * before this decision is requested.
		 */
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Keep converted regions").tryQuery().isPresent());
		Question converted = questionRepository.findById(question.getId()).orElseThrow();
		assertFalse(converted.isPreambleCaptureRequired());
		assertFalse(converted.hasSharedContext());
		assertEquals(2, converted.getRegions().size());
		assertEquals(0.10, converted.getRegions().get(0).y(), 0.000001);
		assertEquals(0.40, converted.getRegions().get(1).y(), 0.000001);
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
		robot.clickOn("Keep converted regions");
		/*
		 * Keeping the converted regions returns to Search rather than entering question
		 * recapture.
		 */
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		assertFalse(lookup(robot, "#cancel-question-edit", Button.class).isVisible());
		Question retained = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(2, retained.getRegions().size());
		assertFalse(retained.hasSharedContext());
		/*
		 * Close Search Questions so its nested event loop unwinds.
		 */
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}

	// @SuppressWarnings("unchecked")
	@Test
	void metadataPreambleConversionMayStartSafeCompleteQuestionRecapture(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode classification = model.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q63 introductory material",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "63", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.40, 0.80, 0.30)), classification, true, null, context);
		/*
		 * Open Search Questions through the real application workflow.
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
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> results.getItems().stream().anyMatch(result -> result.getQuestion().getId() == question.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == question.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		robot.clickOn("#question-search-edit-metadata");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-metadata-preamble-required").tryQuery().isPresent());
		CheckBox preamble = lookup(robot, "#legacy-metadata-preamble-required", CheckBox.class);
		assertTrue(preamble.isSelected());
		robot.interact(() -> preamble.setSelected(false));
		robot.clickOn("#legacy-metadata-save");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("Recapture complete question").tryQuery().isPresent());
		/*
		 * Conversion has already committed before recapture is offered.
		 */
		Question converted = questionRepository.findById(question.getId()).orElseThrow();
		assertFalse(converted.isPreambleCaptureRequired());
		assertFalse(converted.hasSharedContext());
		assertEquals(2, converted.getRegions().size());
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
		robot.clickOn("Recapture complete question");
		WaitForAsyncUtils.waitForFxEvents();
		/*
		 * Search is no longer active. Question Capture is now editing the existing
		 * question, but its transient replacement region list is empty.
		 */
		assertFalse(robot.lookup("#question-search-results").tryQuery().isPresent());
		assertTrue(lookup(robot, "#cancel-question-edit", Button.class).isVisible());
		assertEquals("63", lookup(robot, "#question-code", TextField.class).getText());
		assertEquals("2", lookup(robot, "#question-marks", TextField.class).getText());
		assertEquals("Regions: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(lookup(robot, "#save-question", Button.class).isDisable());
		/*
		 * Starting recapture has not deleted the safely converted regions.
		 */
		Question duringRecapture = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(2, duringRecapture.getRegions().size());
		/*
		 * Cancelling recapture must preserve that safe converted state and resume
		 * Search Questions.
		 */
		robot.clickOn("#cancel-question-edit");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		Question afterCancel = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(2, afterCancel.getRegions().size());
		assertFalse(afterCancel.hasSharedContext());
		assertFalse(afterCancel.isPreambleCaptureRequired());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
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
	void sharedPreambleCanBeRecapturedFromSearch(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext originalContext = contextRepository.save(booklet, "Question 64 preamble",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.50, 0.10)));
		Question question = questionRepository.save(booklet, "64a", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.35, 0.70, 0.20)), classification, true, null,
				originalContext, QuestionResponseType.WRITTEN_RESPONSE);

		// Open Search Questions through the real application workflow.
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
				() -> results.getItems().stream().anyMatch(result -> result.getQuestion().getId() == question.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == question.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		Button recapture = lookup(robot, "#question-search-recapture-preamble", Button.class);
		assertFalse(recapture.isDisabled());
		robot.clickOn(recapture);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());

		// Recapture should reopen the exam on the page containing the first stored
		// shared-preamble region rather than leaving the user at page 1.
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
		assertEquals(2, pdfWorkspace().getCurrentPageNumber());
		Button saveReplacement = lookup(robot, "#save-shared-context", Button.class);
		assertTrue(saveReplacement.isVisible());
		assertEquals("Save Replacement", saveReplacement.getText());
		TextField sharedContextLabel = lookup(robot, "#shared-context-label", TextField.class);

		// Existing context identity and naming are not editable during source-region
		// recapture.
		assertFalse(sharedContextLabel.isVisible());
		assertFalse(sharedContextLabel.isManaged());
		assertEquals("Question 64 preamble", sharedContextLabel.getText());
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		CurriculumSelectorPane classificationPane = field(application, "curriculumSelectorPane",
				CurriculumSelectorPane.class);

		// The Question remains visible as read-only context while only its shared
		// preamble is being replaced.
		assertEquals("64a", questionCode.getText());
		assertEquals("2", marks.getText());
		assertTrue(writtenResponse.isSelected());
		assertTrue(questionCode.isDisabled());
		assertTrue(marks.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertClassificationControlShows(robot, classification);
		assertTrue(classificationPane.isDisabled());
		assertTrue(lookup(robot, "#question-save-status", Label.class).getText()
				.contains("Recapturing shared preamble used by Question 64a"));

		// Starting recapture must leave the persisted original unchanged.
		SharedQuestionContext beforeReplacement = contextRepository.findByBooklet(booklet).stream()
				.filter(context -> context.getId() == originalContext.getId()).findFirst().orElseThrow();
		assertEquals(originalContext.getRegions(), beforeReplacement.getRegions());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-shared-context-region");
		robot.clickOn("#save-shared-context");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		SharedQuestionContext replaced = contextRepository.findByBooklet(booklet).stream()
				.filter(context -> context.getId() == originalContext.getId()).findFirst().orElseThrow();
		assertEquals(originalContext.getId(), replaced.getId());
		assertFalse(originalContext.getRegions().equals(replaced.getRegions()));
		Question reloaded = questionRepository.findById(question.getId()).orElseThrow();

		// The Question must still reference the same shared entity after replacement.
		assertEquals(originalContext.getId(), reloaded.getSharedContext().getId());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}
}
