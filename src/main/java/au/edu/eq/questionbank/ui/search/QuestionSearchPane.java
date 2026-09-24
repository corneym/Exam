package au.edu.eq.questionbank.ui.search;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Curriculum-aware question search pane.
 * <p>
 * Searches automatically against the selected current-curriculum scope from
 * Subject through Unit, Topic, Subtopic and Descriptor. Retrieval runs away
 * from the JavaFX application thread and stale results are discarded when the
 * user changes search scope.
 */
public class QuestionSearchPane extends BorderPane {

	private static final int PANE_PADDING = 10;
	private static final int PREVIEW_WIDTH = 820;
	private static final int DETAIL_ROWS = 6;
	private static final int SECTION_SPACING = 4;
	private static final int SECTION_TOP_PADDING = 6;
	private static final double RESULTS_DIVIDER_POSITION = 0.22;
	private static final double DETAILS_DIVIDER_POSITION = 0.48;
	private static final int SELECTOR_COLUMN_GAP = 8;
	private static final int SELECTOR_ROW_GAP = 6;
	private final CurriculumRepository curriculumRepository;
	private final QuestionRetrievalService retrievalService;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final Label syllabusValue = new Label();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> classificationBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> descriptorBox = new ComboBox<>();
	private final Label statusLabel = new Label();

	// Search presentation uses its own result type so current-curriculum matches
	// and future all-bank results can coexist without weakening retrieval
	// semantics.
	private final ListView<QuestionSearchResult> resultsList = new ListView<>();
	private final TextArea detailsArea = new TextArea();
	private SyllabusVersion currentSyllabus;

	// Complete-bank retrieval is deliberately separate from current-curriculum
	// applicability retrieval. All Questions does not evaluate syllabus mappings.
	private final Supplier<List<Question>> allQuestionsSupplier;
	private final ComboBox<QuestionSearchScope> searchScopeBox = new ComboBox<>();

	// Background Search tasks publish only UI-facing Search results.
	private Task<List<QuestionSearchResult>> activeSearchTask;
	private long searchGeneration;
	private boolean updatingControls;
	private final QuestionPreviewService previewService;
	private final ImageView previewImageView = new ImageView();
	private final Label previewStatusLabel = new Label();
	private Task<Optional<BufferedImage>> activePreviewTask;
	private long previewGeneration;
	private Task<?> activeHierarchyTask;
	private long hierarchyGeneration;
	private boolean disposed;
	private long questionIdToReselect = -1;

	// Distinguish "no subjects exist" from "the initial subject load was cancelled
	// before completion". This lets Current syllabus restart an interrupted load.
	private boolean subjectsLoaded;

