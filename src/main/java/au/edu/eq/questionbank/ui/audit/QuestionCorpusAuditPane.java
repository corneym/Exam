package au.edu.eq.questionbank.ui.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.audit.QuestionCorpusCompletionFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusFilter;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusQueue;
import au.edu.eq.questionbank.service.audit.QuestionCorpusSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Displays corpus-completeness totals and a filterable Question work queue.
 */
final class QuestionCorpusAuditPane extends VBox {

	private static final double SPACING = 8.0;
	private static final Insets PADDING = new Insets(10);
	private List<Question> questions;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<ExamProvider> providerBox = new ComboBox<>();
	private final ComboBox<Integer> yearBox = new ComboBox<>();
	private final ComboBox<ExamBooklet> bookletBox = new ComboBox<>();
	private final ComboBox<QuestionCorpusCompletionFilter> completionBox = new ComboBox<>();
	private final ComboBox<QuestionCorpusProblem> problemBox = new ComboBox<>();
	private final Button clearFiltersButton = new Button("Clear filters");
	private final Label summaryLabel = new Label();
	private final Label resultCountLabel = new Label();
	private final ListView<QuestionCorpusWorkItem> workItems = new ListView<>();
	private final Button selectAllUnknownButton = new Button("Select all unknown shown");
	private final Button setSelectedMultipleChoiceButton = new Button("Set selected: Multiple choice");
	private final Button setSelectedWrittenResponseButton = new Button("Set selected: Written response");
	private BiConsumer<List<Question>, QuestionResponseType> bulkResponseTypeHandler = (_, _) -> {
	};

	QuestionCorpusAuditPane(List<Question> questions) {
		this.questions = copyQuestions(questions);
		configureControls();
		populateFilterOptions();
		configureActions();
		buildContent();
		refresh();
	}

