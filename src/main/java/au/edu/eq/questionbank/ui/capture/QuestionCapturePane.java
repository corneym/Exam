package au.edu.eq.questionbank.ui.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.NewSharedContext;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitPart;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitRequest;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitResult;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SourceQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog.SharedContextChoice;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Owns the question-capture controls, pending regions, previews, validation,
 * and save workflow for new, imported and edited questions. Shared shared
 * contexts are captured separately from ordinary question regions. All control
 * and capture-state access belongs on the JavaFX application thread.
 */
public final class QuestionCapturePane extends VBox {

	private static final double REGION_VIEWPORT_EXTRA_HEIGHT = 4.0;
	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double MARKS_FIELD_WIDTH = 60.0;
	private static final double QUESTION_CODE_FIELD_WIDTH = 80.0;
	private static final double REGION_PREVIEW_ITEM_SPACING = 5.0;
	private static final double REGION_PREVIEW_HORIZONTAL_INSET = 24.0;
	private static final double REGIONS_VIEWPORT_HEIGHT = 300.0;
	private static final double SECTION_SPACING = 10.0;
	private static final Insets COMPACT_BUTTON_PADDING = new Insets(2, 8, 2, 8);
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String REQUIRED_STATUS_STYLE = "-fx-text-fill: #b71c1c;-fx-font-weight: bold;";
	private static final String SECTION_HEADING_STYLE = "-fx-font-weight: bold;";
	private static final String SUCCESS_STATUS_STYLE = "-fx-text-fill: #2e7d32;";

	// Workflow dependencies and application callbacks.
	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final CurriculumSelectorPane curriculumSelectorPane;
	private final Supplier<ExamBooklet> bookletSupplier;
	private final Supplier<PdfSession> examPdfSessionSupplier;
	private final Runnable selectionClearHandler;
	private final Consumer<List<Question>> questionsChangedHandler;
	private final IntConsumer examPageNavigationHandler;

	// Reuse the worker's snapshot throughout synchronous listeners fired by save
	// completion.
	private List<Question> completionQuestions;
	private final Predicate<Question> importedQuestionActivationHandler;
	private final BooleanSupplier questionTargetChangeAllowed;
	private final BooleanSupplier questionSelectionTransferHandler;
	private final SharedContextCapturePane sharedContextCapturePane;
	private final SourceQuestionRepository sourceQuestionRepository;
	private final SqliteQuestionCaptureService questionCaptureService;
	private final LegacyQuestionSplitService legacyQuestionSplitService;

	// Capture mode and imported-question selection.
	private final ToggleButton newQuestionsModeButton = new ToggleButton("Capture New Questions");
	private final ToggleButton importedQuestionsModeButton = new ToggleButton("Capture Imported Questions");
	private final ToggleGroup captureModeGroup = new ToggleGroup();
	private final ComboBox<Question> importedQuestionBox = new ComboBox<>();
	private final Label importedClassificationLabel = new Label();
	private final Label captureHintLabel = new Label();
	private final VBox legacyCaptureBox = new VBox(COMPACT_SPACING);
	private final Label questionCodeStatusLabel = new Label();

	// Question metadata and shared shared context controls.
	private final TextField questionCodeField = new TextField();
	private final TextField marksField = new TextField();
	private final ToggleGroup responseTypeGroup = new ToggleGroup();
	private final RadioButton multipleChoiceResponseButton = new RadioButton("Multiple choice");
	private final RadioButton writtenResponseButton = new RadioButton("Written response");
	private final CheckBox firstRegionSharedContextCheckBox = new CheckBox("First region is shared context");
	private final Label sharedContextStatusLabel = new Label();
	private final VBox sharedContextControlsBox = new VBox(COMPACT_SPACING);

	// Pending PDF selection and accepted mixed Question-content controls.
	private final Button addRegionButton = new Button("Add Region");
	private final Button pasteImageButton = new Button("Paste Image");
	private final Button removeCurrentSelectionButton = new Button("Clear");
	private final Button clearRegionsButton = new Button("Clear Content");
	private final Label regionCountLabel = new Label("Content parts: 0");
	private final VBox regionPreviewBox = new VBox(SECTION_SPACING);
	private final ScrollPane regionsScrollPane = new ScrollPane(regionPreviewBox);
	private final QuestionClipboardImageReader clipboardImageReader = new QuestionClipboardImageReader();

	// Save and edit controls.
	private final Button saveQuestionButton = new Button("Save Question");
	private final Button cancelQuestionEditButton = new Button("Cancel");
	private final Label saveStatusLabel = new Label();

	// Transient capture and edit state.
	private boolean refreshingSharedContextControls;

	// The most recently refreshed Question corpus is sufficient for synchronous
	// duplicate-code feedback without querying SQLite for every keystroke.
	private List<Question> questionSnapshot = List.of();

	// Independent MCQs use one persisted booklet-level continuation rather than
	// SourceQuestion multipart identity.
	private boolean mcqSharedContextCaptureActive;
	private boolean mcqContinuationSelected;
	private SharedQuestionContext inheritedMcqSharedContext;
	private boolean questionSaveInProgress;
	private Question importedQuestion;
	private boolean refreshingImportedQuestions;
	private boolean importedCaptureMode;
	private QuestionRegion currentSelection;

	// This list is the authoritative transient assembly order for the Question
	// body.
	// PDF selections and pasted images can therefore be interleaved arbitrarily.
	private final List<QuestionContentPart> pendingContentParts = new ArrayList<>();
	private Question editingQuestion;
	private boolean loadingQuestionEdit;
	private Runnable questionEditCompletedHandler = () -> {
	};
	private LegacySplitCaptureState legacySplitCaptureState;

	// Working Subject is transient workspace state. A null value preserves the
	// existing all-subject behaviour until the workspace selector is applied.
	private Subject workingSubject;

	// Automatic defaults may be replaced by an explicit response-type choice.
	private boolean responseTypeManuallySelected;

	// Suppress inference while a response-type change itself updates the marks
	// field.
	private boolean updatingResponseTypeSelection;

	/**
	 * Creates the question-capture workflow and its repository integration.
	 * Suppliers provide the active booklet and PDF session, while the callbacks
	 * coordinate selection ownership, imported-question activation and downstream
	 * question refreshes with the containing application.
	 *
	 * @param questionRepository                Question lookup and persistence
	 * @param sourceQuestionRepository          multipart source-identity lookup
	 * @param questionCaptureService            atomic capture persistence service
	 * @param legacyQuestionSplitService        atomic legacy split service
	 * @param sharedContextCapturePane          shared-context capture controls
	 * @param questionExtractor                 extractor used for captured-region
	 *                                          previews
	 * @param curriculumSelectionModel          selected classification state
	 * @param curriculumSelectorPane            classification controls
	 * @param bookletSupplier                   supplier of the active Exam booklet
	 * @param examPdfSessionSupplier            supplier of the active Exam PDF
	 *                                          session
	 * @param importedQuestionActivationHandler opens an imported Question's source
	 * @param examPageNavigationHandler         callback that navigates Exam pages
	 * @param questionTargetChangeAllowed       guard for changing capture targets
	 * @param questionSelectionTransferHandler  transfers PDF-selection ownership
	 * @param selectionClearHandler             callback that clears the shared PDF
	 *                                          selection
	 * @param questionsChangedHandler           callback receiving refreshed
	 *                                          Questions after a save
	 */
	public QuestionCapturePane(QuestionRepository questionRepository, SourceQuestionRepository sourceQuestionRepository,
			SqliteQuestionCaptureService questionCaptureService, LegacyQuestionSplitService legacyQuestionSplitService,
			SharedContextCapturePane sharedContextCapturePane, QuestionExtractor questionExtractor,
			CurriculumSelectionModel curriculumSelectionModel, CurriculumSelectorPane curriculumSelectorPane,
			Supplier<ExamBooklet> bookletSupplier, Supplier<PdfSession> examPdfSessionSupplier,
			Predicate<Question> importedQuestionActivationHandler, IntConsumer examPageNavigationHandler,
			BooleanSupplier questionTargetChangeAllowed, BooleanSupplier questionSelectionTransferHandler,
			Runnable selectionClearHandler, Consumer<List<Question>> questionsChangedHandler) {
		validateDependencies(questionRepository, sourceQuestionRepository, questionCaptureService,
				legacyQuestionSplitService, sharedContextCapturePane, questionExtractor, curriculumSelectionModel,
				curriculumSelectorPane, bookletSupplier, examPdfSessionSupplier, importedQuestionActivationHandler,
				examPageNavigationHandler, questionTargetChangeAllowed, questionSelectionTransferHandler,
				selectionClearHandler, questionsChangedHandler);
		this.questionRepository = questionRepository;
		this.questionExtractor = questionExtractor;
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.curriculumSelectorPane = curriculumSelectorPane;
		this.bookletSupplier = bookletSupplier;
		this.examPdfSessionSupplier = examPdfSessionSupplier;
		this.selectionClearHandler = selectionClearHandler;
		this.questionsChangedHandler = questionsChangedHandler;
		this.importedQuestionActivationHandler = importedQuestionActivationHandler;
		this.examPageNavigationHandler = examPageNavigationHandler;
		this.questionTargetChangeAllowed = questionTargetChangeAllowed;
		this.sourceQuestionRepository = sourceQuestionRepository;
		this.sharedContextCapturePane = sharedContextCapturePane;
		this.questionSelectionTransferHandler = questionSelectionTransferHandler;
		this.questionCaptureService = questionCaptureService;
		this.legacyQuestionSplitService = legacyQuestionSplitService;
		configureControls();
		configureActions();
		buildContent();
		configurePane();
	}

