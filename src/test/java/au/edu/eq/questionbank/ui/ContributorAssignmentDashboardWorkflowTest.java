package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentExporter;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentInstallationCoordinator;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentIssuanceService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
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

	@Test
	@SuppressWarnings("unchecked")
	void installedAssignmentRestoresAndCanExitReenterAndRestart(FxRobot robot) throws Exception {
		awaitAssignedDashboard(robot);
		Label subject = robot.lookup("#working-subject-value").queryAs(Label.class);
		assertEquals("Biology", subject.getText());
		assertTrue(subject.isVisible());
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		assertEquals(1, exams.getItems().size());

		// SQLite-local IDs are allocated independently by each
		// installation. The contributor's Exam need not retain
		// the coordinator's original integer ID.
		SqliteDatabase contributorDatabase = new SqliteDatabase(contributor.databasePath());
		var session = new SqliteContributorAssignmentSessionRepository(contributorDatabase).active().orElseThrow();
		Exam installedExam = exams.getItems().getFirst().exam();
		assertEquals(assignmentId, session.assignmentId());
		assertEquals(session.examLocalId(), installedExam.getId());
		assertEquals("External Assessment", installedExam.getName());

		// The original portable UUID must survive installation,
		// regardless of how SQLite assigns its local primary key.
		var installedIdentity = new SqlitePortableIdentityRepository(contributorDatabase)
				.requireByLocalId(EntityType.EXAM, installedExam.getId());
		assertEquals(session.examPortableId(), installedIdentity.portableId());
		assertTrue(robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton().isDisabled());
		assertTrue(robot.lookup("#corpus-dashboard-create-contributor-assignment").queryButton().isDisabled());
		Button exit = robot.lookup("#corpus-dashboard-exit-assignment").queryButton();
		robot.interact(exit::fire);
		var sessions = new SqliteContributorAssignmentSessionRepository(new SqliteDatabase(contributor.databasePath()));
		assertTrue(sessions.active().isEmpty());
		assertTrue(sessions.find(assignmentId).isPresent());
		ComboBox<Subject> subjectSelector = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		assertTrue(subjectSelector.isVisible());

		// Re-enter the saved assignment using the real Dashboard
		// selector and semantic button activation.
		ComboBox<SqliteContributorAssignmentSessionRepository.Session> assignments = robot
				.lookup("#corpus-dashboard-assignment-selector").queryAs(ComboBox.class);
		Button switchButton = robot.lookup("#corpus-dashboard-switch-assignment").queryButton();
		robot.interact(() -> assignments.getSelectionModel().selectFirst());
		assertFalse(switchButton.isDisabled());
		robot.interact(switchButton::fire);
		assertEquals(assignmentId, sessions.active().orElseThrow().assignmentId());
		awaitAssignedDashboard(robot);

		// Construct a new application instance against the same
		// persisted contributor data, as occurs after restart.
		robot.interact(() -> {
			try {
				application.stop();
				application = new QuestionBankApplication();
				startDesktopApplication(application);
			} catch (Exception exception) {
				throw new IllegalStateException(exception);
			}
		});
		awaitAssignedDashboard(robot);
		Label restoredSubject = robot.lookup("#working-subject-value").queryAs(Label.class);
		assertEquals("Biology", restoredSubject.getText());
		assertTrue(restoredSubject.isVisible());
		assertEquals(assignmentId,
				new SqliteContributorAssignmentSessionRepository(new SqliteDatabase(contributor.databasePath()))
						.active().orElseThrow().assignmentId());
	}

	@Test
	void reimportClosesProgressDialogAndPreservesAssignment(FxRobot robot) throws Exception {
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

	private void prepareMaster(SqliteDatabase database, Path dataRoot) throws Exception {
		Path pdfRoot = dataRoot.resolve("subjects/Biology/exams");
		Files.createDirectories(pdfRoot);
		Files.writeString(pdfRoot.resolve("questions.pdf"), "Question PDF fixture");
		Files.writeString(pdfRoot.resolve("answers.pdf"), "Answer PDF fixture");
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

	private void startDesktopApplication(QuestionBankApplication instance) throws Exception {
		Method startup = QuestionBankApplication.class.getDeclaredMethod("startApplication", Stage.class,
				ApplicationConfig.class);
		startup.setAccessible(true);
		startup.invoke(instance, primaryStage, contributor);
	}
}
