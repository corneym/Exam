package au.edu.eq.questionbank.ui.correction;

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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class LegacyQuestionMetadataDialogTest {

	private Stage owner;
	private SqliteCurriculumRepository curriculumRepository;
	private Question question;
	private Subtopic firstSubtopic;
	private Subtopic secondSubtopic;

	@Test
	void disablesSaveForInvalidMetadata(FxRobot robot) {
		AtomicReference<LegacyQuestionMetadataDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			LegacyQuestionMetadataDialog dialog = new LegacyQuestionMetadataDialog(owner, question,
					curriculumRepository);
			dialogRef.set(dialog);
			dialog.show();
		});
		TextField questionCode = robot.lookup("#legacy-metadata-question-code").queryAs(TextField.class);
		TextField marks = robot.lookup("#legacy-metadata-marks").queryAs(TextField.class);
		Button save = robot.lookup("#legacy-metadata-save").queryAs(Button.class);
		robot.interact(() -> questionCode.clear());
		assertTrue(save.isDisabled());
		robot.interact(() -> {
			questionCode.setText("Q12a");
			marks.setText("0");
		});
		assertTrue(save.isDisabled());
		robot.interact(() -> marks.setText("not a number"));
		assertTrue(save.isDisabled());
		robot.interact(dialogRef.get()::close);
	}

	@Test
	void fieldLabelsRetainReadableWidth(FxRobot robot) {
		AtomicReference<LegacyQuestionMetadataDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			LegacyQuestionMetadataDialog dialog = new LegacyQuestionMetadataDialog(owner, question,
					curriculumRepository);
			dialogRef.set(dialog);
			dialog.show();

			// Complete CSS and layout before comparing allocated and minimum widths.
			dialog.getDialogPane().applyCss();
			dialog.getDialogPane().layout();
		});
		String[] selectors = { "#legacy-metadata-subject-label", "#legacy-metadata-syllabus-label",
				"#legacy-metadata-booklet-label", "#legacy-metadata-question-code-label",
				"#legacy-metadata-marks-label", "#legacy-metadata-classification-label",
				"#legacy-metadata-response-type-label" };
		for (String selector : selectors) {
			Label label = robot.lookup(selector).queryAs(Label.class);

			// Every metadata label keeps at least its preferred readable width,
			// preventing JavaFX from replacing its meaning with an ellipsis.
			assertTrue(label.getWidth() + 0.5 >= label.minWidth(label.getHeight()),
					"Metadata label must remain fully readable: " + label.getText());
		}
		robot.interact(dialogRef.get()::close);
	}

	@Test
	@SuppressWarnings("unchecked")
	void returnsCorrectedMetadata(FxRobot robot) {
		AtomicReference<LegacyQuestionMetadataDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			LegacyQuestionMetadataDialog dialog = new LegacyQuestionMetadataDialog(owner, question,
					curriculumRepository);
			dialogRef.set(dialog);
			dialog.show();
		});
		TextField questionCode = robot.lookup("#legacy-metadata-question-code").queryAs(TextField.class);
		TextField marks = robot.lookup("#legacy-metadata-marks").queryAs(TextField.class);
		ComboBox<CurriculumNode> classification = robot.lookup("#legacy-metadata-classification")
				.queryAs(ComboBox.class);
		ComboBox<QuestionResponseType> responseType = robot.lookup("#legacy-metadata-response-type")
				.queryAs(ComboBox.class);
		CheckBox sharedContext = robot.lookup("#legacy-metadata-shared-context-required").queryAs(CheckBox.class);
		Button save = robot.lookup("#legacy-metadata-save").queryAs(Button.class);
		robot.interact(() -> {
			questionCode.setText("Q12b");
			responseType.setValue(QuestionResponseType.WRITTEN_RESPONSE);
			marks.setText("4");
			classification.setValue(secondSubtopic);
			sharedContext.setSelected(false);
		});
		assertFalse(save.isDisabled());
		robot.interact(save::fire);
		LegacyQuestionMetadataDialog.Result result = dialogRef.get().getResult();
		assertNotNull(result);
		assertEquals("Q12b", result.questionCode());
		assertEquals(4, result.marks());
		assertEquals(secondSubtopic, result.classification());
		assertFalse(result.sharedContextCaptureRequired());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, result.responseType());
	}

	@Test
	@SuppressWarnings("unchecked")
	void showsExistingMetadataAndSameSyllabusClassifications(FxRobot robot) {
		AtomicReference<LegacyQuestionMetadataDialog> dialogRef = new AtomicReference<>();
		robot.interact(() -> {
			LegacyQuestionMetadataDialog dialog = new LegacyQuestionMetadataDialog(owner, question,
					curriculumRepository);
			dialogRef.set(dialog);
			dialog.show();
		});
		TextField questionCode = robot.lookup("#legacy-metadata-question-code").queryAs(TextField.class);
		TextField marks = robot.lookup("#legacy-metadata-marks").queryAs(TextField.class);
		ComboBox<CurriculumNode> classification = robot.lookup("#legacy-metadata-classification")
				.queryAs(ComboBox.class);
		ComboBox<QuestionResponseType> responseType = robot.lookup("#legacy-metadata-response-type")
				.queryAs(ComboBox.class);
		CheckBox sharedContext = robot.lookup("#legacy-metadata-shared-context-required").queryAs(CheckBox.class);
		assertEquals("Q12a", questionCode.getText());
		assertEquals("2", marks.getText());
		assertEquals(firstSubtopic, classification.getValue());
		assertEquals(2, classification.getItems().size());
		assertTrue(classification.getItems().contains(firstSubtopic));
		assertTrue(classification.getItems().contains(secondSubtopic));
		assertTrue(sharedContext.isSelected());
		assertEquals(QuestionResponseType.UNKNOWN, responseType.getValue());
		assertEquals(3, responseType.getItems().size());
		assertTrue(responseType.getItems().contains(QuestionResponseType.MULTIPLE_CHOICE));
		assertTrue(responseType.getItems().contains(QuestionResponseType.WRITTEN_RESPONSE));
		assertTrue(responseType.getItems().contains(QuestionResponseType.UNKNOWN));
		robot.interact(dialogRef.get()::close);
	}

	@Start
	void start(Stage stage) throws Exception {
		owner = stage;
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
		SqliteDatabase database = new SqliteDatabase(
				Files.createTempDirectory("legacy-metadata-dialog-").resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion historical = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(historical, "1", "Unit 1", 0);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 0);
		firstSubtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "First subtopic", 0);
		secondSubtopic = curriculumWriter.insertSubtopic(topic, "1.1.2", "Second subtopic", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		question = new SqliteQuestionRepository(database).save(booklet, "Q12a", "", 2, List.of(), firstSubtopic, true);
		curriculumRepository = new SqliteCurriculumRepository(database);
	}
}
