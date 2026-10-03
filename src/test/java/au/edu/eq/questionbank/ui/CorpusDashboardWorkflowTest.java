package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CorpusDashboardWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void corpusDashboardMenuOpensLiveDashboardForWorkingSubject(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		MenuBar menuBar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
		MenuItem dashboardItem = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(item -> "question-corpus-audit".equals(item.getId())).findFirst().orElseThrow();

		// Corpus Dashboard is now ordinary main-window navigation rather than a modal
		// dialog, so its menu label no longer uses an ellipsis.
		assertEquals("_Corpus Dashboard", dashboardItem.getText());
		assertFalse(dashboardItem.isDisable());
		robot.interact(dashboardItem::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-exams").tryQuery().isPresent());
		@SuppressWarnings("unchecked")
		ComboBox<Subject> subjects = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		TableView<?> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<?> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<?> questionWork = robot.lookup("#corpus-dashboard-question-work").queryAs(TableView.class);

		// Returning home retains the authoritative application Subject inside the
		// Dashboard rather than creating a second modal Subject presentation.
		assertNotNull(subjects.getValue());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertNotNull(exams);
		assertNotNull(booklets);
		assertNotNull(questionWork);

		// The persisted Exam and booklet created through the ordinary application
		// workflow remain visible through the live Dashboard snapshot.
		assertEquals(1, exams.getItems().size());
		assertEquals(1, booklets.getItems().size());

		// The retired concatenated work-item presentation must not return.
		assertTrue(robot.lookup("#corpus-work-items").tryQuery().isEmpty());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
