package au.edu.eq.questionbank.ui.audit;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.QuestionSourceOrder;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.audit.BookletCorpusFinding;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.McqExplanationCoverage;
import au.edu.eq.questionbank.service.audit.QuestionCorpusAudit;
import au.edu.eq.questionbank.service.audit.QuestionCorpusCompletionFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusQueue;
import au.edu.eq.questionbank.service.audit.QuestionCorpusSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverage;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingLevelCoverage;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Displays the operational Exam -> booklet -> Question Corpus Dashboard.
 */
public final class CorpusDashboardPane extends VBox {

	private static final double SPACING = 8.0;
	private static final Insets PADDING = new Insets(10);
	private static final double EXAM_TABLE_HEIGHT = 190.0;
	private static final double BOOKLET_TABLE_HEIGHT = 190.0;
	private static final double QUESTION_TABLE_HEIGHT = 180.0;

	// Booklet scope must retain at least several immediately visible rows even when
	// the dialog is vertically constrained.
	private static final double BOOKLET_TABLE_MAX_HEIGHT = 280.0;
	private static final double BOOKLET_TABLE_MIN_HEIGHT = 125.0;
	private final Subject workingSubject;
	private List<ExamCorpusStatus> examStatuses;
	private List<Question> questions;
	private final ComboBox<ExamProvider> providerBox = new ComboBox<>();
	private final ComboBox<Integer> yearBox = new ComboBox<>();
	private final ComboBox<ExamCaptureState> examStateBox = new ComboBox<>();
	private final Button clearFiltersButton = new Button("Clear");
	private final Button totalQuestionsButton = new Button();
	private final Button needsAttentionButton = new Button();
	private final Button missingContentButton = new Button();
	private final Button missingAnswerButton = new Button();
	private final Button unknownTypeButton = new Button();
	private final Button sharedContextButton = new Button();
	private final TableView<ExamCorpusStatus> examTable = new TableView<>();
	private final Label selectedExamLabel = new Label();
	private final Label declaredExamStateLabel = new Label();
	private final Label selectedExamCountsLabel = new Label();
	private final TableView<BookletCorpusStatus> bookletTable = new TableView<>();
	private final Label selectedBookletLabel = new Label();
	private final Label bookletWarningLabel = new Label();
	private final ComboBox<QuestionCorpusCompletionFilter> questionViewBox = new ComboBox<>();
	private final Label questionResultCountLabel = new Label();
	private final TableView<QuestionCorpusWorkItem> questionTable = new TableView<>();
	private final Button selectAllUnknownButton = new Button("Select all unknown shown");
	private final Button setSelectedMultipleChoiceButton = new Button("Set selected: Multiple choice");
	private final Button setSelectedWrittenResponseButton = new Button("Set selected: Written");
	private final Label mcqExplanationCoverageLabel = new Label();
	private QuestionCorpusProblem selectedQuestionProblem;
	private boolean changingScopeFilters;
	private boolean changingQuestionFilter;
	private BiConsumer<List<Question>, QuestionResponseType> bulkResponseTypeHandler = (_, _) -> {
	};
	private final Button manageExamAssetsButton = new Button("Manage Exam / Assets");
	private BiConsumer<Exam, ExamBooklet> examAssetsHandler = (_, _) -> {
	};
	private Consumer<Question> answerCaptureHandler = _ -> {
	};
	private final Button captureQuestionsButton = new Button("Capture Questions");
	private final Button captureAnswersButton = new Button("Capture Answers");
	private final Button completeSelectedQuestionButton = new Button("Complete Selected Question");
	private Consumer<ExamBooklet> newQuestionCaptureHandler = _ -> {
	};
	private Consumer<Question> questionCorrectionHandler = _ -> {
	};
	private final Button addExamButton = new Button("Add Exam");
	private Runnable addExamHandler = () -> {
	};
	private HBox examEmptyStateRow;
	private VBox examOperationalContent;
	private final Label noExamsLabel = new Label("No Exams have been added.");
	private StackPane bookletsSection;
	private StackPane questionWorkSection;
	private final Button completeSelectedAnswerButton = new Button("Complete Selected Answer");

	// Curriculum mapping review is a Subject-level reporting dimension independent
	// of ordinary Question and Exam completeness.
	private List<CurriculumMappingCoverage> mappingCoverages;
	private final Label curriculumMappingSummaryLabel = new Label();
	private boolean summaryQuestionFilterActive;
	private final Button addCurriculumButton = new Button("+");
	private Runnable addCurriculumHandler = () -> {
	};
	private final Button mapCurriculumButton = new Button("Map Curriculum");
	private Runnable mapCurriculumHandler = () -> {
	};
	private boolean curriculumAvailable;

	// Legacy metadata intake belongs to the Working Subject rather than to one
	// selected Exam or booklet.
	private final Button importLegacyQuestionsButton = new Button("Import Legacy");
	private Runnable legacyQuestionImportHandler = () -> {
	};
	private HBox examFilterRow;
	private final Button examLifecycleButton = new Button("Mark Complete");
	private BiConsumer<Exam, ExamCaptureState> examLifecycleHandler = (_, _) -> {
	};
	private final Button captureMcqExplanationsButton = new Button("Capture MCQ Explanations");
	private Consumer<Question> mcqExplanationCaptureHandler = _ -> {
	};
	private boolean mcqExplanationFilterActive;
	private final Button missingMcqExplanationButton = new Button();

	public CorpusDashboardPane(Subject workingSubject, List<ExamCorpusStatus> examStatuses, List<Question> questions,
			List<CurriculumMappingCoverage> mappingCoverages) {

		// Existing callers predate explicit curriculum-presence reporting and therefore
		// retain their previous non-empty curriculum presentation.
		this(workingSubject, examStatuses, questions, mappingCoverages, true);
	}

	/**
	 * Creates a Subject-scoped operational Dashboard from one persistence snapshot.
	 *
	 * @param workingSubject      authoritative application Subject
	 * @param examStatuses        structural Exam audit state
	 * @param questions           current Question corpus
	 * @param mappingCoverages    curriculum mapping-review coverage
	 * @param curriculumAvailable whether at least one curriculum version exists for
	 *                            the Subject
	 */
	public CorpusDashboardPane(Subject workingSubject, List<ExamCorpusStatus> examStatuses, List<Question> questions,
			List<CurriculumMappingCoverage> mappingCoverages, boolean curriculumAvailable) {
		if (workingSubject == null) {
			throw new NullPointerException("workingSubject");
		}

		// Working Subject remains authoritative application state for calculated Exam,
		// Question and curriculum-mapping snapshots.
		this.workingSubject = workingSubject;
		this.examStatuses = statusesForWorkingSubject(examStatuses);
		this.questions = questionsForWorkingSubject(questions);
		this.mappingCoverages = mappingCoveragesForWorkingSubject(mappingCoverages);
		this.curriculumAvailable = curriculumAvailable;
		configureControls();
		configureActions();
		buildContent();
		populateFilterOptions();
		refreshDashboard();
	}

	CorpusDashboardPane(Subject workingSubject, List<ExamCorpusStatus> examStatuses, List<Question> questions) {

		// Existing callers without mapping information retain an explicit
		// not-applicable mapping-review state.
		this(workingSubject, examStatuses, questions, List.of());
	}

	/**
	 * Replaces the complete persistence-derived Dashboard generation while
	 * retaining compatible local filters and selection.
	 *
	 * @param updatedStatuses            refreshed Exam audit state
	 * @param updatedQuestions           refreshed Question corpus
	 * @param updatedMappingCoverages    refreshed curriculum mapping coverage
	 * @param updatedCurriculumAvailable whether the Subject has at least one
	 *                                   curriculum version
	 * @param preferredQuestionId        Question to restore when still visible, or
	 *                                   a non-positive value for no preferred
	 *                                   Question
	 */
	public void replaceData(List<ExamCorpusStatus> updatedStatuses, List<Question> updatedQuestions,
			List<CurriculumMappingCoverage> updatedMappingCoverages, boolean updatedCurriculumAvailable,
			long preferredQuestionId) {
		Long selectedExamId = selectedExamId();
		Long selectedBookletId = selectedBookletId();
		Long providerId = providerBox.getValue() == null ? null : providerBox.getValue().getId();
		Integer year = yearBox.getValue();
		ExamCaptureState examState = examStateBox.getValue();

		// Replace all Subject-level reporting snapshots from the same persistence
		// generation without allowing mapping status to alter corpus completeness.
		examStatuses = statusesForWorkingSubject(updatedStatuses);
		questions = questionsForWorkingSubject(updatedQuestions);
		mappingCoverages = mappingCoveragesForWorkingSubject(updatedMappingCoverages);
		curriculumAvailable = updatedCurriculumAvailable;
		changingScopeFilters = true;
		try {
			populateFilterOptions();
			providerBox.setValue(providerId == null ? null
					: providerBox.getItems().stream().filter(provider -> provider.getId() == providerId.longValue())
							.findFirst().orElse(null));
			yearBox.setValue(year != null && yearBox.getItems().contains(year) ? year : null);
			examStateBox.setValue(examState != null && examStateBox.getItems().contains(examState) ? examState : null);
		} finally {
			changingScopeFilters = false;
		}

		// Exam creation depends on Subject curriculum, so refresh that gate from the
		// same generation before rebuilding the structural hierarchy.
		updateAddExamActionState();
		refreshSummaryControls();
		refreshCurriculumMappingSummary();
		refreshExamTable(selectedExamId, selectedBookletId);
		refreshQuestionWork();
		if (preferredQuestionId > 0) {

			// Restore a corrected Question only when it remains visible under the
			// Dashboard's current Exam, booklet and work filters.
			QuestionCorpusWorkItem preferred = questionTable.getItems().stream()
					.filter(item -> item.question().getId() == preferredQuestionId).findFirst().orElse(null);
			if (preferred != null) {
				questionTable.getSelectionModel().select(preferred);
				questionTable.scrollTo(preferred);
			}
		}
	}

	public void replaceData(List<ExamCorpusStatus> updatedStatuses, List<Question> updatedQuestions,
			List<CurriculumMappingCoverage> updatedMappingCoverages, long preferredQuestionId) {

		// Callers that do not publish curriculum-presence information retain the
		// current
		// value from the Dashboard generation already on screen.
		replaceData(updatedStatuses, updatedQuestions, updatedMappingCoverages, curriculumAvailable,
				preferredQuestionId);
	}

