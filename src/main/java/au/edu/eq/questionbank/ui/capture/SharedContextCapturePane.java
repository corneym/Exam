package au.edu.eq.questionbank.ui.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.SharedQuestionContextRepository;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.beans.binding.Bindings;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Captures and selects reusable shared question context for the active booklet.
 */
public final class SharedContextCapturePane extends VBox {

	private static final double REGION_VIEWPORT_EXTRA_HEIGHT = 4.0;
	private static final double CONTEXT_SELECTOR_WIDTH = 260.0;
	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double REGION_PREVIEW_HORIZONTAL_INSET = 24.0;
	private static final double REGION_PREVIEW_ITEM_SPACING = 5.0;
	private static final double REGIONS_VIEWPORT_HEIGHT = 300.0;
	private static final Insets CAPTURE_PADDING = new Insets(8);

	// Shared Context dependencies and callbacks.
	private final Supplier<ExamBooklet> bookletSupplier;
	private final BooleanSupplier captureStartAllowed;
	private final SharedQuestionContextRepository contextRepository;
	private BiFunction<String, List<SharedQuestionContextRegion>, SharedQuestionContext> correctionSaveHandler;
	private final Supplier<PdfSession> examPdfSessionSupplier;
	private final QuestionExtractor questionExtractor;
	private final Runnable selectionClearHandler;

	// Shared Context capture and editing state.
	private boolean captureMode;
	private SharedQuestionContextRegion currentSelection;
	private SharedQuestionContext editingContext;

	// Controls and initial workflow state (initialisation order retained).
	// Existing context and new capture controls.
	private final ComboBox<SharedQuestionContext> existingContextField = new ComboBox<>();
	private final Button newContextButton = new Button("New Context");
	private final TextField contextLabelField = new TextField();
	private final Label statusLabel = new Label("No shared context selected");
	private final Label contextCaptureHeadingLabel = new Label("New shared context");

	// Pending and accepted region controls.
	private final Button addRegionButton = new Button("Add");
	private final Button clearSelectionButton = new Button("Clear");
	private final Button clearRegionsButton = new Button("Clear Regions");
	private final ImageView currentPreview = new ImageView();
	private final Label regionCountLabel = new Label("Regions: 0");
	private final VBox acceptedRegionBox = new VBox(COMPACT_SPACING);
	private final ScrollPane acceptedRegionScroll = new ScrollPane(acceptedRegionBox);

	// Save and cancel controls.
	private final Button saveContextButton = new Button("Save Context");
	private final Button cancelContextButton = new Button("Cancel");
	private final VBox newContextBox = new VBox(COMPACT_SPACING);
	private final SharedContextCaptureDraft pendingContent = new SharedContextCaptureDraft();
	private Runnable contextEditCompletedHandler = () -> {
	};
	private final QuestionClipboardImageReader clipboardImageReader = new QuestionClipboardImageReader();

	/**
	 * Creates shared-context controls for the active booklet and exam PDF. The
	 * transition callback prevents this pane from taking selection ownership while
	 * incompatible question capture is pending.
	 *
	 * @param contextRepository      shared-context persistence
	 * @param bookletSupplier        supplier of the active Exam booklet
	 * @param questionExtractor      extractor used for region previews
	 * @param examPdfSessionSupplier supplier of the active Exam PDF session
	 * @param selectionClearHandler  callback that clears the shared PDF selection
	 * @param captureStartAllowed    guard for starting shared-context capture
	 */
	public SharedContextCapturePane(SharedQuestionContextRepository contextRepository,
			Supplier<ExamBooklet> bookletSupplier, QuestionExtractor questionExtractor,
			Supplier<PdfSession> examPdfSessionSupplier, Runnable selectionClearHandler,
			BooleanSupplier captureStartAllowed) {
		validateDependencies(contextRepository, bookletSupplier, questionExtractor, examPdfSessionSupplier,
				selectionClearHandler, captureStartAllowed);
		this.contextRepository = contextRepository;
		this.bookletSupplier = bookletSupplier;
		this.questionExtractor = questionExtractor;
		this.examPdfSessionSupplier = examPdfSessionSupplier;
		this.selectionClearHandler = selectionClearHandler;
		this.captureStartAllowed = captureStartAllowed;
		configureControls();
		configureActions();
		buildContent();
		setSpacing(COMPACT_SPACING);
	}

	/**
	 * Accepts the pending selection as the single region of an automatic shared
	 * context capture and returns to normal question-region capture.
	 *
	 * @return {@code true} when a pending automatic selection was accepted
	 */
	boolean acceptAutomaticRegion() {
		if (!captureMode || currentSelection == null) {
			return false;
		}
		if (!pendingContent.isEmpty()) {
			throw new IllegalStateException("Automatic shared context already has content");
		}
		addCurrentRegion();
		leaveCaptureMode();
		return true;
	}

