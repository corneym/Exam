package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
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
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

@ExtendWith(ApplicationExtension.class)
class QuestionBankApplicationWorkflowTest {
	private static final String CURRICULUM_2019 = "CHM Study Checklist [2019 Syllabus].xlsx";
	private static final String CURRICULUM_2025_UNITS_1_2 = "CHM Study Checklist - Unit 1 and 2 [2025 Syllabus].xlsx";
	private static final String CURRICULUM_2025_UNITS_3_4 = "CHM Study Checklist - Unit 3 and 4 [2025 Syllabus].xlsx";
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
	void canSelectAndPersistHistoricalSyllabusClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> classifications = comboBox(robot, "#curriculum-subtopic");
		assertEquals(2, syllabuses.getItems().size());
		assertNotNull(syllabuses.getValue());
		assertEquals("2025", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("1", units.getItems().getFirst().getCode());
		assertNotNull(classifications.getValue());
		assertEquals("2025", model.getClassification().getSyllabusVersion().getName());
		SyllabusVersion historicalSelection = selectSyllabus(robot, "2019");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("2019", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("3", units.getItems().getFirst().getCode());
		assertTrue(topics.getItems().isEmpty());
		assertTrue(classifications.getItems().isEmpty());
		assertFalse(units.isDisabled());
		assertTrue(topics.isDisabled());
		assertTrue(classifications.isDisabled());
		assertNull(units.getValue());
		assertNull(topics.getValue());
		assertNull(classifications.getValue());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirst(robot, "#curriculum-subtopic");
		CurriculumNode historicalClassification = model.getClassification();
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
		selectFirst(robot, "#curriculum-subtopic");
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		assertEquals(historical, model.getClassification().getSyllabusVersion());
		assertEquals("2.1.1", model.getClassification().getCode());
	}

	@Test
	void capturesQuestionThenSavesTextOnlyAnswer(FxRobot robot) throws Exception {
		assertInitialAnswerControlsDisabled(robot);
		prepareExamAndClassification(robot);
		Question savedQuestion = captureQuestion(robot, "Q1");
		assertEquals(CurriculumLevel.DESCRIPTOR, savedQuestion.getClassification().getLevel());
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
		selectFirst(robot, "#curriculum-subtopic");
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> classifications = comboBox(robot, "#curriculum-subtopic");
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		robot.interact(() -> syllabuses.getSelectionModel().clearSelection());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertNull(syllabuses.getValue());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertFalse(syllabuses.isDisabled());
		assertEquals(2, syllabuses.getItems().size());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, classifications)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
		selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirst(robot, "#curriculum-subtopic");
		robot.interact(() -> subjects.getSelectionModel().clearSelection());
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getClassification());
		assertNull(syllabuses.getValue());
		assertTrue(syllabuses.getItems().isEmpty());
		assertTrue(syllabuses.isDisabled());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, classifications)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
	}

	@Test
	void duplicateQuestionCodeIsRejectedWithoutLosingAcceptedRegions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		captureQuestion(robot, "Q7");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirst(robot, "#curriculum-subtopic");
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
	void refreshingSubjectsReloadsVersionsWithoutReplacingHistoricalSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		SyllabusVersion historical = selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirst(robot, "#curriculum-subtopic");
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
		assertEquals(classification, comboBox(robot, "#curriculum-subtopic").getValue());
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

	private void copyCurriculumFiles(Path curriculumDataRoot) throws Exception {
		createCurriculumFile(curriculumDataRoot, "2019", CURRICULUM_2019, "1");
		createCurriculumFile(curriculumDataRoot, "2025", CURRICULUM_2025_UNITS_1_2, "1");
		createCurriculumFile(curriculumDataRoot, "2025", CURRICULUM_2025_UNITS_3_4, "3");
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

	private void createCurriculumFile(Path curriculumDataRoot, String version, String fileName, String unitCode)
			throws Exception {
		Path destination = curriculumDataRoot.resolve("chemistry").resolve(version).resolve(fileName);
		Files.createDirectories(destination.getParent());
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Code");
			header.createCell(1).setCellValue("Content");
			Row unit = sheet.createRow(1);
			unit.createCell(0).setCellValue(unitCode);
			unit.createCell(1).setCellValue("Test unit " + unitCode);
			String topicCode = unitCode + ".1";
			Row topic = sheet.createRow(2);
			topic.createCell(0).setCellValue(topicCode);
			topic.createCell(1).setCellValue("Test topic");
			String subtopicCode = topicCode + ".1";
			Row subtopic = sheet.createRow(3);
			subtopic.createCell(0).setCellValue(subtopicCode);
			subtopic.createCell(1).setCellValue("Test subtopic");
			Row descriptor = sheet.createRow(4);
			descriptor.createCell(0).setCellValue(subtopicCode + ".1");
			descriptor.createCell(1).setCellValue("Test descriptor");
			try (OutputStream output = Files.newOutputStream(destination)) {
				workbook.write(output);
			}
		}
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
		selectFirst(robot, "#curriculum-subtopic");
	}

	private void selectFirst(FxRobot robot, String selector) throws Exception {
		ComboBox<Object> comboBox = comboBox(robot, selector);
		WaitForAsyncUtils.asyncFx(() -> comboBox.getSelectionModel().selectFirst()).get();
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
