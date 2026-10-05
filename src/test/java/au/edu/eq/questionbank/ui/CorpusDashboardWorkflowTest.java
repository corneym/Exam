package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CorpusDashboardWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void corpusDashboardHomeShowsLiveDashboardForWorkingSubject(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Test the current Dashboard-home navigation model rather than the removed
			// Questions-menu shortcut.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		Node applicationRoot = primaryStage.getScene().getRoot();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> nodePresentOnFx(robot, applicationRoot, "#corpus-dashboard-exams"));
		@SuppressWarnings("unchecked")
		ComboBox<Subject> subjects = lookupOnFx(robot, applicationRoot, "#curriculum-subject", ComboBox.class);
		TableView<?> exams = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-exams", TableView.class);
		TableView<?> booklets = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-booklets", TableView.class);
		TableView<?> questionWork = lookupOnFx(robot, applicationRoot, "#corpus-dashboard-question-work",
				TableView.class);
		MenuBar menuBar = lookupOnFx(robot, applicationRoot, ".menu-bar", MenuBar.class);

		// Dashboard Home owns the authoritative application Subject without retaining
		// either of the redundant navigation entries removed during #65 close-out.
		assertNotNull(subjects.getValue());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "question-corpus-audit".equals(item.getId())));
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "open-exam-for-capture".equals(item.getId())));

		// Exam lifecycle is now owned by explicit Dashboard Exam selection rather than
		// the unrelated concept of whichever booklet happens to be active in Capture.
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "mark-active-exam-complete".equals(item.getId())));
		assertFalse(menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.anyMatch(item -> "reactivate-active-exam".equals(item.getId())));
		assertNotNull(exams);
		assertNotNull(booklets);
		assertNotNull(questionWork);

		// The persisted Exam and booklet created through the ordinary application
		// workflow remain visible through the refreshed Dashboard snapshot.
		assertEquals(1, exams.getItems().size());
		assertEquals(1, booklets.getItems().size());

		// The retired concatenated work-item presentation must not return.
		assertFalse(nodePresentOnFx(robot, applicationRoot, "#corpus-work-items"));
	}

	@Test
	void dashboardLegacyImportDialogCancelReturnsHome(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin on the operational home surface because ownership of cancellation is
			// the behaviour under test.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-import-legacy-questions").tryQuery().isPresent());
		Button importLegacy = lookup(robot, "#corpus-dashboard-import-legacy-questions", Button.class);
		assertFalse(importLegacy.isDisabled());

		// The import dialog is modal, so schedule the semantic action and keep the test
		// thread free to close the real DialogPane.
		Platform.runLater(importLegacy::fire);
		waitForDialogShowing(robot, "Import Legacy Question Metadata");
		fireDialogButton(robot, "Cancel");
		waitForDialogHidden(robot, "Import Legacy Question Metadata");

		// Cancelling a Dashboard-owned intake restores the Dashboard rather than
		// stranding the user in Exam/Assets.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void dashboardLegacyImportEntersExamAssetsOwnedWorkflow(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Return from the fixture's Capture workspace to the operational home surface.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-import-legacy-questions").tryQuery().isPresent());
		Button importLegacy = robot.lookup("#corpus-dashboard-import-legacy-questions").queryButton();
		assertFalse(importLegacy.isDisable());

		// Import opens the existing modal intake dialog after mounting Exam/Assets for
		// any structural preflight work that may be required.
		Platform.runLater(importLegacy::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#legacy-question-import-syllabus").tryQuery().isPresent());

		// Exam/Assets remains available underneath Dashboard-started intake for
		// structural resolution, but it must not expose a second import launcher.
		Node examAssetsWorkspace = lookup(robot, "#exam-assets-workspace", Node.class);
		assertTrue(examAssetsWorkspace.isVisible());
		assertTrue(robot.lookup("#exam-assets-import-legacy-questions").tryQuery().isEmpty());
		Button returnDashboard = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnDashboard.isVisible());
		assertTrue(returnDashboard.isManaged());
		DialogPane importDialog = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
		Node cancelNode = importDialog.lookupButton(ButtonType.CANCEL);
		assertTrue(cancelNode instanceof Button);

		// Cancelling the initial Dashboard-owned intake abandons that transaction and
		// returns directly home; there is no remaining Exam/Assets Return action to
		// invoke afterwards.
		robot.interact(((Button) cancelNode)::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#exam-assets-workspace").tryQuery().isEmpty());
	}

	@Test
	void dashboardMissingAnswerCompletionRefreshesHomeAfterExplicitReturn(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1");

		// Create one persisted Written Response Question whose only remaining corpus
		// problem is its missing Answer.
		Question question = captureQuestion(robot, "DASH-A1");

		// Establish the authoritative AnswerFile mapping before entering the Dashboard.
		// Closing the managed session afterwards ensures Dashboard capture must reopen
		// that persisted source rather than accidentally reusing the test setup view.
		openAnswerPdfForTest(question);

		// PdfWorkspacePane owns JavaFX presentation state as well as the managed PDF
		// sessions, so closing it must occur on the JavaFX application thread.
		WaitForAsyncUtils.asyncFx(() -> {
			pdfWorkspace().closeManagedPdfSessions();
			return null;
		}).get();
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin from the same authoritative Dashboard refresh used after ordinary
			// specialised workflow navigation.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-summary-missing-answer").tryQuery()
						.filter(Button.class::isInstance).map(Button.class::cast).map(Button::getText)
						.filter("1 Missing answers"::equals).isPresent());
		Button missingAnswers = lookup(robot, "#corpus-dashboard-summary-missing-answer", Button.class);
		Button totalQuestions = lookup(robot, "#corpus-dashboard-summary-total", Button.class);

		// The Question is present exactly once and its Answer remains the outstanding
		// Dashboard task.
		assertEquals("1 Questions", totalQuestions.getText());
		assertEquals("1 Missing answers", missingAnswers.getText());
		fireControl(robot, missingAnswers);
		@SuppressWarnings("unchecked")
		TableView<Object> questionWork = robot.lookup("#corpus-dashboard-question-work").queryAs(TableView.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> questionWork.getItems().size() == 1);

		// Route the exact Missing Answer row through the task-specific Dashboard
		// action.
		robot.interact(() -> questionWork.getSelectionModel().selectFirst());
		Button completeAnswer = lookup(robot, "#corpus-dashboard-complete-answer", Button.class);
		assertFalse(completeAnswer.isDisabled());
		fireControl(robot, completeAnswer);
		ComboBox<Question> unanswered = unansweredQuestions(robot);

		// The selected persisted Question must own Answer capture.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> unanswered.getValue() != null && unanswered.getValue().getId() == question.getId());
		Button returnDashboard = lookup(robot, "#return-corpus-dashboard", Button.class);
		Label selectedAnswerPdf = lookup(robot, "#selected-answer-pdf", Label.class);
		assertTrue(returnDashboard.isVisible());
		assertTrue(returnDashboard.isManaged());

		// Dashboard Answer capture must reopen the authoritative assigned AnswerFile
		// rather than depending on whichever document happened to be open previously.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> "exam.pdf".equals(selectedAnswerPdf.getText()));

		// Written Response completion is region-based. Use the real pointer gesture
		// only
		// for PDF-region geometry, then semantic control activation for the actions.
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-answer-region");
		Label regionCount = lookup(robot, "#answer-region-count", Label.class);
		Button saveAnswer = lookup(robot, "#save-answer", Button.class);
		assertEquals("Regions: 1", regionCount.getText());
		assertFalse(saveAnswer.isDisabled());
		fireControl(robot, saveAnswer);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(new SqliteDatabase(databasePath));

		// Persistence is the durable completion condition for the Answer task.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> repository.findById(question.getId())
				.filter(Question::hasAnswer).map(stored -> stored.getAnswer().getRegions().size() == 1).orElse(false));

		// Saving must leave Dashboard navigation under explicit user control.
		assertTrue(returnDashboard.isVisible());
		assertTrue(robot.lookup("#capture-workspace-mode").tryQuery().isPresent());
		fireControl(robot, returnDashboard);

		// Returning home must reload corpus status from persistence and remove exactly
		// the completed Missing Answer without creating a replacement Question.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-summary-missing-answer").tryQuery()
						.filter(Button.class::isInstance).map(Button.class::cast).map(Button::getText)
						.filter("0 Missing answers"::equals).isPresent());
		Button refreshedMissingAnswers = lookup(robot, "#corpus-dashboard-summary-missing-answer", Button.class);
		Button refreshedTotalQuestions = lookup(robot, "#corpus-dashboard-summary-total", Button.class);
		assertEquals("0 Missing answers", refreshedMissingAnswers.getText());
		assertEquals("1 Questions", refreshedTotalQuestions.getText());
	}

	@Test
	void dashboardOwnedPendingLegacyImportCancelReturnsHome(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		WaitForAsyncUtils.asyncFx(() -> {

			// Establish an ordinary Dashboard-owned Exam/Assets return session.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-exams").tryQuery().isPresent());
		TableView<?> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		robot.interact(() -> exams.getSelectionModel().selectFirst());
		fireControl(robot, "#corpus-dashboard-manage-exam-assets");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		ExamAssetsPane pane = field(application, "examAssetsPane", ExamAssetsPane.class);

		// Reproduce the state reached after Dashboard-launched workbook preflight found
		// unresolved structural requirements.
		setField(application, "legacyQuestionImportDashboardOwned", Boolean.TRUE);
		robot.interact(() -> pane.showLegacyImportRequirements("2019", Path.of("legacy-biology.xlsx"),
				List.of(new LegacyBookletRequirement("QCAA", 2020, "Paper 1")), () -> {
				}, () -> {
					try {
						invoke(application, "cancelPendingLegacyQuestionImport", new Class<?>[0]);
					} catch (Exception exception) {
						throw new RuntimeException(exception);
					}
				}));
		Button returnDashboard = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnDashboard.isVisible());

		// Pending structural intake deliberately disables ordinary Return; Cancel
		// Import
		// must therefore consume the transaction and perform the Dashboard return
		// itself.
		assertTrue(returnDashboard.isDisabled());
		fireControl(robot, "#exam-assets-legacy-import-cancel");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void dashboardReturnSurvivesCaptureAndExamAssetsTransitions(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2025, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MIXED);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		WaitForAsyncUtils.asyncFx(() -> {

			// Begin from the production home surface so Dashboard-return ownership is
			// established by the real Manage Exam / Assets route.
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {

			// Dashboard reconstruction is asynchronous. Absence during an early polling
			// iteration means "not ready yet", not a failed TestFX query.
			return robot.lookup("#corpus-dashboard-booklets").tryQuery().filter(TableView.class::isInstance)
					.map(TableView.class::cast).map(table -> !table.getItems().isEmpty()).orElse(false);
		});
		TableView<?> dashboardBooklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		robot.interact(() -> dashboardBooklets.getSelectionModel().selectFirst());
		Button manageExamAssets = lookup(robot, "#corpus-dashboard-manage-exam-assets", Button.class);
		assertFalse(manageExamAssets.isDisabled());
		fireControl(robot, manageExamAssets);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		Button assetsReturn = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(assetsReturn.isVisible());
		assertTrue(assetsReturn.isManaged());
		RadioButton bookletSelection = lookup(robot, "#exam-assets-question-select-" + booklet.getId(),
				RadioButton.class);
		if (!bookletSelection.isSelected()) {

			// Selection is semantic application state; pointer hit-testing is irrelevant
			// to this navigation regression.
			fireControl(robot, bookletSelection);
		}
		Button useBooklet = lookup(robot, "#exam-assets-use-selected-booklet", Button.class);
		assertFalse(useBooklet.isDisabled());
		fireControl(robot, useBooklet);

		// Use Selected Booklet is asynchronous. Wait for the resulting capture surface,
		// not for a transient worker flag.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#capture-workspace").tryQuery().isPresent());
		Button captureReturn = lookup(robot, "#return-corpus-dashboard", Button.class);
		assertTrue(captureReturn.isVisible());
		assertTrue(captureReturn.isManaged());

		// Moving back into structural management must transfer the same Dashboard
		// session instead of discarding it.
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-assets-workspace").tryQuery().isPresent());
		assetsReturn = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(assetsReturn.isVisible());
		assertTrue(assetsReturn.isManaged());
		Node busyOverlay = lookup(robot, "#workspace-busy-overlay", Node.class);
		assertFalse(busyOverlay.isVisible());
		assertFalse(busyOverlay.isManaged());

		// Finally exercise the retained route rather than merely asserting its button.
		fireControl(robot, assetsReturn);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
	}

	@Test
	void legacyRecheckFeedbackNamesRemainingBookletRequirements(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		List<LegacyBookletRequirement> requirements = List.of(new LegacyBookletRequirement("QCAA", 2020, "MCQ booklet"),
				new LegacyBookletRequirement("QCAA", 2020, "Paper 1"));
		Platform.runLater(() -> {
			try {

				// Exercise the feedback used specifically after Recheck finds that saved
				// structural identities still do not satisfy workbook preflight.
				invoke(application, "showLegacyQuestionImportStillIncomplete", new Class<?>[] { List.class },
						requirements);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Legacy Question Import");
		DialogPane dialog = showingDialogPane(robot, "Legacy Question Import");
		assertEquals("Import not ready — booklet requirements remain.", dialog.getHeaderText());
		assertTrue(dialog.getContentText().contains("QCAA 2020 — MCQ booklet"));
		assertTrue(dialog.getContentText().contains("QCAA 2020 — Paper 1"));
		assertTrue(dialog.getContentText().contains("booklet Name must match"));
		assertTrue(dialog.getContentText().contains("edit that existing booklet"));
		fireDialogButton(robot, "OK");
		waitForDialogHidden(robot, "Legacy Question Import");
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}
}
