package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.Question;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

@ExtendWith(ApplicationExtension.class)
class QuestionBankApplicationWorkflowTest {

	private static final String CURRICULUM_2019 = "CHM Study Checklist [2019 Syllabus].xlsx";
	private static final String CURRICULUM_2025_UNITS_1_2 = "CHM Study Checklist - Unit 1 and 2 [2025 Syllabus].xlsx";
	private static final String CURRICULUM_2025_UNITS_3_4 = "CHM Study Checklist - Unit 3 and 4 [2025 Syllabus].xlsx";

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

	private QuestionBankApplication application;

	private Path examPdf;

	private Path pdfDataRoot;

	private AnswerCapturePane answerCapturePane() {
		try {
			return field(application, "answerCapturePane", AnswerCapturePane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void assertAnswerEntryControlsEnabled(FxRobot robot) {
		assertFalse(lookup(robot, "#answer-text", TextField.class).isDisabled());
		assertFalse(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertFalse(lookup(robot, "#choose-answer-pdf", Button.class).isDisabled());
	}

	private void assertInitialAnswerControlsDisabled(FxRobot robot) {
		assertTrue(lookup(robot, "#answer-text", TextField.class).isDisabled());
		assertTrue(lookup(robot, "#save-answer", Button.class).isDisabled());
		assertTrue(lookup(robot, "#choose-answer-pdf", Button.class).isDisabled());
		assertTrue(lookup(robot, "#add-answer-region", Button.class).isDisabled());
		assertTrue(lookup(robot, "#clear-answer-selection", Button.class).isDisabled());
	}

	private Question captureQuestion(FxRobot robot, String questionCode) throws Exception {
		TextField questionCodeField = lookup(robot, "#question-code", TextField.class);
		robot.clickOn(questionCodeField).write(questionCode);
		dragRegionOnDisplayedPage(robot);
		robot.clickOn("#add-question-region");
		robot.clickOn("#save-question");
		WaitForAsyncUtils.waitForFxEvents();

		Question savedQuestion = unansweredQuestions(robot).getItems().stream()
				.filter(question -> questionCode.equals(question.getQuestionCode())).findFirst().orElse(null);
		assertNotNull(savedQuestion);
		return savedQuestion;
	}

	@SuppressWarnings("unchecked")
	private <T> ComboBox<T> comboBox(FxRobot robot, String selector) {
		return robot.lookup(selector).queryAs(ComboBox.class);
	}

	private void copyCurriculumFile(Path curriculumDataRoot, String version, String fileName) throws Exception {
		URL resource = getClass().getResource("/curriculum/" + fileName);
		if (resource == null) {
			throw new IllegalStateException("Missing curriculum test resource: " + fileName);
		}

		Path destination = curriculumDataRoot.resolve("chemistry").resolve(version).resolve(fileName);
		Files.createDirectories(destination.getParent());
		Files.copy(Path.of(resource.toURI()), destination);
	}

	private void copyCurriculumFiles(Path curriculumDataRoot) throws Exception {
		copyCurriculumFile(curriculumDataRoot, "2019", CURRICULUM_2019);
		copyCurriculumFile(curriculumDataRoot, "2025", CURRICULUM_2025_UNITS_1_2);
		copyCurriculumFile(curriculumDataRoot, "2025", CURRICULUM_2025_UNITS_3_4);
	}

	private Path createTwoPagePdf(Path path) throws Exception {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		try (PDDocument ignored = Loader.loadPDF(path.toFile())) {
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

	private ExamMetadataPane examMetadataPane() {
		try {
			return field(application, "examMetadataPane", ExamMetadataPane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
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
		selectFirst(robot, "#curriculum-subject");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirst(robot, "#curriculum-subtopic");

		ComboBox<String> provider = comboBox(robot, "#exam-provider");
		ComboBox<Integer> year = comboBox(robot, "#exam-year");
		ComboBox<String> assessment = comboBox(robot, "#exam-assessment");
		ComboBox<String> booklet = comboBox(robot, "#exam-booklet");
		robot.interact(() -> {
			provider.getEditor().setText("QCAA");
			year.getSelectionModel().select(Integer.valueOf(2024));
			assessment.getEditor().setText("External Assessment");
			booklet.getEditor().setText("Paper 1 MCQ");
		});
		robot.clickOn("#set-exam");
		WaitForAsyncUtils.waitForFxEvents();
	}

	private void selectFirst(FxRobot robot, String selector) throws Exception {
		ComboBox<Object> comboBox = comboBox(robot, selector);
		WaitForAsyncUtils.asyncFx(() -> comboBox.getSelectionModel().selectFirst()).get();
	}

	private void showPdfMode(String modeName) throws Exception {
		PdfWorkspacePane.DocumentMode mode = PdfWorkspacePane.DocumentMode.valueOf(modeName);
		WaitForAsyncUtils.asyncFx(() -> pdfWorkspace().showDocument(mode)).get();
	}

	private ComboBox<Question> unansweredQuestions(FxRobot robot) {
		return comboBox(robot, "#unanswered-question");
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
	void capturesQuestionThenSavesTextOnlyAnswer(FxRobot robot) throws Exception {
		assertInitialAnswerControlsDisabled(robot);
		prepareExamAndClassification(robot);

		Question savedQuestion = captureQuestion(robot, "Q1");

		Label saveStatus = lookup(robot, "#question-save-status", Label.class);
		TextField questionCode = lookup(robot, "#question-code", TextField.class);
		Label questionRegionCount = lookup(robot, "#question-region-count", Label.class);
		ComboBox<Question> unansweredQuestions = unansweredQuestions(robot);

		assertEquals("Saved Q1 (1 region(s))", saveStatus.getText());
		assertEquals("", questionCode.getText());
		assertEquals("Regions: 0", questionRegionCount.getText());
		assertEquals(1, unansweredQuestions.getItems().size());
		assertEquals(savedQuestion, unansweredQuestions.getItems().getFirst());

		robot.interact(() -> unansweredQuestions.getSelectionModel().select(savedQuestion));
		assertAnswerEntryControlsEnabled(robot);

		TextField answerText = lookup(robot, "#answer-text", TextField.class);
		robot.clickOn(answerText).write("B");
		robot.clickOn("#save-answer");
		WaitForAsyncUtils.waitForFxEvents();

		assertTrue(savedQuestion.hasAnswer());
		assertEquals("B", savedQuestion.getAnswer().getAnswerText());
		assertTrue(unansweredQuestions.getItems().isEmpty());
		assertTrue(answerText.isDisabled());
		assertEquals("", answerText.getText());
		assertNull(unansweredQuestions.getValue());
	}

	@Test
	void movingToNextAnswerPageDisablesControlsForUnacceptedSelection(FxRobot robot) throws Exception {
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

		robot.clickOn("#next-pdf-page");
		WaitForAsyncUtils.waitForFxEvents();

		assertTrue(addAnswerRegion.isDisabled());
		assertTrue(clearAnswerSelection.isDisabled());
	}

	@Start
	void start(Stage stage) throws Exception {
		Path testRoot = Files.createTempDirectory("question-bank-ui-");
		pdfDataRoot = Files.createDirectories(testRoot.resolve("exams"));
		Path curriculumDataRoot = Files.createDirectories(testRoot.resolve("curriculum"));
		copyCurriculumFiles(curriculumDataRoot);
		examPdf = createTwoPagePdf(pdfDataRoot.resolve("exam.pdf"));

		application = new QuestionBankApplication();
		invoke(application, "startApplication", new Class<?>[] { Stage.class, ApplicationConfig.class }, stage,
				new ApplicationConfig(pdfDataRoot, curriculumDataRoot));
		openExamPdfForTest();
	}

	@AfterEach
	void stopApplication() throws Exception {
		application.stop();
	}
}
