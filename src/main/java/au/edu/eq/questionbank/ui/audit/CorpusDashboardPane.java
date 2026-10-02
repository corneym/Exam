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
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.audit.BookletCorpusFinding;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusCompletionFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusQueue;
import au.edu.eq.questionbank.service.audit.QuestionCorpusSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Displays the operational Exam -> booklet -> Question Corpus Dashboard.
 */
final class CorpusDashboardPane extends VBox {

	private static final double SPACING = 8.0;
	private static final Insets PADDING = new Insets(10);
	private static final double EXAM_TABLE_HEIGHT = 190.0;
	private static final double BOOKLET_TABLE_HEIGHT = 190.0;
	private static final double QUESTION_TABLE_HEIGHT = 180.0;

	// Booklet scope must retain at least several immediately visible rows even when
	// the dialog is vertically constrained.
	private static final double BOOKLET_TABLE_MAX_HEIGHT = 280.0;
	private static final double BOOKLET_TABLE_MIN_HEIGHT = 125.0;

	// Question work is intentionally more compact because it remains scrollable.
	private static final double QUESTION_TABLE_MAX_HEIGHT = 210.0;
	private final Subject workingSubject;
	private List<ExamCorpusStatus> examStatuses;
	private List<Question> questions;
	private final Label workingSubjectLabel = new Label();
	private final Button refreshButton = new Button("Refresh");
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
	private final Label mcqExplanationSummaryLabel = new Label();
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
	private Runnable refreshHandler = () -> {
	};
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

	CorpusDashboardPane(Subject workingSubject, List<ExamCorpusStatus> examStatuses, List<Question> questions) {
		if (workingSubject == null) {
			throw new NullPointerException("workingSubject");
		}

		// Working Subject remains authoritative application state for both the
		// calculated Exam snapshots and the Question work queue.
		this.workingSubject = workingSubject;
		this.examStatuses = statusesForWorkingSubject(examStatuses);
		this.questions = questionsForWorkingSubject(questions);
		configureControls();
		configureActions();
		buildContent();
		populateFilterOptions();
		refreshDashboard();
	}

	void replaceData(List<ExamCorpusStatus> updatedStatuses, List<Question> updatedQuestions) {

		// Ordinary Refresh has no preferred Question to restore.
		replaceData(updatedStatuses, updatedQuestions, -1L);
	}

