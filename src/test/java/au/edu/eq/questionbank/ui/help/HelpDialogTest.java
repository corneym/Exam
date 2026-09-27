package au.edu.eq.questionbank.ui.help;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class HelpDialogTest {

	private Stage stage;

	@Test
	void packagedHelpIndexAndStylesheetLoad(FxRobot robot) throws Exception {
		HelpDialog[] dialogHolder = new HelpDialog[1];
		robot.interact(() -> {

			// Construct and show Help on the JavaFX application thread because
			// WebView and Dialog are both JavaFX scene-graph components.
			HelpDialog dialog = new HelpDialog(stage);
			dialogHolder[0] = dialog;
			dialog.show();
		});
		WebView webView = robot.lookup("#help-web-view").queryAs(WebView.class);
		AtomicReference<Worker.State> loadState = new AtomicReference<>();
		robot.interact(() -> {
			Worker<Void> loadWorker = webView.getEngine().getLoadWorker();

			// WebEngine state may only be inspected on the FX application thread.
			// Copy each state transition into a thread-safe value that the JUnit
			// thread can wait on without touching WebEngine directly.
			loadState.set(loadWorker.getState());
			loadWorker.stateProperty().addListener((_, _, newState) -> loadState.set(newState));
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> loadState.get() == Worker.State.SUCCEEDED
				|| loadState.get() == Worker.State.FAILED || loadState.get() == Worker.State.CANCELLED);
		assertEquals(Worker.State.SUCCEEDED, loadState.get());
		AtomicReference<String> title = new AtomicReference<>();
		AtomicReference<String> location = new AtomicReference<>();
		AtomicInteger stylesheetCount = new AtomicInteger();
		robot.interact(() -> {

			// WebEngine document access is also FX-thread confined, so capture the
			// loaded document properties before asserting them on the JUnit thread.
			title.set(webView.getEngine().getTitle());
			location.set(webView.getEngine().getLocation());
			Number loadedStylesheets = (Number) webView.getEngine().executeScript("document.styleSheets.length");
			stylesheetCount.set(loadedStylesheets.intValue());
		});
		assertEquals("Exam Question Bank Help", title.get());
		assertTrue(location.get().endsWith("/au/edu/eq/questionbank/help/index.html"));

		// CSS must be resolved as a packaged relative resource rather than being
		// embedded in Java or silently omitted from Help presentation.
		assertEquals(1, stylesheetCount.get());
		robot.interact(dialogHolder[0]::close);
	}

	@Start
	void start(Stage stage) {
		this.stage = stage;

		// Dialog ownership requires a real showing JavaFX window, while the owner
		// itself does not need any application workflow for this focused test.
		stage.setScene(new Scene(new StackPane(), 300, 200));
		stage.show();
	}
}
