package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import au.edu.eq.questionbank.service.workpackage.ContributionSnapshotReader;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentRemovalService;
import au.edu.eq.questionbank.service.workpackage.SqliteContributionReturnLedger;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentExporter;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentInstallationCoordinator;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentIssuanceService;
import au.edu.eq.questionbank.ui.audit.CorpusDashboardPane;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;

@Tag("ui")
@Tag("workflow-ui")
@ExtendWith(ApplicationExtension.class)
class ContributorAssignmentDashboardWorkflowTest {

	private QuestionBankApplication application;
	private UUID assignmentId;
	private ApplicationConfig contributor;
	private Stage primaryStage;
	private Path assignmentArchive;

	private static void awaitBarrier(CountDownLatch entered, CountDownLatch release) {
		entered.countDown();
		try {
			if (!release.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for test release");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Test worker interrupted", exception);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void assignmentSelectorDisplaysAuthoritativeExamDescription(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		robot.interact(() -> {
			assertEquals(1, assignments.getItems().size());
			var selected = assignments.getValue();
			assertNotNull(selected);
			assertEquals(assignmentId, selected.assignmentId());
			String description = assignments.getConverter().toString(selected);

			// Display meaningful Exam metadata rather than
			// installation-specific identifiers.
			assertEquals("QCAA 2025 External Assessment", description);
			assertFalse(description.contains(selected.contributorName()));
			assertFalse(description.contains(selected.assignmentId().toString()));
			assertEquals("", assignments.getConverter().toString(null));
		});

		// Refreshing the assignment catalogue must preserve
		// both its display description and selected identity.
		robot.interact(() -> invoke("refreshContributorAssignmentToolbar", new Class<?>[0]));
		robot.interact(() -> {
			assertEquals(1, assignments.getItems().size());
			assertNotNull(assignments.getValue());
			assertEquals(assignmentId, assignments.getValue().assignmentId());
			assertEquals("QCAA 2025 External Assessment", assignments.getConverter().toString(assignments.getValue()));
		});
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
	}

	@Test
	void cancelledHiddenInstallationReleasesApplication(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Task<WorkAssignmentInstallationCoordinator.Result> installer = new ApplicationBackgroundTask<>(() -> {
			awaitBarrier(entered, release);
			throw new AssertionError("Cancelled installer must not continue");
		});
		try {
			startInstallation(robot, installer);
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			hideInstallationProgress(robot);
			robot.interact(() -> assertTrue(installer.cancel()));
			WaitForAsyncUtils.waitForFxEvents();
			robot.interact(() -> assertEquals(Worker.State.CANCELLED, installer.getState()));
			assertControlsReleased(robot);
		} finally {
			release.countDown();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void contributionExportAvailabilityTracksAssignmentMode(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		Menu exportMenu = menuBar.getMenus().stream().filter(menu -> "E_xport".equals(menu.getText())).findFirst()
				.orElseThrow();
		MenuItem contribution = exportMenu.getItems().stream()
				.filter(item -> "export-assignment-contribution".equals(item.getId())).findFirst().orElseThrow();
		MenuItem snapshot = exportMenu.getItems().stream()
				.filter(item -> "export-subject-snapshot".equals(item.getId())).findFirst().orElseThrow();
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		Button remove = robot.lookup("#corpus-dashboard-remove-assignment").queryButton();

		// An installed active assignment is selected automatically.
		assertEquals(1, assignments.getItems().size());
		assertEquals(assignmentId, assignments.getValue().assignmentId());
		assertFalse(assignments.isDisabled());
		assertFalse(remove.isDisabled());
		assertFalse(contribution.isDisable());
		assertTrue(snapshot.isDisable());

		// There is no separate assignment mode to exit or Switch button.
		robot.interact(() -> {
			assertNull(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exit-assignment"));
			assertNull(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-switch-assignment"));
		});

		// Selecting the already active assignment must not change its
		// persistent identity or remove the export permission.
		robot.interact(() -> assignments.getSelectionModel().selectFirst());
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
		assertFalse(contribution.isDisable());
		assertTrue(snapshot.isDisable());
	}

	@Test
	@SuppressWarnings("unchecked")
	void contributorCanInspectAssignedExamAssetsWithoutChangingStructure(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		SqliteDatabase database = new SqliteDatabase(contributor.databasePath());
		var session = sessions().active().orElseThrow();
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		Subject subject = new Subject(session.subjectLocalId(), "Biology");
		Exam exam = examWriter.findExamsForSubject(subject).stream()
				.filter(candidate -> candidate.getId() == session.examLocalId()).findFirst().orElseThrow();
		List<ExamBooklet> booklets = examWriter.findExamBooklets(exam);
		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(exam);
		assertEquals(2, booklets.size());
		assertEquals(1, answerFiles.size());
		AnswerFile answerFile = answerFiles.getFirst();
		Path beforePath = Files.createTempDirectory("exam-assets-before-").resolve("snapshot.db");
		database.createConsistentSnapshot(beforePath);
		var before = new ContributionSnapshotReader(beforePath).read(assignmentId);
		Button inspect = robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton();
		assertFalse(inspect.isDisabled());
		assertEquals("Inspect Exam / Assets", inspect.getText());
		fireExamAssetsAction(inspect, "Open Exam Assets");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> WaitForAsyncUtils
						.asyncFx(() -> primaryStage.getScene().getRoot().lookup("#exam-assets-exam") != null)
						.get(3, TimeUnit.SECONDS));

		// Use direct scene-graph lookup on the FX thread rather
		// than nesting TestFX lookup operations inside interact().
		robot.interact(() -> {
			var root = primaryStage.getScene().getRoot();
			ComboBox<Exam> examBox = (ComboBox<Exam>) root.lookup("#exam-assets-exam");
			assertEquals(1, examBox.getItems().size());
			assertEquals(exam.getId(), examBox.getValue().getId());
			assertTrue(examBox.isDisabled(), "Assignment scope must remain fixed");
			List<String> protectedControls = List.of("#exam-assets-edit", "#exam-assets-add-new-exam",
					"#exam-assets-add-question-booklet", "#exam-assets-add-answer-booklet",
					"#exam-assets-legacy-import", "#exam-assets-provider", "#exam-assets-year",
					"#exam-assets-assessment", "#exam-assets-expected-question-booklets",
					"#exam-assets-expected-answer-booklets");
			for (String selector : protectedControls) {
				Node control = root.lookup(selector);
				assertNotNull(control, selector);
				assertTrue(control.isDisabled(), selector + " must remain read-only");
			}
			for (ExamBooklet booklet : booklets) {
				long id = booklet.getId();
				List<String> protectedBookletControls = List.of("#exam-assets-question-edit-" + id,
						"#exam-assets-question-delete-" + id, "#exam-assets-question-save-" + id,
						"#exam-assets-question-name-" + id, "#exam-assets-question-expected-" + id,
						"#exam-assets-question-answer-" + id, "#exam-assets-question-format-mcq-" + id,
						"#exam-assets-question-format-written-" + id, "#exam-assets-question-format-both-" + id);
				for (String selector : protectedBookletControls) {
					Node control = root.lookup(selector);
					assertNotNull(control, selector);
					assertTrue(control.isDisabled(), selector + " must remain read-only");
				}
				ComboBox<?> association = (ComboBox<?>) root.lookup("#exam-assets-question-answer-" + id);
				assertNotNull(association.getValue());
				assertEquals("Marking Guide", association.getValue().toString());
				assertFalse(root.lookup("#exam-assets-question-view-" + id).isDisabled());
				assertFalse(root.lookup("#exam-assets-question-select-" + id).isDisabled());
			}
			assertTrue(root.lookup("#exam-assets-answer-delete-" + answerFile.getId()).isDisabled());
			assertTrue(root.lookup("#exam-assets-answer-explanations-" + answerFile.getId()).isDisabled());
		});
		PdfWorkspacePane pdfWorkspace = field("pdfWorkspace", PdfWorkspacePane.class);

		// Verify that the actual Question PDF can be inspected.
		Button questionView = robot.lookup("#exam-assets-question-view-" + booklets.getFirst().getId()).queryButton();
		fireExamAssetsAction(questionView, "View Question PDF");
		robot.interact(() -> {
			assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace.getDisplayedDocument());
			assertEquals(1, pdfWorkspace.getCurrentPageNumber());
		});

		// Verify that the associated Answer PDF can also be inspected.
		Button answerView = robot.lookup("#exam-assets-answer-view-" + answerFile.getId()).queryButton();
		assertFalse(answerView.isDisabled());
		fireExamAssetsAction(answerView, "View Answer PDF");
		robot.interact(() -> {
			assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace.getDisplayedDocument());
			assertEquals(1, pdfWorkspace.getCurrentPageNumber());
		});

		// Inspection must not acquire a structural transaction.
		Button returnButton = robot.lookup("#exam-assets-return-dashboard").queryButton();
		assertFalse(returnButton.isDisabled());
		fireExamAssetsAction(returnButton, "Return to Dashboard");
		awaitAssignedDashboard(robot);
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
		Path afterPath = beforePath.resolveSibling("after.db");
		database.createConsistentSnapshot(afterPath);
		var after = new ContributionSnapshotReader(afterPath).read(assignmentId);

		// Inspecting either PDF must not modify authoritative records
		// or generate contribution candidates.
		assertEquals(before.currentRecords(), after.currentRecords(),
				"Inspection must not alter the authoritative Exam graph");
		assertTrue(after.delta().candidates().isEmpty());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	@SuppressWarnings("unchecked")
	void curriculumRemainsReadOnlyAfterAssignmentRemoval(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		Menu curriculum = menuBar.getMenus().stream().filter(menu -> "_Curriculum".equals(menu.getText())).findFirst()
				.orElseThrow();
		robot.interact(() -> assertTrue(curriculum.isDisable()));
		Platform.runLater(() -> invoke("showCurriculumAuthoring",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, contributor));
		acknowledgeDialog(robot, "Curriculum Authoring", "This Curriculum is read-only.");
		var originalSession = sessions().active().orElseThrow();
		var originalBaseline = sessions().readBaseline(assignmentId);
		removeSelectedAssignment(robot);
		assertTrue(sessions().active().isEmpty());
		assertEquals(originalSession, sessions().find(assignmentId).orElseThrow());
		assertTrue(Arrays.equals(originalBaseline.records(), sessions().readBaseline(assignmentId).records()));
		assertTrue(Arrays.equals(originalBaseline.assets(), sessions().readBaseline(assignmentId).assets()));
		SqliteDatabase database = new SqliteDatabase(contributor.databasePath());
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.REMOVED,
				new SqliteContributorAssignmentVisibilityRepository(database).find(assignmentId).orElseThrow());
		robot.interact(() -> {
			var root = primaryStage.getScene().getRoot();
			ComboBox<?> assignments = field("assignmentSelector", ComboBox.class);
			assertTrue(assignments.getItems().isEmpty());
			assertNull(assignments.getValue());
			CorpusDashboardPane dashboard = field("corpusDashboardPane", CorpusDashboardPane.class);
			assertNotNull(dashboard);
			TableView<?> exams = dashboardField(dashboard, "examTable", TableView.class);
			TableView<?> booklets = dashboardField(dashboard, "bookletTable", TableView.class);
			TableView<?> questionWork = dashboardField(dashboard, "questionTable", TableView.class);
			assertTrue(exams.getItems().isEmpty());
			assertTrue(booklets.getItems().isEmpty());
			assertTrue(questionWork.getItems().isEmpty());
			assertNull(exams.getSelectionModel().getSelectedItem());
			assertNull(booklets.getSelectionModel().getSelectedItem());
			assertTrue(dashboardField(dashboard, "addExamButton", Button.class).isDisabled());
			assertTrue(dashboardField(dashboard, "addCurriculumButton", Button.class).isDisabled());
			assertTrue(field("assignmentRemoveButton", Button.class).isDisabled());
			assertTrue(curriculum.isDisable());
		});

		// Retained contributor data remains externally authoritative.
		Platform.runLater(() -> invoke("showCurriculumAuthoring",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, contributor));
		acknowledgeDialog(robot, "Curriculum Authoring", "This Curriculum is read-only.");

		// Removal changes visibility, not the persisted Exam corpus.
		Subject subject = new Subject(originalSession.subjectLocalId(), "Biology");
		assertTrue(new SqliteExamWriter(database).findExamsForSubject(subject).stream()
				.anyMatch(exam -> exam.getId() == originalSession.examLocalId()));
		assertTrue(Files.isRegularFile(assignmentArchive));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void failedCaptureRefreshReleasesInstallationOwnership(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		robot.interact(() -> setField("workingSubjectQuestionSnapshotLoader", (Supplier<List<Question>>) () -> {
			throw new IllegalStateException("Controlled capture refresh failure");
		}));
		startInstallation(robot, realInstaller());
		acknowledgeDialog(robot, "Contributor Assignment Installed", "The assignment was installed, but");
		assertControlsReleased(robot);
		robot.interact(() -> assertTrue(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exams") == null));
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
	}

	@Test
	void failedDashboardRefreshReleasesInstallationOwnership(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		QuestionRepository repository = field("questionRepository", QuestionRepository.class);
		robot.interact(
				() -> setField("questionRepository", Proxy.newProxyInstance(QuestionRepository.class.getClassLoader(),
						new Class<?>[] { QuestionRepository.class }, (_, method, arguments) -> {
							if (method.getName().equals("findAll")) {
								throw new IllegalStateException("Controlled Dashboard refresh failure");
							}
							return method.invoke(repository, arguments);
						})));
		startInstallation(robot, realInstaller());
		acknowledgeDialog(robot, "Contributor Assignment Installed", "The assignment was installed, but");
		assertControlsReleased(robot);
		robot.interact(() -> assertTrue(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exams") == null));
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
	}

	@Test
	void failedHiddenInstallationReleasesApplicationAndAllowsRetry(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		String diagnostic = "Controlled installation failure. ".repeat(30)
				+ "UNIQUE constraint diagnostic at end of message.";
		Task<WorkAssignmentInstallationCoordinator.Result> installer = new ApplicationBackgroundTask<>(() -> {
			awaitBarrier(entered, release);
			throw new IllegalStateException(diagnostic);
		});
		try {
			startInstallation(robot, installer);
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			hideInstallationProgress(robot);
			robot.interact(() -> assertTrue(busyOverlay().isBusy()));
			release.countDown();
			DialogPane pane = awaitDialog(robot, "Import Contributor Assignment");
			robot.interact(() -> {
				assertEquals("The assignment could not be installed.", pane.getHeaderText());
				assertTrue(((Stage) pane.getScene().getWindow()).isResizable());

				// The complete message must be available for
				// selection and copying without truncation.
				assertTrue(pane.getContent() instanceof TextArea);
				TextArea message = (TextArea) pane.getContent();
				assertFalse(message.isEditable());
				assertTrue(message.isWrapText());
				assertEquals(diagnostic, message.getText());
				message.selectAll();
				assertEquals(diagnostic, message.getSelectedText());

				// Technical details must preserve the original
				// exception and its stack trace.
				assertTrue(pane.getExpandableContent() instanceof TextArea);
				TextArea details = (TextArea) pane.getExpandableContent();
				assertFalse(details.isEditable());
				assertTrue(details.getText().contains(diagnostic));
				assertTrue(details.getText().contains("IllegalStateException"));
				((ButtonBase) pane.lookupButton(ButtonType.OK)).fire();
			});
			assertControlsReleased(robot);
			startInstallation(robot, realInstaller());
			acknowledgeDialog(robot, "Contributor Assignment Installed", "The previously installed assignment");
			assertControlsReleased(robot);
		} finally {
			release.countDown();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void hiddenInstallationBlocksCaptureAndContextUntilDashboardPublication(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		Subject originalSubject = field("workingSubject", Subject.class);
		Subject otherSubject = new Subject(999, "Physics");
		ComboBox<Subject> subjects = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		robot.interact(() -> {
			subjects.getItems().add(otherSubject);
			TableView<?> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
			booklets.getSelectionModel().selectFirst();
		});
		assertFalse(assignments.isDisabled());
		assertEquals(assignmentId, assignments.getValue().assignmentId());
		QuestionRepository repository = field("questionRepository", QuestionRepository.class);
		Question question = repository.findAll().getFirst();
		Question answeredQuestion = repository.findAll().getFirst();
		answeredQuestion.setAnswer(new Answer(1, "A", List.of()));
		CountDownLatch installerEntered = new CountDownLatch(1);
		CountDownLatch releaseInstaller = new CountDownLatch(1);
		CountDownLatch captureEntered = new CountDownLatch(1);
		CountDownLatch releaseCapture = new CountDownLatch(1);
		CountDownLatch dashboardEntered = new CountDownLatch(1);
		CountDownLatch releaseDashboard = new CountDownLatch(1);
		Supplier<List<Question>> captureLoader = field("workingSubjectQuestionSnapshotLoader", Supplier.class);
		robot.interact(() -> {
			setField("workingSubjectQuestionSnapshotLoader", (Supplier<List<Question>>) () -> {
				awaitBarrier(captureEntered, releaseCapture);
				return captureLoader.get();
			});
			setField("questionRepository", Proxy.newProxyInstance(QuestionRepository.class.getClassLoader(),
					new Class<?>[] { QuestionRepository.class }, (_, method, arguments) -> {
						if (method.getName().equals("findAll")) {
							awaitBarrier(dashboardEntered, releaseDashboard);
						}
						return method.invoke(repository, arguments);
					}));
		});
		Task<WorkAssignmentInstallationCoordinator.Result> installer = new ApplicationBackgroundTask<>(() -> {
			awaitBarrier(installerEntered, releaseInstaller);
			return new WorkAssignmentInstallationCoordinator(contributor).install(assignmentArchive);
		});
		try {
			startInstallation(robot, installer);
			assertTrue(installerEntered.await(10, TimeUnit.SECONDS));
			hideInstallationProgress(robot);
			robot.interact(() -> {
				assertTrue(busyOverlay().isBusy());
				assertTrue(subjects.isDisabled());
				assertTrue(assignments.isDisabled());
				Button captureQuestion = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
				Button captureAnswer = robot.lookup("#corpus-dashboard-capture-answers").queryButton();
				assertTrue(captureQuestion.isDisabled());
				assertTrue(captureAnswer.isDisabled());

				// Disabled actions must remain inert even when fired directly.
				captureQuestion.fire();
				captureAnswer.fire();

				// The assignment selector replaces the removed Switch button.
				// Programmatic selection and direct entry-point invocation must
				// not bypass installation ownership.
				subjects.getSelectionModel().select(otherSubject);
				invoke("changeContributorAssignment", new Class<?>[] { UUID.class }, assignmentId);
				invoke("showCorpusDashboardQuestionCorrection", new Class<?>[] { Question.class }, question);
				invoke("startDashboardAnswerCapture", new Class<?>[] { Question.class, Runnable.class }, question,
						(Runnable) () -> {
						});
				assertFalse(field("answerCapturePane", AnswerCapturePane.class).editAnswer(answeredQuestion, () -> {
				}));
				invoke("hideWorkspaceBusy", new Class<?>[0]);
				assertTrue(busyOverlay().isBusy());
				assertEquals(originalSubject, field("workingSubject", Subject.class));
				assertFalse(field("captureSelectionState", CaptureSelectionState.class).hasPendingSelection());
				assertFalse(field("questionCapturePane", QuestionCapturePane.class).hasAcceptedRegions());
				assertFalse(field("answerCapturePane", AnswerCapturePane.class).isEditingAnswer());
				assertFalse(field("answerCapturePane", AnswerCapturePane.class).hasAcceptedRegions());
			});

			// Reinstalling an already active assignment does not erase it.
			assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
			Task<WorkAssignmentInstallationCoordinator.Result> duplicate = new ApplicationBackgroundTask<>(() -> {
				throw new AssertionError("Duplicate installation must not start");
			});
			startInstallation(robot, duplicate);
			robot.interact(() -> assertEquals(Worker.State.READY, duplicate.getState()));
			releaseInstaller.countDown();
			assertTrue(captureEntered.await(10, TimeUnit.SECONDS));
			robot.interact(() -> {
				assertTrue(busyOverlay().isBusy());
				assertTrue(assignments.isDisabled());
			});
			releaseCapture.countDown();
			assertTrue(dashboardEntered.await(10, TimeUnit.SECONDS));
			robot.interact(() -> {
				assertTrue(busyOverlay().isBusy());
				assertTrue(assignments.isDisabled());
			});
			releaseDashboard.countDown();
			acknowledgeDialog(robot, "Contributor Assignment Installed", "The previously installed assignment");
			awaitAssignedDashboard(robot);
			assertControlsReleased(robot);
			assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
			assertEquals(1, sessions().findAll().size());
		} finally {
			releaseInstaller.countDown();
			releaseCapture.countDown();
			releaseDashboard.countDown();
		}
	}

	@Test
	void installationRechecksPendingCaptureAtTaskBoundary(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		Task<WorkAssignmentInstallationCoordinator.Result> installer = realInstaller();
		robot.interact(() -> {
			field("captureSelectionState", CaptureSelectionState.class).claim(CaptureSelectionOwner.QUESTION);

			// The confirmation/file picker runs a nested event loop. Recheck pending
			// work at the actual task boundary, not only before opening those dialogs.
			Platform.runLater(() -> invoke("startContributorAssignmentInstallation",
					new Class<?>[] { Stage.class, Task.class }, primaryStage, installer));
		});
		acknowledgeDialog(robot, "Working Subject", "Capture work is in progress");
		robot.interact(() -> {
			assertEquals(Worker.State.READY, installer.getState());
			assertTrue(field("captureSelectionState", CaptureSelectionState.class)
					.isOwnedBy(CaptureSelectionOwner.QUESTION));
			field("captureSelectionState", CaptureSelectionState.class).clearAll();
		});
		assertControlsReleased(robot);
	}

	@Test
	@SuppressWarnings("unchecked")
	void installedAssignmentRestoresAutomaticallyAcrossRestart(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		Label subject = robot.lookup("#working-subject-value").queryAs(Label.class);
		assertEquals("Biology", subject.getText());
		assertTrue(subject.isVisible());
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		assertEquals(1, exams.getItems().size());
		SqliteDatabase contributorDatabase = new SqliteDatabase(contributor.databasePath());
		var session = new SqliteContributorAssignmentSessionRepository(contributorDatabase).active().orElseThrow();
		Exam installedExam = exams.getItems().getFirst().exam();
		assertEquals(assignmentId, session.assignmentId());
		assertEquals(session.examLocalId(), installedExam.getId());
		assertEquals("External Assessment", installedExam.getName());

		// Portable UUID identity must survive installation and restart.
		var identity = new SqlitePortableIdentityRepository(contributorDatabase).requireByLocalId(EntityType.EXAM,
				installedExam.getId());
		assertEquals(session.examPortableId(), identity.portableId());
		Button inspect = robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton();
		assertFalse(inspect.isDisabled());
		assertEquals("Inspect Exam / Assets", inspect.getText());
		assertTrue(robot.lookup("#corpus-dashboard-create-contributor-assignment").queryButton().isDisabled());
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		assertEquals(1, assignments.getItems().size());
		assertEquals(assignmentId, assignments.getValue().assignmentId());
		robot.interact(() -> {
			assertNull(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exit-assignment"));
			assertNull(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-switch-assignment"));
		});

		// Restart against the same contributor installation.
		robot.interact(() -> {
			try {
				application.stop();
				application = new QuestionBankApplication();
				startDesktopApplication(application);
			} catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		});
		awaitAssignedDashboard(robot);
		Label restoredSubject = robot.lookup("#working-subject-value").queryAs(Label.class);
		assertEquals("Biology", restoredSubject.getText());
		assertTrue(restoredSubject.isVisible());
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> restoredAssignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		assertEquals(1, restoredAssignments.getItems().size());
		assertEquals(assignmentId, restoredAssignments.getValue().assignmentId());
		assertEquals(assignmentId,
				new SqliteContributorAssignmentSessionRepository(new SqliteDatabase(contributor.databasePath()))
						.active().orElseThrow().assignmentId());
		Button restoredInspect = robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton();
		assertFalse(restoredInspect.isDisabled());
		assertEquals("Inspect Exam / Assets", restoredInspect.getText());
	}

	@Test
	@SuppressWarnings("unchecked")
	void obsoleteAssignmentRefreshCannotReplaceNewerSelection(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		var first = sessions().active().orElseThrow();
		var second = installSecondAssignment();
		robot.interact(() -> invoke("refreshContributorAssignmentToolbar", new Class<?>[0]));
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = field("assignmentSelector",
				ComboBox.class);
		CorpusDashboardPane originalDashboard = field("corpusDashboardPane", CorpusDashboardPane.class);
		Supplier<List<Question>> originalLoader = field("workingSubjectQuestionSnapshotLoader", Supplier.class);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean blockFirst = new AtomicBoolean(true);
		AtomicReference<Thread> obsoleteWorker = new AtomicReference<>();
		robot.interact(() -> setField("workingSubjectQuestionSnapshotLoader", (Supplier<List<Question>>) () -> {
			if (blockFirst.compareAndSet(true, false)) {
				obsoleteWorker.set(Thread.currentThread());
				awaitBarrier(entered, release);
			}
			return originalLoader.get();
		}));
		try {
			robot.interact(() -> assignments.getSelectionModel().select(second));
			assertTrue(entered.await(10, TimeUnit.SECONDS));

			// Supersede the blocked assignment refresh without waiting
			// for its background retrieval to finish.
			robot.interact(() -> assignments.getSelectionModel().select(first));
			awaitDashboardExam(robot, first.examLocalId(), 2);
			assertEquals(first.assignmentId(), sessions().active().orElseThrow().assignmentId());
			release.countDown();
			Thread worker = obsoleteWorker.get();
			assertNotNull(worker);
			worker.join(TimeUnit.SECONDS.toMillis(10));
			assertFalse(worker.isAlive());
			WaitForAsyncUtils.waitForFxEvents();
			robot.interact(() -> {
				assertTrue(originalDashboard == field("corpusDashboardPane", CorpusDashboardPane.class));
				assertEquals(first.assignmentId(), assignments.getValue().assignmentId());
				TableView<ExamCorpusStatus> exams = dashboardField(originalDashboard, "examTable", TableView.class);
				assertEquals(1, exams.getItems().size());
				assertEquals(first.examLocalId(), exams.getItems().getFirst().exam().getId());
				assertFalse(originalDashboard.isDisabled());
			});
		} finally {
			release.countDown();
			robot.interact(() -> setField("workingSubjectQuestionSnapshotLoader", originalLoader));
		}
	}

	@Test
	void reimportClosesProgressDialogAndPreservesAssignment(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		Method importMethod = QuestionBankApplication.class.getDeclaredMethod("startContributorAssignmentInstallation",
				Stage.class, ApplicationConfig.class, Path.class);
		importMethod.setAccessible(true);

		// Exercise the real import progress and completion handlers.
		// Replaying the existing package must not duplicate work.
		robot.interact(() -> {
			try {
				importMethod.invoke(application, primaryStage, contributor, assignmentArchive);
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException(exception);
			}
		});
		AtomicReference<DialogPane> successDialog = new AtomicReference<>();
		WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> {
			robot.interact(() -> {
				successDialog.set(null);
				for (Window window : Window.getWindows()) {
					if (!(window instanceof Stage stage) || !stage.isShowing()
							|| !"Contributor Assignment Installed".equals(stage.getTitle())
							|| stage.getScene() == null) {
						continue;
					}
					if (stage.getScene().getRoot() instanceof DialogPane pane) {
						successDialog.set(pane);
						break;
					}
				}
			});
			return successDialog.get() != null;
		});
		robot.interact(() -> {

			// The completion notification must not leave the
			// previous modal progress window blocking the app.
			boolean progressStillShowing = Window.getWindows().stream().filter(Window::isShowing)
					.filter(window -> window instanceof Stage).map(window -> (Stage) window)
					.anyMatch(stage -> "Import Contributor Assignment".equals(stage.getTitle()));
			assertFalse(progressStillShowing);
			Node confirmation = successDialog.get().lookupButton(ButtonType.OK);
			assertTrue(confirmation instanceof ButtonBase);
			((ButtonBase) confirmation).fire();
		});
		var sessions = new SqliteContributorAssignmentSessionRepository(new SqliteDatabase(contributor.databasePath()));
		assertEquals(assignmentId, sessions.active().orElseThrow().assignmentId());
		assertEquals(1, sessions.findAll().size());
	}

	@Test
	@SuppressWarnings("unchecked")
	void reimportRestoresAssignedSubjectAfterAttemptedSwitch(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		Subject biology = field("workingSubject", Subject.class);
		assertEquals("Biology", biology.getName());

		// An active contributor assignment must prevent structural
		// Subject creation. This SQLite rejection is expected.
		SqliteDatabase database = new SqliteDatabase(contributor.databasePath());
		assertThrows(SQLException.class, () -> new SqliteCurriculumWriter(database).insertSubject("Physics"));

		// Test attempted context switching without inserting an
		// unauthorised Subject into the database.
		Subject unavailablePhysics = new Subject(999, "Physics");
		ComboBox<Subject> subjects = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		robot.interact(() -> {
			subjects.getItems().add(unavailablePhysics);
			subjects.getSelectionModel().select(unavailablePhysics);
		});

		// The application's Subject guard must restore Biology.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			AtomicBoolean restored = new AtomicBoolean();
			robot.interact(() -> restored.set(
					biology.equals(field("workingSubject", Subject.class)) && biology.equals(subjects.getValue())));
			return restored.get();
		});
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());

		// Reimporting the existing package must remain idempotent.
		startInstallation(robot, realInstaller());
		acknowledgeDialog(robot, "Contributor Assignment Installed", "The previously installed assignment");
		awaitAssignedDashboard(robot);
		assertControlsReleased(robot);
		robot.interact(() -> assertEquals("Biology", field("workingSubject", Subject.class).getName()));
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
		assertEquals(1, sessions().findAll().size());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	@SuppressWarnings("unchecked")
	void removingAssignmentActivatesRemainingExamWithoutReplacingDashboard(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		var first = sessions().active().orElseThrow();
		var second = installSecondAssignment();
		robot.interact(() -> invoke("refreshContributorAssignmentToolbar", new Class<?>[0]));
		CorpusDashboardPane originalDashboard = field("corpusDashboardPane", CorpusDashboardPane.class);
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = field("assignmentSelector",
				ComboBox.class);

		// Exercise an ordinary selector transition first.
		robot.interact(() -> assignments.getSelectionModel().select(second));
		awaitDashboardExam(robot, second.examLocalId(), 1);
		robot.interact(() -> {
			assertTrue(originalDashboard == field("corpusDashboardPane", CorpusDashboardPane.class));
			TableView<BookletCorpusStatus> booklets = dashboardField(originalDashboard, "bookletTable",
					TableView.class);
			booklets.getSelectionModel().selectFirst();
			assertEquals(second.examLocalId(),
					booklets.getSelectionModel().getSelectedItem().booklet().getExam().getId());
		});

		// Return to the first assignment before removing it.
		robot.interact(() -> assignments.getSelectionModel().select(first));
		awaitDashboardExam(robot, first.examLocalId(), 2);
		removeSelectedAssignment(robot);
		awaitDashboardExam(robot, second.examLocalId(), 1);
		assertEquals(second.assignmentId(), sessions().active().orElseThrow().assignmentId());
		assertTrue(sessions().find(first.assignmentId()).isPresent());
		robot.interact(() -> {
			assertTrue(originalDashboard == field("corpusDashboardPane", CorpusDashboardPane.class));
			assertEquals(1, assignments.getItems().size());
			assertEquals(second.assignmentId(), assignments.getValue().assignmentId());
			TableView<BookletCorpusStatus> booklets = dashboardField(originalDashboard, "bookletTable",
					TableView.class);
			assertNull(booklets.getSelectionModel().getSelectedItem());
			assertTrue(booklets.getItems().stream()
					.allMatch(status -> status.booklet().getExam().getId() == second.examLocalId()));
			TableView<QuestionCorpusWorkItem> questionWork = dashboardField(originalDashboard, "questionTable",
					TableView.class);
			assertFalse(questionWork.getItems().isEmpty());
			assertTrue(questionWork.getItems().stream()
					.allMatch(item -> item.question().getExam().getId() == second.examLocalId()));
			assertNull(questionWork.getSelectionModel().getSelectedItem());
		});
		SqliteDatabase database = new SqliteDatabase(contributor.databasePath());
		var visibility = new SqliteContributorAssignmentVisibilityRepository(database);
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.REMOVED,
				visibility.find(first.assignmentId()).orElseThrow());
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.ACTIVE,
				visibility.find(second.assignmentId()).orElseThrow());
		database.verifyIntegrity();
	}

	@Test
	@SuppressWarnings("unchecked")
	void savedWorkDisablesRemovalAndSubmissionActivatesRemainingAssignment(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		var first = sessions().active().orElseThrow();
		var second = installSecondAssignment();
		robot.interact(() -> invoke("refreshContributorAssignmentToolbar", new Class<?>[0]));
		SqliteDatabase database = new SqliteDatabase(contributor.databasePath());
		ContributorAssignmentRemovalService removal = new ContributorAssignmentRemovalService(database);
		assertTrue(removal.canRemoveUnchanged(first.assignmentId()));
		Subject subject = new Subject(first.subjectLocalId(), "Biology");
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		var syllabus = curriculum.findVersionsForSubject(subject).stream().filter(SyllabusVersion::isCurrent)
				.findFirst().orElseThrow();
		var classification = curriculum.findReviewNodes(syllabus).stream()
				.filter(node -> node.getLevel() == CurriculumLevel.DESCRIPTOR).findFirst().orElseThrow();
		SqliteExamWriter exams = new SqliteExamWriter(database);
		Exam exam = exams.findExamsForSubject(subject).stream()
				.filter(candidate -> candidate.getId() == first.examLocalId()).findFirst().orElseThrow();
		ExamBooklet booklet = exams.findExamBooklets(exam).stream()
				.filter(candidate -> "Paper 1".equals(candidate.getName())).findFirst().orElseThrow();

		// Save real contributor work through the production capture service.
		Question captured = new SqliteQuestionCaptureService(database)
				.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, booklet,
						null, "Q2", 1, List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.70, 0.12)), classification,
						QuestionResponseType.MULTIPLE_CHOICE, null, null, false));
		assertFalse(removal.canRemoveUnchanged(first.assignmentId()));
		assertThrows(IllegalStateException.class, () -> removal.removeUnchanged(first.assignmentId()));
		robot.interact(() -> invoke("refreshCorpusDashboardHome", new Class<?>[] { long.class }, -1L));
		WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() -> {
			CorpusDashboardPane dashboard = field("corpusDashboardPane", CorpusDashboardPane.class);
			TableView<QuestionCorpusWorkItem> work = dashboardField(dashboard, "questionTable", TableView.class);
			return work.getItems().stream().anyMatch(item -> item.question().getId() == captured.getId());
		}).get());
		robot.interact(() -> assertTrue(field("assignmentRemoveButton", Button.class).isDisabled()));
		Path contribution = assignmentArchive.getParent().resolve("submitted-first-assignment.examwork");
		Platform.runLater(() -> invoke("startAssignmentContributionExport",
				new Class<?>[] { Stage.class, ApplicationConfig.class, UUID.class, Path.class }, primaryStage,
				contributor, first.assignmentId(), contribution));
		acknowledgeDialog(robot, "Assignment Contribution Submitted",
				"The contribution package was created and validated.");
		assertTrue(Files.isRegularFile(contribution));
		awaitDashboardExam(robot, second.examLocalId(), 1);
		assertEquals(second.assignmentId(), sessions().active().orElseThrow().assignmentId());
		var visibility = new SqliteContributorAssignmentVisibilityRepository(database);
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.SUBMITTED,
				visibility.find(first.assignmentId()).orElseThrow());
		assertTrue(sessions().find(first.assignmentId()).isPresent());
		assertFalse(removal.canRemoveUnchanged(first.assignmentId()));
		assertTrue(new SqliteContributionReturnLedger(database).findLatest(first.assignmentId()).isPresent());
		robot.interact(() -> {
			ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = field("assignmentSelector",
					ComboBox.class);
			assertEquals(1, assignments.getItems().size());
			assertEquals(second.assignmentId(), assignments.getValue().assignmentId());
		});

		// The submitted Question remains durable, but is no longer part
		// of the active contributor Dashboard.
		assertTrue(new SqliteQuestionRepository(database).findAll().stream()
				.anyMatch(question -> question.getId() == captured.getId()));
		database.verifyIntegrity();
	}

