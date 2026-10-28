package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.diagnostics.PerformanceCsvReader;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentity;
import au.edu.eq.questionbank.importer.legacy.LegacyExamKey;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import au.edu.eq.questionbank.service.legacy.LegacyQuestionWorkbookStore;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import au.edu.eq.questionbank.ui.pdf.PdfFilePicker;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CorpusDashboardWorkflowTest extends QuestionBankApplicationUiTestBase {

	private static final int TIMEOUT = 10;

	private static void assertExamAssetsNavigationMetrics(List<PerformanceCsvReader.Entry> records,
			String operationName) {
		PerformanceCsvReader.Entry navigation = records.stream()
				.filter(entry -> operationName.equals(entry.operation())).findFirst().orElseThrow();
		PerformanceCsvReader.Entry load = records.stream()
				.filter(entry -> (operationName + ".load").equals(entry.operation())).findFirst().orElseThrow();
		PerformanceCsvReader.Entry sql = records.stream()
				.filter(entry -> (operationName + ".load.sql.scope").equals(entry.operation())).findFirst()
				.orElseThrow();
		PerformanceCsvReader.Entry connections = records.stream()
				.filter(entry -> (operationName + ".load.sql.connections").equals(entry.operation())).findFirst()
				.orElseThrow();
		PerformanceCsvReader.Entry executions = records.stream()
				.filter(entry -> (operationName + ".load.sql.execute-invocations").equals(entry.operation()))
				.findFirst().orElseThrow();
		assertTrue(navigation.success());
		assertTrue(load.success());
		assertTrue(sql.success());
		assertEquals(0L, navigation.parentId());
		assertEquals(navigation.operationId(), load.parentId());
		assertEquals(load.operationId(), sql.parentId());
		assertEquals(sql.operationId(), connections.parentId());
		assertEquals(sql.operationId(), executions.parentId());
		assertTrue(connections.resultCount() >= 1L);
		assertTrue(executions.resultCount() >= 1L);

		// The source operation must have completed by the time navigation returns.
		assertTrue(navigation.elapsedMillis() >= 0);
		assertTrue(load.elapsedMillis() >= 0);
	}

	@Test
	void corpusDashboardHomeShowsLiveDashboardForWorkingSubject(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Test the current Dashboard-home navigation model rather than the removed
			// Questions-menu shortcut.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		Node applicationRoot = primaryStage.getScene().getRoot();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-exams"));
		@SuppressWarnings("unchecked")
		ComboBox<Subject> subjects = lookupOnFx(robot, applicationRoot, "#curriculum-subject", ComboBox.class);
		TableView<?> exams = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-exams", TableView.class);
		TableView<?> booklets = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-booklets", TableView.class);
		TableView<?> questionWork = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-question-work",
				TableView.class);
		MenuBar menuBar = lookupOnFx(robot, applicationRoot, ".menu-bar", MenuBar.class);

		// Dashboard Home owns the authoritative application Subject without retaining
		// either of the redundant navigation entries removed during #65 close-out.
		assertNotNull(subjects.getValue());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "question-corpus-audit".equals(item.getId())));
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "open-exam-for-capture".equals(item.getId())));

		// Exam lifecycle is now owned by explicit Dashboard Exam selection rather than
		// the unrelated concept of whichever booklet happens to be active in Capture.
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "mark-active-exam-complete".equals(item.getId())));
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "reactivate-active-exam".equals(item.getId())));
		assertNotNull(exams);
		assertNotNull(booklets);
		assertNotNull(questionWork);

		// The persisted Exam and booklet created through the ordinary application
		// workflow remain visible through the refreshed Dashboard snapshot.
		assertEquals(1, exams.getItems().size());
		assertEquals(1, booklets.getItems().size());

		// The retired concatenated work-item presentation must not return.
		assertFalse(nodePresentOnFx(robot, applicationRoot, "#corpus-work-items"));
	}

	@Test
	void dashboardAddExamProducesCorrelatedSqlMetrics(FxRobot robot) throws Exception {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		robot.interact(() -> subjects.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-add-exam").tryQuery().isPresent());
		Path csv = databasePath.resolveSibling("exam-assets-new-exam.csv");
		PerformanceRecorder original = field(application, "performanceRecorder", PerformanceRecorder.class);
		try (PerformanceRecorder measured = new PerformanceRecorder(true, csv)) {
			setField(application, "performanceRecorder", measured);
			try {
				fireControl(robot, "#corpus-dashboard-add-exam");
				WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
						() -> robot.lookup("#exam-assets-state").tryQuery().filter(Label.class::isInstance)
								.map(Label.class::cast).map(label -> "NEW EXAM".equals(label.getText())).orElse(false));

				// Existing navigation rules still require cancellation before returning.
				fireControl(robot, "#exam-assets-new-exam-cancel");
				fireControl(robot, "#exam-assets-return-dashboard");
				WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
						() -> robot.lookup("#corpus-dashboard-no-exams").tryQuery().isPresent());
			} finally {
				setField(application, "performanceRecorder", original);
			}
		}
		assertExamAssetsNavigationMetrics(PerformanceCsvReader.read(csv), "examAssets.open.new");
	}

	@Test
	void dashboardAndCaptureExamAssetsNavigationProduceCorrelatedSqlMetrics(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		Path csv = databasePath.resolveSibling("exam-assets-navigation.csv");
		PerformanceRecorder original = field(application, "performanceRecorder", PerformanceRecorder.class);
		try (PerformanceRecorder measured = new PerformanceRecorder(true, csv)) {
			setField(application, "performanceRecorder", measured);
			try {

				// First exercise Capture → Change Exam using the ordinary control.
				fireControl(robot, "#change-exam-assets");
				WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
						() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());

				// Re-enter Dashboard Home; its actual persistence refresh stays async.
				WaitForAsyncUtils.asyncFx(() -> {
					invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
					return null;
				}).get();
				WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
						() -> robot.lookup("#corpus-dashboard-exams").tryQuery().filter(TableView.class::isInstance)
								.map(TableView.class::cast).map(table -> table.getItems().size() == 1).orElse(false));

				// Then use the Dashboard's real selected-Exam management action.
				Node root = primaryStage.getScene().getRoot();
				Button manage = lookupOnFx(robot, root, "#corpus-dashboard-manage-exam-assets", Button.class);
				assertNotNull(manage);
				assertFalse(manage.isDisabled());
				fireControl(robot, manage);
				WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
						() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
			} finally {

				// The application owns its original session recorder.
				setField(application, "performanceRecorder", original);
			}
		}
		List<PerformanceCsvReader.Entry> records = PerformanceCsvReader.read(csv);
		assertExamAssetsNavigationMetrics(records, "examAssets.open.general");
		assertExamAssetsNavigationMetrics(records, "examAssets.open.selected");
	}

	@Test
	void dashboardAnswerCaptureBusyOverlayClearsAfterRepositoryFailure(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1");
		Question question = captureQuestion(robot, "DASH-FAIL1");
		openAnswerPdfForTest(question);
		WaitForAsyncUtils.asyncFx(() -> {
			pdfWorkspace().closeManagedPdfSessions();
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-summary-missing-answer").tryQuery().isPresent());
		Button missingAnswers = lookup(robot, "#corpus-dashboard-summary-missing-answer", Button.class);
		fireControl(robot, missingAnswers);
		@SuppressWarnings("unchecked")
		TableView<Object> questionWork = lookup(robot, "#corpus-dashboard-question-work", TableView.class);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> questionWork.getItems().size() == 1);
		robot.interact(() -> questionWork.getSelectionModel().selectFirst());

		// The first worker read waits until the test has observed the
		// visible spinner, then deliberately fails. Later repository
		// reads remain functional so Dashboard recovery can complete.
		CountDownLatch workerStarted = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		AtomicBoolean failFirstRead = new AtomicBoolean(true);
		QuestionRepository original = field(application, "questionRepository", QuestionRepository.class);
		QuestionRepository failingRepository = (QuestionRepository) Proxy.newProxyInstance(
				QuestionRepository.class.getClassLoader(), new Class<?>[] { QuestionRepository.class },
				(_, method, arguments) -> {
					if ("findAll".equals(method.getName()) && failFirstRead.compareAndSet(true, false)) {
						workerStarted.countDown();
						if (!releaseWorker.await(TIMEOUT, TimeUnit.SECONDS)) {
							throw new IllegalStateException("Test worker was not released");
						}
						throw new IllegalStateException("Injected Answer Capture queue failure");
					}
					try {
						return method.invoke(original, arguments);
					} catch (InvocationTargetException failure) {
						throw failure.getCause();
					}
				});
		setField(application, "questionRepository", failingRepository);
		ApplicationBusyOverlay overlay = (ApplicationBusyOverlay) primaryStage.getScene().getRoot();
		Node shield = overlay.lookup("#workspace-busy-overlay");
		Button completeAnswer = lookup(robot, "#corpus-dashboard-complete-answer", Button.class);
		fireControl(robot, completeAnswer);
		try {
			assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

			// The worker remains blocked longer than the visual
			// display threshold. The JavaFX thread must still be
			// able to display the window-wide overlay.
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> evaluateOnFx(robot, shield::isVisible));
			assertTrue(evaluateOnFx(robot, overlay::isBusy));
		} finally {
			releaseWorker.countDown();
		}

		// Failed preparation must report the error and release busy
		// ownership before the modal error is acknowledged.
		waitForDialogShowing(robot, "Capture Answers");
		assertFalse(evaluateOnFx(robot, overlay::isBusy));
		assertFalse(evaluateOnFx(robot, shield::isVisible));
		DialogPane dialog = showingDialogPane(robot, "Capture Answers");
		Node okNode = dialog.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof ButtonBase);
		robot.interact(((ButtonBase) okNode)::fire);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertFalse(evaluateOnFx(robot, overlay::isBusy));
		assertFalse(evaluateOnFx(robot, shield::isVisible));
	}

	@Test
	void dashboardBookletCaptureReturnRestoresWorkingSubjectEditing(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = persistUnfinishedQuestionForSubjectUnlock(robot, "27");
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Node root = primaryStage.getScene().getRoot();

		// Enter through the real Dashboard rather than invoking the correction
		// route reflectively. A persisted unfinished Question must be present.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node node = root.lookup("#corpus-dashboard-booklets");
			return node instanceof TableView<?> table && table.getItems().size() == 1;
		}));
		TableView<?> booklets = lookupOnFx(robot, root, "#corpus-dashboard-booklets", TableView.class);
		robot.interact(() -> booklets.getSelectionModel().selectFirst());
		Button capture = lookupOnFx(robot, root, "#corpus-dashboard-capture-questions", Button.class);
		assertFalse(capture.isDisabled());
		fireControl(robot, capture);

		// The production booklet action routes to its already-persisted
		// incomplete Question; active work must retain its Subject lock.
		assertQuestionCorrectionLocksWorkingSubject(robot, question, subjects);
		returnToDashboardAndSwitchWorkingSubject(robot, subjects);
	}

	@Test
	@SuppressWarnings("unchecked")
	void dashboardDescriptorAssignmentPersistsAndRefreshesCounts(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);

		// Use an MCQ with captured Question content and a valid Answer.
		// Classification will therefore be its only outstanding problem.
		fireControl(robot, "#question-response-type-multiple-choice");
		Question saved = captureQuestion(robot, "DASH-D1");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		SyllabusVersion current = curriculumRepository.findVersionsForSubject(saved.getExam().getSubject()).stream()
				.filter(version -> "2025".equals(version.getName())).findFirst().orElseThrow();
		Topic topic = (Topic) curriculumRepository.findByCode(current, "1.1").orElseThrow();

		// The existing fixture uses a Descriptor directly beneath Topic.
		// Create a Subtopic and child Descriptor for the refinement workflow.
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.2", "Classification work", 2);
		Descriptor targetDescriptor = curriculumWriter.insertDescriptor(subtopic, "1.1.2.1", "Assigned Descriptor", 1);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, new SqliteExamWriter(database));
		answerWriter.insertAnswer(saved, "A", List.of());

		// Establish the persisted incomplete-classification state.
		questionRepository.updateClassification(saved.getId(), subtopic);
		Question unclassified = questionRepository.findById(saved.getId()).orElseThrow();
		assertEquals(CurriculumLevel.SUBTOPIC, unclassified.getClassification().getLevel());
		assertEquals("A", unclassified.getAnswer().getAnswerText());

		// Rebuild the application Dashboard from authoritative persistence.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node node = primaryStage.getScene().getRoot().lookup("#corpus-dashboard-booklets");

			// The Dashboard may not yet be mounted while the
			// asynchronous persistence snapshot is loading.
			if (!(node instanceof TableView<?> table)) {
				return false;
			}
			return table.getItems().size() == 1 && table.getItems().getFirst() instanceof BookletCorpusStatus status
					&& status.questionsWithoutDescriptorCount() == 1;
		}));
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		Label selectedCounts = robot.lookup("#corpus-dashboard-selected-exam-counts").queryAs(Label.class);
		assertEquals(1, booklets.getItems().getFirst().questionsWithoutDescriptorCount());
		assertEquals(1, exams.getItems().getFirst().questionSummary().incompleteQuestions());
		assertTrue(selectedCounts.getText().contains("Need work: 1"));
		robot.interact(() -> booklets.getSelectionModel().selectFirst());
		Button assign = lookup(robot, "#corpus-dashboard-assign-descriptors", Button.class);
		assertFalse(assign.isDisable());

		// The production action opens a modal Search dialog.
		fireControlLater(assign);
		waitForDialogShowing(robot, "Search Questions");
		ListView<Object> results = lookupInShowingDialog(robot, "Search Questions", "#question-search-results",
				ListView.class);
		ComboBox<CurriculumNode> descriptorBox = lookupInShowingDialog(robot, "Search Questions",
				"#question-search-selected-descriptor", ComboBox.class);
		ComboBox<Object> scope = lookupInShowingDialog(robot, "Search Questions", "#question-search-scope",
				ComboBox.class);
		Label status = lookupInShowingDialog(robot, "Search Questions", "#question-search-status", Label.class);
		Label narrowing = lookupInShowingDialog(robot, "Search Questions", "#question-search-narrowing", Label.class);
		Button save = lookupInShowingDialog(robot, "Search Questions", "#question-search-save-classification",
				Button.class);

		// The booklet-scoped queue must contain the one outstanding
		// Question and offer its persisted child Descriptor.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> results.getItems().size() == 1 && descriptorBox.getItems().stream()
						.anyMatch(node -> node.getId() == targetDescriptor.getId())));
		assertEquals(1, results.getItems().size());
		assertTrue(narrowing.getText().contains("Assign Descriptors"));
		assertTrue(narrowing.getText().contains(saved.getBooklet().getName()));
		assertTrue(scope.isDisable());
		CurriculumNode choice = descriptorBox.getItems().stream()
				.filter(node -> node.getId() == targetDescriptor.getId()).findFirst().orElseThrow();
		robot.interact(() -> descriptorBox.setValue(choice));
		assertFalse(save.isDisable());

		// Fire the actual inline Save. This uses the application's
		// SqliteQuestionRepository classification updater.
		fireControl(robot, save);

		// Successful persistence must remove this Question from the queue
		// without closing Search or changing its booklet restriction.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot,
				() -> results.getItems().isEmpty() && status.getText().contains("Descriptor assignment complete")));
		assertEquals(0, results.getItems().size());
		assertTrue(scope.isDisable());

		// Confirm that the database contains the Descriptor, not merely
		// a locally modified JavaFX selection.
		Question persisted = questionRepository.findById(saved.getId()).orElseThrow();
		assertEquals(targetDescriptor.getId(), persisted.getClassification().getId());
		assertEquals(CurriculumLevel.DESCRIPTOR, persisted.getClassification().getLevel());
		assertEquals("A", persisted.getAnswer().getAnswerText());
		closeDialog(robot, "Search Questions");

		// Closing Search must trigger the application's existing
		// authoritative Dashboard refresh after persisted changes.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node root = primaryStage.getScene().getRoot();
			Node bookletNode = root.lookup("#corpus-dashboard-booklets");
			Node examNode = root.lookup("#corpus-dashboard-exams");

			// Both tables must be published before testing
			// the refreshed persistence-derived counts.
			if (!(bookletNode instanceof TableView<?> polledBooklets)
					|| !(examNode instanceof TableView<?> polledExams)) {
				return false;
			}
			if (polledBooklets.getItems().size() != 1 || polledExams.getItems().size() != 1) {
				return false;
			}
			return polledBooklets.getItems().getFirst() instanceof BookletCorpusStatus bookletStatus
					&& bookletStatus.questionsWithoutDescriptorCount() == 0
					&& polledExams.getItems().getFirst() instanceof ExamCorpusStatus examStatus
					&& examStatus.questionSummary().incompleteQuestions() == 0;
		}));
		TableView<BookletCorpusStatus> refreshedBooklets = robot.lookup("#corpus-dashboard-booklets")
				.queryAs(TableView.class);
		TableView<ExamCorpusStatus> refreshedExams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		Label refreshedCounts = robot.lookup("#corpus-dashboard-selected-exam-counts").queryAs(Label.class);
		assertEquals(0, refreshedBooklets.getItems().getFirst().questionsWithoutDescriptorCount());
		assertEquals(0, refreshedExams.getItems().getFirst().questionSummary().incompleteQuestions());
		assertTrue(refreshedCounts.getText().contains("Need work: 0"));

		// The selected booklet has no outstanding Descriptor work,
		// so the action must now be disabled.
		robot.interact(() -> refreshedBooklets.getSelectionModel().selectFirst());
		Button refreshedAssign = lookup(robot, "#corpus-dashboard-assign-descriptors", Button.class);
		assertTrue(refreshedAssign.isDisable());
	}

	@Test
	@SuppressWarnings("unchecked")
	void questionWorkDescriptorQueueSavesAcrossExamsAndRefreshesDashboard(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		ExamBooklet firstBooklet = examMetadataPane().getBooklet();
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		SyllabusVersion syllabus = curriculum.findVersionsForSubject(firstBooklet.getExam().getSubject()).stream()
				.filter(SyllabusVersion::isCurrent).findFirst().orElseThrow();
		Unit unit = (Unit) curriculum.findByCode(syllabus, "1").orElseThrow();
		Topic topic = curriculumWriter.insertTopic(unit, "1.2", "Queue topic", 2);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.2.1", "Queue classification", 1);
		Descriptor descriptor = curriculumWriter.insertDescriptor(subtopic, "1.2.1.1", "Assigned Descriptor", 1);
		ExamBooklet sibling = createDescriptorQueueBooklet(examWriter, firstBooklet.getExam(), "Paper 2");
		Exam otherExam = examWriter.createExam(firstBooklet.getExam().getSubject(), "QCAA", 2025, "Practice Exam");
		ExamBooklet otherBooklet = createDescriptorQueueBooklet(examWriter, otherExam, "Paper 1");
		Exam excludedExam = examWriter.createExam(firstBooklet.getExam().getSubject(), "QCAA", 2024, "Prior Exam");
		ExamBooklet excludedBooklet = createDescriptorQueueBooklet(examWriter, excludedExam, "Paper 1");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question first = persistDescriptorQueueQuestion(repository, firstBooklet, "1", subtopic);
		Question second = persistDescriptorQueueQuestion(repository, sibling, "2", subtopic);
		Question third = persistDescriptorQueueQuestion(repository, otherBooklet, "3", subtopic);
		Question excluded = persistDescriptorQueueQuestion(repository, excludedBooklet, "4", subtopic);
		Question alreadyClassified = persistDescriptorQueueQuestion(repository, sibling, "5", descriptor);

		// A second Subject in the same temporary database must never enter the queue.
		Subject physics = curriculum.findAllSubjects().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		SyllabusVersion physicsSyllabus = curriculum.findVersionsForSubject(physics).getFirst();
		CurriculumNode physicsSubtopic = curriculum.findByCode(physicsSyllabus, "1.1.1").orElseThrow();
		Exam physicsExam = examWriter.createExam(physics, "QCAA", 2025, "External Assessment");
		Question foreign = persistDescriptorQueueQuestion(repository,
				createDescriptorQueueBooklet(examWriter, physicsExam, "Paper 1"), "1", physicsSubtopic);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node node = primaryStage.getScene().getRoot().lookup("#corpus-dashboard-summary-missing-descriptors");
			return node instanceof Button button && "4 Missing descriptors".equals(button.getText());
		}));
		ComboBox<Integer> years = lookup(robot, "#corpus-dashboard-filter-year", ComboBox.class);
		robot.interact(() -> years.getSelectionModel().select(Integer.valueOf(2025)));
		fireControl(robot, "#corpus-dashboard-summary-missing-descriptors");
		TableView<QuestionCorpusWorkItem> work = lookup(robot, "#corpus-dashboard-question-work", TableView.class);
		List<Long> displayedIds = List.of(first.getId(), second.getId(), third.getId());
		assertEquals(displayedIds, work.getItems().stream().map(item -> item.question().getId()).sorted().toList());
		writeDescriptorQueueSnapshot(robot, primaryStage.getScene().getRoot(), "dashboard");
		Button assign = lookup(robot, "#corpus-dashboard-question-work-assign-descriptors", Button.class);
		assertFalse(assign.isDisabled());
		fireControlLater(assign);
		waitForDialogShowing(robot, "Search Questions");
		ListView<Object> results = lookupInShowingDialog(robot, "Search Questions",
				"#question-search-results", ListView.class);
		Label status = lookupInShowingDialog(robot, "Search Questions", "#question-search-status", Label.class);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot,
				() -> results.getItems().size() == 3 && results.getSelectionModel().getSelectedItem() != null));
		assertEquals(displayedIds, results.getItems().stream().map(this::descriptorQueueQuestionId).sorted().toList());
		assertEquals("3 Questions still need a Descriptor in this queue.", status.getText());
		writeDescriptorQueueSnapshot(robot, showingDialogPane(robot, "Search Questions"), "search");

		// Save one Question, then return before completion. Both remaining Questions
		// must still be available in QUESTION WORK after the authoritative refresh.
		long savedId = saveNextDescriptorQueueQuestion(robot, descriptor, 2);
		closeDialog(robot, "Search Questions");
		waitForDashboardDescriptorCount(robot, 2);
		work = lookup(robot, "#corpus-dashboard-question-work", TableView.class);
		assertEquals(2, work.getItems().size());
		assertTrue(work.getItems().stream().noneMatch(item -> item.question().getId() == savedId));
		TableView<ExamCorpusStatus> exams = lookup(robot, "#corpus-dashboard-exams", TableView.class);
		assertEquals(2, exams.getItems().stream().mapToInt(exam -> exam.bookletStatuses().stream()
				.mapToInt(BookletCorpusStatus::questionsWithoutDescriptorCount).sum()).sum());
		assertEquals(Integer.valueOf(2025), years.getValue());

		assign = lookup(robot, "#corpus-dashboard-question-work-assign-descriptors", Button.class);
		fireControlLater(assign);
		waitForDialogShowing(robot, "Search Questions");
		saveNextDescriptorQueueQuestion(robot, descriptor, 1);
		saveNextDescriptorQueueQuestion(robot, descriptor, 0);
		status = lookupInShowingDialog(robot, "Search Questions", "#question-search-status", Label.class);
		assertEquals("Descriptor assignment complete.", status.getText());
		writeDescriptorQueueSnapshot(robot, showingDialogPane(robot, "Search Questions"), "complete");
		closeDialog(robot, "Search Questions");
		waitForDashboardDescriptorCount(robot, 0);
		work = lookup(robot, "#corpus-dashboard-question-work", TableView.class);
		assign = lookup(robot, "#corpus-dashboard-question-work-assign-descriptors", Button.class);
		assertTrue(work.getItems().isEmpty());
		assertTrue(assign.isDisabled());

		// Normal inspection still exposes the refreshed Exam/booklet counts.
		fireControl(robot, "#corpus-dashboard-summary-total");
		exams = lookup(robot, "#corpus-dashboard-exams", TableView.class);
		assertEquals(2, exams.getItems().size());
		assertTrue(exams.getItems().stream().flatMap(exam -> exam.bookletStatuses().stream())
				.allMatch(booklet -> booklet.questionsWithoutDescriptorCount() == 0));
		for (Question original : List.of(first, second, third, alreadyClassified)) {
			Question persisted = repository.findById(original.getId()).orElseThrow();
			assertEquals(descriptor.getId(), persisted.getClassification().getId());
			assertEquals(original.getBooklet().getId(), persisted.getBooklet().getId());
			assertEquals(original.getExam().getId(), persisted.getExam().getId());
			assertEquals(original.getRegions().size(), persisted.getRegions().size());
			for (int index = 0; index < original.getRegions().size(); index++) {
				QuestionRegion before = original.getRegions().get(index);
				QuestionRegion after = persisted.getRegions().get(index);
				assertEquals(before.booklet().getId(), after.booklet().getId());
				assertEquals(before.pageNumber(), after.pageNumber());
				assertEquals(before.x(), after.x());
				assertEquals(before.y(), after.y());
				assertEquals(before.width(), after.width());
				assertEquals(before.height(), after.height());
			}
			assertEquals("A", persisted.getAnswer().getAnswerText());
		}
		assertEquals(subtopic.getId(), repository.findById(excluded.getId()).orElseThrow().getClassification().getId());
		assertEquals(physicsSubtopic.getId(), repository.findById(foreign.getId()).orElseThrow().getClassification().getId());
		robot.interact(() -> years.setValue(null));
		waitForDashboardDescriptorCount(robot, 1);
	}

	private ExamBooklet createDescriptorQueueBooklet(SqliteExamWriter writer, Exam exam, String name) throws Exception {
		Path directory = new ManagedDataLayout(pdfDataRoot).examDirectory(exam.getSubject().getName(),
				exam.getProvider().getName(), exam.getYear(), exam.getName());
		Files.createDirectories(directory);
		Path pdf = Files.copy(examPdf, directory.resolve(name + ".pdf"));
		return writer.insertExamBooklet(exam, writer.insertSourceDocument(pdfDataRoot.relativize(pdf).toString()),
				name, ExamBookletQuestionFormat.MIXED);
	}

	private Question persistDescriptorQueueQuestion(SqliteQuestionRepository repository, ExamBooklet booklet,
			String code, CurriculumNode classification) throws Exception {
		Question question = repository.save(booklet, code, "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.7, 0.2)), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		new SqliteAnswerWriter(database, new SqliteExamWriter(database)).insertAnswer(question, "A", List.of());
		return repository.findById(question.getId()).orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private long saveNextDescriptorQueueQuestion(FxRobot robot, Descriptor descriptor, int remaining) throws Exception {
		ListView<Object> results = lookupInShowingDialog(robot, "Search Questions",
				"#question-search-results", ListView.class);
		ComboBox<CurriculumNode> choices = lookupInShowingDialog(robot, "Search Questions",
				"#question-search-selected-descriptor", ComboBox.class);
		Button save = lookupInShowingDialog(robot, "Search Questions", "#question-search-save-classification", Button.class);
		Label status = lookupInShowingDialog(robot, "Search Questions", "#question-search-status", Label.class);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot,
				() -> results.getItems().size() == remaining + 1 && results.getSelectionModel().getSelectedItem() != null
						&& choices.getItems().stream().anyMatch(choice -> choice.getId() == descriptor.getId())));
		long questionId = descriptorQueueQuestionId(results.getSelectionModel().getSelectedItem());
		List<Long> nextIds = results.getItems().stream().map(this::descriptorQueueQuestionId)
				.filter(id -> id != questionId).toList();
		robot.interact(() -> choices.getSelectionModel().select(choices.getItems().stream()
				.filter(choice -> choice.getId() == descriptor.getId()).findFirst().orElseThrow()));
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot,
				() -> results.getItems().size() == remaining && (remaining == 0
						? status.getText().contains("Descriptor assignment complete")
						: results.getSelectionModel().getSelectedItem() != null
								&& descriptorQueueQuestionId(results.getSelectionModel().getSelectedItem()) == nextIds.getFirst()
								&& status.getText().startsWith(Integer.toString(remaining)))));
		assertEquals(nextIds, results.getItems().stream().map(this::descriptorQueueQuestionId).toList());
		return questionId;
	}

	private long descriptorQueueQuestionId(Object result) {
		try {

			// Search result rows are package-private. Inspect their persisted identity
			// through the shared test reflection helper without widening production API.
			return field(result, "question", Question.class).getId();
		} catch (Exception exception) {
			throw new AssertionError("Could not read Search Question identity", exception);
		}
	}

	private void waitForDashboardDescriptorCount(FxRobot robot, int count) throws TimeoutException {
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node node = primaryStage.getScene().getRoot().lookup("#corpus-dashboard-summary-missing-descriptors");
			return node instanceof Button button && (count + " Missing descriptors").equals(button.getText());
		}));
	}

	private void writeDescriptorQueueSnapshot(FxRobot robot, Node node, String name) throws Exception {
		AtomicReference<BufferedImage> snapshot = new AtomicReference<>();
		robot.interact(() -> snapshot.set(SwingFXUtils.fromFXImage(node.snapshot(null, null), null)));
		Path directory = Files.createDirectories(Path.of("target", "issue-219-ui"));
		ImageIO.write(snapshot.get(), "png", directory.resolve(name + ".png").toFile());
	}

	@Test
	void dashboardInspectQuestionsOpensSearchAtExamAndBookletScope(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		Question question = captureQuestion(robot, "DASH-I1");
		ExamBooklet booklet = question.getBooklet();
		WaitForAsyncUtils.asyncFx(() -> {

			// Rebuild Dashboard Home from persistence so the inspection actions operate on
			// the same authoritative Exam/booklet snapshot used in production.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-inspect-exam-questions").tryQuery().isPresent());
		Button inspectExam = lookup(robot, "#corpus-dashboard-inspect-exam-questions", Button.class);
		assertFalse(inspectExam.isDisabled());

		// Search is modal. Schedule the Dashboard action so the test thread remains
		// available to inspect and close the real Search DialogPane.
		fireControlLater(inspectExam);
		waitForDialogShowing(robot, "Search Questions");
		Label examNarrowing = lookupInShowingDialog(robot, "Search Questions", "#question-search-narrowing",
				Label.class);
		@SuppressWarnings("unchecked")
		ListView<Object> examResults = lookupInShowingDialog(robot, "Search Questions", "#question-search-results",
				ListView.class);
		assertEquals("Exam: QCAA 2025 — External Assessment", examNarrowing.getText());
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> examResults.getItems().size() == 1));
		closeDialog(robot, "Search Questions");

		// Closing inspection leaves the existing Dashboard Home in place rather than
		// navigating into a different workspace.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		@SuppressWarnings("unchecked")
		TableView<Object> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		robot.interact(() -> booklets.getSelectionModel().selectFirst());
		Button inspectBooklet = lookup(robot, "#corpus-dashboard-inspect-booklet-questions", Button.class);
		assertFalse(inspectBooklet.isDisabled());
		fireControlLater(inspectBooklet);
		waitForDialogShowing(robot, "Search Questions");
		Label bookletNarrowing = lookupInShowingDialog(robot, "Search Questions", "#question-search-narrowing",
				Label.class);
		@SuppressWarnings("unchecked")
		ListView<Object> bookletResults = lookupInShowingDialog(robot, "Search Questions", "#question-search-results",
				ListView.class);
		assertEquals("Booklet: QCAA 2025 — External Assessment — " + booklet.getName(), bookletNarrowing.getText());
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> bookletResults.getItems().size() == 1));
		closeDialog(robot, "Search Questions");

		// Both Dashboard inspection routes are modal views over Dashboard Home and do
		// not consume or replace its navigation state.
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void dashboardDoesNotExposeLegacyImportAction(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin on the operational home surface because ownership of cancellation is
			// the behaviour under test.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-import-legacy-questions").tryQuery().isEmpty());
	}

	@Test
	void examAssetsLegacyImportActionUsesExistingDialogAndStaysInWorkspaceOnCancel(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		Node applicationRoot = primaryStage.getScene().getRoot();

		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-manage-exam-assets"));
		Button manageExamAssets = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-manage-exam-assets",
				Button.class);
		robot.interact(manageExamAssets::fire);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#exam-assets-workspace"));
		CheckBox legacyImport = lookupOnFx(robot, applicationRoot, "#exam-assets-legacy-import", CheckBox.class);
		Button addExam = lookupOnFx(robot, applicationRoot, "#exam-assets-add-new-exam", Button.class);

		// Selecting legacy mode alone is passive and leaves the existing structural
		// workspace unchanged.
		robot.interact(legacyImport::fire);
		assertTrue(evaluateOnFx(robot, legacyImport::isSelected));
		assertTrue(nodePresentOnFx(robot, applicationRoot, "#exam-assets-workspace"));

		// The import dialog is modal; schedule its opening so
		// the test thread remains available to inspect and close it.
		Platform.runLater(addExam::fire);
		waitForDialogShowing(robot, "Import Legacy Question Metadata");

		// Inspect the actual showing dialog rather than searching
		// the application scene during a modal transition.
		DialogPane importDialog = showingDialogPane(robot, "Import Legacy Question Metadata");
		assertNotNull(importDialog);
		Node cancelNode = importDialog.lookupButton(ButtonType.CANCEL);
		assertTrue(cancelNode instanceof Button);
		robot.interact(((Button) cancelNode)::fire);
		waitForDialogHidden(robot, "Import Legacy Question Metadata");

		// The launch point is now Exam/Assets, so cancelling the chooser retains that
		// workspace and its Dashboard return route.
		assertTrue(nodePresentOnFx(robot, applicationRoot, "#exam-assets-workspace"));
		Button returnDashboard = lookupOnFx(robot, applicationRoot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(evaluateOnFx(robot, () -> returnDashboard.isVisible() && returnDashboard.isManaged()));
	}

	@Test
	void dashboardMissingAnswerCompletionRefreshesHomeAfterExplicitReturn(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1");

		// Create one persisted Written Response Question whose only remaining corpus
		// problem is its missing Answer.
		Question question = captureQuestion(robot, "DASH-A1");

		// Establish the authoritative AnswerFile mapping before entering the Dashboard.
		// Closing the managed session afterwards ensures Dashboard capture must reopen
		// that persisted source rather than accidentally reusing the test setup view.
		openAnswerPdfForTest(question);

		// PdfWorkspacePane owns JavaFX presentation state as well as the managed PDF
		// sessions, so closing it must occur on the JavaFX application thread.
		WaitForAsyncUtils.asyncFx(() -> {
			pdfWorkspace().closeManagedPdfSessions();
			return null;
		}).get();
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin from the same authoritative Dashboard refresh used after ordinary
			// specialised workflow navigation.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {

			// Dashboard content is replaced asynchronously.
			// Traverse the scene graph only on the FX thread.
			Node node = primaryStage.getScene().getRoot().lookup("#corpus-dashboard-summary-missing-answer");
			return node instanceof Button button && "1 Missing answers".equals(button.getText());
		}));
		Button missingAnswers = lookup(robot, "#corpus-dashboard-summary-missing-answer", Button.class);
		Button totalQuestions = lookup(robot, "#corpus-dashboard-summary-total", Button.class);

		// The Question is present exactly once and its Answer remains the outstanding
		// Dashboard task.
		assertEquals("1 Questions", totalQuestions.getText());
		assertEquals("1 Missing answers", missingAnswers.getText());
		fireControl(robot, missingAnswers);
		@SuppressWarnings("unchecked")
		TableView<Object> questionWork = robot.lookup("#corpus-dashboard-question-work").queryAs(TableView.class);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> questionWork.getItems().size() == 1));

		// Route the exact Missing Answer row through the task-specific Dashboard
		// action.
		robot.interact(() -> questionWork.getSelectionModel().selectFirst());
		Button completeAnswer = lookup(robot, "#corpus-dashboard-complete-answer", Button.class);
		assertFalse(completeAnswer.isDisabled());
		fireControl(robot, completeAnswer);
		ComboBox<Question> unanswered = unansweredQuestions(robot);

		// The selected persisted Question must own Answer capture.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot,
				() -> unanswered.getValue() != null && unanswered.getValue().getId() == question.getId()));
		Button returnDashboard = lookup(robot, "#return-corpus-dashboard", Button.class);
		Label selectedAnswerPdf = lookup(robot, "#selected-answer-pdf", Label.class);
		assertTrue(returnDashboard.isVisible());
		assertTrue(returnDashboard.isManaged());

		// Dashboard Answer capture must reopen the authoritative assigned AnswerFile
		// rather than depending on whichever document happened to be open previously.
		ApplicationBusyOverlay busyOverlay = (ApplicationBusyOverlay) primaryStage.getScene().getRoot();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot,
				() -> "exam.pdf".equals(selectedAnswerPdf.getText()) && !busyOverlay.isBusy()));
		assertFalse(busyOverlay.isBusy());

		// Written Response completion is region-based. Use the real pointer gesture
		// only
		// for PDF-region geometry, then semantic control activation for the actions.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		Label regionCount = lookup(robot, "#answer-region-count", Label.class);
		Button saveAnswer = lookup(robot, "#save-answer", Button.class);
		assertEquals("Regions: 1", regionCount.getText());
		assertFalse(saveAnswer.isDisabled());
		fireControl(robot, saveAnswer);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Persistence is the durable completion condition for the Answer task.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> repository.findById(question.getId())
				.filter(Question::hasAnswer).map(stored -> stored.getAnswer().getRegions().size() == 1).orElse(false));

		// Saving must leave Dashboard navigation under explicit user control.
		assertTrue(returnDashboard.isVisible());
		assertTrue(nodePresentOnFx(robot, primaryStage.getScene().getRoot(), "#capture-workspace-mode"));
		fireControl(robot, returnDashboard);

		// Returning home must reload corpus status from persistence and remove exactly
		// the completed Missing Answer without creating a replacement Question.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {

			// The return-to-Dashboard refresh also replaces UI nodes.
			Node node = primaryStage.getScene().getRoot().lookup("#corpus-dashboard-summary-missing-answer");
			return node instanceof Button button && "0 Missing answers".equals(button.getText());
		}));
		Button refreshedMissingAnswers = lookup(robot, "#corpus-dashboard-summary-missing-answer", Button.class);
		Button refreshedTotalQuestions = lookup(robot, "#corpus-dashboard-summary-total", Button.class);
		assertEquals("0 Missing answers", refreshedMissingAnswers.getText());
		assertEquals("1 Questions", refreshedTotalQuestions.getText());
	}

	@Test
	void dashboardOwnedPendingLegacyImportCancelReturnsHome(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Establish an ordinary Dashboard-owned Exam/Assets return session.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-exams").tryQuery().isPresent());
		TableView<?> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		robot.interact(() -> exams.getSelectionModel().selectFirst());
		fireControl(robot, "#corpus-dashboard-manage-exam-assets");
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		ExamAssetsPane pane = field(application, "examAssetsPane", ExamAssetsPane.class);

		// Reproduce the state reached after Dashboard-launched workbook preflight found
		// unresolved structural requirements.
		setField(application, "legacyQuestionImportDashboardOwned", Boolean.TRUE);
		robot.interact(() -> pane.showLegacyImportRequirements("2019", Path.of("legacy-biology.xlsx"),
				List.of(new LegacyBookletRequirement("QCAA", 2020, "Paper 1")), () -> {
				}, () -> {
					try {
						invoke(application, "cancelPendingLegacyQuestionImport", new Class<?>[0]);
					} catch (Exception exception) {
						throw new RuntimeException(exception);
					}
				}));
		Button returnDashboard = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnDashboard.isVisible());

		// Pending structural intake deliberately disables ordinary Return; Cancel
		// Import
		// must therefore consume the transaction and perform the Dashboard return
		// itself.
		assertTrue(returnDashboard.isDisabled());
		fireControl(robot, "#exam-assets-legacy-import-cancel");
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void dashboardQuestionWorkReturnRestoresWorkingSubjectEditing(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = persistUnfinishedQuestionForSubjectUnlock(robot, "28");
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Node root = primaryStage.getScene().getRoot();

		// Build QUESTION WORK from the persisted corpus. Then select the real
		// row and use its Complete Selected Question action.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node node = root.lookup("#corpus-dashboard-question-work");
			return node instanceof TableView<?> table && table.getItems().size() == 1;
		}));
		TableView<?> questionWork = lookupOnFx(robot, root, "#corpus-dashboard-question-work", TableView.class);
		robot.interact(() -> questionWork.getSelectionModel().selectFirst());
		Button complete = lookupOnFx(robot, root, "#corpus-dashboard-complete-question", Button.class);
		assertFalse(complete.isDisabled());
		fireControl(robot, complete);

		// This is the second reported entry route. It must lock the Subject
		// while correcting the selected Question and unlock it on return.
		assertQuestionCorrectionLocksWorkingSubject(robot, question, subjects);
		returnToDashboardAndSwitchWorkingSubject(robot, subjects);
	}

	@Test
	@SuppressWarnings("unchecked")
	void dashboardRetainsExamWithNoCapturedQuestionsAfterAssetsReturn(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// The structural fixture is persisted, but no Question
		// capture has occurred.
		assertTrue(repository.findAll().isEmpty());
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();

		// Wait for the asynchronous persistence-backed snapshot,
		// not merely the Dashboard container.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-exams").tryQuery().filter(TableView.class::isInstance)
						.map(TableView.class::cast).map(table -> table.getItems().size() == 1).orElse(false));
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		assertEquals(1, exams.getItems().size());
		ExamCorpusStatus initial = exams.getItems().getFirst();
		assertEquals("External Assessment", initial.exam().getName());
		assertEquals(0, initial.questionSummary().totalQuestions());
		assertEquals(1, initial.bookletStatuses().size());
		assertFalse(initial.isReadyForCompletion());

		// The first Exam is automatically selected.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> booklets.getItems().size() == 1);
		assertEquals(initial.exam().getId(), exams.getSelectionModel().getSelectedItem().exam().getId());
		assertEquals(0, booklets.getItems().getFirst().encounteredTopLevelQuestionCount());

		// The action may be outside the visible viewport of the new horizontal
		// ScrollPane. Resolve it on the JavaFX thread instead of relying on
		// TestFX's general scene-graph search.
		Node dashboardRoot = primaryStage.getScene().getRoot();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, dashboardRoot, "#corpus-dashboard-manage-exam-assets"));
		Button manageAssets = lookupOnFx(robot, dashboardRoot, "#corpus-dashboard-manage-exam-assets", Button.class);
		assertFalse(manageAssets.isDisabled());

		// Enter the real Exam/Assets workflow.
		fireControl(robot, manageAssets);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		Button returnDashboard = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnDashboard.isVisible());
		assertFalse(returnDashboard.isDisabled());

		// This is the production navigation boundary
		// associated with the reported defect.
		fireControl(robot, returnDashboard);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-exams").tryQuery().filter(TableView.class::isInstance)
						.map(TableView.class::cast).map(table -> table.getItems().size() == 1).orElse(false));
		TableView<ExamCorpusStatus> refreshedExams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> refreshedBooklets = robot.lookup("#corpus-dashboard-booklets")
				.queryAs(TableView.class);
		ExamCorpusStatus refreshed = refreshedExams.getItems().getFirst();

		// Structural identity must survive Dashboard refresh.
		assertEquals(initial.exam().getId(), refreshed.exam().getId());
		assertEquals(1, refreshedExams.getItems().size());
		assertEquals(1, refreshedBooklets.getItems().size());
		assertEquals(0, refreshed.questionSummary().totalQuestions());
		assertEquals(0, refreshedBooklets.getItems().getFirst().encounteredTopLevelQuestionCount());
		assertTrue(repository.findAll().isEmpty());
	}

	@Test
	void dashboardReturnSurvivesCaptureAndExamAssetsTransitions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin from the production home surface so Dashboard-return ownership is
			// established by the real Manage Exam / Assets route.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> {

			// Dashboard reconstruction is asynchronous. Absence during an early polling
			// iteration means "not ready yet", not a failed TestFX query.
			return robot.lookup("#corpus-dashboard-booklets").tryQuery().filter(TableView.class::isInstance)
					.map(TableView.class::cast).map(table -> !table.getItems().isEmpty()).orElse(false);
		});
		TableView<?> dashboardBooklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		robot.interact(() -> dashboardBooklets.getSelectionModel().selectFirst());
		Button manageExamAssets = lookup(robot, "#corpus-dashboard-manage-exam-assets", Button.class);
		assertFalse(manageExamAssets.isDisabled());
		fireControl(robot, manageExamAssets);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		Button assetsReturn = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(assetsReturn.isVisible());
		assertTrue(assetsReturn.isManaged());
		RadioButton bookletSelection = lookup(robot, "#exam-assets-question-select-" + booklet.getId(),
				RadioButton.class);
		if (!bookletSelection.isSelected()) {

			// Selection is semantic application state; pointer hit-testing is irrelevant
			// to this navigation regression.
			fireControl(robot, bookletSelection);
		}
		Button useBooklet = lookup(robot, "#exam-assets-use-selected-booklet", Button.class);
		assertFalse(useBooklet.isDisabled());
		fireControl(robot, useBooklet);

		// Use Selected Booklet is asynchronous. Wait for the resulting capture surface,
		// not for a transient worker flag.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#capture-workspace").tryQuery().isPresent());
		Button captureReturn = lookup(robot, "#return-corpus-dashboard", Button.class);
		assertTrue(captureReturn.isVisible());
		assertTrue(captureReturn.isManaged());

		// Moving back into structural management must transfer the same Dashboard
		// session instead of discarding it.
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		assetsReturn = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(assetsReturn.isVisible());
		assertTrue(assetsReturn.isManaged());
		Node busyOverlay = lookup(robot, "#workspace-busy-overlay", Node.class);

		// The busy overlay now belongs directly to the application
		// Scene root, so it covers both Dashboard and Capture workspaces.
		assertTrue(primaryStage.getScene().getRoot() instanceof ApplicationBusyOverlay);
		assertEquals(primaryStage.getScene().getRoot(), busyOverlay.getParent());
		assertFalse(busyOverlay.isVisible());
		assertFalse(busyOverlay.isManaged());

		// Finally exercise the retained route rather than merely asserting its button.
		fireControl(robot, assetsReturn);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	@SuppressWarnings("unchecked")
	void dashboardShowsPersistedExamWithoutExpectationsOrBooklets(FxRobot robot) throws Exception {
		ComboBox<Subject> subjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();

		// Select the authoritative Working Subject.
		robot.interact(() -> subjectBox.getSelectionModel().select(chemistry));
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> {
			Subject selected = field(application, "workingSubject", Subject.class);
			return selected != null && selected.getId() == chemistry.getId();
		});
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);

		// Persist only the Exam. No booklet, expected count,
		// Question or Answer exists yet.
		Exam exam = examWriter.createExam(chemistry, "QCAA", 2026, "External Assessment");
		assertEquals(1, examWriter.findExamsForSubject(chemistry).size());
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> robot.lookup("#corpus-dashboard-exams").tryQuery()
				.filter(TableView.class::isInstance).map(TableView.class::cast)
				.map(table -> table.getItems().stream().filter(ExamCorpusStatus.class::isInstance)
						.map(ExamCorpusStatus.class::cast)
						.anyMatch(item -> ((ExamCorpusStatus) item).exam().getId() == exam.getId()))
				.orElse(false));
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		assertEquals(1, exams.getItems().size());
		ExamCorpusStatus initial = exams.getItems().getFirst();
		assertEquals(exam.getId(), initial.exam().getId());
		assertEquals(0, initial.questionSummary().totalQuestions());
		assertTrue(initial.bookletStatuses().isEmpty());
		assertNull(initial.assetExpectations().expectedQuestionBookletCount());
		assertNull(initial.assetExpectations().expectedAnswerFileCount());
		assertEquals(0, initial.assetExpectations().availableQuestionBookletCount());
		assertEquals(0, initial.assetExpectations().availableAnswerFileCount());
		assertFalse(initial.isReadyForCompletion());
		assertTrue(booklets.getItems().isEmpty());

		// Even without booklets, the selected Exam must provide
		// access to structural setup.
		Button manageAssets = lookup(robot, "#corpus-dashboard-manage-exam-assets", Button.class);
		assertFalse(manageAssets.isDisabled());

		// Adding expectations must update the existing Exam,
		// not introduce a new Dashboard row.
		examWriter.updateExamAssetExpectations(exam, 2, 1);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshCorpusDashboardHome", new Class<?>[] { long.class }, -1L);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> robot.lookup("#corpus-dashboard-exams").tryQuery()
				.filter(TableView.class::isInstance).map(TableView.class::cast).map(table -> table.getItems().stream()
						.filter(ExamCorpusStatus.class::isInstance).map(ExamCorpusStatus.class::cast).anyMatch(item -> {
							ExamCorpusStatus status = (ExamCorpusStatus) item;
							return status.exam().getId() == exam.getId() && Integer.valueOf(2)
									.equals(status.assetExpectations().expectedQuestionBookletCount());
						}))
				.orElse(false));
		TableView<ExamCorpusStatus> refreshedExams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		ExamCorpusStatus refreshed = refreshedExams.getItems().getFirst();
		assertEquals(1, refreshedExams.getItems().size());
		assertEquals(exam.getId(), refreshed.exam().getId());
		assertEquals(Integer.valueOf(2), refreshed.assetExpectations().expectedQuestionBookletCount());
		assertEquals(Integer.valueOf(1), refreshed.assetExpectations().expectedAnswerFileCount());
		assertEquals(0, refreshed.assetExpectations().availableQuestionBookletCount());
		assertEquals(0, refreshed.assetExpectations().availableAnswerFileCount());
		assertTrue(refreshed.bookletStatuses().isEmpty());
		assertFalse(refreshed.isReadyForCompletion());
	}

	@Test
	void legacyIntakeSeedsFirstBookletPdfChooserAndPreservesDirectoryHistory() throws Exception {
		Path testRoot = databasePath.getParent();
		Path previousDirectory = Files.createDirectories(testRoot.resolve("previous-pdfs"));
		Path workbookDirectory = Files.createDirectories(testRoot.resolve("external-workbooks"));
		Path pdfDirectory = Files.createDirectories(testRoot.resolve("selected-pdfs"));
		Path nextWorkbookDirectory = Files.createDirectories(testRoot.resolve("next-workbooks"));
		Path missingWorkbookDirectory = testRoot.resolve("missing-workbooks");
		Path previousPdf = Files.createFile(previousDirectory.resolve("previous.pdf"));
		Path answerPdf = Files.createFile(pdfDirectory.resolve("answers.pdf"));
		Path questionPdf = Files.createFile(pdfDirectory.resolve("paper-1.pdf"));
		Files.createFile(workbookDirectory.resolve("QCAA-2020-Mock.xlsx"));
		Files.createFile(nextWorkbookDirectory.resolve("QCAA-2020-Sample.xlsx"));
		List<Path> initialDirectories = new ArrayList<>();
		AtomicInteger invocation = new AtomicInteger();

		// Intercept the native chooser while retaining the real PdfFilePicker
		// directory-history implementation.
		var constructor = PdfFilePicker.class.getDeclaredConstructor(Path.class, BiFunction.class);
		constructor.setAccessible(true);
		PdfFilePicker picker = constructor.newInstance(pdfDataRoot,
				(BiFunction<FileChooser, Stage, File>) (chooser, _) -> {
					assertNull(chooser.getInitialFileName());
					File initialDirectory = chooser.getInitialDirectory();
					assertNotNull(initialDirectory);
					initialDirectories.add(initialDirectory.toPath());
					return switch (invocation.getAndIncrement()) {
					case 0 -> previousPdf.toFile();
					case 1 -> null;
					case 2 -> answerPdf.toFile();
					case 3 -> questionPdf.toFile();
					case 4 -> null;
					case 5 -> null;
					default -> throw new AssertionError("Unexpected PDF chooser invocation");
					};
				});

		// Establish a previous PDF folder, as would happen after ordinary
		// Exam/Assets work earlier in the same application session.
		assertEquals(previousPdf,
				WaitForAsyncUtils.asyncFx(() -> picker.chooseAnyPdf(primaryStage, "Previous PDF")).get());

		// Simulate a newly accepted external legacy workbook.
		// Its folder must override older PDF history on the next selection.
		WaitForAsyncUtils.asyncFx(() -> {
			setField(application, "legacyQuestionSourceDirectory", workbookDirectory);
			setField(application, "legacyQuestionPdfDirectorySeeded", Boolean.FALSE);
			return null;
		}).get();

		// An Answer booklet is selected first. Cancellation must preserve
		// the original workbook directory for the next attempt.
		assertNull(WaitForAsyncUtils.asyncFx(() -> invoke(application, "chooseAnswerBookletSource",
				new Class<?>[] { Stage.class, PdfFilePicker.class }, primaryStage, picker)).get());
		assertEquals(answerPdf, WaitForAsyncUtils.asyncFx(() -> invoke(application, "chooseAnswerBookletSource",
				new Class<?>[] { Stage.class, PdfFilePicker.class }, primaryStage, picker)).get());

		// The first successful PDF establishes the directory used by
		// subsequent Question booklet selection.
		assertEquals(questionPdf, WaitForAsyncUtils.asyncFx(() -> invoke(application, "chooseQuestionBookletSource",
				new Class<?>[] { Stage.class, PdfFilePicker.class }, primaryStage, picker)).get());

		// A different legacy workbook begins another intake.
		// Question-first selection must also use its original directory.
		WaitForAsyncUtils.asyncFx(() -> {
			setField(application, "legacyQuestionSourceDirectory", nextWorkbookDirectory);
			setField(application, "legacyQuestionPdfDirectorySeeded", Boolean.FALSE);
			return null;
		}).get();
		assertNull(WaitForAsyncUtils.asyncFx(() -> invoke(application, "chooseQuestionBookletSource",
				new Class<?>[] { Stage.class, PdfFilePicker.class }, primaryStage, picker)).get());

		// If the original workbook directory disappears, fall back to
		// the configured PDF root rather than using an invalid folder.
		WaitForAsyncUtils.asyncFx(() -> {
			setField(application, "legacyQuestionSourceDirectory", missingWorkbookDirectory);
			setField(application, "legacyQuestionPdfDirectorySeeded", Boolean.FALSE);
			return null;
		}).get();
		assertNull(WaitForAsyncUtils.asyncFx(() -> invoke(application, "chooseQuestionBookletSource",
				new Class<?>[] { Stage.class, PdfFilePicker.class }, primaryStage, picker)).get());
		assertEquals(List.of(pdfDataRoot, workbookDirectory, workbookDirectory, pdfDirectory, nextWorkbookDirectory,
				pdfDataRoot), initialDirectories);
	}

	@Test
	void legacyRecheckFeedbackNamesRemainingBookletRequirements(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		List<LegacyBookletRequirement> requirements = List.of(new LegacyBookletRequirement("QCAA", 2020, "MCQ booklet"),
				new LegacyBookletRequirement("QCAA", 2020, "Paper 1"));
		Platform.runLater(() -> {
			try {

				// Exercise the feedback used specifically after Recheck finds that saved
				// structural identities still do not satisfy workbook preflight.
				invoke(application, "showLegacyQuestionImportStillIncomplete", new Class<?>[] { List.class },
						requirements);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Legacy Question Import");
		DialogPane dialog = showingDialogPane(robot, "Legacy Question Import");
		assertEquals("Import not ready — booklet requirements remain.", dialog.getHeaderText());
		assertTrue(dialog.getContentText().contains("QCAA 2020 — MCQ booklet"));
		assertTrue(dialog.getContentText().contains("QCAA 2020 — Paper 1"));
		assertTrue(dialog.getContentText().contains("booklet Name must match"));
		assertTrue(dialog.getContentText().contains("edit that existing booklet"));
		fireDialogButton(robot, "OK");
		waitForDialogHidden(robot, "Legacy Question Import");
	}

	@Test
	void pendingLegacyImportLinksCustomNamedBookletPersistently(FxRobot robot) throws Exception {
		verifyPendingLegacyPaperBookletMapping(robot, false);
	}

	@Test
	void pendingLegacyImportRechecksManagedWorkbookAfterExternalSourceRemoval(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		Subject subject = field(application, "workingSubject", Subject.class);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SyllabusVersion historicalSyllabus = new SqliteCurriculumRepository(database).findVersionsForSubject(subject)
				.stream().filter(version -> "2019".equals(version.getName())).findFirst().orElseThrow();
		Path externalWorkbook = createPendingLegacyWorkbook(
				databasePath.getParent().resolve("incoming").resolve("legacy-resume.xlsx"));
		ManagedDataLayout layout = new ManagedDataLayout(applicationConfig.dataRoot());
		LegacyQuestionWorkbookStore workbookStore = new LegacyQuestionWorkbookStore(layout);
		Path managedWorkbook = workbookStore.manageWorkbook(subject.getName(), historicalSyllabus.getName(),
				externalWorkbook);
		assertEquals(layout.legacyImportDirectory("Chemistry", "2019").resolve("legacy-resume.xlsx"), managedWorkbook);

		// The original selected file is deleted before preflight. The managed
		// workbook must be sufficient for the pending import to continue.
		Files.delete(externalWorkbook);
		assertFalse(Files.exists(externalWorkbook));
		assertTrue(Files.isRegularFile(managedWorkbook));
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		Node applicationRoot = primaryStage.getScene().getRoot();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-exams"));
		TableView<?> exams = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-exams", TableView.class);

		// Wait for the asynchronous Dashboard snapshot, not just the mounted table.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> {
			AtomicBoolean hasExams = new AtomicBoolean();
			robot.interact(() -> hasExams.set(!exams.getItems().isEmpty()));
			return hasExams.get();
		});
		robot.interact(() -> exams.getSelectionModel().selectFirst());
		Button manageAssets = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-manage-exam-assets", Button.class);
		fireControl(robot, manageAssets);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#exam-assets-workspace"));
		Class<?> pendingType = Class
				.forName("au.edu.eq.questionbank.ui.QuestionBankApplication$PendingLegacyQuestionImport");
		var constructor = pendingType.getDeclaredConstructor(Subject.class, SyllabusVersion.class, Path.class,
				Map.class);
		constructor.setAccessible(true);
		Map<LegacyExamKey, LegacyExamIdentity> mappings = Map.of(new LegacyExamKey("QCAA", 2020),
				new LegacyExamIdentity("QCAA", "External Assessment"));
		Object pending = constructor.newInstance(subject, historicalSyllabus, managedWorkbook, mappings);

		// Simulate a Dashboard-owned intake continued after the external file
		// has disappeared; production established this ownership earlier.
		setField(application, "legacyQuestionImportDashboardOwned", Boolean.TRUE);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "continueLegacyQuestionImport",
					new Class<?>[] { Stage.class, applicationConfig.getClass(), pendingType }, primaryStage,
					applicationConfig, pending);
			return null;
		}).get();

		// Batch 26A intentionally removed individual Paper requirement labels.
		// Wait for the real fixed footer and its progress snapshot instead.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#exam-assets-legacy-import-requirements")
						&& nodePresentOnFx(robot, applicationRoot, "#exam-assets-legacy-import-progress"));
		Object retainedPending = field(application, "pendingLegacyQuestionImport", Object.class);
		assertNotNull(retainedPending);
		var managedWorkbookAccessor = pendingType.getDeclaredMethod("managedWorkbookPath");
		managedWorkbookAccessor.setAccessible(true);
		assertEquals(managedWorkbook, managedWorkbookAccessor.invoke(retainedPending));
		VBox footer = lookupOnFx(robot, applicationRoot, "#exam-assets-legacy-import-requirements", VBox.class);
		Label instruction = lookupOnFx(robot, applicationRoot, "#exam-assets-legacy-import-instruction", Label.class);
		Label progress = lookupOnFx(robot, applicationRoot, "#exam-assets-legacy-import-progress", Label.class);
		Button recheck = lookupOnFx(robot, applicationRoot, "#exam-assets-legacy-import-recheck", Button.class);
		Button cancel = lookupOnFx(robot, applicationRoot, "#exam-assets-legacy-import-cancel", Button.class);
		robot.interact(() -> {
			assertTrue(footer.isVisible());
			assertTrue(footer.isManaged());
			assertTrue(instruction.getText().contains("Link each required Paper"));

			// This workbook requires Paper 1 of the registered 2020 Exam.
			// Its PDF is not attached, so the import correctly remains pending.
			assertTrue(progress.getText().contains("Expected Question booklets: 1"), progress.getText());
			assertTrue(progress.getText().contains("Attached: 0 / 1"), progress.getText());
			assertTrue(progress.getText().contains("Linked Papers: 0 / 1"), progress.getText());
			assertNotNull(recheck);
			assertNotNull(cancel);
		});
		assertFalse(nodePresentOnFx(robot, applicationRoot, "#exam-assets-legacy-import-requirement-0"),
				"Removed per-Paper footer details must not reappear");

		// Use the actual pending-import cancellation path and verify that the
		// Dashboard is restored and the pending workbook state is discarded.
		fireControl(robot, cancel);
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-home"));
		assertNull(field(application, "pendingLegacyQuestionImport", Object.class));
	}

	@Test
	void pendingLegacyImportRejectsExtraMcqBooklet(FxRobot robot) throws Exception {
		verifyPendingLegacyPaperBookletMapping(robot, true);
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	private void assertQuestionCorrectionLocksWorkingSubject(FxRobot robot, Question question,
			ComboBox<Subject> subjects) throws TimeoutException {
		Node root = primaryStage.getScene().getRoot();
		ApplicationBusyOverlay overlay = (ApplicationBusyOverlay) root;
		ComboBox<Question> imported = comboBox(robot, "#imported-question");

		// The observable completion is the exact Question selected in the
		// imported capture queue after the asynchronous PDF/corpus load.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot,
						() -> imported.getValue() != null && imported.getValue().getId() == question.getId()
								&& root.lookup("#capture-workspace-mode") != null && !overlay.isBusy()));
		assertTrue(evaluateOnFx(robot, subjects::isDisabled),
				"Active imported Question correction must keep Working Subject locked");
		assertFalse(evaluateOnFx(robot, subjects::isVisible),
				"Working Subject editing must be hidden in the specialised capture workspace");
	}

	private Path createPendingLegacyWorkbook(Path path) throws Exception {
		return createPendingLegacyWorkbook(path, "1");
	}

	private Path createPendingLegacyWorkbook(Path path, String paperCode) throws Exception {
		Files.createDirectories(path.getParent());
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("QCAA");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Year");
			header.createCell(1).setCellValue("Paper");
			header.createCell(2).setCellValue("Question");
			header.createCell(3).setCellValue("Marks");
			header.createCell(4).setCellValue("Topic");
			header.createCell(5).setCellValue("Answer");
			header.createCell(6).setCellValue("Preamble");
			Row question = sheet.createRow(1);
			question.createCell(0).setCellValue(2020);
			question.createCell(1).setCellValue(paperCode);
			question.createCell(2).setCellValue("21a");

			// MCQ Questions must be worth one mark. Preserve three marks
			// for the existing written-response workbook fixtures.
			question.createCell(3).setCellValue("MCQ".equals(paperCode) ? 1 : 3);
			question.createCell(4).setCellValue("3.1.1");
			try (var output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		return path;
	}

	private Question persistUnfinishedQuestionForSubjectUnlock(FxRobot robot, String code) throws Exception {
		ExamBooklet booklet = examMetadataPane().getBooklet();
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		CurriculumNode classification = descriptors.getValue();
		assertNotNull(classification);

		// A saved, response-type-resolved Question without body regions is
		// genuine QUESTION WORK, rather than a transient unsaved capture.
		return new SqliteQuestionRepository(new SqliteDatabase(databasePath)).save(booklet, code, "", 1, List.of(),
				classification, false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
	}

	private void returnToDashboardAndSwitchWorkingSubject(FxRobot robot, ComboBox<Subject> subjects) throws Exception {
		Node root = primaryStage.getScene().getRoot();
		Button returnDashboard = lookupOnFx(robot, root, "#return-corpus-dashboard", Button.class);
		assertTrue(returnDashboard.isVisible());
		fireControl(robot, returnDashboard);

		// Returning from capture must restore both visibility and enablement.
		// Checking visibility alone would have missed the reported defect.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> root.lookup("#corpus-dashboard-home") != null && subjects.isVisible()
						&& subjects.isManaged() && !subjects.isDisabled()));
		assertFalse(evaluateOnFx(robot, subjects::isDisabled));
		Button addSubject = lookupOnFx(robot, root, "#add-subject", Button.class);
		assertTrue(addSubject.isVisible());
		assertTrue(addSubject.isManaged());

		// A real Subject change, not just a visual assertion, confirms that the
		// restored selector is usable after the previous Question-work lock.
		Subject physics = subjects.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjects.getSelectionModel().select(physics));

		// Physics has curriculum but no Exams in the shared fixture. Its empty
		// Exam presentation proves the asynchronous Subject refresh completed.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> {
			Node emptyExamView = root.lookup("#corpus-dashboard-empty-exam-scroll");
			return physics.equals(subjects.getValue()) && emptyExamView != null && emptyExamView.isVisible()
					&& emptyExamView.isManaged();
		}));
		Subject selected = field(application, "workingSubject", Subject.class);
		assertNotNull(selected);
		assertEquals(physics.getId(), selected.getId());
		assertFalse(evaluateOnFx(robot, subjects::isDisabled));
	}

	private void verifyPendingLegacyPaperBookletMapping(FxRobot robot, boolean ambiguous) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		Subject subject = field(application, "workingSubject", Subject.class);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SyllabusVersion syllabus = new SqliteCurriculumRepository(database).findVersionsForSubject(subject).stream()
				.filter(version -> "2019".equals(version.getName())).findFirst().orElseThrow();
		SqliteExamImporter examImporter = new SqliteExamImporter(database, writer);
		String savedName = ambiguous ? "MCQ" : "Trial Paper A";
		String paperCode = ambiguous ? "MCQ" : "1";
		ExamBooklet selectedBooklet = examImporter.importExam(subject, "QCAA", 2020, "External Assessment", savedName,
				"Chemistry/2020/selected-booklet.pdf",
				ambiguous ? ExamBookletQuestionFormat.MULTIPLE_CHOICE : ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		if (ambiguous) {

			// A second attached MCQ booklet is an unexpected extra
			// because this workbook contains only one required Paper.
			examImporter.importExam(subject, "QCAA", 2020, "External Assessment", "MCQ booklet",
					"Chemistry/2020/extra-mcq-booklet.pdf", ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		}
		Path externalWorkbook = createPendingLegacyWorkbook(databasePath.getParent().resolve("incoming")
				.resolve(ambiguous ? "extra-mcq.xlsx" : "custom-booklet.xlsx"), paperCode);
		ManagedDataLayout layout = new ManagedDataLayout(applicationConfig.dataRoot());
		Path managedWorkbook = new LegacyQuestionWorkbookStore(layout).manageWorkbook(subject.getName(),
				syllabus.getName(), externalWorkbook);
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		Class<?> pendingType = Class
				.forName("au.edu.eq.questionbank.ui." + "QuestionBankApplication$PendingLegacyQuestionImport");
		var constructor = pendingType.getDeclaredConstructor(Subject.class, SyllabusVersion.class, Path.class,
				Map.class);
		constructor.setAccessible(true);
		Map<LegacyExamKey, LegacyExamIdentity> mappings = Map.of(new LegacyExamKey("QCAA", 2020),
				new LegacyExamIdentity("QCAA", "External Assessment"));
		Object pending = constructor.newInstance(subject, syllabus, managedWorkbook, mappings);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "continueLegacyQuestionImport",
					new Class<?>[] { Stage.class, applicationConfig.getClass(), pendingType }, primaryStage,
					applicationConfig, pending);
			return null;
		}).get();
		Node root = primaryStage.getScene().getRoot();
		String paperSelectorId = "#exam-assets-question-legacy-paper-" + selectedBooklet.getId();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> nodePresentOnFx(robot, root, paperSelectorId));
		@SuppressWarnings("unchecked")
		ComboBox<String> paperSelector = lookupOnFx(robot, root, paperSelectorId, ComboBox.class);
		assertNotNull(paperSelector);
		String paperName = ambiguous ? "MCQ booklet" : "Paper 1";

		// The saved booklet receives a persistent Paper link.
		// No arbitrary ID-mapping ComboBox exists in the
		// legacy-import section.
		robot.interact(() -> paperSelector.getSelectionModel().select(paperName));
		fireControl(robot, "#exam-assets-question-legacy-link-" + selectedBooklet.getId());

		// Save Paper Link is a synchronous semantic JavaFX action.
		// Wait for its UI updates before reading SQLite.
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(paperCode, writer.findLegacyPaperCodes(selectedBooklet.getExam()).get(selectedBooklet.getId()));
		Label progress = robot.lookup("#exam-assets-legacy-import-progress").queryAs(Label.class);
		assertTrue(progress.getText().contains("Linked Papers: 1 / 1"));
		if (ambiguous) {

			// Both PDFs still exist, but the workbook expects just one.
			// Correctly linking one Paper must not permit an import
			// with an additional unexpected saved booklet.
			assertTrue(progress.getText().contains("Attached: 2 / 1"));
			fireControlLater(robot, "#exam-assets-legacy-import-recheck");
			waitForDialogShowing(robot, "Legacy Question Import");
			DialogPane dialog = showingDialogPane(robot, "Legacy Question Import");
			assertEquals("Import not ready — booklet requirements remain.", dialog.getHeaderText());
			assertTrue(dialog.getContentText().contains("2 attached"));
			fireDialogButton(robot, "OK");
			waitForDialogHidden(robot, "Legacy Question Import");
			assertTrue(new SqliteQuestionRepository(database).findAll().stream()
					.noneMatch(question -> "21a".equals(question.getQuestionCode())
							&& question.getBooklet().getExam().getId() == selectedBooklet.getExam().getId()));

			// Allow the modal Recheck handler to finish after its
			// warning dialog has closed.
			WaitForAsyncUtils.waitForFxEvents();
			Button cancelImport = lookupOnFx(robot, root, "#exam-assets-legacy-import-cancel", Button.class);
			assertNotNull(cancelImport.getOnAction(), "Cancel Import must retain its application callback");
			assertFalse(cancelImport.isDisabled(), "Cancel Import must be enabled after Recheck");
			fireControl(robot, cancelImport);

			// Cancellation must clear the application-owned pending import.
			// Observe completion on the JavaFX thread rather than reading
			// application state immediately after a modal transition.
			WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS, () -> {
				AtomicBoolean cleared = new AtomicBoolean();
				robot.interact(() -> {
					try {
						cleared.set(field(application, "pendingLegacyQuestionImport", Object.class) == null);
					} catch (Exception exception) {
						throw new RuntimeException(exception);
					}
				});
				return cleared.get();
			});
			return;
		}
		assertTrue(progress.getText().contains("Attached: 1 / 1"));
		fireControl(robot, "#exam-assets-legacy-import-recheck");
		waitForDialogShowing(robot, "Legacy Question Import");
		DialogPane result = showingDialogPane(robot, "Legacy Question Import");
		assertTrue(result.getContentText() != null && result.getContentText().contains("Questions imported: 1"));
		fireDialogButton(robot, "OK");
		waitForDialogHidden(robot, "Legacy Question Import");
		Question imported = new SqliteQuestionRepository(database).findAll().stream()
				.filter(question -> "21a".equals(question.getQuestionCode())
						&& question.getBooklet().getExam().getId() == selectedBooklet.getExam().getId())
				.findFirst().orElseThrow();
		assertEquals(selectedBooklet.getId(), imported.getBooklet().getId());
		assertEquals(savedName, imported.getBooklet().getName());
		assertEquals(selectedBooklet.getSourceDocument().getRelativePath(),
				imported.getBooklet().getSourceDocument().getRelativePath());
		assertNull(field(application, "pendingLegacyQuestionImport", Object.class));
	}
}
