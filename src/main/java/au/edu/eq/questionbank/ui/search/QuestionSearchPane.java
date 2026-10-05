package au.edu.eq.questionbank.ui.search;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.assessment.QuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Curriculum-aware question search pane.
 * <p>
 * Searches automatically against the selected current-curriculum scope from
 * Subject through Unit, Topic, Subtopic and Descriptor. Retrieval runs away
 * from the JavaFX application thread and stale results are discarded when the
 * user changes search scope.
 * <p>
 * The visible workspace is arranged as two task columns. Search controls,
 * matching Questions and basic Question details occupy the left column.
 * Selected-Question classification, revision-output applicability and the
 * Question preview occupy the right column. This keeps the Search workflow
 * vertically compact without changing retrieval or editing semantics.
 */
public class QuestionSearchPane extends BorderPane {

	private static final int PANE_PADDING = 10;
	private static final int DETAIL_ROWS = 6;
	private static final int SECTION_SPACING = 4;
	private static final int SECTION_TOP_PADDING = 6;
	private static final int SELECTOR_COLUMN_GAP = 8;
	private static final int SELECTOR_ROW_GAP = 6;
	private static final double COLUMN_DIVIDER_POSITION = 0.35;
	private static final double LEFT_CONTENT_DIVIDER_POSITION = 0.52;
	private final CurriculumRepository curriculumRepository;
	private final QuestionRetrievalService retrievalService;
	private final Label syllabusValue = new Label();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> classificationBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> descriptorBox = new ComboBox<>();
	private final Label statusLabel = new Label();
	private final Label selectedSyllabusValue = new Label();
	private final ComboBox<CurriculumNode> selectedUnitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> selectedTopicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> selectedSubtopicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> selectedDescriptorBox = new ComboBox<>();
	private final GridPane selectedClassificationPane = new GridPane();

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
	private final BooleanProperty classificationDirty = new SimpleBooleanProperty(false);

	// Population of the selected-Question classification controls must not be
	// mistaken for a teacher edit.
	private boolean updatingSelectedClassification;

	// The containing Dialog owns the Save/Cancel decision because it owns modal
	// warnings and persistence errors.
	private BooleanSupplier classificationNavigationGuard = () -> false;

	// Restoring the previously selected result after a cancelled navigation must
	// not recursively trigger another navigation decision.
	private boolean restoringResultSelection;
	private final Button saveClassificationButton = new Button("Save");
	private final QuestionOutputApplicabilityRepository outputApplicabilityRepository;

	// Revision-output applicability is loaded independently from Search results.
	// In All Questions scope the selected Question requires a separate
	// current-curriculum applicability lookup.
	private final ListView<QuestionOutputApplicabilityRow> outputApplicabilityList = new ListView<>();
	private final Label outputApplicabilityStatusLabel = new Label();
	private Task<List<QuestionOutputApplicabilityRow>> activeOutputApplicabilityTask;
	private long outputApplicabilityGeneration;
	private final Button includeOutputApplicabilityButton = new Button("Include");
	private final Button excludeOutputApplicabilityButton = new Button("Exclude");

	// Output applicability changes save immediately. Keep the active write separate
	// from the background task that reads applicability for a selected Question.
	private Task<Void> activeOutputApplicabilityUpdateTask;

	// The Dialog owns persistence and supplies the action that commits the pending
	// Descriptor refinement.
	private Runnable classificationSaveHandler = () -> {
		throw new IllegalStateException("Classification save handler is not configured");
	};

	// Search inherits the workspace-level Working Subject. It does not own a
	// separate application-level Subject selection.
	private final Subject workingSubject;

	/**
	 * Creates the question-search pane.
	 *
	 * @param workingSubject                authoritative workspace Working Subject
	 * @param curriculumRepository          current curriculum hierarchy lookup
	 * @param retrievalService              curriculum-aware Question retrieval
	 * @param allQuestionsSupplier          complete stored Question retrieval
	 * @param previewService                stored Question image preview service
	 * @param outputApplicabilityRepository persisted per-Question revision-output
	 *                                      exclusions
	 * @throws NullPointerException if any dependency is {@code null}
	 */
	public QuestionSearchPane(Subject workingSubject, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService,
			QuestionOutputApplicabilityRepository outputApplicabilityRepository) {
		if (workingSubject == null) {
			throw new NullPointerException("workingSubject");
		}
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
		if (outputApplicabilityRepository == null) {
			throw new NullPointerException("outputApplicabilityRepository");
		}
		this.workingSubject = workingSubject;
		this.curriculumRepository = curriculumRepository;
		this.retrievalService = retrievalService;
		this.allQuestionsSupplier = allQuestionsSupplier;
		this.previewService = previewService;
		this.outputApplicabilityRepository = outputApplicabilityRepository;
		setPadding(new Insets(PANE_PADDING));
		configureControls();
		configureHandlers();

		// Search is composed as two task columns. Subject loading establishes the
		// authoritative Working Subject before current-syllabus navigation begins.
		setCenter(createWorkspacePane());

		// Search derives its initial curriculum hierarchy directly from the
		// authoritative workspace Working Subject.
		startWorkingSubjectNavigation();
	}

	ReadOnlyBooleanProperty classificationDirtyProperty() {

		// The containing Dialog uses this read-only state to alter action-button
		// availability without being able to mutate the edit state directly.
		return classificationDirty;
	}

	void classificationDiscarded() {
		QuestionSearchResult selected = resultsList.getSelectionModel().getSelectedItem();

		// Discard means return the classification controls to the authoritative
		// persisted state, not merely clear the dirty flag.
		showSelectedClassification(selected);
	}

	void classificationSaved(long questionId) {
		Question selected = getSelectedQuestion();
		if (selected == null || selected.getId() != questionId) {
			throw new IllegalStateException("Saved classification does not belong to the selected Question");
		}

		// Persistence has succeeded. Clear the dirty state without starting a search;
		// the caller decides whether to refresh the current Question or continue to
		// another intended navigation target.
		classificationDirty.set(false);
		selectedDescriptorBox.setDisable(true);
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
		cancelActiveOutputApplicabilityLoad();
		resultsList.getItems().clear();
		detailsArea.clear();
		clearPreview();
		clearOutputApplicability();
		statusLabel.setText("");
	}

