package au.edu.eq.questionbank.ui.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.Subject;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CurriculumImportDialogTest {

	private Stage owner;
	private Path curriculumRoot;

	@Test
	void dashboardImportUsesReadOnlyAuthoritativeSubject(FxRobot robot) {
		Subject chemistry = new Subject(1, "Chemistry");
		AtomicReference<CurriculumImportDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			CurriculumImportDialog dialog = new CurriculumImportDialog(owner, curriculumRoot, chemistry);
			dialogRef.set(dialog);
			dialog.show();
		});
		CurriculumImportDialog dialog = dialogRef.get();
		TextField subject = robot.lookup("#curriculum-import-subject").queryAs(TextField.class);

		// Excel import may supply syllabus metadata and source data, but it cannot
		// redirect the operation to another application Subject.
		assertEquals("Chemistry", subject.getText());
		assertFalse(subject.isEditable());
		assertEquals("Chemistry", dialog.getSubjectName());
		robot.interact(dialog::close);
	}

	@Start
	void start(Stage stage) throws Exception {
		owner = stage;
		curriculumRoot = Files.createTempDirectory("curriculum-import-dialog-");
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
	}
}
