package au.edu.eq.questionbank.ui.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.audit.BookletCorpusFinding;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusFinding;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.McqExplanationCoverage;
import au.edu.eq.questionbank.service.audit.McqExplanationSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CorpusDashboardPaneTest {

	private CorpusDashboardPane pane;
	private Fixture fixture;

	@Test
	@SuppressWarnings("unchecked")
	void bookletSelectionScopesQuestionWorkAndShowsCoverage(FxRobot robot) {
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);
		Label coverage = robot.lookup("#corpus-dashboard-mcq-coverage").queryAs(Label.class);

		robot.interact(() -> booklets.getSelectionModel().select(fixture.paper1Status));

		// Paper 1 has no ordinary incomplete Question work in the representative
		// Question snapshot.
		assertEquals(0, questions.getItems().size());
		assertTrue(coverage.isVisible());
		assertTrue(coverage.getText().contains("1 / 2 eligible Questions"));

		robot.interact(() -> booklets.getSelectionModel().select(fixture.paper2Status));

		assertEquals(3, questions.getItems().size());
		assertTrue(questions.getItems().stream()
				.allMatch(item -> item.question().getBooklet().getId() == fixture.paper2.getId()));

		Label warning = robot.lookup("#corpus-dashboard-booklet-warning").queryAs(Label.class);
		assertTrue(warning.isVisible());
		assertTrue(warning.getText().contains("Expected 30 top-level Questions"));
		assertTrue(warning.getText().contains("encountered 29"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void displaysApprovedExamAndBookletHierarchy(FxRobot robot) {
		Label workingSubject = robot.lookup("#corpus-dashboard-working-subject").queryAs(Label.class);
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		Label selectedExam = robot.lookup("#corpus-dashboard-selected-exam").queryAs(Label.class);
		Label selectedState = robot.lookup("#corpus-dashboard-selected-exam-state").queryAs(Label.class);
		Label selectedCounts = robot.lookup("#corpus-dashboard-selected-exam-counts").queryAs(Label.class);
		Label selectedBooklet = robot.lookup("#corpus-dashboard-selected-booklet").queryAs(Label.class);

		assertEquals("Chemistry", workingSubject.getText());
		assertEquals(2, exams.getItems().size());

		// First visible Exam is selected automatically, while booklet scope initially
		// remains the whole Exam.
		assertEquals(fixture.completeExamStatus, exams.getSelectionModel().getSelectedItem());
		assertTrue(selectedExam.getText().contains("QCAA 2025"));
		assertEquals("Declared state: COMPLETE", selectedState.getText());
		assertTrue(selectedCounts.getText().contains("Question booklets: 2 / 2"));
		assertTrue(selectedCounts.getText().contains("Answer booklets: 1 / 1"));
		assertTrue(selectedCounts.getText().contains("Questions: 54"));
		assertTrue(selectedCounts.getText().contains("Need work: 3"));

		assertEquals(2, booklets.getItems().size());
		assertNull(booklets.getSelectionModel().getSelectedItem());
		assertEquals("Selected booklet: All booklets", selectedBooklet.getText());
	}

	@Test
	@SuppressWarnings("unchecked")
	void examStateFilterChangesSummaryExamAndQuestionScope(FxRobot robot) {
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);
		ComboBox<ExamCaptureState> state = robot.lookup("#corpus-dashboard-filter-exam-state").queryAs(ComboBox.class);

		robot.interact(() -> state.setValue(ExamCaptureState.ACTIVE));

		assertEquals(1, exams.getItems().size());
		assertEquals(fixture.activeExamStatus, exams.getSelectionModel().getSelectedItem());
		assertEquals(1, booklets.getItems().size());

		Label selectedState = robot.lookup("#corpus-dashboard-selected-exam-state").queryAs(Label.class);
		assertEquals("Declared state: ACTIVE", selectedState.getText());

		// The representative ACTIVE Question is complete, so the default Needs
		// attention view contains no Question work.
		assertEquals(0, questions.getItems().size());

		Button total = robot.lookup("#corpus-dashboard-summary-total").queryButton();
		Button attention = robot.lookup("#corpus-dashboard-summary-attention").queryButton();
		assertEquals("1 Questions", total.getText());
		assertEquals("0 Need attention", attention.getText());
	}

	@Test
	void refreshIsDisabledUntilOwningWorkflowSuppliesReloadHandler(FxRobot robot) {
		Button refresh = robot.lookup("#corpus-dashboard-refresh").queryButton();

		assertTrue(refresh.isDisable());

		robot.interact(() -> pane.setRefreshHandler(() -> {
			// This test verifies only explicit installation of the persistence reload
			// responsibility.
		}));

		assertFalse(refresh.isDisable());
	}

	@Test
	void selectsVisibleUnknownQuestionsForBulkResponseTypeResolution(FxRobot robot) {
		AtomicReference<List<Question>> selectedQuestions = new AtomicReference<>();
		AtomicReference<QuestionResponseType> selectedResponseType = new AtomicReference<>();

		robot.interact(() -> pane.setBulkResponseTypeHandler((questions, responseType) -> {
			selectedQuestions.set(List.copyOf(questions));
			selectedResponseType.set(responseType);
		}));

		Button selectUnknown = robot.lookup("#corpus-dashboard-select-all-unknown").queryButton();
		Button writtenResponse = robot.lookup("#corpus-dashboard-set-written-response").queryButton();

		robot.interact(selectUnknown::fire);
		assertFalse(writtenResponse.isDisable());

		robot.interact(writtenResponse::fire);

		assertEquals(1, selectedQuestions.get().size());
		assertEquals("Q18a", selectedQuestions.get().getFirst().getQuestionCode());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, selectedResponseType.get());
	}

	@Start
	void start(Stage stage) {
		fixture = new Fixture();

		pane = new CorpusDashboardPane(fixture.chemistry, List.of(fixture.completeExamStatus, fixture.activeExamStatus),
				fixture.questions());

		stage.setScene(new Scene(pane, 1100, 850));
		stage.show();
	}

	@Test
	@SuppressWarnings("unchecked")
	void summaryButtonsFilterQuestionWorkWithoutChangingExamSelection(FxRobot robot) {
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);

		Button total = robot.lookup("#corpus-dashboard-summary-total").queryButton();
		Button attention = robot.lookup("#corpus-dashboard-summary-attention").queryButton();
		Button missingAnswer = robot.lookup("#corpus-dashboard-summary-missing-answer").queryButton();

		assertEquals("5 Questions", total.getText());
		assertEquals("3 Need attention", attention.getText());
		assertEquals("2 Missing answers", missingAnswer.getText());

		// Default Question view is Needs attention for the selected 2025 Exam.
		assertEquals(3, questions.getItems().size());
		assertEquals(fixture.completeExamStatus, exams.getSelectionModel().getSelectedItem());

		robot.interact(missingAnswer::fire);
		assertEquals(2, questions.getItems().size());
		assertTrue(questions.getItems().stream().allMatch(item -> item.status()
				.hasProblem(au.edu.eq.questionbank.service.audit.QuestionCorpusProblem.MISSING_ANSWER)));

		// Clicking the Subject-scope total changes only the Question-work filter. It
		// deliberately leaves the selected Exam unchanged.
		robot.interact(total::fire);
		assertEquals(4, questions.getItems().size());
		assertEquals(fixture.completeExamStatus, exams.getSelectionModel().getSelectedItem());
	}

	private static final class Fixture {

		private final Subject chemistry = new Subject(1, "Chemistry");
		private final ExamProvider qcaa = new ExamProvider(2, "QCAA");
		private final Descriptor classification = classification(chemistry, 100);

		private final Exam completeExam = new Exam(10, chemistry, qcaa, 2025, "External Assessment",
				ExamCaptureState.COMPLETE);
		private final ExamBooklet paper1 = new ExamBooklet(20, completeExam, "Paper 1",
				new SourceDocument(21, "Chemistry/2025/paper1.pdf"), ExamBookletQuestionFormat.MULTIPLE_CHOICE, 20);
		private final ExamBooklet paper2 = new ExamBooklet(22, completeExam, "Paper 2",
				new SourceDocument(23, "Chemistry/2025/paper2.pdf"), ExamBookletQuestionFormat.WRITTEN_RESPONSE, 30);
		private final AnswerFile markingGuide = new AnswerFile(24, completeExam, "Marking guide",
				new SourceDocument(25, "Chemistry/2025/answers.pdf"), true);

		private final BookletCorpusStatus paper1Status = new BookletCorpusStatus(paper1, true, markingGuide, 20, 20,
				new QuestionCorpusSummary(20, 20, 0, 0, 0, 0, 0), new McqExplanationCoverage(true, 2, 1),
				EnumSet.noneOf(BookletCorpusFinding.class));

		private final BookletCorpusStatus paper2Status = new BookletCorpusStatus(paper2, true, markingGuide, 29, 34,
				new QuestionCorpusSummary(34, 31, 3, 1, 2, 1, 1), new McqExplanationCoverage(true, 0, 0),
				EnumSet.of(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));

		private final ExamCorpusStatus completeExamStatus = new ExamCorpusStatus(completeExam,
				new ExamAssetExpectations(2, 2, 1, 1), List.of(paper1Status, paper2Status),
				new QuestionCorpusSummary(54, 51, 3, 1, 2, 1, 1), new McqExplanationSummary(2, 2, 1),
				EnumSet.noneOf(ExamCorpusFinding.class));

		private final Exam activeExam = new Exam(30, chemistry, qcaa, 2024, "External Assessment",
				ExamCaptureState.ACTIVE);
		private final ExamBooklet activePaper = new ExamBooklet(31, activeExam, "Paper 1",
				new SourceDocument(32, "Chemistry/2024/paper1.pdf"), ExamBookletQuestionFormat.MIXED, 10);

		private final BookletCorpusStatus activePaperStatus = new BookletCorpusStatus(activePaper, false, null, 8, 8,
				new QuestionCorpusSummary(8, 8, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.of(BookletCorpusFinding.MISSING_QUESTION_PDF,
						BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));

		private final ExamCorpusStatus activeExamStatus = new ExamCorpusStatus(activeExam,
				new ExamAssetExpectations(2, 1, 0, 0), List.of(activePaperStatus),
				new QuestionCorpusSummary(8, 8, 0, 0, 0, 0, 0), new McqExplanationSummary(0, 0, 0),
				EnumSet.of(ExamCorpusFinding.EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH));

		private static Descriptor classification(Subject subject, long baseId) {
			SyllabusVersion syllabus = new SyllabusVersion(baseId, subject, "2025", true);
			Unit unit = new Unit(baseId + 1, syllabus, "1", "Unit 1", 1);
			Topic topic = new Topic(baseId + 2, syllabus, unit, "1.1", "Topic 1", 1);

			// Dashboard tests need only one valid persisted classification shared by
			// their representative Chemistry Questions.
			return new Descriptor(baseId + 3, syllabus, topic, "1.1.1", "Descriptor", 1);
		}

		private Question question(long id, ExamBooklet booklet, String code, QuestionResponseType responseType,
				boolean contentCaptured, boolean sharedContextRequired) {
			List<QuestionRegion> regions = contentCaptured
					? List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20))
					: List.of();
			int marks = responseType == QuestionResponseType.MULTIPLE_CHOICE ? 1 : 2;

			// Representative Questions drive the operational work table independently
			// of the larger aggregate counts used to exercise Exam/booklet presentation.
			return new Question(id, booklet, code, "", marks, regions, classification, sharedContextRequired, null,
					null, responseType);
		}

		private List<Question> questions() {
			Question completeMcq = question(200, paper1, "Q1", QuestionResponseType.MULTIPLE_CHOICE, true, false);
			completeMcq.setAnswer(new Answer(300, "A", List.of()));

			Question missingContent = question(201, paper2, "Q7", QuestionResponseType.WRITTEN_RESPONSE, false, false);
			Question missingAnswer = question(202, paper2, "Q12", QuestionResponseType.WRITTEN_RESPONSE, true, false);
			Question unknownShared = question(203, paper2, "Q18a", QuestionResponseType.UNKNOWN, true, true);

			Question activeComplete = question(204, activePaper, "Q2", QuestionResponseType.MULTIPLE_CHOICE, true,
					false);
			activeComplete.setAnswer(new Answer(301, "B", List.of()));

			return List.of(unknownShared, activeComplete, missingAnswer, completeMcq, missingContent);
		}
	}
}
