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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class LegacyExamMappingDialogTest {

	private LegacyExamMappingDialog dialog;

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

	@Start
	void start(Stage stage) {
		stage.setScene(new Scene(new VBox(), 500, 300));
		stage.show();
		LegacyQuestionRow question = new LegacyQuestionRow(2020, "MCQ", "1", 1, "4.1.2", "C", false);
		List<LegacyQuestionSheet> sheets = List.of(new LegacyQuestionSheet("QCAA", List.of(question)),
				new LegacyQuestionSheet("QCAA Mock", List.of(question)),
				new LegacyQuestionSheet("QCAA Sample", List.of(question)));
		dialog = new LegacyExamMappingDialog(stage, sheets);
		dialog.show();
	}
}