	public void setAddCurriculumHandler(Runnable handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Curriculum creation remains application-owned because it opens the existing
		// authoring workflow and refreshes authoritative persistence afterwards.
		addCurriculumHandler = handler;
	}

	/**
	 * Supplies the application-owned route for creating the first Exam for the
	 * current Subject.
	 *
	 * @param handler operation that opens the normal Exam / Assets New Exam
	 *                workflow
	 */
	public void setAddExamHandler(Runnable handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Dashboard onboarding delegates to Exam / Assets rather than introducing a
		// second Exam-creation implementation.
		addExamHandler = handler;
	}

	public void setAnswerCaptureHandler(Consumer<Question> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Answer capture remains application-owned because it coordinates the shared
		// PDF workspace and capture lifecycle.
		answerCaptureHandler = handler;
	}

	public void setBulkResponseTypeHandler(BiConsumer<List<Question>, QuestionResponseType> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Bulk UNKNOWN resolution remains an existing operational Dashboard feature.
		bulkResponseTypeHandler = handler;
	}

	public void setExamAssetsHandler(BiConsumer<Exam, ExamBooklet> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Structural correction is owned by the existing Exam/Assets workspace.
		examAssetsHandler = handler;
	}

	/**
	 * Supplies the application-owned Exam lifecycle persistence action.
	 *
	 * @param handler operation receiving the selected Exam and requested
	 *                replacement lifecycle state
	 * @throws NullPointerException if {@code handler} is {@code null}
	 */
	public void setExamLifecycleHandler(BiConsumer<Exam, ExamCaptureState> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Dashboard selection identifies the Exam directly; lifecycle must not depend
		// on whichever booklet happens to be active in Capture.
		examLifecycleHandler = handler;
	}

	/**
	 * Supplies the Subject-level legacy Question metadata intake action.
	 *
	 * @param handler action that enters the existing legacy import workflow
	 * @throws NullPointerException if {@code handler} is {@code null}
	 */
	public void setLegacyQuestionImportHandler(Runnable handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Dashboard owns only navigation into legacy intake. Exam/Assets and the
		// existing importer remain authoritative for the actual transaction.
		legacyQuestionImportHandler = handler;
	}

	public void setMapCurriculumHandler(Runnable handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Mapping review remains application-owned because it opens the existing
		// persistence-backed review workflow.
		mapCurriculumHandler = handler;
	}

	/**
	 * Supplies the application-owned route into existing MCQ explanation capture.
	 *
	 * @param handler operation receiving the first missing explanation Question to
	 *                open
	 * @throws NullPointerException if {@code handler} is {@code null}
	 */
	public void setMcqExplanationCaptureHandler(Consumer<Question> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Dashboard identifies explanation work, while AnswerCapturePane remains the
		// single owner of Answer-region editing and persistence.
		mcqExplanationCaptureHandler = handler;
	}

	public void setNewQuestionCaptureHandler(Consumer<ExamBooklet> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// New Question capture is booklet-level work because no Question row exists yet
		// for source Questions that have not been captured.
		newQuestionCaptureHandler = handler;
	}

	public void setQuestionCorrectionHandler(Consumer<Question> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Existing incomplete Question correction remains distinct from adding new
		// Questions to an incomplete booklet.
		questionCorrectionHandler = handler;
	}

	void replaceData(List<ExamCorpusStatus> updatedStatuses, List<Question> updatedQuestions) {

		// Ordinary refresh retains the current mapping snapshot unless the application
		// supplies a replacement generation explicitly.
		replaceData(updatedStatuses, updatedQuestions, mappingCoverages, -1L);
	}

	void replaceData(List<ExamCorpusStatus> updatedStatuses, List<Question> updatedQuestions,
			long preferredQuestionId) {

		// Correction-only callers preserve the current independent mapping snapshot.
		replaceData(updatedStatuses, updatedQuestions, mappingCoverages, preferredQuestionId);
	}

	private String answerAssetCount(ExamCorpusStatus status) {
		ExamAssetExpectations expectations = status.assetExpectations();
		return presentExpected(expectations.availableAnswerFileCount(), expectations.expectedAnswerFileCount());
	}

	private String answerFileLabel(BookletCorpusStatus status) {
		if (status.assignedAnswerFile() == null) {
			return "—";
		}

		// Display the authoritative booklet-to-AnswerFile relationship without
		// inventing a defect when no assignment exists.
		return status.assignedAnswerFile().getName();
	}

	private String answerStatusLabel(QuestionCorpusWorkItem item) {
		if (!item.status().responseTypeResolved()) {
			return "—";
		}

		// Answer completeness comes directly from the existing Question audit.
		return item.status().answerComplete() ? "OK" : "Missing";
	}

	private void applyBulkResponseType(QuestionResponseType responseType) {
		List<Question> selected = selectedQuestionsForBulkUpdate();
		if (selected.isEmpty()) {
			return;
		}

		// Persist only an explicit valid selection of currently unresolved response
		// types.
		bulkResponseTypeHandler.accept(selected, responseType);
	}

	private void applyMcqExplanationFilter() {
		changingQuestionFilter = true;
		try {

			// Missing explanations belong to ordinarily complete MCQ Answers, so the
			// standard completeness ComboBox cannot represent this separate dimension.
			questionViewBox.setValue(QuestionCorpusCompletionFilter.ALL);
		} finally {
			changingQuestionFilter = false;
		}
		selectedQuestionProblem = null;
		mcqExplanationFilterActive = true;
		summaryQuestionFilterActive = true;

		// Rebuild Exam -> booklet -> Question scope from the separate explanation
		// requirement while retaining the Subject-level headline coverage.
		refreshDashboard();
	}

	private void applyQuestionFilter(QuestionCorpusCompletionFilter completion, QuestionCorpusProblem problem) {
		changingQuestionFilter = true;
		try {
			questionViewBox.setValue(completion);
		} finally {
			changingQuestionFilter = false;
		}

		// Returning to an ordinary Question filter ends the separate explanation-work
		// drill-down.
		mcqExplanationFilterActive = false;

		// Subject-level summary buttons narrow the complete Exam -> booklet -> Question
		// hierarchy rather than filtering only the previously selected Question scope.
		selectedQuestionProblem = problem;
		summaryQuestionFilterActive = true;
		refreshDashboard();
	}

	private String bookletProblemLabel(BookletCorpusStatus status) {
		if (mcqExplanationFilterActive) {
			int missingExplanations = status.mcqExplanationCoverage().missingExplanationCount();
			if (missingExplanations == 0) {
				return "—";
			}

			// While the Dashboard is explicitly showing missing explanation work, the
			// booklet Problem column should describe that same work dimension rather than
			// falling back to ordinary incomplete-Question counts.
			return missingExplanations == 1 ? "1 missing MCQ explanation"
					: missingExplanations + " missing MCQ explanations";
		}
		int problemCount = status.questionSummary().incompleteQuestions();

		// Outside the explanation drill-down, preserve the existing ordinary Question
		// problem count. Structural booklet findings remain in the warning
		// presentation.
		return problemCount == 0 ? "—" : Integer.toString(problemCount);
	}

	private BookletCorpusStatus bookletStatusFor(Question question) {
		if (question == null) {
			return null;
		}

		// Dashboard audit state is already the authoritative Subject snapshot. Match by
		// persistent identities because repository reads may reconstruct domain
		// objects.
		for (ExamCorpusStatus examStatus : examStatuses) {
			if (examStatus.exam().getId() != question.getExam().getId()) {
				continue;
			}
			for (BookletCorpusStatus bookletStatus : examStatus.bookletStatuses()) {
				if (bookletStatus.booklet().getId() == question.getBooklet().getId()) {
					return bookletStatus;
				}
			}
		}
		return null;
	}

	private String bookletWarningText(BookletCorpusStatus status) {
		StringBuilder text = new StringBuilder();
		if (status.hasFinding(BookletCorpusFinding.MISSING_QUESTION_PDF)) {
			text.append("Question PDF is missing. Use Manage Exam / Assets before Question capture.");
		}
		if (status.hasFinding(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH)) {
			if (!text.isEmpty()) {
				text.append("  ");
			}

			// Expected-versus-encountered is advisory: either the corpus is incomplete
			// or the recorded expectation is wrong.
			text.append("Expected ").append(status.expectedTopLevelQuestionCount())
					.append(" top-level Questions; encountered ").append(status.encounteredTopLevelQuestionCount())
					.append(". ");
			if (status.booklet().getExam().isComplete()) {
				text.append(
						"If Questions are missing, reactivate the Exam in Manage Exam / Assets, then use Capture Questions. ");
			} else {
				text.append("If Questions are missing, use Capture Questions. ");
			}
			text.append("If the expected count is wrong, correct it in Manage Exam / Assets.");
		}
		return text.toString();
	}

