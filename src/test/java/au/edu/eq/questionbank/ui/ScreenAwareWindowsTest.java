package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class ScreenAwareWindowsTest {
	private Stage stage;

	@Start
	void start(Stage stage) {
		this.stage = stage;
		stage.setScene(new Scene(new StackPane(new Button("Essential action")), 1400, 900));
		stage.show();
	}

	@Test
	void oversizedStageGetsReachableScrollableContentAndReducedMinimum(FxRobot robot) {
		robot.interact(() -> {
			stage.setMinWidth(1200);
			stage.setMinHeight(800);
			Rectangle2D screen = new Rectangle2D(0, 0, 800, 560);
			ScreenAwareWindows.fit(stage, List.of(screen), screen);
			assertTrue(stage.getWidth() <= 800);
			assertTrue(stage.getHeight() <= 560);
			assertTrue(stage.getMinWidth() <= 800);
			assertTrue(stage.getMinHeight() <= 560);
			ScrollPane viewport = assertInstanceOf(ScrollPane.class, stage.getScene().getRoot());
			assertEquals("Essential action", ((Button) ((StackPane) viewport.getContent()).getChildren().getFirst()).getText());
			ScreenAwareWindows.fit(stage, List.of(screen), screen);
			assertSame(viewport, stage.getScene().getRoot());
		});
	}

	@Test
	void dialogRemainsUsableAfterFitAndReopening(FxRobot robot) {
		Dialog<Void>[] holder = new Dialog[1];
		robot.interact(() -> {
			Dialog<Void> dialog = new Dialog<>();
			holder[0] = dialog;
			dialog.initOwner(stage);
			dialog.getDialogPane().setPrefSize(1400, 900);
			dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
			dialog.show();
			Rectangle2D screen = new Rectangle2D(0, 0, 800, 560);
			ScreenAwareWindows.fit((Stage) dialog.getDialogPane().getScene().getWindow(), List.of(screen), screen);
			assertTrue(dialog.getWidth() <= 800);
			assertTrue(dialog.getHeight() <= 560);
			((Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE)).fire();
		});
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			holder[0].show();
			assertTrue(holder[0].isShowing());
			holder[0].close();
		});
	}
}
