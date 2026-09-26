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
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class SharedContextWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void activeSharedContextCaptureBlocksWorkingSubjectChange(FxRobot robot) throws Exception {
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

		// Start automatic shared-context capture without yet drawing a PDF region.
		// This isolates the shared-context transition guard from pending-selection
		// state.
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(questionCode).write("24a");
		robot.clickOn(marks).write("2");
		CheckBox sharedContext = lookup(robot, "#first-region-shared-context", CheckBox.class);
		fireControl(robot, sharedContext);
		assertTrue(sharedContext.isSelected());
		assertTrue(questionCapturePane().isCapturingSharedContext());
		CaptureSelectionState selectionState = field(application, "captureSelectionState", CaptureSelectionState.class);
		assertFalse(selectionState.hasPendingSelection());

		// Changing Working Subject would invalidate the booklet and curriculum context
		// needed by the active shared-context workflow, so reject the transition.
		Platform.runLater(() -> workingSubjectBox.setValue(physics));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("Capture work is in progress").tryQuery().isPresent());
		fireDialogButton(robot, "OK");

		// The rejected transition must preserve the complete shared-context capture
		// state and its existing Chemistry classification.
		assertEquals(chemistry, workingSubjectBox.getValue());
		assertTrue(sharedContext.isSelected());
		assertTrue(questionCapturePane().isCapturingSharedContext());
		assertEquals("24a", questionCode.getText());
		assertEquals("2", marks.getText());
		assertEquals(originalCode, curriculumCode.getText());
		assertEquals(originalClassification,
				field(application, "curriculumSelectionModel", CurriculumSelectionModel.class).getClassification());
		assertClassificationControlShows(robot, originalClassification);
	}

	@Test
	void cancellingSplitAfterStagingSharedContextAndFirstPartLeavesOriginalUnchanged(FxRobot robot) throws Exception {
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

		// Select the Search UI result by its wrapped persistent Question identity.
		selectSearchResult(robot, original.getId());
		fireControlLater(robot, "#question-search-split-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-shared-context-choice").tryQuery().isPresent());
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		ComboBox<LegacyQuestionSplitDialog.SharedContextChoice> sharedContextChoice = comboBox(robot,
				"#legacy-split-shared-context-choice");
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			sharedContextChoice.setValue(LegacyQuestionSplitDialog.SharedContextChoice.CAPTURE_NEW_SHARED_CONTEXT);
		});
		fireControl(robot, "#legacy-split-continue");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> !robot.lookup("#question-search-results").tryQuery().isPresent());
		Button save = lookup(robot, "#save-question", Button.class);

		// Stage the shared context first.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Context", lookup(robot, "#add-question-region", Button.class).getText());
		fireControl(robot, "#add-question-region");
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());

		// Stage the complete first resulting part.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertFalse(save.isDisabled());
		assertEquals("Next Part", save.getText());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("70b", lookup(robot, "#question-code", TextField.class).getText());
		assertEquals("Save Split", save.getText());

		// Both the shared context and 70a now exist only in transient split state.
		assertEquals(1, questionRepository.findAll().size());
		assertTrue(sourceQuestionRepository.findByBooklet(original.getBooklet()).isEmpty());
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());

		// Cancel while the workflow is waiting for 70b.
		// Split cancellation synchronously resumes the modal Search workflow.
		fireControlLater(robot, "#cancel-question-edit");
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
		fireDialogButton(robot, "Close");
	}

	@Test
	void capturesMultipartQuestionWithSharedSharedContext(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);

		// Deliberately draw first. The eventual multipart intent must be allowed to
		// reinterpret this pending rectangle as the shared context.
		dragRegionOnDisplayedPage(robot);
		assertTrue(save.isDisabled());
		robot.clickOn(questionCode).write("24a");
		robot.clickOn(marks).write("2");
		CheckBox sharedContext = lookup(robot, "#first-region-shared-context", CheckBox.class);
		assertTrue(sharedContext.isVisible());
		assertFalse(sharedContext.isSelected());
		robot.clickOn(sharedContext);
		assertTrue(sharedContext.isSelected());
		assertTrue(save.isDisabled());

		// The already-drawn rectangle is now accepted as the shared context rather than
		// as an ordinary question region.
		robot.clickOn("#add-question-region");
		assertEquals("Content parts: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(save.isDisabled());

		// Now capture the actual 24a question region.
		dragRegionOnDisplayedPage(robot);
		assertTrue(save.isDisabled());
		robot.clickOn("#add-question-region");
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitForFxEvents();
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().stream()
				.filter(question -> "24a".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertTrue(restored.hasSourceQuestion());
		assertEquals("24", restored.getSourceQuestion().getSourceQuestionCode());
		assertEquals(SharedContextStatus.PRESENT, restored.getSourceQuestion().getSharedContextStatus());
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
	void independentMcqsAutomaticallyReuseAndMayExtendSharedContext(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Paper 1 MCQ",
				ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		SqliteQuestionCaptureService captureService = new SqliteQuestionCaptureService(database);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		CheckBox sharedContext = lookup(robot, "#first-region-shared-context", CheckBox.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button addRegion = lookup(robot, "#add-question-region", Button.class);

		// An MCQ-only booklet knows the response type before a Question number is
		// entered, so the continuation control must already be available.
		assertTrue(multipleChoice.isSelected());
		assertTrue(sharedContext.isVisible());
		assertEquals("Shared context with next question", sharedContext.getText());
		assertFalse(sharedContext.isSelected());
		robot.clickOn(questionCode).write("Q5");
		fireControl(robot, sharedContext);
		assertTrue(sharedContext.isSelected());
		assertTrue(questionCapturePane().isCapturingSharedContext());

		// Q5 creates the context exactly once. The first rectangle is context rather
		// than an ordinary Question region.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Context", addRegion.getText());
		fireControl(robot, addRegion);
		assertFalse(questionCapturePane().isCapturingSharedContext());
		assertEquals("Content parts: 0", lookup(robot, "#question-region-count", Label.class).getText());
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, addRegion);
		fireControl(robot, "#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findAll().stream().anyMatch(question -> "Q5".equals(question.getQuestionCode())));
		WaitForAsyncUtils.waitForFxEvents();
		Question questionFive = repository.findAll().stream()
				.filter(question -> "Q5".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertFalse(questionFive.hasSourceQuestion());
		assertTrue(questionFive.hasSharedContext());
		assertTrue(questionFive.getSharedContext().getLabel().startsWith("CTX-"));
		long contextId = questionFive.getSharedContext().getId();
		assertEquals(contextId, captureService.findPendingMcqSharedContext(booklet).orElseThrow().getId());

		// Q6 receives Q5's context automatically. Its unchecked checkbox means that
		// the context stops after Q6 unless the teacher explicitly extends it.
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		robot.clickOn(questionCode).write("Q6");
		assertTrue(sharedContext.isVisible());
		assertFalse(sharedContext.isSelected());
		assertTrue(lookup(robot, "#shared-context-status", Label.class).getText()
				.contains("Using shared context from the previous question"));

		// Extend the existing context to Q7. This must not start another context
		// capture because Q6 already inherited the stored one.
		fireControl(robot, sharedContext);
		assertTrue(sharedContext.isSelected());
		assertFalse(questionCapturePane().isCapturingSharedContext());
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Region", addRegion.getText());
		fireControl(robot, addRegion);
		fireControl(robot, "#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findAll().stream().anyMatch(question -> "Q6".equals(question.getQuestionCode())));
		WaitForAsyncUtils.waitForFxEvents();
		Question questionSix = repository.findAll().stream().filter(question -> "Q6".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		assertFalse(questionSix.hasSourceQuestion());
		assertTrue(questionSix.hasSharedContext());
		assertEquals(contextId, questionSix.getSharedContext().getId());
		assertEquals(contextId, captureService.findPendingMcqSharedContext(booklet).orElseThrow().getId());

		// Q7 again inherits automatically. Leaving its checkbox unchecked consumes the
		// persisted continuation after Q7 is saved.
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		robot.clickOn(questionCode).write("Q7");
		assertFalse(sharedContext.isSelected());
		assertTrue(lookup(robot, "#shared-context-status", Label.class).getText()
				.contains("Using shared context from the previous question"));
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, addRegion);
		fireControl(robot, "#save-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> repository.findAll().stream().anyMatch(question -> "Q7".equals(question.getQuestionCode())));
		WaitForAsyncUtils.waitForFxEvents();
		Question questionSeven = repository.findAll().stream()
				.filter(question -> "Q7".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertFalse(questionSeven.hasSourceQuestion());
		assertTrue(questionSeven.hasSharedContext());
		assertEquals(contextId, questionSeven.getSharedContext().getId());
		assertTrue(captureService.findPendingMcqSharedContext(booklet).isEmpty());
	}

	@Test
	void metadataSharedContextConversionMayKeepConvertedQuestionRegions(FxRobot robot) throws Exception {
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
		assertTrue(question.isSharedContextCaptureRequired());
		assertTrue(question.hasSharedContext());
		assertEquals(1, question.getRegions().size());

		// Open Search through the real modal application workflow.
		Platform.runLater(() -> {
			try {
				invoke(application, "showQuestionSearch", new Class<?>[] { Stage.class, ApplicationConfig.class },
						primaryStage, applicationConfig);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Search Questions");
		ComboBox<Subject> subjectBox = comboBox(robot, "#question-search-subject");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> subjectBox.getItems().stream().anyMatch(subject -> "Chemistry".equals(subject.getName())));
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjectBox.setValue(chemistry));

		// Select the persisted Question in the currently showing Search dialog.
		selectSearchResult(robot, question.getId());

		// Edit Metadata opens another modal dialog, so schedule the action.
		fireControlLater(robot, "#question-search-edit-metadata");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-metadata-shared-context-required").tryQuery().isPresent());
		CheckBox sharedContext = lookup(robot, "#legacy-metadata-shared-context-required", CheckBox.class);
		assertTrue(sharedContext.isSelected());
		robot.interact(() -> sharedContext.setSelected(false));

		// Saving metadata commits the conversion and then opens the follow-up
		// Question Shared Context Converted dialog synchronously.
		fireControlLater(robot, "#legacy-metadata-save");

		// Do not inspect SQLite until the scheduled save has actually reached the
		// conversion-decision dialog.
		waitForDialogShowing(robot, "Question Shared Context Converted");
		Question converted = questionRepository.findById(question.getId()).orElseThrow();
		assertFalse(converted.isSharedContextCaptureRequired());
		assertFalse(converted.hasSharedContext());
		assertEquals(2, converted.getRegions().size());
		assertEquals(0.10, converted.getRegions().get(0).y(), 0.000001);
		assertEquals(0.40, converted.getRegions().get(1).y(), 0.000001);
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());

		// Retain the safely converted ordinary Question regions.
		fireDialogButton(robot, "Keep converted regions");

		// The completion callback must return to the real Search dialog.
		waitForDialogShowing(robot, "Search Questions");
		assertFalse(lookup(robot, "#cancel-question-edit", Button.class).isVisible());
		Question retained = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(2, retained.getRegions().size());
		assertFalse(retained.hasSharedContext());

		// Close the actual showing Search dialog so its nested event loop unwinds.
		closeDialog(robot, "Search Questions");
	}

	@Test
	void metadataSharedContextConversionMayStartSafeCompleteQuestionRecapture(FxRobot robot) throws Exception {
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

		// Select the Search UI result by its wrapped persistent Question identity.
		selectSearchResult(robot, question.getId());
		fireControlLater(robot, "#question-search-edit-metadata");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-metadata-shared-context-required").tryQuery().isPresent());
		CheckBox sharedContext = lookup(robot, "#legacy-metadata-shared-context-required", CheckBox.class);
		assertTrue(sharedContext.isSelected());
		robot.interact(() -> sharedContext.setSelected(false));
		fireControlLater(robot, "#legacy-metadata-save");

		// Wait for the actual modal conversion decision rather than locating a
		// particular button node in the scene graph.
		waitForDialogShowing(robot, "Question Shared Context Converted");

		// Conversion has already committed before recapture is offered.
		Question converted = questionRepository.findById(question.getId()).orElseThrow();
		assertFalse(converted.isSharedContextCaptureRequired());
		assertFalse(converted.hasSharedContext());
		assertEquals(2, converted.getRegions().size());
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
		fireDialogButton(robot, "Recapture complete question");
		WaitForAsyncUtils.waitForFxEvents();

		// Search is no longer active. Question Capture is now editing the existing
		// question, but its transient replacement region list is empty.
		assertFalse(robot.lookup("#question-search-results").tryQuery().isPresent());
		assertTrue(lookup(robot, "#cancel-question-edit", Button.class).isVisible());
		assertEquals("63", lookup(robot, "#question-code", TextField.class).getText());
		assertEquals("2", lookup(robot, "#question-marks", TextField.class).getText());
		assertEquals("Content parts: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(lookup(robot, "#save-question", Button.class).isDisable());

		// Starting recapture has not deleted the safely converted regions.
		Question duringRecapture = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(2, duringRecapture.getRegions().size());

		// Cancelling recapture must preserve that safe converted state and resume
		// Search Questions.
		// Cancelling recapture invokes the completion callback, which immediately
		// reopens the modal Search dialog.
		fireControlLater(robot, "#cancel-question-edit");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		Question afterCancel = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(2, afterCancel.getRegions().size());
		assertFalse(afterCancel.hasSharedContext());
		assertFalse(afterCancel.isSharedContextCaptureRequired());
		fireDialogButton(robot, "Close");
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
	void multipartQuestionWithoutSharedContextRecordsNone(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(questionCode).write("25a");
		robot.clickOn(marks).write("1");
		CheckBox sharedContext = lookup(robot, "#first-region-shared-context", CheckBox.class);
		assertTrue(sharedContext.isVisible());
		assertFalse(sharedContext.isSelected());
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitForFxEvents();
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().stream()
				.filter(question -> "25a".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertTrue(restored.hasSourceQuestion());
		assertEquals(SharedContextStatus.NONE, restored.getSourceQuestion().getSharedContextStatus());
		assertFalse(restored.hasSharedContext());
	}

	@Test
	void pendingMcqContextAppearsOnlyForImmediateSuccessor(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Paper 1 MCQ",
				ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(new SqliteDatabase(databasePath));
		service.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, booklet, null,
				"Q5", 1, List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.70, 0.12)), classification,
				QuestionResponseType.MULTIPLE_CHOICE, null, new SqliteQuestionCaptureService.PendingSharedContext(
						"CTX-Q5", List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))),
				true));
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Label contextStatus = lookup(robot, "#shared-context-status", Label.class);

		// Q7 is not Q5's immediate successor, so the pending Q5 context must remain
		// invisible and unapplied.
		robot.clickOn(questionCode).write("Q7");
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(contextStatus.isVisible());

		// The pending continuation is still waiting. Entering Q6 now exposes the
		// inherited context immediately.
		robot.interact(questionCode::clear);
		robot.clickOn(questionCode).write("Q6");
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(contextStatus.isVisible());
		assertTrue(contextStatus.getText().contains("Using shared context from the previous question"));
	}

	@Test
	void searchSplitQuestionCapturesOneSharedSharedContextForAllParts(FxRobot robot) throws Exception {
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

		// Search is a reusable Dialog, so retained controls from a hidden instance do
		// not prove that the modal Search window is actually showing.
		waitForDialogShowing(robot, "Search Questions");
		ComboBox<Subject> subjectBox = comboBox(robot, "#question-search-subject");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> subjectBox.getItems().stream().anyMatch(subject -> "Chemistry".equals(subject.getName())));
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjectBox.setValue(chemistry));

		// Select the Search UI result by its wrapped persistent Question identity.
		selectSearchResult(robot, original.getId());
		fireControlLater(robot, "#question-search-split-question");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-part-0-marks").tryQuery().isPresent());
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		ComboBox<LegacyQuestionSplitDialog.SharedContextChoice> sharedContextChoice = comboBox(robot,
				"#legacy-split-shared-context-choice");
		Button continueButton = lookup(robot, "#legacy-split-continue", Button.class);
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			sharedContextChoice.setValue(LegacyQuestionSplitDialog.SharedContextChoice.CAPTURE_NEW_SHARED_CONTEXT);
		});
		assertFalse(continueButton.isDisabled());
		fireControl(robot, continueButton);

		// Starting split capture hides Search. Check the actual Dialog window rather
		// than the reusable Dialog's retained result-list node.
		waitForDialogHidden(robot, "Search Questions");
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);
		assertEquals("67a", questionCode.getText());
		assertEquals("Next Part", save.getText());

		// The first PDF rectangle belongs to the shared context. Accepting it must not
		// create a Question region.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Context", lookup(robot, "#add-question-region", Button.class).getText());
		robot.clickOn("#add-question-region");
		assertEquals("Content parts: 0", lookup(robot, "#question-region-count", Label.class).getText());
		assertTrue(save.isDisabled());

		// The shared context is still transient. Nothing has been added to SQLite.
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());
		assertEquals("67", repository.findById(original.getId()).orElseThrow().getQuestionCode());

		// Capture the actual 67a Question region.
		dragRegionOnDisplayedPage(robot);
		assertEquals("Add Region", lookup(robot, "#add-question-region", Button.class).getText());
		fireControl(robot, "#add-question-region");
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("67b", questionCode.getText());
		assertEquals("Save Split", save.getText());

		// Completing 67a still must not persist either the split or its shared context.
		assertEquals(1, repository.findAll().size());
		assertTrue(contextRepository.findByBooklet(original.getBooklet()).isEmpty());

		// Capture the second Question part.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertFalse(save.isDisabled());
		fireControl(robot, save);

		// Successful split completion resumes the same modal Search Dialog. Wait for
		// its real Stage before inspecting the persisted split or attempting to close
		// it.
		waitForDialogShowing(robot, "Search Questions");
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
		assertEquals(SharedContextStatus.PRESENT, partA.getSourceQuestion().getSharedContextStatus());
		assertTrue(partA.hasSharedContext());
		assertTrue(partB.hasSharedContext());
		assertEquals(partA.getSharedContext().getId(), partB.getSharedContext().getId());
		assertEquals("Question 67 shared context", partA.getSharedContext().getLabel());
		assertEquals(1, partA.getSharedContext().getRegions().size());
		assertEquals(1, partA.getRegions().size());
		assertEquals(1, partB.getRegions().size());

		// Exactly one shared-context entity must have been created for the multipart
		// source Question.
		assertEquals(1, contextRepository.findByBooklet(original.getBooklet()).size());

		// Close the showing Search Dialog specifically; text lookup can match hidden
		// Close buttons retained by other reusable Dialog instances.
		closeDialog(robot, "Search Questions");
	}

	@Test
	void searchSplitQuestionReusesExistingSharedSharedContext(FxRobot robot) throws Exception {
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

		// The legacy single Question is the item that will be corrected into 68a and
		// 68b.
		Question original = questionRepository.save(booklet, "68", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.80, 0.30)), classification, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// An existing correct sibling establishes the SourceQuestion and shared
		// context that the split workflow must reuse.
		SourceQuestion sourceQuestion = sourceQuestionRepository.save(booklet, "68");
		sourceQuestion = sourceQuestionRepository.updatesharedContextStatus(sourceQuestion,
				SharedContextStatus.PRESENT);
		SharedQuestionContext existingContext = contextRepository.save(booklet, "Question 68 shared context",
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
		waitForDialogShowing(robot, "Search Questions");
		ComboBox<Subject> subjectBox = comboBox(robot, "#question-search-subject");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> subjectBox.getItems().stream().anyMatch(subject -> "Chemistry".equals(subject.getName())));
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjectBox.setValue(chemistry));

		// Selection is scoped to the currently showing Search dialog.
		selectSearchResult(robot, original.getId());
		Button splitButton = lookup(robot, "#question-search-split-question", Button.class);
		assertFalse(splitButton.isDisabled());

		// Split opens the definition dialog synchronously.
		fireControlLater(splitButton);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-split-shared-context-choice").tryQuery().isPresent());
		ComboBox<LegacyQuestionSplitDialog.SharedContextChoice> sharedContextChoice = comboBox(robot,
				"#legacy-split-shared-context-choice");

		// Reuse is available because an established SourceQuestion already uses the
		// persisted shared context.
		assertTrue(sharedContextChoice.getItems()
				.contains(LegacyQuestionSplitDialog.SharedContextChoice.REUSE_EXISTING_SHARED_CONTEXT));
		TextField partAMarks = lookup(robot, "#legacy-split-part-0-marks", TextField.class);
		TextField partBMarks = lookup(robot, "#legacy-split-part-1-marks", TextField.class);
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			sharedContextChoice.setValue(LegacyQuestionSplitDialog.SharedContextChoice.REUSE_EXISTING_SHARED_CONTEXT);
		});
		Button continueButton = lookup(robot, "#legacy-split-continue", Button.class);
		assertFalse(continueButton.isDisabled());
		fireControl(robot, continueButton);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Button save = lookup(robot, "#save-question", Button.class);

		// Wait for the actual staged capture state rather than for retained Search
		// nodes to disappear.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> "68a".equals(questionCode.getText()));
		assertEquals("Next Part", save.getText());
		assertEquals("Add Region", lookup(robot, "#add-question-region", Button.class).getText());

		// Capture and stage 68a.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> "68b".equals(questionCode.getText()));
		assertEquals("Save Split", save.getText());

		// Staging 68a must not persist the split or duplicate the shared context.
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
		assertEquals("68", questionRepository.findById(original.getId()).orElseThrow().getQuestionCode());

		// Capture the final part and commit the complete split.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertFalse(save.isDisabled());
		fireControl(robot, save);

		// Successful split completion returns to the actual Search dialog.
		waitForDialogShowing(robot, "Search Questions");
		List<Question> storedQuestions = questionRepository.findAll();
		Question partA = storedQuestions.stream().filter(question -> "68a".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		Question partB = storedQuestions.stream().filter(question -> "68b".equals(question.getQuestionCode()))
				.findFirst().orElseThrow();
		Question reloadedPartC = questionRepository.findById(existingPartC.getId()).orElseThrow();

		// All three Questions belong to the same SourceQuestion group.
		assertEquals(sourceQuestion.getId(), partA.getSourceQuestion().getId());
		assertEquals(sourceQuestion.getId(), partB.getSourceQuestion().getId());
		assertEquals(sourceQuestion.getId(), reloadedPartC.getSourceQuestion().getId());

		// Every member must reuse the exact existing shared context.
		assertEquals(existingContext.getId(), partA.getSharedContext().getId());
		assertEquals(existingContext.getId(), partB.getSharedContext().getId());
		assertEquals(existingContext.getId(), reloadedPartC.getSharedContext().getId());
		assertEquals(SharedContextStatus.PRESENT, partA.getSourceQuestion().getSharedContextStatus());

		// Reuse must not create a duplicate shared-context entity.
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
		assertEquals(List.of("68a", "68b", "68c"),
				storedQuestions.stream().map(Question::getQuestionCode).sorted().toList());
		closeDialog(robot, "Search Questions");
	}

	@Test
	void sharedSharedContextCanBeRecapturedFromSearch(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		assertNotNull(booklet);
		assertNotNull(classification);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext originalContext = contextRepository.save(booklet, "Question 64 shared context",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.50, 0.10)));
		Question question = questionRepository.save(booklet, "64a", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.35, 0.70, 0.20)), classification, true, null,
				originalContext, QuestionResponseType.WRITTEN_RESPONSE);

		// Open Search through the real application workflow.
		Platform.runLater(() -> {
			try {
				invoke(application, "showQuestionSearch", new Class<?>[] { Stage.class, ApplicationConfig.class },
						primaryStage, applicationConfig);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Search Questions");
		ComboBox<Subject> subjectBox = comboBox(robot, "#question-search-subject");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> subjectBox.getItems().stream().anyMatch(subject -> "Chemistry".equals(subject.getName())));
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjectBox.setValue(chemistry));

		// Select the required Question in the showing Search dialog.
		selectSearchResult(robot, question.getId());
		Button recapture = lookup(robot, "#question-search-recapture-shared-context", Button.class);
		assertFalse(recapture.isDisabled());

		// This closes Search and transfers control to shared-context recapture.
		fireControl(robot, recapture);
		waitForDialogHidden(robot, "Search Questions");

		// Recapture opens the exam at the stored shared-context page.
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
		assertEquals(2, pdfWorkspace().getCurrentPageNumber());
		Button saveReplacement = lookup(robot, "#save-shared-context", Button.class);
		assertTrue(saveReplacement.isVisible());
		assertEquals("Save Replacement", saveReplacement.getText());
		TextField sharedContextLabel = lookup(robot, "#shared-context-label", TextField.class);

		// Existing context identity and naming remain read-only during region
		// replacement.
		assertFalse(sharedContextLabel.isVisible());
		assertFalse(sharedContextLabel.isManaged());
		assertEquals("Question 64 shared context", sharedContextLabel.getText());
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		CurriculumSelectorPane classificationPane = field(application, "curriculumSelectorPane",
				CurriculumSelectorPane.class);

		// The linked Question remains visible as read-only context.
		assertEquals("64a", questionCode.getText());
		assertEquals("2", marks.getText());
		assertTrue(writtenResponse.isSelected());
		assertTrue(questionCode.isDisabled());
		assertTrue(marks.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertClassificationControlShows(robot, classification);
		assertTrue(classificationPane.isDisabled());
		assertTrue(lookup(robot, "#question-save-status", Label.class).getText()
				.contains("Recapturing shared context used by Question 64a"));

		// Beginning recapture must not alter the persisted original.
		SharedQuestionContext beforeReplacement = contextRepository.findByBooklet(booklet).stream()
				.filter(context -> context.getId() == originalContext.getId()).findFirst().orElseThrow();
		assertEquals(originalContext.getRegions(), beforeReplacement.getRegions());
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-shared-context-region");

		// Saving invokes the completion callback, which immediately reopens the modal
		// Search dialog. Schedule it so the test thread remains free.
		fireControlLater(robot, "#save-shared-context");
		waitForDialogShowing(robot, "Search Questions");
		SharedQuestionContext replaced = contextRepository.findByBooklet(booklet).stream()
				.filter(context -> context.getId() == originalContext.getId()).findFirst().orElseThrow();
		assertEquals(originalContext.getId(), replaced.getId());
		assertFalse(originalContext.getRegions().equals(replaced.getRegions()));
		Question reloaded = questionRepository.findById(question.getId()).orElseThrow();

		// The Question continues to reference the same shared-context identity.
		assertEquals(originalContext.getId(), reloaded.getSharedContext().getId());
		closeDialog(robot, "Search Questions");
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	private Question searchResultQuestion(Object result) {
		try {

			// Whole-application workflow tests deliberately do not depend on the
			// package-private ui.search result wrapper. Read only the persisted Question
			// exposed through its existing package-private accessor.
			return (Question) invoke(result, "question", new Class<?>[0]);
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	private void selectSearchResult(FxRobot robot, long questionId) throws Exception {

		// Search Questions is a reusable Dialog. Wait for the actual dialog window
		// rather than accepting retained nodes from an earlier hidden instance.
		waitForDialogShowing(robot, "Search Questions");
		DialogPane searchDialog = showingDialogPane(robot, "Search Questions");
		Node resultsNode = searchDialog.lookup("#question-search-results");
		if (!(resultsNode instanceof ListView<?> rawResults)) {
			throw new AssertionError("Showing Search Questions dialog has no results list");
		}
		@SuppressWarnings("unchecked")
		ListView<Object> results = (ListView<Object>) rawResults;

		// Wait until the showing Search dialog contains the requested persistent
		// Question, then select that exact result.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().stream()
				.anyMatch(result -> searchResultQuestion(result).getId() == questionId));
		Object selectedResult = results.getItems().stream()
				.filter(result -> searchResultQuestion(result).getId() == questionId).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
	}
}