	private static List<Question> copyQuestions(List<Question> questions) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		for (Question question : questions) {
			if (question == null) {
				throw new NullPointerException("questions contains null");
			}
		}
		return List.copyOf(questions);
	}

	QuestionCorpusWorkItem getSelectedWorkItem() {
		return workItems.getSelectionModel().getSelectedItem();
	}

	void refreshQuestions(List<Question> updatedQuestions, long preferredQuestionId) {
		Long subjectId = subjectBox.getValue() == null ? null : subjectBox.getValue().getId();
		Long providerId = providerBox.getValue() == null ? null : providerBox.getValue().getId();
		Integer year = yearBox.getValue();
		Long bookletId = bookletBox.getValue() == null ? null : bookletBox.getValue().getId();
		QuestionCorpusCompletionFilter completion = completionBox.getValue();
		QuestionCorpusProblem problem = problemBox.getValue();
		questions = copyQuestions(updatedQuestions);
		populateFilterOptions();
		subjectBox.setValue(subjectId == null ? null
				: subjectBox.getItems().stream().filter(subject -> subject.getId() == subjectId.longValue()).findFirst()
						.orElse(null));
		providerBox.setValue(providerId == null ? null
				: providerBox.getItems().stream().filter(provider -> provider.getId() == providerId.longValue())
						.findFirst().orElse(null));
		yearBox.setValue(year != null && yearBox.getItems().contains(year) ? year : null);
		bookletBox.setValue(bookletId == null ? null
				: bookletBox.getItems().stream().filter(booklet -> booklet.getId() == bookletId.longValue()).findFirst()
						.orElse(null));
		completionBox.setValue(completion == null ? QuestionCorpusCompletionFilter.ALL : completion);
		problemBox.setValue(problem);
		refresh();
		QuestionCorpusWorkItem preferred = workItems.getItems().stream()
				.filter(item -> item.question().getId() == preferredQuestionId).findFirst().orElse(null);
		if (preferred != null) {
			workItems.getSelectionModel().select(preferred);
			workItems.scrollTo(preferred);
			return;
		}
		if (!workItems.getItems().isEmpty()) {
			workItems.getSelectionModel().selectFirst();
			workItems.scrollTo(0);
		}
	}

	ReadOnlyObjectProperty<QuestionCorpusWorkItem> selectedWorkItemProperty() {
		return workItems.getSelectionModel().selectedItemProperty();
	}

	ObservableList<QuestionCorpusWorkItem> selectedWorkItems() {
		return workItems.getSelectionModel().getSelectedItems();
	}

	void setBulkResponseTypeHandler(BiConsumer<List<Question>, QuestionResponseType> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}
		bulkResponseTypeHandler = handler;
	}

	private void applyBulkResponseType(QuestionResponseType responseType) {
		List<Question> selected = selectedQuestionsForBulkUpdate();
		if (selected.isEmpty()) {
			return;
		}
		bulkResponseTypeHandler.accept(selected, responseType);
	}

	private void buildContent() {
		Label heading = new Label("Question-bank completeness");
		heading.setStyle("-fx-font-weight: bold;");
		HBox firstFilterRow = new HBox(SPACING, new Label("Subject"), subjectBox, new Label("Provider"), providerBox,
				new Label("Year"), yearBox, new Label("Booklet"), bookletBox);
		firstFilterRow.setAlignment(Pos.CENTER_LEFT);
		HBox secondFilterRow = new HBox(SPACING, new Label("Completion"), completionBox, new Label("Problem"),
				problemBox, clearFiltersButton);
		secondFilterRow.setAlignment(Pos.CENTER_LEFT);
		HBox bulkResponseTypeRow = new HBox(SPACING, selectAllUnknownButton, setSelectedMultipleChoiceButton,
				setSelectedWrittenResponseButton);
		bulkResponseTypeRow.setAlignment(Pos.CENTER_LEFT);
		summaryLabel.setWrapText(true);
		VBox.setVgrow(workItems, Priority.ALWAYS);
		getChildren().addAll(heading, firstFilterRow, secondFilterRow, bulkResponseTypeRow, summaryLabel,
				resultCountLabel, workItems);
		setSpacing(SPACING);
		setPadding(PADDING);
	}

	private void clearFilters() {
		subjectBox.setValue(null);
		providerBox.setValue(null);
		yearBox.setValue(null);
		bookletBox.setValue(null);
		completionBox.setValue(QuestionCorpusCompletionFilter.ALL);
		problemBox.setValue(null);
		refresh();
	}

	private void configureActions() {
		subjectBox.valueProperty().addListener((_, _, _) -> refresh());
		providerBox.valueProperty().addListener((_, _, _) -> refresh());
		yearBox.valueProperty().addListener((_, _, _) -> refresh());
		bookletBox.valueProperty().addListener((_, _, _) -> refresh());
		completionBox.valueProperty().addListener((_, _, _) -> refresh());
		problemBox.valueProperty().addListener((_, _, _) -> refresh());
		clearFiltersButton.setOnAction(_ -> clearFilters());
		selectAllUnknownButton.setOnAction(_ -> selectAllUnknownShown());
		setSelectedMultipleChoiceButton.setOnAction(_ -> applyBulkResponseType(QuestionResponseType.MULTIPLE_CHOICE));
		setSelectedWrittenResponseButton.setOnAction(_ -> applyBulkResponseType(QuestionResponseType.WRITTEN_RESPONSE));
		workItems.getSelectionModel().getSelectedItems()
				.addListener((ListChangeListener<QuestionCorpusWorkItem>) _ -> updateBulkActionState());
	}

	private void configureControls() {
		subjectBox.setId("corpus-filter-subject");
		providerBox.setId("corpus-filter-provider");
		yearBox.setId("corpus-filter-year");
		bookletBox.setId("corpus-filter-booklet");
		completionBox.setId("corpus-filter-completion");
		problemBox.setId("corpus-filter-problem");
		clearFiltersButton.setId("corpus-clear-filters");
		summaryLabel.setId("corpus-summary");
		resultCountLabel.setId("corpus-result-count");
		workItems.setId("corpus-work-items");
		subjectBox.setPromptText("All subjects");
		providerBox.setPromptText("All providers");
		yearBox.setPromptText("All years");
		bookletBox.setPromptText("All booklets");
		problemBox.setPromptText("All problems");
		completionBox.getItems().setAll(QuestionCorpusCompletionFilter.values());
		completionBox.setValue(QuestionCorpusCompletionFilter.ALL);
		problemBox.getItems().setAll(QuestionCorpusProblem.values());
		providerBox.setConverter(new StringConverter<ExamProvider>() {

			@Override
			public ExamProvider fromString(String text) {
				return null;
			}

			@Override
			public String toString(ExamProvider provider) {
				return provider == null ? "" : provider.getName();
			}
		});
		bookletBox.setConverter(new StringConverter<ExamBooklet>() {

			@Override
			public ExamBooklet fromString(String text) {
				return null;
			}

			@Override
			public String toString(ExamBooklet booklet) {
				if (booklet == null) {
					return "";
				}
				return String.format("%s %d — %s", booklet.getExam().getProvider().getName(),
						booklet.getExam().getYear(), booklet.getName());
			}
		});
		completionBox.setConverter(new StringConverter<QuestionCorpusCompletionFilter>() {

			@Override
			public QuestionCorpusCompletionFilter fromString(String text) {
				return null;
			}

			@Override
			public String toString(QuestionCorpusCompletionFilter value) {
				if (value == null) {
					return "";
				}
				return switch (value) {
				case ALL -> "All";
				case COMPLETE -> "Complete";
				case INCOMPLETE -> "Incomplete";
				};
			}
		});
		problemBox.setConverter(new StringConverter<QuestionCorpusProblem>() {

			@Override
			public QuestionCorpusProblem fromString(String text) {
				return null;
			}

			@Override
			public String toString(QuestionCorpusProblem problem) {
				return problem == null ? "" : problemLabel(problem);
			}
		});
		workItems.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(QuestionCorpusWorkItem item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					return;
				}
				setText(workItemLabel(item));
			}
		});
		selectAllUnknownButton.setId("corpus-select-all-unknown");
		setSelectedMultipleChoiceButton.setId("corpus-set-multiple-choice");
		setSelectedWrittenResponseButton.setId("corpus-set-written-response");
		workItems.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		setSelectedMultipleChoiceButton.setDisable(true);
		setSelectedWrittenResponseButton.setDisable(true);
	}

	private QuestionCorpusFilter createFilter(QuestionCorpusCompletionFilter completion,
			QuestionCorpusProblem problem) {
		Subject subject = subjectBox.getValue();
		ExamProvider provider = providerBox.getValue();
		ExamBooklet booklet = bookletBox.getValue();
		return new QuestionCorpusFilter(subject == null ? null : subject.getId(),
				provider == null ? null : provider.getId(), yearBox.getValue(),
				booklet == null ? null : booklet.getId(), completion, problem);
	}

	private void populateFilterOptions() {
		Map<Long, Subject> subjects = new LinkedHashMap<>();
		Map<Long, ExamProvider> providers = new LinkedHashMap<>();
		Map<Integer, Integer> years = new LinkedHashMap<>();
		Map<Long, ExamBooklet> booklets = new LinkedHashMap<>();
		for (Question question : questions) {
			Subject subject = question.getExam().getSubject();
			ExamProvider provider = question.getExam().getProvider();
			int year = question.getExam().getYear();
			ExamBooklet booklet = question.getBooklet();
			subjects.putIfAbsent(subject.getId(), subject);
			providers.putIfAbsent(provider.getId(), provider);
			years.putIfAbsent(year, year);
			booklets.putIfAbsent(booklet.getId(), booklet);
		}
		subjectBox.getItems().setAll(subjects.values());
		providerBox.getItems().setAll(providers.values());
		yearBox.getItems().setAll(years.values());
		bookletBox.getItems().setAll(booklets.values());
	}

	private String problemLabel(QuestionCorpusProblem problem) {
		return switch (problem) {
		case MISSING_QUESTION_SOURCE -> "Missing question source";
		case MISSING_ANSWER -> "Missing answer";
		case UNRESOLVED_SHARED_CONTEXT -> "Unresolved shared context";
		case UNKNOWN_RESPONSE_TYPE -> "Unknown response type";
		};
	}

	private String problemsLabel(QuestionCorpusWorkItem item) {
		if (item.status().isComplete()) {
			return "Complete";
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

	private void refresh() {

		// Summarise the exam scope before completion and problem filters narrow the
		// work list.
		QuestionCorpusFilter scopeFilter = createFilter(QuestionCorpusCompletionFilter.ALL, null);
		List<Question> scopeQuestions = QuestionCorpusQueue.build(questions, scopeFilter).stream()
				.map(QuestionCorpusWorkItem::question).toList();
		QuestionCorpusSummary summary = QuestionCorpusQueue.summarise(scopeQuestions);
		summaryLabel.setText(summaryText(summary));
		QuestionCorpusFilter workFilter = createFilter(completionBox.getValue(), problemBox.getValue());
		List<QuestionCorpusWorkItem> filtered = QuestionCorpusQueue.build(questions, workFilter);
		workItems.getItems().setAll(filtered);
		resultCountLabel.setText(String.format("Showing %d question(s)", filtered.size()));
		updateBulkActionState();
	}

	private void selectAllUnknownShown() {
		workItems.getSelectionModel().clearSelection();
		for (int index = 0; index < workItems.getItems().size(); index++) {
			QuestionCorpusWorkItem item = workItems.getItems().get(index);
			if (item.question().getResponseType() == QuestionResponseType.UNKNOWN) {
				workItems.getSelectionModel().select(index);
			}
		}
		updateBulkActionState();
	}

	private List<Question> selectedQuestionsForBulkUpdate() {
		List<QuestionCorpusWorkItem> selected = List.copyOf(workItems.getSelectionModel().getSelectedItems());
		if (selected.isEmpty()) {
			return List.of();
		}

		// Reject the entire selection rather than overwrite any already-known response
		// type.
		if (selected.stream().anyMatch(item -> item.question().getResponseType() != QuestionResponseType.UNKNOWN)) {
			return List.of();
		}
		return selected.stream().map(QuestionCorpusWorkItem::question).toList();
	}

	private String summaryText(QuestionCorpusSummary summary) {
		return String.format(
				"Total: %d    Complete: %d    Incomplete: %d    "
						+ "Missing question source: %d    Missing answer: %d    "
						+ "Unresolved shared context: %d    Unknown response type: %d",
				summary.totalQuestions(), summary.completeQuestions(), summary.incompleteQuestions(),
				summary.missingQuestionSource(), summary.missingAnswer(), summary.unresolvedSharedContext(),
				summary.unknownResponseType());
	}

	private void updateBulkActionState() {
		boolean unknownVisible = workItems.getItems().stream()
				.anyMatch(item -> item.question().getResponseType() == QuestionResponseType.UNKNOWN);
		selectAllUnknownButton.setDisable(!unknownVisible);
		boolean validSelection = !selectedQuestionsForBulkUpdate().isEmpty();
		setSelectedMultipleChoiceButton.setDisable(!validSelection);
		setSelectedWrittenResponseButton.setDisable(!validSelection);
	}

	private String workItemLabel(QuestionCorpusWorkItem item) {
		Question question = item.question();
		String problems = problemsLabel(item);
		return String.format("%s %d — %s — %s — %s", question.getExam().getProvider().getName(),
				question.getExam().getYear(), question.getBooklet().getName(), question.getQuestionCode(), problems);
	}
}
