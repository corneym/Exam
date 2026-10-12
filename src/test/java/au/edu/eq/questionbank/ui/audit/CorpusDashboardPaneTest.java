package au.edu.eq.questionbank.ui.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.CurriculumLevel;
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
import au.edu.eq.questionbank.service.audit.QuestionCorpusCompletionFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverage;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingLevelCoverage;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CorpusDashboardPaneTest {

	private CorpusDashboardPane pane;
	private Fixture fixture;

	@Test
	void bookletActionsSeparateCaptureFromInspectionAndScrollWhenNarrow(FxRobot robot) {
		ScrollPane actions = robot.lookup("#corpus-dashboard-booklet-actions-scroll").queryAs(ScrollPane.class);
		HBox row = (HBox) actions.getContent();
		Button captureQuestions = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
		Button captureAnswers = robot.lookup("#corpus-dashboard-capture-answers").queryButton();
		Button captureMcq = robot.lookup("#corpus-dashboard-capture-mcq-explanations").queryButton();
		Button inspect = robot.lookup("#corpus-dashboard-inspect-booklet-questions").queryButton();
		Region spacer = robot.lookup("#corpus-dashboard-booklet-action-spacer").queryAs(Region.class);

		// Capture actions precede Inspection in both layout and keyboard order.
		assertEquals(List.of(captureQuestions, captureAnswers, captureMcq, spacer, inspect), row.getChildren());
		assertEquals(Priority.ALWAYS, HBox.getHgrow(spacer));
		assertFalse(spacer.isFocusTraversable());
		assertTrue(actions.isFitToWidth());
		assertEquals(Region.USE_PREF_SIZE, row.getMinWidth(), 0.001);

		// Exercise the row at a width with ample space without depending
		// on the virtual display's physical screen resolution.
		robot.interact(() -> {
			pane.applyCss();
			pane.layout();
			double wideWidth = row.prefWidth(-1) + 100;
			row.resize(wideWidth, row.prefHeight(wideWidth));
			row.layout();
			assertTrue(spacer.getWidth() >= 50, "The spacer should separate Capture and Inspect");
			assertEquals(row.getWidth(), inspect.getBoundsInParent().getMaxX(), 2.0);
			assertTrue(captureMcq.getBoundsInParent().getMaxX() < inspect.getBoundsInParent().getMinX());
		});

		// At narrow widths, preserve access to every action through
		// horizontal scrolling rather than overlapping the buttons.
		robot.interact(() -> pane.getScene().getWindow().setWidth(820));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			pane.applyCss();
			pane.layout();
		});
		assertTrue(actions.getViewportBounds().getWidth() > 0);
		assertTrue(row.getLayoutBounds().getWidth() > actions.getViewportBounds().getWidth() + 5,
				"The narrow booklet section should scroll horizontally");
		robot.interact(() -> actions.setHvalue(actions.getHmax()));
		assertEquals(actions.getHmax(), actions.getHvalue(), 0.001);
	}

	@Test
	void bookletInspectionRequiresPersistedQuestions(FxRobot robot) {
		Button inspect = robot.lookup("#corpus-dashboard-inspect-booklet-questions").queryButton();
		Button captureQuestions = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
		@SuppressWarnings("unchecked")
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		BookletCorpusStatus emptyBooklet = new BookletCorpusStatus(fixture.activePaper, true, null, 0, 0, 0,
				new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus emptyExam = new ExamCorpusStatus(fixture.activeExam, new ExamAssetExpectations(1, 1, 0, 0),
				List.of(emptyBooklet), new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		robot.interact(() -> {
			pane.replaceData(List.of(emptyExam), List.of());
			booklets.getSelectionModel().selectFirst();
		});

		// The booklet is selected but contains no persisted Questions.
		// New capture remains available; inspection does not.
		assertTrue(inspect.isDisable());
		assertFalse(captureQuestions.isDisable());

		// Inspection becomes available for an ordinary populated booklet.
		robot.interact(() -> {
			pane.replaceData(List.of(fixture.activeExamStatus), fixture.questions());
			booklets.getSelectionModel().selectFirst();
		});
		assertFalse(inspect.isDisable());

		// Removing the booklet selection disables Inspection again.
		robot.interact(() -> booklets.getSelectionModel().clearSelection());
		assertTrue(inspect.isDisable());
	}

	@Test
	@SuppressWarnings("unchecked")
	void bookletReviewCheckboxControlsDerivedExamStatus(FxRobot robot) {
		BookletCorpusStatus readyBooklet = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 0,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus readyExam = new ExamCorpusStatus(fixture.activeExam, new ExamAssetExpectations(1, 1, 0, 0),
				List.of(readyBooklet), new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		AtomicReference<ExamBooklet> requestedBooklet = new AtomicReference<>();
		AtomicReference<Boolean> requestedState = new AtomicReference<>();
		robot.interact(() -> {
			pane.setApplicabilityReviewHandler((booklet, reviewed) -> {
				requestedBooklet.set(booklet);
				requestedState.set(reviewed);
			});
			pane.replaceData(List.of(readyExam), List.of());
		});
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableColumn<ExamCorpusStatus, String> examReview = exams.getColumns().stream()
				.filter(column -> "Applicability Reviewed".equals(column.getText()))
				.map(column -> (TableColumn<ExamCorpusStatus, String>) column).findFirst().orElseThrow();
		TableColumn<BookletCorpusStatus, Boolean> bookletReview = booklets.getColumns().stream()
				.filter(column -> "Applicability Reviewed".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, Boolean>) column).findFirst().orElseThrow();
		Button lifecycle = robot.lookup("#corpus-dashboard-exam-lifecycle").queryButton();
		assertEquals("—", examReview.getCellData(readyExam));
		assertFalse(bookletReview.getCellData(readyBooklet));
		assertTrue(lifecycle.isDisable());

		// Bring the checkbox column into view before semantic activation.
		robot.interact(() -> booklets.scrollToColumn(bookletReview));
		WaitForAsyncUtils.waitForFxEvents();
		CheckBox checkBox = robot.lookup("#corpus-dashboard-applicability-reviewed-31").queryAs(CheckBox.class);
		assertFalse(checkBox.isDisable());
		robot.interact(checkBox::fire);
		assertEquals(fixture.activePaper.getId(), requestedBooklet.get().getId());
		assertEquals(Boolean.TRUE, requestedState.get());

		// A callback request is not itself persisted state.
		assertTrue(lifecycle.isDisable());

		// Simulate the new authoritative persistence snapshot.
		robot.interact(() -> pane.setApplicabilityReviewState(Set.of(fixture.activePaper.getId())));
		assertEquals("✓", examReview.getCellData(readyExam));
		assertTrue(bookletReview.getCellData(readyBooklet));
		assertFalse(lifecycle.isDisable());
		checkBox = robot.lookup("#corpus-dashboard-applicability-reviewed-31").queryAs(CheckBox.class);
		CheckBox confirmedCheckBox = checkBox;
		robot.interact(confirmedCheckBox::fire);
		assertEquals(Boolean.FALSE, requestedState.get());
		robot.interact(() -> pane.setApplicabilityReviewState(Set.of()));
		assertEquals("—", examReview.getCellData(readyExam));
		assertFalse(bookletReview.getCellData(readyBooklet));
		assertTrue(lifecycle.isDisable());
	}

	@Test
	@SuppressWarnings("unchecked")
	void bookletTableDistinguishesCountsProblemsAndNotApplicableValues(FxRobot robot) {
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);

		// Found and expected top-level Question counts belong in one column.
		assertTrue(booklets.getColumns().stream()
				.noneMatch(column -> "Expected".equals(column.getText()) || "Found".equals(column.getText())));
		TableColumn<BookletCorpusStatus, String> questions = booklets.getColumns().stream()
				.filter(column -> "Questions".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();
		TableColumn<BookletCorpusStatus, String> descriptors = booklets.getColumns().stream()
				.filter(column -> "No descriptor".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();
		TableColumn<BookletCorpusStatus, String> explanations = booklets.getColumns().stream()
				.filter(column -> "MCQ explanations".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();
		TableColumn<BookletCorpusStatus, String> problems = booklets.getColumns().stream()
				.filter(column -> "Problems".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();
		ExamBooklet unknownBooklet = new ExamBooklet(80, fixture.activeExam, "Unknown count",
				new SourceDocument(81, "Chemistry/2024/unknown.pdf"), ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		BookletCorpusStatus unknown = new BookletCorpusStatus(unknownBooklet, true, null, 0, 0, 0,
				new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.of(BookletCorpusFinding.MISSING_EXPECTED_TOP_LEVEL_QUESTION_COUNT));
		BookletCorpusStatus emptyKnown = new BookletCorpusStatus(fixture.paper2, true, fixture.markingGuide, 0, 0, 0,
				new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0), new McqExplanationCoverage(true, 0, 0),
				EnumSet.of(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));
		BookletCorpusStatus complete = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 0,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));

		// Zero captured and an unknown expectation are different states.
		assertEquals("0 / ?", questions.getCellData(unknown));
		assertEquals("0 / 30", questions.getCellData(emptyKnown));
		assertEquals("10 / 10", questions.getCellData(complete));
		assertEquals("29 / 30", questions.getCellData(fixture.paper2Status));

		// No Questions means descriptor coverage is not yet applicable.
		assertEquals("—", descriptors.getCellData(unknown));
		assertEquals("—", descriptors.getCellData(emptyKnown));
		assertEquals("0", descriptors.getCellData(complete));
		assertEquals("3", descriptors.getCellData(fixture.paper2Status));

		// Written-response booklets have no MCQ explanation requirement.
		assertEquals("—", explanations.getCellData(emptyKnown));
		assertEquals("—", explanations.getCellData(fixture.paper2Status));

		// A booklet with MCQs and required explanations retains coverage.
		assertEquals("1 / 2", explanations.getCellData(fixture.paper1Status));

		// Problems incorporates audit findings, not only incomplete Questions.
		assertEquals("Yes", problems.getCellData(unknown));
		assertEquals("Yes", problems.getCellData(emptyKnown));
		assertEquals("Yes", problems.getCellData(fixture.paper2Status));
		assertEquals("—", problems.getCellData(complete));
	}

	@Test
	@SuppressWarnings("unchecked")
	void bookletTableReportsQuestionsWithoutDescriptorClassification(FxRobot robot) {
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableColumn<BookletCorpusStatus, String> noDescriptorColumn = booklets.getColumns().stream()
				.filter(column -> "No descriptor".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();

		// A genuine zero means that captured Questions are classified.
		// A non-zero value identifies outstanding descriptor classification.
		assertEquals("0", noDescriptorColumn.getCellData(fixture.paper1Status));
		assertEquals("3", noDescriptorColumn.getCellData(fixture.paper2Status));
	}

	@Test
	@SuppressWarnings("unchecked")
	void captureQuestionsCompletesExistingQuestionWorkBeforeOfferingNewQuestion(FxRobot robot) {
		AtomicReference<Question> routedQuestion = new AtomicReference<>();
		AtomicReference<ExamBooklet> routedNewBooklet = new AtomicReference<>();

		// Reproduce the legacy condition: Expected equals Found, but persisted
		// Questions
		// in the booklet still require Question-content capture.
		BookletCorpusStatus atExpectedWithExistingWork = new BookletCorpusStatus(fixture.paper2, true,
				fixture.markingGuide, 30, 34, fixture.paper2Status.questionsWithoutDescriptorCount(),
				fixture.paper2Status.questionSummary(), fixture.paper2Status.mcqExplanationCoverage(),
				EnumSet.noneOf(BookletCorpusFinding.class));
		robot.interact(() -> {
			pane.setNewQuestionCaptureHandler(routedNewBooklet::set);
			pane.setQuestionCorrectionHandler(routedQuestion::set);
		});
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		Button captureQuestions = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
		robot.interact(() -> {
			booklets.getItems().setAll(atExpectedWithExistingWork);
			booklets.getSelectionModel().select(atExpectedWithExistingWork);
		});

		// Existing Question work remains actionable even though the Exam is COMPLETE
		// and
		// the booklet has already reached its expected Question count.
		assertFalse(captureQuestions.isDisable());
		robot.interact(captureQuestions::fire);

		// Capture Questions must resolve the first existing Question-side task rather
		// than offer to create an additional Question.
		assertNotNull(routedQuestion.get());
		assertEquals("Q7", routedQuestion.get().getQuestionCode());
		assertNull(routedNewBooklet.get());
		assertTrue(robot.lookup("#corpus-dashboard-capture-count-confirmation").tryQuery().isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	void captureQuestionsWarnsWhenExpectedCountAlreadyReached(FxRobot robot) throws TimeoutException {
		AtomicReference<ExamBooklet> routedBooklet = new AtomicReference<>();
		BookletCorpusStatus atExpectedCount = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 0,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		robot.interact(() -> pane.setNewQuestionCaptureHandler(routedBooklet::set));
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		Button captureQuestions = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
		robot.interact(() -> {

			// Construct the exact live-data condition under test: ACTIVE booklet, PDF
			// available, and encountered count equal to its expected count.
			booklets.getItems().setAll(atExpectedCount);
			booklets.getSelectionModel().select(atExpectedCount);
		});
		assertFalse(captureQuestions.isDisable());
		Platform.runLater(captureQuestions::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-capture-count-confirmation").tryQuery().isPresent());
		DialogPane warning = robot.lookup("#corpus-dashboard-capture-count-confirmation").queryAs(DialogPane.class);
		assertTrue(warning.getContentText().contains("10 expected top-level Questions"));
		assertTrue(warning.getContentText().contains("10 have already been found"));
		Node cancelNode = warning.lookupButton(ButtonType.CANCEL);
		assertTrue(cancelNode instanceof Button);

		// Cancelling the warning must not route into capture.
		robot.interact(((Button) cancelNode)::fire);
		assertNull(routedBooklet.get());
		Platform.runLater(captureQuestions::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-capture-count-confirmation").tryQuery().isPresent());
		warning = robot.lookup("#corpus-dashboard-capture-count-confirmation").queryAs(DialogPane.class);
		ButtonType proceedType = warning.getButtonTypes().stream()
				.filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
		Node proceedNode = warning.lookupButton(proceedType);
		assertTrue(proceedNode instanceof Button);

		// Only an explicit second decision allows capture beyond the recorded count.
		robot.interact(((Button) proceedNode)::fire);
		assertEquals(fixture.activePaper.getId(), routedBooklet.get().getId());
	}

	@Test
	@SuppressWarnings("unchecked")
	void clearRestoresFullHierarchyAfterSummaryDrillDown(FxRobot robot) {
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);
		ComboBox<QuestionCorpusCompletionFilter> questionView = robot.lookup("#corpus-dashboard-question-view")
				.queryAs(ComboBox.class);
		Button missingAnswers = robot.lookup("#corpus-dashboard-summary-missing-answer").queryButton();
		Button clear = robot.lookup("#corpus-dashboard-clear-filters").queryButton();

		// Missing Answers deliberately narrows all three Dashboard hierarchy levels.
		robot.interact(missingAnswers::fire);
		assertEquals(1, exams.getItems().size());
		assertEquals(1, booklets.getItems().size());
		assertEquals(2, questions.getItems().size());

		// Clear means remove every Dashboard-local restriction, not merely the
		// Provider/Year/Exam-state controls.
		robot.interact(clear::fire);
		assertEquals(2, exams.getItems().size());
		assertEquals(2, booklets.getItems().size());
		assertEquals(4, questions.getItems().size());
		assertEquals(QuestionCorpusCompletionFilter.ALL, questionView.getValue());
	}

	@Test
	void curriculumMappingReviewIsSeparateFromCorpusCompleteness(FxRobot robot) {
		Label mappingReview = robot.lookup("#corpus-dashboard-mapping-review").queryAs(Label.class);
		Button needsAttention = robot.lookup("#corpus-dashboard-summary-attention").queryButton();

		// Mapping review reports the historical-to-current syllabus pair and its own
		// unresolved work.
		assertTrue(mappingReview.getText().contains("2019 \u2192 2025"));
		assertTrue(mappingReview.getText().contains("11/15 resolved"));
		assertTrue(mappingReview.getText().contains("4 remaining"));
		assertTrue(mappingReview.getText().contains("3 unreviewed"));
		assertTrue(mappingReview.getText().contains("1 inconsistent"));

		// Four outstanding mapping reviews must not become four additional incomplete
		// Questions.
		assertEquals("3 Need attention", needsAttention.getText());
	}

	@Test
	void curriculumSectionRoutesAuthoringAndMappingActions(FxRobot robot) {
		AtomicReference<Boolean> addCalled = new AtomicReference<>(Boolean.FALSE);
		AtomicReference<Boolean> mapCalled = new AtomicReference<>(Boolean.FALSE);
		robot.interact(() -> {
			pane.setAddCurriculumHandler(() -> addCalled.set(Boolean.TRUE));
			pane.setMapCurriculumHandler(() -> mapCalled.set(Boolean.TRUE));
		});
		Button addCurriculum = robot.lookup("#corpus-dashboard-add-curriculum").queryButton();
		Button mapCurriculum = robot.lookup("#corpus-dashboard-map-curriculum").queryButton();
		assertFalse(addCurriculum.isDisable());
		assertFalse(mapCurriculum.isDisable());
		assertEquals(addCurriculum.getParent(), mapCurriculum.getParent());
		robot.interact(addCurriculum::fire);
		robot.interact(mapCurriculum::fire);

		// Dashboard controls delegate to the existing application-owned workflows.
		assertTrue(addCalled.get().booleanValue());
		assertTrue(mapCalled.get().booleanValue());
	}

	@Test
	void dashboardExamAndBookletDividerCanResizeAndFollowWindow(FxRobot robot) throws TimeoutException {
		SplitPane hierarchy = robot.lookup("#corpus-dashboard-hierarchy-row").queryAs(SplitPane.class);
		Node examsSection = robot.lookup("#corpus-dashboard-exams-section").query();
		Node bookletsSection = robot.lookup("#corpus-dashboard-booklets-section").query();
		AtomicReference<double[]> widths = new AtomicReference<>();

		// Use enough horizontal space for a meaningful divider movement
		// without either section encountering its minimum width.
		robot.interact(() -> pane.getScene().getWindow().setWidth(1200));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			robot.interact(() -> {
				pane.applyCss();
				pane.layout();
				widths.set(new double[] { hierarchy.getWidth(), examsSection.getBoundsInParent().getWidth(),
						bookletsSection.getBoundsInParent().getWidth() });
			});
			return widths.get()[0] > 1100 && widths.get()[1] >= 339 && widths.get()[2] >= 359;
		});
		double[] initial = widths.get();
		assertEquals(0.5, hierarchy.getDividerPositions()[0], 0.05);
		assertTrue(Math.abs(initial[1] - initial[2]) < 30.0);

		// Relocate the divider while respecting the minimum booklet width.
		robot.interact(() -> hierarchy.setDividerPositions(0.60));
		try {
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
				robot.interact(() -> {
					hierarchy.requestLayout();
					pane.layout();
					widths.set(new double[] { hierarchy.getWidth(), examsSection.getBoundsInParent().getWidth(),
							bookletsSection.getBoundsInParent().getWidth() });
				});
				return widths.get()[1] > initial[1] + 30 && widths.get()[2] < initial[2] - 30;
			});
		} catch (TimeoutException failure) {
			double[] measured = widths.get();
			throw new AssertionError("Divider did not reallocate panel widths. " + "Initial: exams=" + initial[1]
					+ ", booklets=" + initial[2] + "; measured: hierarchy=" + measured[0] + ", exams=" + measured[1]
					+ ", booklets=" + measured[2], failure);
		}
		double[] adjusted = widths.get();

		// Verify that shrinking the window reallocates the existing sections,
		// without violating their minimum width constraints.
		robot.interact(() -> pane.getScene().getWindow().setWidth(940));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			robot.interact(() -> {
				pane.applyCss();
				pane.layout();
				widths.set(new double[] { hierarchy.getWidth(), examsSection.getBoundsInParent().getWidth(),
						bookletsSection.getBoundsInParent().getWidth() });
			});
			return widths.get()[0] < adjusted[0] - 150 && widths.get()[1] < adjusted[1] - 25;
		});
		double[] narrower = widths.get();
		assertTrue(narrower[1] < adjusted[1] - 25);
		assertTrue(narrower[1] >= 339.0);
		assertTrue(narrower[2] >= 359.0);
	}

	@Test
	void dashboardLabelsRemainReadableAfterDividerResize(FxRobot robot) {
		ScrollPane filters = robot.lookup("#corpus-dashboard-exam-filters-scroll").queryAs(ScrollPane.class);
		SplitPane workSplit = robot.lookup("#corpus-dashboard-work-split").queryAs(SplitPane.class);
		HBox filterRow = (HBox) filters.getContent();
		Label provider = (Label) filterRow.getChildren().get(0);
		Label year = (Label) filterRow.getChildren().get(2);
		Label examState = (Label) filterRow.getChildren().get(4);
		Label selectedExam = robot.lookup("#corpus-dashboard-selected-exam").queryAs(Label.class);
		Label declaredState = robot.lookup("#corpus-dashboard-selected-exam-state").queryAs(Label.class);

		// Exercise window resizing and the existing vertical divider.
		robot.interact(() -> {
			pane.getScene().getWindow().setWidth(820);
			workSplit.setDividerPositions(0.65);
		});
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			pane.applyCss();
			pane.layout();

			// Under the standard light JavaFX theme these labels must
			// retain dark text, including those below the Exam table.
			for (Label label : List.of(provider, year, examState, selectedExam, declaredState)) {
				assertTrue(label.getTextFill() instanceof Color, label.getText());
				Color colour = (Color) label.getTextFill();
				assertTrue(colour.getBrightness() < 0.65, label.getText() + " has unreadable text: " + colour);
			}
		});
	}

	@Test
	void dashboardPlacesCurriculumAboveAdjustableWorkSplit(FxRobot robot) {
		Node curriculumSection = robot.lookup("#corpus-dashboard-curriculum-section").query();
		Node summaryRow = robot.lookup("#corpus-dashboard-summary-row").query();
		SplitPane hierarchyRow = robot.lookup("#corpus-dashboard-hierarchy-row").queryAs(SplitPane.class);
		Node examsSection = robot.lookup("#corpus-dashboard-exams-section").query();
		Node bookletsSection = robot.lookup("#corpus-dashboard-booklets-section").query();
		Node questionSection = robot.lookup("#corpus-dashboard-question-work-section").query();
		SplitPane workSplit = robot.lookup("#corpus-dashboard-work-split").queryAs(SplitPane.class);

		// Subject-level sections remain above the operational workspace.
		assertTrue(pane.getChildren().indexOf(curriculumSection) < pane.getChildren().indexOf(summaryRow));
		assertTrue(pane.getChildren().indexOf(summaryRow) < pane.getChildren().indexOf(workSplit));

		// Exam and booklet sections share a separately adjustable horizontal
		// hierarchy without changing their structural identities.
		assertEquals(Orientation.HORIZONTAL, hierarchyRow.getOrientation());
		assertEquals(2, hierarchyRow.getItems().size());
		assertEquals(examsSection, hierarchyRow.getItems().get(0));
		assertEquals(bookletsSection, hierarchyRow.getItems().get(1));
		assertEquals(1, hierarchyRow.getDividers().size());

		// The established vertical split remains independently adjustable.
		assertEquals(Orientation.VERTICAL, workSplit.getOrientation());
		assertEquals(2, workSplit.getItems().size());
		assertEquals(hierarchyRow, workSplit.getItems().get(0));
		assertEquals(questionSection, workSplit.getItems().get(1));
		assertEquals(1, workSplit.getDividers().size());
		assertEquals(Priority.ALWAYS, VBox.getVgrow(workSplit));
	}

	@Test
	void dashboardTableDataIsCentred(FxRobot robot) {
		TableView<?> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<?> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<?> questionWork = robot.lookup("#corpus-dashboard-question-work").queryAs(TableView.class);

		// Every Dashboard data column uses the same centred presentation. The test
		// checks the cell factory directly so it does not depend on viewport size,
		// scrolling or CSS timing.
		assertCentredColumns(exams);
		assertCentredColumns(booklets);
		assertCentredColumns(questionWork);
	}

	@Test
	void dashboardUsesTitledSectionsAndSingleQuestionActionRow(FxRobot robot) {
		Node examsSection = robot.lookup("#corpus-dashboard-exams-section").query();
		Node bookletsSection = robot.lookup("#corpus-dashboard-booklets-section").query();
		Node questionSection = robot.lookup("#corpus-dashboard-question-work-section").query();
		Node curriculumSection = robot.lookup("#corpus-dashboard-curriculum-section").query();
		Button completeQuestion = robot.lookup("#corpus-dashboard-complete-question").queryButton();
		Button completeAnswer = robot.lookup("#corpus-dashboard-complete-answer").queryButton();
		Button selectUnknown = robot.lookup("#corpus-dashboard-select-all-unknown").queryButton();
		Button multipleChoice = robot.lookup("#corpus-dashboard-set-multiple-choice").queryButton();
		Button writtenResponse = robot.lookup("#corpus-dashboard-set-written-response").queryButton();

		// Major Dashboard responsibilities remain visually separated even though Exam
		// and booklet structure now share one horizontal hierarchy row.
		assertNotNull(examsSection);
		assertNotNull(bookletsSection);
		assertNotNull(questionSection);
		assertNotNull(curriculumSection);

		// Manual persistence refresh has been replaced by automatic refresh on Subject
		// change and return from Dashboard-owned workflows.
		assertTrue(robot.lookup("#corpus-dashboard-refresh").tryQuery().isEmpty());

		// All selected-Question correction and response-type actions occupy one compact
		// action row beneath the Question Work table.
		assertEquals(completeQuestion.getParent(), completeAnswer.getParent());
		assertEquals(completeQuestion.getParent(), selectUnknown.getParent());
		assertEquals(completeQuestion.getParent(), multipleChoice.getParent());
		assertEquals(completeQuestion.getParent(), writtenResponse.getParent());
	}

	@Test
	@SuppressWarnings("unchecked")
	void displaysApprovedExamAndBookletHierarchy(FxRobot robot) {
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		Label selectedExam = robot.lookup("#corpus-dashboard-selected-exam").queryAs(Label.class);
		Label selectedState = robot.lookup("#corpus-dashboard-selected-exam-state").queryAs(Label.class);
		Label selectedCounts = robot.lookup("#corpus-dashboard-selected-exam-counts").queryAs(Label.class);
		Label selectedBooklet = robot.lookup("#corpus-dashboard-selected-booklet").queryAs(Label.class);
		assertEquals(2, exams.getItems().size());

		// Dashboard terminology must use the agreed user-facing corpus concepts rather
		// than the internal generic asset terminology.
		assertEquals("Question Booklets", exams.getColumns().get(4).getText());
		assertEquals("Answer Booklets", exams.getColumns().get(5).getText());
		assertEquals("Question PDF", booklets.getColumns().get(2).getText());

		// First visible Exam is selected automatically, while booklet scope initially
		// remains the whole Exam.
		assertEquals(fixture.completeExamStatus, exams.getSelectionModel().getSelectedItem());
		assertTrue(selectedExam.getText().contains("QCAA 2025"));
		assertEquals("Declared state: COMPLETE", selectedState.getText());
		assertTrue(selectedCounts.getText().contains("Question booklets: 2 / 2"));
		assertTrue(selectedCounts.getText().contains("Answer booklets: 1 / 1"));
		assertTrue(selectedCounts.getText().contains("Questions: 54"));

		// The Exam has three ordinary incomplete Questions plus one required MCQ
		// explanation, so both work dimensions contribute to the operational total.
		assertTrue(selectedCounts.getText().contains("Need work: 4"));
		assertEquals(2, booklets.getItems().size());
		assertNull(booklets.getSelectionModel().getSelectedItem());
		assertEquals("Selected booklet: All booklets", selectedBooklet.getText());
	}

	@Test
	void emptyExamControlsRetainTheirScrollableContainer(FxRobot robot) {
		ScrollPane emptyExamScroll = robot.lookup("#corpus-dashboard-empty-exam-scroll").queryAs(ScrollPane.class);
		Button addExam = robot.lookup("#corpus-dashboard-add-exam").queryButton();
		Button legacyImport = robot.lookup("#corpus-dashboard-import-legacy-questions").queryButton();

		// Start from the ordinary populated-Exam state.
		assertFalse(emptyExamScroll.isManaged());
		robot.interact(() -> pane.replaceData(List.of(), List.of(), List.of(), true, -1L));
		WaitForAsyncUtils.waitForFxEvents();

		// Empty-Exam onboarding must expose its entire control row, including
		// the single Legacy Import action moved from the normal filter row.
		assertTrue(emptyExamScroll.isVisible());
		assertTrue(emptyExamScroll.isManaged());
		assertEquals("corpus-dashboard-exam-empty-state", emptyExamScroll.getContent().getId());
		assertEquals(emptyExamScroll.getContent(), addExam.getParent());
		assertEquals(emptyExamScroll.getContent(), legacyImport.getParent());

		// Returning to a populated Exam must remove the empty-state container
		// rather than leaving a blank scroll area.
		robot.interact(() -> pane.replaceData(List.of(fixture.completeExamStatus, fixture.activeExamStatus),
				fixture.questions()));
		WaitForAsyncUtils.waitForFxEvents();
		assertFalse(emptyExamScroll.isVisible());
		assertFalse(emptyExamScroll.isManaged());
		assertEquals("corpus-dashboard-exam-filter-row", legacyImport.getParent().getId());
	}

	@Test
	@SuppressWarnings("unchecked")
	void emptyExamRemainsVisibleUnderMatchingFilters(FxRobot robot) {
		ExamCorpusStatus emptyExam = new ExamCorpusStatus(fixture.activeExam,
				new ExamAssetExpectations(null, 0, null, 0), List.of(), new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		ComboBox<Integer> years = robot.lookup("#corpus-dashboard-filter-year").queryAs(ComboBox.class);
		ComboBox<ExamCaptureState> states = robot.lookup("#corpus-dashboard-filter-exam-state").queryAs(ComboBox.class);
		Button clear = robot.lookup("#corpus-dashboard-clear-filters").queryButton();

		// Publish an empty Exam independently of any Question.
		robot.interact(() -> pane.replaceData(List.of(emptyExam), List.of()));
		assertEquals(1, exams.getItems().size());
		assertEquals(fixture.activeExam.getId(), exams.getItems().getFirst().exam().getId());

		// Matching lifecycle state must preserve the row.
		robot.interact(() -> states.setValue(ExamCaptureState.ACTIVE));
		assertEquals(1, exams.getItems().size());

		// A deliberately non-matching lifecycle filter excludes it.
		robot.interact(() -> states.setValue(ExamCaptureState.COMPLETE));
		assertTrue(exams.getItems().isEmpty());

		// Clear restores the structural Exam even though there
		// are still no Question or booklet records.
		robot.interact(clear::fire);
		assertEquals(1, exams.getItems().size());
		assertEquals(fixture.activeExam.getId(), exams.getItems().getFirst().exam().getId());

		// A matching year must also preserve visibility.
		robot.interact(() -> years.setValue(fixture.activeExam.getYear()));
		assertEquals(1, exams.getItems().size());
		assertEquals(0, exams.getItems().getFirst().questionSummary().totalQuestions());
	}

	@Test
	void emptySubjectCorpusOffersExamOnlyAfterCurriculumExists(FxRobot robot) {
		AtomicReference<Boolean> addExamCalled = new AtomicReference<>(Boolean.FALSE);
		AtomicReference<Boolean> addCurriculumCalled = new AtomicReference<>(Boolean.FALSE);
		robot.interact(() -> {
			pane.setAddExamHandler(() -> addExamCalled.set(Boolean.TRUE));
			pane.setAddCurriculumHandler(() -> addCurriculumCalled.set(Boolean.TRUE));

			// A newly created Subject has neither curriculum nor Exam structure.
			pane.replaceData(List.of(), List.of(), List.of(), false, -1L);
		});
		Label noExams = robot.lookup("#corpus-dashboard-no-exams").queryAs(Label.class);
		Button addExam = robot.lookup("#corpus-dashboard-add-exam").queryButton();
		Label curriculumStatus = robot.lookup("#corpus-dashboard-mapping-review").queryAs(Label.class);
		Button addCurriculum = robot.lookup("#corpus-dashboard-add-curriculum").queryButton();
		Button mapCurriculum = robot.lookup("#corpus-dashboard-map-curriculum").queryButton();
		Node examOperationalContent = robot.lookup("#corpus-dashboard-exam-operational-content").query();
		Node bookletsSection = robot.lookup("#corpus-dashboard-booklets-section").query();
		Node questionSection = robot.lookup("#corpus-dashboard-question-work-section").query();

		// Curriculum is the first valid onboarding step. Exam creation remains visible
		// as context but cannot be invoked before that prerequisite exists.
		assertEquals("No curriculum has been added.", curriculumStatus.getText());
		assertFalse(addCurriculum.isDisable());
		assertTrue(mapCurriculum.isDisable());
		assertEquals("Add curriculum before adding an Exam.", noExams.getText());
		assertTrue(addExam.isDisable());
		assertFalse(examOperationalContent.isVisible());
		assertFalse(examOperationalContent.isManaged());
		assertFalse(bookletsSection.isVisible());
		assertFalse(bookletsSection.isManaged());
		assertFalse(questionSection.isVisible());
		assertFalse(questionSection.isManaged());
		robot.interact(addExam::fire);
		assertFalse(addExamCalled.get().booleanValue());
		robot.interact(addCurriculum::fire);
		assertTrue(addCurriculumCalled.get().booleanValue());
		robot.interact(() -> {

			// Once curriculum exists, the same empty Subject can proceed to Exam creation
			// without recreating the Dashboard or changing Subject.
			pane.replaceData(List.of(), List.of(), List.of(), true, -1L);
		});
		assertEquals("No mapping review is currently available.", curriculumStatus.getText());
		assertEquals("No Exams have been added.", noExams.getText());
		assertFalse(addExam.isDisable());
		robot.interact(addExam::fire);
		assertTrue(addExamCalled.get().booleanValue());
	}

	@Test
	void examLifecycleActionUsesSelectedExamAuditState(FxRobot robot) {
		AtomicReference<Exam> routedExam = new AtomicReference<>();
		AtomicReference<ExamCaptureState> routedState = new AtomicReference<>();
		robot.interact(() -> pane.setExamLifecycleHandler((exam, state) -> {
			routedExam.set(exam);
			routedState.set(state);
		}));
		Button lifecycle = robot.lookup("#corpus-dashboard-exam-lifecycle").queryButton();

		// The initially selected COMPLETE Exam can always be reopened even when its
		// current audit reports unfinished corpus work.
		assertEquals("Mark Active", lifecycle.getText());
		assertFalse(lifecycle.isDisable());
		robot.interact(lifecycle::fire);
		assertEquals(fixture.completeExam.getId(), routedExam.get().getId());
		assertEquals(ExamCaptureState.ACTIVE, routedState.get());
		robot.interact(() -> pane.replaceData(List.of(fixture.activeExamStatus), List.of()));

		// ACTIVE lifecycle alone is insufficient. This fixture still has structural
		// count mismatches.
		assertEquals("Mark Complete", lifecycle.getText());
		assertTrue(lifecycle.isDisable());
		BookletCorpusStatus missingDescriptor = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 1,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus classificationIncompleteExam = new ExamCorpusStatus(fixture.activeExam,
				new ExamAssetExpectations(1, 1, 0, 0), List.of(missingDescriptor),
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationSummary(0, 0, 0),
				EnumSet.noneOf(ExamCorpusFinding.class));
		robot.interact(() -> pane.replaceData(List.of(classificationIncompleteExam), List.of()));

		// Matching structural and ordinary Question counts are still insufficient while
		// even one Question has not reached descriptor-level classification.
		assertEquals("Mark Complete", lifecycle.getText());
		assertTrue(lifecycle.isDisable());
		BookletCorpusStatus readyBooklet = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 0,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus readyExam = new ExamCorpusStatus(fixture.activeExam, new ExamAssetExpectations(1, 1, 0, 0),
				List.of(readyBooklet), new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		robot.interact(() -> pane.replaceData(List.of(readyExam), List.of()));

		// Complete structural and Question capture is still insufficient
		// until the teacher has explicitly reviewed applicability.
		assertEquals("Mark Complete", lifecycle.getText());
		assertTrue(lifecycle.isDisable());
		robot.interact(() -> pane.setApplicabilityReviewState(Set.of(fixture.activePaper.getId())));

		// The review confirmation completes the remaining lifecycle gate.
		assertFalse(lifecycle.isDisable());
		routedExam.set(null);
		routedState.set(null);
		robot.interact(lifecycle::fire);
		assertEquals(fixture.activeExam.getId(), routedExam.get().getId());
		assertEquals(ExamCaptureState.COMPLETE, routedState.get());
	}

	@Test
	void examRowColoursFollowCompletionAndReviewState(FxRobot robot) {
		@SuppressWarnings("unchecked")
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		BookletCorpusStatus readyBooklet = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 0,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus readyExam = new ExamCorpusStatus(fixture.activeExam, new ExamAssetExpectations(1, 1, 0, 0),
				List.of(readyBooklet), new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		ExamCorpusStatus unknownExam = new ExamCorpusStatus(fixture.completeExam,
				new ExamAssetExpectations(null, 0, null, 0), List.of(), new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		PseudoClass attention = PseudoClass.getPseudoClass("exam-attention");
		PseudoClass clear = PseudoClass.getPseudoClass("exam-clear");
		robot.interact(() -> {
			pane.replaceData(List.of(unknownExam, readyExam), List.of());
			pane.setApplicabilityReviewState(Set.of());
			exams.getSelectionModel().clearSelection();
			pane.applyCss();
			pane.layout();
		});
		int unknownIndex = exams.getItems().indexOf(unknownExam);
		int readyIndex = exams.getItems().indexOf(readyExam);
		assertTrue(unknownIndex >= 0);
		assertTrue(readyIndex >= 0);

		// Unknown expectations require work even when no finding
		// has been explicitly recorded in the snapshot.
		assertTrue(dashboardRowAt(exams, unknownIndex).getPseudoClassStates().contains(attention));

		// Completed capture without applicability review still needs work.
		assertTrue(dashboardRowAt(exams, readyIndex).getPseudoClassStates().contains(attention));
		robot.interact(() -> {
			pane.setApplicabilityReviewState(Set.of(fixture.activePaper.getId()));
			pane.applyCss();
			pane.layout();
		});

		// Review completes the final readiness dimension.
		assertTrue(dashboardRowAt(exams, readyIndex).getPseudoClassStates().contains(clear));
		assertFalse(dashboardRowAt(exams, readyIndex).getPseudoClassStates().contains(attention));
		assertTrue(dashboardRowAt(exams, unknownIndex).getPseudoClassStates().contains(attention));
		Color expectedGreen = readyIndex % 2 == 0 ? Color.web("#f1f8f1") : Color.web("#e5f1e5");
		Color actualGreen = (Color) dashboardRowAt(exams, readyIndex).getBackground().getFills().getFirst().getFill();
		assertEquals(expectedGreen, actualGreen);
		robot.interact(() -> {
			exams.getSelectionModel().select(readyExam);
			exams.requestFocus();
			pane.applyCss();
			pane.layout();
		});

		// Selection highlighting takes precedence over status colours.
		Color selectedColour = (Color) dashboardRowAt(exams, readyIndex).getBackground().getFills().getFirst()
				.getFill();
		assertFalse(expectedGreen.equals(selectedColour));
		assertTrue(dashboardRowAt(exams, readyIndex).isSelected());
		robot.interact(() -> {
			pane.setApplicabilityReviewState(Set.of());
			pane.applyCss();
			pane.layout();
		});

		// Revoking review restores the outstanding-work state.
		assertTrue(dashboardRowAt(exams, readyIndex).getPseudoClassStates().contains(attention));
		assertFalse(dashboardRowAt(exams, readyIndex).getPseudoClassStates().contains(clear));
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
	@SuppressWarnings("unchecked")
	void explicitCaptureActionsUseBookletAndQuestionScope(FxRobot robot) {
		AtomicReference<ExamBooklet> questionCapture = new AtomicReference<>();
		AtomicReference<Question> answerCapture = new AtomicReference<>();
		AtomicReference<Question> questionCorrection = new AtomicReference<>();
		robot.interact(() -> {
			pane.setAnswerCaptureHandler(answerCapture::set);
			pane.setNewQuestionCaptureHandler(questionCapture::set);
			pane.setQuestionCorrectionHandler(questionCorrection::set);
		});
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);
		Button captureAnswers = robot.lookup("#corpus-dashboard-capture-answers").queryButton();
		Button captureQuestions = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
		Button completeAnswer = robot.lookup("#corpus-dashboard-complete-answer").queryButton();
		Button completeQuestion = robot.lookup("#corpus-dashboard-complete-question").queryButton();

		// No booklet selection means there is no unambiguous booklet-level capture
		// target.
		assertTrue(captureAnswers.isDisable());
		assertTrue(captureQuestions.isDisable());
		robot.interact(() -> booklets.getSelectionModel().select(fixture.paper2Status));

		// COMPLETE blocks creation of genuinely new Questions, but this booklet already
		// contains Question-side completion work. Capture Questions therefore remains
		// available and routes to that persisted work rather than adding another
		// Question.
		assertFalse(captureQuestions.isDisable());
		assertFalse(captureAnswers.isDisable());
		robot.interact(captureAnswers::fire);

		// Booklet-level sequential Answer capture still chooses the first Question
		// whose
		// ordinary Answer workflow is immediately ready.
		assertEquals("Q12", answerCapture.get().getQuestionCode());
		QuestionCorpusWorkItem missingAnswer = questions.getItems().stream()
				.filter(item -> "Q12".equals(item.question().getQuestionCode())).findFirst().orElseThrow();
		robot.interact(() -> {
			questions.getSelectionModel().clearSelection();
			questions.getSelectionModel().select(missingAnswer);
		});

		// A row containing only missing Answer work exposes only Answer completion.
		assertFalse(completeAnswer.isDisable());
		assertTrue(completeQuestion.isDisable());
		answerCapture.set(null);
		robot.interact(completeAnswer::fire);
		assertEquals("Q12", answerCapture.get().getQuestionCode());
		QuestionCorpusWorkItem missingContentAndAnswer = questions.getItems().stream()
				.filter(item -> "Q7".equals(item.question().getQuestionCode())).findFirst().orElseThrow();
		robot.interact(() -> {
			questions.getSelectionModel().clearSelection();
			questions.getSelectionModel().select(missingContentAndAnswer);
		});

		// Question content and Answer capture are independent. When both are missing,
		// either task can be performed first from the selected Question Work row.
		assertFalse(completeAnswer.isDisable());
		assertFalse(completeQuestion.isDisable());
		answerCapture.set(null);
		robot.interact(completeAnswer::fire);
		assertEquals("Q7", answerCapture.get().getQuestionCode());
		questionCorrection.set(null);
		robot.interact(completeQuestion::fire);
		assertEquals("Q7", questionCorrection.get().getQuestionCode());
		ComboBox<ExamCaptureState> state = robot.lookup("#corpus-dashboard-filter-exam-state").queryAs(ComboBox.class);
		robot.interact(() -> state.setValue(ExamCaptureState.ACTIVE));
		robot.interact(() -> booklets.getSelectionModel().select(fixture.activePaperStatus));
		assertFalse(captureQuestions.isDisable());
		robot.interact(captureQuestions::fire);
		assertEquals(fixture.activePaper.getId(), questionCapture.get().getId());
	}

	@Test
	void horizontalScrollRowsKeepLabelsReadableAfterResize(FxRobot robot) {
		ScrollPane filters = robot.lookup("#corpus-dashboard-exam-filters-scroll").queryAs(ScrollPane.class);
		Label providerLabel = (Label) ((HBox) filters.getContent()).getChildren().get(0);
		Label selectedExamLabel = robot.lookup("#corpus-dashboard-selected-exam").queryAs(Label.class);
		robot.interact(() -> pane.getScene().getWindow().setWidth(820));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(pane::applyCss);

		// The standard light Dashboard theme requires dark label text.
		// Transparent background colour lookups must not turn it white.
		Color providerText = (Color) providerLabel.getTextFill();
		Color selectedExamText = (Color) selectedExamLabel.getTextFill();
		assertTrue(providerText.getBrightness() < 0.65, "Provider label has insufficient contrast: " + providerText);
		assertTrue(selectedExamText.getBrightness() < 0.65,
				"Selected Exam label has insufficient contrast: " + selectedExamText);
	}

	@Test
	@SuppressWarnings("unchecked")
	void incompleteBookletCannotBeConfirmedReviewed(FxRobot robot) {
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableColumn<BookletCorpusStatus, Boolean> reviewedColumn = booklets.getColumns().stream()
				.filter(column -> "Applicability Reviewed".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, Boolean>) column).findFirst().orElseThrow();
		robot.interact(() -> booklets.scrollToColumn(reviewedColumn));
		WaitForAsyncUtils.waitForFxEvents();

		// Paper 1 still lacks one required MCQ explanation.
		CheckBox checkBox = robot.lookup("#corpus-dashboard-applicability-reviewed-20").queryAs(CheckBox.class);
		assertFalse(checkBox.isSelected());
		assertTrue(checkBox.isDisable());
		assertTrue(checkBox.getTooltip().getText().contains("classification"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void inspectQuestionsRoutesSelectedExamAndBookletScope(FxRobot robot) {
		AtomicReference<Exam> routedExam = new AtomicReference<>();
		AtomicReference<ExamBooklet> routedBooklet = new AtomicReference<>();
		robot.interact(() -> pane.setQuestionInspectionHandler((exam, booklet) -> {
			routedExam.set(exam);
			routedBooklet.set(booklet);
		}));
		Button inspectExam = robot.lookup("#corpus-dashboard-inspect-exam-questions").queryButton();
		Button inspectBooklet = robot.lookup("#corpus-dashboard-inspect-booklet-questions").queryButton();
		Button manageExamAssets = robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton();
		Button captureQuestions = robot.lookup("#corpus-dashboard-capture-questions").queryButton();
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);

		// The Dashboard automatically selects the first visible Exam. Inspection is
		// read-only, so COMPLETE lifecycle does not disable the Exam-level route.
		assertFalse(inspectExam.isDisable());
		assertTrue(inspectBooklet.isDisable());
		assertEquals(manageExamAssets.getParent(), inspectExam.getParent());
		robot.interact(inspectExam::fire);
		assertEquals(fixture.completeExam.getId(), routedExam.get().getId());
		assertNull(routedBooklet.get());

		// Selecting a booklet exposes a separate booklet-level inspection route in
		// the same action row as booklet capture.
		routedExam.set(null);
		robot.interact(() -> booklets.getSelectionModel().select(fixture.paper2Status));
		assertFalse(inspectBooklet.isDisable());
		assertEquals(captureQuestions.getParent(), inspectBooklet.getParent());
		robot.interact(inspectBooklet::fire);
		assertEquals(fixture.completeExam.getId(), routedExam.get().getId());
		assertEquals(fixture.paper2.getId(), routedBooklet.get().getId());

		// Clearing Exam structure leaves neither inspection action with a valid target.
		robot.interact(() -> pane.replaceData(List.of(), List.of()));
		assertTrue(inspectExam.isDisable());
		assertTrue(inspectBooklet.isDisable());
	}

	@Test
	void legacyQuestionImportIsSubjectLevelAndRequiresCurriculum(FxRobot robot) {
		AtomicReference<Boolean> importCalled = new AtomicReference<>(Boolean.FALSE);
		robot.interact(() -> pane.setLegacyQuestionImportHandler(() -> importCalled.set(Boolean.TRUE)));
		Button importLegacy = robot.lookup("#corpus-dashboard-import-legacy-questions").queryButton();

		// Populated Subjects keep legacy intake on the existing Exam filter row rather
		// than reserving another complete Dashboard row.
		assertEquals("Import Legacy", importLegacy.getText());
		assertEquals("corpus-dashboard-exam-filter-row", importLegacy.getParent().getId());
		assertFalse(importLegacy.isDisable());
		robot.interact(importLegacy::fire);
		assertTrue(importCalled.get().booleanValue());
		robot.interact(() -> {

			// Empty Subject onboarding reuses the same action beside Add Exam.
			pane.replaceData(List.of(), List.of(), List.of(), false, -1L);
		});
		assertTrue(importLegacy.isVisible());
		assertTrue(importLegacy.isManaged());
		assertEquals("corpus-dashboard-exam-empty-state", importLegacy.getParent().getId());

		// The workbook classification cannot be resolved without an authoritative
		// curriculum version for this Subject.
		assertTrue(importLegacy.isDisable());
	}

	@Test
	void lifecycleBusyStateShowsProgressAndPreventsDuplicateAction(FxRobot robot) {
		AtomicReference<Exam> routedExam = new AtomicReference<>();
		robot.interact(() -> pane.setExamLifecycleHandler((exam, _) -> routedExam.set(exam)));
		Button lifecycle = robot.lookup("#corpus-dashboard-exam-lifecycle").queryButton();
		HBox progressRow = robot.lookup("#corpus-dashboard-exam-lifecycle-progress").queryAs(HBox.class);
		ProgressIndicator progress = robot.lookup("#corpus-dashboard-exam-lifecycle-progress-indicator")
				.queryAs(ProgressIndicator.class);
		Label progressLabel = robot.lookup("#corpus-dashboard-exam-lifecycle-progress-label").queryAs(Label.class);

		// The initially selected COMPLETE Exam is normally available for reactivation.
		assertEquals("Mark Active", lifecycle.getText());
		assertFalse(lifecycle.isDisable());
		assertFalse(progressRow.isVisible());
		assertFalse(progressRow.isManaged());
		robot.interact(() -> pane.setExamLifecycleChangeInProgress(true));

		// Busy feedback is visible inside Question Work and the lifecycle command is
		// locked before another persistence request can be issued.
		assertTrue(progressRow.isVisible());
		assertTrue(progressRow.isManaged());
		assertTrue(progress.isVisible());
		assertEquals("Updating Exam state...", progressLabel.getText());
		assertTrue(lifecycle.isDisable());
		robot.interact(lifecycle::fire);
		assertNull(routedExam.get());
		robot.interact(() -> pane.setExamLifecycleChangeInProgress(false));

		// Clearing busy state restores the normal lifecycle rule for the unchanged
		// selected Exam.
		assertFalse(progressRow.isVisible());
		assertFalse(progressRow.isManaged());
		assertFalse(lifecycle.isDisable());
		robot.interact(lifecycle::fire);
		assertNotNull(routedExam.get());
		assertEquals(fixture.completeExam.getId(), routedExam.get().getId());
	}

	@Test
	@SuppressWarnings("unchecked")
	void manageExamAssetsRequiresSelectedActiveExam(FxRobot robot) {
		AtomicReference<Exam> routedExam = new AtomicReference<>();
		AtomicReference<ExamBooklet> routedBooklet = new AtomicReference<>();
		robot.interact(() -> pane.setExamAssetsHandler((exam, booklet) -> {
			routedExam.set(exam);
			routedBooklet.set(booklet);
		}));
		Button manageAssets = robot.lookup("#corpus-dashboard-manage-exam-assets").queryButton();
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);

		// The initially selected Exam is COMPLETE. Structural Exam/asset management
		// remains locked until the user deliberately marks that Exam ACTIVE again.
		assertTrue(manageAssets.isDisable());
		robot.interact(manageAssets::fire);
		assertNull(routedExam.get());
		assertNull(routedBooklet.get());
		robot.interact(() -> pane.replaceData(List.of(fixture.activeExamStatus), List.of()));

		// An ACTIVE Exam remains manageable regardless of whether its audit currently
		// reports structural findings.
		assertFalse(manageAssets.isDisable());
		robot.interact(manageAssets::fire);
		assertEquals(fixture.activeExam.getId(), routedExam.get().getId());
		assertNull(routedBooklet.get());
		routedExam.set(null);
		routedBooklet.set(null);
		robot.interact(() -> booklets.getSelectionModel().select(fixture.activePaperStatus));

		// Explicit booklet selection is preserved when routing the ACTIVE Exam into the
		// existing Exam / Assets workspace.
		robot.interact(manageAssets::fire);
		assertEquals(fixture.activeExam.getId(), routedExam.get().getId());
		assertEquals(fixture.activePaper.getId(), routedBooklet.get().getId());
	}

	@Test
	@SuppressWarnings("unchecked")
	void missingExpectedBookletCountShowsActionableWarning(FxRobot robot) {
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		Label warning = robot.lookup("#corpus-dashboard-booklet-warning").queryAs(Label.class);
		ExamBooklet unknownBooklet = new ExamBooklet(90, fixture.activeExam, "Unknown expectations",
				new SourceDocument(91, "Chemistry/2024/unknown.pdf"), ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		BookletCorpusStatus unknown = new BookletCorpusStatus(unknownBooklet, true, null, 0, 0, 0,
				new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.of(BookletCorpusFinding.MISSING_EXPECTED_TOP_LEVEL_QUESTION_COUNT));
		robot.interact(() -> {
			booklets.getItems().setAll(unknown);
			booklets.getSelectionModel().select(unknown);
		});

		// A problem indicator must have an actionable explanation.
		assertTrue(warning.isVisible());
		assertTrue(warning.getText().contains("Expected top-level Question count has not been recorded"));
		assertTrue(warning.getText().contains("Manage Exam / Assets"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void missingMcqExplanationsAreReportedAndRoutedAsSeparateExamWork(FxRobot robot) {
		AtomicReference<Question> routedQuestion = new AtomicReference<>();
		robot.interact(() -> pane.setMcqExplanationCaptureHandler(routedQuestion::set));
		Button missingExplanations = robot.lookup("#corpus-dashboard-summary-missing-mcq-explanations").queryButton();
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);

		// Subject-level explanation reporting is one actionable missing-work count.
		// Detailed captured/eligible coverage belongs to the booklet hierarchy.
		assertEquals("1 Missing MCQ explanations", missingExplanations.getText());
		assertTrue(robot.lookup("#corpus-dashboard-summary-mcq-explanations").tryQuery().isEmpty());
		TableColumn<BookletCorpusStatus, String> explanationColumn = booklets.getColumns().stream()
				.filter(column -> "MCQ explanations".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();

		// Before drill-down, booklet coverage identifies where explanation work
		// remains.
		assertEquals("1 / 2", explanationColumn.getCellData(fixture.paper1Status));
		robot.interact(missingExplanations::fire);

		// Explanation drill-down narrows the complete structural hierarchy to the Exam
		// and booklet that own the outstanding explanation work.
		assertEquals(1, exams.getItems().size());
		assertEquals(fixture.completeExam.getId(), exams.getItems().getFirst().exam().getId());
		assertEquals(1, booklets.getItems().size());
		assertEquals(fixture.paper1.getId(), booklets.getItems().getFirst().booklet().getId());
		TableColumn<BookletCorpusStatus, String> bookletProblemColumn = booklets.getColumns().stream()
				.filter(column -> "Problems".equals(column.getText()))
				.map(column -> (TableColumn<BookletCorpusStatus, String>) column).findFirst().orElseThrow();

		// Question Booklets indicates outstanding work without repeating
		// the detailed problem description shown in Question Work.
		assertEquals("Yes", bookletProblemColumn.getCellData(booklets.getItems().getFirst()));
		assertEquals(1, questions.getItems().size());
		assertEquals("Q1", questions.getItems().getFirst().question().getQuestionCode());
		TableColumn<QuestionCorpusWorkItem, String> problemColumn = questions.getColumns().stream()
				.filter(column -> "Problem".equals(column.getText()))
				.map(column -> (TableColumn<QuestionCorpusWorkItem, String>) column).findFirst().orElseThrow();
		assertEquals("Missing MCQ explanation", problemColumn.getCellData(questions.getItems().getFirst()));
		Button selectedAction = robot.lookup("#corpus-dashboard-complete-answer").queryButton();

		// Selecting the work item changes the Answer-side action to the explanation
		// task and enables the exact-Question route.
		robot.interact(() -> questions.getSelectionModel().selectFirst());
		assertEquals("Capture Explanation", selectedAction.getText());
		assertFalse(selectedAction.isDisabled());

		// Selected-Question entry routes the exact missing explanation Question.
		robot.interact(selectedAction::fire);
		assertNotNull(routedQuestion.get());
		assertEquals("Q1", routedQuestion.get().getQuestionCode());
		routedQuestion.set(null);
		robot.interact(() -> booklets.getSelectionModel().selectFirst());
		Button bookletAction = robot.lookup("#corpus-dashboard-capture-mcq-explanations").queryButton();
		assertFalse(bookletAction.isDisabled());

		// Booklet-level entry begins at the first missing MCQ and lets the existing
		// retrofit workflow advance from there.
		robot.interact(bookletAction::fire);
		assertNotNull(routedQuestion.get());
		assertEquals("Q1", routedQuestion.get().getQuestionCode());
	}

	@Test
	@SuppressWarnings("unchecked")
	void missingQuestionContentUsesExplicitDashboardWording(FxRobot robot) {
		Button missingContentSummary = robot.lookup("#corpus-dashboard-summary-missing-content").queryButton();
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);

		// The summary must describe the missing part of an existing Question rather
		// than implying that the Question record itself is absent.
		assertEquals("1 Missing question content", missingContentSummary.getText());
		QuestionCorpusWorkItem missingContent = questions.getItems().stream()
				.filter(item -> "Q7".equals(item.question().getQuestionCode())).findFirst().orElseThrow();
		TableColumn<QuestionCorpusWorkItem, ?> problemColumn = questions.getColumns().stream()
				.filter(column -> "Problem".equals(column.getText())).findFirst().orElseThrow();

		// Question Work uses the same explicit terminology while retaining any other
		// independent problems reported for the row.
		String problemText = String.valueOf(problemColumn.getCellObservableValue(missingContent).getValue());
		assertTrue(problemText.contains("Missing question content"));
		assertTrue(problemText.contains("Missing answer"));
	}

	@Test
	void narrowDashboardRetainsScrollableControls(FxRobot robot) {
		ScrollPane filters = robot.lookup("#corpus-dashboard-exam-filters-scroll").queryAs(ScrollPane.class);
		ScrollPane examActions = robot.lookup("#corpus-dashboard-exam-actions-scroll").queryAs(ScrollPane.class);
		ScrollPane bookletActions = robot.lookup("#corpus-dashboard-booklet-actions-scroll").queryAs(ScrollPane.class);
		Button legacyImport = robot.lookup("#corpus-dashboard-import-legacy-questions").queryButton();
		Button lifecycle = robot.lookup("#corpus-dashboard-exam-lifecycle").queryButton();
		Button captureMcq = robot.lookup("#corpus-dashboard-capture-mcq-explanations").queryButton();

		// Wrapping the rows must preserve their existing button containers.
		assertEquals(filters.getContent(), legacyImport.getParent());
		assertEquals(examActions.getContent(), lifecycle.getParent());
		assertEquals(bookletActions.getContent(), captureMcq.getParent());
		robot.interact(() -> pane.getScene().getWindow().setWidth(820));
		WaitForAsyncUtils.waitForFxEvents();
		for (ScrollPane scroll : List.of(filters, examActions, bookletActions)) {
			assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scroll.getHbarPolicy());
			assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getVbarPolicy());

			// There must be a real viewport and more content width than fits
			// within it. This verifies that the controls remain scrollable.
			assertTrue(scroll.getViewportBounds().getWidth() > 0, scroll.getId());
			assertTrue(scroll.getContent().getLayoutBounds().getWidth() > scroll.getViewportBounds().getWidth() + 5,
					scroll.getId() + " should require horizontal scrolling");
			robot.interact(() -> scroll.setHvalue(scroll.getHmax()));
			WaitForAsyncUtils.waitForFxEvents();
			assertEquals(scroll.getHmax(), scroll.getHvalue(), 0.001);
		}

		// Selected-scope descriptions must wrap rather than force the
		// hierarchy to expand beyond the available width.
		Label examCounts = robot.lookup("#corpus-dashboard-selected-exam-counts").queryAs(Label.class);
		Label selectedBooklet = robot.lookup("#corpus-dashboard-selected-booklet").queryAs(Label.class);
		assertTrue(examCounts.isWrapText());
		assertTrue(selectedBooklet.isWrapText());
	}

	@Test
	@SuppressWarnings("unchecked")
	void needsWorkReflectsAuthoritativeExamCompletion(FxRobot robot) {
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		ExamCorpusStatus unknownCounts = new ExamCorpusStatus(fixture.activeExam,
				new ExamAssetExpectations(null, 0, null, 0), List.of(), new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		ExamCorpusStatus missingBooklets = new ExamCorpusStatus(fixture.activeExam,
				new ExamAssetExpectations(1, 0, 0, 0), List.of(), new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		ExamCorpusStatus excessBooklets = new ExamCorpusStatus(fixture.completeExam,
				new ExamAssetExpectations(1, 2, 1, 1), fixture.completeExamStatus.bookletStatuses(),
				fixture.completeExamStatus.questionSummary(), fixture.completeExamStatus.mcqExplanationSummary(),
				fixture.completeExamStatus.findings());
		BookletCorpusStatus readyBooklet = new BookletCorpusStatus(fixture.activePaper, true, null, 10, 10, 0,
				new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus exactCounts = new ExamCorpusStatus(fixture.activeExam, new ExamAssetExpectations(1, 1, 0, 0),
				List.of(readyBooklet), new QuestionCorpusSummary(10, 10, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));

		// The Needs Work column must reflect structural incompleteness
		// even when no Questions have been captured.
		robot.interact(() -> pane.replaceData(List.of(unknownCounts, missingBooklets), List.of()));
		TableColumn<ExamCorpusStatus, String> needsWork = exams.getColumns().stream()
				.filter(column -> "Needs Work".equals(column.getText()))
				.map(column -> (TableColumn<ExamCorpusStatus, String>) column).findFirst().orElseThrow();
		assertEquals("Yes", needsWork.getCellData(unknownCounts));
		assertEquals("Yes", needsWork.getCellData(missingBooklets));
		robot.interact(() -> pane.replaceData(List.of(excessBooklets), fixture.questions()));
		assertEquals("Yes", needsWork.getCellData(excessBooklets));

		// Matching asset counts do not eliminate outstanding
		// capture or classification requirements.
		robot.interact(() -> pane.replaceData(List.of(fixture.completeExamStatus), fixture.questions()));
		assertFalse(fixture.completeExamStatus.isReadyForCompletion());
		assertEquals("Yes", needsWork.getCellData(fixture.completeExamStatus));

		// Corpus completion alone no longer closes all operational work.
		robot.interact(() -> pane.replaceData(List.of(exactCounts), List.of()));
		assertTrue(exactCounts.isReadyForCompletion());
		assertEquals("Yes", needsWork.getCellData(exactCounts));
		robot.interact(() -> pane.setApplicabilityReviewState(Set.of(fixture.activePaper.getId())));

		// Explicit applicability review closes the final work dimension.
		assertEquals("—", needsWork.getCellData(exactCounts));
	}

	@Test
	@SuppressWarnings("unchecked")
	void questionWorkShowsSourceColumnsAndNaturalSourceSort(FxRobot robot) {
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);

		// Every Question Work row exposes the source hierarchy needed to distinguish
		// identical Question codes belonging to different Exams or booklets.
		assertEquals(
				List.of("Provider", "Year", "Exam", "Booklet", "Question", "Type", "Content", "Answer",
						"Shared Context", "Problem"),
				questions.getColumns().stream().map(TableColumn::getText).toList());

		// Dashboard default sorting preserves complete source hierarchy before
		// comparing
		// Question codes.
		assertEquals(List.of("Provider", "Year", "Exam", "Booklet", "Question"),
				questions.getSortOrder().stream().map(TableColumn::getText).toList());
		TableColumn<QuestionCorpusWorkItem, String> questionColumn = (TableColumn<QuestionCorpusWorkItem, String>) questions
				.getColumns().get(4);

		// The visible Question column uses numeric-aware ordering with alphabetic parts
		// rather than ordinary lexical String ordering.
		assertTrue(questionColumn.getComparator().compare("1", "10") < 0);
		assertTrue(questionColumn.getComparator().compare("10", "21") < 0);
		assertTrue(questionColumn.getComparator().compare("21", "22") < 0);
		assertTrue(questionColumn.getComparator().compare("22", "22a") < 0);
		assertTrue(questionColumn.getComparator().compare("22a", "22b") < 0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void scopeChangeClearsQuestionSelectionBeforeRebuildingWork(FxRobot robot) {
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);
		ComboBox<QuestionCorpusCompletionFilter> view = robot.lookup("#corpus-dashboard-question-view")
				.queryAs(ComboBox.class);
		Button completeQuestion = robot.lookup("#corpus-dashboard-complete-question").queryButton();
		robot.interact(() -> {
			view.setValue(QuestionCorpusCompletionFilter.ALL);
			booklets.getSelectionModel().select(fixture.paper2Status);
		});
		QuestionCorpusWorkItem missingContent = questions.getItems().stream()
				.filter(item -> "Q7".equals(item.question().getQuestionCode())).findFirst().orElseThrow();
		robot.interact(() -> questions.getSelectionModel().select(missingContent));
		assertFalse(completeQuestion.isDisable());

		// Paper 1 also has a visible Question in ALL mode. This reproduces the original
		// defect: carrying the selected row index into the new scope could make an
		// action
		// target a Question the teacher never selected.
		robot.interact(() -> booklets.getSelectionModel().select(fixture.paper1Status));
		assertNull(questions.getSelectionModel().getSelectedItem());
		assertTrue(completeQuestion.isDisable());
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

		// Dashboard tests include representative mapping-review work beside ordinary
		// Exam and Question audit state.
		pane = new CorpusDashboardPane(fixture.chemistry, List.of(fixture.completeExamStatus, fixture.activeExamStatus),
				fixture.questions(), List.of(fixture.mappingCoverage));
		stage.setScene(new Scene(pane, 1100, 850));
		stage.show();
	}

	@Test
	@SuppressWarnings("unchecked")
	void subjectSummaryDrillDownNarrowsExamBookletAndQuestionRows(FxRobot robot) {
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		TableView<BookletCorpusStatus> booklets = robot.lookup("#corpus-dashboard-booklets").queryAs(TableView.class);
		TableView<QuestionCorpusWorkItem> questions = robot.lookup("#corpus-dashboard-question-work")
				.queryAs(TableView.class);
		Button missingAnswers = robot.lookup("#corpus-dashboard-summary-missing-answer").queryButton();
		String subjectCount = missingAnswers.getText();

		// The Subject-level Missing Answers summary must narrow every lower hierarchy
		// level to records that actually contribute to that count.
		robot.interact(missingAnswers::fire);
		assertTrue(exams.getItems().stream().allMatch(status -> status.questionSummary().missingAnswer() > 0));
		assertTrue(booklets.getItems().stream().allMatch(status -> status.questionSummary().missingAnswer() > 0));
		assertTrue(questions.getItems().stream()
				.allMatch(item -> item.status().hasProblem(QuestionCorpusProblem.MISSING_ANSWER)));

		// Drill-down must not rewrite its own Subject-level summary count.
		assertEquals(subjectCount, missingAnswers.getText());
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

	@Test
	@SuppressWarnings("unchecked")
	void totalQuestionsRestoresEmptyExamAfterSummaryDrillDown(FxRobot robot) {
		ExamCorpusStatus emptyExam = new ExamCorpusStatus(fixture.activeExam,
				new ExamAssetExpectations(null, 0, null, 0), List.of(), new QuestionCorpusSummary(0, 0, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));
		TableView<ExamCorpusStatus> exams = robot.lookup("#corpus-dashboard-exams").queryAs(TableView.class);
		Button missingAnswers = robot.lookup("#corpus-dashboard-summary-missing-answer").queryButton();
		Button needsAttention = robot.lookup("#corpus-dashboard-summary-attention").queryButton();
		Button totalQuestions = robot.lookup("#corpus-dashboard-summary-total").queryButton();

		// One Exam has captured Questions and another has no
		// Question or booklet records.
		robot.interact(() -> pane.replaceData(List.of(fixture.completeExamStatus, emptyExam), fixture.questions()
				.stream().filter(question -> question.getExam().getId() == fixture.completeExam.getId()).toList()));
		assertEquals(2, exams.getItems().size());
		robot.interact(missingAnswers::fire);
		assertEquals(1, exams.getItems().size());
		assertEquals(fixture.completeExam.getId(), exams.getItems().getFirst().exam().getId());
		robot.interact(totalQuestions::fire);

		// All Questions must restore the structural Exam
		// hierarchy, including the Exam with no Questions.
		assertEquals(2, exams.getItems().size());
		assertTrue(exams.getItems().stream().anyMatch(status -> status.exam().getId() == fixture.activeExam.getId()));
		robot.interact(needsAttention::fire);
		assertEquals(1, exams.getItems().size());
		robot.interact(totalQuestions::fire);
		assertEquals(2, exams.getItems().size());
		assertTrue(exams.getItems().stream().anyMatch(status -> status.exam().getId() == fixture.activeExam.getId()));
	}

	@Test
	void unsetExpectedCountsAreShownExplicitly(FxRobot robot) {
		ExamCorpusStatus unsetExpectations = new ExamCorpusStatus(fixture.completeExam,
				new ExamAssetExpectations(null, 2, null, 1), fixture.completeExamStatus.bookletStatuses(),
				fixture.completeExamStatus.questionSummary(), fixture.completeExamStatus.mcqExplanationSummary(),
				EnumSet.noneOf(ExamCorpusFinding.class));
		robot.interact(
				() -> pane.replaceData(List.of(unsetExpectations, fixture.activeExamStatus), fixture.questions()));
		Label selectedCounts = robot.lookup("#corpus-dashboard-selected-exam-counts").queryAs(Label.class);

		// Missing planning metadata must be distinguishable from a genuine zero count
		// and from the dash used to represent no ordinary work.
		assertTrue(selectedCounts.getText().contains("Question booklets: 2 / not recorded"));
		assertTrue(selectedCounts.getText().contains("Answer booklets: 1 / not recorded"));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void assertCentredColumns(TableView<?> table) {
		for (TableColumn column : table.getColumns()) {
			TableCell cell = (TableCell) column.getCellFactory().call(column);

			// A column without the centred factory would retain JavaFX's ordinary
			// left-aligned data-cell presentation.
			assertEquals(Pos.CENTER, cell.getAlignment(), table.getId() + " — " + column.getText());
		}
	}

	private TableRow<?> dashboardRowAt(TableView<?> table, int index) {
		return table.lookupAll(".table-row-cell").stream()
				.filter(node -> node instanceof TableRow<?> row && row.getIndex() == index && !row.isEmpty())
				.map(node -> (TableRow<?>) node).findFirst().orElseThrow();
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
		private final BookletCorpusStatus paper1Status = new BookletCorpusStatus(paper1, true, markingGuide, 20, 20, 0,
				new QuestionCorpusSummary(20, 20, 0, 0, 0, 0, 0), new McqExplanationCoverage(true, 2, 1),
				EnumSet.noneOf(BookletCorpusFinding.class));
		private final BookletCorpusStatus paper2Status = new BookletCorpusStatus(paper2, true, markingGuide, 29, 34, 3,
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
		private final BookletCorpusStatus activePaperStatus = new BookletCorpusStatus(activePaper, true, null, 8, 8, 0,
				new QuestionCorpusSummary(8, 8, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.of(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));
		private final ExamCorpusStatus activeExamStatus = new ExamCorpusStatus(activeExam,
				new ExamAssetExpectations(2, 1, 0, 0), List.of(activePaperStatus),
				new QuestionCorpusSummary(8, 8, 0, 0, 0, 0, 0), new McqExplanationSummary(0, 0, 0),
				EnumSet.of(ExamCorpusFinding.EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH));

		// Mapping coverage deliberately contains unresolved work so the Dashboard test
		// can prove that it remains separate from ordinary Question completeness.
		private final SyllabusVersion historicalSyllabus = new SyllabusVersion(400, chemistry, "2019", false);
		private final SyllabusVersion currentSyllabus = new SyllabusVersion(401, chemistry, "2025", true);
		private final CurriculumMappingCoverage mappingCoverage = new CurriculumMappingCoverage(historicalSyllabus,
				currentSyllabus, new CurriculumMappingLevelCoverage(CurriculumLevel.DESCRIPTOR, 10, 6, 1, 2, 1, 0),
				new CurriculumMappingLevelCoverage(CurriculumLevel.SUBTOPIC, 5, 3, 1, 1, 0, 2));

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