	/**
	 * Creates the question-search pane.
	 *
	 * @param curriculumRepository current curriculum hierarchy lookup
	 * @param retrievalService     curriculum-aware question retrieval
	 * @param allQuestionsSupplier complete stored Question retrieval
	 * @param previewService       stored question image preview service
	 * @throws NullPointerException if any dependency is {@code null}
	 */
	public QuestionSearchPane(CurriculumRepository curriculumRepository, QuestionRetrievalService retrievalService,
			Supplier<List<Question>> allQuestionsSupplier, QuestionPreviewService previewService) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (retrievalService == null) {
			throw new NullPointerException("retrievalService");
		}
		if (allQuestionsSupplier == null) {
			throw new NullPointerException("allQuestionsSupplier");
		}
		if (previewService == null) {
			throw new NullPointerException("previewService");
		}
		this.curriculumRepository = curriculumRepository;
		this.retrievalService = retrievalService;
		this.allQuestionsSupplier = allQuestionsSupplier;
		this.previewService = previewService;
		setPadding(new Insets(PANE_PADDING));
		configureControls();
		configureHandlers();
		setTop(createSelectionPane());
		setCenter(createResultsAndDetailsPane());
		startSubjectLoading();
	}

	/**
	 * Cancels outstanding loads and clears results and previews. Idempotent; late
	 * task completions are ignored through generation and task-identity checks.
	 * Must be called on the JavaFX application thread.
	 */
	void dispose() {
		if (disposed) {
			return;
		}
		disposed = true;
		cancelActiveHierarchyLoad();
		cancelActiveSearch();
		cancelActivePreview();
		resultsList.getItems().clear();
		detailsArea.clear();
		clearPreview();
		statusLabel.setText("");
	}

	/**
	 * @return the selected persisted question, or {@code null} when no result is
	 *         selected
	 */
	Question getSelectedQuestion() {
		QuestionSearchResult result = resultsList.getSelectionModel().getSelectedItem();

		// Editing workflows operate on the authoritative persisted Question regardless
		// of which Search scope produced the visible result.
		return result == null ? null : result.question();
	}

	/**
	 * Repeats the most specific active search and reselects the edited question if
	 * it still matches. Has no effect after disposal.
	 *
	 * @param questionId the persistent identifier to reselect
	 */
	void refreshAfterEdit(long questionId) {
		if (disposed) {
			return;
		}
		questionIdToReselect = questionId;

		// An edit must refresh the scope the teacher was actually viewing. In
		// particular, All Questions must not silently become a curriculum search.
		if (searchScopeBox.getValue() == QuestionSearchScope.ALL_QUESTIONS) {
			startAllQuestionsSearch();
			return;
		}
		startMostSpecificCurrentSyllabusSearch();
		if (subjectBox.getValue() == null) {
			questionIdToReselect = -1;
		}
	}

	/**
	 * @return the selected search result property, whose value may be {@code null}
	 */
	ReadOnlyObjectProperty<QuestionSearchResult> selectedResultProperty() {

		// Dialog action enablement depends only on whether Search has a selected
		// result;
		// the result's scope remains an internal Search concern.
		return resultsList.getSelectionModel().selectedItemProperty();
	}

	private void cancelActiveHierarchyLoad() {
		hierarchyGeneration++;
		if (activeHierarchyTask != null) {
			activeHierarchyTask.cancel();
			activeHierarchyTask = null;
		}
	}

	private void cancelActivePreview() {
		previewGeneration++;
		if (activePreviewTask != null) {
			activePreviewTask.cancel(false);
			activePreviewTask = null;
		}
	}

	private void cancelActiveSearch() {
		searchGeneration++;
		if (activeSearchTask != null) {
			activeSearchTask.cancel();
			activeSearchTask = null;
		}
	}

	private void clearBelowSubject() {
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		updatingControls = true;
		try {
			currentSyllabus = null;
			syllabusValue.setText("No current syllabus");
			resetUnitBox();
			resetTopicBox();
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
	}

	private void clearBelowTopic() {
		updatingControls = true;
		try {
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
	}

	private void clearPreview() {
		previewImageView.setImage(null);
		previewStatusLabel.setText("");
	}

	private void clearResults() {
		resultsList.getItems().clear();
		detailsArea.clear();
	}

	private <T> void completeHierarchyLoad(Task<T> task, long generation, Consumer<T> onSucceeded) {

		// Ignore a hierarchy loaded for a selection that has since changed or been
		// disposed.
		if (generation != hierarchyGeneration || task != activeHierarchyTask) {
			return;
		}
		activeHierarchyTask = null;
		statusLabel.setText("");
		onSucceeded.accept(task.getValue());
	}

	private void completePreview(Task<Optional<BufferedImage>> task, long generation) {

		// A late image must never replace the preview for a newer selection.
		if (generation != previewGeneration || task != activePreviewTask) {
			return;
		}
		activePreviewTask = null;
		Optional<BufferedImage> preview = task.getValue();
		if (preview.isEmpty()) {
			previewStatusLabel.setText("No stored question image.");
			return;
		}
		Image image = SwingFXUtils.toFXImage(preview.get(), null);
		previewImageView.setImage(image);
		previewStatusLabel.setText("");
	}

	private void completeSearch(Task<List<QuestionSearchResult>> task, long generation) {

		// Cancellation can race with completion; only the current request may publish
		// results.
		if (generation != searchGeneration || task != activeSearchTask) {
			return;
		}
		activeSearchTask = null;
		List<QuestionSearchResult> results = task.getValue();
		resultsList.getItems().setAll(results);
		reselectEditedQuestion(results);
		updateSearchStatus();
	}

	private void configureControls() {
		subjectBox.setId("question-search-subject");
		unitBox.setId("question-search-unit");
		topicBox.setId("question-search-topic");
		classificationBox.setId("question-search-classification");
		descriptorBox.setId("question-search-descriptor");
		resultsList.setId("question-search-results");
		detailsArea.setId("question-search-details");
		statusLabel.setId("question-search-status");
		searchScopeBox.setId("question-search-scope");
		searchScopeBox.getItems().setAll(QuestionSearchScope.values());
		searchScopeBox.setValue(QuestionSearchScope.CURRENT_SYLLABUS);
		searchScopeBox.setMaxWidth(Double.MAX_VALUE);
		searchScopeBox.setButtonCell(new ListCell<>() {

			@Override
			protected void updateItem(QuestionSearchScope scope, boolean empty) {
				super.updateItem(scope, empty);
				setText(empty || scope == null ? "" : scope.displayText());
			}
		});
		searchScopeBox.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(QuestionSearchScope scope, boolean empty) {
				super.updateItem(scope, empty);
				setText(empty || scope == null ? "" : scope.displayText());
			}
		});
		subjectBox.setPromptText("Select subject");
		unitBox.setPromptText("Select unit");
		topicBox.setPromptText("Select topic");
		classificationBox.setPromptText("Select subtopic or descriptor");
		descriptorBox.setPromptText("Select descriptor");
		configurePromptDisplay(subjectBox);
		configurePromptDisplay(unitBox);
		configurePromptDisplay(topicBox);
		configurePromptDisplay(classificationBox);
		configurePromptDisplay(descriptorBox);
		previewImageView.setId("question-search-preview");
		previewStatusLabel.setId("question-search-preview-status");
		previewImageView.setPreserveRatio(true);
		previewImageView.setFitWidth(PREVIEW_WIDTH);
		previewImageView.setSmooth(true);
		syllabusValue.setText("No current syllabus");
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);
		descriptorBox.setDisable(true);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		unitBox.setMaxWidth(Double.MAX_VALUE);
		topicBox.setMaxWidth(Double.MAX_VALUE);
		classificationBox.setMaxWidth(Double.MAX_VALUE);
		descriptorBox.setMaxWidth(Double.MAX_VALUE);
		detailsArea.setEditable(false);
		detailsArea.setWrapText(true);
		detailsArea.setPrefRowCount(DETAIL_ROWS);
		resultsList.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(QuestionSearchResult result, boolean empty) {
				super.updateItem(result, empty);
				if (empty || result == null) {
					setText(null);
					return;
				}

				// Result labels are derived from the persisted Question. Search scope affects
				// why a Question matched, not its authoritative source identity.
				Question question = result.question();
				setText(question.getExam().getProvider().getName() + " " + question.getExam().getYear() + " — "
						+ question.getBooklet().getName() + " — " + question.getQuestionCode() + " — "
						+ question.getMarks() + " marks");
			}
		});
	}

	private void configureHandlers() {
		searchScopeBox.setOnAction(_ -> handleSearchScopeSelection());
		subjectBox.setOnAction(_ -> handleSubjectSelection());
		unitBox.setOnAction(_ -> handleUnitSelection());
		topicBox.setOnAction(_ -> handleTopicSelection());
		classificationBox.setOnAction(_ -> handleClassificationSelection());
		descriptorBox.setOnAction(_ -> handleDescriptorSelection());
		subjectBox.setOnMousePressed(_ -> handleSubjectBoxMousePress());
		unitBox.setOnMousePressed(_ -> handleUnitBoxMousePress());
		topicBox.setOnMousePressed(_ -> handleTopicBoxMousePress());
		classificationBox.setOnMousePressed(_ -> handleClassificationBoxMousePress());
		descriptorBox.setOnMousePressed(_ -> handleDescriptorBoxMousePress());
		resultsList.getSelectionModel().selectedItemProperty()
				.addListener((_, _, newResult) -> showResultDetails(newResult));
	}

	private <T> void configurePromptDisplay(ComboBox<T> comboBox) {
		comboBox.setButtonCell(new ListCell<>() {

			@Override
			protected void updateItem(T item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(comboBox.getPromptText());
					return;
				}
				setText(item.toString());
			}
		});
	}

	private VBox createQuestionDetailsPane() {
		Label label = new Label("Question details");
		label.setStyle("-fx-font-weight: bold;");
		VBox pane = new VBox(SECTION_SPACING, label, detailsArea);
		pane.setPadding(new Insets(SECTION_TOP_PADDING, 0, 0, 0));
		VBox.setVgrow(detailsArea, Priority.ALWAYS);
		return pane;
	}

	private VBox createQuestionPreviewPane() {
		Label label = new Label("Question preview");
		label.setStyle("-fx-font-weight: bold;");
		ScrollPane previewPane = new ScrollPane(previewImageView);
		previewPane.setFitToWidth(true);
		VBox pane = new VBox(SECTION_SPACING, label, previewStatusLabel, previewPane);
		pane.setPadding(new Insets(SECTION_TOP_PADDING, 0, 0, 0));
		VBox.setVgrow(previewPane, Priority.ALWAYS);
		return pane;
	}

	private SplitPane createResultsAndDetailsPane() {
		Label resultsLabel = new Label("Matching questions");
		resultsLabel.setStyle("-fx-font-weight: bold;");
		VBox resultsPane = new VBox(SECTION_SPACING, resultsLabel, resultsList);
		VBox.setVgrow(resultsList, Priority.ALWAYS);
		VBox detailsPane = createQuestionDetailsPane();
		VBox previewPane = createQuestionPreviewPane();
		SplitPane pane = new SplitPane(resultsPane, detailsPane, previewPane);
		pane.setOrientation(Orientation.VERTICAL);
		pane.setDividerPositions(RESULTS_DIVIDER_POSITION, DETAILS_DIVIDER_POSITION);
		return pane;
	}

	private GridPane createSelectionPane() {
		GridPane pane = new GridPane();
		pane.setHgap(SELECTOR_COLUMN_GAP);
		pane.setVgap(SELECTOR_ROW_GAP);
		pane.setPadding(new Insets(0, 0, PANE_PADDING, 0));
		pane.add(new Label("Search scope"), 0, 0);
		pane.add(searchScopeBox, 1, 0);
		pane.add(new Label("Subject"), 0, 1);
		pane.add(subjectBox, 1, 1);
		pane.add(new Label("Current syllabus"), 0, 2);
		pane.add(syllabusValue, 1, 2);
		pane.add(new Label("Unit"), 0, 3);
		pane.add(unitBox, 1, 3);
		pane.add(new Label("Topic"), 0, 4);
		pane.add(topicBox, 1, 4);
		pane.add(new Label("Subtopic / Descriptor"), 0, 5);
		pane.add(classificationBox, 1, 5);
		pane.add(new Label("Descriptor"), 0, 6);
		pane.add(descriptorBox, 1, 6);
		pane.add(statusLabel, 1, 7);
		GridPane.setHgrow(searchScopeBox, Priority.ALWAYS);
		GridPane.setHgrow(subjectBox, Priority.ALWAYS);
		GridPane.setHgrow(unitBox, Priority.ALWAYS);
		GridPane.setHgrow(topicBox, Priority.ALWAYS);
		GridPane.setHgrow(classificationBox, Priority.ALWAYS);
		GridPane.setHgrow(descriptorBox, Priority.ALWAYS);
		return pane;
	}

	private <T> void failHierarchyLoad(Task<T> task, long generation) {
		if (generation != hierarchyGeneration || task != activeHierarchyTask) {
			return;
		}
		activeHierarchyTask = null;
		invalidateCurrentSearch();
		Throwable failure = task.getException();
		if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
			statusLabel.setText("Curriculum navigation failed.");
		} else {
			statusLabel.setText("Curriculum navigation failed: " + failure.getMessage());
		}
	}

	private void failPreview(Task<Optional<BufferedImage>> task, long generation) {
		if (generation != previewGeneration || task != activePreviewTask) {
			return;
		}
		activePreviewTask = null;
		Throwable failure = task.getException();
		if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
			previewStatusLabel.setText("Question preview unavailable.");
		} else {
			previewStatusLabel.setText("Question preview unavailable: " + failure.getMessage());
		}
	}

	private void failSearch(Task<List<QuestionSearchResult>> task, long generation) {
		if (generation != searchGeneration || task != activeSearchTask) {
			return;
		}
		activeSearchTask = null;
		Throwable failure = task.getException();

		// Keep existing user-visible failure handling while the internal Search result
		// representation changes.
		if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
			statusLabel.setText("Question search failed.");
		} else {
			statusLabel.setText("Question search failed: " + failure.getMessage());
		}
	}

	private void handleClassificationBoxMousePress() {
		if (!updatingControls && classificationBox.getValue() != null) {
			Platform.runLater(this::handleClassificationSelection);
		}
	}

	private void handleClassificationSelection() {
		if (updatingControls) {
			return;
		}
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		CurriculumNode classification = classificationBox.getValue();
		updatingControls = true;
		try {
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
		if (classification == null) {
			return;
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC) {
			startAutomaticSearch(classification);
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(classification),
				children -> showDescriptors(classification, children));
	}

	private void handleDescriptorBoxMousePress() {
		if (!updatingControls && descriptorBox.getValue() != null) {
			Platform.runLater(this::handleDescriptorSelection);
		}
	}

	private void handleDescriptorSelection() {
		if (updatingControls) {
			return;
		}
		cancelActiveHierarchyLoad();
		startAutomaticSearch(descriptorBox.getValue());
	}

	private void handleSearchScopeSelection() {
		if (updatingControls || disposed) {
			return;
		}

		// A scope transition invalidates asynchronous work belonging to the previous
		// scope so late results cannot overwrite the new Search state.
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		if (searchScopeBox.getValue() == QuestionSearchScope.ALL_QUESTIONS) {
			setCurriculumControlsDisabled(true);

			// Subject remains available as an All Questions filter. If the constructor's
			// initial Subject load was cancelled by this scope change, restart it.
			if (!subjectsLoaded) {
				startSubjectLoading();
			}
			startAllQuestionsSearch();
			return;
		}
		restoreCurriculumControlState();
		startMostSpecificCurrentSyllabusSearch();
	}

	private void handleSubjectBoxMousePress() {
		if (!updatingControls && subjectBox.getValue() != null) {
			Platform.runLater(this::handleSubjectSelection);
		}
	}

	private void handleSubjectSelection() {
		if (updatingControls || disposed) {
			return;
		}
		clearBelowSubject();
		Subject subject = subjectBox.getValue();
		if (searchScopeBox.getValue() == QuestionSearchScope.ALL_QUESTIONS) {

			// All Questions filters directly by the persisted Exam Subject. It does not
			// load or evaluate current-syllabus navigation.
			startAllQuestionsSearch();
			return;
		}
		if (subject == null) {
			return;
		}
		startHierarchyLoad(() -> loadSubjectNavigation(subject),
				navigation -> showSubjectNavigation(subject, navigation));
	}

	private void handleTopicBoxMousePress() {
		if (!updatingControls && topicBox.getValue() != null) {
			Platform.runLater(this::handleTopicSelection);
		}
	}

	private void handleTopicSelection() {
		if (updatingControls) {
			return;
		}
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		clearBelowTopic();
		CurriculumNode topic = topicBox.getValue();
		if (topic == null) {
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(topic),
				classifications -> showClassifications(topic, classifications));
	}

	private void handleUnitBoxMousePress() {
		if (!updatingControls && unitBox.getValue() != null) {
			Platform.runLater(this::handleUnitSelection);
		}
	}

	private void handleUnitSelection() {
		if (updatingControls) {
			return;
		}
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		CurriculumNode unit = unitBox.getValue();
		updatingControls = true;
		try {
			resetTopicBox();
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
		if (unit == null) {
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(unit), topics -> showTopics(unit, topics));
	}

	private void invalidateCurrentSearch() {
		cancelActiveSearch();
		cancelActivePreview();
		resultsList.getItems().clear();
		detailsArea.clear();
		clearPreview();
		statusLabel.setText("");
	}

	private SubjectNavigation loadSubjectNavigation(Subject subject) {
		List<SyllabusVersion> versions = curriculumRepository.findVersionsForSubject(subject);
		SyllabusVersion currentVersion = null;
		for (SyllabusVersion version : versions) {
			if (!version.isCurrent()) {
				continue;
			}
			if (currentVersion != null) {
				throw new IllegalStateException("Subject has multiple current syllabus versions");
			}
			currentVersion = version;
		}
		if (currentVersion == null) {
			return new SubjectNavigation(null, List.of());
		}
		List<CurriculumNode> units = curriculumRepository.findRootNodes(currentVersion);
		return new SubjectNavigation(currentVersion, units);
	}

	private String nodeDescription(CurriculumNode node) {
		return node.getSyllabusVersion().getName() + " " + node.getCode() + " " + node.getName();
	}

	private void reselectEditedQuestion(List<QuestionSearchResult> results) {
		if (questionIdToReselect < 1) {
			return;
		}
		long questionId = questionIdToReselect;
		questionIdToReselect = -1;
		for (QuestionSearchResult result : results) {

			// Persistent Question identity survives metadata and capture edits even when
			// the Search result wrapper itself has been reconstructed.
			if (result.question().getId() != questionId) {
				continue;
			}
			resultsList.getSelectionModel().select(result);
			resultsList.scrollTo(result);
			return;
		}
	}

	private void resetClassificationBox() {
		classificationBox.getSelectionModel().clearSelection();
		classificationBox.setValue(null);
		classificationBox.getItems().clear();
		classificationBox.setPromptText("Select subtopic or descriptor");
		classificationBox.setDisable(true);
	}

	private void resetDescriptorBox() {
		descriptorBox.getSelectionModel().clearSelection();
		descriptorBox.setValue(null);
		descriptorBox.getItems().clear();
		descriptorBox.setPromptText("Select descriptor");
		descriptorBox.setDisable(true);
	}

	private void resetTopicBox() {
		topicBox.getSelectionModel().clearSelection();
		topicBox.setValue(null);
		topicBox.getItems().clear();
		topicBox.setPromptText("Select topic");
		topicBox.setDisable(true);
	}

	private void resetUnitBox() {
		unitBox.getSelectionModel().clearSelection();
		unitBox.setValue(null);
		unitBox.getItems().clear();
		unitBox.setPromptText("Select unit");
		unitBox.setDisable(true);
	}

	private void restoreCurriculumControlState() {

		// Re-enable only controls whose parent hierarchy has actually been loaded.
		setCurriculumControlsDisabled(false);
	}

	private void setCurriculumControlsDisabled(boolean disabled) {

		// Subject remains meaningful in All Questions because it filters by the
		// Question's persisted Exam Subject. Lower controls represent current-syllabus
		// applicability and therefore remain unavailable in that scope.
		subjectBox.setDisable(false);
		unitBox.setDisable(disabled || currentSyllabus == null || unitBox.getItems().isEmpty());
		topicBox.setDisable(disabled || unitBox.getValue() == null || topicBox.getItems().isEmpty());
		classificationBox.setDisable(disabled || topicBox.getValue() == null || classificationBox.getItems().isEmpty());
		descriptorBox
				.setDisable(disabled || classificationBox.getValue() == null || descriptorBox.getItems().isEmpty());
	}

	private void showClassifications(CurriculumNode topic, List<CurriculumNode> classifications) {
		updatingControls = true;
		try {
			classificationBox.getItems().setAll(classifications);
			classificationBox.setDisable(classifications.isEmpty());
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(topic);
	}

	private void showDescriptors(CurriculumNode classification, List<CurriculumNode> children) {
		updatingControls = true;
		try {
			descriptorBox.getItems().setAll(children);
			descriptorBox.setDisable(children.isEmpty());
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(classification);
	}

	private void showResultDetails(QuestionSearchResult result) {
		if (disposed) {
			return;
		}
		cancelActivePreview();
		clearPreview();
		if (result == null) {
			detailsArea.clear();
			return;
		}
		Question question = result.question();
		String applicabilityText;
		if (result.scope() == QuestionSearchScope.ALL_QUESTIONS) {

			// All-bank Search deliberately does not evaluate current curriculum mappings.
			// Empty applicability in that scope therefore must not be presented as
			// evidence that the Question is inapplicable.
			applicabilityText = "Not evaluated in All Questions scope.";
		} else {
			StringJoiner applicability = new StringJoiner(System.lineSeparator());
			for (CurriculumNode node : result.currentApplicability()) {
				applicability.add(nodeDescription(node));
			}
			applicabilityText = applicability.toString();
		}
		String questionText = question.getQuestionText();
		if (questionText.isBlank()) {
			questionText = "No transcribed question text.";
		}
		detailsArea.setText("""
				Question: %s
				Marks: %d

				Original classification:
				%s

				Current applicability:
				%s

				Question text:
				%s
				""".formatted(question.getQuestionCode(), question.getMarks(),
				nodeDescription(question.getClassification()), applicabilityText, questionText));
		startQuestionPreview(question);
	}

	private void showSubjectNavigation(Subject subject, SubjectNavigation navigation) {
		currentSyllabus = navigation.currentSyllabus();
		if (currentSyllabus == null) {
			statusLabel.setText("No current syllabus available.");
			return;
		}
		syllabusValue.setText(currentSyllabus.getName());
		updatingControls = true;
		try {
			unitBox.getItems().setAll(navigation.units());
			unitBox.getSelectionModel().clearSelection();
			unitBox.setValue(null);
			unitBox.setDisable(navigation.units().isEmpty());
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(subject);
	}

	private void showTopics(CurriculumNode unit, List<CurriculumNode> topics) {
		updatingControls = true;
		try {
			topicBox.getItems().setAll(topics);
			topicBox.setDisable(topics.isEmpty());
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(unit);
	}

	private void startAllQuestionsSearch() {
		Subject selectedSubject = subjectBox.getValue();

		// Capture the FX control value before starting the background task. The task
		// itself must not read JavaFX controls.
		startAutomaticSearch(() -> {
			List<Question> questions = allQuestionsSupplier.get();
			if (selectedSubject != null) {
				questions = questions.stream()
						.filter(question -> selectedSubject.equals(question.getExam().getSubject())).toList();
			}
			return QuestionSearchResult.allQuestionResults(questions);
		});
	}

	private void startAutomaticSearch(CurriculumNode currentNode) {
		if (disposed) {
			return;
		}
		if (currentNode == null) {
			cancelActiveSearch();
			clearResults();
			statusLabel.setText("");
			return;
		}

		// The retrieval service remains authoritative for current-curriculum
		// applicability. Convert its results only at the Search presentation boundary.
		startAutomaticSearch(() -> QuestionSearchResult
				.currentSyllabusResults(retrievalService.findQuestionsApplicableTo(currentNode)));
	}

	private void startAutomaticSearch(Subject subject) {
		if (subject == null) {
			cancelActiveSearch();
			clearResults();
			statusLabel.setText("");
			return;
		}

		// Subject Search still means applicability anywhere in that Subject's current
		// syllabus; the new result wrapper does not alter retrieval semantics.
		startAutomaticSearch(
				() -> QuestionSearchResult.currentSyllabusResults(retrievalService.findQuestionsApplicableTo(subject)));
	}

	private void startAutomaticSearch(Supplier<List<QuestionSearchResult>> retrieval) {
		cancelActiveSearch();
		clearResults();
		long generation = searchGeneration;
		statusLabel.setText("Searching...");
		Task<List<QuestionSearchResult>> task = new Task<>() {

			@Override
			protected List<QuestionSearchResult> call() {

				// Retrieval and source ordering remain off the JavaFX thread.
				return retrieval.get();
			}
		};
		activeSearchTask = task;
		task.setOnSucceeded(_ -> completeSearch(task, generation));
		task.setOnFailed(_ -> failSearch(task, generation));
		Thread.ofVirtual().name("question-search").start(task);
	}

	private <T> void startHierarchyLoad(Supplier<T> loader, Consumer<T> onSucceeded) {
		if (disposed) {
			return;
		}
		cancelActiveHierarchyLoad();
		long generation = hierarchyGeneration;
		statusLabel.setText("Loading curriculum...");
		Task<T> task = new Task<>() {

			@Override
			protected T call() {
				return loader.get();
			}
		};
		activeHierarchyTask = task;
		task.setOnSucceeded(_ -> completeHierarchyLoad(task, generation, onSucceeded));
		task.setOnFailed(_ -> failHierarchyLoad(task, generation));
		Thread.ofVirtual().name("curriculum-navigation").start(task);
	}

	private void startMostSpecificCurrentSyllabusSearch() {
		CurriculumNode descriptor = descriptorBox.getValue();
		if (descriptor != null) {
			startAutomaticSearch(descriptor);
			return;
		}
		CurriculumNode classification = classificationBox.getValue();
		if (classification != null) {
			startAutomaticSearch(classification);
			return;
		}
		CurriculumNode topic = topicBox.getValue();
		if (topic != null) {
			startAutomaticSearch(topic);
			return;
		}
		CurriculumNode unit = unitBox.getValue();
		if (unit != null) {
			startAutomaticSearch(unit);
			return;
		}
		Subject subject = subjectBox.getValue();
		if (subject == null) {

			// Switching to All Questions may have cancelled the constructor's initial
			// subject load. Returning to Current syllabus must make that navigation
			// usable again rather than leaving an empty Subject control permanently.
			if (!subjectsLoaded) {
				startSubjectLoading();
			} else {
				statusLabel.setText("");
			}
			return;
		}

		// A Subject may also have been selected while its hierarchy load was cancelled
		// by switching scope. Reload that hierarchy rather than searching incomplete
		// navigation state.
		if (currentSyllabus == null) {
			handleSubjectSelection();
			return;
		}
		startAutomaticSearch(subject);
	}

	private void startQuestionPreview(Question question) {
		if (disposed) {
			return;
		}
		if (question.getRegions().isEmpty()) {
			previewStatusLabel.setText("No stored question image.");
			return;
		}
		long generation = previewGeneration;
		previewStatusLabel.setText("Loading preview...");
		Task<Optional<BufferedImage>> task = new Task<>() {

			@Override
			protected Optional<BufferedImage> call() throws Exception {
				return previewService.loadPreview(question);
			}
		};
		activePreviewTask = task;
		task.setOnSucceeded(_ -> completePreview(task, generation));
		task.setOnFailed(_ -> failPreview(task, generation));
		Thread.ofVirtual().name("question-preview").start(task);
	}

	private void startSubjectLoading() {
		startHierarchyLoad(curriculumRepository::findAllSubjects, subjects -> {

			// Mark completion even when the repository legitimately contains no Subjects.
			// A false value therefore means loading never completed successfully.
			subjectsLoaded = true;
			subjectBox.getItems().setAll(subjects);

			// In All Questions, Subject loading is auxiliary to the Question search.
			// Restore whichever Search status is currently authoritative.
			if (searchScopeBox.getValue() == QuestionSearchScope.ALL_QUESTIONS) {
				subjectBox.setDisable(false);
				if (activeSearchTask != null) {
					statusLabel.setText("Searching...");
				} else {
					updateSearchStatus();
				}
			}
		});
	}

	private void updateSearchStatus() {
		int resultCount = resultsList.getItems().size();
		if (resultCount == 0) {
			statusLabel.setText("No questions found.");
		} else if (resultCount == 1) {
			statusLabel.setText("1 question found.");
		} else {
			statusLabel.setText(resultCount + " questions found.");
		}
	}

	private record SubjectNavigation(SyllabusVersion currentSyllabus, List<CurriculumNode> units) {
	}
}
