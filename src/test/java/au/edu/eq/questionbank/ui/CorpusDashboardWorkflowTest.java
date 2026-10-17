package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
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
import au.edu.eq.questionbank.service.legacy.LegacyQuestionWorkbookStore;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CorpusDashboardWorkflowTest extends QuestionBankApplicationUiTestBase {

	private static final int TIMEOUT = 10;

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
	void dashboardLegacyImportDialogCancelReturnsHome(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin on the operational home surface because ownership of cancellation is
			// the behaviour under test.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-import-legacy-questions").tryQuery().isPresent());
		Button importLegacy = lookup(robot, "#corpus-dashboard-import-legacy-questions", Button.class);
		assertFalse(importLegacy.isDisabled());

		// The import dialog is modal, so schedule the semantic action and keep the test
		// thread free to close the real DialogPane.
		Platform.runLater(importLegacy::fire);
		waitForDialogShowing(robot, "Import Legacy Question Metadata");
		fireDialogButton(robot, "Cancel");
		waitForDialogHidden(robot, "Import Legacy Question Metadata");

		// Cancelling a Dashboard-owned intake restores the Dashboard rather than
		// stranding the user in Exam/Assets.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void dashboardLegacyImportEntersExamAssetsOwnedWorkflow(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		Node applicationRoot = primaryStage.getScene().getRoot();

		// The workspace changes asynchronously. Every scene-graph
		// lookup must execute on the JavaFX Application Thread.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-import-legacy-questions"));
		Button importLegacy = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-import-legacy-questions",
				Button.class);
		assertFalse(evaluateOnFx(robot, importLegacy::isDisabled));

		// The import dialog is modal; schedule its opening so
		// the test thread remains available to inspect and close it.
		Platform.runLater(importLegacy::fire);
		waitForDialogShowing(robot, "Import Legacy Question Metadata");
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#exam-assets-workspace"));
		Node examAssetsWorkspace = lookupOnFx(robot, applicationRoot, "#exam-assets-workspace", Node.class);
		assertTrue(evaluateOnFx(robot, examAssetsWorkspace::isVisible));
		assertFalse(nodePresentOnFx(robot, applicationRoot, "#exam-assets-import-legacy-questions"));
		Button returnDashboard = lookupOnFx(robot, applicationRoot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(evaluateOnFx(robot, () -> returnDashboard.isVisible() && returnDashboard.isManaged()));

		// Inspect the actual showing dialog rather than searching
		// the application scene during a modal transition.
		DialogPane importDialog = showingDialogPane(robot, "Import Legacy Question Metadata");
		assertNotNull(importDialog);
		Node cancelNode = importDialog.lookupButton(ButtonType.CANCEL);
		assertTrue(cancelNode instanceof Button);
		robot.interact(((Button) cancelNode)::fire);
		waitForDialogHidden(robot, "Import Legacy Question Metadata");

		// Cancellation returns to Dashboard and removes the
		// temporary Exam/Assets workspace.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-home"));
		assertFalse(nodePresentOnFx(robot, applicationRoot, "#exam-assets-workspace"));
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
	void pendingLegacyImportMapsCustomNamedBookletById(FxRobot robot) throws Exception {
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

		// The selected external file is deliberately removed before preflight. Any
		// accidental dependency on the original selection will now fail this workflow.
		Files.delete(externalWorkbook);
		assertFalse(Files.exists(externalWorkbook));
		assertTrue(Files.isRegularFile(managedWorkbook));

		// Mount the real structural workspace used by Dashboard-owned legacy preflight.
		WaitForAsyncUtils.asyncFx(() -> {
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
		Class<?> pendingType = Class
				.forName("au.edu.eq.questionbank.ui.QuestionBankApplication$PendingLegacyQuestionImport");

		// The current pending-import record retains the confirmed
		// worksheet/year-to-Exam mapping alongside the managed workbook.
		var constructor = pendingType.getDeclaredConstructor(Subject.class, SyllabusVersion.class, Path.class,
				Map.class);
		constructor.setAccessible(true);
		Map<LegacyExamKey, LegacyExamIdentity> mappings = Map.of(new LegacyExamKey("QCAA", 2020),
				new LegacyExamIdentity("QCAA", "External Assessment"));
		Object pending = constructor.newInstance(subject, historicalSyllabus, managedWorkbook, mappings);

		// This synthetic continuation represents a Dashboard-launched intake.
		// Production
		// establishes this ownership before the initial workbook dialog is shown.
		setField(application, "legacyQuestionImportDashboardOwned", Boolean.TRUE);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "continueLegacyQuestionImport",
					new Class<?>[] { Stage.class, applicationConfig.getClass(), pendingType }, primaryStage,
					applicationConfig, pending);
			return null;
		}).get();

		// QCAA 2020 Paper 1 is not present in this fixture, so the managed workbook
		// must survive into the pending Exam/Assets preflight transaction.
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-legacy-import-requirement-0").tryQuery().isPresent());
		Object retainedPending = field(application, "pendingLegacyQuestionImport", Object.class);
		assertNotNull(retainedPending);
		var managedWorkbookAccessor = pendingType.getDeclaredMethod("managedWorkbookPath");
		managedWorkbookAccessor.setAccessible(true);
		assertEquals(managedWorkbook, managedWorkbookAccessor.invoke(retainedPending));
		assertTrue(robot.lookup("#exam-assets-legacy-import-requirement-0").tryQuery().isPresent());

		// Clean up through the real pending-import cancellation path.
		fireControl(robot, "#exam-assets-legacy-import-cancel");
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void pendingLegacyImportResolvesAmbiguousMcqBookletsById(FxRobot robot) throws Exception {
		verifyPendingLegacyPaperBookletMapping(robot, true);
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
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

	private void verifyPendingLegacyPaperBookletMapping(FxRobot robot, boolean ambiguous) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		Subject subject = field(application, "workingSubject", Subject.class);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SyllabusVersion syllabus = new SqliteCurriculumRepository(database).findVersionsForSubject(subject).stream()
				.filter(version -> "2019".equals(version.getName())).findFirst().orElseThrow();
		SqliteExamImporter examImporter = new SqliteExamImporter(database, new SqliteExamWriter(database));
		String savedName = ambiguous ? "MCQ" : "Trial Paper A";
		String paperCode = ambiguous ? "MCQ" : "1";
		ExamBooklet selectedBooklet = examImporter.importExam(subject, "QCAA", 2020, "External Assessment", savedName,
				"Chemistry/2020/selected-booklet.pdf",
				ambiguous ? ExamBookletQuestionFormat.MULTIPLE_CHOICE : ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		if (ambiguous) {

			// Two conventional MCQ aliases must force an explicit
			// choice rather than selecting the first saved booklet.
			examImporter.importExam(subject, "QCAA", 2020, "External Assessment", "MCQ booklet",
					"Chemistry/2020/other-mcq-booklet.pdf", ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		}
		Path externalWorkbook = createPendingLegacyWorkbook(databasePath.getParent().resolve("incoming")
				.resolve(ambiguous ? "ambiguous-mcq.xlsx" : "custom-booklet.xlsx"), paperCode);
		ManagedDataLayout layout = new ManagedDataLayout(applicationConfig.dataRoot());
		Path managedWorkbook = new LegacyQuestionWorkbookStore(layout).manageWorkbook(subject.getName(),
				syllabus.getName(), externalWorkbook);

		// Enter the actual structural workspace before resuming the
		// retained workbook, as a teacher would do from Dashboard.
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
		WaitForAsyncUtils.waitFor(TIMEOUT, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, root, "#exam-assets-legacy-import-booklet-0"));
		@SuppressWarnings("unchecked")
		ComboBox<String> choice = lookupOnFx(robot, root, "#exam-assets-legacy-import-booklet-0", ComboBox.class);
		assertNotNull(choice);
		String selectedOption = choice.getItems().stream()
				.filter(option -> option.contains("[ID " + selectedBooklet.getId() + "]")).findFirst().orElseThrow();
		if (ambiguous) {

			// Confirm that the second MCQ alias was actually persisted
			// under the same Exam before inspecting the available choices.
			List<ExamBooklet> persistedBooklets = new SqliteExamWriter(database).findAllExamBooklets();
			ExamBooklet alternative = persistedBooklets.stream()
					.filter(booklet -> booklet.getExam().getId() == selectedBooklet.getExam().getId()
							&& "MCQ booklet".equals(booklet.getName()))
					.findFirst()
					.orElseThrow(() -> new AssertionError("The second MCQ booklet was not persisted. "
							+ "Persisted booklets: " + persistedBooklets.stream()
									.map(booklet -> booklet.getName() + " [ID " + booklet.getId() + "]").toList()));

			// Read the JavaFX ComboBox contents on the FX thread.
			List<String> availableOptions = new ArrayList<>();
			robot.interact(() -> availableOptions.addAll(choice.getItems()));

			// Compare persisted identity rather than the precise formatting
			// of the option's display text.
			assertTrue(
					availableOptions.stream().anyMatch(option -> option.contains("[ID " + alternative.getId() + "]")),
					() -> "The second saved MCQ booklet is missing from " + "the selection UI. Expected booklet ID "
							+ alternative.getId() + "; actual choices: " + availableOptions);
		}

		// Direct selection is deterministic under TestFX and Xvfb.
		robot.interact(() -> choice.getSelectionModel().select(selectedOption));
		fireControl(robot, "#exam-assets-legacy-import-recheck");

		// Successful import refreshes the corpus asynchronously and
		// displays the normal import-result dialog.
		waitForDialogShowing(robot, "Legacy Question Import");
		DialogPane result = showingDialogPane(robot, "Legacy Question Import");
		assertTrue(result.getContentText() != null && result.getContentText().contains("Questions imported: 1"),
				() -> "Expected successful legacy import, but received:" + "\nHeader: " + result.getHeaderText()
						+ "\nContent: " + result.getContentText());
		fireDialogButton(robot, "OK");
		waitForDialogHidden(robot, "Legacy Question Import");
		List<Question> persisted = new SqliteQuestionRepository(database).findAll();
		Question imported = persisted.stream().filter(question -> "21a".equals(question.getQuestionCode())).findFirst()
				.orElseThrow();

		// This is the authoritative acceptance check: the Question
		// refers to the explicitly selected persisted booklet ID.
		assertEquals(selectedBooklet.getId(), imported.getBooklet().getId());
		assertEquals(savedName, imported.getBooklet().getName());
		assertEquals("External Assessment", imported.getBooklet().getExam().getName());
		assertEquals(selectedBooklet.getSourceDocument().getRelativePath(),
				imported.getBooklet().getSourceDocument().getRelativePath());

		// A completed import must not retain stale selections.
		assertNull(field(application, "pendingLegacyQuestionImport", Object.class));
	}
}