	CurriculumNode getPendingClassification() {
		if (!classificationDirty.get()) {
			return null;
		}
		Question question = getSelectedQuestion();
		CurriculumNode descriptor = selectedDescriptorBox.getValue();
		if (question == null || descriptor == null) {
			throw new IllegalStateException("Dirty classification edit has no selected Question or Descriptor");
		}
		CurriculumNode original = question.getClassification();

		// Search is permitted to refine only Subtopic -> child Descriptor. Broader
		// reclassification remains the responsibility of Edit Question.
		if (original.getLevel() != CurriculumLevel.SUBTOPIC || descriptor.getLevel() != CurriculumLevel.DESCRIPTOR
				|| !original.equals(descriptor.getParent())) {
			throw new IllegalStateException("Pending Descriptor is not a child of the Question's stored Subtopic");
		}
		return descriptor;
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

	boolean isClassificationDirty() {
		return classificationDirty.get();
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

	void setClassificationNavigationGuard(BooleanSupplier classificationNavigationGuard) {
		if (classificationNavigationGuard == null) {
			throw new NullPointerException("classificationNavigationGuard");
		}

		// Search delegates the decision but retains responsibility for restoring or
		// continuing the attempted result selection.
		this.classificationNavigationGuard = classificationNavigationGuard;
	}

	void setClassificationSaveHandler(Runnable classificationSaveHandler) {
		if (classificationSaveHandler == null) {
			throw new NullPointerException("classificationSaveHandler");
		}

		// Retain only the persistence action; classification state remains owned by
		// this Pane.
		this.classificationSaveHandler = classificationSaveHandler;
	}

	private void activateAllQuestionsScope() {

		// Lower hierarchy controls represent current-syllabus applicability and are
		// unavailable while all stored Questions for the Working Subject are shown.
		setCurriculumControlsDisabled(true);
		startAllQuestionsSearch();
	}

	private void activateCurrentSyllabusScope() {

		// Restore only hierarchy controls whose parent state is already available,
		// then repeat the most specific valid current-syllabus Search.
		restoreCurriculumControlState();
		startMostSpecificCurrentSyllabusSearch();
	}

	private void cancelActiveHierarchyLoad() {
		hierarchyGeneration++;
		if (activeHierarchyTask != null) {
			activeHierarchyTask.cancel();
			activeHierarchyTask = null;
		}
	}

	private void cancelActiveOutputApplicabilityLoad() {
		outputApplicabilityGeneration++;
		if (activeOutputApplicabilityTask != null) {
			activeOutputApplicabilityTask.cancel(false);
			activeOutputApplicabilityTask = null;
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

	private void clearBelowClassification() {
		updatingControls = true;
		try {

			// Changing Subtopic or Descriptor classification invalidates only the
			// optional Descriptor refinement control beneath it.
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

	private void clearBelowUnit() {
		updatingControls = true;
		try {

			// Changing Unit invalidates every lower curriculum selector.
			resetTopicBox();
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
	}

	private void clearOutputApplicability() {
		outputApplicabilityList.getItems().clear();
		outputApplicabilityStatusLabel.setText("");
	}

	private void clearPreview() {
		previewImageView.setImage(null);
		previewStatusLabel.setText("");
	}

	private void clearResults() {
		cancelActiveOutputApplicabilityLoad();
		resultsList.getItems().clear();
		detailsArea.clear();
		clearOutputApplicability();
	}

	private void clearSelectedClassification() {
		updatingSelectedClassification = true;
		try {

			// Clear the displayed stored hierarchy as one controlled update so ComboBox
			// action handlers cannot interpret programmatic changes as teacher edits.
			selectedSyllabusValue.setText("");
			setSingleValue(selectedUnitBox, null);
			setSingleValue(selectedTopicBox, null);
			setSingleValue(selectedSubtopicBox, null);
			setSingleValue(selectedDescriptorBox, null);

			// Descriptor refinement becomes available again only after another selected
			// Question has supplied a valid stored Subtopic and Descriptor choices.
			selectedDescriptorBox.setDisable(true);
			classificationDirty.set(false);
		} finally {
			updatingSelectedClassification = false;
		}
		selectedClassificationPane.setVisible(false);
		selectedClassificationPane.setManaged(false);
	}

	private void clearWorkingSubjectNavigation() {
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		updatingControls = true;
		try {

			// Reloading the Working Subject invalidates every current-syllabus selector
			// beneath it while leaving the workspace Subject itself unchanged.
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

	private void completeOutputApplicabilityLoad(Task<List<QuestionOutputApplicabilityRow>> task, long generation,
			long questionId) {

		// A completed background lookup may belong to a Question that is no longer
		// selected. Generation and task identity prevent stale applicability from
		// replacing the current Question's display.
		if (generation != outputApplicabilityGeneration || task != activeOutputApplicabilityTask) {
			return;
		}
		activeOutputApplicabilityTask = null;
		Question selectedQuestion = getSelectedQuestion();
		if (selectedQuestion == null || selectedQuestion.getId() != questionId) {
			return;
		}
		outputApplicabilityList.getItems().setAll(task.getValue());
		updateOutputApplicabilityStatus();
		updateOutputApplicabilityActionState();
	}

	private void completeOutputApplicabilityUpdate(Task<Void> task, long questionId, CurriculumNode currentNode,
			boolean excluded) {
		if (task != activeOutputApplicabilityUpdateTask) {
			return;
		}
		activeOutputApplicabilityUpdateTask = null;
		if (disposed) {
			return;
		}

		// A successful write may belong to a Question that is no longer selected.
		// Its completion must not alter the newer Question's applicability display.
		if (!isSelectedQuestion(questionId)) {
			updateOutputApplicabilityActionState();
			return;
		}
		if (replaceOutputApplicabilityRow(currentNode, excluded)) {
			return;
		}

		// If an intervening reload removed the edited row, rebuild applicability from
		// persistence rather than manufacturing replacement UI state.
		reloadSelectedOutputApplicability();
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
		configureSearchScopeControl();
		configureSearchHierarchyControls();
		configureSelectedClassificationControls();
		configureResultsControls();
		configureOutputApplicabilityControls();
		configurePreviewControls();
	}

	private void configureHandlers() {
		searchScopeBox.setOnAction(_ -> handleSearchScopeSelection());
		unitBox.setOnAction(_ -> handleUnitSelection());
		topicBox.setOnAction(_ -> handleTopicSelection());
		classificationBox.setOnAction(_ -> handleClassificationSelection());
		descriptorBox.setOnAction(_ -> handleDescriptorSelection());
		unitBox.setOnMousePressed(_ -> handleUnitBoxMousePress());
		topicBox.setOnMousePressed(_ -> handleTopicBoxMousePress());
		classificationBox.setOnMousePressed(_ -> handleClassificationBoxMousePress());
		descriptorBox.setOnMousePressed(_ -> handleDescriptorBoxMousePress());

		// Only a previously blank Descriptor may be refined directly from Search.
		selectedDescriptorBox.setOnAction(_ -> handleSelectedDescriptorSelection());

		// Persistence remains outside the Pane; this button delegates the action to
		// the containing Dialog.
		saveClassificationButton.setOnAction(_ -> classificationSaveHandler.run());

		// Dirty classification state controls which Search navigation remains
		// available until the pending Descriptor is saved.
		classificationDirty.addListener((_, _, _) -> updateClassificationEditLock());
		resultsList.getSelectionModel().selectedItemProperty()
				.addListener((_, oldResult, newResult) -> handleResultSelection(oldResult, newResult));
		outputApplicabilityList.getSelectionModel().selectedItemProperty()
				.addListener((_, _, _) -> updateOutputApplicabilityActionState());
		includeOutputApplicabilityButton.setOnAction(_ -> handleOutputApplicabilityChange(false));
		excludeOutputApplicabilityButton.setOnAction(_ -> handleOutputApplicabilityChange(true));
	}

	private void configureOutputApplicabilityControls() {
		outputApplicabilityList.setId("question-search-output-applicability");
		outputApplicabilityStatusLabel.setId("question-search-output-applicability-status");

		// Each row represents one current-curriculum placement derived for the
		// selected Question.
		outputApplicabilityList.setPrefHeight(110);
		outputApplicabilityList.setCellFactory(_ -> new DisplayListCell<>(this::outputApplicabilityText));
		outputApplicabilityList.setTooltip(new Tooltip(
				"Current-curriculum placements used for Revision Output; changes here do not alter Question classification."));
		includeOutputApplicabilityButton.setId("question-search-output-include");
		excludeOutputApplicabilityButton.setId("question-search-output-exclude");
		includeOutputApplicabilityButton.setTooltip(new Tooltip(
				"Include the selected placement in Revision Output; this does not alter Question classification."));
		excludeOutputApplicabilityButton.setTooltip(new Tooltip(
				"Exclude the selected placement from Revision Output; this does not alter Question classification."));

		// Action labels must remain readable when the right-hand column is narrow.
		includeOutputApplicabilityButton.setMinWidth(Region.USE_PREF_SIZE);
		excludeOutputApplicabilityButton.setMinWidth(Region.USE_PREF_SIZE);

		// No applicability action exists until the teacher selects one placement.
		includeOutputApplicabilityButton.setDisable(true);
		excludeOutputApplicabilityButton.setDisable(true);
	}

	private void configurePreviewControls() {
		previewImageView.setId("question-search-preview");
		previewStatusLabel.setId("question-search-preview-status");

		// Preview width is controlled by the containing preview pane. Preserve the
		// source aspect ratio while allowing long Questions to scroll vertically.
		previewImageView.setPreserveRatio(true);
		previewImageView.setSmooth(true);
	}

	private <T> void configurePromptDisplay(ComboBox<T> comboBox) {

		// Preserve the explicit prompt in the button area whenever the hierarchy has
		// no selected value.
		comboBox.setButtonCell(new DisplayListCell<>(item -> item.toString(), comboBox::getPromptText));
	}

	private void configureResultsControls() {
		resultsList.setId("question-search-results");

		// Search result labels describe persisted source identity. The current Search
		// scope affects why a Question matched, not the identity displayed here.
		resultsList.setCellFactory(_ -> new DisplayListCell<>(this::questionSearchResultText));
		detailsArea.setId("question-search-details");
		detailsArea.setEditable(false);
		detailsArea.setWrapText(true);
		detailsArea.setPrefRowCount(DETAIL_ROWS);
	}

	private void configureSearchHierarchyControls() {
		unitBox.setId("question-search-unit");
		topicBox.setId("question-search-topic");
		classificationBox.setId("question-search-classification");
		descriptorBox.setId("question-search-descriptor");
		statusLabel.setId("question-search-status");
		unitBox.setPromptText("Select unit");
		topicBox.setPromptText("Select topic");
		classificationBox.setPromptText("Select subtopic or descriptor");
		descriptorBox.setPromptText("Select descriptor");
		String hierarchyTooltip = "Narrows results within the current Syllabus; it does not change stored Question classifications.";
		unitBox.setTooltip(new Tooltip(hierarchyTooltip));
		topicBox.setTooltip(new Tooltip(hierarchyTooltip));
		classificationBox.setTooltip(new Tooltip(hierarchyTooltip));
		descriptorBox.setTooltip(new Tooltip(hierarchyTooltip));
		configurePromptDisplay(unitBox);
		configurePromptDisplay(topicBox);
		configurePromptDisplay(classificationBox);
		configurePromptDisplay(descriptorBox);
		unitBox.setMaxWidth(Double.MAX_VALUE);
		topicBox.setMaxWidth(Double.MAX_VALUE);
		classificationBox.setMaxWidth(Double.MAX_VALUE);
		descriptorBox.setMaxWidth(Double.MAX_VALUE);
		syllabusValue.setText("No current syllabus");

		// Curriculum navigation begins from the workspace Working Subject. Deeper
		// controls become available as that Subject's current hierarchy is loaded.
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);
		descriptorBox.setDisable(true);
	}

	private void configureSearchScopeControl() {
		searchScopeBox.setId("question-search-scope");
		searchScopeBox.getItems().setAll(QuestionSearchScope.values());
		searchScopeBox.setValue(QuestionSearchScope.CURRENT_SYLLABUS);
		searchScopeBox.setMaxWidth(Double.MAX_VALUE);
		searchScopeBox.setTooltip(new Tooltip(
				"Current Syllabus searches by current curriculum; All Questions searches all stored Questions for the Working Subject."));

		// Search scope owns its user-facing wording rather than exposing enum names.
		searchScopeBox.setButtonCell(new DisplayListCell<>(QuestionSearchScope::displayText));
		searchScopeBox.setCellFactory(_ -> new DisplayListCell<>(QuestionSearchScope::displayText));
	}

	private void configureSelectedClassificationControls() {
		selectedSyllabusValue.setId("question-search-selected-syllabus");
		selectedUnitBox.setId("question-search-selected-unit");
		selectedTopicBox.setId("question-search-selected-topic");
		selectedSubtopicBox.setId("question-search-selected-subtopic");
		selectedDescriptorBox.setId("question-search-selected-descriptor");
		selectedClassificationPane.setId("question-search-selected-classification");
		selectedUnitBox.setPromptText("No unit");
		selectedTopicBox.setPromptText("No topic");
		selectedSubtopicBox.setPromptText("No subtopic");
		selectedDescriptorBox.setPromptText("No descriptor");
		selectedDescriptorBox.setTooltip(new Tooltip(
				"Refine a Subtopic-classified Question to a child Descriptor; the change is not stored until Save."));
		configurePromptDisplay(selectedUnitBox);
		configurePromptDisplay(selectedTopicBox);
		configurePromptDisplay(selectedSubtopicBox);
		configurePromptDisplay(selectedDescriptorBox);
		selectedUnitBox.setMaxWidth(Double.MAX_VALUE);
		selectedTopicBox.setMaxWidth(Double.MAX_VALUE);
		selectedSubtopicBox.setMaxWidth(Double.MAX_VALUE);
		selectedDescriptorBox.setMaxWidth(Double.MAX_VALUE);

		// The stored hierarchy is informational. Only a previously blank Descriptor
		// may become editable when a Subtopic-classified Question is selected.
		selectedUnitBox.setDisable(true);
		selectedTopicBox.setDisable(true);
		selectedSubtopicBox.setDisable(true);
		selectedDescriptorBox.setDisable(true);
		saveClassificationButton.setId("question-search-save-classification");
		saveClassificationButton
				.setTooltip(new Tooltip("Persist the selected Descriptor as this Question's classification."));

		// Keep the complete action label visible beside the Descriptor selector.
		saveClassificationButton.setMinWidth(Region.USE_PREF_SIZE);

		// Save becomes meaningful only when the selected Descriptor differs from the
		// persisted Question classification.
		saveClassificationButton.disableProperty().bind(classificationDirty.not());
		selectedClassificationPane.setVisible(false);
		selectedClassificationPane.setManaged(false);
	}

	private VBox createLeftColumn() {
		VBox searchPane = createSearchFilterPane();
		VBox resultsPane = createMatchingQuestionsPane();
		VBox detailsPane = createQuestionDetailsPane();

		// Matching Questions and Question Details share the vertical space left after
		// the Search controls have taken their natural height.
		SplitPane contentPane = new SplitPane(resultsPane, detailsPane);
		contentPane.setOrientation(Orientation.VERTICAL);
		contentPane.setDividerPositions(LEFT_CONTENT_DIVIDER_POSITION);
		VBox leftColumn = new VBox(SECTION_SPACING, searchPane, contentPane);
		leftColumn.setId("question-search-left-column");
		VBox.setVgrow(contentPane, Priority.ALWAYS);
		return leftColumn;
	}

	private VBox createMatchingQuestionsPane() {
		Label resultsLabel = new Label("Matching questions");
		resultsLabel.setStyle("-fx-font-weight: bold;");
		VBox pane = new VBox(SECTION_SPACING, resultsLabel, resultsList);
		pane.setId("question-search-results-section");

		// Matching Questions own the available height in the upper left result
		// section, preserving the existing scrolling ListView behaviour.
		VBox.setVgrow(resultsList, Priority.ALWAYS);
		return pane;
	}

	private VBox createOutputApplicabilityPane() {
		Label outputApplicabilityLabel = new Label("Revision output applicability");
		outputApplicabilityLabel.setStyle("-fx-font-weight: bold;");
		HBox outputApplicabilityActions = new HBox(SECTION_SPACING, includeOutputApplicabilityButton,
				excludeOutputApplicabilityButton);

		// Include and Exclude alter only revision-output placement. They remain
		// separate from the historical classification controls above.
		VBox pane = new VBox(SECTION_SPACING, outputApplicabilityLabel, outputApplicabilityStatusLabel,
				outputApplicabilityList, outputApplicabilityActions);
		pane.setId("question-search-output-section");
		pane.setPadding(new Insets(SECTION_TOP_PADDING, 0, 0, 0));

		// The ListView retains its configured preferred height and scrolls internally
		// when many current placements exist. It must not compete with Preview for all
		// available vertical space.
		return pane;
	}

	private VBox createQuestionDetailsPane() {
		Label detailsLabel = new Label("Question details");
		detailsLabel.setStyle("-fx-font-weight: bold;");
		VBox pane = new VBox(SECTION_SPACING, detailsLabel, detailsArea);
		pane.setId("question-search-details-section");
		pane.setPadding(new Insets(SECTION_TOP_PADDING, 0, 0, 0));

		// Question details consume the space made available by the lower half of
		// the left column.
		VBox.setVgrow(detailsArea, Priority.ALWAYS);
		return pane;
	}

	private VBox createQuestionPreviewPane() {
		Label label = new Label("Question preview");
		label.setStyle("-fx-font-weight: bold;");
		ScrollPane previewPane = new ScrollPane(previewImageView);
		previewPane.setId("question-search-preview-scroll");
		previewPane.setFitToWidth(true);

		// Question images belong entirely within the right-hand preview column.
		// Bind the rendered width to the actual ScrollPane viewport rather than
		// retaining the old full-width Search-dialog dimension.
		previewImageView.fitWidthProperty().bind(Bindings.createDoubleBinding(
				() -> Math.max(0.0, previewPane.getViewportBounds().getWidth()), previewPane.viewportBoundsProperty()));

		// Width is always fitted to the viewport. Long Questions may still scroll
		// vertically so their text is not shrunk merely to fit the available height.
		previewPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox pane = new VBox(SECTION_SPACING, label, previewStatusLabel, previewPane);
		pane.setId("question-search-preview-section");
		pane.setPadding(new Insets(SECTION_TOP_PADDING, 0, 0, 0));
		VBox.setVgrow(previewPane, Priority.ALWAYS);
		return pane;
	}

	private VBox createRightColumn() {
		GridPane classificationPane = createSelectedClassificationPane();
		VBox outputApplicabilityPane = createOutputApplicabilityPane();
		VBox previewPane = createQuestionPreviewPane();

		// Classification and revision-output applicability are compact controls.
		// Preview owns the remaining height because Question content is the section
		// that benefits from additional vertical space.
		VBox rightColumn = new VBox(SECTION_SPACING, classificationPane, outputApplicabilityPane, previewPane);
		rightColumn.setId("question-search-right-column");
		VBox.setVgrow(previewPane, Priority.ALWAYS);
		return rightColumn;
	}

	private VBox createSearchFilterPane() {
		Label heading = new Label("Question Search");
		heading.setStyle("-fx-font-weight: bold;");
		VBox pane = new VBox(SECTION_SPACING, heading, createSelectionPane());
		pane.setId("question-search-filter-section");
		return pane;
	}

	private GridPane createSelectedClassificationPane() {
		selectedClassificationPane.setHgap(SELECTOR_COLUMN_GAP);
		selectedClassificationPane.setVgap(SELECTOR_ROW_GAP);
		selectedClassificationPane.setPadding(new Insets(SECTION_TOP_PADDING, 0, PANE_PADDING, 0));
		Label heading = new Label("Selected question classification");
		heading.setStyle("-fx-font-weight: bold;");
		selectedClassificationPane.add(heading, 0, 0, 2, 1);
		selectedClassificationPane.add(createSelectorLabel("Syllabus"), 0, 1);
		selectedClassificationPane.add(selectedSyllabusValue, 1, 1);
		selectedClassificationPane.add(createSelectorLabel("Unit"), 0, 2);
		selectedClassificationPane.add(selectedUnitBox, 1, 2);
		selectedClassificationPane.add(createSelectorLabel("Topic"), 0, 3);
		selectedClassificationPane.add(selectedTopicBox, 1, 3);
		selectedClassificationPane.add(createSelectorLabel("Subtopic"), 0, 4);
		selectedClassificationPane.add(selectedSubtopicBox, 1, 4);
		selectedClassificationPane.add(createSelectorLabel("Descriptor"), 0, 5);
		selectedClassificationPane.add(selectedDescriptorBox, 1, 5);

		// Descriptor refinement is the only classification change saved directly
		// from Search, so its Save action remains beside that control.
		selectedClassificationPane.add(saveClassificationButton, 2, 5);
		GridPane.setHgrow(selectedUnitBox, Priority.ALWAYS);
		GridPane.setHgrow(selectedTopicBox, Priority.ALWAYS);
		GridPane.setHgrow(selectedSubtopicBox, Priority.ALWAYS);
		GridPane.setHgrow(selectedDescriptorBox, Priority.ALWAYS);
		return selectedClassificationPane;
	}

	private GridPane createSelectionPane() {
		GridPane pane = new GridPane();
		pane.setHgap(SELECTOR_COLUMN_GAP);
		pane.setVgap(SELECTOR_ROW_GAP);
		pane.setPadding(new Insets(0, 0, PANE_PADDING, 0));
		pane.add(createSelectorLabel("Search scope"), 0, 0);
		pane.add(searchScopeBox, 1, 0);
		pane.add(createSelectorLabel("Current syllabus"), 0, 1);
		pane.add(syllabusValue, 1, 1);
		pane.add(createSelectorLabel("Unit"), 0, 2);
		pane.add(unitBox, 1, 2);
		pane.add(createSelectorLabel("Topic"), 0, 3);
		pane.add(topicBox, 1, 3);
		pane.add(createSelectorLabel("Subtopic / Descriptor"), 0, 4);
		pane.add(classificationBox, 1, 4);
		pane.add(createSelectorLabel("Descriptor"), 0, 5);
		pane.add(descriptorBox, 1, 5);
		pane.add(statusLabel, 1, 6);

		// The Subject is supplied by the workspace, so every visible selector now
		// represents only Search-local scope beneath that Subject.
		GridPane.setHgrow(searchScopeBox, Priority.ALWAYS);
		GridPane.setHgrow(unitBox, Priority.ALWAYS);
		GridPane.setHgrow(topicBox, Priority.ALWAYS);
		GridPane.setHgrow(classificationBox, Priority.ALWAYS);
		GridPane.setHgrow(descriptorBox, Priority.ALWAYS);
		return pane;
	}

	private Label createSelectorLabel(String text) {
		Label label = new Label(text);

		// Selector labels describe the controls beside them and must not collapse to
		// ellipses when the Dialog is resized.
		label.setMinWidth(Region.USE_PREF_SIZE);
		return label;
	}

	private SplitPane createWorkspacePane() {
		VBox leftColumn = createLeftColumn();
		VBox rightColumn = createRightColumn();

		// The outer SplitPane gives the teacher control over the amount of horizontal
		// space assigned to Search/results and selected-Question information.
		SplitPane workspace = new SplitPane(leftColumn, rightColumn);
		workspace.setId("question-search-workspace");
		workspace.setOrientation(Orientation.HORIZONTAL);
		workspace.setDividerPositions(COLUMN_DIVIDER_POSITION);
		return workspace;
	}

	private String currentApplicabilityText(QuestionSearchResult result) {
		if (result.scope() == QuestionSearchScope.ALL_QUESTIONS) {

			// All Questions deliberately does not evaluate curriculum mappings, so an
			// empty applicability list in this scope is not evidence of inapplicability.
			return "Not evaluated in All Questions scope.";
		}
		StringJoiner applicability = new StringJoiner(System.lineSeparator());
		for (CurriculumNode node : result.currentApplicability()) {
			applicability.add(nodeDescription(node));
		}
		return applicability.toString();
	}

	private String displayQuestionText(Question question) {
		String questionText = question.getQuestionText();

		// Stored image-only Questions remain meaningful Search results even when no
		// transcribed text has been entered.
		if (questionText.isBlank()) {
			return "No transcribed question text.";
		}
		return questionText;
	}

	private void displaySelectedResult(QuestionSearchResult result) {

		// Question details, stored classification and revision-output applicability
		// all represent the same selected Search result and must change together.
		showResultDetails(result);
		showSelectedClassification(result);
		startOutputApplicabilityLoad(result);
	}

	private <T> void failHierarchyLoad(Task<T> task, long generation) {
		if (generation != hierarchyGeneration || task != activeHierarchyTask) {
			return;
		}
		activeHierarchyTask = null;
		invalidateCurrentSearch();

		// Preserve the existing user-visible failure wording while centralising the
		// optional exception-detail formatting.
		statusLabel.setText(failureStatusText("Curriculum navigation failed", task.getException()));
	}

	private void failOutputApplicabilityLoad(Task<List<QuestionOutputApplicabilityRow>> task, long generation,
			long questionId) {
		if (generation != outputApplicabilityGeneration || task != activeOutputApplicabilityTask) {
			return;
		}
		activeOutputApplicabilityTask = null;
		if (!isSelectedQuestion(questionId)) {
			return;
		}
		outputApplicabilityStatusLabel
				.setText(failureStatusText("Revision output applicability unavailable", task.getException()));
		outputApplicabilityList.getItems().clear();

		// A failed read leaves no trustworthy placement on which Include or Exclude
		// could operate.
		updateOutputApplicabilityActionState();
	}

	private void failOutputApplicabilityUpdate(Task<Void> task, long questionId) {
		if (task != activeOutputApplicabilityUpdateTask) {
			return;
		}
		activeOutputApplicabilityUpdateTask = null;
		if (disposed) {
			return;
		}
		if (isSelectedQuestion(questionId)) {
			outputApplicabilityStatusLabel.setText(
					failureStatusText("Revision output applicability could not be saved", task.getException()));
		}

		// The row still represents the last successfully persisted state, so restore
		// whichever Include or Exclude action remains valid for that state.
		updateOutputApplicabilityActionState();
	}

	private void failPreview(Task<Optional<BufferedImage>> task, long generation) {
		if (generation != previewGeneration || task != activePreviewTask) {
			return;
		}
		activePreviewTask = null;
		previewStatusLabel.setText(failureStatusText("Question preview unavailable", task.getException()));
	}

	private void failSearch(Task<List<QuestionSearchResult>> task, long generation) {
		if (generation != searchGeneration || task != activeSearchTask) {
			return;
		}
		activeSearchTask = null;
		statusLabel.setText(failureStatusText("Question search failed", task.getException()));
	}

	private String failureStatusText(String summary, Throwable failure) {
		if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {

			// A useful fixed summary must still be shown when the exception provides no
			// user-readable detail.
			return summary + ".";
		}
		return summary + ": " + failure.getMessage();
	}

	private List<CurriculumNode> findCompleteCurrentApplicability(Question question) {
		List<QuestionRetrievalResult> results = retrievalService
				.findQuestionsApplicableTo(question.getExam().getSubject());
		for (QuestionRetrievalResult result : results) {
			if (result.getQuestion().getId() != question.getId()) {
				continue;
			}

			// Output applicability is Subject-wide. The Search result may represent a
			// much narrower Unit, Topic, Subtopic or Descriptor scope and therefore
			// cannot be used as the complete set of output placements.
			return result.getCurrentApplicability();
		}

		// A Question may legitimately have no current mapping or the Subject may have
		// no current syllabus.
		return List.of();
	}

	private SyllabusVersion findCurrentSyllabus(Subject subject) {
		List<SyllabusVersion> versions = curriculumRepository.findVersionsForSubject(subject);
		SyllabusVersion currentVersion = null;
		for (SyllabusVersion version : versions) {
			if (!version.isCurrent()) {
				continue;
			}

			// Search navigation requires one unambiguous current syllabus. Multiple
			// current versions indicate invalid curriculum state rather than a UI choice.
			if (currentVersion != null) {
				throw new IllegalStateException("Subject has multiple current syllabus versions");
			}
			currentVersion = version;
		}
		return currentVersion;
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
		clearBelowClassification();
		if (classification == null) {
			return;
		}

		// A Descriptor is already the most specific Search classification, so no
		// additional hierarchy load is required.
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC) {
			startAutomaticSearch(classification);
			return;
		}

		// A Subtopic may expose child Descriptors while still acting as the current
		// Search scope itself.
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

	private void handleOutputApplicabilityChange(boolean excluded) {
		if (disposed || classificationDirty.get() || activeOutputApplicabilityUpdateTask != null) {
			return;
		}
		Question question = getSelectedQuestion();
		QuestionOutputApplicabilityRow row = outputApplicabilityList.getSelectionModel().getSelectedItem();
		if (question == null || row == null || row.excluded() == excluded) {
			return;
		}

		// Capture the Question and node before leaving the FX thread. The background
		// task must not inspect mutable JavaFX selection state.
		startOutputApplicabilityUpdate(question, row.currentNode(), excluded);
	}

	private void handleResultSelection(QuestionSearchResult oldResult, QuestionSearchResult newResult) {
		if (restoringResultSelection) {
			return;
		}
		if (classificationDirty.get() && oldResult != null && newResult != null
				&& oldResult.question().getId() != newResult.question().getId()) {

			// ListView is still completing its selection transaction here. Defer the
			// dirty-edit decision until JavaFX has finished changing the selection.
			Platform.runLater(() -> resolveDirtyResultNavigation(oldResult, newResult));
			return;
		}
		displaySelectedResult(newResult);
	}

	private void handleSearchScopeSelection() {
		if (updatingControls || disposed) {
			return;
		}
		prepareSearchScopeTransition();
		if (searchScopeBox.getValue() == QuestionSearchScope.ALL_QUESTIONS) {
			activateAllQuestionsScope();
			return;
		}
		activateCurrentSyllabusScope();
	}

	private void handleSelectedDescriptorSelection() {
		if (updatingSelectedClassification || disposed) {
			return;
		}
		Question question = getSelectedQuestion();
		CurriculumNode descriptor = selectedDescriptorBox.getValue();
		if (question == null || descriptor == null) {
			classificationDirty.set(false);
			return;
		}
		CurriculumNode original = question.getClassification();

		// A valid inline edit can only refine the selected Question's stored
		// Subtopic to one of its immediate Descriptor children.
		if (original.getLevel() != CurriculumLevel.SUBTOPIC || descriptor.getLevel() != CurriculumLevel.DESCRIPTOR
				|| !original.equals(descriptor.getParent())) {
			throw new IllegalStateException("Selected Descriptor is not valid for this Question");
		}
		classificationDirty.set(true);
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
		CurriculumNode topic = topicBox.getValue();
		clearBelowTopic();
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
		clearBelowUnit();
		if (unit == null) {
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(unit), topics -> showTopics(unit, topics));
	}

	private void invalidateCurrentSearch() {
		cancelActiveSearch();
		cancelActivePreview();

		// Clearing Search results also invalidates any selected Question's separate
		// revision-output applicability request.
		clearResults();
		clearPreview();
		statusLabel.setText("");
	}

	private boolean isRequestedResultStillSelected(QuestionSearchResult requestedResult) {
		QuestionSearchResult currentResult = resultsList.getSelectionModel().getSelectedItem();

		// A later user action may supersede the deferred navigation request before it
		// gets its turn on the JavaFX application thread.
		return currentResult != null && currentResult.question().getId() == requestedResult.question().getId();
	}

	private boolean isSelectedQuestion(long questionId) {
		Question selectedQuestion = getSelectedQuestion();

		// Persistent Question identity determines whether an asynchronous completion
		// still belongs to the currently displayed result.
		return selectedQuestion != null && selectedQuestion.getId() == questionId;
	}

	private List<QuestionOutputApplicabilityRow> loadOutputApplicability(QuestionSearchResult result) {
		Question question = result.question();

		// Search-result applicability explains why this Question matched the current
		// Search scope. Revision-output applicability is different: it must show every
		// current placement for the Question across its Subject, regardless of how
		// narrowly Search itself is filtered.
		List<CurriculumNode> currentApplicability = findCompleteCurrentApplicability(question);
		Set<Long> excludedCurrentNodeIds = outputApplicabilityRepository.findExcludedCurrentNodeIds(question);
		if (excludedCurrentNodeIds == null) {
			throw new IllegalStateException("Question output applicability repository returned null");
		}

		// Only currently-derived placements are displayed. A stored exclusion for a
		// node that is no longer applicable has no effect on current revision output.
		return currentApplicability.stream().map(currentNode -> new QuestionOutputApplicabilityRow(currentNode,
				excludedCurrentNodeIds.contains(currentNode.getId()))).toList();
	}

	private SubjectNavigation loadSubjectNavigation(Subject subject) {
		SyllabusVersion currentVersion = findCurrentSyllabus(subject);
		if (currentVersion == null) {

			// A Subject without a current syllabus still produces a valid navigation
			// result so the UI can report that state without treating it as a failure.
			return new SubjectNavigation(null, List.of());
		}
		List<CurriculumNode> units = curriculumRepository.findRootNodes(currentVersion);
		return new SubjectNavigation(currentVersion, units);
	}

	private CurriculumNode mostSpecificSelectedCurriculumNode() {
		CurriculumNode descriptor = descriptorBox.getValue();
		if (descriptor != null) {
			return descriptor;
		}
		CurriculumNode classification = classificationBox.getValue();
		if (classification != null) {
			return classification;
		}
		CurriculumNode topic = topicBox.getValue();
		if (topic != null) {
			return topic;
		}
		return unitBox.getValue();
	}

	private String nodeDescription(CurriculumNode node) {
		return node.getSyllabusVersion().getName() + " " + node.getCode() + " " + node.getName();
	}

	private String outputApplicabilityText(QuestionOutputApplicabilityRow row) {
		String state = row.excluded() ? "Excluded" : "Included";
		return state + " — " + nodeDescription(row.currentNode());
	}

	private Void persistOutputApplicabilityChange(Question question, CurriculumNode currentNode, boolean excluded) {

		// Persist only the selected Question/current-node exception. Curriculum
		// mappings and the Question's stored classification remain unchanged.
		outputApplicabilityRepository.setExcluded(question, currentNode, excluded);
		return null;
	}

	private void populateSelectedClassificationControls(QuestionClassificationPath path) {
		updatingSelectedClassification = true;
		try {
			selectedSyllabusValue.setText(path.classification().getSyllabusVersion().getName());
			setSingleValue(selectedUnitBox, path.unit());
			setSingleValue(selectedTopicBox, path.topic());
			setSingleValue(selectedSubtopicBox, path.subtopic());
			selectedDescriptorBox.getItems().setAll(path.descriptorChoices());
			selectedDescriptorBox.setValue(path.descriptor());

			// Descriptor selection becomes editable only for a stored Subtopic with
			// valid child Descriptors.
			selectedDescriptorBox.setDisable(!path.descriptorRefinementAvailable());
			classificationDirty.set(false);
		} finally {
			updatingSelectedClassification = false;
		}
	}

	private void prepareSearchScopeTransition() {

		// A scope transition invalidates hierarchy and Search work belonging to the
		// previous scope so late asynchronous results cannot restore stale UI state.
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
	}

	private String questionDetailsText(QuestionSearchResult result) {
		Question question = result.question();

		// Keep details construction separate from selection handling so Search state
		// changes and presentation formatting remain independent concerns.
		return """
				Question: %s
				Marks: %d

				Original classification:
				%s

				Current applicability:
				%s

				Question text:
				%s
				""".formatted(question.getQuestionCode(), question.getMarks(),
				nodeDescription(question.getClassification()), currentApplicabilityText(result),
				displayQuestionText(question));
	}

	private String questionSearchResultText(QuestionSearchResult result) {
		Question question = result.question();
		return question.getExam().getProvider().getName() + " " + question.getExam().getYear() + " — "
				+ question.getBooklet().getName() + " — " + question.getQuestionCode() + " — " + question.getMarks()
				+ " marks";
	}

	private void reloadSelectedOutputApplicability() {
		QuestionSearchResult selectedResult = resultsList.getSelectionModel().getSelectedItem();
		if (selectedResult != null) {

			// Re-read the authoritative persisted applicability for the still-selected
			// Question when the expected in-memory row is no longer present.
			startOutputApplicabilityLoad(selectedResult);
			return;
		}
		clearOutputApplicability();
	}

	private boolean replaceOutputApplicabilityRow(CurriculumNode currentNode, boolean excluded) {
		for (int index = 0; index < outputApplicabilityList.getItems().size(); index++) {
			QuestionOutputApplicabilityRow existing = outputApplicabilityList.getItems().get(index);
			if (existing.currentNode().getId() != currentNode.getId()) {
				continue;
			}
			QuestionOutputApplicabilityRow updated = new QuestionOutputApplicabilityRow(existing.currentNode(),
					excluded);

			// Replace only the persisted placement and retain its selection so the
			// teacher can immediately reverse the Include/Exclude decision.
			outputApplicabilityList.getItems().set(index, updated);
			outputApplicabilityList.getSelectionModel().select(index);
			updateOutputApplicabilityStatus();
			updateOutputApplicabilityActionState();
			return true;
		}
		return false;
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
		resetSearchHierarchyBox(classificationBox, "Select subtopic or descriptor");
	}

	private void resetDescriptorBox() {
		resetSearchHierarchyBox(descriptorBox, "Select descriptor");
	}

	private <T> void resetSearchHierarchyBox(ComboBox<T> comboBox, String promptText) {

		// A hierarchy reset removes both the selected value and every choice that
		// belonged to the previously selected parent node.
		comboBox.getSelectionModel().clearSelection();
		comboBox.setValue(null);
		comboBox.getItems().clear();
		comboBox.setPromptText(promptText);

		// The control remains unavailable until its parent selection loads a new set
		// of valid curriculum choices.
		comboBox.setDisable(true);
	}

	private void resetTopicBox() {
		resetSearchHierarchyBox(topicBox, "Select topic");
	}

	private void resetUnitBox() {
		resetSearchHierarchyBox(unitBox, "Select unit");
	}

	private QuestionClassificationPath resolveDescriptorClassification(CurriculumNode descriptor) {
		CurriculumNode parent = descriptor.getParent();
		CurriculumNode topic;
		CurriculumNode subtopic = null;

		// Historical curricula may place a Descriptor beneath either a Subtopic or
		// directly beneath a Topic, so both stored structures remain supported.
		if (parent.getLevel() == CurriculumLevel.SUBTOPIC) {
			subtopic = parent;
			topic = subtopic.getParent();
		} else if (parent.getLevel() == CurriculumLevel.TOPIC) {
			topic = parent;
		} else {
			throw new IllegalStateException("Descriptor has invalid parent level " + parent.getLevel());
		}
		CurriculumNode unit = topic.getParent();

		// An already stored Descriptor is informational in Search and therefore has
		// no alternative refinement choices.
		return new QuestionClassificationPath(descriptor, unit, topic, subtopic, descriptor, List.of(descriptor));
	}

	private void resolveDirtyResultNavigation(QuestionSearchResult oldResult, QuestionSearchResult requestedResult) {
		if (disposed || !isRequestedResultStillSelected(requestedResult)) {
			return;
		}
		if (!classificationDirty.get()) {

			// The edit may have been resolved before this deferred callback ran. The
			// requested Question can therefore be displayed immediately.
			displaySelectedResult(requestedResult);
			return;
		}
		restoreResultSelection(oldResult);

		// Save / Discard Changes / Cancel is owned by the Dialog rather than by the
		// ListView selection listener.
		if (!classificationNavigationGuard.getAsBoolean()) {
			return;
		}

		// Save or Discard succeeded. Re-run the active Search and reselect the
		// Question the teacher originally attempted to navigate to.
		refreshAfterEdit(requestedResult.question().getId());
	}

	private QuestionClassificationPath resolveSelectedClassificationPath(CurriculumNode classification) {
		if (classification.getLevel() == CurriculumLevel.SUBTOPIC) {
			return resolveSubtopicClassification(classification);
		}
		if (classification.getLevel() == CurriculumLevel.DESCRIPTOR) {
			return resolveDescriptorClassification(classification);
		}
		throw new IllegalStateException("Question classification must be a Subtopic or Descriptor");
	}

	private QuestionClassificationPath resolveSubtopicClassification(CurriculumNode subtopic) {
		CurriculumNode topic = subtopic.getParent();
		CurriculumNode unit = topic.getParent();

		// A Subtopic-classified Question may be refined only to one of that
		// Subtopic's own Descriptors.
		List<CurriculumNode> descriptorChoices = curriculumRepository.findChildren(subtopic).stream()
				.filter(node -> node.getLevel() == CurriculumLevel.DESCRIPTOR).toList();
		return new QuestionClassificationPath(subtopic, unit, topic, subtopic, null, descriptorChoices);
	}

	private void restoreCurriculumControlState() {

		// Re-enable only controls whose parent hierarchy has actually been loaded.
		setCurriculumControlsDisabled(false);
	}

	private void restoreResultSelection(QuestionSearchResult result) {
		restoringResultSelection = true;
		try {

			// Restore the Question that owns the dirty Descriptor without recursively
			// treating this programmatic selection as another navigation request.
			resultsList.getSelectionModel().select(result);
		} finally {
			restoringResultSelection = false;
		}
	}

	private void setCurriculumControlsDisabled(boolean disabled) {

		// Every visible hierarchy control is below the fixed workspace Working
		// Subject. All Questions disables the complete current-syllabus hierarchy.
		unitBox.setDisable(disabled || currentSyllabus == null || unitBox.getItems().isEmpty());
		topicBox.setDisable(disabled || unitBox.getValue() == null || topicBox.getItems().isEmpty());
		classificationBox.setDisable(disabled || topicBox.getValue() == null || classificationBox.getItems().isEmpty());
		descriptorBox
				.setDisable(disabled || classificationBox.getValue() == null || descriptorBox.getItems().isEmpty());
	}

	private <T> void setSingleValue(ComboBox<T> comboBox, T value) {
		comboBox.getItems().clear();
		if (value != null) {

			// Selected-classification controls contain only the authoritative node for
			// that level rather than presenting alternative navigation choices.
			comboBox.getItems().add(value);
		}
		comboBox.setValue(value);
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
		detailsArea.setText(questionDetailsText(result));
		startQuestionPreview(question);
	}

	private void showSelectedClassification(QuestionSearchResult result) {
		clearSelectedClassification();
		if (result == null) {
			return;
		}
		QuestionClassificationPath path = resolveSelectedClassificationPath(result.question().getClassification());
		populateSelectedClassificationControls(path);
		selectedClassificationPane.setVisible(true);
		selectedClassificationPane.setManaged(true);
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

		// All Questions means all persisted Questions belonging to the authoritative
		// workspace Working Subject. Cross-Subject searching is not a Search-local
		// concern.
		startAutomaticSearch(() -> {
			List<Question> questions = allQuestionsSupplier.get().stream()
					.filter(question -> workingSubject.equals(question.getExam().getSubject())).toList();
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
		Task<List<QuestionSearchResult>> task = new BackgroundTask<>(retrieval::get);
		activeSearchTask = task;
		task.setOnSucceeded(_ -> completeSearch(task, generation));
		task.setOnFailed(_ -> failSearch(task, generation));
		startBackgroundTask("question-search", task);
	}

	private void startBackgroundTask(String threadName, Task<?> task) {

		// Search background work uses virtual threads consistently while Task retains
		// responsibility for JavaFX success, failure and cancellation state.
		Thread.ofVirtual().name(threadName).start(task);
	}

	private void startCurrentSyllabusSubjectSearch() {

		// A scope transition may have cancelled the Working Subject hierarchy load.
		// Restore that hierarchy before attempting a broad current-syllabus search.
		if (currentSyllabus == null) {
			startWorkingSubjectNavigation();
			return;
		}

		// Subject-level Search means applicability anywhere in the Working Subject's
		// current syllabus.
		startAutomaticSearch(workingSubject);
	}

	private <T> void startHierarchyLoad(Supplier<T> loader, Consumer<T> onSucceeded) {
		if (disposed) {
			return;
		}
		cancelActiveHierarchyLoad();
		long generation = hierarchyGeneration;
		statusLabel.setText("Loading curriculum...");
		Task<T> task = new BackgroundTask<>(loader::get);
		activeHierarchyTask = task;
		task.setOnSucceeded(_ -> completeHierarchyLoad(task, generation, onSucceeded));
		task.setOnFailed(_ -> failHierarchyLoad(task, generation));
		startBackgroundTask("curriculum-navigation", task);
	}

	private void startMostSpecificCurrentSyllabusSearch() {
		CurriculumNode selectedNode = mostSpecificSelectedCurriculumNode();
		if (selectedNode != null) {
			startAutomaticSearch(selectedNode);
			return;
		}
		startCurrentSyllabusSubjectSearch();
	}

	private void startOutputApplicabilityLoad(QuestionSearchResult result) {
		cancelActiveOutputApplicabilityLoad();
		clearOutputApplicability();
		if (disposed || result == null) {
			return;
		}
		long generation = outputApplicabilityGeneration;
		long questionId = result.question().getId();
		outputApplicabilityStatusLabel.setText("Loading revision output applicability...");
		Task<List<QuestionOutputApplicabilityRow>> task = new BackgroundTask<>(() -> loadOutputApplicability(result));
		activeOutputApplicabilityTask = task;
		task.setOnSucceeded(_ -> completeOutputApplicabilityLoad(task, generation, questionId));
		task.setOnFailed(_ -> failOutputApplicabilityLoad(task, generation, questionId));
		startBackgroundTask("question-output-applicability", task);
	}

	private void startOutputApplicabilityUpdate(Question question, CurriculumNode currentNode, boolean excluded) {
		if (activeOutputApplicabilityUpdateTask != null) {
			throw new IllegalStateException("Revision output applicability update is already in progress");
		}
		outputApplicabilityStatusLabel.setText("Saving revision output applicability...");
		Task<Void> task = new BackgroundTask<>(() -> persistOutputApplicabilityChange(question, currentNode, excluded));
		activeOutputApplicabilityUpdateTask = task;
		updateOutputApplicabilityActionState();
		task.setOnSucceeded(_ -> completeOutputApplicabilityUpdate(task, question.getId(), currentNode, excluded));
		task.setOnFailed(_ -> failOutputApplicabilityUpdate(task, question.getId()));
		startBackgroundTask("question-output-applicability-save", task);
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
		Task<Optional<BufferedImage>> task = new BackgroundTask<>(() -> previewService.loadPreview(question));
		activePreviewTask = task;
		task.setOnSucceeded(_ -> completePreview(task, generation));
		task.setOnFailed(_ -> failPreview(task, generation));
		startBackgroundTask("question-preview", task);
	}

	private void startWorkingSubjectNavigation() {
		if (disposed) {
			return;
		}

		// Working Subject belongs to the application workspace. Search reloads only
		// the curriculum hierarchy beneath that fixed Subject.
		clearWorkingSubjectNavigation();
		startHierarchyLoad(() -> loadSubjectNavigation(workingSubject),
				navigation -> showSubjectNavigation(workingSubject, navigation));
	}

	private void updateClassificationEditLock() {
		boolean dirty = classificationDirty.get();

		// Result selection remains available so an attempt to leave the Question can
		// invoke the Save/Cancel navigation guard.
		resultsList.setDisable(false);
		if (dirty) {

			// Hold Search-local navigation while the pending Descriptor edit is resolved.
			searchScopeBox.setDisable(true);
			unitBox.setDisable(true);
			topicBox.setDisable(true);
			classificationBox.setDisable(true);
			descriptorBox.setDisable(true);
			return;
		}
		searchScopeBox.setDisable(false);
		if (searchScopeBox.getValue() == QuestionSearchScope.ALL_QUESTIONS) {

			// All Questions has no current-syllabus navigation beneath the fixed
			// workspace Working Subject.
			unitBox.setDisable(true);
			topicBox.setDisable(true);
			classificationBox.setDisable(true);
			descriptorBox.setDisable(true);
			return;
		}
		restoreCurriculumControlState();
	}

	private void updateOutputApplicabilityActionState() {
		QuestionOutputApplicabilityRow selected = outputApplicabilityList.getSelectionModel().getSelectedItem();
		boolean saving = activeOutputApplicabilityUpdateTask != null;
		boolean editingClassification = classificationDirty.get();

		// Prevent another placement from being selected while its current state is
		// being persisted. Ordinary inspection remains available otherwise.
		outputApplicabilityList.setDisable(saving);
		includeOutputApplicabilityButton
				.setDisable(saving || editingClassification || selected == null || !selected.excluded());
		excludeOutputApplicabilityButton
				.setDisable(saving || editingClassification || selected == null || selected.excluded());
	}

	private void updateOutputApplicabilityStatus() {
		List<QuestionOutputApplicabilityRow> rows = List.copyOf(outputApplicabilityList.getItems());
		if (rows.isEmpty()) {
			outputApplicabilityStatusLabel.setText("No current revision applicability.");
			return;
		}
		long excludedCount = rows.stream().filter(QuestionOutputApplicabilityRow::excluded).count();
		long includedCount = rows.size() - excludedCount;
		String placementWord = rows.size() == 1 ? "placement" : "placements";
		outputApplicabilityStatusLabel.setText("%d current %s: %d included, %d excluded.".formatted(rows.size(),
				placementWord, includedCount, excludedCount));
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
}
