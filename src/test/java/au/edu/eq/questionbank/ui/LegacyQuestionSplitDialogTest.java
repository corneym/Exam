package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class LegacyQuestionSplitDialogTest {

	private Stage owner;
	private SqliteCurriculumRepository curriculumRepository;
	private Question question;
	private Subtopic firstSubtopic;
	private Subtopic secondSubtopic;

	@Test
	@SuppressWarnings("unchecked")
	void returnsExplicitTwoPartSplitDefinition(FxRobot robot) {
		AtomicReference<LegacyQuestionSplitDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			LegacyQuestionSplitDialog dialog = new LegacyQuestionSplitDialog(owner, question, curriculumRepository,
					null);
			dialogRef.set(dialog);
			dialog.show();
		});
		TextField sourceCode = robot.lookup("#legacy-split-source-code").queryAs(TextField.class);
		TextField partACode = robot.lookup("#legacy-split-part-0-code").queryAs(TextField.class);
		TextField partAMarks = robot.lookup("#legacy-split-part-0-marks").queryAs(TextField.class);
		TextField partBCode = robot.lookup("#legacy-split-part-1-code").queryAs(TextField.class);
		TextField partBMarks = robot.lookup("#legacy-split-part-1-marks").queryAs(TextField.class);
		ComboBox<CurriculumNode> partBClassification = robot.lookup("#legacy-split-part-1-classification")
				.queryAs(ComboBox.class);
		ComboBox<LegacyQuestionSplitDialog.PreambleChoice> preamble = robot.lookup("#legacy-split-preamble-choice")
				.queryAs(ComboBox.class);
		Button continueButton = robot.lookup("#legacy-split-continue").queryAs(Button.class);
		assertEquals("3", sourceCode.getText());
		assertFalse(sourceCode.isEditable());
		assertEquals("3a", partACode.getText());
		assertEquals("3b", partBCode.getText());

		// Marks and preamble semantics require explicit confirmation rather than
		// being guessed from the unsplit legacy Question.
		assertTrue(partAMarks.getText().isBlank());
		assertTrue(partBMarks.getText().isBlank());
		assertTrue(continueButton.isDisabled());
		robot.interact(() -> {
			partAMarks.setText("2");
			partBMarks.setText("3");
			partBClassification.setValue(secondSubtopic);
			preamble.setValue(LegacyQuestionSplitDialog.PreambleChoice.NO_SHARED_PREAMBLE);
		});
		assertFalse(continueButton.isDisabled());
		robot.interact(continueButton::fire);
		LegacyQuestionSplitDialog.Result result = dialogRef.get().getResult();
		assertNotNull(result);
		assertEquals("3", result.sourceQuestionCode());
		assertEquals(2, result.parts().size());
		assertEquals("3a", result.parts().get(0).questionCode());
		assertEquals(2, result.parts().get(0).marks());
		assertEquals(firstSubtopic, result.parts().get(0).classification());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, result.parts().get(0).responseType());
		assertEquals("3b", result.parts().get(1).questionCode());
		assertEquals(3, result.parts().get(1).marks());
		assertEquals(secondSubtopic, result.parts().get(1).classification());
		assertEquals(0, result.retainedPartIndex());
		assertEquals(LegacyQuestionSplitDialog.PreambleChoice.NO_SHARED_PREAMBLE, result.preambleChoice());
	}

	@Start
	void start(Stage stage) throws Exception {
		owner = stage;
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
		SqliteDatabase database = new SqliteDatabase(
				Files.createTempDirectory("legacy-split-dialog-").resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 0);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 0);
		firstSubtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "First subtopic", 0);
		secondSubtopic = curriculumWriter.insertSubtopic(topic, "1.1.2", "Second subtopic", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		question = new SqliteQuestionRepository(database).save(booklet, "3", "", 5, List.of(), firstSubtopic, true,
				null, null, QuestionResponseType.WRITTEN_RESPONSE);
		curriculumRepository = new SqliteCurriculumRepository(database);
	}
}
