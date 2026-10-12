package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class QuestionBankApplicationFocusLifecycleTest extends QuestionBankApplicationUiTestBase {

	@Test
	void repeatedApplicationStartsAndStopsReleaseFocusRegistrations(FxRobot robot) throws Exception {
		Stage reusedStage = primaryStage;
		for (int cycle = 0; cycle < 3; cycle++) {
			QuestionBankApplication stoppingApplication = application;

			// Each application must register its focus refresh listener.
			assertNotNull(field(stoppingApplication, "primaryStageFocusRegistration", FocusRefreshRegistration.class));
			robot.interact(() -> {
				try {
					stoppingApplication.stop();
				} catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});

			// Shutdown must discard the registration owned by this application.
			assertNull(field(stoppingApplication, "primaryStageFocusRegistration", FocusRefreshRegistration.class));

			// Create the next fixture using the same TestFX Stage.
			// The final fixture is closed by the inherited @AfterEach.
			WaitForAsyncUtils.asyncFx(() -> {
				super.start(reusedStage);
				return null;
			}).get();
			WaitForAsyncUtils.waitForFxEvents();
		}
		assertNotNull(field(application, "primaryStageFocusRegistration", FocusRefreshRegistration.class));
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