	/**
	 * Returns imported Questions still requiring Question-body or shared-context
	 * capture, optionally restricted to one Working Subject.
	 *
	 * @param questions      current Question snapshot
	 * @param workingSubject active Working Subject, or {@code null} for all
	 *                       Subjects
	 * @return Questions awaiting Question-side capture
	 */
	static List<Question> awaitingCaptureForWorkingSubject(List<Question> questions, Subject workingSubject) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		List<Question> awaitingCapture = new ArrayList<>();
		for (Question question : questions) {

			// Working Subject is a transient workspace filter. Questions belonging to
			// another Subject remain persisted but are excluded from the active queue.
			if (workingSubject != null && !question.getExam().getSubject().equals(workingSubject)) {
				continue;
			}

			// Imported Questions remain in this queue until ordinary regions are captured
			// and any unresolved shared-context requirement has also been resolved.
			if (question.getContentParts().isEmpty() || question.isSharedContextUnresolved()) {
				awaitingCapture.add(question);
			}
		}
		return List.copyOf(awaitingCapture);
	}

	/**
	 * Accepts a proportional exam-page selection as the current pending region.
	 *
	 * @param selection the selected exam-page rectangle
	 */
	public void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()) {
			clearCurrentSelection();
			showAlert(Alert.AlertType.INFORMATION, "Question content already captured.",
					"This imported question already has its question content. "
							+ "Only the unresolved shared context needs to be captured.");
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			clearCurrentSelection();
			showAlert(Alert.AlertType.WARNING, "Exam details have not been set.",
					"Enter the exam and booklet details, then click Set Exam.");
			return;
		}
		currentSelection = new QuestionRegion(booklet, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		saveStatusLabel.setText("Selection pending — click Add Region or Clear");
		refreshSaveButtonState();
	}

	/**
	 * Routes a proportional exam-page selection to active shared-context capture.
	 *
	 * @param selection the selected exam-page rectangle
	 */
	public void acceptSharedContextSelection(PdfWorkspacePane.RegionSelection selection) {
		sharedContextCapturePane.acceptSelection(selection);
		saveStatusLabel.setText("Shared context selection pending — click Add Region or Clear");
		refreshSaveButtonState();
	}

	/**
	 * Starts staged region capture for an explicitly defined legacy Question split.
	 * Nothing is persisted until every resulting part has been captured.
	 *
	 * @param question         original persisted Question
	 * @param definition       user-confirmed split metadata
	 * @param completedHandler callback after save or cancellation
	 * @return whether split capture started
	 */
	public boolean beginLegacyQuestionSplit(Question question, LegacyQuestionSplitDialog.Result definition,
			Runnable completedHandler) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (definition == null) {
			throw new NullPointerException("definition");
		}
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
		}
		if (!definition.sourceQuestionCode().equals(question.getQuestionCode())) {
			throw new IllegalArgumentException("Split source code must match the original Question code");
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return false;
		}
		if (!importedQuestionActivationHandler.test(question)) {
			restoreCaptureModeToggle();
			return false;
		}

		// Start the correction on the source page already associated with the original
		// Question where possible.
		showFirstQuestionRegionPage(question);
		importedQuestion = null;
		importedCaptureMode = false;
		editingQuestion = null;
		questionEditCompletedHandler = () -> {
		};
		captureModeGroup.selectToggle(null);
		setLegacyCaptureControlsVisible(false);

		// Clear ordinary capture state before establishing split-specific state.
		resetQuestionEntry();
		legacySplitCaptureState = new LegacySplitCaptureState(question, definition, completedHandler);
		setCaptureModeControlsDisabled(true);
		loadActiveLegacySplitPart();
		if (definition
				.sharedContextChoice() == LegacyQuestionSplitDialog.SharedContextChoice.CAPTURE_NEW_SHARED_CONTEXT) {
			boolean started = sharedContextCapturePane
					.beginAutomaticContext("Question " + definition.sourceQuestionCode() + " context");
			if (!started) {
				cancelLegacyQuestionSplit();
				return false;
			}
			showSharedContextRequiredStatus("Capture the shared context before capturing "
					+ legacySplitCaptureState.activeDefinition().questionCode() + ".");
		}
		if (definition
				.sharedContextChoice() == LegacyQuestionSplitDialog.SharedContextChoice.REUSE_EXISTING_SHARED_CONTEXT) {
			sharedContextCapturePane.selectContext(definition.existingSharedContext());
			showSharedContextStatus("Reusing shared context for Question " + definition.sourceQuestionCode());
		}
		refreshSaveButtonState();
		return true;
	}

	/**
	 * Opens imported-question capture and selects one specific incomplete question.
	 *
	 * @param question persisted question requiring source/context work
	 * @return whether the requested question became the active capture target
	 */
	public boolean captureImportedQuestion(Question question) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		showImportedQuestionCapture();
		if (!importedCaptureMode) {
			return false;
		}
		Question matching = importedQuestionBox.getItems().stream()
				.filter(candidate -> candidate.getId() == question.getId()).findFirst().orElse(null);
		if (matching == null) {
			return false;
		}
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(matching);
		} finally {
			refreshingImportedQuestions = false;
		}
		loadImportedQuestion(matching);
		return importedQuestion != null && importedQuestion.getId() == question.getId();
	}

	/**
	 * Discards the current unaccepted region selection.
	 */
	public void clearCurrentSelection() {
		if (sharedContextCapturePane.isCaptureMode() && sharedContextCapturePane.hasCurrentSelection()) {
			sharedContextCapturePane.clearCurrentSelection();
			refreshSaveButtonState();
			return;
		}
		currentSelection = null;
		selectionClearHandler.run();
		refreshSaveButtonState();
	}

	/**
	 * Clears all transient question regions when the exam PDF changes.
	 */
	public void clearForNewPdf() {
		importedQuestion = null;
		importedCaptureMode = false;
		editingQuestion = null;
		questionEditCompletedHandler = () -> {
		};
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		showNewQuestionMode();
		resetQuestionEntry();
		sharedContextCapturePane.refreshForCurrentBooklet();
		refreshImportedQuestions();
	}

	/**
	 * Clears the transient question save/update status.
	 */
	public void clearSaveStatus() {
		saveStatusLabel.setText("");
	}

	/**
	 * Clears the current unaccepted shared-context selection.
	 */
	public void clearSharedContextCurrentSelection() {
		sharedContextCapturePane.clearCurrentSelection();
		refreshSaveButtonState();
	}

	/**
	 * Discards local unaccepted Question or shared-context selection state after a
	 * different workflow takes ownership of the single PDF selection.
	 */
	public void discardCurrentSelectionForOwnershipLoss() {

		// Do not invoke the normal clear handler here. The PDF workspace already
		// contains the replacement selection belonging to another workflow.
		currentSelection = null;
		sharedContextCapturePane.discardCurrentSelectionForOwnershipLoss();
		if (sharedContextCapturePane.isCaptureMode()) {

			// Shared-context capture remains active even though its previous rectangle was
			// superseded by another workflow.
			saveStatusLabel.setText("Shared context capture active — select a region");
		} else {

			// Restore status to the accepted-region state rather than a stale pending
			// state.
			showQuestionPendingStatus();
		}
		refreshSaveButtonState();
	}

	/**
	 * Loads a question and its ordered regions for editing after capture-transition
	 * guards and booklet activation succeed. The existing syllabus is locked.
	 *
	 * @param question             the persisted question to load
	 * @param editCompletedHandler callback when the edit is saved or cancelled
	 * @return whether the edit was started
	 * @throws NullPointerException if either argument is null
	 */
	public boolean editQuestion(Question question, Runnable editCompletedHandler) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (editCompletedHandler == null) {
			throw new NullPointerException("editCompletedHandler");
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return false;
		}
		if (!importedQuestionActivationHandler.test(question)) {
			restoreCaptureModeToggle();
			return false;
		}
		showFirstQuestionRegionPage(question);
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(null);
		} finally {
			refreshingImportedQuestions = false;
		}
		importedQuestion = null;
		importedCaptureMode = false;
		setLegacyCaptureControlsVisible(false);
		captureModeGroup.selectToggle(null);
		clearRegions();
		editingQuestion = question;
		questionEditCompletedHandler = editCompletedHandler;
		sharedContextCapturePane.refreshForCurrentBooklet();
		loadQuestionEditFields(question);

		// Editing loads the complete persisted assembly rather than only its PDF
		// subset.
		pendingContentParts.addAll(question.getContentParts());
		refreshRegionPreviews();
		setRegionCountLabel(pendingContentParts.size());
		questionCodeField.setDisable(false);
		marksField.setDisable(false);
		setResponseTypeDisabled(false);

		// Editing an existing MCQ still enforces its one-mark UI invariant.
		updateMarksFieldForResponseType();
		curriculumSelectorPane.setDisable(false);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		hideImportedClassification();
		saveQuestionButton.setText("Update Question");
		cancelQuestionEditButton.setVisible(true);
		cancelQuestionEditButton.setManaged(true);
		showCaptureHint(
				"Editing stored question " + question.getQuestionCode() + ". Exam and booklet cannot be changed.");
		refreshSharedContextControls();
		saveStatusLabel.setText("Editing " + question.getQuestionCode() + " — " + pendingContentParts.size()
				+ " stored content part(s) loaded");
		refreshSaveButtonState();
		return true;
	}

	/**
	 * Indicates whether accepted Question content or automatic context regions
	 * would be discarded by a workflow transition.
	 * <p>
	 * The retained method name is preserved for existing callers, but accepted
	 * Question content may now contain PDF regions, pasted images, or both.
	 *
	 * @return {@code true} when accepted transient Question content exists
	 */
	public boolean hasAcceptedRegions() {
		boolean splitPartsStaged = legacySplitCaptureState != null && !legacySplitCaptureState.completedParts.isEmpty();
		return !pendingContentParts.isEmpty() || sharedContextCapturePane.hasPendingAutomaticRegion()
				|| splitPartsStaged;
	}

	/**
	 * Returns whether the shared-context pane currently owns PDF selections.
	 *
	 * @return {@code true} while the shared-context pane owns PDF selections
	 */
	public boolean isCapturingSharedContext() {
		return sharedContextCapturePane.isCaptureMode();
	}

	/**
	 * Returns whether Question persistence is in progress.
	 *
	 * @return whether a question save transaction is currently running
	 */
	public boolean isSaveInProgress() {
		return questionSaveInProgress;
	}

	/**
	 * Starts a full recapture of an existing question.
	 * <p>
	 * Stored regions remain persisted until the replacement edit is successfully
	 * saved. The transient edit starts with no accepted regions.
	 *
	 * @param question             persisted question to recapture
	 * @param editCompletedHandler callback when recapture is saved or cancelled
	 * @return whether recapture was started
	 */
	public boolean recaptureQuestion(Question question, Runnable editCompletedHandler) {
		if (!editQuestion(question, editCompletedHandler)) {
			return false;
		}
		clearRegions();
		showCaptureHint("Recapturing complete question " + question.getQuestionCode()
				+ ". Capture the complete replacement question.");
		saveStatusLabel.setText("Recapturing " + question.getQuestionCode()
				+ " — stored regions remain unchanged until Update Question");
		return true;
	}

	/**
	 * Starts replacement capture for the shared context linked to a Question.
	 *
	 * <p>
	 * The Question's booklet and exam PDF are activated before the shared-context
	 * editor is shown. The persisted shared context remains unchanged until the
	 * replacement is saved.
	 * </p>
	 *
	 * @param question         a Question linked to the shared context to replace
	 * @param completedHandler callback after replacement is saved or cancelled
	 * @return {@code true} when correction capture started
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the Question has no shared context
	 */
	public boolean recaptureSharedContext(Question question, Runnable completedHandler) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
		}
		if (!question.hasSharedContext()) {
			throw new IllegalArgumentException("Question does not have a shared context to recapture");
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return false;
		}
		if (!importedQuestionActivationHandler.test(question)) {
			restoreCaptureModeToggle();
			return false;
		}

		// Activate the authoritative booklet and stored exam PDF before any new
		// shared-context regions can be selected.
		showFirstSharedContextRegionPage(question.getSharedContext());

		// Shared-context correction is independent of Question editing, so clear
		// ordinary edit/queue ownership before preparing the contextual display.
		importedQuestion = null;
		importedCaptureMode = false;
		editingQuestion = null;
		questionEditCompletedHandler = () -> {
		};
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);

		// Clear stale capture state first, then restore this Question's persisted
		// metadata as read-only context for the correction.
		resetQuestionEntry();
		showSharedContextCorrectionQuestion(question);
		sharedContextCapturePane.refreshForCurrentBooklet();
		setSharedContextCorrectionVisible(true);
		boolean started = sharedContextCapturePane.recaptureContext(question.getSharedContext(),
				() -> finishSharedContextRecapture(completedHandler));
		if (!started) {
			setSharedContextCorrectionVisible(false);
			showNewQuestionMode();
			resetQuestionEntry();
			return false;
		}
		return true;
	}

	/**
	 * Reapplies the active booklet's response-type default after a booklet has been
	 * opened or reactivated.
	 */
	public void refreshForActiveBooklet() {

		// Persisted imported/edit Questions remain authoritative. Their surrounding
		// workflows clear and reload capture state explicitly after booklet activation.
		if (importedCaptureMode || importedQuestion != null || editingQuestion != null
				|| legacySplitCaptureState != null) {
			return;
		}

		// A successful booklet activation establishes a new Question-capture target.
		// No unaccepted rectangle, accepted region, Question metadata or transient
		// shared-context state may survive from the previous booklet.
		resetQuestionEntry();

		// resetQuestionEntry clears the actual capture state; clear the presentation
		// status separately so a green "Pending Q..." message from the previous booklet
		// cannot remain visible after the switch.
		clearSaveStatus();
	}

	/**
	 * Reloads persisted questions that still require question-region capture while
	 * retaining the selected item when it remains available.
	 */
	public void refreshImportedQuestions() {
		refreshImportedQuestions(currentQuestions());
	}

	/**
	 * Changes the transient Subject used to filter the imported Question-capture
	 * queue.
	 *
	 * @param workingSubject Subject to display, or {@code null} to display all
	 *                       Subjects
	 */
	public void setWorkingSubject(Subject workingSubject) {
		this.workingSubject = workingSubject;

		// Re-read the current corpus so a Working Subject change immediately updates
		// the imported Question queue without modifying persisted Questions.
		refreshImportedQuestions();
	}

	/**
	 * Makes imported-question capture controls available and refreshes their data.
	 */
	public void showLegacyCaptureControls() {
		showImportedQuestionCapture();
	}

	/**
	 * Attempts to edit a question without a completion callback.
	 *
	 * @param question the persisted question to load
	 */
	void editQuestion(Question question) {
		editQuestion(question, () -> {
		});
	}

	/**
	 * Attempts to switch to imported-question capture, subject to the pending-state
	 * guards. A rejected transition restores the previous mode toggle.
	 */
	void showImportedQuestionCapture() {
		if ((!importedCaptureMode || editingQuestion != null) && !captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return;
		}
		try {

			// Repair derived source-question relationships before resolving any
			// reusable shared-context relationships.
			backfillDerivedSourceQuestions();
			reconcileKnownSharedContexts();
		} catch (IllegalStateException e) {
			showAlert(Alert.AlertType.ERROR, "Shared context links are inconsistent.", e.getMessage());
			restoreCaptureModeToggle();
			return;
		}
		if (!importedCaptureMode || editingQuestion != null) {
			questionEditCompletedHandler = () -> {
			};
			editingQuestion = null;
			clearImportedQuestionSelection();
			importedCaptureMode = true;
		}
		selectCaptureModeToggle(true);

		// Refresh the queue after relationship reconciliation so the visible
		// questions reflect the persisted capture state.
		refreshImportedQuestions();
		setLegacyCaptureControlsVisible(true);
		if (importedQuestion == null) {
			showImportedQueueMode();
		}
	}

	/**
	 * Attempts to switch to new-question capture, subject to pending-state guards.
	 * Accepted state is cleared only after the transition is allowed.
	 */
	void showNewQuestionCapture() {
		if (!importedCaptureMode && editingQuestion == null) {
			selectCaptureModeToggle(false);
			setLegacyCaptureControlsVisible(false);
			return;
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return;
		}
		questionEditCompletedHandler = () -> {
		};
		editingQuestion = null;
		importedCaptureMode = false;
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		clearImportedQuestionSelection();
	}

	private void addContentPreview(QuestionContentPart part, int contentIndex) {
		ImageView imageView;
		String description;
		if (part instanceof PdfQuestionContentPart pdfPart) {
			QuestionRegion region = pdfPart.region();
			try {
				BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
				imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
			} catch (IOException exception) {
				throw new RuntimeException("Unable to preview PDF Question content", exception);
			}
			description = String.format("Part %d — PDF page %d", contentIndex + 1, region.pageNumber());
		} else if (part instanceof ImageQuestionContentPart imagePart) {
			Image image = new Image(new ByteArrayInputStream(imagePart.pngBytes()));
			if (image.isError()) {
				throw new IllegalStateException("Unable to preview stored Question image", image.getException());
			}
			imageView = new ImageView(image);
			description = String.format("Part %d — Pasted image", contentIndex + 1);
		} else {
			throw new IllegalStateException("Unsupported Question content part: " + part.getClass().getName());
		}
		imageView.setPreserveRatio(true);

		// Keep the current stable width behaviour; scrollbar appearance must not start
		// a resize loop.
		imageView.fitWidthProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.max(0.0, regionsScrollPane.getWidth() - REGION_PREVIEW_HORIZONTAL_INSET),
						regionsScrollPane.widthProperty()));
		imageView.setSmooth(true);
		Label label = new Label(description);
		Button moveUpButton = new Button("Up");
		moveUpButton.setId("question-content-move-up-" + contentIndex);
		moveUpButton.setDisable(contentIndex == 0);
		moveUpButton.setOnAction(_ -> moveContentPart(contentIndex, -1));
		Button moveDownButton = new Button("Down");
		moveDownButton.setId("question-content-move-down-" + contentIndex);
		moveDownButton.setDisable(contentIndex == pendingContentParts.size() - 1);
		moveDownButton.setOnAction(_ -> moveContentPart(contentIndex, 1));
		Button removeButton = new Button("Remove");
		removeButton.setId("question-content-remove-" + contentIndex);
		removeButton.setOnAction(_ -> removeContentPart(contentIndex));
		HBox header = new HBox(SECTION_SPACING, label, moveUpButton, moveDownButton, removeButton);
		VBox preview = new VBox(REGION_PREVIEW_ITEM_SPACING, header, imageView);
		preview.setId("question-content-part-" + contentIndex);
		regionPreviewBox.getChildren().add(preview);
	}

	private void addCurrentRegion() {
		if (sharedContextCapturePane.isCaptureMode()) {
			if (!sharedContextCapturePane.hasCurrentSelection()) {
				return;
			}
			if (!sharedContextCapturePane.acceptAutomaticRegion()) {
				return;
			}
			updateQuestionCodeLock();
			hideSharedContextStatus();
			if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()) {
				saveStatusLabel.setText("Shared context captured — save the resolution.");
			} else {
				saveStatusLabel.setText("Shared context captured — add the question content.");
			}
			refreshSaveButtonState();
			return;
		}
		if (currentSelection == null) {
			return;
		}

		// PDF selections become one content part at their current assembly position.
		pendingContentParts.add(new PdfQuestionContentPart(currentSelection));
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(pendingContentParts.size());
		showQuestionPendingStatus();
		updateQuestionCodeLock();
		refreshSaveButtonState();
	}

	private void advanceLegacyQuestionSplit() {
		LegacySplitCaptureState state = legacySplitCaptureState;
		LegacyQuestionSplitDialog.PartDefinition definition = state.activeDefinition();
		SplitPart capturedPart = new SplitPart(definition.questionCode(), definition.marks(),
				definition.classification(), definition.responseType(), pendingQuestionRegions());
		if (!state.isLastPart()) {

			// Completed parts remain only in memory. SQLite still contains the original
			// unsplit Question at this point.
			state.completedParts.add(capturedPart);
			state.activePartIndex++;
			loadActiveLegacySplitPart();
			return;
		}
		List<SplitPart> completeParts = new ArrayList<>(state.completedParts);
		completeParts.add(capturedPart);
		SplitRequest request = createLegacySplitRequest(state, completeParts);
		persistLegacyQuestionSplit(request);
	}

	private void applyAutomaticResponseType() {

		// Imported, edited and legacy-split Questions retain their persisted response
		// type. Booklet format controls only genuinely new Question capture.
		if (importedCaptureMode || importedQuestion != null || editingQuestion != null
				|| legacySplitCaptureState != null) {
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {

			// Without an active booklet there is no format constraint or safe default.
			setResponseTypeDisabled(false);
			selectResponseType(null);
			updateMarksFieldForResponseType();
			return;
		}
		switch (booklet.getQuestionFormat()) {
		case MULTIPLE_CHOICE -> {

			// An MCQ-only booklet fixes every new Question as Multiple Choice.
			responseTypeManuallySelected = false;
			selectResponseType(QuestionResponseType.MULTIPLE_CHOICE);
			setResponseTypeDisabled(true);
		}
		case WRITTEN_RESPONSE -> {

			// A Written-Response-only booklet fixes every new Question as Written
			// Response while leaving its mark value editable.
			responseTypeManuallySelected = false;
			selectResponseType(QuestionResponseType.WRITTEN_RESPONSE);
			setResponseTypeDisabled(true);
		}
		case MIXED, UNSPECIFIED -> {

			// Only Mixed or unresolved legacy booklets allow a per-Question response-type
			// choice. An explicit user choice takes precedence over later inference.
			setResponseTypeDisabled(false);
			if (responseTypeManuallySelected) {
				return;
			}

			// Conservative inference may identify Written Response, but one mark alone
			// must never be treated as evidence of Multiple Choice.
			selectResponseType(shouldInferWrittenResponse() ? QuestionResponseType.WRITTEN_RESPONSE : null);
		}
		}

		// Apply the one-mark MCQ invariant, or restore editable marks for Written
		// Response, after the booklet policy has selected the response type.
		updateMarksFieldForResponseType();
	}

	private void backfillDerivedSourceQuestions() {
		for (Question question : questionRepository.findAll()) {
			if (question.hasSourceQuestion()) {
				continue;
			}
			String sourceCode = SourceQuestionCodeParser.derive(question.getQuestionCode());
			if (sourceCode == null) {
				continue;
			}
			ExamBooklet booklet = question.getBooklet();
			SourceQuestion sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode)
					.orElseGet(() -> sourceQuestionRepository.save(booklet, sourceCode));
			questionRepository.updateCaptureRelationships(question.getId(), question.getClassification(),
					sourceQuestion, question.getSharedContext());
		}
	}

	private boolean beginAutomaticSharedContextCapture(String label) {
		if (currentSelection != null) {

			// Reuse an already-drawn Question rectangle as the shared context instead of
			// forcing the user to draw it again.
			boolean started = sharedContextCapturePane.beginAutomaticContext(label, currentSelection);
			if (!started) {
				return false;
			}
			if (!questionSelectionTransferHandler.getAsBoolean()) {
				sharedContextCapturePane.cancelAutomaticContext();
				return false;
			}

			// The rectangle now belongs to SharedContextCapturePane. Leave the visible
			// PDF rectangle in place while changing its logical owner.
			currentSelection = null;
			saveStatusLabel.setText("Shared context selection pending — click Add Context or Clear");
			return true;
		}
		boolean started = sharedContextCapturePane.beginAutomaticContext(label);
		if (started) {
			saveStatusLabel.setText("Shared context capture active — select a region");
		}
		return started;
	}

	private void buildContent() {
		legacyCaptureBox.getChildren().addAll(new Label("Question awaiting capture"), createImportedQuestionControls(),
				importedClassificationLabel, captureHintLabel);
		sharedContextControlsBox.getChildren().addAll(firstRegionSharedContextCheckBox, sharedContextStatusLabel);
		setLegacyCaptureControlsVisible(false);

		// The shared-context editor is exposed only for an explicit correction
		// workflow. Automatic shared context capture continues to use it as internal
		// state.
		sharedContextCapturePane.setVisible(false);
		sharedContextCapturePane.setManaged(false);
		getChildren().addAll(createSectionLabel("Question"), createCaptureModeControls(), legacyCaptureBox,
				createQuestionControls(), sharedContextControlsBox, saveStatusLabel, createCurrentSelectionControls(),
				new Separator(), new Label("Accepted Question content"), regionCountLabel, createRegionsScrollPane(),
				sharedContextCapturePane);
	}

	private void cancelLegacyQuestionSplit() {
		Runnable completedHandler = finishLegacyQuestionSplitState();
		if (completedHandler != null) {
			completedHandler.run();
		}
	}

	private void cancelQuestionEdit() {
		if (legacySplitCaptureState != null) {
			cancelLegacyQuestionSplit();
			return;
		}
		if (editingQuestion == null) {
			return;
		}
		finishQuestionEdit();
	}

	private boolean captureModeChangeAllowed() {
		if (questionSaveInProgress) {
			return false;
		}
		if (currentSelection != null || sharedContextCapturePane.hasCurrentSelection()) {
			showAlert(Alert.AlertType.WARNING, "Selection pending.",
					"Add or clear the current selection before changing question capture mode.");
			return false;
		}
		if (hasAcceptedRegions() && !questionTargetChangeAllowed.getAsBoolean()) {
			return false;
		}
		return true;
	}

	private SqliteQuestionCaptureService.Operation captureOperation() {
		if (editingQuestion != null) {
			return SqliteQuestionCaptureService.Operation.EDIT;
		}
		if (importedQuestion != null) {
			return SqliteQuestionCaptureService.Operation.IMPORTED;
		}
		return SqliteQuestionCaptureService.Operation.NEW;
	}

	private void checkImportedQuestionBox() {
		if (!refreshingImportedQuestions) {
			loadImportedQuestion(importedQuestionBox.getValue());
		}
	}

	private void clearImportedQuestionSelection() {
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(null);
		} finally {
			refreshingImportedQuestions = false;
		}
		importedQuestion = null;
		showNewQuestionMode();
		resetQuestionEntry();
	}

	private void clearPendingSelection() {
		clearCurrentSelection();
		showQuestionPendingStatus();
	}

	private void clearQuestionRegions() {
		clearRegions();
		showQuestionPendingStatus();
	}

	private void clearRegions() {

		// Clear both PDF-backed and pasted-image content.
		pendingContentParts.clear();
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(0);
		updateQuestionCodeLock();
		refreshSaveButtonState();
	}

	private void completeLegacyQuestionSplit(LegacySplitSaveResult result) {
		completionQuestions = result.questions();
		Runnable completedHandler = null;
		try {
			questionsChangedHandler.accept(result.questions());
			String sourceCode = result.splitResult().sourceQuestion().getSourceQuestionCode();
			completedHandler = finishLegacyQuestionSplitState();
			saveStatusLabel.setText("Split Question " + sourceCode + " into "
					+ result.splitResult().questions().stream().map(Question::getQuestionCode).toList());
			if (result.refreshFailure() != null) {
				showAlert(Alert.AlertType.WARNING, "Question split saved; lists could not be fully refreshed.",
						"The split is stored. Reopen Search or capture to reload the question lists.");
			}
		} finally {
			questionSaveInProgress = false;
			setDisable(false);
			try {
				refreshSaveButtonState();
			} finally {
				completionQuestions = null;
			}
		}
		if (completedHandler != null) {
			completedHandler.run();
		}
	}

	// Publish the committed result on the FX thread before handing control back to
	// an edit caller.
	private void completeQuestionSave(QuestionSaveResult result, boolean editing, boolean imported,
			int previousImportedIndex, boolean hadStoredContent) {
		completionQuestions = result.questions();
		Runnable editCompletedHandler = null;
		try {
			if (result.validationError() != null) {
				saveStatusLabel.setText("Question not saved — check question details");
				showAlert(Alert.AlertType.WARNING, "Question could not be saved.", result.validationError());
				return;
			}
			Question question = result.question();
			questionsChangedHandler.accept(result.questions());
			if (editing) {
				editCompletedHandler = finishQuestionEditState();
			} else {
				resetAfterQuestionSave(previousImportedIndex);
			}
			String action = "Saved";
			if (editing) {
				action = "Updated";
			} else if (imported) {
				action = hadStoredContent ? "Resolved" : "Captured";
			}
			showSavedQuestionStatus(action, question);
			if (result.refreshFailure() != null) {
				saveStatusLabel.setText("Saved " + question.getQuestionCode() + " — list refresh failed");
				showAlert(Alert.AlertType.WARNING, "Question saved; lists could not be fully refreshed.",
						"The question is stored. Do not save it again. Reopen capture to reload the question lists.");
			}
		} finally {
			questionSaveInProgress = false;
			setDisable(false);
			try {
				refreshSaveButtonState();
			} finally {
				completionQuestions = null;
			}
		}
		if (editCompletedHandler != null) {
			editCompletedHandler.run();
		}
	}

	private void configureActions() {
		addRegionButton.setOnAction(_ -> addCurrentRegion());
		pasteImageButton.setOnAction(_ -> pasteClipboardImage());
		clearRegionsButton.setOnAction(_ -> clearQuestionRegions());
		removeCurrentSelectionButton.setOnAction(_ -> clearPendingSelection());
		saveQuestionButton.setOnAction(_ -> validateQuestionForSave());
		questionCodeField.textProperty().addListener((_, _, newCode) -> handleQuestionCodeChanged(newCode));
		marksField.textProperty().addListener((_, _, _) -> {

			// Mixed-book inference may become decisive when marks change, but marks
			// changes caused by selecting MCQ must not recursively change the selection.
			if (!updatingResponseTypeSelection) {
				applyAutomaticResponseType();
			}
			refreshSaveButtonState();
		});
		responseTypeGroup.selectedToggleProperty().addListener((_, _, _) -> handleResponseTypeChanged());

		// Action events represent an explicit user override. Programmatic defaults and
		// inference use selectResponseType() and therefore do not set this flag.
		multipleChoiceResponseButton.setOnAction(_ -> responseTypeManuallySelected = true);
		writtenResponseButton.setOnAction(_ -> responseTypeManuallySelected = true);
		curriculumSelectorPane.selectedClassificationProperty().addListener((_, _, _) -> refreshSaveButtonState());
		firstRegionSharedContextCheckBox.selectedProperty()
				.addListener((_, _, selected) -> handleSharedContextOptionChanged(selected.booleanValue()));
		importedQuestionBox.setOnAction(_ -> checkImportedQuestionBox());
		newQuestionsModeButton.setOnAction(_ -> showNewQuestionCapture());
		importedQuestionsModeButton.setOnAction(_ -> showImportedQuestionCapture());
		cancelQuestionEditButton.setOnAction(_ -> cancelQuestionEdit());
	}

	private void configureControls() {
		questionCodeField.setId("question-code");
		questionCodeField.setPromptText("Q1");
		questionCodeField.setPrefWidth(QUESTION_CODE_FIELD_WIDTH);
		questionCodeStatusLabel.setId("question-code-status");
		questionCodeStatusLabel.setStyle(REQUIRED_STATUS_STYLE);
		questionCodeStatusLabel.setWrapText(true);
		questionCodeStatusLabel.setVisible(false);
		questionCodeStatusLabel.setManaged(false);
		marksField.setId("question-marks");
		marksField.setPromptText("1");
		marksField.setPrefWidth(MARKS_FIELD_WIDTH);
		multipleChoiceResponseButton.setId("question-response-type-multiple-choice");
		writtenResponseButton.setId("question-response-type-written");
		multipleChoiceResponseButton.setToggleGroup(responseTypeGroup);
		writtenResponseButton.setToggleGroup(responseTypeGroup);
		multipleChoiceResponseButton.setUserData(QuestionResponseType.MULTIPLE_CHOICE);
		writtenResponseButton.setUserData(QuestionResponseType.WRITTEN_RESPONSE);

		// Keep both labels fully readable at the minimum supported workspace width.
		multipleChoiceResponseButton.setMinWidth(Region.USE_PREF_SIZE);
		writtenResponseButton.setMinWidth(Region.USE_PREF_SIZE);
		saveQuestionButton.setId("save-question");
		saveQuestionButton.setDisable(true);
		saveStatusLabel.setId("question-save-status");

		// Capture, edit and context status messages can be longer than the narrow
		// workspace width, so keep the complete message visible by wrapping it.
		saveStatusLabel.setWrapText(true);
		saveStatusLabel.setMaxWidth(Double.MAX_VALUE);
		regionCountLabel.setId("question-region-count");
		addRegionButton.setId("add-question-region");
		pasteImageButton.setId("paste-question-image");
		pasteImageButton.setTooltip(new Tooltip("Paste an image from the clipboard into the Question body."));
		clearRegionsButton.setId("clear-question-content");
		removeCurrentSelectionButton.setId("clear-question-selection");

		// Keep Question-capture action labels fully readable at the minimum
		// supported capture-workspace width.
		addRegionButton.setMinWidth(Region.USE_PREF_SIZE);
		removeCurrentSelectionButton.setMinWidth(Region.USE_PREF_SIZE);
		saveQuestionButton.setMinWidth(Region.USE_PREF_SIZE);
		cancelQuestionEditButton.setMinWidth(Region.USE_PREF_SIZE);
		addRegionButton.setPadding(COMPACT_BUTTON_PADDING);
		removeCurrentSelectionButton.setPadding(COMPACT_BUTTON_PADDING);
		pasteImageButton.setMinWidth(Region.USE_PREF_SIZE);
		clearRegionsButton.setMinWidth(Region.USE_PREF_SIZE);
		pasteImageButton.setPadding(COMPACT_BUTTON_PADDING);
		clearRegionsButton.setPadding(COMPACT_BUTTON_PADDING);

		// Region actions are meaningful only while an unaccepted compatible PDF
		// selection is pending. Selection acceptance will enable them as required.
		addRegionButton.setDisable(true);
		removeCurrentSelectionButton.setDisable(true);
		saveStatusLabel.setStyle(SUCCESS_STATUS_STYLE);
		importedQuestionBox.setId("imported-question");
		importedQuestionBox.setPromptText("Select imported question");
		importedQuestionBox.setMaxWidth(Double.MAX_VALUE);
		importedQuestionBox.setConverter(createImportedQuestionConverter());
		importedQuestionBox.setOnShowing(_ -> showSelectedImportedQuestionDocument());
		importedClassificationLabel.setId("imported-classification");
		importedClassificationLabel.setWrapText(true);
		importedClassificationLabel.setVisible(false);
		importedClassificationLabel.setManaged(false);
		newQuestionsModeButton.setId("capture-mode-new");
		importedQuestionsModeButton.setId("capture-mode-imported");

		// Capture-mode actions must keep their complete labels at supported pane
		// widths.
		newQuestionsModeButton.setMinWidth(Region.USE_PREF_SIZE);
		importedQuestionsModeButton.setMinWidth(Region.USE_PREF_SIZE);
		newQuestionsModeButton.setToggleGroup(captureModeGroup);
		importedQuestionsModeButton.setToggleGroup(captureModeGroup);
		newQuestionsModeButton.setSelected(true);
		legacyCaptureBox.setId("legacy-question-capture");
		firstRegionSharedContextCheckBox.setId("first-region-shared-context");
		firstRegionSharedContextCheckBox.setTooltip(
				new Tooltip("Store the first captured region as shared context for all parts of this question."));
		firstRegionSharedContextCheckBox.setVisible(false);
		firstRegionSharedContextCheckBox.setManaged(false);
		sharedContextStatusLabel.setId("shared-context-status");
		sharedContextStatusLabel.setVisible(false);
		sharedContextStatusLabel.setManaged(false);
		sharedContextStatusLabel.setWrapText(true);
		captureHintLabel.setId("question-capture-hint");
		captureHintLabel.setWrapText(true);
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
		cancelQuestionEditButton.setId("cancel-question-edit");
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
	}

	private void configurePane() {
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	private boolean continueSharedContextToNextMcq() {
		return isNewIndependentMcqCapture() && mcqContinuationSelected;
	}

	private HBox createCaptureModeControls() {
		HBox controls = new HBox(CONTROL_SPACING, newQuestionsModeButton, importedQuestionsModeButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private VBox createCurrentSelectionControls() {

		// PDF-selection actions remain together because they operate on the rectangle
		// currently owned by Question capture.
		HBox selectionControls = new HBox(CONTROL_SPACING, addRegionButton, removeCurrentSelectionButton);
		selectionControls.setAlignment(Pos.CENTER_LEFT);

		// Clipboard and whole-body actions do not depend on a PDF rectangle.
		HBox contentControls = new HBox(CONTROL_SPACING, pasteImageButton, clearRegionsButton);
		contentControls.setAlignment(Pos.CENTER_LEFT);

		// Persistence actions stay on their own row so they remain readable in the
		// narrow capture workspace.
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox saveControls = new HBox(CONTROL_SPACING, spacer, saveQuestionButton, cancelQuestionEditButton);
		saveControls.setAlignment(Pos.CENTER_LEFT);
		VBox controls = new VBox(COMPACT_SPACING, selectionControls, contentControls, saveControls);
		controls.setFillWidth(true);
		return controls;
	}

	private HBox createImportedQuestionControls() {
		HBox controls = new HBox(CONTROL_SPACING, importedQuestionBox);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private StringConverter<Question> createImportedQuestionConverter() {

		// Convert imported Questions to the descriptive labels shown in the ComboBox.
		return createStringConverterList();
	}

	private SplitRequest createLegacySplitRequest(LegacySplitCaptureState state, List<SplitPart> completeParts) {
		NewSharedContext newSharedContext = null;
		SharedQuestionContext existingSharedContext = null;
		switch (state.definition.sharedContextChoice()) {
		case SharedContextChoice.NO_SHARED_CONTEXT -> {

			// No shared-context relationship is included in the transaction.
		}
		case SharedContextChoice.CAPTURE_NEW_SHARED_CONTEXT -> {
			newSharedContext = new NewSharedContext(sharedContextCapturePane.getPendingAutomaticContextRegions());
		}
		case SharedContextChoice.REUSE_EXISTING_SHARED_CONTEXT -> {
			existingSharedContext = state.definition.existingSharedContext();
		}
		}
		return new SplitRequest(state.originalQuestion, state.definition.sourceQuestionCode(), completeParts,
				state.definition.retainedPartIndex(), newSharedContext, existingSharedContext);
	}

	private VBox createQuestionControls() {
		Label questionLabel = new Label("Question");
		questionLabel.setId("question-code-label");
		questionLabel.setMinWidth(Region.USE_PREF_SIZE);
		Label marksLabel = new Label("Marks");
		marksLabel.setId("question-marks-label");
		marksLabel.setMinWidth(Region.USE_PREF_SIZE);

		// Keep the frequently edited Question number and marks on a short,
		// compact row that remains readable at narrow workspace widths.
		HBox questionDetails = new HBox(CONTROL_SPACING, questionLabel, questionCodeField, marksLabel, marksField);
		questionDetails.setAlignment(Pos.CENTER_LEFT);

		// Duplicate-code feedback sits directly below the field that caused it. It is
		// non-modal so the teacher can immediately correct the number and continue.
		Label responseTypeLabel = new Label("Response type");
		responseTypeLabel.setId("question-response-type-label");
		responseTypeLabel.setMinWidth(Region.USE_PREF_SIZE);

		// Response type remains on its own row so both explicit choices are visible.
		HBox responseTypeControls = new HBox(CONTROL_SPACING, responseTypeLabel, multipleChoiceResponseButton,
				writtenResponseButton);
		responseTypeControls.setId("question-response-type-controls");
		responseTypeControls.setAlignment(Pos.CENTER_LEFT);
		VBox controls = new VBox(COMPACT_SPACING, questionDetails, questionCodeStatusLabel, responseTypeControls);
		controls.setFillWidth(true);
		return controls;
	}

	private ScrollPane createRegionsScrollPane() {
		regionsScrollPane.setFitToWidth(true);
		regionsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		regionsScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		regionsScrollPane.setMinHeight(0);
		regionsScrollPane.setMaxHeight(REGIONS_VIEWPORT_HEIGHT);
		regionsScrollPane.prefHeightProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.min(REGIONS_VIEWPORT_HEIGHT,
								regionPreviewBox.getLayoutBounds().getHeight() + REGION_VIEWPORT_EXTRA_HEIGHT),
						regionPreviewBox.layoutBoundsProperty()));
		regionsScrollPane.setVisible(false);
		regionsScrollPane.setManaged(false);
		return regionsScrollPane;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private StringConverter<Question> createStringConverterList() {
		return new StringConverter<Question>() {

			@Override
			public Question fromString(String text) {

				// The ComboBox is selection-only, so displayed text is never parsed.
				return null;
			}

			@Override
			public String toString(Question question) {
				if (question == null) {
					return "";
				}
				ExamBooklet booklet = question.getBooklet();
				Exam exam = booklet.getExam();
				return String.format("%s %d — %s — %s — %d mark(s)", exam.getProvider().getName(), exam.getYear(),
						booklet.getName(), question.getQuestionCode(), question.getMarks());
			}
		};
	}

	private Task<QuestionSaveResult> createTaskList(SqliteQuestionCaptureService.Request request) {
		return new Task<>() {

			@Override
			protected QuestionSaveResult call() {
				return persistQuestionCapture(request);
			}
		};
	}

	private List<Question> currentQuestions() {
		return completionQuestions == null ? questionRepository.findAll() : completionQuestions;
	}

	private boolean editingSourceMatches(String questionCode) {
		if (editingQuestion == null) {
			return true;
		}
		String derivedSourceCode = SourceQuestionCodeParser.derive(questionCode);
		if (!editingQuestion.hasSourceQuestion()) {
			return derivedSourceCode == null;
		}
		return editingQuestion.getSourceQuestion().getSourceQuestionCode().equals(derivedSourceCode);
	}

	private Question findDuplicateQuestion(String questionCode) {
		ExamBooklet booklet = bookletSupplier.get();
		String code = questionCode == null ? "" : questionCode.trim();
		if (booklet == null || code.isBlank() || importedCaptureMode || importedQuestion != null
				|| legacySplitCaptureState != null) {
			return null;
		}
		for (Question question : questionSnapshot) {
			if (question.getBooklet().getId() != booklet.getId()) {
				continue;
			}
			if (!question.getQuestionCode().equals(code)) {
				continue;
			}

			// Editing a Question under its own existing code is valid. Changing it to the
			// code of another persisted Question is still a duplicate.
			if (editingQuestion != null && question.getId() == editingQuestion.getId()) {
				continue;
			}
			return question;
		}
		return null;
	}

	private String findQuestionDetailsValidationError() {
		Question duplicate = findDuplicateQuestion(questionCodeField.getText());
		if (duplicate != null) {
			return "Question " + duplicate.getQuestionCode() + " already exists for this booklet.";
		}
		int effectiveContentCount = pendingContentParts.size();
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()) {
			effectiveContentCount = importedQuestion.getContentParts().size();
		}
		String validationError = QuestionCaptureValidator.findError(new QuestionCaptureValidator.State(
				bookletSupplier.get() != null, questionCodeField.getText().trim(), marksField.getText().trim(),
				curriculumSelectionModel.getSubject() != null, curriculumSelectionModel.getUnit() != null,
				curriculumSelectionModel.getTopic() != null, curriculumSelectionModel.getClassification() != null,
				currentSelection != null, effectiveContentCount));
		if (validationError != null) {
			return validationError;
		}
		if (selectedResponseType() == null) {

			// By the time ordinary capture is available, the active booklet has already
			// supplied its default or the user must explicitly select a response type.
			return "Select whether this question is multiple choice or written response.";
		}
		return null;
	}

	private SharedQuestionContext findSharedContextForSourceQuestion(SourceQuestion sourceQuestion) {
		return findSharedContextForSourceQuestion(sourceQuestion, currentQuestions());
	}

	private SharedQuestionContext findSharedContextForSourceQuestion(SourceQuestion sourceQuestion,
			List<Question> questions) {
		SharedQuestionContext matchingContext = null;
		for (Question question : questions) {
			if (question.getBooklet().getId() != sourceQuestion.getBooklet().getId()) {
				continue;
			}
			if (!question.hasSourceQuestion()) {
				continue;
			}
			if (question.getSourceQuestion().getId() != sourceQuestion.getId()) {
				continue;
			}
			if (!question.hasSharedContext()) {
				continue;
			}
			SharedQuestionContext candidate = question.getSharedContext();
			if (matchingContext != null && matchingContext.getId() != candidate.getId()) {
				throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
						+ " has inconsistent shared context links");
			}
			matchingContext = candidate;
		}
		return matchingContext;
	}

	private String findSharedContextValidationError() {
		if (sharedContextCapturePane.isCaptureMode()) {
			return "Capture the shared context region and click Add Context before saving the question.";
		}
		String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
		ExamBooklet booklet = bookletSupplier.get();
		SourceQuestion sourceQuestion = null;
		if (booklet != null && sourceCode != null) {
			sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode).orElse(null);
		}
		SharedQuestionContext existingContext = null;
		if (sourceQuestion != null) {
			existingContext = findSharedContextForSourceQuestion(sourceQuestion);
		}
		boolean contextAvailable = sharedContextCapturePane.getSelectedContext() != null
				|| sharedContextCapturePane.hasPendingAutomaticRegion() || existingContext != null;
		boolean unresolvedSharedContext = (importedQuestion != null && importedQuestion.isSharedContextUnresolved())
				|| (editingQuestion != null && editingQuestion.isSharedContextUnresolved());
		return sharedContextValidationError(sourceCode, sourceQuestion, unresolvedSharedContext, contextAvailable);
	}

	private String findValidationError() {
		String validationError = findQuestionDetailsValidationError();
		if (validationError != null) {
			return validationError;
		}
		return findSharedContextValidationError();
	}

	private Runnable finishLegacyQuestionSplitState() {
		if (legacySplitCaptureState == null) {
			return null;
		}
		Runnable completedHandler = legacySplitCaptureState.completedHandler;
		legacySplitCaptureState = null;
		setCaptureModeControlsDisabled(false);
		importedQuestion = null;
		importedCaptureMode = false;
		editingQuestion = null;
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		showNewQuestionMode();
		resetQuestionEntry();
		refreshImportedQuestions();
		return completedHandler;
	}

	private void finishQuestionEdit() {
		finishQuestionEditState().run();
	}

	private Runnable finishQuestionEditState() {
		Runnable editCompletedHandler = questionEditCompletedHandler;
		questionEditCompletedHandler = () -> {
		};
		editingQuestion = null;
		importedCaptureMode = false;
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		showNewQuestionMode();
		resetQuestionEntry();
		return editCompletedHandler;
	}

	private void finishSharedContextRecapture(Runnable completedHandler) {

		// Hide the shared-context editor before returning the Question workspace to
		// its ordinary new-question state.
		setSharedContextCorrectionVisible(false);
		resetQuestionEntry();
		showNewQuestionMode();
		refreshImportedQuestions();
		completedHandler.run();
	}

	private void handleQuestionCodeChanged(String newCode) {
		if (legacySplitCaptureState != null) {

			// Split metadata was already explicitly confirmed. Updating the displayed
			// part must not invoke ordinary automatic multipart/context inference.
			refreshQuestionCodeStatus(newCode);
			refreshSaveButtonState();
			return;
		}
		if (editingQuestion != null && !loadingQuestionEdit && !editingSourceMatches(newCode)) {
			sharedContextCapturePane.selectContext(null);
		}

		// Resolve response type before shared-context controls because independent MCQ
		// continuation depends on the effective response type.
		applyAutomaticResponseType();
		refreshSharedContextControls();

		// Duplicate feedback is deliberately independent of classification and region
		// state, so it appears as soon as the Question number is recognisable.
		refreshQuestionCodeStatus(newCode);
		refreshSaveButtonState();
	}

	private void handleResponseTypeChanged() {
		updatingResponseTypeSelection = true;
		try {

			// Response type controls the editable marks state immediately.
			updateMarksFieldForResponseType();
		} finally {
			updatingResponseTypeSelection = false;
		}

		// The same compact checkbox represents different context semantics for
		// independent MCQs and multipart Written Response Questions.
		refreshSharedContextControls();
		refreshSaveButtonState();
	}

	private void handleSharedContextOptionChanged(boolean selected) {
		if (refreshingSharedContextControls) {
			return;
		}
		try {
			if (isNewIndependentMcqCapture()) {
				mcqContinuationSelected = selected;
				if (!selected) {
					if (mcqSharedContextCaptureActive) {

						// Unticking before Save abandons a newly staged MCQ context.
						sharedContextCapturePane.cancelAutomaticContext();
						mcqSharedContextCaptureActive = false;
						inheritedMcqSharedContext = null;
					}
					if (inheritedMcqSharedContext != null) {
						showSharedContextStatus("Using shared context from the previous question.");
					} else {
						hideSharedContextStatus();
					}
					updateQuestionCodeLock();
					return;
				}
				if (inheritedMcqSharedContext != null) {

					// This MCQ already inherits the context. Selecting the checkbox means
					// only that the same context should continue to one more MCQ.
					showSharedContextStatus(
							"Using shared context from the previous question — it will also continue to the following question.");
					updateQuestionCodeLock();
					return;
				}
				if (!beginAutomaticSharedContextCapture(newMcqSharedContextLabel())) {
					mcqContinuationSelected = false;
					setSharedContextCheckBoxSelected(false);
					return;
				}
				mcqSharedContextCaptureActive = true;
				showSharedContextStatus("Capture the shared context before capturing the question region.");
				updateQuestionCodeLock();
				return;
			}

			// Multipart Written Response continues to use the existing SourceQuestion
			// semantics. MCQ continuation state must never leak into that workflow.
			mcqContinuationSelected = false;
			inheritedMcqSharedContext = null;
			String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
			if (!selected) {
				sharedContextCapturePane.cancelAutomaticContext();
				hideSharedContextStatus();
				updateQuestionCodeLock();
				return;
			}
			if (sourceCode == null) {
				setSharedContextCheckBoxSelected(false);
				return;
			}

			// Retain the existing persisted-label convention for multipart Questions.
			String label = "Question " + sourceCode + " context";
			if (!beginAutomaticSharedContextCapture(label)) {
				setSharedContextCheckBoxSelected(false);
				return;
			}
			updateQuestionCodeLock();
		} finally {
			refreshSaveButtonState();
		}
	}

	private void hideCaptureHint() {
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
	}

	private void hideImportedClassification() {
		importedClassificationLabel.setText("");
		importedClassificationLabel.setVisible(false);
		importedClassificationLabel.setManaged(false);
	}

	private void hideSharedContextControls() {
		setSharedContextCheckBoxSelected(false);
		setSharedContextCheckBoxVisible(false);
		firstRegionSharedContextCheckBox.setDisable(false);
		hideSharedContextStatus();
	}

	private void hideSharedContextStatus() {
		sharedContextStatusLabel.setText("");
		sharedContextStatusLabel.setStyle("");
		sharedContextStatusLabel.setVisible(false);
		sharedContextStatusLabel.setManaged(false);
	}

	private boolean isNewIndependentMcqCapture() {
		return !importedCaptureMode && importedQuestion == null && editingQuestion == null
				&& legacySplitCaptureState == null && selectedResponseType() == QuestionResponseType.MULTIPLE_CHOICE
				&& SourceQuestionCodeParser.derive(questionCodeField.getText()) == null;
	}

	private void loadActiveLegacySplitPart() {
		LegacyQuestionSplitDialog.PartDefinition part = legacySplitCaptureState.activeDefinition();

		// Only ordinary Question regions are cleared between parts. Any newly captured
		// shared shared context remains staged until the final atomic split
		// transaction.
		pendingContentParts.clear();
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(0);
		questionCodeField.setText(part.questionCode());
		marksField.setText(Integer.toString(part.marks()));
		selectResponseType(part.responseType());
		curriculumSelectorPane.selectClassificationPath(part.classification());

		// Split metadata was explicitly confirmed in the dialog. Region capture must
		// not allow that metadata to drift while the parts are being staged.
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		setResponseTypeDisabled(true);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		curriculumSelectorPane.setDisable(true);
		hideImportedClassification();
		hideSharedContextControls();
		saveQuestionButton.setText(legacySplitCaptureState.isLastPart() ? "Save Split" : "Next Part");
		cancelQuestionEditButton.setVisible(true);
		cancelQuestionEditButton.setManaged(true);
		showCaptureHint("Splitting Question " + legacySplitCaptureState.definition.sourceQuestionCode()
				+ " — capture part " + (legacySplitCaptureState.activePartIndex + 1) + " of "
				+ legacySplitCaptureState.definition.parts().size() + " (" + part.questionCode() + ").");
		showQuestionPendingStatus();
		refreshSaveButtonState();
	}

	private void loadImportedQuestion(Question question) {
		Question previousQuestion = importedQuestion;
		if (question != previousQuestion && hasAcceptedRegions() && !questionTargetChangeAllowed.getAsBoolean()) {
			restoreImportedQuestionSelection(previousQuestion);
			return;
		}
		if (question != null && !importedQuestionActivationHandler.test(question)) {
			restoreImportedQuestionSelection(previousQuestion);
			return;
		}
		clearRegions();
		importedQuestion = question;
		sharedContextCapturePane.refreshForCurrentBooklet();
		if (question == null) {
			resetQuestionEntry();
			if (importedCaptureMode) {
				showImportedQueueMode();
			} else {
				showNewQuestionMode();
			}
			refreshSaveButtonState();
			return;
		}
		showImportedQuestionMode(question);
	}

	private void loadQuestionEditFields(Question question) {
		loadingQuestionEdit = true;
		try {
			questionCodeField.setText(question.getQuestionCode());
			loadResponseType(question);
			marksField.setText(Integer.toString(question.getMarks()));
			curriculumSelectorPane.selectClassificationPath(question.getClassification());
			if (question.hasSharedContext()) {
				sharedContextCapturePane.selectContext(question.getSharedContext());
			}
		} finally {
			loadingQuestionEdit = false;
		}
	}

	private void loadResponseType(Question question) {

		// Restore the persisted response type into the capture control.
		selectResponseType(question.getResponseType());
	}

	private void moveContentPart(int contentIndex, int offset) {
		int targetIndex = contentIndex + offset;
		if (contentIndex < 0 || contentIndex >= pendingContentParts.size() || targetIndex < 0
				|| targetIndex >= pendingContentParts.size()) {
			return;
		}

		// Reordering the transient list directly changes the authoritative assembly
		// order that will later be persisted.
		Collections.swap(pendingContentParts, contentIndex, targetIndex);
		refreshRegionPreviews();
		showQuestionPendingStatus();
		refreshSaveButtonState();
	}

	private String newMcqSharedContextLabel() {

		// The label is internal persistence metadata. Teachers should not need to name
		// routine MCQ stimulus/context during capture.
		return "CTX-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private void pasteClipboardImage() {
		if (bookletSupplier.get() == null) {
			showAlert(Alert.AlertType.WARNING, "Exam details have not been set.",
					"Enter the exam and booklet details before pasting Question content.");
			return;
		}
		if (sharedContextCapturePane.isCaptureMode()) {
			showAlert(Alert.AlertType.INFORMATION, "Shared context capture is active.",
					"Finish or cancel shared context capture before pasting Question content.");
			return;
		}
		if (currentSelection != null) {
			showAlert(Alert.AlertType.INFORMATION, "PDF selection pending.",
					"Add or clear the current PDF selection before pasting an image.");
			return;
		}
		if (legacySplitCaptureState != null) {

			// Legacy split persistence intentionally remains PDF-region-only. Clipboard
			// images stay outside this workflow until mixed split capture is explicitly
			// implemented.
			showAlert(Alert.AlertType.INFORMATION, "Clipboard images are unavailable during legacy split capture.",
					"Complete this legacy split using PDF regions.");
			return;
		}
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()) {
			showAlert(Alert.AlertType.INFORMATION, "Question content already captured.",
					"This imported Question already has body content.");
			return;
		}
		try {
			byte[] pngBytes = clipboardImageReader.readPng().orElse(null);
			if (pngBytes == null) {
				showAlert(Alert.AlertType.INFORMATION, "No image on clipboard.",
						"Copy or snip an image, then click Paste Image again.");
				return;
			}

			// Insert at the end of the current assembly. Move controls can subsequently
			// place the image anywhere within the Question body.
			pendingContentParts.add(new ImageQuestionContentPart(pngBytes));
			refreshRegionPreviews();
			setRegionCountLabel(pendingContentParts.size());
			showQuestionPendingStatus();
			updateQuestionCodeLock();
			refreshSaveButtonState();
		} catch (IOException exception) {
			showAlert(Alert.AlertType.ERROR, "Clipboard image could not be added.",
					"The clipboard image could not be converted to PNG.");
		}
	}

	private List<QuestionRegion> pendingQuestionRegions() {
		List<QuestionRegion> regions = new ArrayList<>();

		// Legacy split capture still persists only PDF-backed regions. Ordinary capture
		// uses the complete mixed-content list.
		for (QuestionContentPart part : pendingContentParts) {
			if (part instanceof PdfQuestionContentPart pdfPart) {
				regions.add(pdfPart.region());
			}
		}
		return List.copyOf(regions);
	}

	private SqliteQuestionCaptureService.PendingSharedContext pendingSharedContextForSave() {
		if (!sharedContextCapturePane.hasPendingAutomaticRegion()) {
			return null;
		}
		return new SqliteQuestionCaptureService.PendingSharedContext(
				sharedContextCapturePane.getPendingAutomaticContextLabel(),
				sharedContextCapturePane.getPendingAutomaticContextRegions());
	}

	private void persistLegacyQuestionSplit(SplitRequest request) {
		questionSaveInProgress = true;
		setDisable(true);
		saveStatusLabel.setText("Saving split Question " + request.sourceQuestionCode() + "...");
		Task<LegacySplitSaveResult> saveTask = new Task<>() {

			@Override
			protected LegacySplitSaveResult call() {
				return persistLegacyQuestionSplitTransaction(request);
			}
		};
		saveTask.setOnSucceeded(_ -> completeLegacyQuestionSplit(saveTask.getValue()));
		saveTask.setOnFailed(_ -> {
			questionSaveInProgress = false;
			setDisable(false);
			saveStatusLabel.setText("Split save failed — staged parts retained");
			showAlert(Alert.AlertType.ERROR, "Question split could not be saved.",
					"The original Question remains unchanged. " + "The staged split regions have been retained.");
			refreshSaveButtonState();
		});
		Thread saveThread = new Thread(saveTask, "legacy-question-split-save");
		saveThread.setDaemon(true);
		saveThread.start();
	}

	private LegacySplitSaveResult persistLegacyQuestionSplitTransaction(SplitRequest request) {
		List<Question> beforeSplit = questionRepository.findAll();
		SplitResult splitResult = legacyQuestionSplitService.split(request);
		try {
			return new LegacySplitSaveResult(splitResult, questionRepository.findAll(), null);
		} catch (RuntimeException refreshFailure) {

			// The split transaction has committed. Reconstruct a safe list from the
			// pre-save snapshot and committed split result rather than reporting the
			// persistence itself as failed.
			List<Question> fallback = new ArrayList<>(beforeSplit);
			fallback.removeIf(question -> question.getId() == request.originalQuestion().getId());
			fallback.addAll(splitResult.questions());
			return new LegacySplitSaveResult(splitResult, List.copyOf(fallback), refreshFailure);
		}
	}

	// Run validation, persistence and list reconstruction on the save task, away
	// from the FX thread.
	private QuestionSaveResult persistQuestionCapture(SqliteQuestionCaptureService.Request request) {
		List<Question> beforeSave = questionRepository.findAll();
		String validationError = validateStoredQuestion(request, beforeSave);
		if (validationError != null) {
			return new QuestionSaveResult(null, beforeSave, validationError, null);
		}
		Question saved = questionCaptureService.save(request);
		try {
			return new QuestionSaveResult(saved, questionRepository.findAll(), null, null);
		} catch (RuntimeException refreshFailure) {

			// The transaction has committed. Never report a refresh failure as a failed
			// save.
			List<Question> fallback = new ArrayList<>(beforeSave);
			fallback.removeIf(question -> question.getId() == saved.getId());
			fallback.add(saved);
			return new QuestionSaveResult(saved, List.copyOf(fallback), null, refreshFailure);
		}
	}

	private void reconcileKnownSharedContexts() {

		// Load one consistent Question snapshot for the complete reconciliation pass.
		// Reusing it avoids re-reading the entire corpus for every source question.
		List<Question> questions = currentQuestions();
		List<Long> processedSourceQuestionIds = new ArrayList<>();
		for (Question question : questions) {
			if (!question.hasSourceQuestion() || !question.hasSharedContext()) {
				continue;
			}
			SourceQuestion sourceQuestion = question.getSourceQuestion();
			if (processedSourceQuestionIds.contains(sourceQuestion.getId())) {
				continue;
			}

			// Search the already-loaded snapshot rather than calling currentQuestions()
			// again for each distinct source question.
			SharedQuestionContext sharedContext = findSharedContextForSourceQuestion(sourceQuestion, questions);
			if (sharedContext != null) {
				questionRepository.applySharedContextToSourceQuestion(sourceQuestion, sharedContext);
			}
			processedSourceQuestionIds.add(sourceQuestion.getId());
		}
	}

	private void refreshImportedQuestions(List<Question> questions) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}

		// Retain the complete refreshed corpus for synchronous duplicate-code
		// validation while the teacher types.
		questionSnapshot = List.copyOf(questions);
		Question selected = importedQuestion;

		// Build the visible imported-question queue from the transient Working Subject
		// without changing any persisted Question data.
		List<Question> awaitingCapture = awaitingCaptureForWorkingSubject(questionSnapshot, workingSubject);
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getItems().setAll(awaitingCapture);
			Question matchingSelection = null;
			if (selected != null) {
				for (Question question : awaitingCapture) {

					// Preserve the current imported Question only when it still belongs to
					// the filtered queue.
					if (question.getId() == selected.getId()) {
						matchingSelection = question;
						break;
					}
				}
			}
			importedQuestionBox.setValue(matchingSelection);
			importedQuestion = matchingSelection;
		} finally {
			refreshingImportedQuestions = false;
		}
	}

	private void refreshQuestionCodeStatus(String questionCode) {
		Question duplicate = findDuplicateQuestion(questionCode);
		if (duplicate == null) {
			questionCodeStatusLabel.setText("");
			questionCodeStatusLabel.setVisible(false);
			questionCodeStatusLabel.setManaged(false);
			return;
		}
		questionCodeStatusLabel
				.setText("Question " + duplicate.getQuestionCode() + " has already been captured in this booklet.");
		questionCodeStatusLabel.setVisible(true);
		questionCodeStatusLabel.setManaged(true);
	}

	private void refreshRegionPreviews() {
		regionPreviewBox.getChildren().clear();
		for (int index = 0; index < pendingContentParts.size(); index++) {
			addContentPreview(pendingContentParts.get(index), index);
		}
		updateRegionsScrollPane();
	}

	private void refreshSaveButtonState() {

		// A selection is compatible with these controls only when it belongs to the
		// currently active Question or automatic shared-context capture workflow.
		boolean compatibleSelectionPending = sharedContextCapturePane.isCaptureMode()
				? sharedContextCapturePane.hasCurrentSelection()
				: currentSelection != null;
		boolean selectionActionsEnabled = !questionSaveInProgress && compatibleSelectionPending;
		addRegionButton.setDisable(!selectionActionsEnabled);
		removeCurrentSelectionButton.setDisable(!selectionActionsEnabled);
		boolean importedContentAlreadyStored = importedQuestion != null
				&& !importedQuestion.getContentParts().isEmpty();

		// Pasting is ordinary Question-body capture. Do not permit it while a PDF
		// rectangle is unresolved, during shared-context capture, or during the
		// still-PDF-only legacy split workflow.
		boolean pasteAllowed = !questionSaveInProgress && currentSelection == null
				&& !sharedContextCapturePane.isCaptureMode() && legacySplitCaptureState == null
				&& !importedContentAlreadyStored;
		pasteImageButton.setDisable(!pasteAllowed);
		clearRegionsButton.setDisable(questionSaveInProgress || pendingContentParts.isEmpty());
		if (questionSaveInProgress) {
			saveQuestionButton.setDisable(true);
			return;
		}
		addRegionButton.setText(sharedContextCapturePane.isCaptureMode() ? "Add Context" : "Add Region");
		boolean ready;
		try {
			ready = findValidationError() == null;
		} catch (IllegalStateException exception) {

			// Inconsistent persisted relationships must never make Save available.
			ready = false;
		}
		saveQuestionButton.setDisable(!ready);
	}

	private void refreshSharedContextControls() {
		if (isNewIndependentMcqCapture()) {

			// Preserve a context currently being captured for this MCQ. Any older
			// automatic multipart capture is no longer compatible with MCQ mode.
			if (!mcqSharedContextCaptureActive && sharedContextCapturePane.hasUnsavedContextCapture()) {
				sharedContextCapturePane.cancelAutomaticContext();
			}
			showMcqSharedContextOption();
			return;
		}
		if (mcqSharedContextCaptureActive) {
			sharedContextCapturePane.cancelAutomaticContext();
		}
		mcqSharedContextCaptureActive = false;
		mcqContinuationSelected = false;
		inheritedMcqSharedContext = null;

		// Existing multipart and imported workflows retain their SourceQuestion-based
		// context rules.
		sharedContextCapturePane.cancelAutomaticContext();
		String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
		if (sourceCode == null) {
			if (importedQuestion != null && importedQuestion.isSharedContextUnresolved()) {
				hideSharedContextControls();
				boolean started = sharedContextCapturePane
						.beginAutomaticContext("Question " + importedQuestion.getQuestionCode() + " context");
				if (started) {
					showSharedContextRequiredStatus("Shared context required — capture it as the first region.");
				}
				return;
			}
			hideSharedContextControls();
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			hideSharedContextControls();
			return;
		}
		if (showImportedSharedContext(sourceCode)) {
			return;
		}
		SourceQuestion sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode).orElse(null);
		if (sourceQuestion != null && showStoredSharedContext(sourceCode, sourceQuestion)) {
			return;
		}
		boolean required = importedQuestion != null && importedQuestion.isSharedContextUnresolved();
		showSharedContextOption(sourceCode, required);
	}

	private void removeContentPart(int contentIndex) {
		if (contentIndex < 0 || contentIndex >= pendingContentParts.size()) {
			return;
		}
		pendingContentParts.remove(contentIndex);
		refreshRegionPreviews();
		setRegionCountLabel(pendingContentParts.size());
		showQuestionPendingStatus();
		updateQuestionCodeLock();
		refreshSaveButtonState();
	}

	private void resetAfterQuestionSave(int previousImportedIndex) {
		importedQuestion = null;
		resetQuestionEntry();
		refreshImportedQuestions();
		if (previousImportedIndex < 0) {
			showNewQuestionMode();
			return;
		}
		if (importedQuestionBox.getItems().isEmpty()) {
			showImportedQueueMode();
			return;
		}
		int nextIndex = Math.min(previousImportedIndex, importedQuestionBox.getItems().size() - 1);
		Question nextQuestion = importedQuestionBox.getItems().get(nextIndex);
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getSelectionModel().select(nextIndex);
		} finally {
			refreshingImportedQuestions = false;
		}
		loadImportedQuestion(nextQuestion);
	}

	private void resetQuestionEntry() {
		mcqSharedContextCaptureActive = false;
		mcqContinuationSelected = false;
		inheritedMcqSharedContext = null;
		sharedContextCapturePane.clearForQuestion();
		hideSharedContextControls();
		questionCodeField.clear();
		questionCodeField.setDisable(false);
		marksField.clear();

		// Every new Question starts with fresh automatic/default state rather than
		// inheriting a manual response-type or checkbox choice from the preceding
		// Question. Persisted MCQ continuation is reloaded separately below.
		responseTypeManuallySelected = false;
		selectResponseType(null);
		setResponseTypeDisabled(false);
		applyAutomaticResponseType();
		refreshSharedContextControls();
		clearRegions();
		curriculumSelectorPane.clearClassificationBelowSubject();
		refreshSaveButtonState();
	}

	private void restoreCaptureModeToggle() {
		if (editingQuestion != null) {
			captureModeGroup.selectToggle(null);
			return;
		}
		selectCaptureModeToggle(importedCaptureMode);
	}

	private void restoreImportedQuestionSelection(Question question) {
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(question);
		} finally {
			refreshingImportedQuestions = false;
		}
	}

	private void saveQuestion() {
		int previousImportedIndex = -1;
		boolean hadStoredContent = false;
		Question existingQuestion = null;
		boolean editing = editingQuestion != null;
		boolean imported = importedQuestion != null;
		if (editing) {
			existingQuestion = editingQuestion;
		} else if (imported) {
			existingQuestion = importedQuestion;
			previousImportedIndex = selectedImportedQuestionIndex();
			hadStoredContent = !importedQuestion.getContentParts().isEmpty();
		}
		SqliteQuestionCaptureService.Request request = SqliteQuestionCaptureService.Request.withContent(
				captureOperation(), bookletSupplier.get(), existingQuestion, questionCodeField.getText().trim(),
				Integer.parseInt(marksField.getText().trim()), List.copyOf(pendingContentParts),
				curriculumSelectionModel.getClassification(), selectedResponseType(),
				sharedContextCapturePane.getSelectedContext(), pendingSharedContextForSave(),
				continueSharedContextToNextMcq());
		int savedPreviousImportedIndex = previousImportedIndex;
		boolean savedHadStoredContent = hadStoredContent;
		questionSaveInProgress = true;
		setDisable(true);
		saveStatusLabel.setText("Saving " + request.questionCode() + "...");
		Task<QuestionSaveResult> saveTask = createTaskList(request);
		saveTask.setOnSucceeded(_ -> {
			QuestionSaveResult result = saveTask.getValue();
			completeQuestionSave(result, editing, imported, savedPreviousImportedIndex, savedHadStoredContent);
		});
		saveTask.setOnFailed(_ -> {
			questionSaveInProgress = false;
			setDisable(false);
			saveStatusLabel.setText("Save failed — current question retained");
			showAlert(Alert.AlertType.ERROR, "Question could not be saved.", "The question was not saved. "
					+ "Your current question details and accepted content " + "have been retained.");
		});
		Thread saveThread = new Thread(saveTask, "question-save");
		saveThread.setDaemon(true);
		saveThread.start();
	}

	private void selectCaptureModeToggle(boolean imported) {
		if (imported) {
			importedQuestionsModeButton.setSelected(true);
		} else {
			newQuestionsModeButton.setSelected(true);
		}
	}

	private int selectedImportedQuestionIndex() {
		int selectedIndex = importedQuestionBox.getSelectionModel().getSelectedIndex();
		return selectedIndex < 0 ? 0 : selectedIndex;
	}

	private QuestionResponseType selectedResponseType() {

		// No selected radio button represents an unresolved response type.
		if (responseTypeGroup.getSelectedToggle() == null) {
			return null;
		}
		return (QuestionResponseType) responseTypeGroup.getSelectedToggle().getUserData();
	}

	private void selectResponseType(QuestionResponseType responseType) {

		// UNKNOWN is represented by having neither response-type button selected.
		if (responseType == null || responseType == QuestionResponseType.UNKNOWN) {
			responseTypeGroup.selectToggle(null);
			return;
		}
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
			responseTypeGroup.selectToggle(multipleChoiceResponseButton);
			return;
		}
		responseTypeGroup.selectToggle(writtenResponseButton);
	}

	private void setCaptureModeControlsDisabled(boolean disabled) {

		// A split is one correction workflow. New/imported capture cannot be entered
		// part-way through it.
		newQuestionsModeButton.setDisable(disabled);
		importedQuestionsModeButton.setDisable(disabled);
	}

	private void setLegacyCaptureControlsVisible(boolean visible) {
		legacyCaptureBox.setVisible(visible);
		legacyCaptureBox.setManaged(visible);
	}

	private void setRegionCountLabel(int count) {
		regionCountLabel.setText(String.format("Content parts: %d", count));
	}

	private void setResponseTypeDisabled(boolean disabled) {

		// Keep enablement independent of the concrete response-type controls.
		multipleChoiceResponseButton.setDisable(disabled);
		writtenResponseButton.setDisable(disabled);
	}

	private void setSharedContextCheckBoxSelected(boolean selected) {
		refreshingSharedContextControls = true;
		try {
			firstRegionSharedContextCheckBox.setSelected(selected);
		} finally {
			refreshingSharedContextControls = false;
		}
	}

	private void setSharedContextCheckBoxVisible(boolean visible) {
		firstRegionSharedContextCheckBox.setVisible(visible);
		firstRegionSharedContextCheckBox.setManaged(visible);
	}

	private void setSharedContextCorrectionVisible(boolean visible) {
		sharedContextCapturePane.setVisible(visible);
		sharedContextCapturePane.setManaged(visible);

		// During shared context correction, ordinary Question controls must not trigger
		// listeners that could cancel or otherwise alter the replacement capture.
		for (Node child : getChildren()) {
			if (child != sharedContextCapturePane) {
				child.setDisable(visible);
			}
		}
	}

	private String sharedContextValidationError(String sourceCode, SourceQuestion sourceQuestion,
			boolean unresolvedSharedContext, boolean contextAvailable) {
		if (unresolvedSharedContext && !contextAvailable) {
			return "This imported question requires shared context. Capture the context as the first region.";
		}
		if (sourceQuestion != null && sourceQuestion.getSharedContextStatus() == SharedContextStatus.PRESENT
				&& !contextAvailable) {
			return "Question " + sourceCode + " is recorded as having shared context, "
					+ "but its shared context could not be found.";
		}
		return null;
	}

	private boolean shouldInferWrittenResponse() {

		// A conservative part-letter code such as 21a identifies Written Response.
		if (SourceQuestionCodeParser.derive(questionCodeField.getText()) != null) {
			return true;
		}
		String marksText = marksField.getText().trim();
		if (marksText.isEmpty()) {
			return false;
		}
		try {

			// MCQs are always one mark, so more than one mark safely identifies Written
			// Response without making the inverse assumption for a one-mark Question.
			return Integer.parseInt(marksText) > 1;
		} catch (NumberFormatException exception) {

			// Invalid partial input remains unresolved and is handled by normal form
			// validation rather than response-type inference.
			return false;
		}
	}

	private void showAlert(Alert.AlertType type, String header, String message) {
		Alert alert = new Alert(type);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showCaptureHint(String text) {
		captureHintLabel.setText(text);
		captureHintLabel.setVisible(true);
		captureHintLabel.setManaged(true);
	}

	private void showFirstQuestionRegionPage(Question question) {
		if (question.getRegions().isEmpty()) {
			return;
		}

		// Persisted Question regions are ordered, so the first region identifies the
		// most useful source page when entering edit or recapture.
		examPageNavigationHandler.accept(question.getRegions().getFirst().pageNumber());
	}

	private void showFirstSharedContextRegionPage(SharedQuestionContext context) {
		if (context.getRegions().isEmpty()) {
			return;
		}

		// Shared-context regions are persisted in source order, so start correction
		// on the page containing the first stored shared context region.
		examPageNavigationHandler.accept(context.getRegions().getFirst().pageNumber());
	}

	private void showImportedClassification(Question question) {
		importedClassificationLabel.setText("Imported classification: " + question.getClassification().getCode() + " — "
				+ question.getClassification().getName());
		importedClassificationLabel.setVisible(true);
		importedClassificationLabel.setManaged(true);
	}

	private void showImportedQuestionMode(Question question) {
		questionCodeField.setText(question.getQuestionCode());
		marksField.setText(Integer.toString(question.getMarks()));
		if (question.hasSharedContext()) {
			sharedContextCapturePane.selectContext(question.getSharedContext());
		}
		curriculumSelectorPane.selectClassificationPath(question.getClassification());
		curriculumSelectorPane.setDisable(false);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
		showImportedClassification(question);
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		loadResponseType(question);
		setResponseTypeDisabled(false);
		refreshSharedContextControls();
		if (question.getContentParts().isEmpty()) {
			saveQuestionButton.setText("Save Question");
			if (question.isSharedContextUnresolved()) {
				hideCaptureHint();
			} else {
				showCaptureHint("Capture or paste the question content.");
			}
		} else {
			saveQuestionButton.setText("Save Resolution");
			hideCaptureHint();
		}
		showQuestionPendingStatus();
		refreshSaveButtonState();
	}

	private void showImportedQueueMode() {
		questionCodeField.setDisable(true);
		marksField.setDisable(true);

		// Imported queue mode has no editable response type until a question is
		// selected.
		selectResponseType(null);
		setResponseTypeDisabled(true);
		curriculumSelectorPane.setSyllabusContextLocked(false);
		curriculumSelectorPane.setDisable(true);
		hideImportedClassification();
		hideSharedContextControls();
		saveQuestionButton.setText("Save Resolution");
		saveQuestionButton.setDisable(true);
		if (importedQuestionBox.getItems().isEmpty()) {
			showCaptureHint("No imported questions are awaiting capture or resolution.");
		} else {
			showCaptureHint("Select an imported question awaiting capture or resolution.");
		}
	}

	private boolean showImportedSharedContext(String sourceCode) {
		if (importedQuestion == null || !importedQuestion.hasSharedContext()) {
			return false;
		}
		sharedContextCapturePane.selectContext(importedQuestion.getSharedContext());
		hideSharedContextControls();
		showSharedContextStatus("Shared context: Question " + sourceCode);
		return true;
	}

	private void showMcqSharedContextOption() {
		firstRegionSharedContextCheckBox.setText("Shared context with next question");
		firstRegionSharedContextCheckBox
				.setTooltip(new Tooltip("Select when this MCQ and the following MCQ use the same source context."));
		firstRegionSharedContextCheckBox.setDisable(false);
		setSharedContextCheckBoxVisible(true);
		if (mcqSharedContextCaptureActive) {
			inheritedMcqSharedContext = null;
			setSharedContextCheckBoxSelected(mcqContinuationSelected);
			if (sharedContextCapturePane.isCaptureMode()) {
				showSharedContextStatus("Capture the shared context before capturing the question region.");
			} else {
				showSharedContextStatus("Shared context captured — it will also be used by the next question.");
			}
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		String questionCode = questionCodeField.getText().trim();
		inheritedMcqSharedContext = booklet == null || questionCode.isBlank() ? null
				: questionCaptureService.findPendingMcqSharedContextForQuestion(booklet, questionCode).orElse(null);
		if (inheritedMcqSharedContext != null) {

			// Only the immediate sequence successor inherits automatically. The checkbox
			// now means "continue this same context one Question further".
			setSharedContextCheckBoxSelected(mcqContinuationSelected);
			if (mcqContinuationSelected) {
				showSharedContextStatus(
						"Using shared context from the previous question — it will also continue to the following question.");
			} else {
				showSharedContextStatus("Using shared context from the previous question.");
			}
			return;
		}
		mcqContinuationSelected = false;
		setSharedContextCheckBoxSelected(false);
		hideSharedContextStatus();
	}

	private void showNewQuestionMode() {
		questionCodeField.setDisable(false);

		// Reapply the active booklet's response-type policy whenever ordinary new
		// Question capture becomes visible. Single-format booklets remain locked;
		// Mixed booklets retain editable response-type controls.
		applyAutomaticResponseType();
		curriculumSelectorPane.setDisable(false);
		curriculumSelectorPane.setSyllabusContextLocked(false);
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
		hideImportedClassification();
		saveQuestionButton.setText("Save Question");
		hideCaptureHint();
	}

	private void showQuestionPendingStatus() {
		String questionCode = questionCodeField.getText().trim();
		String prefix = questionCode.isBlank() ? "Question pending" : "Pending " + questionCode;
		saveStatusLabel.setText(String.format("%s — %d content part(s) accepted", prefix, pendingContentParts.size()));
	}

	private void showSavedQuestionStatus(String action, Question question) {
		saveStatusLabel.setText(String.format("%s %s (%d mark(s), %d content part(s))", action,
				question.getQuestionCode(), question.getMarks(), question.getContentParts().size()));
	}

	private void showSelectedImportedQuestionDocument() {
		Question question = importedQuestionBox.getValue();
		if (question == null) {
			return;
		}
		importedQuestionActivationHandler.test(question);
	}

	private void showSharedContextCorrectionQuestion(Question question) {

		// Display the Question that led to this shared-context correction without
		// turning the operation into an ordinary Question edit.
		loadingQuestionEdit = true;
		try {
			questionCodeField.setText(question.getQuestionCode());
			marksField.setText(Integer.toString(question.getMarks()));
			loadResponseType(question);
			curriculumSelectorPane.selectClassificationPath(question.getClassification());
		} finally {
			loadingQuestionEdit = false;
		}

		// These values identify the Question using the shared context. They are
		// contextual information only and cannot be changed by this workflow.
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		setResponseTypeDisabled(true);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		curriculumSelectorPane.setDisable(true);
		hideImportedClassification();
		hideSharedContextControls();
		saveStatusLabel.setText("Recapturing shared context used by Question " + question.getQuestionCode());
	}

	private void showSharedContextOption(String sourceCode, boolean required) {
		firstRegionSharedContextCheckBox.setText("First region is shared context");
		firstRegionSharedContextCheckBox.setTooltip(
				new Tooltip("Store the first captured region as shared context for all parts of this question."));
		setSharedContextCheckBoxVisible(true);
		firstRegionSharedContextCheckBox.setDisable(required);
		setSharedContextCheckBoxSelected(required);
		if (!required) {
			hideSharedContextStatus();
			return;
		}
		boolean started = sharedContextCapturePane.beginAutomaticContext("Question " + sourceCode + " context");
		if (!started) {
			setSharedContextCheckBoxSelected(false);
			return;
		}
		showSharedContextRequiredStatus("Shared context required — capture it as the first region.");
	}

	private void showSharedContextRequiredStatus(String text) {
		sharedContextStatusLabel.setStyle(REQUIRED_STATUS_STYLE);
		sharedContextStatusLabel.setText(text);
		sharedContextStatusLabel.setVisible(true);
		sharedContextStatusLabel.setManaged(true);
	}

	private void showSharedContextStatus(String text) {
		sharedContextStatusLabel.setStyle("");
		sharedContextStatusLabel.setText(text);
		sharedContextStatusLabel.setVisible(true);
		sharedContextStatusLabel.setManaged(true);
	}

	private boolean showStoredSharedContext(String sourceCode, SourceQuestion sourceQuestion) {
		SharedQuestionContext existingContext = findSharedContextForSourceQuestion(sourceQuestion);
		if (existingContext != null) {
			sharedContextCapturePane.selectContext(existingContext);
			hideSharedContextControls();
			showSharedContextStatus("Shared context: Question " + sourceCode);
			return true;
		}
		if (sourceQuestion.getSharedContextStatus() == SharedContextStatus.NONE) {
			hideSharedContextControls();
			return true;
		}
		if (sourceQuestion.getSharedContextStatus() == SharedContextStatus.PRESENT) {
			hideSharedContextControls();
			showSharedContextStatus("Shared context for Question " + sourceCode + " could not be found.");
			return true;
		}
		return false;
	}

	private void updateMarksFieldForResponseType() {
		QuestionResponseType responseType = selectedResponseType();
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE && importedQuestion == null
				&& legacySplitCaptureState == null) {

			// Every MCQ is one mark. Replace any previous Written Response mark value as
			// soon as MCQ is selected and prevent inconsistent manual editing.
			if (!"1".equals(marksField.getText().trim())) {
				marksField.setText("1");
			}
			marksField.setDisable(true);
			return;
		}
		if (!importedCaptureMode && importedQuestion == null && legacySplitCaptureState == null) {

			// Written Response and unresolved new/edit Questions allow marks to be
			// entered normally.
			marksField.setDisable(false);
		}
	}

	private void updateQuestionCodeLock() {
		if (legacySplitCaptureState != null) {

			// Split metadata is fixed by the definition dialog.
			questionCodeField.setDisable(true);
			return;
		}
		if (importedQuestion != null) {
			questionCodeField.setDisable(true);
			return;
		}

		// Ordinary accepted question regions do not lock the question number. The user
		// may still correct or enter metadata before Save.
		//
		// A captured shared context does lock the number because changing the source
		// question would change ownership of that shared context.
		questionCodeField.setDisable(sharedContextCapturePane.hasPendingAutomaticRegion());
	}

	private void updateRegionsScrollPane() {
		boolean hasContent = !pendingContentParts.isEmpty();
		regionsScrollPane.setVisible(hasContent);
		regionsScrollPane.setManaged(hasContent);
	}

	private void validateDependencies(QuestionRepository questionRepository,
			SourceQuestionRepository sourceQuestionRepository, SqliteQuestionCaptureService questionCaptureService,
			LegacyQuestionSplitService legacyQuestionSplitService, SharedContextCapturePane sharedContextCapturePane,
			QuestionExtractor questionExtractor, CurriculumSelectionModel curriculumSelectionModel,
			CurriculumSelectorPane curriculumSelectorPane, Supplier<ExamBooklet> bookletSupplier,
			Supplier<PdfSession> examPdfSessionSupplier, Predicate<Question> importedQuestionActivationHandler,
			IntConsumer examPageNavigationHandler, BooleanSupplier questionTargetChangeAllowed,
			BooleanSupplier questionSelectionTransferHandler, Runnable selectionClearHandler,
			Consumer<List<Question>> questionsChangedHandler) {
		if (questionRepository == null) {
			throw new NullPointerException("questionRepository");
		}
		if (sourceQuestionRepository == null) {
			throw new NullPointerException("sourceQuestionRepository");
		}
		if (questionCaptureService == null) {
			throw new NullPointerException("questionCaptureService");
		}
		if (legacyQuestionSplitService == null) {
			throw new NullPointerException("legacyQuestionSplitService");
		}
		if (sharedContextCapturePane == null) {
			throw new NullPointerException("sharedContextCapturePane");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		if (curriculumSelectionModel == null) {
			throw new NullPointerException("curriculumSelectionModel");
		}
		if (curriculumSelectorPane == null) {
			throw new NullPointerException("curriculumSelectorPane");
		}
		if (bookletSupplier == null) {
			throw new NullPointerException("bookletSupplier");
		}
		if (examPdfSessionSupplier == null) {
			throw new NullPointerException("examPdfSessionSupplier");
		}
		if (importedQuestionActivationHandler == null) {
			throw new NullPointerException("importedQuestionActivationHandler");
		}
		if (examPageNavigationHandler == null) {
			throw new NullPointerException("examPageNavigationHandler");
		}
		if (questionTargetChangeAllowed == null) {
			throw new NullPointerException("questionTargetChangeAllowed");
		}
		if (questionSelectionTransferHandler == null) {
			throw new NullPointerException("questionSelectionTransferHandler");
		}
		if (selectionClearHandler == null) {
			throw new NullPointerException("selectionClearHandler");
		}
		if (questionsChangedHandler == null) {
			throw new NullPointerException("questionsChangedHandler");
		}
	}

	private void validateQuestionForSave() {
		if (questionSaveInProgress) {
			return;
		}
		String validationError = findQuestionDetailsValidationError();
		if (validationError == null && sharedContextCapturePane.isCaptureMode()) {
			validationError = "Capture the shared context region and click Add Context before continuing.";
		}
		if (validationError == null && legacySplitCaptureState != null && legacySplitCaptureState.definition
				.sharedContextChoice() == LegacyQuestionSplitDialog.SharedContextChoice.CAPTURE_NEW_SHARED_CONTEXT
				&& !sharedContextCapturePane.hasPendingAutomaticRegion()) {
			validationError = "Capture the shared context before continuing with the split.";
		}
		if (validationError != null) {
			showAlert(Alert.AlertType.WARNING, "Question is incomplete.", validationError);
			return;
		}
		if (legacySplitCaptureState != null) {
			advanceLegacyQuestionSplit();
			return;
		}

		// Booklet-format resolution now occurs when a known booklet is opened, so Save
		// deals only with Question-level validation and persistence.
		saveQuestion();
	}

	private String validateStoredQuestion(SqliteQuestionCaptureService.Request request, List<Question> questions) {
		if (request.operation() != SqliteQuestionCaptureService.Operation.IMPORTED) {
			for (Question question : questions) {
				if (question.getBooklet().getId() == request.booklet().getId()
						&& question.getQuestionCode().equals(request.questionCode())
						&& (request.existingQuestion() == null
								|| question.getId() != request.existingQuestion().getId())) {
					return "Question " + request.questionCode() + " already exists for this booklet.";
				}
			}
		}
		String sourceCode = SourceQuestionCodeParser.derive(request.questionCode());
		SourceQuestion source = sourceCode == null ? null
				: sourceQuestionRepository.findByBookletAndCode(request.booklet(), sourceCode).orElse(null);
		SharedQuestionContext storedContext = source == null ? null
				: findSharedContextForSourceQuestion(source, questions);
		boolean contextAvailable = request.selectedSharedContext() != null || request.pendingSharedContext() != null
				|| storedContext != null;
		boolean unresolved = request.existingQuestion() != null
				&& request.existingQuestion().isSharedContextUnresolved();
		return sharedContextValidationError(sourceCode, source, unresolved, contextAvailable);
	}

	private static final class LegacySplitCaptureState {

		private final Question originalQuestion;
		private final LegacyQuestionSplitDialog.Result definition;
		private final List<SplitPart> completedParts = new ArrayList<>();
		private final Runnable completedHandler;
		private int activePartIndex;

		private LegacySplitCaptureState(Question originalQuestion, LegacyQuestionSplitDialog.Result definition,
				Runnable completedHandler) {
			this.originalQuestion = originalQuestion;
			this.definition = definition;
			this.completedHandler = completedHandler;
		}

		private LegacyQuestionSplitDialog.PartDefinition activeDefinition() {
			return definition.parts().get(activePartIndex);
		}

		private boolean isLastPart() {
			return activePartIndex == definition.parts().size() - 1;
		}
	}

	private record LegacySplitSaveResult(SplitResult splitResult, List<Question> questions,
			RuntimeException refreshFailure) {

		private LegacySplitSaveResult {
			questions = List.copyOf(questions);
		}
	}

	private record QuestionSaveResult(Question question, List<Question> questions, String validationError,
			RuntimeException refreshFailure) {

		private QuestionSaveResult {
			questions = List.copyOf(questions);
		}
	}
}
