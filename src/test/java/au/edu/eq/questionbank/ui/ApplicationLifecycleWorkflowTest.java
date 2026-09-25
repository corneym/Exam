package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

@Tag("ui")
@Tag("workflow-ui")
class ApplicationLifecycleWorkflowTest extends QuestionBankApplicationUiTestBase {

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
}
