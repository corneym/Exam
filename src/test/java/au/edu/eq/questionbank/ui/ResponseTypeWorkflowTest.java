package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class ResponseTypeWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void mixedBookletInfersOnlyWrittenResponse(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Mixed Paper",
				ExamBookletQuestionFormat.MIXED);

		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);

		// One mark alone is ambiguous and must never be used to infer MCQ.
		robot.interact(() -> marks.setText("1"));
		assertFalse(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());

		// A conservative part-letter code safely identifies Written Response.
		robot.interact(() -> questionCode.setText("21a"));
		assertFalse(multipleChoice.isSelected());
		assertTrue(writtenResponse.isSelected());

		// Removing the evidence returns a non-manual inference to unresolved.
		robot.interact(() -> questionCode.setText("21"));
		assertFalse(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());

		// More than one mark also safely identifies Written Response.
		robot.interact(() -> marks.setText("2"));
		assertFalse(multipleChoice.isSelected());
		assertTrue(writtenResponse.isSelected());

		robot.clickOn(multipleChoice);
		robot.interact(() -> questionCode.setText("21a"));

		// An explicit per-question choice overrides subsequent automatic inference.
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertEquals("1", marks.getText());
		assertTrue(marks.isDisabled());
	}

	@Test
	void multipleChoiceBookletLocksResponseTypeAndMarks(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MULTIPLE_CHOICE);

		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);

		// An MCQ-only booklet fixes the response type and the one-mark invariant for
		// every genuinely new Question.
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertTrue(multipleChoice.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertEquals("1", marks.getText());
		assertTrue(marks.isDisabled());

		robot.interact(writtenResponse::fire);

		// Even a programmatic fire of the disabled alternative must not change the
		// booklet-constrained response type.
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertEquals("1", marks.getText());
		assertTrue(marks.isDisabled());
		Question saved = captureQuestion(robot, "MC1");
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, saved.getResponseType());

		// Saving one MCQ starts the next blank Question immediately. The booklet-level
		// constraint must already be active before another question number is entered.
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertTrue(multipleChoice.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertEquals("1", marks.getText());
		assertTrue(marks.isDisabled());
	}

	@Test
	void multipleChoiceUsesChoicesAndAnswerPdfWithoutAnswerRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);

		// Override the Written-response fixture default for this MCQ workflow.
		robot.clickOn(multipleChoice);
		Question question = captureQuestion(robot, "MC1");
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, question.getResponseType());
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		Node multipleChoiceControls = lookup(robot, "#multiple-choice-answer-controls", Node.class);
		Node pdfControls = lookup(robot, "#answer-pdf-controls", Node.class);
		Button choosePdf = lookup(robot, "#choose-answer-pdf", Button.class);
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		Button save = lookup(robot, "#save-answer", Button.class);

		// MCQ capture still uses answer-letter controls, but the answer booklet must
		// also be available because that is where the authoritative letter is read.
		assertTrue(multipleChoiceControls.isVisible());
		assertTrue(multipleChoiceControls.isManaged());
		assertTrue(pdfControls.isVisible());
		assertTrue(pdfControls.isManaged());
		assertFalse(choosePdf.isDisabled());

		// MCQs never capture rectangular Answer regions even though they can display
		// and register an Answer PDF.
		assertFalse(addRegion.isVisible());
		assertFalse(addRegion.isManaged());
		assertTrue(save.isDisabled());
		RadioButton answerA = lookup(robot, "#answer-choice-a", RadioButton.class);
		robot.interact(answerA::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(answerA.isSelected());
		assertFalse(save.isDisabled());
		robot.clickOn(save);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !answerCapturePane().isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();

		// The PDF supports answer lookup only. Persisted MCQ completeness remains the
		// selected answer letter with no AnswerRegion rows.
		assertTrue(stored.hasAnswer());
		assertEquals("A", stored.getAnswer().getAnswerText());
		assertTrue(stored.getAnswer().getRegions().isEmpty());
	}

	@Test
	void newQuestionRequiresExplicitResponseTypeAndPersistsIt(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		Button save = lookup(robot, "#save-question", Button.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);

		// The shared setup selects Written response for older workflow tests.
		// Clear that selection to exercise the explicit-response-type requirement.
		robot.interact(() -> writtenResponse.getToggleGroup().selectToggle(null));
		robot.clickOn(questionCode).write("R1");
		robot.clickOn(marks).write("1");
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		assertTrue(save.isDisabled(), "Question capture must require an explicit response type");
		robot.clickOn(multipleChoice);
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertFalse(save.isDisabled());
		robot.clickOn("#save-question");
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !pane.isSaveInProgress());
		WaitForAsyncUtils.waitForFxEvents();
		Question stored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findAll().stream()
				.filter(question -> "R1".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, stored.getResponseType());

		// Completing a new Question must reset both radio buttons.
		assertFalse(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
	}

	@Test
	void reopeningMultipleChoiceBookletAppliesFormatOnlyToNewQuestions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MULTIPLE_CHOICE);

		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);

		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Simulate historical persisted data that predates the booklet-format rule.
		// Reopening the booklet must not rewrite an existing Question's stored type.
		Question existing = repository.save(originalBooklet, "WR1", "", 1,
				List.of(new QuestionRegion(originalBooklet, 1, 0.10, 0.10, 0.70, 0.20)), classification, false, null,
				null, QuestionResponseType.WRITTEN_RESPONSE);

		Path storedPdf = new PdfStore(pdfDataRoot).resolve(originalBooklet.getSourceDocument().getRelativePath());

		// Reopen the already-persisted booklet through the normal Open Exam workflow.
		WaitForAsyncUtils.asyncFx(() -> {
			examMetadataPane().beginImport();
			examImportDialog().show();
		}).get();

		WaitForAsyncUtils.asyncFx(() -> stageExamPdfForTest(storedPdf)).get();

		robot.clickOn("#confirm-exam-details");
		WaitForAsyncUtils.waitForFxEvents();

		TextField marks = lookup(robot, "#question-marks", TextField.class);

		// The persisted MCQ-only booklet fixes the next genuinely new Question.
		assertTrue(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertTrue(multipleChoice.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertEquals("1", marks.getText());
		assertTrue(marks.isDisabled());

		// A newly opened Exam does not inherit the preceding Question's curriculum
		// classification, so select it again before capturing the new Question.
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);

		Question newlyCaptured = captureQuestion(robot, "MC2");
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, newlyCaptured.getResponseType());

		Question reloadedExisting = repository.findById(existing.getId()).orElseThrow();

		// Booklet format constrains new capture only; historical persisted Question
		// data
		// remains authoritative until explicitly corrected.
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, reloadedExisting.getResponseType());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void unknownResponseTypeDisablesAnswerEntry(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		CurriculumNode classification = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class)
				.getClassification();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));
		Question unknown = repository.save(booklet, "U1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20)), classification, false, null, null,
				QuestionResponseType.UNKNOWN);
		robot.interact(() -> answerCapturePane().refreshQuestions());
		ComboBox<Question> questions = unansweredQuestions(robot);
		Question queued = questions.getItems().stream().filter(question -> question.getId() == unknown.getId())
				.findFirst().orElseThrow();
		robot.interact(() -> questions.getSelectionModel().select(queued));
		Label status = lookup(robot, "#selected-answer-question", Label.class);
		Node multipleChoiceControls = lookup(robot, "#multiple-choice-answer-controls", Node.class);
		Node pdfControls = lookup(robot, "#answer-pdf-controls", Node.class);
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		Button save = lookup(robot, "#save-answer", Button.class);
		assertTrue(status.getText().contains("response type unresolved"));
		assertFalse(multipleChoiceControls.isVisible());
		assertFalse(pdfControls.isVisible());
		assertFalse(addRegion.isVisible());
		assertTrue(save.isDisabled());
		assertFalse(answerCapturePane().canCaptureRegions());
	}

	@Test
	void writtenResponseBookletLocksResponseTypeButLeavesMarksEditable(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Written Paper",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);

		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		TextField marks = lookup(robot, "#question-marks", TextField.class);

		// A Written-Response-only booklet fixes the response type but does not impose
		// any automatic mark value.
		assertFalse(multipleChoice.isSelected());
		assertTrue(writtenResponse.isSelected());
		assertTrue(multipleChoice.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertFalse(marks.isDisabled());

		robot.interact(multipleChoice::fire);

		// The disabled MCQ alternative cannot override the booklet's declared format.
		assertFalse(multipleChoice.isSelected());
		assertTrue(writtenResponse.isSelected());
		assertFalse(marks.isDisabled());
	}

	@Test
	void writtenResponseUsesPersistedTypeDespiteMcqBookletName(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		/*
		 * The fixture booklet is named "Paper 1 MCQ", but the Question itself is
		 * explicitly WRITTEN_RESPONSE.
		 */
		Question question = captureQuestion(robot, "WR1");
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, question.getResponseType());
		ComboBox<Question> questions = unansweredQuestions(robot);
		robot.interact(() -> questions.getSelectionModel().select(question));
		Node multipleChoiceControls = lookup(robot, "#multiple-choice-answer-controls", Node.class);
		Node pdfControls = lookup(robot, "#answer-pdf-controls", Node.class);
		Button addRegion = lookup(robot, "#add-answer-region", Button.class);
		assertFalse(multipleChoiceControls.isVisible());
		assertFalse(multipleChoiceControls.isManaged());
		assertTrue(pdfControls.isVisible());
		assertTrue(pdfControls.isManaged());
		assertTrue(addRegion.isVisible());
		assertTrue(addRegion.isManaged());
	}
}
