package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.Subject;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@ExtendWith(ApplicationExtension.class)
class ScormExportDialogTest {

	private Stage stage;

	@Test
	void defaultsToCurrentSubjectAndRequiresDestination() throws Exception {
		Subject chemistry = new Subject(1, "Chemistry");
		Subject physics = new Subject(2, "Physics");

		ScormExportDialog[] holder = new ScormExportDialog[1];

		org.testfx.api.FxToolkit
				.setupFixture(() -> holder[0] = new ScormExportDialog(stage, List.of(chemistry, physics), chemistry));

		ScormExportDialog dialog = holder[0];

		@SuppressWarnings("unchecked")
		ComboBox<Subject> subjectBox = (ComboBox<Subject>) dialog.getDialogPane().lookup("#scorm-export-subject");

		Field exportButtonField = ScormExportDialog.class.getDeclaredField("exportButton");

		exportButtonField.setAccessible(true);

		Button exportButton = (Button) exportButtonField.get(dialog);

		assertEquals(List.of(chemistry, physics), subjectBox.getItems());
		assertEquals(chemistry, dialog.getSelectedSubject());
		assertNull(dialog.getDestinationParent());
		assertTrue(exportButton.isDisabled());

		Path destination = Path.of("target", "scorm-export-test").toAbsolutePath().normalize();

		Method setDestination = ScormExportDialog.class.getDeclaredMethod("setDestinationParent", Path.class);

		setDestination.setAccessible(true);

		org.testfx.api.FxToolkit.setupFixture(() -> {
			try {
				setDestination.invoke(dialog, destination);
			} catch (ReflectiveOperationException exception) {
				throw new RuntimeException(exception);
			}
		});

		WaitForAsyncUtils.waitForFxEvents();

		assertEquals(destination, dialog.getDestinationParent());
		assertFalse(exportButton.isDisabled());
	}

	@Start
	void start(Stage stage) {
		this.stage = stage;
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
	}
}
