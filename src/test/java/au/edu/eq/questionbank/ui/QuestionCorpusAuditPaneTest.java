package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
		Label summary = robot.lookup("#corpus-summary").queryAs(Label.class);
		Label resultCount = robot.lookup("#corpus-result-count").queryAs(Label.class);
		ListView<QuestionCorpusWorkItem> workItems = robot.lookup("#corpus-work-items").queryAs(ListView.class);
		ComboBox<QuestionCorpusProblem> problem = robot.lookup("#corpus-filter-problem").queryAs(ComboBox.class);
		ComboBox<Subject> subject = robot.lookup("#corpus-filter-subject").queryAs(ComboBox.class);
		ComboBox<QuestionCorpusCompletionFilter> completion = robot.lookup("#corpus-filter-completion")
				.queryAs(ComboBox.class);
		assertTrue(summary.getText().contains("Total: 3"));
		assertTrue(summary.getText().contains("Complete: 1"));
		assertTrue(summary.getText().contains("Incomplete: 2"));
		assertTrue(summary.getText().contains("Missing answer: 1"));
		assertTrue(summary.getText().contains("Unknown response type: 1"));
		assertEquals("Showing 3 question(s)", resultCount.getText());
		assertEquals(3, workItems.getItems().size());
		robot.interact(() -> problem.setValue(QuestionCorpusProblem.MISSING_ANSWER));
		assertEquals(1, workItems.getItems().size());
		assertEquals("Q2", workItems.getItems().getFirst().question().getQuestionCode());
		robot.clickOn("#corpus-clear-filters");
		robot.interact(() -> subject.setValue(chemistry));
		assertEquals(2, workItems.getItems().size());
		assertTrue(summary.getText().contains("Total: 2"));
		robot.interact(() -> completion.setValue(QuestionCorpusCompletionFilter.COMPLETE));
		assertEquals(1, workItems.getItems().size());
		assertEquals("Q1", workItems.getItems().getFirst().question().getQuestionCode());
	}

	@Start
	void start(Stage stage) {
		Fixture fixture = new Fixture();
		chemistry = fixture.chemistry;
		pane = new QuestionCorpusAuditPane(fixture.questions());
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
			Question unknown = question(52, physicsBooklet, physicsClassification, "Q3", QuestionResponseType.UNKNOWN);
			return List.of(complete, missingAnswer, unknown);
		}
	}
}