	/**
	 * Accepts one PNG into the current Shared Context draft. Duplicate images are
	 * rejected without altering the draft.
	 *
	 * @param pngBytes encoded PNG data
	 * @return true when a new image was accepted
	 */
	boolean acceptClipboardPng(byte[] pngBytes) {
		if (!captureMode) {
			throw new IllegalStateException("Shared Context capture is not active");
		}
		if (correctionSaveHandler != null) {
			throw new IllegalStateException("Clipboard images are not yet available in source-wide correction");
		}
		if (currentSelection != null) {
			throw new IllegalStateException("Accept or clear the current PDF selection first");
		}
		boolean accepted = pendingContent.addImage(pngBytes);
		if (accepted) {
			refreshRegionPreviews();
			statusLabel.setText(pendingContent.size() + " shared context content part(s) accepted");
		}
		return accepted;
	}

	/**
	 * Accepts a PDF selection while this pane owns capture.
	 *
	 * @param selection the proportional source region
	 * @throws IllegalStateException if shared-context capture is not active
	 */
	void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		if (!captureMode) {
			throw new IllegalStateException("Shared context capture is not active");
		}
		currentSelection = new SharedQuestionContextRegion(selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		showCurrentPreview();
		statusLabel.setText("Shared context selection pending — click Add or Clear");
		setSelectionButtonsEnabled(true);
	}

	/**
	 * Starts automatic capture for a shared context that will be persisted with the
	 * next question save.
	 *
	 * @param label the generated context label
	 * @return {@code true} when capture started
	 */
	boolean beginAutomaticContext(String label) {
		return beginAutomaticContext(label, null);
	}

	/**
	 * Starts automatic shared context capture, optionally taking ownership of an
	 * existing question selection without clearing it from the PDF workspace.
	 *
	 * @param label                the generated context label
	 * @param transferredSelection an existing question region to reinterpret, or
	 *                             {@code null} to await a new selection
	 * @return {@code true} when capture started
	 * @throws IllegalArgumentException if the label is blank or the transferred
	 *                                  region belongs to another booklet
	 */
	boolean beginAutomaticContext(String label, QuestionRegion transferredSelection) {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}

