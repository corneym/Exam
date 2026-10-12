package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.util.Duration;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class ApplicationBusyOverlayTest {

	private ApplicationBusyOverlay overlay;
	private Label content;

	@Test
	void cancellationRestoresApplication(FxRobot robot) {
		robot.interact(() -> overlay.begin("Preparing Answer Capture..."));
		assertTrue(overlay.isBusy());
		robot.interact(overlay::cancel);
		Node shield = overlay.lookup("#workspace-busy-overlay");
		assertFalse(overlay.isBusy());
		assertFalse(content.isDisabled());
		assertFalse(shield.isVisible());
		assertFalse(shield.isManaged());
	}

	@Test
	void displaysCentredOverlayAndRestoresApplication(FxRobot robot) throws TimeoutException {
		AtomicLong request = new AtomicLong();
		robot.interact(() -> request.set(overlay.begin("Preparing Question Capture...")));
		Node shield = overlay.lookup("#workspace-busy-overlay");
		Label status = (Label) overlay.lookup("#workspace-busy-label");
		assertTrue(overlay.isBusy());
		assertTrue(content.isDisabled());
		assertEquals("Preparing Question Capture...", status.getText());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> visibleOnFx(robot, shield));
		assertTrue(shield.isManaged());

		// The shield belongs to the complete Scene root,
		// not an individual capture pane.
		assertEquals(overlay, shield.getParent());
		robot.interact(() -> overlay.finish(request.get()));
		assertFalse(overlay.isBusy());
		assertFalse(content.isDisabled());
		assertFalse(shield.isVisible());
		assertFalse(shield.isManaged());
		assertEquals("", status.getText());
	}

	@Test
	void quickOperationDoesNotLeaveVisibleSpinner(FxRobot robot) throws Exception {
		AtomicLong request = new AtomicLong();
		robot.interact(() -> {
			request.set(overlay.begin("Saving Question..."));
			overlay.finish(request.get());
		});

		// Allow the original delayed-display interval to pass.
		// A completed operation must never reveal a stale spinner.
		Thread.sleep(300);
		WaitForAsyncUtils.waitForFxEvents();
		Node shield = overlay.lookup("#workspace-busy-overlay");
		assertFalse(overlay.isBusy());
		assertFalse(content.isDisabled());
		assertFalse(shield.isVisible());
	}

	@Test
	void repeatedRequestCannotReplaceCurrentOperation(FxRobot robot) {
		AtomicLong request = new AtomicLong();
		AtomicLong duplicate = new AtomicLong();
		robot.interact(() -> {
			request.set(overlay.begin("Opening Question booklet..."));
			duplicate.set(overlay.begin("Preparing Answer Capture..."));
		});
		Label status = (Label) overlay.lookup("#workspace-busy-label");
		assertTrue(request.get() > 0);
		assertEquals(0, duplicate.get());
		assertEquals("Opening Question booklet...", status.getText());
		assertTrue(overlay.isBusy());
		assertTrue(content.isDisabled());
		robot.interact(() -> overlay.finish(request.get()));
		assertFalse(overlay.isBusy());
	}

	@Test
	void staleCompletionCannotDismissNewerOperation(FxRobot robot) {
		AtomicLong first = new AtomicLong();
		AtomicLong second = new AtomicLong();
		robot.interact(() -> {
			first.set(overlay.begin("First operation"));
			overlay.finish(first.get());
			second.set(overlay.begin("Second operation"));

			// A late completion from the first operation
			// must not release the second operation.
			overlay.finish(first.get());
		});
		Label status = (Label) overlay.lookup("#workspace-busy-label");
		assertTrue(overlay.isBusy());
		assertTrue(content.isDisabled());
		assertEquals("Second operation", status.getText());
		assertTrue(overlay.owns(second.get()));
		assertFalse(overlay.owns(first.get()));
		assertFalse(overlay.owns(0));
		robot.interact(() -> overlay.finish(second.get()));
		assertFalse(overlay.isBusy());
		assertFalse(content.isDisabled());
	}

	@Start
	void start(Stage stage) {
		content = new Label("Application content");
		content.setId("test-application-content");
		overlay = new ApplicationBusyOverlay(content, Duration.millis(180));
		stage.setScene(new Scene(overlay, 600, 400));
		stage.show();
	}

	private boolean visibleOnFx(FxRobot robot, Node node) {
		final boolean[] visible = { false };
		robot.interact(() -> visible[0] = node.isVisible());
		return visible[0];
	}
}