	@Start
	void start(Stage stage) throws Exception {
		primaryStage = stage;
		Path root = Files.createTempDirectory("contributor-dashboard-workflow-");
		ApplicationConfig coordinator = ApplicationConfig.fromDataRoot(root.resolve("coordinator"));
		contributor = ApplicationConfig.fromDataRoot(root.resolve("contributor"));
		Files.createDirectories(coordinator.dataRoot());
		Files.createDirectories(contributor.dataRoot());
		SqliteDatabase master = new SqliteDatabase(coordinator.databasePath());
		SqliteDatabase worker = new SqliteDatabase(contributor.databasePath());
		master.initialiseSchema();
		worker.initialiseSchema();
		prepareMaster(master, coordinator.dataRoot());
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		assignmentId = UUID.randomUUID();
		assignmentArchive = root.resolve("biology-assignment.examwork");
		var request = new WorkAssignmentExporter.Request(exam, assignmentId, UUID.randomUUID(), "Test Contributor",
				false, assignmentArchive);
		new WorkAssignmentIssuanceService(master, new ManagedDataLayout(coordinator.dataRoot())).issue(request);
		new WorkAssignmentInstallationCoordinator(contributor).install(assignmentArchive);
		application = new QuestionBankApplication();
		startDesktopApplication(application);
	}

