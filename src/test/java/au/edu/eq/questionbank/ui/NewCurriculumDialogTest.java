package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.Subject;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class NewCurriculumDialogTest {

	private Stage owner;

	@Test
	void defaultsToExistingSubjectWhenSubjectsExist(FxRobot robot) {
		Subject chemistry = new Subject(1, "Chemistry");
		Subject engineering = new Subject(2, "Engineering");
		AtomicReference<NewCurriculumDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			NewCurriculumDialog dialog = new NewCurriculumDialog(owner, List.of(chemistry, engineering));
			dialogRef.set(dialog);
			dialog.show();
		});
		NewCurriculumDialog dialog = dialogRef.get();
		RadioButton existing = robot.lookup("#existing-curriculum-subject").queryAs(RadioButton.class);
		RadioButton createNew = robot.lookup("#new-curriculum-subject").queryAs(RadioButton.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> subjects = robot.lookup("#new-curriculum-subject-box").queryAs(ComboBox.class);
		TextField newSubject = robot.lookup("#new-curriculum-subject-name").queryAs(TextField.class);
		assertTrue(existing.isSelected());
		assertFalse(createNew.isSelected());
		assertFalse(subjects.isDisabled());
		assertTrue(newSubject.isDisabled());
		assertEquals(chemistry, subjects.getValue());
		assertEquals("Chemistry", dialog.getSubjectName());
		robot.interact(dialog::close);
	}

	@Test
	void forcesNewSubjectModeWhenNoSubjectsExist(FxRobot robot) {
		AtomicReference<NewCurriculumDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			NewCurriculumDialog dialog = new NewCurriculumDialog(owner, List.of());
			dialogRef.set(dialog);
			dialog.show();
		});
		NewCurriculumDialog dialog = dialogRef.get();
		RadioButton existing = robot.lookup("#existing-curriculum-subject").queryAs(RadioButton.class);
		RadioButton createNew = robot.lookup("#new-curriculum-subject").queryAs(RadioButton.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subject> subjects = robot.lookup("#new-curriculum-subject-box").queryAs(ComboBox.class);
		TextField subjectName = robot.lookup("#new-curriculum-subject-name").queryAs(TextField.class);
		assertTrue(existing.isDisabled());
		assertTrue(createNew.isSelected());
		assertTrue(subjects.isDisabled());
		assertFalse(subjectName.isDisabled());
		robot.interact(dialog::close);
	}

	@Start
	void start(Stage stage) {
		owner = stage;
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
	}

	@Test
	void supportsCreatingNewSubject(FxRobot robot) {
		Subject chemistry = new Subject(1, "Chemistry");
		AtomicReference<NewCurriculumDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			NewCurriculumDialog dialog = new NewCurriculumDialog(owner, List.of(chemistry));
			dialogRef.set(dialog);
			dialog.show();
		});
		NewCurriculumDialog dialog = dialogRef.get();
		RadioButton createNew = robot.lookup("#new-curriculum-subject").queryAs(RadioButton.class);
		TextField subjectName = robot.lookup("#new-curriculum-subject-name").queryAs(TextField.class);
		TextField version = robot.lookup("#new-curriculum-version").queryAs(TextField.class);
		CheckBox current = robot.lookup("#new-curriculum-current").queryAs(CheckBox.class);
		robot.interact(() -> {
			createNew.fire();
			subjectName.setText("Engineering");
			version.setText("2025");
			current.setSelected(true);
		});
		assertEquals("Engineering", dialog.getSubjectName());
		assertEquals("2025", dialog.getVersionName());
		assertTrue(dialog.isCurrent());
		robot.interact(dialog::close);
	}
}
