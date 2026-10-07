package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class ExportWorkflowTest extends QuestionBankApplicationUiTestBase {

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
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class,
								RevisionGroupingMode.class, Set.class },
						primaryStage, applicationConfig, chemistry, destination, null, null);
				disabledWhileStarting.set(exportItem.isDisable());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertTrue(disabledWhileStarting.get());

		// The helper waits for the DialogPane-owned button, so rendered button text is
		// never used as the TestFX target.
		fireDialogButton(robot, "OK");
		assertFalse(Files.exists(destination));
		assertFalse(field(application, "scormExportRunning", Boolean.class).booleanValue());
		assertFalse(exportItem.isDisable());
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
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class,
								RevisionGroupingMode.class, Set.class },
						primaryStage, applicationConfig, chemistry, destination, null, null);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		assertFalse(Files.exists(destination));
		setField(application, "revisionExportRunning", Boolean.FALSE);
	}

	@Test
	void revisionHtmlDialogUsesAuthoritativeWorkingSubject(FxRobot robot) throws Exception {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject chemistry = subjects.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();

		// Establish the Subject through the real authoritative Dashboard selector.
		robot.interact(() -> subjects.getSelectionModel().select(chemistry));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(chemistry, field(application, "workingSubject", Subject.class));
		MenuItem exportItem = field(application, "revisionExportMenuItem", MenuItem.class);

		// The export dialog is modal, so schedule the real menu action and leave the
		// test thread available to inspect it.
		Platform.runLater(exportItem::fire);
		waitForDialogShowing(robot, "Export Revision HTML");
		DialogPane dialog = showingDialogPane(robot, "Export Revision HTML");
		Node subjectControl = dialog.lookup("#revision-export-subject");
		assertTrue(subjectControl instanceof Label);
		assertEquals("Chemistry", ((Label) subjectControl).getText());

		// Existing export choices remain available while Subject itself is fixed.
		assertTrue(dialog.lookup("#revision-export-units") != null);
		assertTrue(dialog.lookup("#revision-export-grouping") != null);
		assertTrue(dialog.lookup("#revision-export-destination") != null);
		Node cancelNode = dialog.lookupButton(ButtonType.CANCEL);
		assertTrue(cancelNode instanceof Button);
		robot.interact(((Button) cancelNode)::fire);
		waitForDialogHidden(robot, "Export Revision HTML");
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
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class,
								RevisionGroupingMode.class, Set.class },
						primaryStage, applicationConfig, chemistry, destination, null, null);
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

		// The successful export displays its normal information alert. Close it so the
		// FX success handler can finish.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot.lookup("OK").tryQuery().isPresent());
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(field(application, "revisionExportRunning", Boolean.class).booleanValue());
		assertFalse(exportItem.isDisable());
	}

	@Test
	void revisionHtmlExportWithoutCurrentSyllabusShowsUnavailableMessage(FxRobot robot) throws Exception {
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(new SqliteDatabase(databasePath));
		Subject newSubject = writer.insertSubject("Geography");
		setField(application, "workingSubject", newSubject);
		Platform.runLater(() -> {
			try {
				invoke(application, "showRevisionExportDialog", new Class<?>[] { Stage.class, ApplicationConfig.class },
						primaryStage, applicationConfig);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Export Revision HTML");
		DialogPane unavailable = showingDialogPane(robot, "Export Revision HTML");
		assertEquals("Revision export is not available.", unavailable.getHeaderText());
		assertTrue(unavailable.getContentText().contains("Geography"));
		assertTrue(unavailable.getContentText().contains("no current syllabus version"));

		// The prerequisite message is reached before RevisionExportDialog is
		// constructed, so no export-specific controls can have been created.
		assertNull(unavailable.lookup("#revision-export-units"));
		Node okNode = unavailable.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);
		robot.interact(((Button) okNode)::fire);
		waitForDialogHidden(robot, "Export Revision HTML");
	}

	@Test
	void revisionScormDialogUsesAuthoritativeWorkingSubject(FxRobot robot) throws Exception {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject chemistry = subjects.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjects.getSelectionModel().select(chemistry));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(chemistry, field(application, "workingSubject", Subject.class));
		MenuItem exportItem = field(application, "scormExportMenuItem", MenuItem.class);
		Platform.runLater(exportItem::fire);
		waitForDialogShowing(robot, "Export Revision SCORM");
		DialogPane dialog = showingDialogPane(robot, "Export Revision SCORM");
		Node subjectControl = dialog.lookup("#scorm-export-subject");
		assertTrue(subjectControl instanceof Label);
		assertEquals("Chemistry", ((Label) subjectControl).getText());
		assertTrue(dialog.lookup("#scorm-export-units") != null);
		assertTrue(dialog.lookup("#scorm-export-grouping") != null);
		assertTrue(dialog.lookup("#scorm-export-destination") != null);
		Node cancelNode = dialog.lookupButton(ButtonType.CANCEL);
		assertTrue(cancelNode instanceof Button);
		robot.interact(((Button) cancelNode)::fire);
		waitForDialogHidden(robot, "Export Revision SCORM");
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
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class,
								RevisionGroupingMode.class, Set.class },
						primaryStage, applicationConfig, chemistry, destination, null, null);
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
		fireDialogButton(robot, "OK");
		assertFalse(field(application, "scormExportRunning", Boolean.class).booleanValue());
		assertFalse(exportItem.isDisable());
	}

	@Test
	void revisionScormExportWithoutCurrentSyllabusShowsUnavailableMessage(FxRobot robot) throws Exception {
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(new SqliteDatabase(databasePath));
		Subject newSubject = writer.insertSubject("Geography");
		setField(application, "workingSubject", newSubject);
		Platform.runLater(() -> {
			try {
				invoke(application, "showScormExportDialog", new Class<?>[] { Stage.class, ApplicationConfig.class },
						primaryStage, applicationConfig);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Export Revision SCORM");
		DialogPane unavailable = showingDialogPane(robot, "Export Revision SCORM");
		assertEquals("Revision export is not available.", unavailable.getHeaderText());
		assertTrue(unavailable.getContentText().contains("Geography"));
		assertTrue(unavailable.getContentText().contains("no current syllabus version"));
		assertNull(unavailable.lookup("#scorm-export-units"));
		Node okNode = unavailable.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);
		robot.interact(((Button) okNode)::fire);
		waitForDialogHidden(robot, "Export Revision SCORM");
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
						new Class<?>[] { Stage.class, ApplicationConfig.class, Subject.class, Path.class,
								RevisionGroupingMode.class, Set.class },
						primaryStage, applicationConfig, chemistry, destination, null, null);
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

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