	void replaceData(List<ExamCorpusStatus> updatedStatuses, List<Question> updatedQuestions,
			long preferredQuestionId) {
		Long selectedExamId = selectedExamId();
		Long selectedBookletId = selectedBookletId();
		Long providerId = providerBox.getValue() == null ? null : providerBox.getValue().getId();
		Integer year = yearBox.getValue();
		ExamCaptureState examState = examStateBox.getValue();

		// Replace both representations from the same persistence refresh so structural
		// summaries and Question work cannot drift across separate UI snapshots.
		examStatuses = statusesForWorkingSubject(updatedStatuses);
		questions = questionsForWorkingSubject(updatedQuestions);
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
		refreshSummaryControls();
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

	void setAnswerCaptureHandler(Consumer<Question> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Answer capture remains application-owned because it coordinates the shared
		// PDF workspace and capture lifecycle.
		answerCaptureHandler = handler;
	}

	void setBulkResponseTypeHandler(BiConsumer<List<Question>, QuestionResponseType> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Bulk UNKNOWN resolution remains an existing operational Dashboard feature.
		bulkResponseTypeHandler = handler;
	}

	void setExamAssetsHandler(BiConsumer<Exam, ExamBooklet> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Structural correction is owned by the existing Exam/Assets workspace.
		examAssetsHandler = handler;
	}

	void setNewQuestionCaptureHandler(Consumer<ExamBooklet> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// New Question capture is booklet-level work because no Question row exists yet
		// for source Questions that have not been captured.
		newQuestionCaptureHandler = handler;
	}

	void setQuestionCorrectionHandler(Consumer<Question> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// Existing incomplete Question correction remains distinct from adding new
		// Questions to an incomplete booklet.
		questionCorrectionHandler = handler;
	}

	void setRefreshHandler(Runnable handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// The owning workflow supplies the persistence reload boundary.
		refreshHandler = handler;
		refreshButton.setDisable(false);
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

	private void applyQuestionFilter(QuestionCorpusCompletionFilter completion, QuestionCorpusProblem problem) {
		changingQuestionFilter = true;
		try {
			questionViewBox.setValue(completion);
		} finally {
			changingQuestionFilter = false;
		}

		// Summary buttons select one ordinary Question problem without changing the
		// current Exam or booklet selection.
		selectedQuestionProblem = problem;
		refreshQuestionWork();
	}

	private String bookletProblemLabel(BookletCorpusStatus status) {
		int problemCount = status.questionSummary().incompleteQuestions();

		// Structural booklet findings are displayed independently beneath the table,
		// matching the approved Dashboard design.
		return problemCount == 0 ? "—" : Integer.toString(problemCount);
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
		Label heading = new Label("CORPUS DASHBOARD");
		heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
		HBox subjectRow = new HBox(SPACING, new Label("Working Subject:"), workingSubjectLabel);
		subjectRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(subjectRow, Priority.ALWAYS);
		HBox headingRow = new HBox(SPACING, heading, subjectRow, refreshButton);
		headingRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(subjectRow, Priority.ALWAYS);
		HBox filterRow = new HBox(SPACING, new Label("Provider"), providerBox, new Label("Year"), yearBox,
				new Label("Exam state"), examStateBox, clearFiltersButton);
		filterRow.setAlignment(Pos.CENTER_LEFT);
		HBox summaryRowOne = new HBox(SPACING, totalQuestionsButton, needsAttentionButton, missingContentButton,
				missingAnswerButton);
		summaryRowOne.setAlignment(Pos.CENTER_LEFT);
		HBox summaryRowTwo = new HBox(SPACING, unknownTypeButton, sharedContextButton, mcqExplanationSummaryLabel);
		summaryRowTwo.setAlignment(Pos.CENTER_LEFT);
		Label examsHeading = new Label("EXAMS");
		examsHeading.setStyle("-fx-font-weight: bold;");
		Label selectedExamHeading = new Label("SELECTED EXAM");
		selectedExamHeading.setStyle("-fx-font-weight: bold;");
		HBox selectedExamTitleRow = new HBox(SPACING, selectedExamLabel, declaredExamStateLabel,
				manageExamAssetsButton);
		selectedExamTitleRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(selectedExamLabel, Priority.ALWAYS);
		Label bookletsHeading = new Label("BOOKLETS");
		bookletsHeading.setStyle("-fx-font-weight: bold;");
		bookletWarningLabel.setWrapText(true);
		setVisibleAndManaged(bookletWarningLabel, false);

		// Booklet actions operate on the selected source booklet rather than an
		// arbitrary existing Question row.
		HBox bookletActionsRow = new HBox(SPACING, captureQuestionsButton, captureAnswersButton);
		bookletActionsRow.setAlignment(Pos.CENTER_LEFT);
		Label questionWorkHeading = new Label("QUESTION WORK");
		questionWorkHeading.setStyle("-fx-font-weight: bold;");
		HBox questionFilterRow = new HBox(SPACING, new Label("Show"), questionViewBox, questionResultCountLabel);
		questionFilterRow.setAlignment(Pos.CENTER_LEFT);

		// Existing Question content and Shared Context correction remains a
		// Question-row
		// operation.
		HBox questionActionsRow = new HBox(SPACING, completeSelectedQuestionButton);
		questionActionsRow.setAlignment(Pos.CENTER_LEFT);
		HBox bulkResponseTypeRow = new HBox(SPACING, selectAllUnknownButton, setSelectedMultipleChoiceButton,
				setSelectedWrittenResponseButton);
		bulkResponseTypeRow.setAlignment(Pos.CENTER_LEFT);
		mcqExplanationCoverageLabel.setWrapText(true);
		setVisibleAndManaged(mcqExplanationCoverageLabel, false);

		// Extra vertical space belongs primarily to booklet scope. Question work
		// remains
		// a compact, independently scrollable table.
		VBox.setVgrow(bookletTable, Priority.ALWAYS);
		VBox.setVgrow(questionTable, Priority.NEVER);
		getChildren().addAll(headingRow, filterRow, summaryRowOne, summaryRowTwo, examsHeading, examTable,
				selectedExamHeading, selectedExamTitleRow, selectedExamCountsLabel, bookletsHeading, bookletTable,
				selectedBookletLabel, bookletWarningLabel, bookletActionsRow, questionWorkHeading, questionFilterRow,
				questionTable, questionActionsRow, bulkResponseTypeRow, mcqExplanationCoverageLabel);
		setSpacing(SPACING);
		setPadding(PADDING);
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

	private void captureQuestions() {
		BookletCorpusStatus selected = bookletTable.getSelectionModel().getSelectedItem();
		if (selected == null || captureQuestionsButton.isDisable()) {
			return;
		}

		// A booklet may remain structurally open even after its expected Question count
		// has already been reached. Require an explicit decision before over-capturing.
		if (!confirmQuestionCaptureBeyondExpected(selected)) {
			return;
		}

		// The selected booklet supplies the source PDF and structural identity for new
		// Question capture.
		newQuestionCaptureHandler.accept(selected.booklet());
	}

	private void clearFilters() {
		changingScopeFilters = true;
		try {

			// Clear only Dashboard-local scope. Working Subject remains authoritative.
			providerBox.setValue(null);
			yearBox.setValue(null);
			examStateBox.setValue(null);
		} finally {
			changingScopeFilters = false;
		}
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

		// No selected Exam or booklet can supply either capture or structural work.
		updateCaptureActionState();
		updateExamAssetsActionState();
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
		refreshButton.setOnAction(_ -> refreshHandler.run());
		clearFiltersButton.setOnAction(_ -> clearFilters());
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
		questionViewBox.valueProperty().addListener((_, _, _) -> {
			if (changingQuestionFilter) {
				return;
			}

			// Choosing a general Show mode clears any problem-specific summary filter.
			selectedQuestionProblem = null;
			refreshQuestionWork();
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
		captureQuestionsButton.setOnAction(_ -> captureQuestions());
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
		TableColumn<BookletCorpusStatus, String> problemsColumn = new TableColumn<>("Problems");
		problemsColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(bookletProblemLabel(data.getValue())));

		// Use the collection overload so mixed TableColumn value types do not create
		// a generic varargs array warning.
		bookletTable.getColumns().setAll(List.<TableColumn<BookletCorpusStatus, ?>>of(bookletColumn, formatColumn,
				pdfColumn, answerFileColumn, expectedColumn, foundColumn, partsColumn, problemsColumn));
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

	private void configureControls() {
		workingSubjectLabel.setId("corpus-dashboard-working-subject");
		workingSubjectLabel.setText(workingSubject.getName());
		refreshButton.setId("corpus-dashboard-refresh");

		// Persistence reload is supplied later by the owning dialog/application.
		refreshButton.setDisable(true);
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
				"Start Answer capture at the first ready Question with a missing Answer in the selected booklet."));
		captureAnswersButton.setDisable(true);
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
		questionViewBox.setId("corpus-dashboard-question-view");
		questionResultCountLabel.setId("corpus-dashboard-question-count");
		questionTable.setId("corpus-dashboard-question-work");
		questionViewBox.getItems().setAll(QuestionCorpusCompletionFilter.values());
		questionViewBox.setValue(QuestionCorpusCompletionFilter.INCOMPLETE);
		questionViewBox.setConverter(new QuestionCorpusAuditFilterConverter<>(this::completionLabel));
		questionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		// Question work is deliberately shorter than booklet scope and uses scrolling
		// when more work items are present.
		questionTable.setPrefHeight(QUESTION_TABLE_HEIGHT);
		questionTable.setMaxHeight(QUESTION_TABLE_MAX_HEIGHT);
		questionTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		TableColumn<QuestionCorpusWorkItem, String> questionColumn = new TableColumn<>("Question");
		questionColumn
				.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().question().getQuestionCode()));
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

		// Use the collection overload so mixed TableColumn value types do not create
		// a generic varargs array warning.
		questionTable.getColumns().setAll(List.<TableColumn<QuestionCorpusWorkItem, ?>>of(questionColumn, typeColumn,
				contentColumn, answerColumn, sharedContextColumn, problemColumn));
	}

	private void configureSummaryControls() {
		totalQuestionsButton.setId("corpus-dashboard-summary-total");
		needsAttentionButton.setId("corpus-dashboard-summary-attention");
		missingContentButton.setId("corpus-dashboard-summary-missing-content");
		missingAnswerButton.setId("corpus-dashboard-summary-missing-answer");
		unknownTypeButton.setId("corpus-dashboard-summary-unknown-type");
		sharedContextButton.setId("corpus-dashboard-summary-shared-context");
		mcqExplanationSummaryLabel.setId("corpus-dashboard-summary-mcq-explanations");
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

	private int examWorkCount(ExamCorpusStatus status) {

		// "Work" in the approved Dashboard means Question correction work. Structural
		// findings are visible independently through counts and warnings.
		return status.questionSummary().incompleteQuestions();
	}

	private List<ExamCorpusStatus> filteredExamStatuses() {
		ExamProvider provider = providerBox.getValue();
		Integer year = yearBox.getValue();
		ExamCaptureState state = examStateBox.getValue();
		return examStatuses.stream()
				.filter(status -> provider == null || status.exam().getProvider().getId() == provider.getId())
				.filter(status -> year == null || status.exam().getYear() == year.intValue())
				.filter(status -> state == null || status.declaredCaptureState() == state).toList();
	}

	private List<Question> filteredSummaryQuestions() {
		Set<Long> visibleExamIds = new HashSet<>();
		for (ExamCorpusStatus status : filteredExamStatuses()) {
			visibleExamIds.add(status.exam().getId());
		}

		// Top summary counts follow Provider/Year/Exam-state scope but remain
		// independent of the currently selected Exam or booklet.
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
		case MISSING_QUESTION_CONTENT -> "Missing content";
		case MISSING_ANSWER -> "Missing answer";
		case UNRESOLVED_SHARED_CONTEXT -> "Shared Context";
		case UNKNOWN_RESPONSE_TYPE -> "Unknown type";
		};
	}

	private String problemsLabel(QuestionCorpusWorkItem item) {
		if (item.status().isComplete()) {
			return "—";
		}
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
		return text.toString();
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

	private void refreshDashboard() {
		Long preferredExamId = selectedExamId();
		Long preferredBookletId = selectedBookletId();
		refreshSummaryControls();
		refreshExamTable(preferredExamId, preferredBookletId);
		refreshQuestionWork();
	}

	private void refreshExamTable(Long preferredExamId, Long preferredBookletId) {
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
		List<QuestionCorpusWorkItem> workItems = QuestionCorpusQueue.build(questionsForSelectedScope(),
				questionWorkFilter());
		questionTable.getItems().setAll(workItems);

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
		missingContentButton.setText(summary.missingQuestionContent() + " Missing content");
		missingAnswerButton.setText(summary.missingAnswer() + " Missing answers");
		unknownTypeButton.setText(summary.unknownResponseType() + " Unknown type");
		sharedContextButton.setText(summary.unresolvedSharedContext() + " Shared Context");
		int eligibleExplanations = 0;
		int capturedExplanations = 0;
		for (ExamCorpusStatus status : filteredExamStatuses()) {
			eligibleExplanations += status.mcqExplanationSummary().eligibleQuestionCount();
			capturedExplanations += status.mcqExplanationSummary().capturedExplanationCount();
		}

		// Explanation coverage is reported independently from ordinary incomplete
		// Question totals.
		mcqExplanationSummaryLabel
				.setText(String.format("MCQ explanations %d / %d", capturedExplanations, eligibleExplanations));
	}

	private String responseTypeLabel(Question question) {
		return switch (question.getResponseType()) {
		case MULTIPLE_CHOICE -> "Multiple choice";
		case WRITTEN_RESPONSE -> "Written";
		case UNKNOWN -> "UNKNOWN";
		};
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

	private void setVisibleAndManaged(Label label, boolean visible) {
		label.setVisible(visible);
		label.setManaged(visible);
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
		bookletTable.getItems().setAll(status.bookletStatuses());
		bookletTable.getSelectionModel().clearSelection();
		selectedBookletLabel.setText("Selected booklet: All booklets");
		setVisibleAndManaged(bookletWarningLabel, false);
		setVisibleAndManaged(mcqExplanationCoverageLabel, false);

		// Booklet capture requires an explicit booklet selection. Exam-wide structural
		// findings remain independently actionable.
		updateCaptureActionState();
		updateExamAssetsActionState();
		if (preferredBookletId == null) {
			return;
		}
		BookletCorpusStatus preferred = status.bookletStatuses().stream()
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

		// New Questions may be added only to an ACTIVE Exam whose authoritative
		// Question PDF is available.
		boolean questionCaptureAvailable = selected != null && selected.questionPdfAvailable()
				&& !selected.booklet().getExam().isComplete();
		captureQuestionsButton.setDisable(!questionCaptureAvailable);

		// Answer capture begins only when this booklet contains a missing Answer whose
		// earlier Question-side prerequisites have already been resolved.
		captureAnswersButton.setDisable(selected == null || firstMissingAnswerQuestion() == null);
	}

	private void updateExamAssetsActionState() {
		ExamCorpusStatus selectedExam = examTable.getSelectionModel().getSelectedItem();

		// Exam / Assets is available for every selected Exam. Audit findings may
		// explain
		// why the user should visit it, but they do not control access to the
		// workspace.
		manageExamAssetsButton.setDisable(selectedExam == null);
	}

	private void updateQuestionActionState() {
		QuestionCorpusWorkItem selected = singleSelectedQuestionWorkItem();

		// UNKNOWN response type is deliberately resolved first through the explicit
		// response-type controls rather than through Question-region correction.
		completeSelectedQuestionButton.setDisable(!canCompleteSelectedQuestion(selected));
	}

	private String workLabel(ExamCorpusStatus status) {
		int work = examWorkCount(status);
		return work == 0 ? "—" : Integer.toString(work);
	}
}
