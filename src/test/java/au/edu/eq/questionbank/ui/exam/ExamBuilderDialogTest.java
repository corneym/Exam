package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.pdf.PrintableQuestionNumbering;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraft;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class ExamBuilderDialogTest {

	private Stage stage;
	private final Subject subject = new Subject(1, "Chemistry");
	@TempDir
	Path tempDirectory;

	@Start
	public void start(Stage stage) {
		this.stage = stage;
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
	}

	@Test
	void addsReordersRemovesAndTransfersQuestions(FxRobot robot) {
		ExamBuilderDraft draft = new ExamBuilderDraft(subject);
		Question first = createQuestion(1, "7", 1, QuestionResponseType.MULTIPLE_CHOICE);
		Question second = createQuestion(2, "8", 1, QuestionResponseType.MULTIPLE_CHOICE);
		Question written = createQuestion(3, "21", 4, QuestionResponseType.WRITTEN_RESPONSE);
		Queue<List<Question>> available = new ArrayDeque<>();
		available.add(List.of(first, second));
		available.add(List.of(written));
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(
				() -> holder.set(new ExamBuilderDialog(stage, draft, (_, _) -> Optional.ofNullable(available.poll()))));
		ExamBuilderDialog dialog = holder.get();
		try {
			robot.interact(dialog::show);
			Button add = robot.lookup("#exam-builder-add").queryButton();
			Button up = robot.lookup("#exam-builder-up").queryButton();
			Button down = robot.lookup("#exam-builder-down").queryButton();
			Button remove = robot.lookup("#exam-builder-remove").queryButton();
			Button transfer = robot.lookup("#exam-builder-transfer").queryButton();
			ComboBox<ExamBuilderDraft.Section> sectionBox = robot.lookup("#exam-builder-section").queryComboBox();
			ListView<PrintableQuestionNumbering.NumberedQuestion> questions = robot.lookup("#exam-builder-questions")
					.queryListView();
			Label total = robot.lookup("#exam-builder-total").queryAs(Label.class);
			var mcqSection = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
			assertTrue(up.isDisabled());
			assertTrue(remove.isDisabled());

			// One chooser confirmation adds both Questions.
			robot.interact(add::fire);
			assertEquals(List.of(first, second), draft.getQuestions(mcqSection));
			assertEquals(2, questions.getItems().size());
			assertFalse(up.isDisabled());
			assertTrue(down.isDisabled());
			robot.interact(up::fire);
			assertEquals(List.of(second, first), draft.getQuestions(mcqSection));
			assertEquals(second.getId(), questions.getItems().getFirst().question().getId());
			assertTrue(up.isDisabled());
			robot.interact(remove::fire);
			assertEquals(List.of(first), draft.getQuestions(mcqSection));
			assertTrue(total.getText().contains("Total: 1 marks"));
			robot.interact(() -> sectionBox.setValue(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN));
			robot.interact(add::fire);
			assertEquals("2", questions.getItems().getFirst().label());
			assertFalse(transfer.isDisabled());
			robot.interact(transfer::fire);
			assertEquals(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, sectionBox.getValue());
			assertTrue(draft.getQuestions(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN).isEmpty());
			assertEquals(List.of(written), draft.getQuestions(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN));
			assertEquals("1", questions.getItems().getFirst().label());
			assertTrue(total.getText().contains("Total: 5 marks"));
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void defaultsBothPerusalTimesToFiveMinutes(FxRobot robot) {
		ExamBuilderDraft draft = new ExamBuilderDraft(subject);
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> {
			ExamBuilderDialog dialog = new ExamBuilderDialog(stage, draft, (_, _) -> Optional.empty());
			dialog.configureGeneration((_, _, _) -> {
			}, tempDirectory);
			holder.set(dialog);
		});
		ExamBuilderDialog dialog = holder.get();
		try {
			robot.interact(dialog::show);
			TextField paperOne = robot.lookup("#exam-builder-paper-one-perusal").queryAs(TextField.class);
			TextField paperTwo = robot.lookup("#exam-builder-paper-two-perusal").queryAs(TextField.class);
			assertEquals("5", paperOne.getText());
			assertEquals("5", paperTwo.getText());
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void generationUsesCurrentSettingsAndPlan(FxRobot robot) throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(subject);
		Question mcq = createQuestion(1, "7", 1, QuestionResponseType.MULTIPLE_CHOICE);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, mcq));
		Path dataRoot = Files.createDirectory(tempDirectory.resolve("data"));
		Path destination = Files.createDirectory(tempDirectory.resolve("generated"));
		AtomicReference<PrintableExamSettings> settingsUsed = new AtomicReference<>();
		AtomicReference<PrintableAssessmentPlan> planUsed = new AtomicReference<>();
		AtomicBoolean generatedOnFxThread = new AtomicBoolean(true);
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> {
			ExamBuilderDialog dialog = new ExamBuilderDialog(stage, draft, (_, _) -> Optional.empty());
			dialog.configureGeneration((settings, plan, output) -> {
				generatedOnFxThread.set(Platform.isFxApplicationThread());
				settingsUsed.set(settings);
				planUsed.set(plan);
				Files.writeString(output.resolve("generated.marker"), "Generation completed");
			}, dataRoot);
			holder.set(dialog);
		});
		ExamBuilderDialog dialog = holder.get();
		try {
			robot.interact(dialog::show);
			TextField year = robot.lookup("#exam-builder-year").queryAs(TextField.class);
			TextField workingTime = robot.lookup("#exam-builder-paper-one-working").queryAs(TextField.class);
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			Button generate = robot.lookup("#exam-builder-generate").queryButton();
			assertFalse(generate.isDisabled());
			robot.interact(() -> {
				year.setText("invalid");
				dialog.startGeneration(destination);
			});
			assertTrue(status.getText().contains("must be a whole number"));
			assertNull(settingsUsed.get());
			robot.interact(() -> {
				year.setText("2027");
				workingTime.setText("60");
				dialog.startGeneration(dataRoot);
			});
			assertTrue(status.getText().contains("outside the managed"));
			assertNull(settingsUsed.get());
			robot.interact(() -> dialog.startGeneration(destination));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> Files.exists(destination.resolve("generated.marker")));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Examination generated:"));
			assertFalse(generatedOnFxThread.get());
			assertEquals("Chemistry", settingsUsed.get().subject());
			assertEquals(2027, settingsUsed.get().year());
			assertEquals(60, settingsUsed.get().paperOneWorkingMinutes());
			assertEquals(1, planUsed.get().getMultipleChoiceCount());
			assertEquals(mcq.getId(), planUsed.get().getMultipleChoice().getFirst().question().getId());
			assertFalse(generate.isDisabled());
		} finally {
			robot.interact(dialog::close);
		}
	}

	private Question createQuestion(long id, String code, int marks, QuestionResponseType responseType) {
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(2, syllabus, unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = new Subtopic(3, syllabus, topic, "3.1.1", "Equilibrium", 1);
		Exam exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2025, "External");
		ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "questions.pdf"));
		Question question = new Question(id, booklet, code, "", marks,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, null, null, responseType);
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
			question.setAnswer(new Answer(id, "C", List.of()));
		} else {
			AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", new SourceDocument(2, "answers.pdf"));
			question.setAnswer(new Answer(id, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2))));
		}
		return question;
	}
}
