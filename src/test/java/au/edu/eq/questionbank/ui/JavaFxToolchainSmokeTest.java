package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@ExtendWith(ApplicationExtension.class)
public class JavaFxToolchainSmokeTest {

	private Button button;

	@Start
	public void start(Stage stage) {
		button = new Button("Ready");
		button.setId("toolchain-button");
		button.setOnAction(event -> button.setText("Clicked"));

		stage.setScene(new Scene(new StackPane(button), 240, 120));
		stage.show();
	}

	@Test
	public void testFxCanClickJavaFxControlUnderCurrentToolchain(FxRobot robot) {
		assertEquals("Ready", button.getText());

		robot.clickOn("#toolchain-button");

		assertEquals("Clicked", button.getText());
	}
}