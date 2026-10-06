package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.backup.BackupKind;
import au.edu.eq.questionbank.service.backup.BackupManifest;
import au.edu.eq.questionbank.service.backup.RestoreResult;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

@Tag("ui")
@Tag("workflow-ui")
class ApplicationLifecycleWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void aboutDisplaysAuthoritativeApplicationVersion(FxRobot robot) throws Exception {
		MenuItem aboutItem = helpAboutMenuItem();

		// About is modal, so queue the menu action and leave the JUnit thread free to
		// inspect and close the resulting Alert.
		Platform.runLater(aboutItem::fire);
		waitForDialogShowing(robot, "About Exam Question Bank");
		javafx.scene.control.DialogPane dialog = showingDialogPane(robot, "About Exam Question Bank");
		AtomicReference<String> contentText = new AtomicReference<>();
		robot.interact(() -> {
			javafx.scene.Node contentLabel = dialog.lookup(".content.label");
			if (!(contentLabel instanceof javafx.scene.control.Label label)) {
				throw new AssertionError("About dialog content label not found");
			}
			contentText.set(label.getText());
		});
		assertEquals("""
				An application for importing, classifying, capturing and managing examination questions.

				Version: %s
				""".formatted(ApplicationVersion.current()).strip(), contentText.get());
		fireDialogButton(robot, "OK");
	}

	@Test
	void dataRootChangeCanExitWithoutRestart(FxRobot robot) throws Exception {
		Path propertiesDirectory = Files.createTempDirectory("question-bank-options-");
		Path propertiesFile = propertiesDirectory.resolve("questionbank.properties");
		Path newDataRoot = propertiesDirectory.resolve("new-data");
		ApplicationConfig.saveDataRoot(propertiesFile, applicationConfig.dataRoot());
		AtomicInteger exitCount = new AtomicInteger();
		AtomicInteger restartCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		setField(application, "applicationPropertiesFile", propertiesFile);
		setField(application, "applicationRestartAction", (Runnable) restartCount::incrementAndGet);
		assertEquals(0, automaticBackupCount());
		openDataRootRestartPrompt(robot, newDataRoot);
		fireDialogButton(robot, "Exit");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, exitCount.get());
		assertEquals(0, restartCount.get());
		assertEquals(1, automaticBackupCount());
		assertEquals(newDataRoot.toAbsolutePath().normalize(), ApplicationConfig.load(propertiesFile).dataRoot());
	}

	@Test
	void dataRootChangeCanRestartAfterNormalShutdown(FxRobot robot) throws Exception {
		Path propertiesDirectory = Files.createTempDirectory("question-bank-options-");
		Path propertiesFile = propertiesDirectory.resolve("questionbank.properties");
		Path newDataRoot = propertiesDirectory.resolve("new-data");
		ApplicationConfig.saveDataRoot(propertiesFile, applicationConfig.dataRoot());
		AtomicInteger exitCount = new AtomicInteger();
		AtomicInteger restartCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		setField(application, "applicationPropertiesFile", propertiesFile);
		setField(application, "applicationRestartAction", (Runnable) restartCount::incrementAndGet);
		assertEquals(0, automaticBackupCount());
		openDataRootRestartPrompt(robot, newDataRoot);
		fireDialogButton(robot, "Restart Now");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(0, exitCount.get());
		assertEquals(1, restartCount.get());
		assertEquals(1, automaticBackupCount());
		assertEquals(newDataRoot.toAbsolutePath().normalize(), ApplicationConfig.load(propertiesFile).dataRoot());
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
	void fileExitIsBlockedWhileQuestionSaveIsInProgress(FxRobot robot) throws Exception {
		QuestionCapturePane pane = field(application, "questionCapturePane", QuestionCapturePane.class);
		AtomicInteger exitCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		assertEquals(0, automaticBackupCount());
		setField(pane, "questionSaveInProgress", true);
		try {
			MenuItem exitItem = fileExitMenuItem();
			Platform.runLater(exitItem::fire);
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> robot.lookup("Save in progress").tryQuery().isPresent());
			assertEquals(0, exitCount.get());
			assertEquals(0, automaticBackupCount());
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot.lookup("OK").tryQuery().isPresent());
			Button okButton = robot.lookup("OK").queryButton();
			robot.interact(okButton::fire);
			WaitForAsyncUtils.waitForFxEvents();
		} finally {
			setField(pane, "questionSaveInProgress", false);
		}
	}

	@Test
	void helpContentsOpensPackagedHelp(FxRobot robot) throws Exception {
		MenuItem helpContents = helpContentsMenuItem();

		// The Help action uses showAndWait(). Queue it without blocking the JUnit
		// thread so the test can interact with the modal dialog while it is open.
		Platform.runLater(helpContents::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot.lookup("#help-web-view").tryQuery().isPresent());
		WebView helpWebView = robot.lookup("#help-web-view").queryAs(WebView.class);
		AtomicReference<Worker.State> loadState = new AtomicReference<>();
		robot.interact(() -> {
			Worker<Void> loadWorker = helpWebView.getEngine().getLoadWorker();

			// WebEngine state is confined to the JavaFX application thread. Mirror
			// transitions into thread-safe state for the JUnit wait below.
			loadState.set(loadWorker.getState());
			loadWorker.stateProperty().addListener((_, _, newState) -> loadState.set(newState));
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> loadState.get() == Worker.State.SUCCEEDED
				|| loadState.get() == Worker.State.FAILED || loadState.get() == Worker.State.CANCELLED);
		assertEquals(Worker.State.SUCCEEDED, loadState.get());
		robot.interact(() -> {

			// Closing the modal Help window releases the nested showAndWait event
			// loop and leaves the primary application window running.
			helpWebView.getScene().getWindow().hide();
		});
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Test
	void restoreIsBlockedWhileAnswerSaveIsInProgress(FxRobot robot) throws Exception {
		AnswerCapturePane pane = answerCapturePane();
		AtomicInteger exitCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		setField(pane, "answerSaveInProgress", true);
		try {
			MenuItem restoreItem = fileRestoreMenuItem();
			Platform.runLater(restoreItem::fire);
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> robot.lookup("Save in progress").tryQuery().isPresent());
			assertEquals(0, exitCount.get());
			fireDialogButton(robot, "OK");
		} finally {
			setField(pane, "answerSaveInProgress", false);
		}
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void successfulRestoreCanRestartWithoutSecondAutomaticBackup(FxRobot robot) throws Exception {
		AtomicInteger exitCount = new AtomicInteger();
		AtomicInteger restartCount = new AtomicInteger();
		setField(application, "applicationExitAction", (Runnable) exitCount::incrementAndGet);
		setField(application, "applicationRestartAction", (Runnable) restartCount::incrementAndGet);
		RestoreResult restoreResult = new RestoreResult(
				BackupManifest.current(BackupKind.FULL, Instant.parse("2026-10-06T00:00:00Z"),
						SqliteDatabase.latestSchemaVersion(), "Test"),
				Files.createTempFile("question-bank-safety-", ".zip"));
		assertEquals(0, automaticBackupCount());
		Platform.runLater(() -> {
			try {
				invoke(application, "completeSuccessfulRestore", new Class<?>[] { Stage.class, RestoreResult.class },
						primaryStage, restoreResult);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Restart Required");
		fireDialogButton(robot, "Restart Now");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(0, exitCount.get());
		assertEquals(1, restartCount.get());

		// Restore has already performed its own pre-restore safety backup and resource
		// closure. The post-restore restart must not create an ordinary automatic
		// backup
		// over the restored database.
		assertEquals(0, automaticBackupCount());
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

	private MenuItem fileOptionsMenuItem() {
		BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
		MenuBar menuBar = (MenuBar) root.getTop();
		for (MenuItem item : menuBar.getMenus().get(0).getItems()) {
			if ("Op_tions...".equals(item.getText())) {
				return item;
			}
		}
		throw new AssertionError("File -> Options menu item not found");
	}

	private MenuItem fileRestoreMenuItem() {
		BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
		MenuBar menuBar = (MenuBar) root.getTop();
		for (MenuItem item : menuBar.getMenus().get(0).getItems()) {
			if ("_Restore Backup...".equals(item.getText())) {
				return item;
			}
		}
		throw new AssertionError("File -> Restore Backup menu item not found");
	}

	private MenuItem helpAboutMenuItem() {
		BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
		MenuBar menuBar = (MenuBar) root.getTop();
		for (Menu menu : menuBar.getMenus()) {
			if (!"_Help".equals(menu.getText())) {
				continue;
			}
			for (MenuItem item : menu.getItems()) {
				if ("_About...".equals(item.getText())) {
					return item;
				}
			}
		}
		throw new AssertionError("Help -> About menu item not found");
	}

	private MenuItem helpContentsMenuItem() {
		BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
		MenuBar menuBar = (MenuBar) root.getTop();
		for (Menu menu : menuBar.getMenus()) {
			if (!"_Help".equals(menu.getText())) {
				continue;
			}
			for (MenuItem item : menu.getItems()) {
				if ("_Help Contents...".equals(item.getText())) {
					return item;
				}
			}
		}
		throw new AssertionError("Help -> Help Contents menu item not found");
	}

	private void openDataRootRestartPrompt(FxRobot robot, Path newDataRoot) {
		Platform.runLater(fileOptionsMenuItem()::fire);
		waitForDialogShowing(robot, "Options");
		TextField dataRootField = lookupInShowingDialog(robot, "Options", ".text-field", TextField.class);
		robot.interact(() -> dataRootField.setText(newDataRoot.toString()));
		DialogPane options = showingDialogPane(robot, "Options");
		ButtonType saveButtonType = options.getButtonTypes().stream()
				.filter(buttonType -> "Save".equals(buttonType.getText())).findFirst().orElseThrow();
		Node saveNode = options.lookupButton(saveButtonType);
		assertTrue(saveNode instanceof Button);

		// Saving Options immediately opens the modal restart decision. Schedule the
		// control action so the JUnit thread remains available for that second dialog.
		fireControlLater((Button) saveNode);
		waitForDialogShowing(robot, "Restart Required");
	}
}