	@AfterEach
	void stopApplication() throws Exception {
		if (application != null) {
			WaitForAsyncUtils.asyncFx(() -> {
				application.stop();
				return null;
			}).get();
		}
	}

	@Test
	void synchronousActivationFailureReleasesInstallationOwnership(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		startInstallation(robot, new ApplicationBackgroundTask<>(() -> new WorkAssignmentInstallationCoordinator.Result(
				UUID.randomUUID(), UUID.randomUUID(), false, false)));
		acknowledgeDialog(robot, "Contributor Assignment Installed", "The assignment was installed, but");
		assertControlsReleased(robot);
		robot.interact(() -> assertTrue(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exams") == null));
	}

	@Test
	void unchangedContributionIsRejectedBeforeDestinationSelection(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		SqliteDatabase database = new SqliteDatabase(contributor.databasePath());
		SqliteContributionReturnLedger ledger = new SqliteContributionReturnLedger(database);
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		Menu exportMenu = menuBar.getMenus().stream().filter(menu -> "E_xport".equals(menu.getText())).findFirst()
				.orElseThrow();
		MenuItem contribution = exportMenu.getItems().stream()
				.filter(item -> "export-assignment-contribution".equals(item.getId())).findFirst().orElseThrow();
		Button remove = robot.lookup("#corpus-dashboard-remove-assignment").queryButton();
		WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> WaitForAsyncUtils
				.asyncFx(() -> !remove.isDisabled() && !contribution.isDisable() && !busyOverlay().isBusy()).get());
		assertTrue(ledger.findLatest(assignmentId).isEmpty());

		// Invoke the real Export menu action. An unchanged
		// assignment must produce an explanatory application
		// alert instead of entering the native destination chooser.
		Platform.runLater(contribution::fire);
		DialogPane rejection = awaitDialog(robot, "Export Assignment Contribution");
		robot.interact(() -> {
			assertEquals("The work package is not ready for export.", rejection.getHeaderText());
			TextArea message = (TextArea) rejection.getContent();
			assertTrue(message.getText().contains("No contributor changes to export"));
			assertTrue(message.getText().contains("Capture and save new work"));
			assertFalse(busyOverlay().isBusy());
			((ButtonBase) rejection.lookupButton(ButtonType.OK)).fire();
		});
		WaitForAsyncUtils.waitForFxEvents();

		// Refusal must not submit, remove or deselect the assignment.
		assertEquals(assignmentId, sessions().active().orElseThrow().assignmentId());
		assertTrue(ledger.findLatest(assignmentId).isEmpty());
		var visibility = new SqliteContributorAssignmentVisibilityRepository(database);
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.ACTIVE,
				visibility.find(assignmentId).orElseThrow());
		robot.interact(() -> {
			assertFalse(remove.isDisabled());
			assertFalse(contribution.isDisable());
			assertFalse(busyOverlay().isBusy());
			ComboBox<?> assignments = robot.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
			assertEquals(1, assignments.getItems().size());
			assertNotNull(assignments.getValue());
		});
		database.verifyIntegrity();
	}

