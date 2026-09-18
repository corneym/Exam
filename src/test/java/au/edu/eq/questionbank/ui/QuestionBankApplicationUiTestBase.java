package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Per-test application fixture shared by the workflow suites. Each test
 * receives a fresh database, managed PDF root and application instance through
 * TestFX.
 */
@ExtendWith(ApplicationExtension.class)
abstract class QuestionBankApplicationUiTestBase {

	QuestionBankApplication application;
	ApplicationConfig applicationConfig;
	Stage primaryStage;
	Path examPdf;
	Path pdfDataRoot;
	Path databasePath;

	static <T> T field(Object owner, String fieldName, Class<T> type) throws Exception {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return type.cast(field.get(owner));
	}

	private static void fireMouseEvent(ImageView pageView, javafx.event.EventType<MouseEvent> eventType, double x,
			double y, boolean primaryButtonDown) {
		Event.fireEvent(pageView, new MouseEvent(eventType, x, y, x, y, MouseButton.PRIMARY, 1, false, false, false,
				false, primaryButtonDown, false, false, false, false, false, null));
	}

	static Object invoke(Object owner, String methodName, Class<?>[] parameterTypes, Object... arguments)
			throws Exception {
		Method method = owner.getClass().getDeclaredMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return method.invoke(owner, arguments);
	}

	@SuppressWarnings("unchecked")
	static <T> ListView<T> listView(FxRobot robot, String selector) {
		return robot.lookup(selector).queryAs(ListView.class);
	}

	static <T extends Node> T lookup(FxRobot robot, String selector, Class<T> type) {
		return robot.lookup(selector).queryAs(type);
	}

	static void setField(Object owner, String fieldName, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(owner, value);
	}

	// TestFX 4.0.18 discovers only declared @Start methods. Each concrete class
	// supplies a thin forwarding method so the actual setup remains shared here.
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

	AnswerCapturePane answerCapturePane() {
		try {
			return field(application, "answerCapturePane", AnswerCapturePane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	void assertClassificationControlShows(FxRobot robot, CurriculumNode classification) {
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

	Question captureQuestion(FxRobot robot, String questionCode) throws Exception {
		TextField questionCodeField = lookup(robot, "#question-code", TextField.class);
		robot.clickOn(questionCodeField).write(questionCode);
		TextField marksField = lookup(robot, "#question-marks", TextField.class);
		robot.clickOn(marksField).write("1");
		RadioButton multipleChoice = lookup(robot, "#question-response-type-multiple-choice", RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);

		// Most workflow tests exercise written-response capture. Supply that fixture
		// default only when the test has not deliberately selected either response
		// type.
		if (!multipleChoice.isSelected() && !writtenResponse.isSelected()) {
			robot.interact(() -> writtenResponse.setSelected(true));
		}
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
	<T> ComboBox<T> comboBox(FxRobot robot, String selector) {
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

	void dragRegionOnDisplayedPage(FxRobot robot) throws Exception {
		ImageView pageView = lookup(robot, "#pdf-page-view", ImageView.class);
		robot.interact(() -> {
			fireMouseEvent(pageView, MouseEvent.MOUSE_PRESSED, 30, 30, true);
			fireMouseEvent(pageView, MouseEvent.MOUSE_DRAGGED, 160, 150, true);
			fireMouseEvent(pageView, MouseEvent.MOUSE_RELEASED, 160, 150, false);
		});
		WaitForAsyncUtils.waitForFxEvents();
	}

	ExamImportDialog examImportDialog() {
		try {
			return field(application, "examImportDialog", ExamImportDialog.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	ExamMetadataPane examMetadataPane() {
		try {
			return field(application, "examMetadataPane", ExamMetadataPane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	void openAnswerPdfForTest(Question question) throws Exception {
		SelectedPdf selectedPdf = new SelectedPdf(examPdf.toFile(), examPdf, pdfDataRoot);
		WaitForAsyncUtils.asyncFx(() -> answerCapturePane().selectAnswerPdf(question, selectedPdf)).get();
	}

	private void openExamPdfForTest() throws Exception {
		examMetadataPane().selectExamPdf(new SelectedPdf(examPdf.toFile(), examPdf, pdfDataRoot));
	}

	PdfWorkspacePane pdfWorkspace() {
		try {
			return field(application, "pdfWorkspace", PdfWorkspacePane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	void prepareExamAndClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry");
	}

	void prepareExamAndClassification(FxRobot robot, String subjectName) throws Exception {
		prepareExamAndClassification(robot, subjectName, "QCAA", 2024, "External Assessment", "Paper 1 MCQ");
	}

	void prepareExamAndClassification(FxRobot robot, String subjectName, String providerName, int yearValue,
			String assessmentName, String bookletName) throws Exception {
		WaitForAsyncUtils.asyncFx(() -> examImportDialog().show()).get();
		WaitForAsyncUtils.asyncFx(() -> examMetadataPane().stageExamPdf(examPdf)).get();
		ComboBox<Subject> examSubject = comboBox(robot, "#exam-subject");
		Subject selectedSubject = examSubject.getItems().stream()
				.filter(subject -> subjectName.equals(subject.getName())).findFirst().orElseThrow();
		ComboBox<String> provider = comboBox(robot, "#exam-provider");
		ComboBox<Integer> year = comboBox(robot, "#exam-year");
		ComboBox<String> assessment = comboBox(robot, "#exam-assessment");
		ComboBox<String> booklet = comboBox(robot, "#exam-booklet");
		robot.interact(() -> {
			examSubject.setValue(selectedSubject);
			provider.getEditor().setText(providerName);
			year.getSelectionModel().select(Integer.valueOf(yearValue));
			assessment.getEditor().setText(assessmentName);
			booklet.getEditor().setText(bookletName);
		});
		robot.clickOn("#confirm-exam-details");
		WaitForAsyncUtils.waitForFxEvents();
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		RadioButton writtenResponse = lookup(robot, "#question-response-type-written", RadioButton.class);
		robot.interact(() -> writtenResponse.setSelected(true));
	}

	QuestionCapturePane questionCapturePane() {
		try {
			return field(application, "questionCapturePane", QuestionCapturePane.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	void selectFirst(FxRobot robot, String selector) throws Exception {
		ComboBox<Object> comboBox = comboBox(robot, selector);
		WaitForAsyncUtils.asyncFx(() -> comboBox.getSelectionModel().selectFirst()).get();
	}

	void selectFirstFinalClassification(FxRobot robot) throws Exception {
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

	ComboBox<Question> unansweredQuestions(FxRobot robot) {
		return comboBox(robot, "#unanswered-question");
	}
}
