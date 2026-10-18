package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentity;
import au.edu.eq.questionbank.importer.legacy.LegacyExamKey;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionRow;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionSheet;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class LegacyExamMappingDialogTest {

	private LegacyExamMappingDialog dialog;
	private Stage owner;
	private List<LegacyQuestionSheet> sheets;

	@Test
	void blankExaminationNamePreventsConfirmation(FxRobot robot) {
		TextField examName = robot.lookup("#legacy-mapping-exam-2").queryAs(TextField.class);
		Button confirm = robot.lookup("#legacy-mapping-confirm").queryButton();
		robot.interact(examName::clear);
		assertTrue(confirm.isDisabled());
		robot.interact(() -> examName.setText("Mock Examination"));
		assertFalse(confirm.isDisabled());
	}

	@Test
	void caseOnlyMatchRequiresAcknowledgementAndPreservesSavedSpelling(FxRobot robot) {
		Exam saved = exam(101, "External assessment", ExamCaptureState.COMPLETE);
		replaceDialog(robot, List.of(new LegacyExamMappingDialog.ExistingExamChoice(saved, 3, 1)));
		Button confirm = robot.lookup("#legacy-mapping-confirm").queryButton();
		RadioButton reuse = robot.lookup("#legacy-mapping-existing-1-101").queryAs(RadioButton.class);
		CheckBox acknowledge = robot.lookup("#legacy-mapping-acknowledge-1").queryAs(CheckBox.class);
		Label status = robot.lookup("#legacy-mapping-status-1").queryAs(Label.class);
		assertTrue(reuse.isSelected());
		assertTrue(status.getText().contains("capitalisation"));
		assertTrue(confirm.isDisabled());
		robot.interact(acknowledge::fire);
		assertFalse(confirm.isDisabled());
		robot.interact(confirm::fire);
		assertEquals(new LegacyExamIdentity("QCAA", "External assessment"),
				dialog.getResult().get(new LegacyExamKey("QCAA", 2020)));
	}

	@Test
	void confirmsThreeDistinctExaminationIdentities(FxRobot robot) {
		Button confirm = robot.lookup("#legacy-mapping-confirm").queryButton();
		assertFalse(confirm.isDisabled());
		robot.interact(confirm::fire);
		Map<LegacyExamKey, LegacyExamIdentity> mappings = dialog.getResult();
		assertNotNull(mappings);
		assertEquals(3, mappings.size());
		assertEquals(new LegacyExamIdentity("QCAA", "External Assessment"),
				mappings.get(new LegacyExamKey("QCAA", 2020)));
		assertEquals(new LegacyExamIdentity("QCAA", "Mock Examination"),
				mappings.get(new LegacyExamKey("QCAA Mock", 2020)));
		assertEquals(new LegacyExamIdentity("QCAA", "Sample Examination"),
				mappings.get(new LegacyExamKey("QCAA Sample", 2020)));
	}

	@Test
	void examinationIdentityCanBeCorrected(FxRobot robot) {
		TextField examName = robot.lookup("#legacy-mapping-exam-2").queryAs(TextField.class);
		Button confirm = robot.lookup("#legacy-mapping-confirm").queryButton();
		robot.interact(() -> examName.setText("Trial Examination"));
		robot.interact(confirm::fire);
		assertEquals("Trial Examination", dialog.getResult().get(new LegacyExamKey("QCAA Mock", 2020)).examName());
	}

	@Test
	void multipleCaseOnlyMatchesRequireExplicitChoice(FxRobot robot) {
		Exam old = exam(101, "External assessment", ExamCaptureState.COMPLETE);
		Exam duplicated = exam(102, "External Assessment", ExamCaptureState.ACTIVE);
		replaceDialog(robot, List.of(new LegacyExamMappingDialog.ExistingExamChoice(old, 3, 1),
				new LegacyExamMappingDialog.ExistingExamChoice(duplicated, 3, 0)));
		Button confirm = robot.lookup("#legacy-mapping-confirm").queryButton();
		assertTrue(confirm.isDisabled());
		Label status = robot.lookup("#legacy-mapping-status-1").queryAs(Label.class);
		assertTrue(status.getText().contains("Multiple saved Exams"));
		RadioButton original = robot.lookup("#legacy-mapping-existing-1-101").queryAs(RadioButton.class);
		RadioButton duplicate = robot.lookup("#legacy-mapping-existing-1-102").queryAs(RadioButton.class);
		assertFalse(original.isSelected());
		assertFalse(duplicate.isSelected());
		robot.interact(original::fire);
		assertFalse(confirm.isDisabled());
		robot.interact(confirm::fire);
		assertEquals("External assessment", dialog.getResult().get(new LegacyExamKey("QCAA", 2020)).examName());
	}

	@Start
	void start(Stage stage) {
		owner = stage;
		stage.setScene(new Scene(new VBox(), 500, 300));
		stage.show();
		LegacyQuestionRow question = new LegacyQuestionRow(2020, "MCQ", "1", 1, "4.1.2", "C", false);
		sheets = List.of(new LegacyQuestionSheet("QCAA", List.of(question)),
				new LegacyQuestionSheet("QCAA Mock", List.of(question)),
				new LegacyQuestionSheet("QCAA Sample", List.of(question)));
		dialog = new LegacyExamMappingDialog(stage, sheets);
		dialog.show();
	}

	private Exam exam(long id, String name, ExamCaptureState state) {
		return new Exam(id, new Subject(1, "Chemistry"), new ExamProvider(2, "QCAA"), 2020, name, state);
	}

	private void replaceDialog(FxRobot robot, List<LegacyExamMappingDialog.ExistingExamChoice> choices) {
		robot.interact(() -> {
			dialog.hide();
			dialog = new LegacyExamMappingDialog(owner, sheets, choices);
			dialog.show();
		});
	}
}
