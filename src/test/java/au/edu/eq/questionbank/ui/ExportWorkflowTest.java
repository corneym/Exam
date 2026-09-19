package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.scene.control.Button;
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
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
		WaitForAsyncUtils.waitForFxEvents();
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
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot.lookup("OK").tryQuery().isPresent());
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
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
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
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

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