		// A transferred question selection already exists in the PDF workspace.
		// Starting a new automatic capture from scratch must still obey the normal
		// transition guard.
		if (transferredSelection == null && !captureStartAllowed.getAsBoolean()) {
			showWarning("Question selection pending",
					"Add or clear the current question selection " + "before capturing shared context.");
			return false;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			showWarning("Exam details have not been set.", "Select an exam booklet before capturing shared context.");
			return false;
		}
		if (transferredSelection != null && transferredSelection.booklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Transferred selection must belong " + "to the active booklet");
		}
		enterCaptureMode();
		if (transferredSelection == null) {
			clearCurrentSelection();
		} else {
			currentSelection = new SharedQuestionContextRegion(transferredSelection.pageNumber(),
					transferredSelection.x(), transferredSelection.y(), transferredSelection.width(),
					transferredSelection.height());
			currentPreview.setImage(null);
			currentPreview.setVisible(false);
			setSelectionButtonsEnabled(true);
		}
		contextLabelField.setText(label);
		refreshRegionPreviews();
		setNewContextBoxVisible(false);
		if (transferredSelection != null) {
			statusLabel.setText("Shared context selection pending " + "— click Add or Clear");
		}
		return true;
	}

	boolean canAcceptAutomaticClipboardImage() {
		return captureMode && correctionSaveHandler == null && editingContext == null && currentSelection == null;
	}

	/**
	 * Cancels automatic capture and discards its transient regions.
	 */
	void cancelAutomaticContext() {
		clearForQuestion();
	}

	/**
	 * Stages newly captured regions for a source-wide correction. The caller owns
	 * transactional persistence at Save time.
	 *
	 * @param initialLabel     suggested editable context label
	 * @param saveHandler      transactional correction callback
	 * @param completedHandler callback after Save or Cancel
	 * @return true when capture started
	 */
	boolean captureMissingContext(String initialLabel,
			BiFunction<String, List<SharedQuestionContextRegion>, SharedQuestionContext> saveHandler,
			Runnable completedHandler) {
		if (initialLabel == null || initialLabel.isBlank()) {
			throw new IllegalArgumentException("initialLabel must not be blank");
		}
		Objects.requireNonNull(saveHandler, "saveHandler");
		Objects.requireNonNull(completedHandler, "completedHandler");
		if (!captureStartAllowed.getAsBoolean()) {
			showWarning("Question selection pending",
					"Add or clear the current Question selection " + "before capturing Shared Context.");
			return false;
		}
		if (bookletSupplier.get() == null) {
			showWarning("No active booklet",
					"Open the original Question booklet " + "before capturing Shared Context.");
			return false;
		}
		clearContextEditState();
		correctionSaveHandler = saveHandler;
		contextEditCompletedHandler = completedHandler;
		enterCaptureMode();
		clearCurrentSelection();
		contextLabelField.setText(initialLabel);
		pendingContent.clear();
		refreshRegionPreviews();
		contextLabelField.setVisible(true);
		contextLabelField.setManaged(true);
		contextCaptureHeadingLabel.setText("Capture missing Shared Context");
		saveContextButton.setText("Save and Link Context");
		statusLabel.setText("Capture one or more context regions");
		setNewContextBoxVisible(true);
		return true;
	}

	/**
	 * Clears the unaccepted shared-context selection.
	 */
	void clearCurrentSelection() {
		currentSelection = null;
		currentPreview.setImage(null);
		currentPreview.setVisible(false);
		selectionClearHandler.run();
		setSelectionButtonsEnabled(false);
	}

	/**
	 * Clears transient capture and selection state between questions.
	 */
	void clearForQuestion() {
		if (captureMode) {
			cancelNewContext();
		} else {
			clearCurrentSelection();
			pendingContent.clear();
			refreshRegionPreviews();
		}
		existingContextField.getSelectionModel().clearSelection();
		statusLabel.setText("No shared context selected");
	}

	/**
	 * Discards only this pane's local unaccepted selection after another capture
	 * workflow takes ownership of the PDF selection.
	 * <p>
	 * This deliberately does not call the selection-clear handler because the PDF
	 * workspace now contains the new owner's selection rectangle.
	 */
	void discardCurrentSelectionForOwnershipLoss() {

		// Remove the stale shared-context rectangle retained by this pane.
		currentSelection = null;
		currentPreview.setImage(null);
		currentPreview.setVisible(false);
		setSelectionButtonsEnabled(false);
		if (captureMode) {

			// Shared-context capture remains active; only ownership of the previous
			// unaccepted rectangle has been lost.
			statusLabel.setText("Shared context capture active — select a region");
		}
	}

	List<SharedContextContentPart> getPendingAutomaticContextContentParts() {
		if (!hasPendingAutomaticContext()) {
			throw new IllegalStateException("No automatic Shared Context is pending");
		}
		return pendingContent.contentParts();
	}

	String getPendingAutomaticContextLabel() {
		if (!hasPendingAutomaticContext()) {
			throw new IllegalStateException("No automatic Shared Context is pending");
		}
		String label = contextLabelField.getText().trim();
		if (label.isBlank()) {
			throw new IllegalStateException("Automatic Shared Context has no label");
		}
		return label;
	}

	List<SharedQuestionContextRegion> getPendingAutomaticContextRegions() {
		if (!hasPendingAutomaticRegion()) {
			throw new IllegalStateException("No automatic shared context is pending");
		}
		return pendingContent.regions();
	}

	/**
	 * @return the selected persisted context, or {@code null}; accepted automatic
	 *         regions are not yet persisted
	 */
	SharedQuestionContext getSelectedContext() {
		return existingContextField.getValue();
	}

	/**
	 * @return whether an unaccepted rectangle belongs to this capture pane
	 */
	boolean hasCurrentSelection() {
		return currentSelection != null;
	}

	boolean hasPendingAutomaticContext() {
		return !captureMode && correctionSaveHandler == null && editingContext == null && !pendingContent.isEmpty();
	}

	/**
	 * @return whether accepted context regions await persistence, including after
	 *         automatic capture mode ends
	 */
	boolean hasPendingAutomaticRegion() {
		return pendingContent.size() == 1
				&& pendingContent.contentParts().getFirst() instanceof SharedQuestionContextRegion;
	}

	/**
	 * @return whether capture is active or accepted regions remain unsaved
	 */
	boolean hasUnsavedContextCapture() {
		return captureMode || !pendingContent.isEmpty();
	}

	/**
	 * @return whether new PDF selections are currently routed to shared-context
	 *         capture
	 */
	boolean isCaptureMode() {
		return captureMode;
	}

	/**
	 * Starts replacement capture for an existing shared context.
	 *
	 * <p>
	 * The persisted context remains unchanged until the replacement is saved.
	 * Saving preserves the existing context identifier so all linked Questions
	 * continue to share the corrected shared context.
	 * </p>
	 *
	 * @param context          the persisted shared context to replace
	 * @param completedHandler callback run after replacement is saved or cancelled
	 * @return {@code true} when replacement capture started
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the context belongs to another booklet
	 */
	boolean recaptureContext(SharedQuestionContext context, Runnable completedHandler) {
		if (context == null) {
			throw new NullPointerException("context");
		}
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
		}
		if (!captureStartAllowed.getAsBoolean()) {
			showWarning("Question selection pending",
					"Add or clear the current question selection before recapturing shared context.");
			return false;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null || context.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared context must belong to the active booklet");
		}
		editingContext = context;
		contextEditCompletedHandler = completedHandler;
		enterCaptureMode();
		clearCurrentSelection();

		// Replacement capture starts empty. The persisted original remains available
		// until Save successfully replaces it.
		contextLabelField.setText(context.getLabel());

		// Recapture corrects only the stored source regions. The existing context
		// label remains authoritative and is not editable in this workflow.
		contextLabelField.setVisible(false);
		contextLabelField.setManaged(false);
		pendingContent.clear();
		refreshRegionPreviews();
		contextCaptureHeadingLabel.setText("Recapture shared context");
		saveContextButton.setText("Save Replacement");

		// Stored labels are implementation metadata and may be opaque for automatically
		// captured MCQ contexts, so do not expose them as the edit description.
		statusLabel.setText("Recapturing: " + context.getLabel());
		setNewContextBoxVisible(true);
		return true;
	}

	/**
	 * Reloads the contexts belonging to the active booklet after clearing transient
	 * capture state.
	 */
	void refreshForCurrentBooklet() {
		clearForQuestion();
		loadContexts(bookletSupplier.get());
	}

	/**
	 * Selects a persisted context belonging to the active booklet.
	 *
	 * @param context the context to select, or {@code null} to clear selection
	 * @throws IllegalArgumentException if the context belongs to another booklet
	 * @throws IllegalStateException    if it cannot be reloaded
	 */
	void selectContext(SharedQuestionContext context) {
		if (context == null) {
			existingContextField.getSelectionModel().clearSelection();
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null || context.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared context must belong to the active booklet");
		}
		SharedQuestionContext matching = findContextById(context.getId());
		if (matching == null) {
			loadContexts(booklet);
			matching = findContextById(context.getId());
		}
		if (matching == null) {
			throw new IllegalStateException("Shared context could not be found in the active booklet");
		}
		existingContextField.setValue(matching);
	}

	void stageAcceptedQuestionImage(byte[] pngBytes) {
		if (!canAcceptAutomaticClipboardImage()) {
			throw new IllegalStateException("Shared Context capture is not ready for a clipboard image");
		}

		// Preserve the label established when automatic capture began.
		// This also preserves generated independent-MCQ context labels.
		if (contextLabelField.getText().isBlank()) {
			throw new IllegalStateException("Automatic Shared Context has no label");
		}
		if (!pendingContent.isEmpty()) {
			throw new IllegalStateException("Automatic Shared Context already has content");
		}
		if (!pendingContent.addImage(pngBytes)) {
			throw new IllegalStateException("Shared Context image was not accepted");
		}
		clearCurrentSelection();
		leaveCaptureMode();
		refreshRegionPreviews();
		statusLabel.setText("No shared context selected");
	}

	/**
	 * Reclassifies an already accepted Question image as automatic Shared Context.
	 * The caller removes its Question-content reference only after staging
	 * succeeds.
	 *
	 * @param label    generated Shared Context label
	 * @param pngBytes encoded image already accepted as Question content
	 */
	void stageAcceptedQuestionImageContent(String label, byte[] pngBytes) {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		Objects.requireNonNull(pngBytes, "pngBytes");
		if (hasUnsavedContextCapture() || currentSelection != null) {
			throw new IllegalStateException("Another Shared Context capture is already pending");
		}
		if (!pendingContent.addImage(pngBytes)) {
			throw new IllegalStateException("Question image could not be staged as Shared Context");
		}
		existingContextField.getSelectionModel().clearSelection();
		contextLabelField.setText(label);
		refreshRegionPreviews();
		statusLabel.setText("1 shared context image accepted");
	}

	/**
	 * Reclassifies an already accepted Question PDF region as an unsaved automatic
	 * Shared Context region.
	 *
	 * The source geometry is retained without clearing the PDF workspace selection
	 * or starting a new region capture.
	 *
	 * @param label          label for the pending Shared Context
	 * @param acceptedRegion existing accepted Question region
	 */
	void stageAcceptedQuestionRegion(String label, QuestionRegion acceptedRegion) {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		Objects.requireNonNull(acceptedRegion, "acceptedRegion");
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null || acceptedRegion.booklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Accepted region must belong to the active booklet");
		}
		if (hasUnsavedContextCapture() || currentSelection != null) {
			throw new IllegalStateException("Another Shared Context capture is already pending");
		}

		// Stage the accepted region directly. It is not an unaccepted
		// PDF selection, so neither the selection-clear callback nor
		// the normal capture-mode transition should run.
		SharedQuestionContextRegion contextRegion = new SharedQuestionContextRegion(acceptedRegion.pageNumber(),
				acceptedRegion.x(), acceptedRegion.y(), acceptedRegion.width(), acceptedRegion.height());
		existingContextField.getSelectionModel().clearSelection();
		contextLabelField.setText(label);
		pendingContent.addRegion(contextRegion);
		refreshRegionPreviews();
		statusLabel.setText("1 shared context region(s) accepted");
	}

	/**
	 * Returns a single accepted automatic Shared Context image to ordinary Question
	 * capture when the Shared Context checkbox is unticked.
	 *
	 * @return the recovered Question image
	 */
	ImageQuestionContentPart takePendingAutomaticImage() {
		if (!hasPendingAutomaticContext() || currentSelection != null || pendingContent.size() != 1
				|| !(pendingContent.contentParts().getFirst() instanceof ImageSharedContextContentPart image)) {
			throw new IllegalStateException("No single accepted automatic Shared Context image");
		}
		ImageQuestionContentPart questionImage = new ImageQuestionContentPart(image.pngBytes());
		pendingContent.clear();
		contextLabelField.clear();
		refreshRegionPreviews();
		statusLabel.setText("No shared context selected");
		return questionImage;
	}

	/**
	 * Removes the single accepted automatic Shared Context region from transient
	 * capture and returns it as a Question region.
	 *
	 * Does not clear the PDF workspace selection or alter any persisted Shared
	 * Context.
	 *
	 * @return the accepted region with its original source geometry
	 * @throws IllegalStateException if no single accepted automatic region is
	 *                               available
	 */
	QuestionRegion takePendingAutomaticRegion() {
		if (captureMode || currentSelection != null || !hasPendingAutomaticRegion() || editingContext != null
				|| correctionSaveHandler != null) {
			throw new IllegalStateException("No single accepted automatic Shared Context region");
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			throw new IllegalStateException("No active booklet");
		}
		SharedQuestionContextRegion region = pendingContent.regions().getFirst();

		// Preserve source coordinates when returning automatic
		// Shared Context content to ordinary Question capture.
		QuestionRegion questionRegion = new QuestionRegion(booklet, region.pageNumber(), region.x(), region.y(),
				region.width(), region.height());
		pendingContent.clear();
		contextLabelField.clear();
		refreshRegionPreviews();
		statusLabel.setText("No shared context selected");
		return questionRegion;
	}

	private void addCurrentRegion() {
		if (currentSelection == null) {
			return;
		}
		pendingContent.addRegion(currentSelection);
		currentSelection = null;
		currentPreview.setImage(null);
		currentPreview.setVisible(false);
		selectionClearHandler.run();
		setSelectionButtonsEnabled(false);
		refreshRegionPreviews();
		statusLabel.setText(pendingContent.size() + " shared context region(s) accepted");
	}

	private void beginNewContext() {
		if (!captureStartAllowed.getAsBoolean()) {
			showWarning("Question selection pending",
					"Add or clear the current question selection before capturing shared context.");
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			showWarning("Exam details have not been set.", "Select an exam booklet before capturing shared context.");
			return;
		}

		// Ordinary creation must not retain state from an earlier replacement.
		clearContextEditState();
		enterCaptureMode();
		contextLabelField.clear();

		// A newly created shared context still requires its own label.
		contextLabelField.setVisible(true);
		contextLabelField.setManaged(true);
		clearCurrentSelection();
		refreshRegionPreviews();
		contextCaptureHeadingLabel.setText("New shared context");
		saveContextButton.setText("Save Context");
		statusLabel.setText("Capturing new shared context");
		setNewContextBoxVisible(true);
	}

	private void buildContent() {
		getChildren().addAll(createExistingContextControls(), statusLabel, newContextBox);
	}

	private void buildNewContextBox() {
		newContextBox.getChildren().addAll(contextCaptureHeadingLabel, contextLabelField,
				createCurrentSelectionControls(), currentPreview, regionCountLabel, acceptedRegionScroll,
				createSaveControls());
		newContextBox.setPadding(CAPTURE_PADDING);
		setNewContextBoxVisible(false);
	}

	private void cancelContextCaptureFromUser() {
		Runnable completedHandler = contextEditCompletedHandler;
		cancelNewContext();

		// For ordinary new-context capture this is the no-op handler.
		completedHandler.run();
	}

	private void cancelNewContext() {
		clearCurrentSelection();
		pendingContent.clear();
		refreshRegionPreviews();
		contextLabelField.clear();
		leaveCaptureMode();
		clearContextEditState();
		statusLabel.setText("No shared context selected");
	}

	private void clearContextEditState() {
		correctionSaveHandler = null;
		editingContext = null;
		contextEditCompletedHandler = () -> {
		};
		contextCaptureHeadingLabel.setText("New shared context");
		saveContextButton.setText("Save Context");
		contextLabelField.setVisible(true);
		contextLabelField.setManaged(true);
	}

	private void clearPersistedCaptureState() {
		pendingContent.clear();
		contextLabelField.clear();
		currentPreview.setImage(null);
		currentPreview.setVisible(false);
	}

	private void clearRegions() {
		pendingContent.clear();
		clearCurrentSelection();
		refreshRegionPreviews();
	}

	private void configureActions() {
		newContextButton.setOnAction(_ -> beginNewContext());
		addRegionButton.setOnAction(_ -> addCurrentRegion());
		clearSelectionButton.setOnAction(_ -> clearCurrentSelection());
		clearRegionsButton.setOnAction(_ -> clearRegions());
		saveContextButton.setOnAction(_ -> saveContext());
		cancelContextButton.setOnAction(_ -> cancelContextCaptureFromUser());
		existingContextField.valueProperty().addListener((_, _, newContext) -> updateSelectedContextStatus(newContext));
	}

	private void configureCaptureControls() {
		contextLabelField.setId("shared-context-label");
		contextLabelField.setPromptText("Context label, e.g. Question 24 context");
		addRegionButton.setId("add-shared-context-region");
		addRegionButton
				.setTooltip(new Tooltip("Accept the current PDF selection as another reusable Shared Context region."));
		clearSelectionButton.setId("clear-shared-context-selection");
		clearRegionsButton.setId("clear-shared-context-regions");
		saveContextButton.setId("save-shared-context");
		saveContextButton
				.setTooltip(new Tooltip("Persist the captured regions as reusable Shared Context for this booklet."));
		cancelContextButton.setId("cancel-shared-context");
		regionCountLabel.setId("shared-context-region-count");
		currentPreview.setId("shared-context-current-preview");
		currentPreview.setPreserveRatio(true);

		// Match the accepted-region presentation so the pending selection is previewed
		// at the same scale before and after Add.
		currentPreview.fitWidthProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.max(0.0, newContextBox.getWidth() - REGION_PREVIEW_HORIZONTAL_INSET),
						newContextBox.widthProperty()));
		currentPreview.setSmooth(true);

		// The transient preview should occupy layout space only while an unaccepted
		// PDF selection is actually being previewed.
		currentPreview.setVisible(false);
		currentPreview.managedProperty().bind(currentPreview.visibleProperty());
		acceptedRegionScroll.setFitToWidth(true);
		acceptedRegionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		acceptedRegionScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		acceptedRegionScroll.setMinHeight(0);
		acceptedRegionScroll.setMaxHeight(REGIONS_VIEWPORT_HEIGHT);

		// Grow the accepted-region area with its actual content until the same
		// 300 px scrolling limit used by ordinary Question capture is reached.
		acceptedRegionScroll.prefHeightProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.min(REGIONS_VIEWPORT_HEIGHT,
								acceptedRegionBox.getLayoutBounds().getHeight() + REGION_VIEWPORT_EXTRA_HEIGHT),
						acceptedRegionBox.layoutBoundsProperty()));
		setSelectionButtonsEnabled(false);
	}

	private void configureControls() {
		configureExistingContextField();
		configureCaptureControls();
		buildNewContextBox();
	}

	private void configureExistingContextField() {
		existingContextField.setId("shared-context");
		existingContextField.setPromptText("No shared context");
		existingContextField.setTooltip(
				new Tooltip("Reuse Shared Context already captured for independent Questions in this booklet."));
		existingContextField.setPrefWidth(CONTEXT_SELECTOR_WIDTH);
		existingContextField.setConverter(new StringConverter<>() {

			@Override
			public SharedQuestionContext fromString(String text) {
				return null;
			}

			@Override
			public String toString(SharedQuestionContext context) {
				if (context == null) {
					return "";
				}
				int total = context.getContentParts().size();
				int pdfCount = context.getRegions().size();
				int imageCount = total - pdfCount;

				// Report the authoritative mixed-content inventory,
				// not just its backwards-compatible PDF projection.
				if (imageCount == 0) {
					return String.format("%s — %d region(s)", context.getLabel(), pdfCount);
				}
				if (pdfCount == 0) {
					return String.format("%s — %d image(s)", context.getLabel(), imageCount);
				}
				return String.format("%s — %d region(s), %d image(s)", context.getLabel(), pdfCount, imageCount);
			}
		});
		newContextButton.setId("new-shared-context");
		newContextButton.setTooltip(new Tooltip(
				"Capture reusable source content that can be linked to more than one independent Question."));
	}

	private HBox createCurrentSelectionControls() {
		HBox controls = new HBox(CONTROL_SPACING, new Label("Current selection"), addRegionButton, clearSelectionButton,
				clearRegionsButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createExistingContextControls() {
		HBox controls = new HBox(CONTROL_SPACING, new Label("Shared context"), existingContextField, newContextButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private VBox createImagePreview(ImageSharedContextContentPart part, int index) {
		Image image = new Image(new ByteArrayInputStream(part.pngBytes()));
		if (image.isError()) {
			throw new IllegalStateException("Stored Shared Context PNG cannot be previewed", image.getException());
		}
		ImageView imageView = new ImageView(image);
		imageView.setId("shared-context-image-preview-" + index);
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);
		imageView.fitWidthProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.max(0.0, acceptedRegionScroll.getWidth() - REGION_PREVIEW_HORIZONTAL_INSET),
						acceptedRegionScroll.widthProperty()));
		Label label = new Label("Image " + (index + 1));
		Button removeButton = new Button("Remove");
		removeButton.setId("remove-shared-context-image-" + index);
		removeButton.setOnAction(_ -> removeRegion(index));
		HBox footer = new HBox(CONTROL_SPACING, label, removeButton);
		footer.setAlignment(Pos.CENTER_LEFT);
		return new VBox(REGION_PREVIEW_ITEM_SPACING, imageView, footer);
	}

	private VBox createRegionPreview(SharedQuestionContextRegion region, int regionIndex) {
		try {
			BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
			ImageView imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
			imageView.setId("shared-context-region-preview-" + regionIndex);
			imageView.setPreserveRatio(true);

			// Match ordinary Question-region presentation: use the available pane width
			// and derive the preview height from the source image aspect ratio.
			imageView.fitWidthProperty()
					.bind(Bindings.createDoubleBinding(
							() -> Math.max(0.0, acceptedRegionScroll.getWidth() - REGION_PREVIEW_HORIZONTAL_INSET),
							acceptedRegionScroll.widthProperty()));
			imageView.setSmooth(true);
			Label label = new Label(String.format("Region %d — Page %d", regionIndex + 1, region.pageNumber()));
			Button removeButton = new Button("Remove");
			removeButton.setId("remove-shared-context-region-" + regionIndex);
			removeButton.setOnAction(_ -> removeRegion(regionIndex));

			// Keep the controls below the full-width preview rather than consuming
			// horizontal space beside the captured image.
			HBox footer = new HBox(CONTROL_SPACING, label, removeButton);
			footer.setAlignment(Pos.CENTER_LEFT);
			return new VBox(REGION_PREVIEW_ITEM_SPACING, imageView, footer);
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview shared context region", e);
		}
	}

	private HBox createSaveControls() {
		HBox controls = new HBox(CONTROL_SPACING, saveContextButton, cancelContextButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private void enterCaptureMode() {
		captureMode = true;
		existingContextField.getSelectionModel().clearSelection();
		existingContextField.setDisable(true);
		newContextButton.setDisable(true);
		pendingContent.clear();
	}

	private SharedQuestionContext findContextById(long id) {
		for (SharedQuestionContext context : existingContextField.getItems()) {
			if (context.getId() == id) {
				return context;
			}
		}
		return null;
	}

	private void leaveCaptureMode() {
		captureMode = false;
		existingContextField.setDisable(false);
		newContextButton.setDisable(false);
		setNewContextBoxVisible(false);
	}

	private void loadContexts(ExamBooklet booklet) {
		if (booklet == null) {
			existingContextField.getItems().clear();
			return;
		}
		existingContextField.getItems().setAll(contextRepository.findByBooklet(booklet));
	}

	private void pasteClipboardImage() {
		if (correctionSaveHandler != null) {
			showWarning("Clipboard capture unavailable", "Complete this source-wide correction using PDF regions.");
			return;
		}
		if (currentSelection != null) {
			showWarning("PDF selection pending", "Accept or clear the current PDF selection first.");
			return;
		}
		try {
			byte[] png = clipboardImageReader.readPng().orElse(null);
			if (png == null) {
				showWarning("No image on clipboard", "Copy or snip an image before using Add From Clipboard.");
				return;
			}

			// A duplicate must not consume the clipboard.
			if (!acceptClipboardPng(png)) {
				statusLabel.setText("This image is already accepted in the Shared Context");
				return;
			}

			// Only a successful insertion consumes clipboard content.
			try {
				Clipboard.getSystemClipboard().clear();
			} catch (RuntimeException exception) {
				statusLabel.setText("Image accepted, but clipboard could not be cleared");
			}
		} catch (IOException exception) {
			showWarning("Clipboard image could not be added", "The clipboard image could not be converted to PNG.");
		}
	}

	private void refreshRegionPreviews() {
		acceptedRegionBox.getChildren().clear();
		List<SharedContextContentPart> parts = pendingContent.contentParts();
		for (int index = 0; index < parts.size(); index++) {
			SharedContextContentPart part = parts.get(index);
			if (part instanceof SharedQuestionContextRegion region) {
				acceptedRegionBox.getChildren().add(createRegionPreview(region, index));
			} else if (part instanceof ImageSharedContextContentPart image) {
				acceptedRegionBox.getChildren().add(createImagePreview(image, index));
			} else {
				throw new IllegalStateException("Unsupported Shared Context content type");
			}
		}
		regionCountLabel.setText("Content parts: " + parts.size());
		boolean hasContent = !parts.isEmpty();
		acceptedRegionScroll.setVisible(hasContent);
		acceptedRegionScroll.setManaged(hasContent);
	}

	private SharedQuestionContext reloadSavedContext(ExamBooklet booklet, SharedQuestionContext saved) {
		loadContexts(booklet);
		return findContextById(saved.getId());
	}

	private void removeRegion(int regionIndex) {
		pendingContent.removeAt(regionIndex);
		refreshRegionPreviews();
	}

	private void saveContext() {
		if (currentSelection != null) {
			showWarning("Shared Context selection pending", "Click Add or Clear before saving Shared Context.");
			return;
		}
		String label = contextLabelField.getText().trim();
		if (label.isBlank()) {
			showWarning("Shared Context is incomplete", "Enter a label for the Shared Context.");
			return;
		}
		if (pendingContent.isEmpty()) {
			showWarning("Shared Context is incomplete", "Capture at least one Shared Context content part.");
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			showWarning("No active booklet", "Select the original Question booklet before saving.");
			return;
		}
		Runnable completedHandler = contextEditCompletedHandler;
		SharedQuestionContext saved;
		try {

			// Correction callbacks commit both the context and its
			// source-wide Question links in one transaction.
			saved = savePendingContext(booklet, label);
		} catch (RuntimeException exception) {

			// Retain all staged regions so the teacher can retry
			// or cancel without losing the captured rectangles.
			String message = exception.getMessage();
			showWarning("Shared Context could not be saved",
					message == null || message.isBlank() ? exception.toString() : message);
			return;
		}
		leaveCaptureMode();
		clearPersistedCaptureState();
		clearContextEditState();
		SharedQuestionContext matching = reloadSavedContext(booklet, saved);
		existingContextField.setValue(matching);
		statusLabel.setText("Linked: " + saved.getLabel());

		// Search is reopened only after persistence succeeded
		// and transient capture state was cleared.
		completedHandler.run();
	}

	private SharedQuestionContext savePendingContext(ExamBooklet booklet, String label) {
		List<SharedContextContentPart> parts = pendingContent.contentParts();
		if (correctionSaveHandler != null) {

			// Source-wide correction still owns Question relationships
			// and supports PDF regions only.
			if (parts.stream().anyMatch(part -> !(part instanceof SharedQuestionContextRegion))) {
				throw new IllegalStateException("Mixed content requires a source-wide correction handler");
			}
			return correctionSaveHandler.apply(label, pendingContent.regions());
		}
		if (editingContext != null) {

			// Preserve persisted context identity and Question links.
			return contextRepository.replaceContentParts(editingContext, label, parts);
		}
		return contextRepository.saveContentParts(booklet, label, parts);
	}

	private void setNewContextBoxVisible(boolean visible) {
		newContextBox.setVisible(visible);
		newContextBox.setManaged(visible);
	}

	private void setSelectionButtonsEnabled(boolean enabled) {
		addRegionButton.setDisable(!enabled);
		clearSelectionButton.setDisable(!enabled);
	}

	private void showCurrentPreview() {
		try {
			BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), currentSelection);
			currentPreview.setImage(SwingFXUtils.toFXImage(image, null));

			// A pending selection now has something meaningful to preview.
			currentPreview.setVisible(true);
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview shared context selection", e);
		}
	}

	private void showWarning(String header, String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void updateSelectedContextStatus(SharedQuestionContext newContext) {
		if (newContext == null) {
			statusLabel.setText("No shared context selected");
			return;
		}
		statusLabel.setText("Linked: " + newContext.getLabel());
	}

	private void validateDependencies(SharedQuestionContextRepository contextRepository,
			Supplier<ExamBooklet> bookletSupplier, QuestionExtractor questionExtractor,
			Supplier<PdfSession> examPdfSessionSupplier, Runnable selectionClearHandler,
			BooleanSupplier captureStartAllowed) {
		if (contextRepository == null) {
			throw new NullPointerException("contextRepository");
		}
		if (bookletSupplier == null) {
			throw new NullPointerException("bookletSupplier");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		if (examPdfSessionSupplier == null) {
			throw new NullPointerException("examPdfSessionSupplier");
		}
		if (selectionClearHandler == null) {
			throw new NullPointerException("selectionClearHandler");
		}
		if (captureStartAllowed == null) {
			throw new NullPointerException("captureStartAllowed");
		}
	}
}