	// TODO Refacator
	private void buildContent() {

		// Keep the Exam filter row as application state because the single Legacy
		// Import
		// action moves between this populated-Exam row and the empty-Exam onboarding
		// row.
		examFilterRow = new HBox(SPACING, createBoldLabel("Provider"), providerBox, createBoldLabel("Year"), yearBox,
				createBoldLabel("Exam state"), examStateBox, clearFiltersButton);

		// Retain a stable structural identity because the single Subject-level Legacy
		// Import action is deliberately reparented between this row and empty
		// onboarding.
		examFilterRow.setId("corpus-dashboard-exam-filter-row");
		examFilterRow.setAlignment(Pos.CENTER_LEFT);

		// Subject-level corpus indicators remain immediately below the Subject-level
		// Curriculum section rather than between structural work areas.
		HBox summaryRow = new HBox(SPACING, totalQuestionsButton, needsAttentionButton, missingContentButton,
				missingAnswerButton, unknownTypeButton, sharedContextButton, missingMcqExplanationButton);
		summaryRow.setId("corpus-dashboard-summary-row");
		summaryRow.setAlignment(Pos.CENTER_LEFT);
		examEmptyStateRow = new HBox(SPACING, noExamsLabel, addExamButton);
		examEmptyStateRow.setId("corpus-dashboard-exam-empty-state");
		examEmptyStateRow.setAlignment(Pos.CENTER_LEFT);
		HBox selectedExamTitleRow = new HBox(SPACING, selectedExamLabel, declaredExamStateLabel, manageExamAssetsButton,
				examLifecycleButton);
		selectedExamTitleRow.setAlignment(Pos.CENTER_LEFT);

		// Exam lifecycle now belongs beside the selected Exam's structural management
		// action because both operate on Dashboard Exam selection, not Capture state.
		HBox.setHgrow(selectedExamLabel, Priority.ALWAYS);

		// The ordinary Exam catalogue disappears when a Subject has no Exams. The
		// onboarding row then explains the next valid Subject-level action.
		// Reuse the retained row so Legacy Import can be reparented without
		// constructing
		// a second set of Exam filters.
		examOperationalContent = new VBox(SPACING, examFilterRow, examTable, selectedExamTitleRow,
				selectedExamCountsLabel);
		examOperationalContent.setId("corpus-dashboard-exam-operational-content");
		bookletWarningLabel.setWrapText(true);
		setVisibleAndManaged(bookletWarningLabel, false);
		HBox bookletActionsRow = new HBox(SPACING, captureQuestionsButton, captureAnswersButton,
				captureMcqExplanationsButton);
		bookletActionsRow.setAlignment(Pos.CENTER_LEFT);
		HBox questionFilterRow = new HBox(SPACING, createBoldLabel("Show"), questionViewBox, questionResultCountLabel);
		questionFilterRow.setAlignment(Pos.CENTER_LEFT);

		// Selected Question work exposes direct Question and Answer completion together
		// with the existing bulk response-type actions.
		HBox questionActionsRow = new HBox(SPACING, completeSelectedQuestionButton, completeSelectedAnswerButton,
				selectAllUnknownButton, setSelectedMultipleChoiceButton, setSelectedWrittenResponseButton);
		questionActionsRow.setAlignment(Pos.CENTER_LEFT);
		mcqExplanationCoverageLabel.setWrapText(true);
		setVisibleAndManaged(mcqExplanationCoverageLabel, false);
		HBox mappingReviewRow = new HBox(SPACING, createBoldLabel("Mapping review"), curriculumMappingSummaryLabel);
		mappingReviewRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(curriculumMappingSummaryLabel, Priority.ALWAYS);
		HBox curriculumActionsRow = new HBox(SPACING, addCurriculumButton, mapCurriculumButton);
		curriculumActionsRow.setAlignment(Pos.CENTER_LEFT);
		StackPane curriculumSection = createTitledSection("corpus-dashboard-curriculum-section", "CURRICULUM",
				mappingReviewRow, curriculumActionsRow);
		StackPane examsSection = createTitledSection("corpus-dashboard-exams-section", "EXAMS", examEmptyStateRow,
				examOperationalContent);
		bookletsSection = createTitledSection("corpus-dashboard-booklets-section", "QUESTION BOOKLETS", bookletTable,
				selectedBookletLabel, bookletWarningLabel, bookletActionsRow);
		questionWorkSection = createTitledSection("corpus-dashboard-question-work-section", "QUESTION WORK",
				questionFilterRow, questionTable, questionActionsRow, mcqExplanationCoverageLabel);

		// Exam and booklet hierarchy shares one horizontal structural band.
		HBox hierarchyRow = new HBox(SPACING, examsSection, bookletsSection);
		hierarchyRow.setId("corpus-dashboard-hierarchy-row");
		examsSection.setMinWidth(0);
		examsSection.setMaxWidth(Double.MAX_VALUE);
		bookletsSection.setMinWidth(0);
		bookletsSection.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(examsSection, Priority.ALWAYS);
		HBox.setHgrow(bookletsSection, Priority.ALWAYS);

		// Structural hierarchy and Question work are independently sizeable. The user
		// can drag the divider according to whether structure or cleanup currently
		// needs
		// more screen space.
		SplitPane workSplit = new SplitPane(hierarchyRow, questionWorkSection);
		workSplit.setId("corpus-dashboard-work-split");
		workSplit.setOrientation(Orientation.VERTICAL);
		workSplit.setDividerPositions(0.45);
		VBox.setVgrow(bookletTable, Priority.ALWAYS);
		VBox.setVgrow(questionTable, Priority.ALWAYS);
		VBox.setVgrow(workSplit, Priority.ALWAYS);

		// Curriculum belongs directly below Subject. Corpus summary follows it, while
		// the adjustable Exam/Question workspace consumes the remaining height.
		getChildren().addAll(curriculumSection, summaryRow, workSplit);
		setSpacing(SPACING);
		setPadding(PADDING);
	}

	private boolean canCompleteSelectedAnswer(QuestionCorpusWorkItem item) {
		if (item == null) {
			return false;
		}

		// Question-body capture and Answer capture are independent work items. Once the
		// response type is known, a missing Answer can be completed before or after any
		// missing Question content or Shared Context work.
		return item.status().hasProblem(QuestionCorpusProblem.MISSING_ANSWER) && item.status().responseTypeResolved();
	}