	private void acknowledgeDialog(FxRobot robot, String title, String expectedHeader) throws Exception {
		DialogPane pane = awaitDialog(robot, title);
		robot.interact(() -> {
			assertTrue(pane.getHeaderText().startsWith(expectedHeader), pane.getHeaderText());
			((ButtonBase) pane.lookupButton(ButtonType.OK)).fire();
		});
	}

	private void assertControlsReleased(FxRobot robot) {
		robot.interact(() -> {
			assertFalse(busyOverlay().isBusy());
			assertFalse(busyOverlay().getChildren().getFirst().isDisabled());
			assertEquals(0L, field("contributorAssignmentInstallationRequest", Long.class));
		});
	}

	private void awaitAssignedDashboard(FxRobot robot) throws Exception {
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			AtomicBoolean ready = new AtomicBoolean(false);
			robot.interact(() -> {
				var node = primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exams");
				if (node instanceof TableView<?> table) {
					ready.set(table.getItems().size() == 1);
				}
			});
			return ready.get();
		});
	}

	@SuppressWarnings("unchecked")
	private void awaitDashboardExam(FxRobot robot, long examId, int expectedBookletCount) throws Exception {
		WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() -> {
			CorpusDashboardPane dashboard = field("corpusDashboardPane", CorpusDashboardPane.class);
			if (dashboard == null || dashboard.isDisabled()) {
				return false;
			}
			TableView<ExamCorpusStatus> exams = dashboardField(dashboard, "examTable", TableView.class);
			TableView<BookletCorpusStatus> booklets = dashboardField(dashboard, "bookletTable", TableView.class);
			return exams.getItems().size() == 1 && exams.getItems().getFirst().exam().getId() == examId
					&& exams.getSelectionModel().getSelectedItem() != null
					&& exams.getSelectionModel().getSelectedItem().exam().getId() == examId
					&& booklets.getItems().size() == expectedBookletCount
					&& booklets.getItems().stream().allMatch(status -> status.booklet().getExam().getId() == examId);
		}).get());
	}

	private DialogPane awaitDialog(FxRobot robot, String title) throws Exception {
		AtomicReference<DialogPane> result = new AtomicReference<>();
		WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> {
			robot.interact(() -> {
				for (Window window : Window.getWindows()) {
					if (window instanceof Stage stage && stage.isShowing() && title.equals(stage.getTitle())
							&& stage.getScene().getRoot() instanceof DialogPane pane) {
						result.set(pane);
					}
				}
			});
			return result.get() != null;
		});
		return result.get();
	}

	private ApplicationBusyOverlay busyOverlay() {
		return field("applicationBusyOverlay", ApplicationBusyOverlay.class);
	}

	private <T> T dashboardField(CorpusDashboardPane dashboard, String name, Class<T> type) {
		try {
			Field field = CorpusDashboardPane.class.getDeclaredField(name);
			field.setAccessible(true);
			return type.cast(field.get(dashboard));
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private <T> T field(String name, Class<T> type) {
		try {
			Field field = QuestionBankApplication.class.getDeclaredField(name);
			field.setAccessible(true);
			return type.cast(field.get(application));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void fireExamAssetsAction(Button button, String actionDescription) throws Exception {
		CompletableFuture<Void> completion = new CompletableFuture<>();
		AtomicReference<String> unexpectedDialog = new AtomicReference<>();

		// Keep the test thread available while an application action
		// potentially enters a modal JavaFX event loop.
		Platform.runLater(() -> {
			try {
				assertFalse(button.isDisabled(), actionDescription + " is disabled");
				button.fire();
				completion.complete(null);
			} catch (Throwable failure) {
				completion.completeExceptionally(failure);
			}
		});
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!completion.isDone() && System.nanoTime() < deadline) {
			try {
				completion.get(100, TimeUnit.MILLISECONDS);
			} catch (TimeoutException pending) {

				// Detect and dismiss unexpected application alerts
				// rather than leaving their showAndWait loop blocked.
				WaitForAsyncUtils.asyncFx(() -> {
					for (Window window : List.copyOf(Window.getWindows())) {
						if (!(window instanceof Stage stage) || !stage.isShowing() || stage.getScene() == null
								|| !(stage.getScene().getRoot() instanceof DialogPane pane)) {
							continue;
						}
						String content = pane.getContent() instanceof TextArea area ? area.getText()
								: pane.getContentText();
						unexpectedDialog.compareAndSet(null,
								stage.getTitle() + ": " + pane.getHeaderText() + "\n" + content);
						Node dismiss = pane.lookupButton(ButtonType.CANCEL);
						if (dismiss == null) {
							dismiss = pane.lookupButton(ButtonType.CLOSE);
						}
						if (dismiss == null) {
							dismiss = pane.lookupButton(ButtonType.OK);
						}
						if (dismiss instanceof ButtonBase dismissButton) {
							dismissButton.fire();
						} else {

							// An unexpected custom modal must not
							// prevent the test harness from terminating.
							stage.hide();
						}
					}
					return null;
				}).get(3, TimeUnit.SECONDS);
			}
		}
		assertTrue(completion.isDone(), actionDescription + " did not finish within 15 seconds");
		completion.get(1, TimeUnit.SECONDS);
		assertNull(unexpectedDialog.get(),
				actionDescription + " opened an unexpected dialog:\n" + unexpectedDialog.get());
	}

	private void hideInstallationProgress(FxRobot robot) throws Exception {
		DialogPane pane = awaitDialog(robot, "Import Contributor Assignment");
		robot.interact(() -> {
			Window window = pane.getScene().getWindow();
			((ButtonBase) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
			assertFalse(window.isShowing());
		});
	}

	private SqliteContributorAssignmentSessionRepository.Session installSecondAssignment() throws Exception {
		Path root = assignmentArchive.getParent();
		ApplicationConfig coordinator = ApplicationConfig.fromDataRoot(root.resolve("coordinator"));
		SqliteDatabase master = new SqliteDatabase(coordinator.databasePath());
		ManagedDataLayout layout = new ManagedDataLayout(coordinator.dataRoot());
		Path pdfRoot = layout.examDirectory("Biology", "QCAA", 2026, "Second Assessment");
		Files.createDirectories(pdfRoot);
		Path questionPdf = pdfRoot.resolve("questions.pdf");
		Path answerPdf = pdfRoot.resolve("answers.pdf");
		Files.write(questionPdf, TestPdf.bytes("Second Question PDF"));
		Files.write(answerPdf, TestPdf.bytes("Second Answer PDF"));
		try (Connection connection = master.openConnection(); Statement statement = connection.createStatement()) {
			try (PreparedStatement insert = connection.prepareStatement("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (?, ?)
					""")) {
				insert.setLong(1, 53);
				insert.setString(2, layout.relativePath(questionPdf));
				insert.executeUpdate();
				insert.setLong(1, 54);
				insert.setString(2, layout.relativePath(answerPdf));
				insert.executeUpdate();
			}
			statement.execute("""
					INSERT INTO exams (
					    id,
					    subject_id,
					    provider_id,
					    exam_year,
					    exam_name,
					    expected_question_booklet_count,
					    expected_answer_file_count
					)
					VALUES (
					    32, 1, 21, 2026, 'Second Assessment', 1, 1
					)
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id,
					    exam_id,
					    source_document_id,
					    answer_file_name
					)
					VALUES (72, 32, 54, 'Second Marking Guide')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id,
					    exam_id,
					    source_document_id,
					    answer_file_id,
					    booklet_name
					)
					VALUES (63, 32, 53, 72, 'Second Paper')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id,
					    booklet_id,
					    classification_node_id,
					    question_code,
					    question_text,
					    marks,
					    shared_context_capture_required
					)
					VALUES (
					    82, 63, 103,
					    '1', 'Existing Second Assessment Question', 3, 0
					)
					""");
		}
		Exam secondExam = new Exam(32, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2026,
				"Second Assessment");
		UUID secondId = UUID.randomUUID();
		Path secondArchive = root.resolve("second-biology-assignment.examwork");
		var request = new WorkAssignmentExporter.Request(secondExam, secondId, UUID.randomUUID(), "Test Contributor",
				false, secondArchive);
		new WorkAssignmentIssuanceService(master, layout).issue(request);
		new WorkAssignmentInstallationCoordinator(contributor).install(secondArchive);

// The installer selects its newly installed assignment.
// Restore the existing application's selected assignment so
// tests can exercise the real selector and lifecycle actions.
		sessions().select(assignmentId);
		return sessions().find(secondId).orElseThrow();
	}

	private Object invoke(String name, Class<?>[] types, Object... arguments) {
		try {
			Method method = QuestionBankApplication.class.getDeclaredMethod(name, types);
			method.setAccessible(true);
			return method.invoke(application, arguments);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void prepareMaster(SqliteDatabase database, Path dataRoot) throws Exception {

		// Use the same canonical managed hierarchy as ordinary
		// Exam Assets intake.
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		Path pdfRoot = layout.examDirectory("Biology", "QCAA", 2025, "External Assessment");
		Files.createDirectories(pdfRoot);
		Files.write(pdfRoot.resolve("questions.pdf"), TestPdf.bytes("Question PDF fixture"));
		Files.write(pdfRoot.resolve("answers.pdf"), TestPdf.bytes("Answer PDF fixture"));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (11, 1, 'Biology 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES
					    (101, 11, NULL, 'B25U1',
					     'Biology Unit', 'UNIT', 0),
					    (102, 11, 101, 'B25T1',
					     'Biology Topic', 'TOPIC', 0),
					    (103, 11, 102, 'B25D1',
					     'Biology Descriptor', 'DESCRIPTOR', 0)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (21, 'QCAA')
					""");

			// Persist the exact paths generated by ManagedDataLayout
			// rather than maintaining separate hardcoded path strings.
			try (PreparedStatement insert = connection.prepareStatement("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (?, ?)
					""")) {
				insert.setLong(1, 51);
				insert.setString(2, layout.relativePath(pdfRoot.resolve("questions.pdf")));
				insert.executeUpdate();
				insert.setLong(1, 52);
				insert.setString(2, layout.relativePath(pdfRoot.resolve("answers.pdf")));
				insert.executeUpdate();
			}
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name,
					    expected_question_booklet_count,
					    expected_answer_file_count
					)
					VALUES (
					    31, 1, 21, 2025,
					    'External Assessment', 2, 1
					)
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id,
					    answer_file_name
					)
					VALUES (71, 31, 52, 'Marking Guide')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id,
					    answer_file_id, booklet_name
					)
					VALUES
					    (61, 31, 51, 71, 'Paper 1'),
					    (62, 31, 51, 71, 'Paper 2')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES (
					    81, 61, 103,
					    '1', 'Existing Biology Question', 2, 0
					)
					""");
		}
	}

	private Task<WorkAssignmentInstallationCoordinator.Result> realInstaller() {
		return new ApplicationBackgroundTask<>(
				() -> new WorkAssignmentInstallationCoordinator(contributor).install(assignmentArchive));
	}

	private void removeSelectedAssignment(FxRobot robot) throws Exception {
		Button remove = field("assignmentRemoveButton", Button.class);
		WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS,
				() -> WaitForAsyncUtils.asyncFx(() -> !remove.isDisabled()).get());

		// Schedule modal entry without blocking the test thread.
		Platform.runLater(remove::fire);
		DialogPane confirmation = awaitDialog(robot, "Remove Work Package");
		robot.interact(() -> {
			assertEquals("Remove this unchanged assignment?", confirmation.getHeaderText());
			ButtonType removeType = confirmation.getButtonTypes().stream()
					.filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
			((ButtonBase) confirmation.lookupButton(removeType)).fire();
		});
		acknowledgeDialog(robot, "Work Package Removed", "The unchanged assignment was removed");
	}

	private SqliteContributorAssignmentSessionRepository sessions() {
		return new SqliteContributorAssignmentSessionRepository(new SqliteDatabase(contributor.databasePath()));
	}

	private void setField(String name, Object value) {
		try {
			Field field = QuestionBankApplication.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(application, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void startDesktopApplication(QuestionBankApplication instance) throws Exception {
		Method startup = QuestionBankApplication.class.getDeclaredMethod("startApplication", Stage.class,
				ApplicationConfig.class);
		startup.setAccessible(true);
		startup.invoke(instance, primaryStage, contributor);
	}

	private void startInstallation(FxRobot robot, Task<WorkAssignmentInstallationCoordinator.Result> task) {
		robot.interact(() -> invoke("startContributorAssignmentInstallation",
				new Class<?>[] { Stage.class, Task.class }, primaryStage, task));
	}
}
