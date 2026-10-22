package au.edu.eq.questionbank.ui.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import au.edu.eq.questionbank.diagnostics.FxPulseGapProbe;
import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
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
import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;
import au.edu.eq.questionbank.service.audit.BookletCorpusAudit;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog.SharedContextChoice;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
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
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

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
	private static final double REGIONS_VIEWPORT_MIN_HEIGHT = 96.0;
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

	// Capture dependencies and collaborators.
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final CurriculumSelectorPane curriculumSelectorPane;
	private final LegacyQuestionSplitService legacyQuestionSplitService;
	private final SqliteQuestionCaptureService questionCaptureService;
	private final QuestionExtractor questionExtractor;
	private final QuestionRepository questionRepository;
	private final SharedContextCapturePane sharedContextCapturePane;
	private final SourceQuestionRepository sourceQuestionRepository;

	// Application callbacks and workflow guards.
	private final Supplier<ExamBooklet> bookletSupplier;
	private final IntConsumer examPageNavigationHandler;
	private final Supplier<PdfSession> examPdfSessionSupplier;
	private final Predicate<Question> importedQuestionActivationHandler;
	private final Consumer<List<Question>> questionsChangedHandler;
	private final BooleanSupplier questionSelectionTransferHandler;
	private final BooleanSupplier questionTargetChangeAllowed;
	private final Runnable selectionClearHandler;

	// Question capture and editing state.
	// Reuse the worker's snapshot throughout synchronous listeners fired by save
	// completion.
	private List<Question> completionQuestions;
	private QuestionRegion currentSelection;
	private Question editingQuestion;
	private boolean importedCaptureMode;
	private Question importedQuestion;
	private LegacySplitCaptureState legacySplitCaptureState;
	private boolean loadingQuestionEdit;
	private String sharedContextLookupError;

	// New-Question capture is deliberately inactive until the user starts it.
	private boolean newQuestionCaptureActive;
	private boolean questionSaveInProgress;
	private boolean refreshingImportedQuestions;

	// Working Subject is transient workspace state. A null value preserves the
	// existing all-subject behaviour until the workspace selector is applied.
	private Subject workingSubject;

	// Shared Context and response-type state.
	private SharedQuestionContext inheritedMcqSharedContext;
	private boolean mcqContinuationSelected;
	private int sharedContextOriginalContentIndex;
	private final IdentityHashMap<QuestionContentPart, ContentPreview> contentPreviewNodes = new IdentityHashMap<>();
	private PdfSession contentPreviewSession;

	// Independent MCQs use one persisted booklet-level continuation rather than
	// SourceQuestion multipart identity.
	private boolean mcqSharedContextCaptureActive;
	private boolean refreshingSharedContextControls;

	// Automatic defaults may be replaced by an explicit response-type choice.
	private boolean responseTypeManuallySelected;

	// Suppress inference while a response-type change itself updates the marks
	// field.
	private boolean updatingResponseTypeSelection;

	// Controls and initial workflow state (initialisation order retained).
	// Capture mode and imported-question selection.
	private final ToggleButton importedQuestionsModeButton = new ToggleButton("Complete Imported Question");
	private final ToggleGroup captureModeGroup = new ToggleGroup();
	private final ComboBox<Question> importedQuestionBox = new ComboBox<>();
	private final Label importedClassificationLabel = new Label();
	private final Label captureHintLabel = new Label();
	private final VBox legacyCaptureBox = new VBox(COMPACT_SPACING);
	private final Label questionCodeStatusLabel = new Label();
	private final VBox questionWorkBox = new VBox(COMPACT_SPACING);

	// Question metadata and shared shared context controls.
	private final TextField questionCodeField = new TextField();
	private final TextField marksField = new TextField();
	private final ToggleGroup responseTypeGroup = new ToggleGroup();
	private final RadioButton multipleChoiceResponseButton = new RadioButton("Multiple choice");
	private final RadioButton writtenResponseButton = new RadioButton("Written response");
	private final CheckBox firstRegionSharedContextCheckBox = new CheckBox("First region is shared context");
	private final Label sharedContextStatusLabel = new Label();
	private final VBox sharedContextControlsBox = new VBox(COMPACT_SPACING);
	private VBox sharedContextPreviewNode;

	// Pending PDF selection and accepted mixed Question-content controls.
	private final Button addRegionButton = new Button("Add Region");
	private final Button pasteImageButton = new Button("Add From Clipboard");
	private final Label regionCountLabel = new Label("Content parts: 0");
	private final VBox regionPreviewBox = new VBox(SECTION_SPACING);
	private final ScrollPane regionsScrollPane = new ScrollPane(regionPreviewBox);
	private final QuestionClipboardImageReader clipboardImageReader = new QuestionClipboardImageReader();

	// Save and edit controls.
	private final Button saveQuestionButton = new Button("Save Question");
	private final Button cancelQuestionEditButton = new Button("Cancel");
	private final Label saveStatusLabel = new Label();

	// The most recently refreshed Question corpus is sufficient for synchronous
	// duplicate-code feedback without querying SQLite for every keystroke.
	private List<Question> questionSnapshot = List.of();
	private Consumer<Boolean> questionSaveBusyHandler = _ -> {
	};
	private List<SharedContextContentPart> sharedContextPreviewParts = List.of();

	// This list is the authoritative transient assembly order for the Question
	// body.
	// PDF selections and pasted images can therefore be interleaved arbitrarily.
	private final List<QuestionContentPart> pendingContentParts = new ArrayList<>();
	private Runnable questionEditCompletedHandler = () -> {
	};
	private long importedCaptureCompletionQuestionId = -1L;
	private Runnable importedCaptureCompletedHandler = () -> {
	};
	private final Label importedQuestionQueueLabel = new Label("Question(s) awaiting capture");
	private PerformanceRecorder performanceRecorder = new PerformanceRecorder(false, Path.of("performance.csv"));
	private ExamBooklet importedQuestionBookletScope;
	private FxPulseGapProbe importedEntryPulseProbe;
	private PdfSession sharedContextPreviewSession;

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

		// A newly created capture pane begins idle. Working Subject remains available,
		// but Question-specific work requires an explicit workflow transition.
		showCaptureIdleMode();
	}

	/**
	 * Returns incomplete Questions for the Working Subject without an additional
	 * booklet restriction.
	 *
	 * @param questions      current Question snapshot
	 * @param workingSubject Working Subject, or null for all Subjects
	 * @return Questions awaiting Question-side capture
	 */
	static List<Question> awaitingCaptureForWorkingSubject(List<Question> questions, Subject workingSubject) {
		return awaitingCaptureForWorkingSubject(questions, workingSubject, null);
	}

	/**
	 * Returns incomplete Questions restricted by Working Subject and, optionally,
	 * one persisted Question booklet.
	 *
	 * <p>
	 * The existing completion rules remain unchanged. Only the operational scope of
	 * the returned queue is different.
	 *
	 * @param questions      complete Question snapshot
	 * @param workingSubject Working Subject, or null for all Subjects
	 * @param bookletId      required persisted booklet ID, or null for Subject-wide
	 *                       capture
	 * @return incomplete Questions within the requested scope
	 */
	static List<Question> awaitingCaptureForWorkingSubject(List<Question> questions, Subject workingSubject,
			Long bookletId) {
		Objects.requireNonNull(questions, "questions");
		List<Question> awaitingCapture = new ArrayList<>();
		for (Question question : questions) {

			// A booklet-specific session must never include another
			// booklet, even when Provider, Year, Exam Name or
			// Question code coincide.
			if (bookletId != null && question.getBooklet().getId() != bookletId.longValue()) {
				continue;
			}
			if (workingSubject != null && !question.getExam().getSubject().equals(workingSubject)) {
				continue;
			}

			// Preserve all established reasons for Question-side work:
			// missing content, invalidated source or unresolved context.
			if (question.getContentParts().isEmpty() || question.isSourceCaptureRequired()
					|| question.isSharedContextUnresolved()) {
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
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()
				&& !importedQuestion.isSourceCaptureRequired()) {
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

		// An unaccepted PDF rectangle is cancelled by clicking elsewhere in the PDF;
		// there is no separate Clear action.
		saveStatusLabel.setText("Selection pending — click Add Region or click elsewhere in the PDF to cancel");
		refreshSaveButtonState();
	}

	/**
	 * Routes a proportional exam-page selection to active shared-context capture.
	 *
	 * @param selection the selected exam-page rectangle
	 */
	public void acceptSharedContextSelection(PdfWorkspacePane.RegionSelection selection) {
		sharedContextCapturePane.acceptSelection(selection);

		// Shared Context uses the same click-away cancellation semantics as ordinary
		// Question-region capture.
		saveStatusLabel.setText(
				"Shared context selection pending — click Add Context or click elsewhere in the PDF to cancel");
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

		// Legacy split capture is an explicit correction workflow.
		newQuestionCaptureActive = false;
		questionWorkBox.setDisable(false);
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
	 * Returns whether the Exam PDF may currently create a Question-side region
	 * selection.
	 *
	 * @return {@code true} while an explicit Question capture, edit, split or
	 *         Shared Context workflow is active
	 */
	public boolean canCaptureRegions() {
		if (questionSaveInProgress) {
			return false;
		}
		if (sharedContextCapturePane.isCaptureMode()) {
			return true;
		}
		return newQuestionCaptureActive || importedQuestion != null || editingQuestion != null
				|| legacySplitCaptureState != null;
	}

	/**
	 * Opens imported-question capture and selects one specific incomplete question.
	 *
	 * @param question persisted question requiring source/context work
	 * @return whether the requested question became the active capture target
	 */
	public boolean captureImportedQuestion(Question question) {

		// Ordinary callers do not require a completion notification.
		return captureImportedQuestion(question, () -> {
		});
	}

	/**
	 * Opens imported-question capture for one incomplete Question and records an
	 * operation to run after that Question is successfully persisted.
	 *
	 * @param question         persisted Question requiring source/context work
	 * @param completedHandler operation to run after successful correction
	 * @return whether the requested Question became the active capture target
	 */
	public boolean captureImportedQuestion(Question question, Runnable completedHandler) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
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
		boolean activated = importedQuestion != null && importedQuestion.getId() == question.getId();
		if (activated) {

			// Dashboard correction owns this callback only for the explicitly requested
			// persisted Question.
			importedCaptureCompletionQuestionId = question.getId();
			importedCaptureCompletedHandler = completedHandler;
		}
		return activated;
	}

	/**
	 * Starts capture of a missing context for an existing multipart SourceQuestion.
	 * Ordinary Question metadata and content remain read-only throughout the
	 * correction.
	 *
	 * @param question         an existing multipart Question without context
	 * @param saveHandler      transactional source-wide persistence callback
	 * @param completedHandler invoked after successful save or cancellation
	 * @return true when the capture workspace was prepared
	 */
	public boolean captureMissingSharedContext(Question question,
			BiFunction<String, List<SharedQuestionContextRegion>, SharedQuestionContext> saveHandler,
			Runnable completedHandler) {
		Objects.requireNonNull(question, "question");
		Objects.requireNonNull(saveHandler, "saveHandler");
		Objects.requireNonNull(completedHandler, "completedHandler");
		if (!question.hasSourceQuestion() || question.hasSharedContext()) {
			throw new IllegalArgumentException(
					"Question must have a SourceQuestion " + "but no existing Shared Context");
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return false;
		}
		if (!importedQuestionActivationHandler.test(question)) {
			restoreCaptureModeToggle();
			return false;
		}

		// The authoritative source PDF is active. This correction
		// must not enter ordinary Question editing or change its regions.
		importedQuestion = null;
		importedCaptureMode = false;
		editingQuestion = null;
		questionEditCompletedHandler = () -> {
		};
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		resetQuestionEntry();
		showSharedContextCorrectionQuestion(question);
		sharedContextCapturePane.refreshForCurrentBooklet();
		setSharedContextCorrectionVisible(true);
		String label = "Question " + question.getSourceQuestion().getSourceQuestionCode() + " shared context";
		boolean started = sharedContextCapturePane.captureMissingContext(label, saveHandler,
				() -> finishSharedContextRecapture(completedHandler));
		if (!started) {
			setSharedContextCorrectionVisible(false);
			resetQuestionEntry();
			showCaptureIdleMode();
			return false;
		}
		saveStatusLabel.setText("Capturing missing Shared Context for Question " + question.getQuestionCode());
		return true;
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
		newQuestionCaptureActive = false;

		// A changed Exam PDF invalidates any direct imported-correction return target.
		clearImportedCaptureCompletion();
		questionEditCompletedHandler = () -> {
		};
		captureModeGroup.selectToggle(null);
		setLegacyCaptureControlsVisible(false);
		resetQuestionEntry();
		clearSaveStatus();
		showCaptureIdleMode();
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

		// Editing is itself an explicit Question workflow and therefore activates the
		// Question controls without entering new-Question capture.
		newQuestionCaptureActive = false;
		questionWorkBox.setDisable(false);
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
		curriculumSelectorPane.setClassificationControlsDisabled(false);
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
	 * Indicates whether accepted Question content or automatic Shared Context
	 * content would be discarded by a workflow transition.
	 *
	 * @return true when accepted transient content exists
	 */
	public boolean hasAcceptedRegions() {
		boolean splitPartsStaged = legacySplitCaptureState != null && !legacySplitCaptureState.completedParts.isEmpty();
		return !pendingContentParts.isEmpty() || sharedContextCapturePane.hasPendingAutomaticContext()
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

			// A failed correction start must not turn into an implicit new-Question
			// capture workflow.
			resetQuestionEntry();
			showCaptureIdleMode();
			return false;
		}
		return true;
	}

	/**
	 * Re-evaluates Question-content actions whose availability depends on the
	 * current system clipboard.
	 */
	public void refreshClipboardImageAvailability() {
		pasteImageButton.setDisable(!canAcceptClipboardImage());
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

		// Activating a booklet establishes the structural target, but it does not
		// silently begin Question capture.
		resetQuestionEntry();
		clearSaveStatus();
		showCaptureIdleMode();
	}

	/**
	 * Reloads persisted questions that still require question-region capture while
	 * retaining the selected item when it remains available.
	 */
	public void refreshImportedQuestions() {
		refreshImportedQuestions(currentQuestions());
	}

	/**
	 * Restricts the imported/incomplete Question queue to one persisted Question
	 * booklet, or restores the Working Subject-wide queue.
	 *
	 * <p>
	 * Booklet capture must never offer incomplete Questions from another booklet
	 * while the selected source PDF remains active. A null booklet deliberately
	 * restores general correction navigation.
	 *
	 * @param booklet the required Question booklet, or null for Working
	 *                Subject-wide capture
	 * @throws IllegalArgumentException if the booklet belongs to another Working
	 *                                  Subject
	 */
	public void setImportedQuestionBookletScope(ExamBooklet booklet) {
		if (booklet != null && workingSubject != null
				&& booklet.getExam().getSubject().getId() != workingSubject.getId()) {
			throw new IllegalArgumentException("Capture booklet does not belong to the Working Subject");
		}

		// Compare persistent identities rather than Java object instances.
		if (booklet == null && importedQuestionBookletScope == null) {
			return;
		}
		if (booklet != null && importedQuestionBookletScope != null
				&& booklet.getId() == importedQuestionBookletScope.getId()) {
			return;
		}
		importedQuestionBookletScope = booklet;

		// Reuse the last authoritative corpus snapshot. Changing only
		// the visible queue scope requires no additional database read.
		refreshImportedQuestions(questionSnapshot);
	}

	/**
	 * Configures shared application performance diagnostics.
	 *
	 * @param performanceRecorder application recorder
	 * @throws NullPointerException if the recorder is null
	 */
	public void setPerformanceRecorder(PerformanceRecorder performanceRecorder) {
		Objects.requireNonNull(performanceRecorder, "performanceRecorder");
		if (importedEntryPulseProbe != null) {
			importedEntryPulseProbe.stop();
			importedEntryPulseProbe = null;
		}
		this.performanceRecorder = performanceRecorder;
		if (performanceRecorder.isEnabled()) {

			// JavaFX invokes this monitor only on actual animation pulses.
			// Disabled diagnostics create no continuous observation work.
			importedEntryPulseProbe = new FxPulseGapProbe(performanceRecorder);
			importedEntryPulseProbe.start();
		}
	}

	/**
	 * Configures application-level busy presentation for Question persistence.
	 *
	 * The handler receives true when saving begins and false when the save
	 * finishes, fails or is cancelled. Notifications run on the JavaFX Application
	 * Thread.
	 *
	 * @param handler application busy-state callback
	 */
	public void setQuestionSaveBusyHandler(Consumer<Boolean> handler) {
		questionSaveBusyHandler = Objects.requireNonNull(handler, "handler");
	}

	/**
	 * Changes the transient Subject used to filter the imported Question-capture
	 * queue.
	 *
	 * @param workingSubject Subject to display, or {@code null} to display all
	 *                       Subjects
	 */
	public void setWorkingSubject(Subject workingSubject) {

		// Preserve the existing standalone behaviour for callers that do not already
		// own a Question snapshot.
		setWorkingSubject(workingSubject, currentQuestions());
	}

	/**
	 * Changes the transient Working Subject and rebuilds the imported-Question
	 * queue from an already-loaded Question snapshot.
	 *
	 * <p>
	 * A booklet-specific queue is retained while refreshing the same Subject, but
	 * must not survive switching to another Subject.
	 *
	 * @param workingSubject Subject to display, or null for all Subjects
	 * @param questions      complete Question snapshot to filter
	 * @throws NullPointerException if questions is null
	 */
	public void setWorkingSubject(Subject workingSubject, List<Question> questions) {
		Objects.requireNonNull(questions, "questions");
		this.workingSubject = workingSubject;
		if (importedQuestionBookletScope != null && (workingSubject == null
				|| importedQuestionBookletScope.getExam().getSubject().getId() != workingSubject.getId())) {

			// A scope from the previous Subject cannot control the
			// imported Question queue in the new Subject.
			importedQuestionBookletScope = null;
		}
		refreshImportedQuestions(questions);
	}

	/**
	 * Makes imported-question capture controls available and refreshes their data.
	 */
	public void showLegacyCaptureControls() {
		showImportedQuestionCapture();
	}

	/**
	 * Starts the same new-Question workflow exposed by the visible capture-mode
	 * control.
	 */
	public void startNewQuestionCapture() {

		// Programmatic Dashboard routing must use the normal capture transition rather
		// than duplicating its state changes.
		showNewQuestionCapture();
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
		try (PerformanceOperation entry = performanceRecorder.start("capture.question.imported.entry");
				SqliteReadProbe sqlProbe = performanceRecorder.isEnabled() ? SqliteReadProbe.begin() : null) {
			try {
				newQuestionCaptureActive = false;
				List<Question> questions;
				try {

					// Repair derived SourceQuestion relationships first.
					// The returned snapshot reflects any successful repairs.
					try (PerformanceOperation backfill = performanceRecorder.start("capture.question.imported.backfill",
							entry.id())) {
						try {
							questions = backfillDerivedSourceQuestions(backfill.id());
						} catch (RuntimeException | Error failure) {
							backfill.failed();
							throw failure;
						}
					}

					// Use the same authoritative snapshot for
					// Shared Context reconciliation.
					int updatedRelationships;
					try (PerformanceOperation reconciliation = performanceRecorder
							.start("capture.question.imported.reconcile", entry.id())) {
						try {
							updatedRelationships = reconcileKnownSharedContexts(questions, reconciliation.id());
						} catch (RuntimeException | Error failure) {
							reconciliation.failed();
							throw failure;
						}
					}
					if (!importedCaptureMode || editingQuestion != null) {
						clearImportedCaptureCompletion();
						questionEditCompletedHandler = () -> {
						};
						editingQuestion = null;
						clearImportedQuestionSelection();
						importedCaptureMode = true;
					}
					selectCaptureModeToggle(true);
					try (PerformanceOperation refresh = performanceRecorder.start("capture.question.imported.refresh",
							entry.id())) {
						try {
							if (updatedRelationships > 0) {

								// Reconciliation changed persisted links.
								// Reload before publishing the authoritative queue.
								try (PerformanceOperation corpus = performanceRecorder
										.start("capture.question.imported.refresh.corpus", refresh.id())) {
									try {
										questions = questionRepository.findAll();
										corpus.resultCount(questions.size());
									} catch (RuntimeException | Error failure) {
										corpus.failed();
										throw failure;
									}
								}
							}
							refreshImportedQuestions(questions);
							refresh.resultCount(questionSnapshot.size());
						} catch (RuntimeException | Error failure) {
							refresh.failed();
							throw failure;
						}
					}
					entry.resultCount(importedQuestionBox.getItems().size());
					if (importedQuestionBox.getItems().isEmpty()) {
						return;
					}
					setLegacyCaptureControlsVisible(true);
					if (importedQuestion == null) {
						showImportedQueueMode();
					}
				} catch (IllegalStateException exception) {
					entry.failed();

					// Never retain an active imported queue after a failed
					// reconciliation. The earlier snapshot may now be stale.
					rejectImportedCaptureEntry();
					showAlert(Alert.AlertType.ERROR, "Shared context links are inconsistent.", exception.getMessage());
					return;
				}
			} catch (RuntimeException | Error failure) {
				entry.failed();

				// A failure during queue publication must also leave the
				// workspace unable to edit from a stale imported snapshot.
				rejectImportedCaptureEntry();
				throw failure;
			} finally {
				if (sqlProbe != null) {
					recordImportedEntrySql(entry.id(), sqlProbe.snapshot());
				}
				if (importedEntryPulseProbe != null) {
					importedEntryPulseProbe.recordEntryCompletion(entry.id());
				}
			}
		}
	}

	/**
	 * Attempts to switch to new-question capture, subject to pending-state guards.
	 * Accepted state is cleared only after the transition is allowed.
	 */
	void showNewQuestionCapture() {
		if (!importedCaptureMode && editingQuestion == null && legacySplitCaptureState == null) {

			// Idle-to-new is the normal explicit workflow transition. Re-firing the
			// action while already capturing simply keeps that workflow active.
			selectCaptureModeToggle(false);
			setLegacyCaptureControlsVisible(false);
			showNewQuestionMode();
			return;
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return;
		}

		// Explicitly leaving imported correction abandons any direct-correction
		// callback
		// associated with that unfinished Question.
		clearImportedCaptureCompletion();
		questionEditCompletedHandler = () -> {
		};
		editingQuestion = null;
		importedCaptureMode = false;
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		clearImportedQuestionSelection();

		// Leaving the queue makes its entry action useful again if imported/incomplete
		// work still remains.
		updateImportedCaptureAvailability(!importedQuestionBox.getItems().isEmpty());
	}

	private void activateImportedQuestionControls(Question question) {

		// Selecting a queued imported/incomplete Question explicitly activates
		// Question-side work without entering ordinary new-Question capture.
		newQuestionCaptureActive = false;
		questionWorkBox.setDisable(false);
		questionCodeField.setText(question.getQuestionCode());
		marksField.setText(Integer.toString(question.getMarks()));
		if (question.hasSharedContext()) {
			sharedContextCapturePane.selectContext(question.getSharedContext());
		}
		curriculumSelectorPane.selectClassificationPath(question.getClassification());
		curriculumSelectorPane.setClassificationControlsDisabled(false);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
		showImportedClassification(question);
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		loadResponseType(question);
		setResponseTypeDisabled(false);
		refreshSharedContextControls();
	}

	private void addContentPreview(QuestionContentPart part, int contentIndex) {
		ContentPreview preview = contentPreviewNodes.get(part);
		if (preview == null) {
			ImageView imageView;
			if (part instanceof PdfQuestionContentPart pdfPart) {
				try {
					BufferedImage image = extractQuestionPreviewRegion(pdfPart.region());
					imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
				} catch (IOException exception) {
					throw new RuntimeException("Unable to preview PDF Question content", exception);
				}
			} else if (part instanceof ImageQuestionContentPart imagePart) {
				Image image = new Image(new ByteArrayInputStream(imagePart.pngBytes()));
				if (image.isError()) {
					throw new IllegalStateException("Unable to preview stored Question image", image.getException());
				}
				imageView = new ImageView(image);
			} else {
				throw new IllegalStateException("Unsupported Question content part: " + part.getClass().getName());
			}
			imageView.setPreserveRatio(true);
			imageView.fitWidthProperty()
					.bind(Bindings.createDoubleBinding(
							() -> Math.max(0.0, regionsScrollPane.getWidth() - REGION_PREVIEW_HORIZONTAL_INSET),
							regionsScrollPane.widthProperty()));
			imageView.setSmooth(true);
			Label label = new Label();
			Button moveUpButton = new Button("Up");
			Button moveDownButton = new Button("Down");
			Button removeButton = new Button("Remove");
			HBox header = new HBox(SECTION_SPACING, label, moveUpButton, moveDownButton, removeButton);
			VBox container = new VBox(REGION_PREVIEW_ITEM_SPACING, header, imageView);
			preview = new ContentPreview(container, label, moveUpButton, moveDownButton, removeButton);
			contentPreviewNodes.put(part, preview);
		}

		// The rendered content remains unchanged when parts move.
		// Only its presentation index and associated actions change.
		if (part instanceof PdfQuestionContentPart pdfPart) {
			preview.label()
					.setText(String.format("Part %d — PDF page %d", contentIndex + 1, pdfPart.region().pageNumber()));
		} else {
			preview.label().setText(String.format("Part %d — Pasted image", contentIndex + 1));
		}
		preview.container().setId("question-content-part-" + contentIndex);
		preview.moveUp().setId("question-content-move-up-" + contentIndex);
		preview.moveUp().setDisable(contentIndex == 0);
		preview.moveUp().setOnAction(_ -> moveContentPart(contentIndex, -1));
		preview.moveDown().setId("question-content-move-down-" + contentIndex);
		preview.moveDown().setDisable(contentIndex == pendingContentParts.size() - 1);
		preview.moveDown().setOnAction(_ -> moveContentPart(contentIndex, 1));
		preview.remove().setId("question-content-remove-" + contentIndex);
		preview.remove().setOnAction(_ -> removeContentPart(contentIndex));
		regionPreviewBox.getChildren().add(preview.container());
	}

	private void addCurrentRegion() {
		if (sharedContextCapturePane.isCaptureMode()) {
			if (!sharedContextCapturePane.hasCurrentSelection()) {
				return;
			}
			try (PerformanceOperation operation = performanceRecorder.start("capture.question.shared-context.accept")) {
				try {
					try (PerformanceOperation acceptance = performanceRecorder
							.start("capture.question.shared-context.region", operation.id())) {
						try {
							if (!sharedContextCapturePane.acceptAutomaticRegion()) {
								acceptance.failed();
								operation.failed();
								return;
							}
							acceptance.resultCount(1);
						} catch (RuntimeException | Error failure) {
							acceptance.failed();
							throw failure;
						}
					}
					try (PerformanceOperation preview = performanceRecorder
							.start("capture.question.shared-context.preview", operation.id())) {
						try {
							refreshSharedContextPreview();
							preview.resultCount(1);
						} catch (RuntimeException | Error failure) {
							preview.failed();
							throw failure;
						}
					}
					try (PerformanceOperation controls = performanceRecorder
							.start("capture.question.shared-context.controls", operation.id())) {
						try {
							updateQuestionCodeLock();
							hideSharedContextStatus();
							if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()) {
								saveStatusLabel.setText("Shared context captured — save the resolution.");
							} else {
								saveStatusLabel.setText("Shared context captured — add the question content.");
							}
							refreshSaveButtonState();
							controls.resultCount(1);
						} catch (RuntimeException | Error failure) {
							controls.failed();
							throw failure;
						}
					}
					operation.resultCount(1);
				} catch (RuntimeException | Error failure) {
					operation.failed();
					throw failure;
				}
			}
			return;
		}
		if (currentSelection == null) {
			return;
		}
		try (PerformanceOperation operation = performanceRecorder.start("capture.question.region.accept")) {
			try {

				// Preserve the existing authoritative content-part order.
				pendingContentParts.add(new PdfQuestionContentPart(currentSelection));
				try (PerformanceOperation clearing = performanceRecorder
						.start("capture.question.region.clear-selection", operation.id())) {
					try {
						clearCurrentSelection();
						clearing.resultCount(1);
					} catch (RuntimeException | Error failure) {
						clearing.failed();
						throw failure;
					}
				}
				try (PerformanceOperation preview = performanceRecorder.start("capture.question.region.preview",
						operation.id())) {
					try {
						refreshRegionPreviews();
						preview.resultCount(pendingContentParts.size());
					} catch (RuntimeException | Error failure) {
						preview.failed();
						throw failure;
					}
				}
				try (PerformanceOperation controls = performanceRecorder.start("capture.question.region.controls",
						operation.id())) {
					try {
						setRegionCountLabel(pendingContentParts.size());
						showQuestionPendingStatus();
						updateQuestionCodeLock();
						refreshSaveButtonState();
						controls.resultCount(pendingContentParts.size());
					} catch (RuntimeException | Error failure) {
						controls.failed();
						throw failure;
					}
				}
				operation.resultCount(pendingContentParts.size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
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

	private List<Question> backfillDerivedSourceQuestions(long parentOperationId) {
		List<Question> questions;
		try (PerformanceOperation corpus = performanceRecorder.start("capture.question.imported.backfill.corpus",
				parentOperationId)) {
			try {
				questions = questionRepository.findAll();
				corpus.resultCount(questions.size());
			} catch (RuntimeException | Error failure) {
				corpus.failed();
				throw failure;
			}
		}
		int repaired = 0;
		try (PerformanceOperation links = performanceRecorder.start("capture.question.imported.backfill.links",
				parentOperationId)) {
			try {
				for (Question question : questions) {
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
					repaired++;
				}
				links.resultCount(repaired);
			} catch (RuntimeException | Error failure) {
				links.failed();
				throw failure;
			}
		}
		if (repaired == 0) {

			// No persisted relationship changed. The original
			// authoritative snapshot remains valid.
			return questions;
		}
		try (PerformanceOperation reload = performanceRecorder.start("capture.question.imported.backfill.reload",
				parentOperationId)) {
			try {

				// Return the newly persisted SourceQuestion identities.
				// Reconciliation must never use the pre-repair objects.
				List<Question> refreshed = questionRepository.findAll();
				reload.resultCount(refreshed.size());
				return refreshed;
			} catch (RuntimeException | Error failure) {
				reload.failed();
				throw failure;
			}
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

			// The transferred rectangle now belongs to Shared Context capture and uses the
			// normal PDF click-away cancellation behaviour.
			saveStatusLabel.setText(
					"Shared context selection pending — click Add Context or click elsewhere in the PDF to cancel");
		}
		return started;
	}

	private void beginQuestionSaveBusy() {
		questionSaveInProgress = true;
		setDisable(true);

		// The application becomes logically busy immediately.
		// The shared overlay controls delayed visual appearance.
		questionSaveBusyHandler.accept(true);
	}

	private void buildContent() {
		legacyCaptureBox.getChildren().addAll(importedQuestionQueueLabel, createImportedQuestionControls(),
				importedClassificationLabel, captureHintLabel);
		sharedContextControlsBox.getChildren().addAll(firstRegionSharedContextCheckBox, sharedContextStatusLabel);
		setLegacyCaptureControlsVisible(false);

		// Question metadata, content and persistence controls form one activatable
		// workflow. Capture-entry actions remain outside this box so they remain
		// available while ordinary Question work is inactive.
		questionWorkBox.setId("question-capture-work");
		questionWorkBox.getChildren().addAll(createQuestionControls(), sharedContextControlsBox, saveStatusLabel,
				createCurrentSelectionControls(), new Separator(), new Label("Accepted Question content"),
				regionCountLabel, createRegionsScrollPane());

		// The shared-context editor is exposed only for an explicit correction
		// workflow. Automatic Shared Context capture continues to use it as internal
		// state.
		sharedContextCapturePane.setVisible(false);
		sharedContextCapturePane.setManaged(false);
		getChildren().addAll(createSectionLabel("Question"), createCaptureModeControls(), legacyCaptureBox,
				questionWorkBox, sharedContextCapturePane);
	}

	private VBox cachedSharedContextPreview(List<SharedContextContentPart> parts) {
		if (parts.isEmpty()) {
			sharedContextPreviewNode = null;
			sharedContextPreviewParts = List.of();
			sharedContextPreviewSession = null;
			return null;
		}
		PdfSession session = examPdfSessionSupplier.get();

		// Reuse the rendered preview when its ordered content and
		// originating PDF session have not changed.
		if (sharedContextPreviewNode != null && sharedContextPreviewSession == session
				&& sharedContextPreviewParts.equals(parts)) {
			return sharedContextPreviewNode;
		}
		VBox preview = createSharedContextPreview(parts);
		sharedContextPreviewNode = preview;
		sharedContextPreviewParts = List.copyOf(parts);
		sharedContextPreviewSession = session;
		return preview;
	}

	private boolean canAcceptClipboardImage() {
		if (questionSaveInProgress || bookletSupplier.get() == null) {
			return false;
		}

		// Text-only, file-only and empty clipboard states never enable
		// the existing Add From Clipboard button.
		if (!clipboardImageReader.hasImage()) {
			return false;
		}
		if (legacySplitCaptureState != null) {
			return false;
		}
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()
				&& !importedQuestion.isSourceCaptureRequired()) {
			return false;
		}

		// Never fall back to ordinary Question capture while Shared
		// Context capture owns the workflow or its PDF selection.
		if (sharedContextCapturePane.isCaptureMode()) {
			return sharedContextCapturePane.canAcceptAutomaticClipboardImage();
		}
		return currentSelection == null;
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
					"Add the current selection or click elsewhere in the PDF to cancel it before changing question capture mode.");
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
		if (refreshingImportedQuestions) {
			return;
		}
		Question requestedQuestion = importedQuestionBox.getValue();

		// A mouse selection is still being completed by the ComboBox popup's internal
		// ListView when this action fires. Defer booklet/Subject activation until that
		// selection transaction has finished, because activation can rebuild this
		// queue.
		Platform.runLater(() -> {
			if (refreshingImportedQuestions) {
				return;
			}
			Question currentQuestion = importedQuestionBox.getValue();

			// Ignore a deferred action if the teacher has selected another Question before
			// this callback runs.
			if (requestedQuestion == null) {
				if (currentQuestion != null) {
					return;
				}
			} else if (currentQuestion == null || currentQuestion.getId() != requestedQuestion.getId()) {
				return;
			}
			loadImportedQuestion(requestedQuestion);
		});
	}

	private void clearClipboardAfterAcceptedImage() {
		try {

			// The clipboard image is already part of the accepted
			// Question content. Remove its source to prevent accidental
			// reuse through a subsequent paste operation.
			Clipboard.getSystemClipboard().clear();
		} catch (RuntimeException exception) {

			// Failure to clear the operating-system clipboard must not
			// undo an accepted image or interrupt Question capture.
			saveStatusLabel.setText("Image accepted, but the clipboard could not be cleared.");
		}
	}

	private void clearImportedCaptureCompletion() {
		importedCaptureCompletionQuestionId = -1L;
		importedCaptureCompletedHandler = () -> {
		};
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

	private void clearIndependentMcqSharedContextState() {

		// Leaving independent MCQ handling clears its transient continuation state.
		sharedContextCapturePane.cancelAutomaticContext();
		mcqSharedContextCaptureActive = false;
		mcqContinuationSelected = false;
		inheritedMcqSharedContext = null;
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

	private void completeAutomaticSharedContextCapture() {
		updateQuestionCodeLock();
		hideSharedContextStatus();
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()) {
			saveStatusLabel.setText("Shared context captured — save the resolution.");
		} else {
			saveStatusLabel.setText("Shared context captured — add the question content.");
		}
		refreshRegionPreviews();
		refreshSaveButtonState();
		refreshClipboardImageAvailability();
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

				// Release application input before the modal warning.
				finishQuestionSaveBusy();
				showAlert(Alert.AlertType.WARNING, "Question split saved; lists could not be fully refreshed.",
						"The split is stored. Reopen Search or capture to reload the question lists.");
			}
		} finally {
			try {
				finishQuestionSaveBusy();
			} finally {
				completionQuestions = null;
			}
		}
		if (completedHandler != null) {
			completedHandler.run();
		}
	}

	private void completeQuestionSave(QuestionSaveResult result, boolean editing, boolean imported,
			int previousImportedIndex, boolean hadStoredContent) {
		completionQuestions = result.questions();
		Runnable completedHandler = null;
		try {
			if (result.validationError() != null) {
				saveStatusLabel.setText("Question not saved — check question details");
				finishQuestionSaveBusy();
				showAlert(Alert.AlertType.WARNING, "Question could not be saved.", result.validationError());
				return;
			}

			// A non-null Question means persistence returned after commit.
			Question question = result.question();

			// Clear stale lookup keywords only after a committed save.
			// Validation failures, failed Tasks and cancelled Tasks never
			// enter this success path. The saved classification is unchanged.
			curriculumSelectorPane.clearDescriptorSearch();
			try {
				questionsChangedHandler.accept(result.questions());
				if (editing) {
					completedHandler = finishQuestionEditState();
				} else {
					if (imported) {
						completedHandler = takeImportedCaptureCompletion(question);
					}
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
					finishQuestionSaveBusy();
					showAlert(Alert.AlertType.WARNING, "Question saved; lists could not be fully refreshed.",
							"Question " + question.getQuestionCode() + " was saved with ID " + question.getId()
									+ ".\n\n" + "Do not save it again. "
									+ "Reopen capture to reload the question lists.");
				}

				// Preserve the established callback contract:
				// completion handlers run only after the save
				// transition and busy overlay have finished.
				finishQuestionSaveBusy();
				completionQuestions = null;
				if (completedHandler != null) {
					completedHandler.run();
				}
			} catch (RuntimeException publicationFailure) {

				// SQLite persistence has already committed.
				// Disable the existing capture so it cannot be
				// submitted again after partial UI publication.
				newQuestionCaptureActive = false;
				importedCaptureMode = false;
				editingQuestion = null;
				importedQuestion = null;
				questionWorkBox.setDisable(true);
				saveQuestionButton.setDisable(true);
				cancelQuestionEditButton.setDisable(true);
				saveStatusLabel.setText("Saved " + question.getQuestionCode() + " (ID " + question.getId()
						+ ") — workspace refresh failed");
				finishQuestionSaveBusy();
				showAlert(Alert.AlertType.WARNING, "Question saved; workspace refresh failed.",
						"Question " + question.getQuestionCode() + " was saved successfully with ID " + question.getId()
								+ ".\n\n" + "The capture workspace could not be " + "fully updated.\n"
								+ "Do not save this Question again.\n" + "Return to the Dashboard and reopen "
								+ "capture to reload the stored Questions.");
			}
		} finally {
			try {
				finishQuestionSaveBusy();
			} finally {
				completionQuestions = null;
			}
		}
	}

	private void configureActions() {

		// Keep event wiring separate from the behaviour executed by those events.
		configureQuestionContentActions();
		configureQuestionMetadataListeners();
		configureResponseTypeActions();
		configureCaptureWorkflowActions();
	}

	private void configureCaptureModeControls() {

		// Ordinary new-Question capture is entered by the application-level Dashboard
		// or Exam/Assets route. Only imported/incomplete work needs a local mode
		// action.
		importedQuestionsModeButton.setId("capture-mode-imported");
		importedQuestionsModeButton.setTooltip(new Tooltip(
				"Complete outstanding Question content or Shared Context work for an imported/incomplete Question."));
		importedQuestionsModeButton.setMinWidth(Region.USE_PREF_SIZE);
		importedQuestionsModeButton.setToggleGroup(captureModeGroup);

		// Imported capture is operational work. Do not expose its entry point until
		// the filtered queue proves that relevant work exists.
		importedQuestionsModeButton.setVisible(false);
		importedQuestionsModeButton.setManaged(false);

		// New capture has no local toggle; the group records only imported-mode
		// presentation when that workflow is active.
		captureModeGroup.selectToggle(null);
		legacyCaptureBox.setId("legacy-question-capture");
	}

	private void configureCaptureWorkflowActions() {

		// New capture is application-routed. The Question pane retains only the
		// conditional imported/incomplete Question workflow action.
		importedQuestionBox.setOnAction(_ -> checkImportedQuestionBox());
		importedQuestionsModeButton.setOnAction(_ -> showImportedQuestionCapture());
	}

	private void configureControls() {

		// Keep configuration grouped by the UI concern being established. Runtime
		// workflow state changes remain in the existing transition methods.
		configureQuestionMetadataControls();
		configureResponseTypeControls();
		configureQuestionContentControls();
		configureImportedQuestionControls();
		configureCaptureModeControls();
		configureSharedContextControls();
		configureStatusAndEditControls();
	}

	private void configureImportedQuestionControls() {

		// The queue heading remains visible throughout imported/incomplete Question
		// work,
		// including Dashboard-launched sequential completion.
		importedQuestionQueueLabel.setId("imported-question-queue-label");
		importedQuestionBox.setId("imported-question");
		importedQuestionBox.setPromptText("Select imported question");
		importedQuestionBox.setTooltip(new Tooltip(
				"Select an imported or incomplete Question requiring outstanding Question content or Shared Context work."));
		importedQuestionBox.setMaxWidth(Double.MAX_VALUE);
		importedQuestionBox.setConverter(new ImportedQuestionStringConverter());
		importedQuestionBox.setOnShowing(_ -> showSelectedImportedQuestionDocument());
		importedClassificationLabel.setId("imported-classification");
		importedClassificationLabel.setWrapText(true);
		importedClassificationLabel.setVisible(false);
		importedClassificationLabel.setManaged(false);
	}

	private void configureImportedQuestionSavePresentation(Question question) {
		if (question.isSourceCaptureRequired()) {
			saveQuestionButton.setText("Save Recapture");

			// Independent image content remains valid after PDF replacement; only the
			// source-dependent PDF content must be captured again.
			showCaptureHint("The Question PDF was replaced. Preserved image content is shown below; "
					+ "capture at least one replacement PDF region.");
			return;
		}
		if (question.getContentParts().isEmpty()) {
			saveQuestionButton.setText("Save Question");
			if (question.isSharedContextUnresolved()) {
				hideCaptureHint();
			} else {
				showCaptureHint("Capture or paste the question content.");
			}
			return;
		}
		saveQuestionButton.setText("Save Resolution");
		hideCaptureHint();
	}

	private void configurePane() {
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	private void configureQuestionContentActions() {
		addRegionButton.setOnAction(_ -> addCurrentRegion());
		pasteImageButton.setOnAction(_ -> pasteClipboardImage());
		saveQuestionButton.setOnAction(_ -> validateQuestionForSave());
		cancelQuestionEditButton.setOnAction(_ -> cancelQuestionEdit());
	}

	private void configureQuestionContentControls() {

		// These controls create or persist ordinary Question content.
		addRegionButton.setId("add-question-region");
		addRegionButton.setTooltip(new Tooltip(
				"Accept the current PDF selection as Question content; it is persisted when the Question is saved."));
		pasteImageButton.setId("paste-question-image");
		pasteImageButton.setTooltip(new Tooltip(
				"Add clipboard image content to the Question; it is persisted when the Question is saved."));
		saveQuestionButton.setId("save-question");
		saveQuestionButton.setDisable(true);
		regionCountLabel.setId("question-region-count");

		// Action labels must remain fully readable at the minimum supported capture
		// workspace width.
		addRegionButton.setMinWidth(Region.USE_PREF_SIZE);
		pasteImageButton.setMinWidth(Region.USE_PREF_SIZE);
		saveQuestionButton.setMinWidth(Region.USE_PREF_SIZE);
		cancelQuestionEditButton.setMinWidth(Region.USE_PREF_SIZE);
		addRegionButton.setPadding(COMPACT_BUTTON_PADDING);
		pasteImageButton.setPadding(COMPACT_BUTTON_PADDING);

		// Question-content actions begin unavailable. Their runtime state is derived
		// from the active capture workflow, pending PDF selection and clipboard image.
		addRegionButton.setDisable(true);
		pasteImageButton.setDisable(true);
	}

	private void configureQuestionMetadataControls() {

		// Question identity and marks form the compact metadata row used by every
		// Question-side workflow.
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
	}

	private void configureQuestionMetadataListeners() {
		questionCodeField.textProperty().addListener((_, _, newCode) -> handleQuestionCodeChanged(newCode));
		marksField.textProperty().addListener((_, _, _) -> handleMarksChanged());
		curriculumSelectorPane.selectedClassificationProperty().addListener((_, _, _) -> refreshSaveButtonState());
	}

	private void configureResponseTypeActions() {
		responseTypeGroup.selectedToggleProperty().addListener((_, _, _) -> handleResponseTypeChanged());

		// Action events identify an explicit user choice. Programmatic response-type
		// selection does not set this flag.
		multipleChoiceResponseButton.setOnAction(_ -> handleManualResponseTypeSelection());
		writtenResponseButton.setOnAction(_ -> handleManualResponseTypeSelection());
		firstRegionSharedContextCheckBox.selectedProperty()
				.addListener((_, _, selected) -> handleSharedContextOptionChanged(selected.booleanValue()));
	}

	private void configureResponseTypeControls() {

		// Both response-type controls share one ToggleGroup; booklet policy and
		// workflow state decide later whether either control is editable.
		multipleChoiceResponseButton.setId("question-response-type-multiple-choice");
		writtenResponseButton.setId("question-response-type-written");
		multipleChoiceResponseButton.setToggleGroup(responseTypeGroup);
		writtenResponseButton.setToggleGroup(responseTypeGroup);
		multipleChoiceResponseButton.setUserData(QuestionResponseType.MULTIPLE_CHOICE);
		writtenResponseButton.setUserData(QuestionResponseType.WRITTEN_RESPONSE);
		multipleChoiceResponseButton.setTooltip(new Tooltip(
				"Store this as a multiple-choice Question; it must be worth one mark and its Answer is an A-D choice."));
		writtenResponseButton.setTooltip(new Tooltip(
				"Store this as a written-response Question whose Answer may be captured from an Answer PDF."));

		// Keep both labels fully readable at the minimum supported workspace width.
		multipleChoiceResponseButton.setMinWidth(Region.USE_PREF_SIZE);
		writtenResponseButton.setMinWidth(Region.USE_PREF_SIZE);
	}

	private void configureSharedContextControls() {

		// The same compact control is reused for multipart Written Response and MCQ
		// continuation semantics; runtime methods replace its text as required.
		firstRegionSharedContextCheckBox.setId("first-region-shared-context");
		firstRegionSharedContextCheckBox.setTooltip(
				new Tooltip("Store the first captured region as shared context for all parts of this question."));
		firstRegionSharedContextCheckBox.setVisible(false);
		firstRegionSharedContextCheckBox.setManaged(false);
		sharedContextStatusLabel.setId("shared-context-status");
		sharedContextStatusLabel.setVisible(false);
		sharedContextStatusLabel.setManaged(false);
		sharedContextStatusLabel.setWrapText(true);
	}

	private void configureStatusAndEditControls() {

		// Capture, edit and context status messages may exceed the narrow workspace
		// width, so preserve the complete message through wrapping.
		saveStatusLabel.setId("question-save-status");
		saveStatusLabel.setWrapText(true);
		saveStatusLabel.setMaxWidth(Double.MAX_VALUE);
		saveStatusLabel.setStyle(SUCCESS_STATUS_STYLE);
		captureHintLabel.setId("question-capture-hint");
		captureHintLabel.setWrapText(true);
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
		cancelQuestionEditButton.setId("cancel-question-edit");
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
	}

	private boolean confirmNewTopLevelQuestionBeyondExpected() {

		// Editing an existing record, resolving imported Question work and legacy
		// splitting do not introduce an independent new top-level Question.
		if (!newQuestionCaptureActive || editingQuestion != null || importedQuestion != null
				|| legacySplitCaptureState != null) {
			return true;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null || booklet.getExpectedQuestionCount() == null) {
			return true;
		}

		// BookletCorpusAudit already owns the authoritative grouping rule:
		// ordinary Questions count individually, while multipart records
		// sharing a persisted SourceQuestion count once.
		int found = BookletCorpusAudit.assess(booklet, questionSnapshot, null, true).encounteredTopLevelQuestionCount();
		int expected = booklet.getExpectedQuestionCount();
		if (found < expected) {
			return true;
		}
		String proposedCode = questionCodeField.getText().trim();
		String sourceCode = SourceQuestionCodeParser.derive(proposedCode);
		if (sourceCode != null
				&& questionSnapshot.stream().filter(question -> question.getBooklet().getId() == booklet.getId())
						.filter(Question::hasSourceQuestion).anyMatch(
								question -> sourceCode.equals(question.getSourceQuestion().getSourceQuestionCode()))) {

			// For example, 9b and 9c share the top-level source Question already
			// represented by saved 9a. Duplicate 9a is rejected separately.
			return true;
		}
		ButtonType proceed = new ButtonType("Save New Question", ButtonBar.ButtonData.OK_DONE);
		Alert confirmation = new Alert(Alert.AlertType.WARNING);
		if (getScene() != null && getScene().getWindow() != null) {
			confirmation.initOwner(getScene().getWindow());
		}
		confirmation.setTitle("Capture Questions");
		confirmation.getDialogPane().setId("question-capture-count-confirmation");
		confirmation.setHeaderText(found == expected ? "Expected top-level Question count already reached."
				: "Top-level Question count already exceeds expectation.");
		confirmation.setContentText("Question " + proposedCode + " introduces a new top-level Question in booklet "
				+ booklet.getName() + ".\n\n" + "Expected top-level Questions: " + expected + ". Already encountered: "
				+ found + ".\n\nCheck the source before saving. If the expected count is wrong, "
				+ "correct it in Exam / Assets.");
		confirmation.getButtonTypes().setAll(proceed, ButtonType.CANCEL);
		return confirmation.showAndWait().orElse(ButtonType.CANCEL) == proceed;
	}

	private boolean continueSharedContextToNextMcq() {
		return isNewIndependentMcqCapture() && mcqContinuationSelected;
	}

	private HBox createCaptureModeControls() {

		// Imported correction remains a local Question-pane action because it selects
		// from persisted outstanding work. New capture is entered from Dashboard or
		// Exam/Assets and therefore needs no duplicate control here.
		HBox controls = new HBox(CONTROL_SPACING, importedQuestionsModeButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private VBox createCurrentSelectionControls() {

		// These are the two ordinary ways to add Question content. A pending PDF
		// rectangle is cancelled by clicking away rather than by a separate button.
		HBox contentControls = new HBox(CONTROL_SPACING, addRegionButton, pasteImageButton);
		contentControls.setAlignment(Pos.CENTER_LEFT);

		// Persistence remains on its own row so content creation and Save do not
		// compete for horizontal space in the narrow capture workspace.
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox saveControls = new HBox(CONTROL_SPACING, spacer, saveQuestionButton, cancelQuestionEditButton);
		saveControls.setAlignment(Pos.CENTER_LEFT);
		VBox controls = new VBox(COMPACT_SPACING, contentControls, saveControls);
		controls.setFillWidth(true);
		return controls;
	}

	private HBox createImportedQuestionControls() {
		HBox controls = new HBox(CONTROL_SPACING, importedQuestionBox);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
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

	private Task<LegacySplitSaveResult> createLegacySplitSaveTask(SplitRequest request) {

		// Legacy split persistence uses the same reusable Task wrapper as ordinary
		// Question saves.
		return new CaptureBackgroundTask<>(() -> persistLegacyQuestionSplitTransaction(request));
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

	private Task<QuestionSaveResult> createQuestionSaveTask(SqliteQuestionCaptureService.Request request,
			List<Question> beforeSaveSnapshot, long parentOperationId) {

		// Capture the FX-thread snapshot before starting background work.
		// The worker uses this only for validation rejection and
		// post-commit refresh recovery, never for stored-state validation.
		List<Question> fallbackSnapshot = List.copyOf(beforeSaveSnapshot);
		return new CaptureBackgroundTask<>(() -> persistQuestionCapture(request, fallbackSnapshot, parentOperationId));
	}

	private ScrollPane createRegionsScrollPane() {
		regionsScrollPane.setFitToWidth(true);
		regionsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		regionsScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

		// Grow to the preferred height of the accepted content,
		// rather than waiting for its layout bounds to update.
		// Retain the minimum viewport and 300 px scrolling cap.
		regionsScrollPane.setMinHeight(REGIONS_VIEWPORT_MIN_HEIGHT);
		regionsScrollPane.setMaxHeight(REGIONS_VIEWPORT_HEIGHT);
		regionsScrollPane.prefHeightProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.max(REGIONS_VIEWPORT_MIN_HEIGHT,
								Math.min(REGIONS_VIEWPORT_HEIGHT,
										regionPreviewBox.prefHeight(-1) + REGION_VIEWPORT_EXTRA_HEIGHT)),
						regionPreviewBox.getChildren(), regionsScrollPane.widthProperty()));
		regionsScrollPane.setVisible(false);
		regionsScrollPane.setManaged(false);
		return regionsScrollPane;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private VBox createSharedContextPreview(List<SharedContextContentPart> parts) {
		Label label = new Label("Shared Context");
		VBox preview = new VBox(REGION_PREVIEW_ITEM_SPACING, label);
		preview.setId("question-shared-context-preview");
		for (int index = 0; index < parts.size(); index++) {
			SharedContextContentPart part = parts.get(index);
			Image image;
			if (part instanceof SharedQuestionContextRegion region) {
				try {
					image = SwingFXUtils.toFXImage(extractSharedContextPreviewRegion(region), null);
				} catch (IOException exception) {
					throw new RuntimeException("Unable to preview Shared Context PDF region", exception);
				}
			} else if (part instanceof ImageSharedContextContentPart imagePart) {
				image = new Image(new ByteArrayInputStream(imagePart.pngBytes()));
			} else {
				throw new IllegalStateException("Unsupported Shared Context content: " + part);
			}
			ImageView imageView = new ImageView(image);
			imageView.setId("question-shared-context-image-" + index);
			imageView.setPreserveRatio(true);
			imageView.setSmooth(true);

			// Keep the same responsive width as ordinary Question content.
			imageView.fitWidthProperty()
					.bind(Bindings.createDoubleBinding(
							() -> Math.max(0.0, regionsScrollPane.getWidth() - REGION_PREVIEW_HORIZONTAL_INSET),
							regionsScrollPane.widthProperty()));
			preview.getChildren().add(imageView);
		}
		return preview;
	}

	private List<Question> currentQuestions() {
		return completionQuestions == null ? questionRepository.findAll() : completionQuestions;
	}

	private List<SharedContextContentPart> currentSharedContextContentParts() {
		if (sharedContextCapturePane.hasPendingAutomaticContext()) {

			// Display accepted automatic Shared Context before persistence.
			// Image-only contexts have no PDF regions, but remain valid content.
			return sharedContextCapturePane.getPendingAutomaticContextContentParts();
		}
		if (inheritedMcqSharedContext != null) {
			return inheritedMcqSharedContext.getContentParts();
		}
		SharedQuestionContext selectedContext = sharedContextCapturePane.getSelectedContext();
		if (selectedContext == null) {
			return List.of();
		}
		return selectedContext.getContentParts();
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

	private BufferedImage extractQuestionPreviewRegion(QuestionRegion region) throws IOException {
		try (PerformanceOperation extraction = performanceRecorder.start("capture.question.region.extract")) {
			try {

				// A fresh crop includes a real PDF page render. Cached preview
				// nodes never enter this method on reorder or unchanged refresh.
				BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
				extraction.resultCount((long) image.getWidth() * image.getHeight());
				return image;
			} catch (IOException | RuntimeException | Error failure) {
				extraction.failed();
				throw failure;
			}
		}
	}

	private BufferedImage extractSharedContextPreviewRegion(SharedQuestionContextRegion region) throws IOException {
		try (PerformanceOperation extraction = performanceRecorder.start("capture.question.shared-context.extract")) {
			try {

				// The same source geometry and PdfSession are reused by
				// cachedSharedContextPreview() without another extraction.
				BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
				extraction.resultCount((long) image.getWidth() * image.getHeight());
				return image;
			} catch (IOException | RuntimeException | Error failure) {
				extraction.failed();
				throw failure;
			}
		}
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
		if (importedQuestion != null && importedQuestion.isSourceCaptureRequired()
				&& pendingQuestionRegions().isEmpty()) {

			// Preserved images remain valid, but they cannot by themselves resolve a
			// requirement created specifically by Question-PDF replacement.
			return "Capture at least one replacement PDF region for this question.";
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

		// Always retrieve the current persisted relationship.
		// A source-specific query avoids whole-corpus reconstruction
		// without risking an indefinitely cached Shared Context.
		return questionCaptureService.findStoredSharedContext(sourceQuestion).orElse(null);
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

		// A failed authoritative lookup must prevent Save, even
		// when a previously selected context still exists in memory.
		if (sharedContextLookupError != null) {
			return sharedContextLookupError;
		}
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
				|| sharedContextCapturePane.hasPendingAutomaticContext() || existingContext != null;
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

		// Completing a correction does not implicitly begin another new Question.
		captureModeGroup.selectToggle(null);
		setLegacyCaptureControlsVisible(false);
		resetQuestionEntry();
		showCaptureIdleMode();
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

		// Finishing or cancelling an edit returns to idle. Starting a new Question
		// remains an explicit teacher action.
		captureModeGroup.selectToggle(null);
		setLegacyCaptureControlsVisible(false);
		resetQuestionEntry();
		showCaptureIdleMode();
		return editCompletedHandler;
	}

	private void finishQuestionSaveBusy() {
		if (!questionSaveInProgress) {
			return;
		}

		// Idempotent cleanup is required because warning paths
		// dismiss the overlay before entering a modal dialog.
		questionSaveInProgress = false;
		try {
			setDisable(false);
			refreshSaveButtonState();
		} finally {
			questionSaveBusyHandler.accept(false);
		}
	}

	private void finishSharedContextRecapture(Runnable completedHandler) {

		// Shared Context correction is complete. Return to the idle Question
		// workspace rather than silently beginning new-Question capture.
		setSharedContextCorrectionVisible(false);
		resetQuestionEntry();
		showCaptureIdleMode();
		refreshImportedQuestions();
		completedHandler.run();
	}

	private void handleIndependentMcqSharedContextOptionChanged(boolean selected) {
		mcqContinuationSelected = selected;
		if (!selected) {

			// Unticking before Save abandons a newly staged MCQ context.
			if (mcqSharedContextCaptureActive) {
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

			// This Question already inherits context. Selection therefore means only
			// that the same context should continue to the following MCQ.
			showSharedContextStatus("Using shared context from the previous question — "
					+ "it will also continue to the following question.");
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
	}

	private void handleManualResponseTypeSelection() {

		// Manual response-type choice takes precedence over later conservative
		// inference for Mixed or unresolved booklet formats.
		responseTypeManuallySelected = true;
	}

	private void handleMarksChanged() {

		// Mixed-book inference may become decisive when marks change, but marks
		// changes caused by selecting MCQ must not recursively change the selection.
		if (!updatingResponseTypeSelection) {
			applyAutomaticResponseType();
		}
		refreshSaveButtonState();
	}

	private void handleMultipartSharedContextOptionChanged(boolean selected) {

		// Multipart Written Response uses SourceQuestion semantics.
		// It must not retain independent-MCQ continuation state.
		mcqContinuationSelected = false;
		inheritedMcqSharedContext = null;
		String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
		if (!selected) {
			if (sharedContextCapturePane.hasPendingAutomaticRegion() && !sharedContextCapturePane.isCaptureMode()) {

				// Transfer the accepted context back into ordinary Question
				// content rather than discarding its captured geometry.
				QuestionRegion region = sharedContextCapturePane.takePendingAutomaticRegion();
				int insertionIndex = Math.min(sharedContextOriginalContentIndex, pendingContentParts.size());
				pendingContentParts.add(insertionIndex, new PdfQuestionContentPart(region));
				refreshRegionPreviews();
				setRegionCountLabel(pendingContentParts.size());
				showQuestionPendingStatus();
			} else {

				// Preserve the existing behaviour for a context whose
				// first PDF selection has not yet been accepted.
				sharedContextCapturePane.cancelAutomaticContext();
				refreshSharedContextPreview();
			}
			hideSharedContextStatus();
			updateQuestionCodeLock();
			return;
		}
		if (sourceCode == null) {
			setSharedContextCheckBoxSelected(false);
			return;
		}
		String label = "Question " + sourceCode + " context";

		// The first accepted PDF region may already belong to the
		// Question body. Move only that region, preserving the relative
		// order and source of every remaining content part.
		for (int index = 0; index < pendingContentParts.size(); index++) {
			QuestionContentPart part = pendingContentParts.get(index);
			if (!(part instanceof PdfQuestionContentPart pdfPart)) {
				continue;
			}
			sharedContextCapturePane.stageAcceptedQuestionRegion(label, pdfPart.region());

			// Remove the ordinary-content reference only after the
			// Shared Context staging operation has succeeded.
			pendingContentParts.remove(index);
			sharedContextOriginalContentIndex = index;
			refreshRegionPreviews();
			setRegionCountLabel(pendingContentParts.size());
			showQuestionPendingStatus();
			updateQuestionCodeLock();
			return;
		}

		// No accepted PDF region exists. Retain the established workflow
		// for transferring an unaccepted selection or capturing a new one.
		if (!beginAutomaticSharedContextCapture(label)) {
			setSharedContextCheckBoxSelected(false);
			return;
		}
		sharedContextOriginalContentIndex = 0;
		updateQuestionCodeLock();
	}

	private void handleQuestionCodeChanged(String newCode) {
		try (PerformanceOperation operation = performanceRecorder.start("capture.question.code-change")) {
			try {
				if (legacySplitCaptureState != null) {

					// Confirmed split metadata bypasses ordinary inference.
					measureQuestionCodePhase("capture.question.code.duplicate", operation.id(),
							() -> refreshQuestionCodeStatus(newCode));
					measureQuestionCodePhase("capture.question.code.controls", operation.id(),
							this::refreshSaveButtonState);
					operation.resultCount(newCode == null ? 0 : newCode.length());
					return;
				}

				// Pass the active inference ID into nested repository
				// measurements, including reads on the JavaFX thread.
				measureQuestionCodePhase("capture.question.code.infer", operation.id(), inferenceId -> {
					if (editingQuestion != null && !loadingQuestionEdit && !editingSourceMatches(newCode)) {
						sharedContextCapturePane.selectContext(null);
					}
					applyAutomaticResponseType();
					refreshSharedContextControls(inferenceId);
				});
				measureQuestionCodePhase("capture.question.code.duplicate", operation.id(),
						() -> refreshQuestionCodeStatus(newCode));

				// Keep an existing pending-capture message consistent
				// with the current candidate Question number.
				refreshPendingQuestionCodeStatus(newCode);
				measureQuestionCodePhase("capture.question.code.controls", operation.id(),
						this::refreshSaveButtonState);

				// Question codes themselves are never written to diagnostics.
				operation.resultCount(newCode == null ? 0 : newCode.length());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
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
				handleIndependentMcqSharedContextOptionChanged(selected);
				return;
			}
			handleMultipartSharedContextOptionChanged(selected);
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
		curriculumSelectorPane.setClassificationControlsDisabled(true);
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
		if (question != null) {

			// Publish the intended target before booklet activation. Activating a different
			// Working Subject can rebuild this queue.
			importedQuestion = question;
			if (!importedQuestionActivationHandler.test(question)) {
				importedQuestion = previousQuestion;
				restoreImportedQuestionSelection(previousQuestion);
				return;
			}
			if (importedQuestion == null || importedQuestion.getId() != question.getId()) {
				importedQuestion = question;
			}
		} else {
			importedQuestion = null;
		}
		clearRegions();
		sharedContextCapturePane.refreshForCurrentBooklet();
		if (importedQuestion == null) {
			resetQuestionEntry();
			if (importedCaptureMode) {
				showImportedQueueMode();
			} else {
				showNewQuestionMode();
			}
			refreshSaveButtonState();
			return;
		}
		if (importedQuestion.isSourceCaptureRequired()) {

			// Replacement has already deleted obsolete PDF regions. Any remaining content
			// is independent image content and must form the starting assembly for
			// recapture rather than being discarded.
			pendingContentParts.addAll(importedQuestion.getContentParts());
			refreshRegionPreviews();
			setRegionCountLabel(pendingContentParts.size());
		}
		showImportedQuestionMode(importedQuestion);
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

	private void measureQuestionCodePhase(String name, long parentOperationId, LongConsumer action) {
		try (PerformanceOperation operation = performanceRecorder.start(name, parentOperationId)) {
			try {
				action.accept(operation.id());
				operation.resultCount(1);
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	private void measureQuestionCodePhase(String name, long parentOperationId, Runnable action) {
		measureQuestionCodePhase(name, parentOperationId, _ -> action.run());
	}

	private <T> T measureQuestionSaveSql(String phase, Supplier<T> action) {
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(action, "action");
		if (!Boolean.getBoolean("eqb.performance.sql.enabled")) {
			return action.get();
		}
		SqliteReadProbe probe = SqliteReadProbe.begin();
		try (probe) {
			return action.get();
		} finally {
			SqliteReadProbe.Snapshot snapshot = probe.snapshot();

			// Report JDBC executions rather than assuming each execute
			// corresponds to exactly one SQL statement.
			System.out.printf(
					"QUESTION_SAVE_SQL phase=%s connections=%d " + "executions=%d rows=%d questions=%d "
							+ "connection_ms=%.3f sql_ms=%.3f%n",
					phase, snapshot.connectionsOpened(), snapshot.executions(), snapshot.rowsRead(),
					snapshot.questionsReconstructed(), snapshot.connectionOpenNanos() / 1_000_000.0,
					snapshot.executionNanos() / 1_000_000.0);
		}
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

	private boolean needsSharedContextReconciliation(SourceQuestion sourceQuestion, SharedQuestionContext sharedContext,
			List<Question> questions) {
		long sourceId = sourceQuestion.getId();
		long bookletId = sourceQuestion.getBooklet().getId();
		boolean missingRelationship = false;
		if (sharedContext.getBooklet().getId() != bookletId) {
			throw new IllegalStateException("Shared context belongs to another booklet");
		}
		for (Question question : questions) {
			if (!question.hasSourceQuestion() || question.getSourceQuestion().getId() != sourceId) {
				continue;
			}

			// Preserve booklet identity checks before deciding whether
			// a database relationship update can be skipped.
			if (question.getBooklet().getId() != bookletId
					|| question.getSourceQuestion().getBooklet().getId() != bookletId) {
				throw new IllegalStateException(
						"Question " + question.getId() + " is linked to a source question " + "from another booklet");
			}
			if (!question.hasSharedContext()) {
				missingRelationship = true;
				continue;
			}
			if (question.getSharedContext().getId() != sharedContext.getId()) {
				throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
						+ " has inconsistent shared context links");
			}
			if (question.getSharedContext().getBooklet().getId() != bookletId) {
				throw new IllegalStateException("Shared context belongs to another booklet");
			}
		}
		return missingRelationship;
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

		// The current PDF selection always has priority. The user must either
		// accept or cancel it before the clipboard image can be routed.
		if (currentSelection != null) {
			showAlert(Alert.AlertType.INFORMATION, "PDF selection pending.",
					"Add the current PDF selection or click elsewhere in the PDF to cancel it before pasting an image.");
			return;
		}
		if (legacySplitCaptureState != null) {
			showAlert(Alert.AlertType.INFORMATION, "Clipboard images are unavailable during legacy split capture.",
					"Complete this legacy split using PDF regions.");
			return;
		}
		if (importedQuestion != null && !importedQuestion.getContentParts().isEmpty()
				&& !importedQuestion.isSourceCaptureRequired()) {
			showAlert(Alert.AlertType.INFORMATION, "Question content already captured.",
					"This imported Question already has body content.");
			return;
		}
		if (sharedContextCapturePane.isCaptureMode() && !sharedContextCapturePane.canAcceptAutomaticClipboardImage()) {
			showAlert(Alert.AlertType.INFORMATION, "Shared Context selection pending.",
					"Accept or clear the Shared Context PDF selection " + "before adding a clipboard image.");
			return;
		}
		try {
			byte[] pngBytes = clipboardImageReader.readPng().orElse(null);
			if (pngBytes == null) {
				showAlert(Alert.AlertType.INFORMATION, "No image on clipboard.",
						"Copy or snip an image, then click Add From Clipboard again.");
				refreshClipboardImageAvailability();
				return;
			}

			// Automatic Shared Context capture uses the existing Question-side
			// Add From Clipboard button. No separate UI button is introduced.
			if (sharedContextCapturePane.canAcceptAutomaticClipboardImage()) {
				sharedContextCapturePane.stageAcceptedQuestionImage(pngBytes);
				try {
					Clipboard.getSystemClipboard().clear();
				} catch (RuntimeException exception) {
					saveStatusLabel.setText("Shared context accepted, but clipboard could not be cleared.");
				}
				completeAutomaticSharedContextCapture();
				return;
			}
			ImageQuestionContentPart imagePart = new ImageQuestionContentPart(pngBytes);
			if (pendingContentParts.contains(imagePart)) {
				refreshClipboardImageAvailability();
				return;
			}
			pendingContentParts.add(imagePart);
			refreshRegionPreviews();
			setRegionCountLabel(pendingContentParts.size());
			showQuestionPendingStatus();
			updateQuestionCodeLock();
			try {
				Clipboard.getSystemClipboard().clear();
			} catch (RuntimeException exception) {
				saveStatusLabel.setText("Image accepted, but the clipboard could not be cleared.");
			}
			refreshSaveButtonState();
			refreshClipboardImageAvailability();
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
		if (!sharedContextCapturePane.hasPendingAutomaticContext()) {
			return null;
		}
		return new SqliteQuestionCaptureService.PendingSharedContext(
				sharedContextCapturePane.getPendingAutomaticContextLabel(),
				sharedContextCapturePane.getPendingAutomaticContextContentParts());
	}

	private void persistLegacyQuestionSplit(SplitRequest request) {
		try {
			beginQuestionSaveBusy();
			saveStatusLabel.setText("Saving split Question " + request.sourceQuestionCode() + "...");
			Task<LegacySplitSaveResult> saveTask = createLegacySplitSaveTask(request);
			saveTask.setOnSucceeded(_ -> completeLegacyQuestionSplit(saveTask.getValue()));
			saveTask.setOnFailed(_ -> {

				// Keep staged split regions available for retry.
				saveStatusLabel.setText("Split save failed — staged parts retained");
				finishQuestionSaveBusy();
				showAlert(Alert.AlertType.ERROR, "Question split could not be saved.",
						"The original Question remains unchanged. " + "The staged split regions have been retained.");
			});
			saveTask.setOnCancelled(_ -> {
				saveStatusLabel.setText("Split save cancelled — staged parts retained");
				finishQuestionSaveBusy();
			});
			Thread saveThread = new Thread(saveTask, "legacy-question-split-save");
			saveThread.setDaemon(true);
			saveThread.start();
		} catch (RuntimeException | Error failure) {
			finishQuestionSaveBusy();
			throw failure;
		}
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
	// Validate and persist on the background worker, then reconstruct
	// the complete Question corpus once after a successful commit.
	private QuestionSaveResult persistQuestionCapture(SqliteQuestionCaptureService.Request request,
			List<Question> beforeSaveSnapshot, long parentOperationId) {
		String validationError;
		try (PerformanceOperation validationOperation = performanceRecorder.start("capture.question.save.validate",
				parentOperationId)) {
			try (PerformanceOperation readOperation = performanceRecorder.start("capture.question.save.read-before",
					validationOperation.id())) {
				try {
					validationError = measureQuestionSaveSql("validate", () -> validateStoredQuestion(request));

					// No full Question reconstruction is required
					// for scoped pre-save validation.
					readOperation.resultCount(0);
				} catch (RuntimeException | Error failure) {
					readOperation.failed();
					validationOperation.failed();
					throw failure;
				}
			}
			if (validationError != null) {
				validationOperation.failed();
			} else {
				validationOperation.resultCount(1);
			}
		}
		if (validationError != null) {
			return new QuestionSaveResult(null, beforeSaveSnapshot, validationError, null);
		}
		Question saved;
		try (PerformanceOperation persistenceOperation = performanceRecorder.start("capture.question.save.persist",
				parentOperationId)) {
			try {
				saved = measureQuestionSaveSql("persist", () -> questionCaptureService.save(request));
				persistenceOperation.resultCount(1);
			} catch (RuntimeException | Error failure) {
				persistenceOperation.failed();
				throw failure;
			}
		}

		// A successful return from save() establishes that
		// the transaction committed.
		try (PerformanceOperation refreshOperation = performanceRecorder.start("capture.question.save.refresh",
				parentOperationId)) {
			try {
				List<Question> refreshed = measureQuestionSaveSql("refresh", questionRepository::findAll);
				refreshOperation.resultCount(refreshed.size());
				return new QuestionSaveResult(saved, refreshed, null, null);
			} catch (RuntimeException refreshFailure) {
				refreshOperation.failed();

				// The transaction already committed. A failed refresh
				// must not be presented as an unsuccessful save.
				List<Question> fallback = new ArrayList<>(beforeSaveSnapshot);
				fallback.removeIf(question -> question.getId() == saved.getId());
				fallback.add(saved);
				return new QuestionSaveResult(saved, List.copyOf(fallback), null, refreshFailure);
			}
		}
	}

	private boolean questionReadyToSave() {
		try {
			return findValidationError() == null;
		} catch (IllegalStateException exception) {

			// Inconsistent persisted relationships must never make Save available.
			return false;
		}
	}

	private int reconcileKnownSharedContexts(List<Question> questions, long parentOperationId) {
		Objects.requireNonNull(questions, "questions");
		Set<Long> processedSourceQuestionIds = new HashSet<>();
		int updatedRelationships = 0;
		try (PerformanceOperation scan = performanceRecorder.start("capture.question.imported.reconcile.scan",
				parentOperationId)) {
			try {
				for (Question question : questions) {
					if (!question.hasSourceQuestion() || !question.hasSharedContext()) {
						continue;
					}
					SourceQuestion sourceQuestion = question.getSourceQuestion();
					if (!processedSourceQuestionIds.add(sourceQuestion.getId())) {
						continue;
					}

					// Detect conflicting sibling contexts using the
					// authoritative snapshot before attempting a repair.
					SharedQuestionContext sharedContext = findSharedContextForSourceQuestion(sourceQuestion, questions);
					if (sharedContext == null) {
						continue;
					}
					boolean needsRepair = needsSharedContextReconciliation(sourceQuestion, sharedContext, questions);
					if (!needsRepair) {

						// All sibling Questions already refer to the
						// same context. No transaction is necessary.
						continue;
					}
					try (PerformanceOperation apply = performanceRecorder
							.start("capture.question.imported.reconcile.apply", scan.id())) {
						try {
							int updated = questionRepository.applySharedContextToSourceQuestion(sourceQuestion,
									sharedContext);
							updatedRelationships += updated;
							apply.resultCount(updated);
						} catch (RuntimeException | Error failure) {
							apply.failed();
							throw failure;
						}
					}
				}
				scan.resultCount(processedSourceQuestionIds.size());
				return updatedRelationships;
			} catch (RuntimeException | Error failure) {
				scan.failed();
				throw failure;
			}
		}
	}

	private void recordImportedEntryMetric(String operation, long parentOperationId, long value) {
		try (PerformanceOperation measurement = performanceRecorder.start(operation, parentOperationId)) {
			measurement.resultCount(value);
		}
	}

	private void recordImportedEntrySql(long parentOperationId, SqliteReadProbe.Snapshot snapshot) {
		recordImportedEntryMetric("capture.question.imported.sqlite.connections", parentOperationId,
				snapshot.connectionsOpened());
		recordImportedEntryMetric("capture.question.imported.sqlite.executions", parentOperationId,
				snapshot.executions());
		recordImportedEntryMetric("capture.question.imported.sqlite.questions", parentOperationId,
				snapshot.questionsReconstructed());
		recordImportedEntryMetric("capture.question.imported.sqlite.rows", parentOperationId, snapshot.rowsRead());
		recordImportedEntryMetric("capture.question.imported.sqlite.open-us", parentOperationId,
				snapshot.connectionOpenNanos() / 1_000);
		recordImportedEntryMetric("capture.question.imported.sqlite.execute-us", parentOperationId,
				snapshot.executionNanos() / 1_000);
	}

	private void refreshImportedQuestions(List<Question> questions) {
		Objects.requireNonNull(questions, "questions");

		// Preserve the complete authoritative snapshot for Question
		// validation. Filtering affects presentation, not persistence.
		questionSnapshot = List.copyOf(questions);
		Question selected = importedQuestion;
		ExamBooklet scopedBooklet = importedQuestionBookletScope;
		Long scopedBookletId = scopedBooklet == null ? null : Long.valueOf(scopedBooklet.getId());
		List<Question> awaitingCapture = awaitingCaptureForWorkingSubject(questionSnapshot, workingSubject,
				scopedBookletId);
		if (scopedBooklet == null) {
			importedQuestionQueueLabel.setText("Question(s) awaiting capture");
		} else {
			Exam exam = scopedBooklet.getExam();

			// Expose source provenance explicitly. In particular,
			// External, Mock and Sample must not look interchangeable.
			importedQuestionQueueLabel.setText("Question(s) awaiting capture — %s %d — %s — %s"
					.formatted(exam.getProvider().getName(), exam.getYear(), exam.getName(), scopedBooklet.getName()));
		}
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getItems().setAll(awaitingCapture);
			Question matchingSelection = null;
			if (selected != null) {
				for (Question question : awaitingCapture) {

					// Retain selection only when the persisted Question
					// still belongs to the current operational scope.
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

		// An empty booklet queue must not fall back to unresolved
		// Questions from other booklets or Examinations.
		updateImportedCaptureAvailability(!awaitingCapture.isEmpty());
	}

	private void refreshIndependentMcqSharedContextControls() {

		// A stale automatic multipart capture cannot survive a transition to
		// independent MCQ semantics.
		if (!mcqSharedContextCaptureActive && sharedContextCapturePane.hasUnsavedContextCapture()) {
			sharedContextCapturePane.cancelAutomaticContext();
		}
		showMcqSharedContextOption();
	}

	private void refreshMultipartSharedContextControls(long parentOperationId) {
		String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
		if (sourceCode == null) {
			refreshUnresolvedImportedSharedContext();
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
		SourceQuestion sourceQuestion;
		try (PerformanceOperation lookup = performanceRecorder.start("capture.question.code.context.source",
				parentOperationId)) {
			try {
				sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode).orElse(null);
				lookup.resultCount(sourceQuestion == null ? 0 : 1);
			} catch (RuntimeException | Error failure) {
				lookup.failed();
				throw failure;
			}
		}
		if (sourceQuestion != null && showStoredSharedContext(sourceCode, sourceQuestion, parentOperationId)) {
			return;
		}
		boolean required = importedQuestion != null && importedQuestion.isSharedContextUnresolved();
		showSharedContextOption(sourceCode, required);
	}

	private void refreshPendingQuestionCodeStatus(String newCode) {
		String currentStatus = saveStatusLabel.getText();
		boolean pendingMessage = currentStatus.startsWith("Pending ") || currentStatus.startsWith("Question pending");

		// Do not overwrite save outcomes, PDF-selection instructions,
		// editing messages or Shared Context workflow messages.
		if (!pendingMessage && !currentStatus.isBlank()) {
			return;
		}
		String code = newCode == null ? "" : newCode.trim();
		if (code.isEmpty() || questionCodeStatusLabel.isVisible()) {
			if (pendingMessage) {
				saveStatusLabel.setText("");
			}
			return;
		}

		// Restore pending status after a duplicate or empty candidate
		// is corrected, provided this is active new-Question capture.
		if (pendingMessage || (currentStatus.isBlank() && newQuestionCaptureActive && !pendingContentParts.isEmpty())) {
			showQuestionPendingStatus();
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

	private void refreshQuestionContentActionState() {
		boolean compatibleSelectionPending = sharedContextCapturePane.isCaptureMode()
				? sharedContextCapturePane.hasCurrentSelection()
				: currentSelection != null;
		addRegionButton.setDisable(questionSaveInProgress || !compatibleSelectionPending);

		// Both Question and automatic Shared Context image capture use
		// the same button and the same image-only clipboard availability test.
		pasteImageButton.setDisable(!canAcceptClipboardImage());
	}

	private void refreshQuestionSaveActionState() {
		if (questionSaveInProgress) {
			saveQuestionButton.setDisable(true);
			return;
		}

		// The shared content action changes meaning while the current PDF selection is
		// owned by automatic Shared Context capture.
		addRegionButton.setText(sharedContextCapturePane.isCaptureMode() ? "Add Context" : "Add Region");
		saveQuestionButton.setDisable(!questionReadyToSave());
	}

	private void refreshRegionPreviews() {
		PdfSession currentSession = examPdfSessionSupplier.get();
		if (currentSession != contentPreviewSession) {
			contentPreviewNodes.clear();
			contentPreviewSession = currentSession;
		}

		// Release cached Question previews that are no longer accepted.
		contentPreviewNodes.keySet()
				.removeIf(cachedPart -> pendingContentParts.stream().noneMatch(part -> part == cachedPart));

		// The Shared Context preview always precedes the editable Question body.
		regionPreviewBox.getChildren().clear();
		List<SharedContextContentPart> sharedParts = currentSharedContextContentParts();
		VBox sharedPreview = cachedSharedContextPreview(sharedParts);
		if (sharedPreview != null) {
			regionPreviewBox.getChildren().add(sharedPreview);
		}
		for (int index = 0; index < pendingContentParts.size(); index++) {
			addContentPreview(pendingContentParts.get(index), index);
		}
		updateRegionsScrollPane();
	}

	private void refreshSaveButtonState() {

		// Content actions and persistence readiness derive independently from the same
		// current Question-capture state.
		refreshQuestionContentActionState();
		refreshQuestionSaveActionState();
	}

	private void refreshSharedContextControls() {
		refreshSharedContextControls(0);
	}

	private void refreshSharedContextControls(long parentOperationId) {

		// Every refresh re-evaluates the authoritative relationship.
		// An error from a previous Question code must not persist
		// after the user changes code or the stored data is corrected.
		sharedContextLookupError = null;
		if (isNewIndependentMcqCapture()) {
			refreshIndependentMcqSharedContextControls();
		} else {
			clearIndependentMcqSharedContextState();
			refreshMultipartSharedContextControls(parentOperationId);
		}
		try (PerformanceOperation preview = performanceRecorder.start("capture.question.code.context.preview",
				parentOperationId)) {
			try {
				refreshSharedContextPreview();
				preview.resultCount(1);
			} catch (RuntimeException | Error failure) {
				preview.failed();
				throw failure;
			}
		}
	}

	private void refreshSharedContextPreview() {

		// Replace only the Shared Context preview; ordinary Question
		// content retains its existing preview nodes and editing controls.
		regionPreviewBox.getChildren().removeIf(node -> "question-shared-context-preview".equals(node.getId()));
		List<SharedContextContentPart> sharedParts = currentSharedContextContentParts();
		VBox sharedPreview = cachedSharedContextPreview(sharedParts);
		if (sharedPreview != null) {
			regionPreviewBox.getChildren().addFirst(sharedPreview);
		}
		updateRegionsScrollPane();
	}

	private void refreshUnresolvedImportedSharedContext() {
		if (importedQuestion == null || !importedQuestion.isSharedContextUnresolved()) {
			hideSharedContextControls();
			return;
		}
		hideSharedContextControls();
		boolean started = sharedContextCapturePane
				.beginAutomaticContext("Question " + importedQuestion.getQuestionCode() + " context");
		if (started) {
			showSharedContextRequiredStatus("Shared context required — capture it as the first region.");
		}
	}

	private void rejectImportedCaptureEntry() {
		if (!importedCaptureMode) {

			// A first-entry failure has not activated imported capture.
			// Preserve any existing non-imported workflow selection.
			restoreCaptureModeToggle();
			return;
		}

		// A failure during re-entry invalidates the currently exposed
		// imported queue. It must not remain editable or selected.
		clearImportedCaptureCompletion();
		questionEditCompletedHandler = () -> {
		};
		editingQuestion = null;
		importedCaptureMode = false;
		importedQuestion = null;
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(null);
		} finally {
			refreshingImportedQuestions = false;
		}
		resetQuestionEntry();
		clearSaveStatus();
		showCaptureIdleMode();

		// Retain the previously known pending-work availability only
		// for displaying the entry action. The complete authoritative
		// queue is rebuilt when the user retries.
		updateImportedCaptureAvailability(!importedQuestionBox.getItems().isEmpty());
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

			// Ordinary new-Question capture deliberately remains active for sequential
			// capture.
			showNewQuestionMode();
			return;
		}
		if (importedQuestionBox.getItems().isEmpty()) {

			// The last imported/incomplete Question has been completed.
			// refreshImportedQuestions() has already returned the workflow to idle and
			// hidden its entry point.
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

		// Continue directly with the next remaining imported/incomplete Question.
		loadImportedQuestion(nextQuestion);
	}

	private void resetQuestionEntry() {
		resetQuestionSharedContextState();
		resetQuestionMetadataState();
		clearRegions();
		curriculumSelectorPane.clearClassificationBelowSubject();
		refreshSaveButtonState();
	}

	private void resetQuestionMetadataState() {
		questionCodeField.clear();
		questionCodeField.setDisable(false);
		marksField.clear();

		// Every new Question begins without a retained manual response-type choice.
		// Booklet policy may immediately restore the appropriate automatic state.
		responseTypeManuallySelected = false;
		selectResponseType(null);
		setResponseTypeDisabled(false);
		applyAutomaticResponseType();
		refreshSharedContextControls();
	}

	private void resetQuestionSharedContextState() {

		// No transient Shared Context or MCQ continuation choice
		// carries into the next Question.
		mcqSharedContextCaptureActive = false;
		mcqContinuationSelected = false;
		inheritedMcqSharedContext = null;
		sharedContextOriginalContentIndex = 0;
		sharedContextCapturePane.clearForQuestion();
		hideSharedContextControls();
	}

	private void restoreCaptureModeToggle() {
		if (editingQuestion != null) {
			captureModeGroup.selectToggle(null);
			return;
		}
		if (importedCaptureMode) {
			selectCaptureModeToggle(true);
			return;
		}
		if (newQuestionCaptureActive) {
			selectCaptureModeToggle(false);
			return;
		}

		// Idle capture has no selected mode.
		captureModeGroup.selectToggle(null);
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
		Integer marks = QuestionCaptureValidator.parsePositiveMarks(marksField.getText());
		if (marks == null) {
			showAlert(Alert.AlertType.WARNING, "Question is incomplete.", "Marks must be a positive whole number.");
			return;
		}
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
				captureOperation(), bookletSupplier.get(), existingQuestion, questionCodeField.getText().trim(), marks,
				List.copyOf(pendingContentParts), curriculumSelectionModel.getClassification(), selectedResponseType(),
				sharedContextCapturePane.getSelectedContext(), pendingSharedContextForSave(),
				continueSharedContextToNextMcq());

		// This snapshot belongs to the FX thread. It is used only if
		// the worker cannot publish a new authoritative corpus.
		List<Question> beforeSaveSnapshot = List.copyOf(questionSnapshot);
		int savedPreviousImportedIndex = previousImportedIndex;
		boolean savedHadStoredContent = hadStoredContent;
		PerformanceOperation operation = performanceRecorder.start("capture.question.save");
		try {
			beginQuestionSaveBusy();
			saveStatusLabel.setText("Saving " + request.questionCode() + "...");
			Task<QuestionSaveResult> saveTask = createQuestionSaveTask(request, beforeSaveSnapshot, operation.id());
			saveTask.setOnSucceeded(_ -> {
				try {
					QuestionSaveResult result = saveTask.getValue();
					if (result.validationError() != null) {
						operation.failed();
					}
					try (PerformanceOperation publication = performanceRecorder.start("capture.question.save.publish",
							operation.id())) {
						try {
							completeQuestionSave(result, editing, imported, savedPreviousImportedIndex,
									savedHadStoredContent);
							publication.resultCount(result.question() == null ? 0 : 1);
						} catch (RuntimeException | Error failure) {
							publication.failed();
							operation.failed();
							throw failure;
						}
					}
					operation.resultCount(result.question() == null ? 0 : 1);
				} catch (RuntimeException | Error failure) {
					operation.failed();
					throw failure;
				} finally {
					try {
						finishQuestionSaveBusy();
					} finally {
						operation.close();
					}
				}
			});
			saveTask.setOnFailed(_ -> {
				operation.failed();
				try {
					saveStatusLabel.setText("Save failed — current question retained");
					finishQuestionSaveBusy();
				} finally {
					operation.close();
				}
				showAlert(Alert.AlertType.ERROR, "Question could not be saved.", "The question was not saved. "
						+ "Your current question details and " + "accepted content have been retained.");
			});
			saveTask.setOnCancelled(_ -> {
				operation.failed();
				try {
					saveStatusLabel.setText("Save cancelled — current question retained");
					finishQuestionSaveBusy();
				} finally {
					operation.close();
				}
			});
			Thread saveThread = new Thread(saveTask, "question-save");
			saveThread.setDaemon(true);
			saveThread.start();
		} catch (RuntimeException | Error failure) {
			operation.failed();
			try {
				finishQuestionSaveBusy();
			} finally {
				operation.close();
			}
			throw failure;
		}
	}

	private void selectCaptureModeToggle(boolean imported) {
		if (imported) {

			// Imported/incomplete Question work has a visible local mode indicator.
			importedQuestionsModeButton.setSelected(true);
			return;
		}

		// Ordinary new capture has no local mode button. Clearing the imported toggle
		// leaves the task-level route as the only visible new-capture entry point.
		captureModeGroup.selectToggle(null);
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

		// A split may not be interrupted by switching into imported/incomplete work.
		// Ordinary new capture is no longer entered from this pane.
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

	private void showCaptureIdleMode() {
		newQuestionCaptureActive = false;
		captureModeGroup.selectToggle(null);
		setLegacyCaptureControlsVisible(false);

		// Working Subject is application context and remains usable. Only the
		// Question-specific Classification and capture controls become inactive.
		curriculumSelectorPane.setClassificationControlsDisabled(true);
		questionWorkBox.setDisable(true);
		hideImportedClassification();
		hideSharedContextControls();
		saveQuestionButton.setText("Save Question");
		hideCaptureHint();
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
		activateImportedQuestionControls(question);
		configureImportedQuestionSavePresentation(question);
		showQuestionPendingStatus();
		refreshSaveButtonState();
	}

	private void showImportedQueueMode() {

		// The imported-Question chooser remains available, but no Question work is
		// active until an actual imported Question has been selected.
		newQuestionCaptureActive = false;
		questionWorkBox.setDisable(true);
		questionCodeField.setDisable(true);
		marksField.setDisable(true);

		// Imported queue mode has no editable response type until a question is
		// selected.
		selectResponseType(null);
		setResponseTypeDisabled(true);
		curriculumSelectorPane.setSyllabusContextLocked(false);
		curriculumSelectorPane.setClassificationControlsDisabled(true);
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
		newQuestionCaptureActive = true;
		questionWorkBox.setDisable(false);
		curriculumSelectorPane.setClassificationControlsDisabled(false);
		questionCodeField.setDisable(false);

		// Reapply the active booklet's response-type policy whenever ordinary new
		// Question capture becomes active. Single-format booklets remain locked;
		// Mixed booklets retain editable response-type controls.
		applyAutomaticResponseType();

		// The response type may already have been selected while the workspace was
		// idle. In that case no ToggleGroup change event fires here, so explicitly
		// rebuild Shared Context controls for the now-active capture workflow.
		refreshSharedContextControls();
		curriculumSelectorPane.setSyllabusContextLocked(false);
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
		hideImportedClassification();
		saveQuestionButton.setText("Save Question");
		hideCaptureHint();

		// Entering active Question capture must also derive clipboard-dependent action
		// state from the clipboard as it exists now.
		refreshSaveButtonState();
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

		// Shared Context correction is a separate explicit workflow, not new-Question
		// capture.
		newQuestionCaptureActive = false;

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
		curriculumSelectorPane.setClassificationControlsDisabled(true);
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

	private boolean showStoredSharedContext(String sourceCode, SourceQuestion sourceQuestion, long parentOperationId) {
		SharedQuestionContext existingContext;
		try (PerformanceOperation lookup = performanceRecorder.start("capture.question.code.context.lookup",
				parentOperationId)) {
			try {
				existingContext = findSharedContextForSourceQuestion(sourceQuestion);
				lookup.resultCount(existingContext == null ? 0 : 1);
			} catch (IllegalStateException failure) {
				lookup.failed();

				// A conflicting or unreadable persisted relationship is
				// not a normal "context not found" condition.
				// Display the error without interrupting every keystroke
				// with a modal dialog.
				sharedContextLookupError = "Shared Context for Question " + sourceCode + " cannot be resolved: "
						+ failure.getMessage();

				// Never retain a context inherited from an earlier code.
				sharedContextCapturePane.selectContext(null);
				hideSharedContextControls();
				showSharedContextRequiredStatus(sharedContextLookupError);
				return true;
			} catch (RuntimeException | Error failure) {
				lookup.failed();
				throw failure;
			}
		}
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

	private Runnable takeImportedCaptureCompletion(Question question) {
		if (question == null || importedCaptureCompletionQuestionId != question.getId()) {
			return null;
		}

		// Consume the callback before resetAfterQuestionSave advances the imported
		// queue so it can run only once for the requested correction.
		Runnable completedHandler = importedCaptureCompletedHandler;
		clearImportedCaptureCompletion();
		return completedHandler;
	}

	private void updateImportedCaptureAvailability(boolean available) {

		// The entry action is useful only before imported capture has started. Once the
		// queue itself is visible, "Complete Imported Question" would merely re-enter
		// the
		// workflow the user is already using.
		boolean showEntryAction = available && !importedCaptureMode;
		importedQuestionsModeButton.setVisible(showEntryAction);
		importedQuestionsModeButton.setManaged(showEntryAction);
		if (available) {
			return;
		}

		// The selector and its supporting status controls disappear when no relevant
		// imported/incomplete Question remains.
		setLegacyCaptureControlsVisible(false);
		if (!importedCaptureMode) {
			return;
		}

		// Exhausting the imported queue returns Question capture to explicit idle. The
		// user still controls navigation back to the Dashboard through the application
		// header rather than being redirected automatically after each Save.
		importedCaptureMode = false;
		importedQuestion = null;
		captureModeGroup.selectToggle(null);
		resetQuestionEntry();
		clearSaveStatus();
		showCaptureIdleMode();
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
		questionCodeField.setDisable(sharedContextCapturePane.hasPendingAutomaticContext());
	}

	private void updateRegionsScrollPane() {

		// Shared Context can be the only accepted visual material while the Question
		// body is still empty, so visibility follows the actual preview assembly.
		boolean hasContent = !regionPreviewBox.getChildren().isEmpty();
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

		// Only ordinary new capture may add a previously unknown top-level
		// Question. Decide once the code and content are valid, before Save
		// starts any background persistence or busy overlay.
		if (!confirmNewTopLevelQuestionBeyondExpected()) {
			return;
		}
		saveQuestion();
	}

	private String validateStoredQuestion(SqliteQuestionCaptureService.Request request) {
		if (request.operation() != SqliteQuestionCaptureService.Operation.IMPORTED) {
			Long excludedQuestionId = request.existingQuestion() == null ? null : request.existingQuestion().getId();
			boolean duplicate = questionCaptureService.hasConflictingQuestionCode(request.booklet(),
					request.questionCode(), excludedQuestionId);
			if (duplicate) {
				return "Question " + request.questionCode() + " already exists for this booklet.";
			}
		}
		String sourceCode = SourceQuestionCodeParser.derive(request.questionCode());
		SourceQuestion source = sourceCode == null ? null
				: sourceQuestionRepository.findByBookletAndCode(request.booklet(), sourceCode).orElse(null);

		// Use the persisted source-specific relationship lookup
		// introduced by #140. It rejects inconsistent sibling
		// Shared Context links without reading unrelated Questions.
		SharedQuestionContext storedContext = source == null ? null : findSharedContextForSourceQuestion(source);
		boolean contextAvailable = request.selectedSharedContext() != null || request.pendingSharedContext() != null
				|| storedContext != null;
		boolean unresolved = request.existingQuestion() != null
				&& request.existingQuestion().isSharedContextUnresolved();
		return sharedContextValidationError(sourceCode, source, unresolved, contextAvailable);
	}

	private record ContentPreview(VBox container, Label label, Button moveUp, Button moveDown, Button remove) {
	}

	private static final class LegacySplitCaptureState {

		// Staged split definition and progress.
		private int activePartIndex;
		private final Runnable completedHandler;
		private final LegacyQuestionSplitDialog.Result definition;
		private final Question originalQuestion;

		// Completed split parts (initialisation order retained).
		private final List<SplitPart> completedParts = new ArrayList<>();

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
