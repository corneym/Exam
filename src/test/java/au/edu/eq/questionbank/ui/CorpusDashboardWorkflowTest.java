package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import javafx.application.Platform;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
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
		assertEquals("_Corpus Dashboard...", dashboardItem.getText());
		assertFalse(dashboardItem.isDisable());

		// The menu action opens a modal showAndWait workflow, so queue the real
		// MenuItem action on the JavaFX thread and inspect the resulting DialogPane.
		Platform.runLater(dashboardItem::fire);
		waitForDialogShowing(robot, "Corpus Dashboard");
		DialogPane dialog = showingDialogPane(robot, "Corpus Dashboard");
		assertNotNull(dialog);
		Label workingSubject = (Label) dialog.lookup("#corpus-dashboard-working-subject");
		assertNotNull(workingSubject);
		assertEquals("Chemistry", workingSubject.getText());
		TableView<?> exams = (TableView<?>) dialog.lookup("#corpus-dashboard-exams");
		TableView<?> booklets = (TableView<?>) dialog.lookup("#corpus-dashboard-booklets");
		TableView<?> questionWork = (TableView<?>) dialog.lookup("#corpus-dashboard-question-work");
		assertNotNull(exams);
		assertNotNull(booklets);
		assertNotNull(questionWork);

		// The persisted Exam and booklet created through the normal application test
		// fixture must be visible through the live audit-service composition.
		assertEquals(1, exams.getItems().size());
		assertEquals(1, booklets.getItems().size());

		// The retired concatenated ListView must not survive anywhere in the live
		// Dashboard dialog.
		assertNull(dialog.lookup("#corpus-work-items"));
		closeDialog(robot, "Corpus Dashboard");
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