	private boolean canCompleteSelectedQuestion(QuestionCorpusWorkItem item) {
		if (item == null || !item.status().responseTypeResolved()) {
			return false;
		}

		// This action owns existing Question-content and Shared Context work only.
		// Missing Answers have their own explicit booklet-level capture action.
		return item.status().hasProblem(QuestionCorpusProblem.MISSING_QUESTION_CONTENT)
				|| item.status().hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT);
	}

	private void captureAnswers() {
		Question question = firstMissingAnswerQuestion();
		if (question == null) {
			return;
		}

		// Begin with the first source-ordered ready Question in the selected booklet.
		answerCaptureHandler.accept(question);
	}

	private void captureMcqExplanations() {
		BookletCorpusStatus selected = bookletTable.getSelectionModel().getSelectedItem();
		Question question = firstMissingMcqExplanationQuestion(selected);
		if (question == null) {
			return;
		}

		// Booklet-level entry starts at the first source-ordered outstanding MCQ. The
		// existing AnswerCapturePane retrofit workflow advances through the remainder.
		mcqExplanationCaptureHandler.accept(question);
	}

	private void captureQuestions() {
		BookletCorpusStatus selected = bookletTable.getSelectionModel().getSelectedItem();
		if (selected == null || captureQuestionsButton.isDisable()) {
			return;
		}
		Question existingQuestionWork = firstQuestionCaptureWorkQuestion(selected);
		if (existingQuestionWork != null) {

			// Existing persisted Questions take precedence over adding another Question.
			// Legacy imports commonly reach Expected == Found before their Question
			// content has been captured, so that completion work must not be treated as
			// over-capture.
			questionCorrectionHandler.accept(existingQuestionWork);
			return;
		}

		// Only a genuinely new Question can increase the encountered Question count.
		// Warn when that addition would meet or exceed the recorded expectation.
		if (!confirmQuestionCaptureBeyondExpected(selected)) {
			return;
		}
		newQuestionCaptureHandler.accept(selected.booklet());
	}

	private void changeSelectedExamState() {
		ExamCorpusStatus selected = examTable.getSelectionModel().getSelectedItem();
		if (selected == null) {
			return;
		}
		if (selected.declaredCaptureState() == ExamCaptureState.COMPLETE) {

			// COMPLETE Exams can always be deliberately reopened for structural work.
			examLifecycleHandler.accept(selected.exam(), ExamCaptureState.ACTIVE);
			return;
		}
		if (!selected.isReadyForCompletion()) {
			return;
		}

		// ACTIVE -> COMPLETE is offered only after the current audit snapshot proves
		// that every required structure and ordinary capture task is complete.
		examLifecycleHandler.accept(selected.exam(), ExamCaptureState.COMPLETE);
	}

	private void clearFilters() {
		changingScopeFilters = true;
		changingQuestionFilter = true;
		try {

			// Clear every Dashboard-local restriction while retaining the authoritative
			// Working Subject.
			providerBox.setValue(null);
			yearBox.setValue(null);
			examStateBox.setValue(null);
			questionViewBox.setValue(QuestionCorpusCompletionFilter.ALL);
			selectedQuestionProblem = null;
			mcqExplanationFilterActive = false;
			summaryQuestionFilterActive = false;
		} finally {
			changingQuestionFilter = false;
			changingScopeFilters = false;
		}

		// Rebuild the complete Exam -> booklet -> Question hierarchy after all local
		// filters have been reset together.
		refreshDashboard();
	}

	private void clearSelectedExam() {
		selectedExamLabel.setText("No Exam selected");
		declaredExamStateLabel.setText("");
		selectedExamCountsLabel.setText("");
		bookletTable.getItems().clear();
		selectedBookletLabel.setText("Selected booklet: All booklets");
		setVisibleAndManaged(bookletWarningLabel, false);
		setVisibleAndManaged(mcqExplanationCoverageLabel, false);

		// No selected Exam or booklet can supply capture, structural or lifecycle work.
		updateCaptureActionState();
		updateExamAssetsActionState();
		updateExamLifecycleActionState();
	}

	private void completeSelectedAnswer() {
		QuestionCorpusWorkItem selected = singleSelectedQuestionWorkItem();
		if (selected == null) {
			return;
		}
		if (isMissingMcqExplanation(selected.question())) {

			// Explanation work belongs to an already-complete A-D Answer and therefore
			// routes to the retrofit explanation workflow rather than ordinary Answer
			// capture.
			mcqExplanationCaptureHandler.accept(selected.question());
			return;
		}
		if (!canCompleteSelectedAnswer(selected)) {
			return;
		}

		// Ordinary missing-Answer work retains its existing exact-Question route.
		answerCaptureHandler.accept(selected.question());
	}

	private void completeSelectedQuestion() {
		QuestionCorpusWorkItem selected = singleSelectedQuestionWorkItem();
		if (!canCompleteSelectedQuestion(selected)) {
			return;
		}

		// Correct exactly the one Question explicitly selected in the current scope.
		questionCorrectionHandler.accept(selected.question());
	}

	private String completionLabel(QuestionCorpusCompletionFilter completion) {
		return switch (completion) {
		case ALL -> "All Questions";
		case COMPLETE -> "Complete";
		case INCOMPLETE -> "Needs attention";
		};
	}

	// TODO Refactor into single concerns
	private void configureActions() {
		addExamButton.setOnAction(_ -> addExamHandler.run());
		clearFiltersButton.setOnAction(_ -> clearFilters());
		importLegacyQuestionsButton.setOnAction(_ -> legacyQuestionImportHandler.run());
		providerBox.valueProperty().addListener((_, _, _) -> {
			if (!changingScopeFilters) {
				refreshDashboard();
			}
		});
		yearBox.valueProperty().addListener((_, _, _) -> {
			if (!changingScopeFilters) {
				refreshDashboard();
			}
		});
		examStateBox.valueProperty().addListener((_, _, _) -> {
			if (!changingScopeFilters) {
				refreshDashboard();
			}
		});
		totalQuestionsButton.setOnAction(_ -> applyQuestionFilter(QuestionCorpusCompletionFilter.ALL, null));
		needsAttentionButton.setOnAction(_ -> applyQuestionFilter(QuestionCorpusCompletionFilter.INCOMPLETE, null));
		missingContentButton.setOnAction(_ -> applyQuestionFilter(QuestionCorpusCompletionFilter.INCOMPLETE,
				QuestionCorpusProblem.MISSING_QUESTION_CONTENT));
		missingAnswerButton.setOnAction(_ -> applyQuestionFilter(QuestionCorpusCompletionFilter.INCOMPLETE,
				QuestionCorpusProblem.MISSING_ANSWER));
		unknownTypeButton.setOnAction(_ -> applyQuestionFilter(QuestionCorpusCompletionFilter.INCOMPLETE,
				QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE));
		sharedContextButton.setOnAction(_ -> applyQuestionFilter(QuestionCorpusCompletionFilter.INCOMPLETE,
				QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT));
		missingMcqExplanationButton.setOnAction(_ -> applyMcqExplanationFilter());
		questionViewBox.valueProperty().addListener((_, _, _) -> {
			if (changingQuestionFilter) {
				return;
			}

			// A direct Show selection returns to ordinary Question completeness filtering,
			// ending any separate explanation-work drill-down.
			mcqExplanationFilterActive = false;
			selectedQuestionProblem = null;
			summaryQuestionFilterActive = false;
			refreshDashboard();
		});
		examTable.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
			if (selected == null) {
				clearSelectedExam();
				refreshQuestionWork();
				return;
			}

			// Exam selection owns booklet and Question scope.
			showSelectedExam(selected, null);
			refreshQuestionWork();
		});
		bookletTable.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
			if (selected == null) {
				selectedBookletLabel.setText("Selected booklet: All booklets");
				setVisibleAndManaged(bookletWarningLabel, false);
				setVisibleAndManaged(mcqExplanationCoverageLabel, false);
				updateCaptureActionState();
				updateExamAssetsActionState();
				refreshQuestionWork();
				return;
			}

			// Booklet selection narrows both visible Question work and the explicit
			// capture actions.
			showSelectedBooklet(selected);
			refreshQuestionWork();
		});
		captureAnswersButton.setOnAction(_ -> captureAnswers());
		captureMcqExplanationsButton.setOnAction(_ -> captureMcqExplanations());
		captureQuestionsButton.setOnAction(_ -> captureQuestions());
		completeSelectedAnswerButton.setOnAction(_ -> completeSelectedAnswer());
		completeSelectedQuestionButton.setOnAction(_ -> completeSelectedQuestion());
		selectAllUnknownButton.setOnAction(_ -> selectAllUnknownShown());
		setSelectedMultipleChoiceButton.setOnAction(_ -> applyBulkResponseType(QuestionResponseType.MULTIPLE_CHOICE));
		setSelectedWrittenResponseButton.setOnAction(_ -> applyBulkResponseType(QuestionResponseType.WRITTEN_RESPONSE));
		questionTable.getSelectionModel().getSelectedItems()
				.addListener((ListChangeListener<QuestionCorpusWorkItem>) _ -> {

					// Bulk response-type resolution and single-Question correction derive
					// independently from the same explicit table selection.
					updateBulkActionState();
					updateQuestionActionState();
				});
		manageExamAssetsButton.setOnAction(_ -> manageSelectedExamAssets());
		examLifecycleButton.setOnAction(_ -> changeSelectedExamState());
		addCurriculumButton.setOnAction(_ -> addCurriculumHandler.run());
		mapCurriculumButton.setOnAction(_ -> mapCurriculumHandler.run());
	}

	private void configureBookletTable() {
		bookletTable.setId("corpus-dashboard-booklets");
		bookletTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		// Preserve enough space for the header plus at least three ordinary booklet
		// rows, while preventing a small booklet list from consuming the whole dialog.
		bookletTable.setMinHeight(BOOKLET_TABLE_MIN_HEIGHT);
		bookletTable.setPrefHeight(BOOKLET_TABLE_HEIGHT);
		bookletTable.setMaxHeight(BOOKLET_TABLE_MAX_HEIGHT);
		TableColumn<BookletCorpusStatus, String> bookletColumn = new TableColumn<>("Booklet");
		bookletColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().booklet().getName()));
		TableColumn<BookletCorpusStatus, String> formatColumn = new TableColumn<>("Format");
		formatColumn.setCellValueFactory(
				data -> new ReadOnlyStringWrapper(data.getValue().booklet().getQuestionFormat().toString()));
		TableColumn<BookletCorpusStatus, String> pdfColumn = new TableColumn<>("Question PDF");
		pdfColumn.setCellValueFactory(
				data -> new ReadOnlyStringWrapper(data.getValue().questionPdfAvailable() ? "Available" : "MISSING"));
		TableColumn<BookletCorpusStatus, String> answerFileColumn = new TableColumn<>("Answer file");
		answerFileColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(answerFileLabel(data.getValue())));
		TableColumn<BookletCorpusStatus, String> expectedColumn = new TableColumn<>("Expected");
		expectedColumn.setCellValueFactory(
				data -> new ReadOnlyStringWrapper(nullableCount(data.getValue().expectedTopLevelQuestionCount())));
		TableColumn<BookletCorpusStatus, Number> foundColumn = new TableColumn<>("Found");
		foundColumn.setCellValueFactory(
				data -> new ReadOnlyObjectWrapper<>(data.getValue().encounteredTopLevelQuestionCount()));
		TableColumn<BookletCorpusStatus, Number> partsColumn = new TableColumn<>("Parts");
		partsColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().questionPartCount()));
		TableColumn<BookletCorpusStatus, Number> noDescriptorColumn = new TableColumn<>("No descriptor");
		noDescriptorColumn.setCellValueFactory(
				data -> new ReadOnlyObjectWrapper<>(data.getValue().questionsWithoutDescriptorCount()));
		TableColumn<BookletCorpusStatus, String> mcqExplanationColumn = new TableColumn<>("MCQ explanations");
		mcqExplanationColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(mcqExplanationCoverageText(data.getValue())));
		TableColumn<BookletCorpusStatus, String> problemsColumn = new TableColumn<>("Problems");
		problemsColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(bookletProblemLabel(data.getValue())));

		// Descriptor coverage and MCQ explanation coverage are independent reporting
		// dimensions beside the ordinary Question counts.
		bookletTable.getColumns()
				.setAll(List.<TableColumn<BookletCorpusStatus, ?>>of(bookletColumn, formatColumn, pdfColumn,
						answerFileColumn, expectedColumn, foundColumn, partsColumn, noDescriptorColumn,
						mcqExplanationColumn, problemsColumn));
	}

	private void configureBulkResponseTypeControls() {
		selectAllUnknownButton.setId("corpus-dashboard-select-all-unknown");
		setSelectedMultipleChoiceButton.setId("corpus-dashboard-set-multiple-choice");
		setSelectedWrittenResponseButton.setId("corpus-dashboard-set-written-response");
		selectAllUnknownButton
				.setTooltip(new Tooltip("Select every currently shown Question whose response type is Unknown."));
		setSelectedMultipleChoiceButton
				.setTooltip(new Tooltip("Set the selected unresolved Questions to Multiple choice."));
		setSelectedWrittenResponseButton
				.setTooltip(new Tooltip("Set the selected unresolved Questions to Written response."));
		setSelectedMultipleChoiceButton.setDisable(true);
		setSelectedWrittenResponseButton.setDisable(true);
	}

	// TODO Refactor into separate concerns
	private void configureControls() {
		curriculumMappingSummaryLabel.setId("corpus-dashboard-mapping-review");
		curriculumMappingSummaryLabel.setWrapText(true);
		addCurriculumButton.setId("corpus-dashboard-add-curriculum");
		addCurriculumButton.setAccessibleText("Add Curriculum");
		addCurriculumButton.setTooltip(new Tooltip("Add curriculum to the current Subject."));
		addCurriculumButton.setPadding(new Insets(2, 7, 2, 7));
		addCurriculumButton.setMinWidth(Region.USE_PREF_SIZE);
		addCurriculumButton.setMaxWidth(Region.USE_PREF_SIZE);
		mapCurriculumButton.setId("corpus-dashboard-map-curriculum");
		mapCurriculumButton
				.setTooltip(new Tooltip("Review mappings between historical and current curriculum versions."));
		noExamsLabel.setId("corpus-dashboard-no-exams");
		noExamsLabel.setWrapText(true);
		addExamButton.setId("corpus-dashboard-add-exam");
		addExamButton.setTooltip(new Tooltip("Add an Exam to the current Subject."));
		addExamButton.setMinWidth(Region.USE_PREF_SIZE);
		importLegacyQuestionsButton.setId("corpus-dashboard-import-legacy-questions");
		importLegacyQuestionsButton
				.setTooltip(new Tooltip("Import legacy Question metadata for the current Working Subject."));
		importLegacyQuestionsButton.setMinWidth(Region.USE_PREF_SIZE);

		// Selected-scope descriptions act as field labels as well as status text, so
		// emphasise them consistently with the Dashboard's other metadata labels.
		declaredExamStateLabel.setStyle("-fx-font-weight: bold;");
		selectedExamCountsLabel.setStyle("-fx-font-weight: bold;");
		selectedBookletLabel.setStyle("-fx-font-weight: bold;");
		configureFilterControls();
		configureSummaryControls();
		configureExamTable();
		configureBookletTable();
		configureQuestionWorkControls();
		configureBulkResponseTypeControls();
		selectedExamLabel.setId("corpus-dashboard-selected-exam");
		declaredExamStateLabel.setId("corpus-dashboard-selected-exam-state");
		selectedExamCountsLabel.setId("corpus-dashboard-selected-exam-counts");
		selectedBookletLabel.setId("corpus-dashboard-selected-booklet");
		bookletWarningLabel.setId("corpus-dashboard-booklet-warning");
		mcqExplanationCoverageLabel.setId("corpus-dashboard-mcq-coverage");
		captureAnswersButton.setId("corpus-dashboard-capture-answers");
		captureAnswersButton.setTooltip(new Tooltip(
				"Start with the selected booklet, then continue through ready unanswered Questions in the selected Exam."));
		captureAnswersButton.setDisable(true);
		captureMcqExplanationsButton.setId("corpus-dashboard-capture-mcq-explanations");
		captureMcqExplanationsButton.setTooltip(
				new Tooltip("Capture required MCQ explanation regions for the selected explanation-capable booklet."));
		captureMcqExplanationsButton.setDisable(true);
		captureQuestionsButton.setId("corpus-dashboard-capture-questions");
		captureQuestionsButton.setTooltip(new Tooltip(
				"Start new Question capture for the selected booklet. COMPLETE Exams must be reactivated first."));
		captureQuestionsButton.setDisable(true);
		completeSelectedQuestionButton.setId("corpus-dashboard-complete-question");
		completeSelectedQuestionButton.setTooltip(new Tooltip(
				"Complete missing Question content or unresolved Shared Context for the selected Question."));
		completeSelectedQuestionButton.setDisable(true);
		manageExamAssetsButton.setId("corpus-dashboard-manage-exam-assets");
		manageExamAssetsButton.setTooltip(new Tooltip("Open the selected Exam or booklet in Exam / Assets."));
		manageExamAssetsButton.setDisable(true);
		examLifecycleButton.setId("corpus-dashboard-exam-lifecycle");
		examLifecycleButton.setMinWidth(Region.USE_PREF_SIZE);
		examLifecycleButton.setTooltip(new Tooltip("Change the lifecycle state of the selected Exam."));
		examLifecycleButton.setDisable(true);
	}

	private void configureExamTable() {
		examTable.setId("corpus-dashboard-exams");
		examTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		examTable.setPrefHeight(EXAM_TABLE_HEIGHT);
		TableColumn<ExamCorpusStatus, Number> yearColumn = new TableColumn<>("Year");
		yearColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().exam().getYear()));
		TableColumn<ExamCorpusStatus, String> providerColumn = new TableColumn<>("Provider");
		providerColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().exam().getProvider().getName()));
		TableColumn<ExamCorpusStatus, String> assessmentColumn = new TableColumn<>("Assessment");
		assessmentColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().exam().getName()));
		TableColumn<ExamCorpusStatus, String> stateColumn = new TableColumn<>("State");
		stateColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().declaredCaptureState().name()));
		TableColumn<ExamCorpusStatus, String> questionBookletsColumn = new TableColumn<>("Question Booklets");
		questionBookletsColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(questionAssetCount(data.getValue())));
		TableColumn<ExamCorpusStatus, String> answerBookletsColumn = new TableColumn<>("Answer Booklets");
		answerBookletsColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(answerAssetCount(data.getValue())));
		TableColumn<ExamCorpusStatus, String> workColumn = new TableColumn<>("Work");
		workColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(workLabel(data.getValue())));

		// Use the collection overload so mixed TableColumn value types do not create
		// a generic varargs array warning.
		examTable.getColumns().setAll(List.<TableColumn<ExamCorpusStatus, ?>>of(yearColumn, providerColumn,
				assessmentColumn, stateColumn, questionBookletsColumn, answerBookletsColumn, workColumn));
	}

	private void configureFilterControls() {
		providerBox.setId("corpus-dashboard-filter-provider");
		yearBox.setId("corpus-dashboard-filter-year");
		examStateBox.setId("corpus-dashboard-filter-exam-state");
		clearFiltersButton.setId("corpus-dashboard-clear-filters");
		providerBox.setPromptText("All providers");
		yearBox.setPromptText("All years");
		examStateBox.setPromptText("All");
		examStateBox.getItems().setAll(ExamCaptureState.values());
		providerBox.setConverter(new QuestionCorpusAuditFilterConverter<>(ExamProvider::getName));
	}

	private void configureQuestionWorkControls() {
		completeSelectedAnswerButton.setId("corpus-dashboard-complete-answer");
		completeSelectedAnswerButton.setTooltip(new Tooltip("Complete the missing Answer for the selected Question."));
		completeSelectedAnswerButton.setDisable(true);
		questionViewBox.setId("corpus-dashboard-question-view");
		questionResultCountLabel.setId("corpus-dashboard-question-count");
		questionTable.setId("corpus-dashboard-question-work");
		questionViewBox.getItems().setAll(QuestionCorpusCompletionFilter.values());
		questionViewBox.setValue(QuestionCorpusCompletionFilter.INCOMPLETE);
		questionViewBox.setConverter(new QuestionCorpusAuditFilterConverter<>(this::completionLabel));
		questionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		// The structural hierarchy is now compact enough for Question Work to use the
		// remaining Dashboard height rather than being artificially capped.
		questionTable.setPrefHeight(QUESTION_TABLE_HEIGHT);
		questionTable.setMaxHeight(Double.MAX_VALUE);
		questionTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

		// Question Work can span several Exams and booklets, so each row carries enough
		// source context to identify the Question without relying on its code alone.
		TableColumn<QuestionCorpusWorkItem, String> providerColumn = new TableColumn<>("Provider");
		providerColumn.setCellValueFactory(
				data -> new ReadOnlyStringWrapper(data.getValue().question().getExam().getProvider().getName()));
		TableColumn<QuestionCorpusWorkItem, Number> yearColumn = new TableColumn<>("Year");
		yearColumn.setCellValueFactory(
				data -> new ReadOnlyObjectWrapper<Number>(data.getValue().question().getExam().getYear()));
		TableColumn<QuestionCorpusWorkItem, String> examColumn = new TableColumn<>("Exam");
		examColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().question().getExam().getName()));
		TableColumn<QuestionCorpusWorkItem, String> bookletColumn = new TableColumn<>("Booklet");
		bookletColumn.setCellValueFactory(
				data -> new ReadOnlyStringWrapper(data.getValue().question().getBooklet().getName()));
		TableColumn<QuestionCorpusWorkItem, String> questionColumn = new TableColumn<>("Question");
		questionColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().question().getQuestionCode()));

		// Lexical String sorting would put 10 before 2. Reuse the model's natural
		// Question-code rule for both default and user-requested Question sorting.
		questionColumn.setComparator(QuestionSourceOrder.questionCodeComparator());
		TableColumn<QuestionCorpusWorkItem, String> typeColumn = new TableColumn<>("Type");
		typeColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(responseTypeLabel(data.getValue().question())));
		TableColumn<QuestionCorpusWorkItem, String> contentColumn = new TableColumn<>("Content");
		contentColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(
				data.getValue().status().questionContentCaptured() ? "OK" : "MISSING"));
		TableColumn<QuestionCorpusWorkItem, String> answerColumn = new TableColumn<>("Answer");
		answerColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(answerStatusLabel(data.getValue())));
		TableColumn<QuestionCorpusWorkItem, String> sharedContextColumn = new TableColumn<>("Shared Context");
		sharedContextColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(
				data.getValue().status().sharedContextResolved() ? "Resolved" : "UNRESOLVED"));
		TableColumn<QuestionCorpusWorkItem, String> problemColumn = new TableColumn<>("Problem");
		problemColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(problemsLabel(data.getValue())));

		// Use the collection overload because the source columns deliberately use mixed
		// String and numeric value types.
		questionTable.getColumns()
				.setAll(List.<TableColumn<QuestionCorpusWorkItem, ?>>of(providerColumn, yearColumn, examColumn,
						bookletColumn, questionColumn, typeColumn, contentColumn, answerColumn, sharedContextColumn,
						problemColumn));

		// Default presentation preserves complete source hierarchy. Users can still
		// change table sorting explicitly afterwards.
		questionTable.getSortOrder().setAll(List.<TableColumn<QuestionCorpusWorkItem, ?>>of(providerColumn, yearColumn,
				examColumn, bookletColumn, questionColumn));
	}

	private void configureSummaryControls() {
		totalQuestionsButton.setId("corpus-dashboard-summary-total");
		needsAttentionButton.setId("corpus-dashboard-summary-attention");
		missingContentButton.setId("corpus-dashboard-summary-missing-content");
		missingAnswerButton.setId("corpus-dashboard-summary-missing-answer");
		unknownTypeButton.setId("corpus-dashboard-summary-unknown-type");
		sharedContextButton.setId("corpus-dashboard-summary-shared-context");
		missingMcqExplanationButton.setId("corpus-dashboard-summary-missing-mcq-explanations");
		missingMcqExplanationButton
				.setTooltip(new Tooltip("Show answered MCQs still missing required explanation regions."));
	}

	private boolean confirmQuestionCaptureBeyondExpected(BookletCorpusStatus status) {
		Integer expected = status.expectedTopLevelQuestionCount();
		int found = status.encounteredTopLevelQuestionCount();

		// An unknown expectation, or a genuine shortfall, requires no warning.
		if (expected == null || found < expected.intValue()) {
			return true;
		}
		ButtonType captureButton = new ButtonType("Capture Another Question", ButtonBar.ButtonData.OK_DONE);
		Alert confirmation = new Alert(Alert.AlertType.WARNING);

		// Keep this warning owned by the Dashboard when it is currently displayed.
		if (getScene() != null && getScene().getWindow() != null) {
			confirmation.initOwner(getScene().getWindow());
		}
		confirmation.setTitle("Capture Questions");
		confirmation.getDialogPane().setId("corpus-dashboard-capture-count-confirmation");
		if (found == expected.intValue()) {
			confirmation.setHeaderText("Expected Question count already reached.");
			confirmation.setContentText("This booklet records " + expected + " expected top-level Questions and "
					+ found + " have already been found.\n\n"
					+ "Capture another Question only if the source genuinely contains another Question. "
					+ "If the expected count is wrong, correct it in Exam / Assets.");
		} else {
			confirmation.setHeaderText("Found Question count already exceeds the expectation.");
			confirmation.setContentText("This booklet records " + expected + " expected top-level Questions, but "
					+ found + " have already been found.\n\n" + "Check the booklet before capturing another Question. "
					+ "If the expected count is wrong, correct it in Exam / Assets.");
		}
		confirmation.getButtonTypes().setAll(captureButton, ButtonType.CANCEL);

		// Only the explicit Capture Another Question decision may continue.
		return confirmation.showAndWait().orElse(ButtonType.CANCEL) == captureButton;
	}

	private Label createBoldLabel(String text) {
		Label label = new Label(text);

		// Dashboard field names should remain visually distinct from the values they
		// describe.
		label.setStyle("-fx-font-weight: bold;");
		return label;
	}

	private StackPane createTitledSection(String id, String title, Node... content) {
		Label titleLabel = new Label(title);
		titleLabel.setStyle("-fx-font-weight: bold; -fx-background-color: -fx-background; -fx-padding: 0 5 0 5;");
		VBox body = new VBox(SPACING, content);
		body.setPadding(new Insets(14, 10, 10, 10));
		body.setStyle("-fx-border-color: #b0b0b0; -fx-border-width: 1; -fx-border-radius: 3;");

		// The title label sits over the upper border, giving the Dashboard the compact
		// titled-border appearance without using collapsible TitledPane controls.
		StackPane section = new StackPane(body, titleLabel);
		section.setId(id);
		section.setPadding(new Insets(4, 0, 0, 0));
		StackPane.setAlignment(titleLabel, Pos.TOP_LEFT);

		// Position the legend across the border rather than below it.
		StackPane.setMargin(titleLabel, new Insets(-3, 0, 0, 10));
		return section;
	}

	private int examWorkCount(ExamCorpusStatus status) {

		// Exam Work now includes both ordinary incomplete Questions and the separate
		// explanation requirement declared by explanation-capable AnswerFiles.
		return status.questionSummary().incompleteQuestions()
				+ status.mcqExplanationSummary().missingExplanationCount();
	}

	private List<BookletCorpusStatus> filteredBookletStatuses(ExamCorpusStatus status) {
		if (!summaryQuestionFilterActive) {
			return status.bookletStatuses();
		}
		Set<Long> matchingBookletIds = new HashSet<>();
		for (QuestionCorpusWorkItem item : summaryFilteredQuestionWork()) {
			if (item.question().getExam().getId() == status.exam().getId()) {
				matchingBookletIds.add(item.question().getBooklet().getId());
			}
		}

		// Once an Exam has survived a summary drill-down, show only its booklets that
		// actually contribute Questions to that selected summary category.
		return status.bookletStatuses().stream()
				.filter(bookletStatus -> matchingBookletIds.contains(bookletStatus.booklet().getId())).toList();
	}

	private List<ExamCorpusStatus> filteredExamStatuses() {
		List<ExamCorpusStatus> scoped = scopeFilteredExamStatuses();
		if (!summaryQuestionFilterActive) {
			return scoped;
		}
		Set<Long> matchingExamIds = new HashSet<>();
		for (QuestionCorpusWorkItem item : summaryFilteredQuestionWork()) {
			matchingExamIds.add(item.question().getExam().getId());
		}

		// A Subject-level summary drill-down keeps only Exams that actually contain
		// Questions represented by that summary count.
		return scoped.stream().filter(status -> matchingExamIds.contains(status.exam().getId())).toList();
	}

	private List<Question> filteredSummaryQuestions() {
		Set<Long> visibleExamIds = new HashSet<>();
		for (ExamCorpusStatus status : scopeFilteredExamStatuses()) {
			visibleExamIds.add(status.exam().getId());
		}

		// Headline counts follow only the ordinary structural filters. Selecting one of
		// those headline counts must not recalculate the number printed on the button.
		return questions.stream().filter(question -> visibleExamIds.contains(question.getExam().getId())).toList();
	}

	private Question firstMissingAnswerQuestion() {
		BookletCorpusStatus selected = bookletTable.getSelectionModel().getSelectedItem();
		if (selected == null) {
			return null;
		}
		QuestionCorpusFilter filter = new QuestionCorpusFilter(workingSubject.getId(), null, null,
				selected.booklet().getId(), QuestionCorpusCompletionFilter.INCOMPLETE,
				QuestionCorpusProblem.MISSING_ANSWER);
		return QuestionCorpusQueue.build(questions, filter).stream()

				// Do not send the user to Answer capture while Question content, Shared
				// Context or response type still requires earlier correction.
				.filter(item -> item.status().questionContentCaptured() && item.status().sharedContextResolved()
						&& item.status().responseTypeResolved())
				.map(QuestionCorpusWorkItem::question).findFirst().orElse(null);
	}

	private Question firstMissingMcqExplanationQuestion(BookletCorpusStatus bookletStatus) {
		if (bookletStatus == null || !bookletStatus.mcqExplanationCoverage().explanationCapable()) {
			return null;
		}

		// Booklet capture must begin at the first outstanding Question in natural
		// source
		// order and must never cross into another booklet.
		return questions.stream().filter(question -> question.getBooklet().getId() == bookletStatus.booklet().getId())
				.filter(this::isMissingMcqExplanation).sorted(QuestionSourceOrder.comparator()).findFirst()
				.orElse(null);
	}

	private Question firstQuestionCaptureWorkQuestion(BookletCorpusStatus selected) {
		if (selected == null) {
			return null;
		}
		QuestionCorpusFilter filter = new QuestionCorpusFilter(workingSubject.getId(), null, null,
				selected.booklet().getId(), QuestionCorpusCompletionFilter.INCOMPLETE, null);

		// Question-side capture consists of existing Questions whose body content or
		// Shared Context still requires resolution. Answer-only work does not belong to
		// this action.
		return QuestionCorpusQueue.build(questions, filter).stream()
				.filter(item -> item.status().hasProblem(QuestionCorpusProblem.MISSING_QUESTION_CONTENT)
						|| item.status().hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT))
				.map(QuestionCorpusWorkItem::question).findFirst().orElse(null);
	}

	private String formatCurriculumMappingCoverage(CurriculumMappingCoverage coverage) {
		CurriculumMappingLevelCoverage descriptors = coverage.descriptorCoverage();
		CurriculumMappingLevelCoverage subtopics = coverage.subtopicCoverage();
		int total = descriptors.total() + subtopics.total();
		int reviewed = descriptors.deliberatelyReviewed() + subtopics.deliberatelyReviewed();
		int unreviewed = descriptors.unreviewed() + subtopics.unreviewed();
		int inconsistent = descriptors.inconsistent() + subtopics.inconsistent();
		int remaining = unreviewed + inconsistent;
		String pair = coverage.sourceVersion().getName() + " \u2192 " + coverage.targetVersion().getName();

		// A syllabus pair with no applicable Descriptor or Subtopic source nodes has no
		// review work and must not be presented as incomplete.
		if (total == 0) {
			return pair + ": not applicable";
		}

		// Completed mapping review remains independent of Question/Exam completeness.
		if (remaining == 0) {
			return pair + ": complete (" + reviewed + "/" + total + " resolved)";
		}
		return pair + ": " + reviewed + "/" + total + " resolved; " + remaining + " remaining (" + unreviewed
				+ " unreviewed, " + inconsistent + " inconsistent)";
	}

	private boolean isMissingMcqExplanation(Question question) {
		if (question == null || question.getResponseType() != QuestionResponseType.MULTIPLE_CHOICE) {
			return false;
		}
		BookletCorpusStatus bookletStatus = bookletStatusFor(question);
		if (bookletStatus == null || !bookletStatus.mcqExplanationCoverage().explanationCapable()) {
			return false;
		}

		// Reuse the same ordinary Answer audit that supplies booklet explanation
		// eligibility. A malformed or missing A-D Answer remains ordinary Answer work.
		if (!QuestionCorpusAudit.assess(question).answerComplete()) {
			return false;
		}

		// For eligible MCQs, at least one persisted Answer region is the explanation.
		return question.getAnswer().getRegions().isEmpty();
	}

	private void manageSelectedExamAssets() {
		ExamCorpusStatus selectedExam = examTable.getSelectionModel().getSelectedItem();
		if (selectedExam == null) {
			return;
		}
		BookletCorpusStatus selectedBooklet = bookletTable.getSelectionModel().getSelectedItem();

		// Exam / Assets is a general Dashboard management route, not merely a repair
		// action. Preserve booklet scope whenever the user has deliberately selected
		// one.
		ExamBooklet targetBooklet = selectedBooklet == null ? null : selectedBooklet.booklet();
		examAssetsHandler.accept(selectedExam.exam(), targetBooklet);
	}

	private List<CurriculumMappingCoverage> mappingCoveragesForWorkingSubject(
			List<CurriculumMappingCoverage> sourceCoverages) {
		if (sourceCoverages == null) {
			throw new NullPointerException("mappingCoverages");
		}
		for (CurriculumMappingCoverage coverage : sourceCoverages) {
			if (coverage == null) {
				throw new NullPointerException("mappingCoverages contains null");
			}
		}

		// Persistent Subject identity is the authoritative Dashboard boundary for
		// mapping review just as it is for Exam and Question state.
		return sourceCoverages.stream()
				.filter(coverage -> coverage.sourceVersion().getSubject().getId() == workingSubject.getId()
						&& coverage.targetVersion().getSubject().getId() == workingSubject.getId())
				.toList();
	}

	private String mcqExplanationCoverageText(BookletCorpusStatus status) {
		McqExplanationCoverage coverage = status.mcqExplanationCoverage();
		if (!coverage.explanationCapable()) {
			return "—";
		}

		// Booklet rows make the location of explanation work visible without requiring
		// the teacher to select every booklet individually.
		return coverage.capturedExplanationCount() + " / " + coverage.eligibleQuestionCount();
	}

	private List<QuestionCorpusWorkItem> missingMcqExplanationWorkItems(List<Question> sourceQuestions) {

		// Reuse ordinary Question audit status for all existing columns. Explanation
		// absence is a separate Dashboard work dimension rather than a new
		// QuestionCorpusProblem.
		return sourceQuestions.stream().filter(this::isMissingMcqExplanation)
				.map(question -> new QuestionCorpusWorkItem(question, QuestionCorpusAudit.assess(question))).toList();
	}

	private void moveLegacyImportButton(HBox target) {
		if (target == null) {
			throw new NullPointerException("target");
		}
		if (importLegacyQuestionsButton.getParent() == target) {
			return;
		}

		// Reuse one action in both populated and empty Exam presentations so the
		// Dashboard never exposes two competing legacy-import entry points.
		if (importLegacyQuestionsButton.getParent() instanceof HBox currentParent) {
			currentParent.getChildren().remove(importLegacyQuestionsButton);
		}
		target.getChildren().add(importLegacyQuestionsButton);
	}

	private String nullableCount(Integer count) {

		// An absent planning count is different from zero. State that explicitly
		// rather than displaying the same dash used elsewhere for "no problem".
		return count == null ? "not recorded" : count.toString();
	}

	private void populateFilterOptions() {
		Map<Long, ExamProvider> providers = new LinkedHashMap<>();
		for (ExamCorpusStatus status : examStatuses) {
			ExamProvider provider = status.exam().getProvider();
			providers.putIfAbsent(provider.getId(), provider);
		}
		providerBox.getItems().setAll(providers.values().stream()
				.sorted(Comparator.comparing(ExamProvider::getName).thenComparingLong(ExamProvider::getId)).toList());
		yearBox.getItems().setAll(examStatuses.stream().map(status -> status.exam().getYear()).distinct()
				.sorted(Comparator.reverseOrder()).toList());
	}

	private String presentExpected(int present, Integer expected) {
		return present + " / " + nullableCount(expected);
	}

	private String problemLabel(QuestionCorpusProblem problem) {
		return switch (problem) {
		case MISSING_QUESTION_CONTENT -> "Missing question content";
		case MISSING_ANSWER -> "Missing answer";
		case UNRESOLVED_SHARED_CONTEXT -> "Shared Context";
		case UNKNOWN_RESPONSE_TYPE -> "Unknown type";
		};
	}

	private String problemsLabel(QuestionCorpusWorkItem item) {
		StringBuilder text = new StringBuilder();
		for (QuestionCorpusProblem problem : QuestionCorpusProblem.values()) {
			if (!item.status().hasProblem(problem)) {
				continue;
			}
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(problemLabel(problem));
		}
		if (isMissingMcqExplanation(item.question())) {
			if (!text.isEmpty()) {
				text.append(", ");
			}

			// Required explanation absence is deliberately a Dashboard work dimension,
			// not a false MISSING_ANSWER audit problem.
			text.append("Missing MCQ explanation");
		}
		return text.isEmpty() ? "—" : text.toString();
	}

	private String questionAssetCount(ExamCorpusStatus status) {
		ExamAssetExpectations expectations = status.assetExpectations();
		return presentExpected(expectations.availableQuestionBookletCount(),
				expectations.expectedQuestionBookletCount());
	}

	private List<Question> questionsForSelectedScope() {
		ExamCorpusStatus selectedExam = examTable.getSelectionModel().getSelectedItem();
		if (selectedExam == null) {
			return List.of();
		}
		BookletCorpusStatus selectedBooklet = bookletTable.getSelectionModel().getSelectedItem();
		return questions.stream().filter(question -> question.getExam().getId() == selectedExam.exam().getId())
				.filter(question -> selectedBooklet == null
						|| question.getBooklet().getId() == selectedBooklet.booklet().getId())
				.toList();
	}

	private List<Question> questionsForWorkingSubject(List<Question> sourceQuestions) {
		if (sourceQuestions == null) {
			throw new NullPointerException("questions");
		}
		for (Question question : sourceQuestions) {
			if (question == null) {
				throw new NullPointerException("questions contains null");
			}
		}

		// Persistent Subject identity is authoritative across reconstructed repository
		// domain objects.
		return sourceQuestions.stream()
				.filter(question -> question.getExam().getSubject().getId() == workingSubject.getId()).toList();
	}

	private QuestionCorpusFilter questionWorkFilter() {
		QuestionCorpusCompletionFilter completion = questionViewBox.getValue();

		// Questions have already been narrowed to selected Exam/booklet scope, so the
		// domain filter only needs Subject and ordinary completeness/problem criteria.
		return new QuestionCorpusFilter(workingSubject.getId(), null, null, null, completion, selectedQuestionProblem);
	}

	private void refreshCurriculumMappingSummary() {

		// Legacy metadata requires an existing syllabus version because workbook
		// classification must resolve against authoritative curriculum.
		importLegacyQuestionsButton.setDisable(!curriculumAvailable);
		if (!curriculumAvailable) {

			// No syllabus version exists for this Subject yet. Distinguish that onboarding
			// state from a valid curriculum that simply has no historical mapping pair.
			mapCurriculumButton.setDisable(true);
			curriculumMappingSummaryLabel.setText("No curriculum has been added.");
			return;
		}
		mapCurriculumButton.setDisable(mappingCoverages.isEmpty());
		if (mappingCoverages.isEmpty()) {

			// Curriculum exists, but no historical-to-current pair currently requires
			// mapping review.
			curriculumMappingSummaryLabel.setText("No mapping review is currently available.");
			return;
		}
		StringBuilder summary = new StringBuilder();
		for (CurriculumMappingCoverage coverage : mappingCoverages) {
			if (!summary.isEmpty()) {
				summary.append("    |    ");
			}
			summary.append(formatCurriculumMappingCoverage(coverage));
		}

		// Mapping review is deliberately displayed without changing any corpus summary
		// counts or ordinary Question-work filters.
		curriculumMappingSummaryLabel.setText(summary.toString());
	}

	private void refreshDashboard() {
		Long preferredExamId = selectedExamId();
		Long preferredBookletId = selectedBookletId();

		// Curriculum availability controls whether Exam creation is a valid next
		// action.
		updateAddExamActionState();
		refreshSummaryControls();

		// Curriculum mapping review is a parallel Subject status dimension rather than
		// part of Exam or Question completeness.
		refreshCurriculumMappingSummary();
		refreshExamTable(preferredExamId, preferredBookletId);
		refreshQuestionWork();
	}

	private void refreshExamTable(Long preferredExamId, Long preferredBookletId) {
		updateExamEmptyState();
		List<ExamCorpusStatus> filtered = filteredExamStatuses();
		examTable.getItems().setAll(filtered);
		ExamCorpusStatus preferred = preferredExamId == null ? null
				: filtered.stream().filter(status -> status.exam().getId() == preferredExamId.longValue()).findFirst()
						.orElse(null);
		if (preferred != null) {
			examTable.getSelectionModel().select(preferred);
			showSelectedExam(preferred, preferredBookletId);
			return;
		}
		if (!filtered.isEmpty()) {

			// The first visible Exam is selected automatically. Booklet scope remains
			// unselected unless a compatible previous booklet can be restored.
			examTable.getSelectionModel().selectFirst();
			return;
		}
		examTable.getSelectionModel().clearSelection();
		clearSelectedExam();
	}

	private void refreshQuestionWork() {
		List<QuestionCorpusWorkItem> workItems;
		if (mcqExplanationFilterActive) {

			// Missing explanations may belong to ordinarily complete Questions, so they
			// cannot be obtained from the standard incomplete-Question queue.
			workItems = missingMcqExplanationWorkItems(questionsForSelectedScope());
		} else {
			workItems = QuestionCorpusQueue.build(questionsForSelectedScope(), questionWorkFilter());
		}
		questionTable.getItems().setAll(workItems);

		// Reapply the table's current source-aware sort after replacing its rows. The
		// default is Provider -> Year -> Exam -> Booklet -> natural Question code.
		questionTable.sort();

		// A scope rebuild must never transfer the old selected row index onto a
		// different Question. Any deliberate post-correction restoration occurs later
		// by persistent Question ID.
		questionTable.getSelectionModel().clearSelection();
		questionResultCountLabel.setText(String.format("Showing %d Questions", workItems.size()));
		updateBulkActionState();
		updateCaptureActionState();
		updateQuestionActionState();
	}

	private void refreshSummaryControls() {
		QuestionCorpusSummary summary = QuestionCorpusQueue.summarise(filteredSummaryQuestions());
		totalQuestionsButton.setText(summary.totalQuestions() + " Questions");
		needsAttentionButton.setText(summary.incompleteQuestions() + " Need attention");
		missingContentButton.setText(summary.missingQuestionContent() + " Missing question content");
		missingAnswerButton.setText(summary.missingAnswer() + " Missing answers");
		unknownTypeButton.setText(summary.unknownResponseType() + " Unknown type");
		sharedContextButton.setText(summary.unresolvedSharedContext() + " Shared Context");
		int missingExplanations = scopeFilteredExamStatuses().stream()
				.mapToInt(status -> status.mcqExplanationSummary().missingExplanationCount()).sum();

		// The actionable count is sufficient at Subject level. Detailed
		// captured/eligible
		// coverage remains visible on each Question booklet.
		missingMcqExplanationButton.setText(missingExplanations + " Missing MCQ explanations");
	}

	private String responseTypeLabel(Question question) {
		return switch (question.getResponseType()) {
		case MULTIPLE_CHOICE -> "Multiple choice";
		case WRITTEN_RESPONSE -> "Written";
		case UNKNOWN -> "UNKNOWN";
		};
	}

	private List<ExamCorpusStatus> scopeFilteredExamStatuses() {
		ExamProvider provider = providerBox.getValue();
		Integer year = yearBox.getValue();
		ExamCaptureState state = examStateBox.getValue();

		// Provider, year and declared state define the ordinary structural scope.
		// Summary
		// drill-down is deliberately applied later so headline counts do not change
		// when
		// one of their own buttons is selected.
		return examStatuses.stream()
				.filter(status -> provider == null || status.exam().getProvider().getId() == provider.getId())
				.filter(status -> year == null || status.exam().getYear() == year.intValue())
				.filter(status -> state == null || status.declaredCaptureState() == state).toList();
	}

	private void selectAllUnknownShown() {
		questionTable.getSelectionModel().clearSelection();
		for (int index = 0; index < questionTable.getItems().size(); index++) {
			QuestionCorpusWorkItem item = questionTable.getItems().get(index);
			if (item.question().getResponseType() == QuestionResponseType.UNKNOWN) {
				questionTable.getSelectionModel().select(index);
			}
		}
		updateBulkActionState();
	}

	private Long selectedBookletId() {
		BookletCorpusStatus selected = bookletTable.getSelectionModel().getSelectedItem();
		return selected == null ? null : Long.valueOf(selected.booklet().getId());
	}

	private Long selectedExamId() {
		ExamCorpusStatus selected = examTable.getSelectionModel().getSelectedItem();
		return selected == null ? null : Long.valueOf(selected.exam().getId());
	}

	private List<Question> selectedQuestionsForBulkUpdate() {
		List<QuestionCorpusWorkItem> selected = List.copyOf(questionTable.getSelectionModel().getSelectedItems());
		if (selected.isEmpty()) {
			return List.of();
		}

		// Reject the whole selection rather than overwriting any already-authoritative
		// response type.
		if (selected.stream().anyMatch(item -> item.question().getResponseType() != QuestionResponseType.UNKNOWN)) {
			return List.of();
		}
		return selected.stream().map(QuestionCorpusWorkItem::question).toList();
	}

	private void setVisibleAndManaged(Node node, boolean visible) {
		node.setVisible(visible);
		node.setManaged(visible);
	}

	private void showSelectedBooklet(BookletCorpusStatus status) {
		selectedBookletLabel.setText("Selected booklet: " + status.booklet().getName());
		String warning = bookletWarningText(status);
		bookletWarningLabel.setText(warning);
		setVisibleAndManaged(bookletWarningLabel, !warning.isBlank());
		if (status.mcqExplanationCoverage().explanationCapable()) {
			mcqExplanationCoverageLabel
					.setText(String.format("MCQ explanation coverage for selected booklet: %d / %d eligible Questions",
							status.mcqExplanationCoverage().capturedExplanationCount(),
							status.mcqExplanationCoverage().eligibleQuestionCount()));
		} else {
			mcqExplanationCoverageLabel.setText("MCQ explanation coverage for selected booklet: not available");
		}
		setVisibleAndManaged(mcqExplanationCoverageLabel, true);

		// Structural investigation and operational capture remain independent actions
		// for the same selected booklet.
		updateCaptureActionState();
		updateExamAssetsActionState();
	}

	private void showSelectedExam(ExamCorpusStatus status, Long preferredBookletId) {
		selectedExamLabel.setText(String.format("%s %d — %s", status.exam().getProvider().getName(),
				status.exam().getYear(), status.exam().getName()));
		declaredExamStateLabel.setText("Declared state: " + status.declaredCaptureState().name());
		ExamAssetExpectations expectations = status.assetExpectations();
		selectedExamCountsLabel.setText(String.format(
				"Question booklets: %s     Answer booklets: %s     Questions: %d     Need work: %d",
				presentExpected(expectations.availableQuestionBookletCount(),
						expectations.expectedQuestionBookletCount()),
				presentExpected(expectations.availableAnswerFileCount(), expectations.expectedAnswerFileCount()),
				status.questionSummary().totalQuestions(), examWorkCount(status)));
		List<BookletCorpusStatus> displayedBooklets = filteredBookletStatuses(status);
		bookletTable.getItems().setAll(displayedBooklets);
		bookletTable.getSelectionModel().clearSelection();
		selectedBookletLabel.setText("Selected booklet: All booklets");
		setVisibleAndManaged(bookletWarningLabel, false);
		setVisibleAndManaged(mcqExplanationCoverageLabel, false);

		// Booklet capture requires an explicit booklet selection. Exam-wide structural
		// management and lifecycle operate directly from the selected Exam.
		updateCaptureActionState();
		updateExamAssetsActionState();
		updateExamLifecycleActionState();
		if (preferredBookletId == null) {
			return;
		}
		BookletCorpusStatus preferred = displayedBooklets.stream()
				.filter(bookletStatus -> bookletStatus.booklet().getId() == preferredBookletId.longValue()).findFirst()
				.orElse(null);
		if (preferred != null) {
			bookletTable.getSelectionModel().select(preferred);
			showSelectedBooklet(preferred);
		}
	}

	private QuestionCorpusWorkItem singleSelectedQuestionWorkItem() {
		List<QuestionCorpusWorkItem> selected = List.copyOf(questionTable.getSelectionModel().getSelectedItems());

		// Direct capture actions operate on exactly one explicit Question.
		// Multi-selection
		// remains reserved for the existing bulk UNKNOWN response-type workflow.
		return selected.size() == 1 ? selected.getFirst() : null;
	}

	private List<ExamCorpusStatus> statusesForWorkingSubject(List<ExamCorpusStatus> statuses) {
		if (statuses == null) {
			throw new NullPointerException("examStatuses");
		}
		for (ExamCorpusStatus status : statuses) {
			if (status == null) {
				throw new NullPointerException("examStatuses contains null");
			}
		}

		// Persistent Subject identity is the authoritative Dashboard boundary.
		return statuses.stream().filter(status -> status.exam().getSubject().getId() == workingSubject.getId())
				.toList();
	}

	private List<QuestionCorpusWorkItem> summaryFilteredQuestionWork() {
		if (mcqExplanationFilterActive) {

			// Explanation drill-down uses the same Provider/Year/Exam-state Subject scope
			// as the other headline indicators, but not ordinary Question incompleteness.
			return missingMcqExplanationWorkItems(filteredSummaryQuestions());
		}

		// Summary buttons are calculated over Provider/Year/Exam-state scope rather
		// than over an already selected Exam or booklet.
		QuestionCorpusFilter filter = new QuestionCorpusFilter(workingSubject.getId(), null, null, null,
				questionViewBox.getValue(), selectedQuestionProblem);
		return QuestionCorpusQueue.build(filteredSummaryQuestions(), filter);
	}

	private void updateAddExamActionState() {

		// Every Exam must belong to a Subject with curriculum. Prevent structural Exam
		// creation until that prerequisite exists instead of allowing an invalid path.
		addExamButton.setDisable(!curriculumAvailable);
		if (!curriculumAvailable) {
			noExamsLabel.setText("Add curriculum before adding an Exam.");
			return;
		}

		// Once curriculum exists, ordinary Exam onboarding becomes the next structural
		// action for an otherwise empty Subject.
		noExamsLabel.setText("No Exams have been added.");
	}

	private void updateBulkActionState() {
		boolean unknownVisible = questionTable.getItems().stream()
				.anyMatch(item -> item.question().getResponseType() == QuestionResponseType.UNKNOWN);
		selectAllUnknownButton.setDisable(!unknownVisible);
		boolean validSelection = !selectedQuestionsForBulkUpdate().isEmpty();
		setSelectedMultipleChoiceButton.setDisable(!validSelection);
		setSelectedWrittenResponseButton.setDisable(!validSelection);
	}

	private void updateCaptureActionState() {
		BookletCorpusStatus selected = bookletTable.getSelectionModel().getSelectedItem();
		Question existingQuestionWork = firstQuestionCaptureWorkQuestion(selected);

		// Existing Question-side work may be completed even for a COMPLETE Exam.
		// Creating a genuinely new Question still requires an ACTIVE Exam.
		boolean questionCaptureAvailable = selected != null && selected.questionPdfAvailable()
				&& (existingQuestionWork != null || !selected.booklet().getExam().isComplete());
		captureQuestionsButton.setDisable(!questionCaptureAvailable);

		// Booklet-level ordinary Answer capture retains its sequential unanswered
		// workflow.
		captureAnswersButton.setDisable(selected == null || firstMissingAnswerQuestion() == null);

		// Explanation capture is correction of already-persisted Answer material and is
		// therefore valid even when the Exam is already COMPLETE.
		captureMcqExplanationsButton
				.setDisable(selected == null || firstMissingMcqExplanationQuestion(selected) == null);
	}

	private void updateExamAssetsActionState() {
		ExamCorpusStatus selectedExam = examTable.getSelectionModel().getSelectedItem();
		boolean activeExamSelected = selectedExam != null
				&& selectedExam.declaredCaptureState() == ExamCaptureState.ACTIVE;

		// Exam / Assets changes authoritative Exam structure and source assets. A
		// COMPLETE Exam must first be deliberately reactivated through the lifecycle
		// action before structural management becomes available again.
		manageExamAssetsButton.setDisable(!activeExamSelected);
	}

	private void updateExamEmptyState() {
		boolean noExams = examStatuses.isEmpty();

		// Legacy preflight is valid before an Exam exists. Move the single action
		// beside Add Exam for onboarding, otherwise keep it on the normal filter row.
		moveLegacyImportButton(noExams ? examEmptyStateRow : examFilterRow);

		// Booklet and Question work becomes meaningful only after authoritative Exam
		// structure exists.
		setVisibleAndManaged(examEmptyStateRow, noExams);
		setVisibleAndManaged(examOperationalContent, !noExams);
		setVisibleAndManaged(bookletsSection, !noExams);
		setVisibleAndManaged(questionWorkSection, !noExams);
	}

	private void updateExamLifecycleActionState() {
		ExamCorpusStatus selected = examTable.getSelectionModel().getSelectedItem();
		if (selected == null) {
			examLifecycleButton.setText("Mark Complete");
			examLifecycleButton.setDisable(true);
			examLifecycleButton.setTooltip(new Tooltip("Select an Exam before changing its lifecycle state."));
			return;
		}
		if (selected.declaredCaptureState() == ExamCaptureState.COMPLETE) {

			// Reactivation is a deliberate structural decision and does not require the
			// completed Exam still to satisfy current audit readiness.
			examLifecycleButton.setText("Mark Active");
			examLifecycleButton.setDisable(false);
			examLifecycleButton.setTooltip(new Tooltip("Reactivate this Exam for structural changes."));
			return;
		}
		boolean ready = selected.isReadyForCompletion();
		examLifecycleButton.setText("Mark Complete");
		examLifecycleButton.setDisable(!ready);
		examLifecycleButton.setTooltip(new Tooltip(ready ? "Mark this fully captured Exam complete."
				: "Complete expected assets, Question counts and remaining Question work first."));
	}

	private void updateQuestionActionState() {
		QuestionCorpusWorkItem selected = singleSelectedQuestionWorkItem();
		boolean missingExplanation = selected != null && isMissingMcqExplanation(selected.question());
		if (missingExplanation) {
			completeSelectedAnswerButton.setText("Capture Explanation");
			completeSelectedAnswerButton
					.setTooltip(new Tooltip("Capture the missing MCQ explanation for the selected Question."));
			completeSelectedAnswerButton.setDisable(false);
		} else {
			completeSelectedAnswerButton.setText("Complete Selected Answer");
			completeSelectedAnswerButton
					.setTooltip(new Tooltip("Complete the missing Answer for the selected Question."));
			completeSelectedAnswerButton.setDisable(!canCompleteSelectedAnswer(selected));
		}

		// Question-side correction remains independently available if the same
		// persisted
		// Question also has Question-content work.
		completeSelectedQuestionButton.setDisable(!canCompleteSelectedQuestion(selected));
	}

	private String workLabel(ExamCorpusStatus status) {
		int work = examWorkCount(status);
		return work == 0 ? "—" : Integer.toString(work);
	}
}
