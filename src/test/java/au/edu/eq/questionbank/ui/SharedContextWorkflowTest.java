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
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
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
class SharedContextWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	@SuppressWarnings("unchecked")
	void cancellingSplitAfterStagingPreambleAndFirstPartLeavesOriginalUnchanged(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question original = captureQuestion(robot, "70");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteSourceQuestionRepository sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		Question storedBeforeSplit = questionRepository.findById(original.getId()).orElseThrow();
		assertEquals(1, storedBeforeSplit.getRegions().size());
		List<QuestionRegion> originalRegions = storedBeforeSplit.getRegions();
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
		robot.clickOn("#question-search-split-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-preamble-choice").tryQuery().isPresent());
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		ComboBox<LegacyQuestionSplitDialog.PreambleChoice> preambleChoice = comboBox(robot,
				"#legacy-split-preamble-choice");
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			preambleChoice.setValue(LegacyQuestionSplitDialog.PreambleChoice.CAPTURE_NEW_SHARED_PREAMBLE);
		});
		robot.clickOn("#legacy-split-continue");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());
		Button save = lookup(robot, "#save-question", Button.class);

		// Stage the shared preamble first.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Preamble", lookup(robot, "#add-question-region", Button.class).getText());
		robot.clickOn("#add-question-region");
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());

		// Stage the complete first resulting part.
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertFalse(save.isDisabled());
		assertEquals("Next Part", save.getText());
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("70b", lookup(robot, "#question-code", TextField.class).getText());
		assertEquals("Save Split", save.getText());

		// Both the preamble and 70a now exist only in transient split state.
		assertEquals(1, questionRepository.findAll().size());
		assertTrue(sourceQuestionRepository.findByBooklet(original.getBooklet()).isEmpty());
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());

		// Cancel while the workflow is waiting for 70b.
		robot.clickOn("#cancel-question-edit");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		Question afterCancel = questionRepository.findById(original.getId()).orElseThrow();

		// The original persistent identity, code, metadata and source regions remain
		// exactly the legacy single Question that existed before the workflow.
		assertEquals(original.getId(), afterCancel.getId());
		assertEquals("70", afterCancel.getQuestionCode());
		assertEquals(original.getMarks(), afterCancel.getMarks());
		assertEquals(original.getClassification().getId(), afterCancel.getClassification().getId());
		assertEquals(original.getResponseType(), afterCancel.getResponseType());
		assertEquals(originalRegions.size(), afterCancel.getRegions().size());
		for (int index = 0; index < originalRegions.size(); index++) {
			QuestionRegion expectedRegion = originalRegions.get(index);
			QuestionRegion actualRegion = afterCancel.getRegions().get(index);

			// Repository reconstruction creates a new ExamBooklet object, so compare its
			// persistent identity rather than relying on QuestionRegion record equality.
			assertEquals(expectedRegion.booklet().getId(), actualRegion.booklet().getId());
			assertEquals(expectedRegion.pageNumber(), actualRegion.pageNumber());
			assertEquals(expectedRegion.x(), actualRegion.x());
			assertEquals(expectedRegion.y(), actualRegion.y());
			assertEquals(expectedRegion.width(), actualRegion.width());
			assertEquals(expectedRegion.height(), actualRegion.height());
		}
		assertFalse(afterCancel.hasSourceQuestion());
		assertFalse(afterCancel.hasSharedContext());

		// Cancelling transient capture must not leave any relationship rows behind.
		assertEquals(1, questionRepository.findAll().size());
		assertTrue(sourceQuestionRepository.findByBooklet(original.getBooklet()).isEmpty());
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
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
	@SuppressWarnings("unchecked")
	void searchSplitQuestionCapturesOneSharedPreambleForAllParts(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question original = captureQuestion(robot, "67");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);

		// Enter the split workflow through Search rather than directly invoking the
		// capture pane.
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
		robot.clickOn("#question-search-split-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-part-0-marks").tryQuery().isPresent());
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		ComboBox<LegacyQuestionSplitDialog.PreambleChoice> preambleChoice = comboBox(robot,
				"#legacy-split-preamble-choice");
		Button continueButton = lookup(robot, "#legacy-split-continue", Button.class);
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			preambleChoice.setValue(LegacyQuestionSplitDialog.PreambleChoice.CAPTURE_NEW_SHARED_PREAMBLE);
		});
		assertFalse(continueButton.isDisabled());
		robot.clickOn(continueButton);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);
		assertEquals("67a", questionCode.getText());
		assertEquals("Next Part", save.getText());

		// The first PDF rectangle belongs to the shared preamble. Accepting it must not
		// create a Question region.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Preamble", lookup(robot, "#add-question-region", Button.class).getText());
		robot.clickOn("#add-question-region");
		assertEquals("Regions: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(save.isDisabled());

		// The preamble is still transient. Nothing has been added to SQLite.
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());
		assertEquals("67", repository.findById(original.getId()).orElseThrow().getQuestionCode());

		// Capture the actual 67a Question region.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Region", lookup(robot, "#add-question-region", Button.class).getText());
		robot.clickOn("#add-question-region");
		assertEquals("Regions: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("67b", questionCode.getText());
		assertEquals("Save Split", save.getText());

		// Completing 67a still must not persist either the split or its preamble.
		assertEquals(1, repository.findAll().size());
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());

		// Capture the second Question part.
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		List<Question> stored = repository.findAll();
		assertEquals(2, stored.size());
		Question partA = stored.stream().filter(question -> "67a".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();
		Question partB = stored.stream().filter(question -> "67b".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();
		assertTrue(partA.hasSourceQuestion());
		assertTrue(partB.hasSourceQuestion());
		assertEquals(partA.getSourceQuestion().getId(), partB.getSourceQuestion().getId());
		assertEquals("67", partA.getSourceQuestion().getSourceQuestionCode());
		assertEquals(PreambleStatus.PRESENT, partA.getSourceQuestion().getPreambleStatus());
		assertTrue(partA.hasSharedContext());
		assertTrue(partB.hasSharedContext());
		assertEquals(partA.getSharedContext().getId(), partB.getSharedContext().getId());
		assertEquals("Question 67 preamble", partA.getSharedContext().getLabel());
		assertEquals(1, partA.getSharedContext().getRegions().size());
		assertEquals(1, partA.getRegions().size());
		assertEquals(1, partB.getRegions().size());

		// Exactly one shared-context entity must have been created for the multipart
		// source Question.
		assertEquals(1, contextRepository.findByBooklet(original.getBooklet()).size());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Test
	void searchSplitQuestionReusesExistingSharedPreamble(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteSourceQuestionRepository sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);

		// The legacy single Question is still independent and is the Question that
		// will be corrected into 68a and 68b.
		Question original = questionRepository.save(booklet, "68", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.80, 0.30)), classification, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// An already-correct multipart sibling establishes the destination
		// SourceQuestion and its authoritative shared preamble.
		SourceQuestion sourceQuestion = sourceQuestionRepository.save(booklet, "68");
		sourceQuestion = sourceQuestionRepository.updatePreambleStatus(sourceQuestion, PreambleStatus.PRESENT);
		SharedQuestionContext existingContext = contextRepository.save(booklet, "Question 68 preamble",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)));
		Question existingPartC = questionRepository.save(booklet, "68c", "", 1,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.60, 0.80, 0.15)), classification, false, sourceQuestion,
				existingContext, QuestionResponseType.WRITTEN_RESPONSE);
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
				() -> robot.lookup("#legacy-split-preamble-choice").tryQuery().isPresent());
		ComboBox<LegacyQuestionSplitDialog.PreambleChoice> preambleChoice = comboBox(robot,
				"#legacy-split-preamble-choice");

		// Reuse is offered only because Search found an established SourceQuestion
		// whose members consistently use this persisted shared context.
		assertTrue(preambleChoice.getItems()
				.contains(LegacyQuestionSplitDialog.PreambleChoice.REUSE_EXISTING_SHARED_PREAMBLE));
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			preambleChoice.setValue(LegacyQuestionSplitDialog.PreambleChoice.REUSE_EXISTING_SHARED_PREAMBLE);
		});
		Button continueButton = lookup(robot, "#legacy-split-continue", Button.class);
		assertFalse(continueButton.isDisabled());
		robot.clickOn(continueButton);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);

		// Reuse requires no preamble recapture. Region selection begins immediately
		// with the first resulting Question part.
		assertEquals("68a", questionCode.getText());
		assertEquals("Next Part", save.getText());
		assertEquals("Add Region", lookup(robot, "#add-question-region", Button.class).getText());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("68b", questionCode.getText());
		assertEquals("Save Split", save.getText());

		// Staging the first resulting part must not create another context or change
		// the persisted legacy Question.
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
		assertEquals("68", questionRepository.findById(original.getId()).orElseThrow().getQuestionCode());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn(save);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		List<Question> storedQuestions = questionRepository.findAll();
		Question partA = storedQuestions.stream().filter(question -> "68a".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		Question partB = storedQuestions.stream().filter(question -> "68b".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		Question reloadedPartC = questionRepository.findById(existingPartC.getId()).orElseThrow();

		// All three parts must reconstruct as one SourceQuestion group.
		assertEquals(sourceQuestion.getId(), partA.getSourceQuestion().getId());
		assertEquals(sourceQuestion.getId(), partB.getSourceQuestion().getId());
		assertEquals(sourceQuestion.getId(), reloadedPartC.getSourceQuestion().getId());

		// The exact existing context identity is reused by every member.
		assertEquals(existingContext.getId(), partA.getSharedContext().getId());
		assertEquals(existingContext.getId(), partB.getSharedContext().getId());
		assertEquals(existingContext.getId(), reloadedPartC.getSharedContext().getId());
		assertEquals(PreambleStatus.PRESENT, partA.getSourceQuestion().getPreambleStatus());

		// Reusing the context must not insert a duplicate shared-context entity.
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
		assertEquals(List.of("68a", "68b", "68c"),
				storedQuestions.stream().map(Question::getQuestionCode).sorted().toList());
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
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

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
