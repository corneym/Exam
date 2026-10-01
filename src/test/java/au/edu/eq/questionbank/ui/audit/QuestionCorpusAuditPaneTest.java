package au.edu.eq.questionbank.ui.audit;

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

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.audit.QuestionCorpusCompletionFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class QuestionCorpusAuditPaneTest {

	private QuestionCorpusAuditPane pane;
	private Subject chemistry;

	@Test
	@SuppressWarnings("unchecked")
	void displaysSummaryAndFiltersWorkQueue(FxRobot robot) {
		Label workingSubject = robot.lookup("#corpus-working-subject").queryAs(Label.class);
		Label summary = robot.lookup("#corpus-summary").queryAs(Label.class);
		Label resultCount = robot.lookup("#corpus-result-count").queryAs(Label.class);
		ListView<QuestionCorpusWorkItem> workItems = robot.lookup("#corpus-work-items").queryAs(ListView.class);
		ComboBox<QuestionCorpusProblem> problem = robot.lookup("#corpus-filter-problem").queryAs(ComboBox.class);
		ComboBox<QuestionCorpusCompletionFilter> completion = robot.lookup("#corpus-filter-completion")
				.queryAs(ComboBox.class);

		// Working Subject is read-only context. No independent audit Subject selector
		// may exist.
		assertEquals("Chemistry", workingSubject.getText());
		assertTrue(robot.lookup("#corpus-filter-subject").queryAll().isEmpty());
		assertTrue(summary.getText().contains("Total: 3"));
		assertTrue(summary.getText().contains("Complete: 1"));
		assertTrue(summary.getText().contains("Incomplete: 2"));
		assertTrue(summary.getText().contains("Missing answer: 1"));
		assertTrue(summary.getText().contains("Unknown response type: 1"));
		assertEquals("Showing 3 question(s)", resultCount.getText());
		assertEquals(3, workItems.getItems().size());

		// Every visible Question must belong to the authoritative Working Subject.
		assertTrue(workItems.getItems().stream()
				.allMatch(item -> item.question().getExam().getSubject().getId() == chemistry.getId()));
		robot.interact(() -> problem.setValue(QuestionCorpusProblem.MISSING_ANSWER));
		assertEquals(1, workItems.getItems().size());
		assertEquals("Q2", workItems.getItems().getFirst().question().getQuestionCode());
		Button clearFilters = robot.lookup("#corpus-clear-filters").queryButton();
		robot.interact(clearFilters::fire);
		assertEquals(3, workItems.getItems().size());
		robot.interact(() -> completion.setValue(QuestionCorpusCompletionFilter.COMPLETE));
		assertEquals(1, workItems.getItems().size());
		assertEquals("Q1", workItems.getItems().getFirst().question().getQuestionCode());
	}

	@Test
	@SuppressWarnings("unchecked")
	void filterOptionsAreRestrictedToWorkingSubject(FxRobot robot) {
		ComboBox<Integer> year = robot.lookup("#corpus-filter-year").queryAs(ComboBox.class);
		ComboBox<ExamBooklet> booklet = robot.lookup("#corpus-filter-booklet").queryAs(ComboBox.class);

		// The source snapshot also contains Physics 2025 data. Neither its year nor
		// booklet may leak into Chemistry's local Dashboard filters.
		assertEquals(List.of(2024), List.copyOf(year.getItems()));
		assertEquals(1, booklet.getItems().size());
		assertEquals(chemistry.getId(), booklet.getItems().getFirst().getExam().getSubject().getId());
	}

	@Test
	void selectsVisibleUnknownQuestionsForBulkResponseTypeResolution(FxRobot robot) {
		AtomicReference<List<Question>> selectedQuestions = new AtomicReference<>();
		AtomicReference<QuestionResponseType> selectedResponseType = new AtomicReference<>();
		robot.interact(() -> pane.setBulkResponseTypeHandler((questions, responseType) -> {
			selectedQuestions.set(List.copyOf(questions));
			selectedResponseType.set(responseType);
		}));
		Button selectUnknown = robot.lookup("#corpus-select-all-unknown").queryButton();
		robot.interact(selectUnknown::fire);
		Button writtenResponseButton = robot.lookup("#corpus-set-written-response").queryButton();
		assertFalse(writtenResponseButton.isDisable());
		robot.interact(writtenResponseButton::fire);

		// The Physics UNKNOWN Question is outside Working Subject scope and therefore
		// cannot enter the bulk correction request.
		assertEquals(1, selectedQuestions.get().size());
		assertEquals("Q3", selectedQuestions.get().getFirst().getQuestionCode());
		assertEquals(chemistry.getId(), selectedQuestions.get().getFirst().getExam().getSubject().getId());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, selectedResponseType.get());
	}

	@Start
	void start(Stage stage) {
		Fixture fixture = new Fixture();
		chemistry = fixture.chemistry;

		// Corpus Audit receives its Subject from the application rather than exposing
		// another Subject selector.
		pane = new QuestionCorpusAuditPane(chemistry, fixture.questions());
		stage.setScene(new Scene(pane, 1100, 600));
		stage.show();
	}

	private static final class Fixture {

		private final Subject chemistry = new Subject(1, "Chemistry");
		private final Subject physics = new Subject(2, "Physics");
		private final ExamProvider provider = new ExamProvider(3, "QCAA");
		private final Descriptor chemistryClassification = classification(chemistry, 10);
		private final Descriptor physicsClassification = classification(physics, 20);
		private final Exam chemistryExam = new Exam(30, chemistry, provider, 2024, "Chemistry EA");
		private final Exam physicsExam = new Exam(31, physics, provider, 2025, "Physics EA");
		private final ExamBooklet chemistryBooklet = new ExamBooklet(40, chemistryExam, "Paper 1",
				new SourceDocument(41, "Chemistry/2024/paper1.pdf"));
		private final ExamBooklet physicsBooklet = new ExamBooklet(42, physicsExam, "Paper 1",
				new SourceDocument(43, "Physics/2025/paper1.pdf"));

		private static Descriptor classification(Subject subject, long baseId) {
			SyllabusVersion syllabus = new SyllabusVersion(baseId, subject, "2025", true);
			Unit unit = new Unit(baseId + 1, syllabus, "1", "Unit 1", 1);
			Topic topic = new Topic(baseId + 2, syllabus, unit, "1.1", "Topic 1", 1);
			return new Descriptor(baseId + 3, syllabus, topic, "1.1.1", "Descriptor", 1);
		}

		private Question question(long id, ExamBooklet booklet, Descriptor classification, String code,
				QuestionResponseType responseType) {
			return new Question(id, booklet, code, "", 1,
					List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20)), classification, false, null, null,
					responseType);
		}

		private List<Question> questions() {
			Question complete = question(50, chemistryBooklet, chemistryClassification, "Q1",
					QuestionResponseType.MULTIPLE_CHOICE);
			complete.setAnswer(new Answer(60, "A", List.of()));
			Question missingAnswer = question(51, chemistryBooklet, chemistryClassification, "Q2",
					QuestionResponseType.WRITTEN_RESPONSE);
			Question chemistryUnknown = question(52, chemistryBooklet, chemistryClassification, "Q3",
					QuestionResponseType.UNKNOWN);
			Question physicsUnknown = question(53, physicsBooklet, physicsClassification, "Q99",
					QuestionResponseType.UNKNOWN);

			// Deliberately place out-of-scope Physics first so the test proves audit
			// scoping rather than accidentally relying on input order.
			return List.of(physicsUnknown, chemistryUnknown, complete, missingAnswer);
		}
	}
}
