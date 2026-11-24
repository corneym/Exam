package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentExporter;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentInstallationCoordinator;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentIssuanceService;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.Button;
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

		// Local integer IDs are installation-specific; portable UUID identity
		// must survive assignment installation and application restart.
		var identity = new SqlitePortableIdentityRepository(contributorDatabase).requireByLocalId(EntityType.EXAM,
				installedExam.getId());
		assertEquals(session.examPortableId(), identity.portableId());
		assertTrue(robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton().isDisabled());
		assertTrue(robot.lookup("#corpus-dashboard-create-contributor-assignment").queryButton().isDisabled());
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		assertEquals(1, assignments.getItems().size());
		assertEquals(assignmentId, assignments.getValue().assignmentId());
		robot.interact(() -> {
			assertNull(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-exit-assignment"));
			assertNull(primaryStage.getScene().getRoot().lookup("#corpus-dashboard-switch-assignment"));
		});

		// A new application instance against the same data installation must
		// restore the active assignment automatically, with no Switch action.
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

	private <T> T field(String name, Class<T> type) {
		try {
			Field field = QuestionBankApplication.class.getDeclaredField(name);
			field.setAccessible(true);
			return type.cast(field.get(application));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void hideInstallationProgress(FxRobot robot) throws Exception {
		DialogPane pane = awaitDialog(robot, "Import Contributor Assignment");
		robot.interact(() -> {
			Window window = pane.getScene().getWindow();
			((ButtonBase) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
			assertFalse(window.isShowing());
		});
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
		Path pdfRoot = dataRoot.resolve("subjects/Biology/exams");
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
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES
					  (51, 'subjects/Biology/exams/questions.pdf'),
					  (52, 'subjects/Biology/exams/answers.pdf')
					""");
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
