package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.pdf.PrintableQuestionNumbering;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraft;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraftFile;
import au.edu.eq.questionbank.ui.model.SqliteMockExamStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
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
	void addsWholeMultipartSelectionFromChooser(FxRobot robot) {
		ExamBuilderDraft draft = new ExamBuilderDraft(subject);
		Question standalone = createQuestion(30, "25", 4, QuestionResponseType.WRITTEN_RESPONSE);
		SourceQuestion source = new SourceQuestion(1, standalone.getBooklet(), "21");
		Question partA = createQuestion(10, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source);
		Question partB = createQuestion(20, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source);
		List<Question> corpus = List.of(partB, standalone, partA);
		Queue<List<Question>> available = new ArrayDeque<>();

		// Simulate a filtered chooser returning only one
		// visible constituent of the multipart Question.
		available.add(List.of(partB));

		// Simulate a subsequent attempt to select another
		// constituent of the already selected Question.
		available.add(List.of(partA));
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> {
			ExamBuilderDialog dialog = new ExamBuilderDialog(stage, draft,
					(_, _) -> Optional.ofNullable(available.poll()), () -> corpus);

			// Configure generation as the production application
			// does, ensuring the status label is in the scene graph.
			dialog.configureGeneration((_, _, _) -> {
				throw new AssertionError("PDF generation is not expected in this test.");
			}, tempDirectory);
			holder.set(dialog);
		});
		ExamBuilderDialog dialog = holder.get();
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		try {
			robot.interact(dialog::show);
			Button add = robot.lookup("#exam-builder-add").queryButton();
			ComboBox<ExamBuilderDraft.Section> sectionBox = robot.lookup("#exam-builder-section").queryComboBox();
			ListView<PrintableQuestionNumbering.NumberedQuestion> questions = robot.lookup("#exam-builder-questions")
					.queryListView();
			Label total = robot.lookup("#exam-builder-total").queryAs(Label.class);
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			robot.interact(() -> sectionBox.setValue(paperOne));
			robot.interact(add::fire);
			assertEquals(List.of(partA, partB), draft.getQuestions(paperOne));
			assertEquals(2, questions.getItems().size());
			assertEquals(List.of("1(a)", "1(b)"),
					questions.getItems().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
			assertEquals("1 selected Questions — Total: 5 marks", total.getText());
			robot.interact(() -> sectionBox.setValue(paperTwo));
			robot.interact(add::fire);
			assertTrue(draft.getQuestions(paperTwo).isEmpty());
			assertTrue(status.getText().contains("unavailable"));
			assertEquals(List.of(partA, partB), draft.getQuestions(paperOne));
			assertEquals(1, draft.getTopLevelSelectedCount());
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
	void finalisedExaminationDisablesEditingControls(FxRobot robot) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("finalised-ui.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculum.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculum.insertUnit(syllabus, "3", "Unit 3", 1);
		Topic topic = curriculum.insertTopic(unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = curriculum.insertSubtopic(topic, "3.1.1", "Equilibrium", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "questions.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		Question saved = questions.save(booklet, "21", "", 4,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answers.findOrCreateAnswerFile(booklet, "Marking guide", "answers.pdf");
		answers.insertAnswer(saved, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2)));
		Question question = questions.findById(saved.getId()).orElseThrow();
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, question, List.of(question)));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2026, 5, 60, 5, 75);
		new SqliteMockExamStore(database).finalise(draft, settings, true);
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> {
			ExamBuilderDialog dialog = new ExamBuilderDialog(stage, draft, (_, _) -> Optional.empty());

			// The metadata fields and generation controls must
			// actually be attached to the dialog under test.
			dialog.configureGeneration((_, _, _) -> {
				throw new AssertionError("PDF generation is not expected in this test.");
			}, tempDirectory);
			holder.set(dialog);
		});
		ExamBuilderDialog dialog = holder.get();
		try {
			robot.interact(dialog::show);
			ComboBox<ExamBuilderDraft.Section> sectionBox = robot.lookup("#exam-builder-section").queryComboBox();
			ListView<PrintableQuestionNumbering.NumberedQuestion> questionList = robot.lookup("#exam-builder-questions")
					.queryListView();
			robot.interact(() -> {
				sectionBox.setValue(section);
				questionList.getSelectionModel().selectFirst();
			});
			assertEquals(1, questionList.getItems().size());
			assertTrue(dialog.getHeaderText().contains("FINALISED"));
			for (String id : List.of("#exam-builder-add", "#exam-builder-up", "#exam-builder-down",
					"#exam-builder-remove", "#exam-builder-transfer")) {
				assertTrue(robot.lookup(id).queryButton().isDisabled(), id);
			}
			for (String id : List.of("#exam-builder-year", "#exam-builder-paper-one-perusal",
					"#exam-builder-paper-one-working", "#exam-builder-paper-two-perusal",
					"#exam-builder-paper-two-working")) {
				TextField field = robot.lookup(id).queryAs(TextField.class);
				assertFalse(field.isEditable(), id);
			}
			assertFalse(sectionBox.isDisabled());
			assertFalse(robot.lookup("#exam-builder-generate").queryButton().isDisabled());
			assertEquals(List.of(question.getId()), draft.getQuestions(section).stream().map(Question::getId).toList());
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

	@Test
	void managedChooserRevalidatesReservationsCreatedWhileOpen(FxRobot robot) throws Exception {
		ManagedExamFixture fixture = createManagedExamFixture();
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		List<Question> corpus = List.of(fixture.first(), fixture.second());
		ExamBuilderDialog dialog = createManagedExamDialog(robot, fixture, draft, true,
				(_, destination, year, locked) -> {
					assertTrue(locked.isEmpty());

					// Simulate a reservation committed by another
					// examination after the selection snapshot was taken.
					ExamBuilderDraft competing = new ExamBuilderDraft(fixture.subject());
					assertTrue(competing.addWholeQuestion(destination, fixture.first(), corpus));
					try {
						fixture.store().finalise(competing,
								new PrintableExamSettings(fixture.subject().getName(), year, 5, 90, 5, 90), true);
					} catch (SQLException failure) {
						throw new IllegalStateException(failure);
					}

					// Deliberately return one eligible Question and one
					// newly reserved Question. Neither should be added.
					return Optional.of(List.of(fixture.second(), fixture.first()));
				});
		try {
			robot.interact(dialog::show);
			Button add = robot.lookup("#exam-builder-add").queryButton();
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			robot.interact(add::fire);
			assertTrue(status.getText().contains("already reserved"));
			assertTrue(draft.getQuestions(section).isEmpty());
			assertEquals(Set.of(fixture.first().getId()), fixture.store().lockedQuestionIds(fixture.subject(), 2026));
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void managedExaminationLifecycleAndAnnualReset(FxRobot robot) throws Exception {
		ManagedExamFixture fixture = createManagedExamFixture();
		SqliteMockExamStore store = fixture.store();
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		List<Question> _ = List.of(fixture.first(), fixture.second());
		var section = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		AtomicReference<Question> candidate = new AtomicReference<>(fixture.first());
		AtomicReference<Set<Long>> observedLocks = new AtomicReference<>(Set.of());
		AtomicInteger observedYear = new AtomicInteger();
		ExamBuilderDialog dialog = createManagedExamDialog(robot, fixture, draft, true, (_, _, year, locked) -> {
			observedYear.set(year);
			observedLocks.set(locked);
			return Optional.of(List.of(candidate.get()));
		});
		try {
			robot.interact(dialog::show);
			Button add = robot.lookup("#exam-builder-add").queryButton();
			Button save = robot.lookup("#exam-builder-save-managed").queryButton();
			Button open = robot.lookup("#exam-builder-open-managed").queryButton();
			Button newExam = robot.lookup("#exam-builder-new").queryButton();
			Button finalise = robot.lookup("#exam-builder-finalise").queryButton();
			Button reset = robot.lookup("#exam-builder-reset-year").queryButton();
			Button delete = robot.lookup("#exam-builder-delete-managed").queryButton();
			TextField year = robot.lookup("#exam-builder-year").queryAs(TextField.class);
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			assertEquals("2026", year.getText());
			assertTrue(finalise.isDisabled());
			robot.interact(add::fire);
			assertEquals(2026, observedYear.get());
			assertTrue(observedLocks.get().isEmpty());
			assertEquals(List.of(fixture.first().getId()),
					draft.getQuestions(section).stream().map(Question::getId).toList());
			UUID firstExamId = draft.getExaminationId();
			robot.interact(save::fire);
			assertTrue(store.find(firstExamId, fixture.subject()).isPresent());
			assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());
			assertFalse(finalise.isDisabled());

			// Cancelling finalisation must not create reservations.
			Platform.runLater(finalise::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.CANCEL_CLOSE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> robot.lookup("#exam-builder-managed-confirmation").tryQuery().isEmpty());
			assertFalse(draft.isFinalised());
			assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());

			// Explicit finalisation creates the annual reservation.
			Platform.runLater(finalise::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> status.getText().startsWith("Examination finalised"));
			assertTrue(draft.isFinalised());
			assertTrue(add.isDisabled());
			assertTrue(save.isDisabled());
			assertTrue(finalise.isDisabled());
			assertFalse(year.isEditable());
			assertEquals(Set.of(fixture.first().getId()), store.lockedQuestionIds(fixture.subject(), 2026));

			// A new examination must have a different persistent identity.
			robot.interact(newExam::fire);
			UUID secondExamId = draft.getExaminationId();
			assertNotEquals(firstExamId, secondExamId);
			assertFalse(draft.isFinalised());
			assertTrue(draft.isEmpty());
			assertEquals("2026", year.getText());
			assertTrue(year.isEditable());

			// The deliberately bypassed chooser returns a locked
			// Question. The owning dialog must independently reject it.
			robot.interact(add::fire);
			assertEquals(Set.of(fixture.first().getId()), observedLocks.get());
			assertTrue(draft.isEmpty());
			assertTrue(status.getText().contains("already reserved"));

			// Selecting an unrelated eligible Question is allowed.
			candidate.set(fixture.second());
			robot.interact(add::fire);
			robot.interact(save::fire);
			assertTrue(store.find(secondExamId, fixture.subject()).isPresent());
			assertEquals(Set.of(fixture.first().getId()), store.lockedQuestionIds(fixture.subject(), 2026));

			// Open the first, finalised examination from managed storage.
			robot.interact(newExam::fire);
			Platform.runLater(open::fire);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> robot.lookup("#exam-builder-managed-choice").tryQuery().isPresent());
			DialogPane chooser = robot.lookup("#exam-builder-managed-choice").queryAs(DialogPane.class);

			// Both examinations have the same year. The summary order
			// presents the finalised examination before the editable draft.
			answerManagedDialog(robot, chooser.getId(), ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Finalised examination opened"));
			assertEquals(firstExamId, draft.getExaminationId());
			assertTrue(draft.isFinalised());
			assertTrue(add.isDisabled());

			// Cancelling an annual reset must retain the reservations.
			Platform.runLater(reset::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.CANCEL_CLOSE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> robot.lookup("#exam-builder-managed-confirmation").tryQuery().isEmpty());
			assertEquals(Set.of(fixture.first().getId()), store.lockedQuestionIds(fixture.subject(), 2026));
			Platform.runLater(reset::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Annual reservations released"));
			assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());

			// Reset releases usage, not the committed examination.
			assertTrue(draft.isFinalised());
			assertTrue(store.find(firstExamId, fixture.subject()).orElseThrow().draft().isFinalised());
			assertTrue(add.isDisabled());
			robot.interact(newExam::fire);
			candidate.set(fixture.first());
			robot.interact(add::fire);
			assertTrue(observedLocks.get().isEmpty());
			assertEquals(1, draft.getTotalSelectedCount());

			// Save the newly reusable Question as a third draft.
			robot.interact(save::fire);
			UUID thirdExamId = draft.getExaminationId();

			// The deletion chooser defaults to the current saved draft.
			Platform.runLater(delete::fire);
			answerManagedDialog(robot, "exam-builder-managed-choice", ButtonBar.ButtonData.OK_DONE);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> status.getText().startsWith("Saved draft deleted"));
			assertTrue(store.find(thirdExamId, fixture.subject()).isEmpty());
			assertTrue(draft.isEmpty());
			assertNotEquals(thirdExamId, draft.getExaminationId());
			assertTrue(store.find(firstExamId, fixture.subject()).isPresent());
			assertTrue(store.find(secondExamId, fixture.subject()).isPresent());
			assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());
			fixture.database().verifyIntegrity();
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void managedFinalisationRejectsConflictingOlderDraft(FxRobot robot) throws Exception {
		ManagedExamFixture fixture = createManagedExamFixture();
		SqliteMockExamStore store = fixture.store();
		var section = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		List<Question> corpus = List.of(fixture.first(), fixture.second());
		PrintableExamSettings settings = new PrintableExamSettings(fixture.subject().getName(), 2026, 5, 90, 5, 90);
		ExamBuilderDraft older = new ExamBuilderDraft(fixture.subject());
		assertTrue(older.addSelections(section, corpus, corpus));
		store.saveDraft(older, settings, true);
		ExamBuilderDraft committed = new ExamBuilderDraft(fixture.subject());
		assertTrue(committed.addWholeQuestion(section, fixture.first(), corpus));
		store.finalise(committed, settings, true);
		ExamBuilderDialog dialog = createManagedExamDialog(robot, fixture, older, true,
				(_, _, _, _) -> Optional.empty());
		try {
			robot.interact(dialog::show);
			Button finalise = robot.lookup("#exam-builder-finalise").queryButton();
			Button remove = robot.lookup("#exam-builder-remove").queryButton();
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			ListView<PrintableQuestionNumbering.NumberedQuestion> questions = robot.lookup("#exam-builder-questions")
					.queryListView();
			Platform.runLater(finalise::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Cannot finalise examination:"));
			assertTrue(status.getText().contains("already reserved"));
			assertFalse(older.isFinalised());
			assertEquals(2, older.getTotalSelectedCount());
			assertFalse(finalise.isDisabled());
			assertEquals(Set.of(fixture.first().getId()), store.lockedQuestionIds(fixture.subject(), 2026));

			// Remove the conflicting Question through the actual
			// examination editing controls.
			robot.interact(() -> questions.getSelectionModel().selectFirst());
			assertFalse(remove.isDisabled());
			robot.interact(remove::fire);
			assertEquals(List.of(fixture.second().getId()),
					older.getQuestions(section).stream().map(Question::getId).toList());
			Platform.runLater(finalise::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> status.getText().startsWith("Examination finalised"));
			assertTrue(older.isFinalised());
			assertEquals(Set.of(fixture.first().getId(), fixture.second().getId()),
					store.lockedQuestionIds(fixture.subject(), 2026));
			assertTrue(remove.isDisabled());
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void managedPaperTwoInclusionSurvivesSaveOpenAndFinalisation(FxRobot robot) throws Exception {
		ManagedExamFixture fixture = createManagedExamFixture();
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		assertTrue(draft.add(section, fixture.first()));
		UUID examinationId = draft.getExaminationId();
		ExamBuilderDraftFile files = new ExamBuilderDraftFile(new SqlitePortableIdentityRepository(fixture.database()),
				new SqliteQuestionRepository(fixture.database()), fixture.store());
		PrintableExamSettings settings = new PrintableExamSettings(fixture.subject().getName(), 2026, 5, 90, 5, 90);

		// Simulate an external file saved before Paper 2 was excluded.
		Path staleFile = tempDirectory.resolve("before-exclusion.exam.json");
		files.save(staleFile, draft, settings, true);
		ExamBuilderDialog dialog = createManagedExamDialog(robot, fixture, draft, true,
				(_, _, _, _) -> Optional.empty());
		try {
			robot.interact(dialog::show);
			CheckBox includePaperTwo = robot.lookup("#exam-builder-include-paper-two").queryAs(CheckBox.class);
			ComboBox<ExamBuilderDraft.Section> sections = robot.lookup("#exam-builder-section").queryComboBox();
			Button save = robot.lookup("#exam-builder-save-managed").queryButton();
			Button open = robot.lookup("#exam-builder-open-managed").queryButton();
			Button newExam = robot.lookup("#exam-builder-new").queryButton();
			Button finalise = robot.lookup("#exam-builder-finalise").queryButton();
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			assertTrue(includePaperTwo.isSelected());
			assertTrue(sections.getItems().contains(paperTwo));
			robot.interact(includePaperTwo::fire);
			assertFalse(includePaperTwo.isSelected());
			assertFalse(sections.getItems().contains(paperTwo));
			robot.interact(save::fire);
			var saved = fixture.store().find(examinationId, fixture.subject()).orElseThrow();
			assertFalse(saved.includePaperTwo());
			assertFalse(saved.draft().isFinalised());
			assertTrue(fixture.store().lockedQuestionIds(fixture.subject(), 2026).isEmpty());

			// A new examination defaults to Paper 2 included.
			robot.interact(newExam::fire);
			assertNotEquals(examinationId, draft.getExaminationId());
			assertTrue(includePaperTwo.isSelected());
			assertTrue(sections.getItems().contains(paperTwo));

			// Reopen the saved Paper 1-only draft.
			Platform.runLater(open::fire);
			answerManagedDialog(robot, "exam-builder-managed-choice", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Saved examination draft opened"));
			assertEquals(examinationId, draft.getExaminationId());
			assertFalse(includePaperTwo.isSelected());
			assertFalse(sections.getItems().contains(paperTwo));
			assertFalse(includePaperTwo.isDisabled());
			Platform.runLater(finalise::fire);
			answerManagedDialog(robot, "exam-builder-managed-confirmation", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> status.getText().startsWith("Examination finalised"));
			assertTrue(draft.isFinalised());
			assertFalse(includePaperTwo.isSelected());
			assertTrue(includePaperTwo.isDisabled());
			assertFalse(sections.getItems().contains(paperTwo));
			var committed = fixture.store().find(examinationId, fixture.subject()).orElseThrow();
			assertFalse(committed.includePaperTwo());
			assertTrue(committed.draft().isFinalised());
			assertEquals(Set.of(fixture.first().getId()), fixture.store().lockedQuestionIds(fixture.subject(), 2026));

			// The managed finalised definition must override
			// the older external file's Paper 2 setting.
			var reopenedStaleFile = files.load(staleFile, fixture.subject());
			assertTrue(reopenedStaleFile.draft().isFinalised());
			assertFalse(reopenedStaleFile.includePaperTwo());
			assertEquals(examinationId, reopenedStaleFile.draft().getExaminationId());

			// Reopen the committed Paper 1-only examination.
			robot.interact(newExam::fire);
			assertTrue(includePaperTwo.isSelected());
			Platform.runLater(open::fire);
			answerManagedDialog(robot, "exam-builder-managed-choice", ButtonBar.ButtonData.OK_DONE);
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Finalised examination opened"));
			assertEquals(examinationId, draft.getExaminationId());
			assertTrue(draft.isFinalised());
			assertFalse(includePaperTwo.isSelected());
			assertTrue(includePaperTwo.isDisabled());
			assertFalse(sections.getItems().contains(paperTwo));
			fixture.database().verifyIntegrity();
		} finally {
			robot.interact(() -> {

				// Do not open an unrelated discard confirmation
				// during cleanup after a failed assertion.
				dialog.setOnCloseRequest(null);
				dialog.close();
			});
		}
	}

	@Test
	void movesTransfersAndRemovesEntireMultipartQuestion(FxRobot robot) {
		ExamBuilderDraft draft = new ExamBuilderDraft(subject);
		Question standalone = createQuestion(30, "25", 4, QuestionResponseType.WRITTEN_RESPONSE);
		SourceQuestion source = new SourceQuestion(1, standalone.getBooklet(), "21");
		Question partA = createQuestion(10, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source);
		Question partB = createQuestion(20, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source);
		List<Question> corpus = List.of(partA, partB, standalone);
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		assertTrue(draft.addWholeQuestion(paperOne, partA, corpus));
		assertTrue(draft.addWholeQuestion(paperOne, standalone, corpus));
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> holder.set(new ExamBuilderDialog(stage, draft, (_, _) -> Optional.empty())));
		ExamBuilderDialog dialog = holder.get();
		try {
			robot.interact(dialog::show);
			Button up = robot.lookup("#exam-builder-up").queryButton();
			Button down = robot.lookup("#exam-builder-down").queryButton();
			Button remove = robot.lookup("#exam-builder-remove").queryButton();
			Button transfer = robot.lookup("#exam-builder-transfer").queryButton();
			ComboBox<ExamBuilderDraft.Section> sectionBox = robot.lookup("#exam-builder-section").queryComboBox();
			ListView<PrintableQuestionNumbering.NumberedQuestion> questions = robot.lookup("#exam-builder-questions")
					.queryListView();
			robot.interact(() -> {
				sectionBox.setValue(paperOne);
				questions.getSelectionModel().select(1);
			});

			// The second constituent part is selected,
			// but the entire Question is at the top.
			assertTrue(up.isDisabled());
			assertFalse(down.isDisabled());
			robot.interact(down::fire);
			assertEquals(List.of(standalone, partA, partB), draft.getQuestions(paperOne));
			robot.interact(() -> questions.getSelectionModel().select(1));

			// The first constituent part is selected,
			// but the entire Question is at the bottom.
			assertTrue(down.isDisabled());
			assertFalse(up.isDisabled());
			robot.interact(up::fire);
			assertEquals(List.of(partA, partB, standalone), draft.getQuestions(paperOne));
			robot.interact(() -> questions.getSelectionModel().select(1));
			robot.interact(transfer::fire);
			assertEquals(paperTwo, sectionBox.getValue());
			assertEquals(List.of(standalone), draft.getQuestions(paperOne));
			assertEquals(List.of(partA, partB), draft.getQuestions(paperTwo));
			assertTrue(up.isDisabled());
			assertTrue(down.isDisabled());
			assertEquals(List.of("1(a)", "1(b)"),
					questions.getItems().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
			robot.interact(remove::fire);
			assertTrue(draft.getQuestions(paperTwo).isEmpty());
			assertTrue(questions.getItems().isEmpty());
			assertTrue(remove.isDisabled());
			assertTrue(transfer.isDisabled());
			assertEquals(List.of(partA, partB), draft.eligibleWholeQuestion(paperOne, partA, corpus));
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void nonAuthoritativeSubjectDisablesManagedWrites(FxRobot robot) throws Exception {
		ManagedExamFixture fixture = createManagedExamFixture();
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		ExamBuilderDialog dialog = createManagedExamDialog(robot, fixture, draft, false,
				(_, _, _, _) -> Optional.empty());
		try {
			robot.interact(dialog::show);
			for (String id : List.of("#exam-builder-save-managed", "#exam-builder-delete-managed",
					"#exam-builder-finalise", "#exam-builder-reset-year")) {
				assertTrue(robot.lookup(id).queryButton().isDisabled(), id);
			}

			// Independent portable drafting and managed inspection
			// remain available for imported Subject snapshots.
			assertFalse(robot.lookup("#exam-builder-add").queryButton().isDisabled());
			assertFalse(robot.lookup("#exam-builder-new").queryButton().isDisabled());
			assertFalse(robot.lookup("#exam-builder-open-managed").queryButton().isDisabled());
			assertFalse(robot.lookup("#exam-builder-save-draft").queryButton().isDisabled());
		} finally {
			robot.interact(dialog::close);
		}
	}

	@Test
	void optionalPaperTwoProtectsSelectionsAndControlsGeneration(FxRobot robot) throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(subject);
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		Question first = createQuestion(1, "21", 4, QuestionResponseType.WRITTEN_RESPONSE);
		Question second = createQuestion(2, "22", 3, QuestionResponseType.WRITTEN_RESPONSE);
		assertTrue(draft.add(paperOne, first));
		assertTrue(draft.add(paperTwo, second));
		Path dataRoot = Files.createDirectory(tempDirectory.resolve("data"));
		Path destination = Files.createDirectory(tempDirectory.resolve("generated"));
		AtomicReference<PrintableExamSettings> settingsUsed = new AtomicReference<>();
		AtomicReference<PrintableAssessmentPlan> planUsed = new AtomicReference<>();
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> {
			ExamBuilderDialog dialog = new ExamBuilderDialog(stage, draft, (_, _) -> Optional.empty());
			dialog.configureGeneration((settings, plan, output) -> {
				settingsUsed.set(settings);
				planUsed.set(plan);
				Files.writeString(output.resolve("generated.marker"), "Generation completed.");
			}, dataRoot);
			holder.set(dialog);
		});
		ExamBuilderDialog dialog = holder.get();
		try {
			robot.interact(dialog::show);
			CheckBox includePaperTwo = robot.lookup("#exam-builder-include-paper-two").queryAs(CheckBox.class);
			ComboBox<ExamBuilderDraft.Section> sections = robot.lookup("#exam-builder-section").queryComboBox();
			ListView<PrintableQuestionNumbering.NumberedQuestion> questions = robot.lookup("#exam-builder-questions")
					.queryListView();
			Button transfer = robot.lookup("#exam-builder-transfer").queryButton();
			TextField perusal = robot.lookup("#exam-builder-paper-two-perusal").queryAs(TextField.class);
			TextField working = robot.lookup("#exam-builder-paper-two-working").queryAs(TextField.class);
			Label paperTwoLabel = robot.lookup("#exam-builder-paper-two-label").queryAs(Label.class);
			Label summary = robot.lookup("#exam-builder-summary").queryAs(Label.class);
			Label total = robot.lookup("#exam-builder-total").queryAs(Label.class);
			Label status = robot.lookup("#exam-builder-status").queryAs(Label.class);
			assertTrue(includePaperTwo.isSelected());
			assertTrue(sections.getItems().contains(paperTwo));
			assertTrue(perusal.isVisible());
			assertTrue(working.isVisible());

			// Attempt to exclude a populated Paper 2.
			robot.interact(includePaperTwo::fire);
			assertTrue(includePaperTwo.isSelected());
			assertTrue(status.getText().contains("Cannot exclude Paper 2"));
			assertEquals(List.of(first), draft.getQuestions(paperOne));
			assertEquals(List.of(second), draft.getQuestions(paperTwo));
			assertEquals(2, draft.getTopLevelSelectedCount());

			// Transfer the Question using the actual UI action.
			robot.interact(() -> {
				sections.setValue(paperTwo);
				questions.getSelectionModel().selectFirst();
			});
			assertFalse(transfer.isDisabled());
			robot.interact(transfer::fire);
			assertEquals(List.of(first, second), draft.getQuestions(paperOne));
			assertTrue(draft.getQuestions(paperTwo).isEmpty());

			// Select the now-empty Paper 2 and enter invalid times.
			// Once excluded, those hidden values must not prevent
			// Paper 1-only generation.
			robot.interact(() -> {
				sections.setValue(paperTwo);
				perusal.setText("-5");
				working.setText("invalid");
			});
			robot.interact(includePaperTwo::fire);
			assertFalse(includePaperTwo.isSelected());
			assertEquals(List.of(ExamBuilderDraft.Section.MULTIPLE_CHOICE, paperOne), List.copyOf(sections.getItems()));
			assertEquals(paperOne, sections.getValue());
			assertFalse(paperTwoLabel.isVisible());
			assertFalse(paperTwoLabel.isManaged());
			assertFalse(perusal.isVisible());
			assertFalse(perusal.isManaged());
			assertFalse(working.isVisible());
			assertFalse(working.isManaged());
			assertFalse(transfer.isVisible());
			assertFalse(transfer.isManaged());
			assertFalse(summary.getText().contains("Paper 2"));
			assertEquals("2 selected Questions — Total: 7 marks", total.getText());

			// The existing generation callback must receive
			// all Paper 1 Questions and no Paper 2 Questions.
			robot.interact(() -> dialog.startGeneration(destination));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> Files.exists(destination.resolve("generated.marker")));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> status.getText().startsWith("Examination generated:"));
			assertEquals(2, planUsed.get().getPaperOneQuestionCount());
			assertEquals(7, planUsed.get().getPaperOneTotalMarks());
			assertTrue(planUsed.get().getPaperTwo().isEmpty());
			assertEquals(5, settingsUsed.get().paperTwoPerusalMinutes());
			assertEquals(90, settingsUsed.get().paperTwoWorkingMinutes());

			// Re-enabling restores Paper 2 controls without
			// moving any Questions back into the empty paper.
			robot.interact(includePaperTwo::fire);
			assertTrue(includePaperTwo.isSelected());
			assertTrue(sections.getItems().contains(paperTwo));
			assertTrue(perusal.isVisible());
			assertTrue(working.isVisible());
			assertTrue(draft.getQuestions(paperTwo).isEmpty());
			assertEquals(List.of(first, second), draft.getQuestions(paperOne));
			assertEquals(2, draft.getTopLevelSelectedCount());
		} finally {
			robot.interact(dialog::close);
		}
	}

	private void answerManagedDialog(FxRobot robot, String dialogId, ButtonBar.ButtonData buttonData)
			throws TimeoutException {
		String selector = "#" + dialogId;
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot.lookup(selector).tryQuery().isPresent());
		DialogPane pane = robot.lookup(selector).queryAs(DialogPane.class);
		ButtonType buttonType = pane.getButtonTypes().stream().filter(type -> type.getButtonData() == buttonData)
				.findFirst().orElseThrow();
		ButtonBase button = (ButtonBase) pane.lookupButton(buttonType);
		assertFalse(button.isDisabled());

		// The choice or confirmation may resume an outer modal workflow.
		// Schedule semantic activation rather than blocking the test thread.
		Platform.runLater(button::fire);
	}

	private ExamBuilderDialog createManagedExamDialog(FxRobot robot, ManagedExamFixture fixture, ExamBuilderDraft draft,
			boolean locallyAuthoritative, ExamBuilderDialog.AnnualQuestionSelector selector) {
		SqliteQuestionRepository questions = new SqliteQuestionRepository(fixture.database());
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(fixture.database());
		AtomicReference<ExamBuilderDialog> holder = new AtomicReference<>();
		robot.interact(() -> {
			ExamBuilderDialog dialog = new ExamBuilderDialog(stage, draft, (_, _) -> Optional.empty(),
					questions::findAll);
			dialog.configureGeneration((_, _, _) -> {
				throw new AssertionError("PDF generation is not expected.");
			}, tempDirectory);

			// Align the fixture metadata with the controlled clock
			// before the portable draft dirty listeners are installed.
			TextField year = (TextField) dialog.getDialogPane().getContent().lookup("#exam-builder-year");
			year.setText(Integer.toString(fixture.store().currentYear()));
			dialog.configureDraftFiles(new ExamBuilderDraftFile(identities, questions, fixture.store()));
			dialog.configureManagedExams(fixture.store(), locallyAuthoritative, selector);
			holder.set(dialog);
		});
		return holder.get();
	}

	private ManagedExamFixture createManagedExamFixture() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("managed-exam.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculum.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculum.insertUnit(syllabus, "3", "Unit 3", 1);
		Topic topic = curriculum.insertTopic(unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = curriculum.insertSubtopic(topic, "3.1.1", "Equilibrium", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "questions.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		Question first = questions.save(booklet, "1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		Question second = questions.save(booklet, "2", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.4, 0.8, 0.2)), subtopic, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, examWriter);
		answers.insertAnswer(first, "A", List.of());
		answers.insertAnswer(second, "B", List.of());
		Question firstQuestion = questions.findById(first.getId()).orElseThrow();
		Question secondQuestion = questions.findById(second.getId()).orElseThrow();
		Clock clock = Clock.fixed(Instant.parse("2026-11-28T01:00:00Z"), ZoneId.of("Australia/Brisbane"));
		return new ManagedExamFixture(database, chemistry, new SqliteMockExamStore(database, clock), firstQuestion,
				secondQuestion);
	}

	private Question createQuestion(long id, String code, int marks, QuestionResponseType responseType) {
		return createQuestion(id, code, marks, responseType, null);
	}

	private Question createQuestion(long id, String code, int marks, QuestionResponseType responseType,
			SourceQuestion sourceQuestion) {
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(2, syllabus, unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = new Subtopic(3, syllabus, topic, "3.1.1", "Equilibrium", 1);
		Exam exam = new Exam(1, subject, new ExamProvider(1, "QCAA"), 2025, "External");
		ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "questions.pdf"));
		Question question = new Question(id, booklet, code, "", marks,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, sourceQuestion, null,
				responseType);
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
			question.setAnswer(new Answer(id, "C", List.of()));
		} else {
			AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", new SourceDocument(2, "answers.pdf"));
			question.setAnswer(new Answer(id, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2))));
		}
		return question;
	}

	private record ManagedExamFixture(SqliteDatabase database, Subject subject, SqliteMockExamStore store,
			Question first, Question second) {
	}
}
