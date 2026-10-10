package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import au.edu.eq.questionbank.ApplicationPaths;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.QuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Tooltip;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Dialog containing the curriculum-aware question search workflow.
 */
public final class QuestionSearchDialog extends Dialog<QuestionSearchDialog.EditRequest> {

	private static final int DIALOG_WIDTH = 1100;
	private static final int DIALOG_HEIGHT = 700;
	private final QuestionSearchPane searchPane;
	private final BiFunction<Long, CurriculumNode, Question> classificationUpdater;

	// A Search edit temporarily hides and later reuses this same Dialog instance.
	// Preserve teacher-adjusted dimensions across that hide/show cycle.
	private double rememberedWidth = Double.NaN;
	private double rememberedHeight = Double.NaN;
	private double rememberedX = Double.NaN;
	private double rememberedY = Double.NaN;
	private final ButtonType editAnswerButtonType = new ButtonType("Edit Answer", ButtonBar.ButtonData.OTHER);
	private final ButtonType editMetadataButtonType = new ButtonType("Edit Metadata", ButtonBar.ButtonData.OTHER);
	private final ButtonType editQuestionButtonType = new ButtonType("Edit Question", ButtonBar.ButtonData.OTHER);
	private final ButtonType recaptureSharedContextButtonType = new ButtonType("Recapture Shared Context",
			ButtonBar.ButtonData.OTHER);
	private final ButtonType splitQuestionButtonType = new ButtonType("Split Question...", ButtonBar.ButtonData.OTHER);
	private boolean persistedClassificationChange;

	/**
	 * Creates an unrestricted question-search dialog owned by the supplied window.
	 *
	 * @param owner                         dialog owner
	 * @param workingSubject                authoritative workspace Working Subject
	 * @param curriculumRepository          current curriculum hierarchy lookup
	 * @param retrievalService              curriculum-aware Question retrieval
	 * @param allQuestionsSupplier          complete stored Question retrieval
	 * @param previewService                stored Question image preview service
	 * @param outputApplicabilityRepository persisted per-Question revision-output
	 *                                      exclusions
	 * @param classificationUpdater         persistence operation for a
	 *                                      classification-only Question update
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public QuestionSearchDialog(Window owner, Subject workingSubject, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService, QuestionOutputApplicabilityRepository outputApplicabilityRepository,
			BiFunction<Long, CurriculumNode, Question> classificationUpdater) {
		this(owner, workingSubject, curriculumRepository, retrievalService, allQuestionsSupplier, previewService,
				outputApplicabilityRepository, classificationUpdater, QuestionSearchNarrowing.unrestricted());
	}

	/**
	 * Creates a narrowed Search dialog without diagnostics.
	 *
	 * @param owner                         dialog owner
	 * @param workingSubject                authoritative Working Subject
	 * @param curriculumRepository          curriculum lookup
	 * @param retrievalService              Question retrieval
	 * @param allQuestionsSupplier          all-Question retrieval
	 * @param previewService                Question preview service
	 * @param outputApplicabilityRepository applicability persistence
	 * @param classificationUpdater         classification persistence callback
	 * @param searchNarrowing               immutable Search constraint
	 */
	public QuestionSearchDialog(Window owner, Subject workingSubject, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService, QuestionOutputApplicabilityRepository outputApplicabilityRepository,
			BiFunction<Long, CurriculumNode, Question> classificationUpdater, QuestionSearchNarrowing searchNarrowing) {
		this(owner, workingSubject, curriculumRepository, retrievalService, allQuestionsSupplier, previewService,
				outputApplicabilityRepository, classificationUpdater, searchNarrowing,
				new PerformanceRecorder(false, ApplicationPaths.diagnosticsDirectory().resolve("performance.csv")));
	}

	/**
	 * Creates a narrowed Search dialog using the application recorder.
	 *
	 * @param owner                         dialog owner
	 * @param workingSubject                authoritative Working Subject
	 * @param curriculumRepository          curriculum lookup
	 * @param retrievalService              Question retrieval
	 * @param allQuestionsSupplier          all-Question retrieval
	 * @param previewService                Question preview service
	 * @param outputApplicabilityRepository applicability persistence
	 * @param classificationUpdater         classification persistence callback
	 * @param searchNarrowing               immutable Search constraint
	 * @param performanceRecorder           shared application recorder
	 */
	public QuestionSearchDialog(Window owner, Subject workingSubject, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService, QuestionOutputApplicabilityRepository outputApplicabilityRepository,
			BiFunction<Long, CurriculumNode, Question> classificationUpdater, QuestionSearchNarrowing searchNarrowing,
			PerformanceRecorder performanceRecorder) {
		this.classificationUpdater = Objects.requireNonNull(classificationUpdater, "classificationUpdater");
		initOwner(Objects.requireNonNull(owner, "owner"));
		searchPane = new QuestionSearchPane(Objects.requireNonNull(workingSubject, "workingSubject"),
				Objects.requireNonNull(curriculumRepository, "curriculumRepository"),
				Objects.requireNonNull(retrievalService, "retrievalService"),
				Objects.requireNonNull(allQuestionsSupplier, "allQuestionsSupplier"),
				Objects.requireNonNull(previewService, "previewService"),
				Objects.requireNonNull(outputApplicabilityRepository, "outputApplicabilityRepository"),
				Objects.requireNonNull(searchNarrowing, "searchNarrowing"),
				Objects.requireNonNull(performanceRecorder, "performanceRecorder"));
		configureDialogShell();
		configureSearchPaneCallbacks();
		configureActionButtons();
		configureResultConversion();
		configureGeometry();
	}

	/**
	 * Reports and clears whether inline classification persistence has changed the
	 * Question corpus since the previous consumption.
	 *
	 * @return {@code true} when at least one Descriptor refinement was persisted
	 */
	public boolean consumePersistedClassificationChange() {
		boolean changed = persistedClassificationChange;
		persistedClassificationChange = false;
		return changed;
	}

	/**
	 * Disposes the search pane and cancels its pending background work.
	 */
	public void dispose() {
		searchPane.dispose();
	}

	/**
	 * Refreshes the active search after an edit.
	 *
	 * @param questionId the persistent question identifier to reselect if still
	 *                   present
	 */
	public void refreshAfterEdit(long questionId) {
		searchPane.refreshAfterEdit(questionId);
	}

	private Button buttonFor(ButtonType buttonType) {

		// Every supplied ButtonType is installed before this lookup, so failure here
		// means the Dialog configuration itself is inconsistent.
		Button button = (Button) getDialogPane().lookupButton(buttonType);
		if (button == null) {
			throw new IllegalStateException("Dialog button was not created: " + buttonType.getText());
		}
		return button;
	}

	private void configureActionButtonBindings(Button editQuestionButton, Button splitQuestionButton,
			Button editMetadataButton, Button recaptureSharedContextButton, Button editAnswerButton) {
		editQuestionButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());
		splitQuestionButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return searchPane.isClassificationDirty() || selected == null || selected.hasSourceQuestion()
					|| selected.hasSharedContext();
		}, searchPane.selectedResultProperty(), searchPane.classificationDirtyProperty()));
		editMetadataButton.disableProperty().bind(
				Bindings.or(searchPane.selectedResultProperty().isNull(), searchPane.classificationDirtyProperty()));
		recaptureSharedContextButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return searchPane.isClassificationDirty() || selected == null || !selected.hasSharedContext();
		}, searchPane.selectedResultProperty(), searchPane.classificationDirtyProperty()));
		editAnswerButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return searchPane.isClassificationDirty() || selected == null || !selected.hasAnswer();
		}, searchPane.selectedResultProperty(), searchPane.classificationDirtyProperty()));
	}

	private void configureActionButtonIds(Button editQuestionButton, Button splitQuestionButton,
			Button editMetadataButton, Button recaptureSharedContextButton, Button editAnswerButton) {

		// Stable IDs support deterministic TestFX lookup without depending on Dialog
		// button ordering.
		editQuestionButton.setId("question-search-edit-question");
		splitQuestionButton.setId("question-search-split-question");
		editMetadataButton.setId("question-search-edit-metadata");
		recaptureSharedContextButton.setId("question-search-recapture-shared-context");
		editAnswerButton.setId("question-search-edit-answer");
		editQuestionButton.setTooltip(
				new Tooltip("Edit Question content, source regions, classification and response metadata."));
		splitQuestionButton.setTooltip(
				new Tooltip("Convert one legacy Question into multipart Questions after capturing every part."));
		editMetadataButton.setTooltip(
				new Tooltip("Correct this Question's code, marks, response type and Shared Context requirement."));
		recaptureSharedContextButton
				.setTooltip(new Tooltip("Replace the reusable Shared Context linked to this Question."));
		editAnswerButton.setTooltip(new Tooltip("Replace this Question's stored Answer content."));
	}

	private void configureActionButtons() {
		getDialogPane().getButtonTypes().setAll(editQuestionButtonType, splitQuestionButtonType, editMetadataButtonType,
				recaptureSharedContextButtonType, editAnswerButtonType, ButtonType.CLOSE);
		Button editQuestionButton = buttonFor(editQuestionButtonType);
		Button splitQuestionButton = buttonFor(splitQuestionButtonType);
		Button editMetadataButton = buttonFor(editMetadataButtonType);
		Button recaptureSharedContextButton = buttonFor(recaptureSharedContextButtonType);
		Button editAnswerButton = buttonFor(editAnswerButtonType);
		Button closeButton = buttonFor(ButtonType.CLOSE);

		// The five Question-edit actions form one uniformly sized group. Close remains
		// on the same ButtonBar row but keeps its natural compact width.
		for (Button button : List.of(editQuestionButton, splitQuestionButton, editMetadataButton,
				recaptureSharedContextButton, editAnswerButton)) {
			ButtonBar.setButtonUniformSize(button, true);
		}
		ButtonBar.setButtonUniformSize(closeButton, false);
		configureActionButtonIds(editQuestionButton, splitQuestionButton, editMetadataButton,
				recaptureSharedContextButton, editAnswerButton);
		configureActionButtonBindings(editQuestionButton, splitQuestionButton, editMetadataButton,
				recaptureSharedContextButton, editAnswerButton);
		configureDirtyActionHandling(editQuestionButton, closeButton);
	}

	private void configureDialogShell() {
		setTitle("Search Questions");
		setHeaderText("Find questions across the bank or by current curriculum");
		setResizable(true);
		getDialogPane().setContent(searchPane);
	}

	private void configureDirtyActionHandling(Button editQuestionButton, Button closeButton) {

		// A dirty Descriptor must be resolved before full Question editing begins,
		// while Edit Question itself remains available.
		editQuestionButton.addEventFilter(ActionEvent.ACTION, event -> {
			if (!searchPane.isClassificationDirty()) {
				return;
			}
			event.consume();
			Question question = resolvePendingClassificationForEdit();
			if (question == null) {

				// Cancel preserves the pending Descriptor and leaves Search open.
				return;
			}
			setResult(new EditRequest(question, EditTarget.QUESTION));
			close();
		});

		// The DialogPane Close button has its own action event. Resolve the pending
		// classification before allowing that button to close Search.
		closeButton.addEventFilter(ActionEvent.ACTION, event -> {
			if (!searchPane.isClassificationDirty()) {
				return;
			}
			event.consume();
			if (confirmPendingClassificationBeforeClose()) {

				// Save or Discard has cleared the dirty state, so the Dialog-level
				// close-request guard below will allow this close to complete
				// without displaying a second prompt.
				close();
			}
		});

		// A native title-bar close does not fire the DialogPane Close button action.
		// Guard the Dialog close request itself so every exit path resolves an
		// unsaved Descriptor refinement consistently.
		setOnCloseRequest(event -> {
			if (!searchPane.isClassificationDirty()) {
				return;
			}
			if (!confirmPendingClassificationBeforeClose()) {

				// Cancel or a failed Save must leave Search open with the pending
				// Descriptor refinement intact.
				event.consume();
			}
		});
	}

	private void configureGeometry() {

		// Search controls have a functional minimum size below which selector labels
		// and inline actions no longer remain usable.
		getDialogPane().setMinWidth(DIALOG_WIDTH);
		getDialogPane().setPrefWidth(DIALOG_WIDTH);
		getDialogPane().setPrefHeight(DIALOG_HEIGHT);

		// JavaFX may permit programmatic width changes below the native Stage minimum.
		// Correct those after the current resize event rather than re-entrantly.
		widthProperty().addListener((_, _, width) -> {
			if (!isShowing() || width.doubleValue() >= DIALOG_WIDTH) {
				return;
			}
			Platform.runLater(this::enforceMinimumWidth);
		});
		configureSizePersistence();
	}

	private void configureResultConversion() {
		setResultConverter(buttonType -> {
			Question selected = searchPane.getSelectedQuestion();
			if (selected == null) {
				return null;
			}
			if (buttonType == editQuestionButtonType) {
				return new EditRequest(selected, EditTarget.QUESTION);
			}
			if (buttonType == splitQuestionButtonType) {
				return new EditRequest(selected, EditTarget.SPLIT);
			}
			if (buttonType == editMetadataButtonType) {
				return new EditRequest(selected, EditTarget.METADATA);
			}
			if (buttonType == recaptureSharedContextButtonType) {
				return new EditRequest(selected, EditTarget.SHARED_CONTEXT);
			}
			if (buttonType == editAnswerButtonType) {
				return new EditRequest(selected, EditTarget.ANSWER);
			}

			// Close publishes no edit request.
			return null;
		});
	}

	private void configureSearchPaneCallbacks() {

		// The Dialog owns modal decisions and persistence; the Pane owns the dirty
		// classification state that triggers those decisions.
		searchPane.setClassificationNavigationGuard(this::confirmPendingClassificationBeforeNavigation);

		// Inline Save commits only the Descriptor refinement and leaves Search open on
		// the refreshed Question.
		searchPane.setClassificationSaveHandler(() -> {
			Question updated = savePendingClassification();
			if (updated != null) {
				searchPane.refreshAfterEdit(updated.getId());
			}
		});
	}

	private void configureSizePersistence() {
		setOnHiding(_ -> {

			// Capture the actual window geometry immediately before an edit action hides
			// Search so the same Dialog instance can return to the teacher's location.
			if (Double.isFinite(getWidth()) && getWidth() > 0) {
				rememberedWidth = getWidth();
			}
			if (Double.isFinite(getHeight()) && getHeight() > 0) {
				rememberedHeight = getHeight();
			}
			if (Double.isFinite(getX())) {
				rememberedX = getX();
			}
			if (Double.isFinite(getY())) {
				rememberedY = getY();
			}
		});
		setOnShown(_ -> {

			// Windows may still apply native snap/full-height geometry after onShown.
			// Restore the remembered size on the next FX turn, then restore position
			// after that size has settled.
			Platform.runLater(this::restoreRememberedGeometry);
		});
	}

	private boolean confirmPendingClassificationBeforeClose() {
		ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
		ButtonType discardButton = new ButtonType("Discard Changes", ButtonBar.ButtonData.OTHER);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(getDialogPane().getScene().getWindow());
		alert.setTitle("Unsaved Question");
		alert.setHeaderText("Save the Descriptor classification?");
		alert.setContentText("The selected Question has an unsaved Descriptor change.");
		alert.getButtonTypes().setAll(saveButton, discardButton, cancelButton);
		ButtonType result = alert.showAndWait().orElse(cancelButton);
		if (result == saveButton) {

			// Closing may proceed only after the requested save succeeds.
			return savePendingClassification() != null;
		}
		if (result == discardButton) {

			// The Dialog is about to close, so only the pending in-memory edit needs
			// to be cleared; persisted classification remains unchanged.
			searchPane.classificationDiscarded();
			return true;
		}

		// Cancel leaves the Dialog open with the pending Descriptor still selected.
		return false;
	}

	private boolean confirmPendingClassificationBeforeNavigation() {
		ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
		ButtonType discardButton = new ButtonType("Discard Changes", ButtonBar.ButtonData.OTHER);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(getDialogPane().getScene().getWindow());
		alert.setTitle("Unsaved Question");
		alert.setHeaderText("Save the Descriptor classification?");
		alert.setContentText("The selected Question has an unsaved Descriptor change.");
		alert.getButtonTypes().setAll(saveButton, discardButton, cancelButton);
		ButtonType result = alert.showAndWait().orElse(cancelButton);
		if (result == saveButton) {

			// Navigation may continue only when the requested save succeeds.
			return savePendingClassification() != null;
		}
		if (result == discardButton) {

			// Discard removes the pending edit and allows the attempted navigation
			// to continue without changing persisted classification.
			searchPane.classificationDiscarded();
			return true;
		}

		// Cancel preserves the dirty Descriptor and keeps the current Question
		// selected.
		return false;
	}

	private void enforceMinimumWidth() {
		if (!isShowing() || getWidth() >= DIALOG_WIDTH) {
			return;
		}

		// Search has a functional minimum width independent of whether the resize
		// originated from the native window manager, restored geometry or code.
		setWidth(DIALOG_WIDTH);
	}

	private boolean isRememberedPositionVisible() {
		if (!Double.isFinite(rememberedX) || !Double.isFinite(rememberedY)) {
			return false;
		}
		double width = Double.isFinite(rememberedWidth) && rememberedWidth > 0 ? rememberedWidth
				: Math.max(getWidth(), 1);
		double height = Double.isFinite(rememberedHeight) && rememberedHeight > 0 ? rememberedHeight
				: Math.max(getHeight(), 1);
		return !Screen.getScreensForRectangle(rememberedX, rememberedY, width, height).isEmpty();
	}

	private Question resolvePendingClassificationForEdit() {
		ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
		ButtonType discardButton = new ButtonType("Discard Changes", ButtonBar.ButtonData.OTHER);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(getDialogPane().getScene().getWindow());
		alert.setTitle("Unsaved Question");
		alert.setHeaderText("Resolve the Descriptor change before editing the Question.");
		alert.setContentText("Save the Descriptor refinement, discard it, or cancel full Question editing.");
		alert.getButtonTypes().setAll(saveButton, discardButton, cancelButton);
		ButtonType result = alert.showAndWait().orElse(cancelButton);
		if (result == saveButton) {

			// Full editing must receive the freshly persisted Question rather than
			// the stale pre-save Search object.
			return savePendingClassification();
		}
		if (result == discardButton) {

			// Restore the displayed classification from persisted state before
			// handing the original Question to the full editor.
			searchPane.classificationDiscarded();
			return searchPane.getSelectedQuestion();
		}

		// Cancel leaves the dirty Descriptor edit active in Search.
		return null;
	}

	private void restoreRememberedGeometry() {
		Scene scene = getDialogPane().getScene();

		// Geometry restoration is deferred until after showing. A very short-lived
		// Dialog may already have been hidden or disposed before this callback runs.
		if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
			return;
		}
		if (scene.getWindow() instanceof Stage dialogStage) {

			// DialogPane minimum size controls layout, while Stage minimum width
			// prevents the native window itself from being dragged below that limit.
			dialogStage.setMinWidth(DIALOG_WIDTH);
		}
		if (Double.isFinite(rememberedWidth) && rememberedWidth > 0) {

			// Geometry remembered before the minimum-width rule was introduced must
			// not restore the Dialog below its current usable minimum.
			setWidth(Math.max(DIALOG_WIDTH, rememberedWidth));
		} else {

			// The first show has no remembered geometry, but it must obey the same
			// minimum-width invariant as later restored shows.
			enforceMinimumWidth();
		}
		if (Double.isFinite(rememberedHeight) && rememberedHeight > 0) {
			setHeight(rememberedHeight);
		}

		// Restoring screen-height geometry can itself cause the native window manager
		// to adjust position. Apply X/Y only after that sizing pass.
		Platform.runLater(() -> {
			Scene currentScene = getDialogPane().getScene();

			// The Dialog may also have been closed between the size restoration and
			// this second deferred position-restoration callback.
			if (currentScene == null || currentScene.getWindow() == null || !currentScene.getWindow().isShowing()) {
				return;
			}
			if (isRememberedPositionVisible()) {
				setX(rememberedX);
				setY(rememberedY);
			}
		});
	}

	private Question savePendingClassification() {
		Question selected = searchPane.getSelectedQuestion();
		CurriculumNode classification = searchPane.getPendingClassification();
		if (selected == null || classification == null) {
			return null;
		}
		try {

			// Persist only classification_node_id. Capture relationships, regions and
			// all other Question metadata remain unchanged.
			Question updated = classificationUpdater.apply(selected.getId(), classification);

			// Record the persistence change before updating presentation state so the
			// application cannot miss an already-committed classification if later UI
			// publication fails.
			persistedClassificationChange = true;
			searchPane.classificationSaved(updated.getId());
			return updated;
		} catch (IllegalArgumentException | IllegalStateException exception) {
			Alert alert = new Alert(Alert.AlertType.ERROR);
			alert.initOwner(getDialogPane().getScene().getWindow());
			alert.setTitle("Save Question");
			alert.setHeaderText("The Question classification could not be saved.");
			alert.setContentText(exception.getMessage());
			alert.showAndWait();
			return null;
		}
	}

	/** Application workflow requested for the selected Question. */
	public enum EditTarget {
		/** Edit or recapture the Question. */
		QUESTION,
		/** Split one legacy Question into multipart Questions. */
		SPLIT,
		/** Correct Question metadata. */
		METADATA,
		/** Recapture the reusable shared context. */
		SHARED_CONTEXT,
		/** Edit or recapture the Answer. */
		ANSWER
	}

	/**
	 * Question and workflow selected when Search closes for editing.
	 *
	 * @param question selected Question
	 * @param target   workflow to launch
	 */
	public record EditRequest(Question question, EditTarget target) {

		/** Validates an edit request. */
		public EditRequest {
			if (question == null) {
				throw new NullPointerException("question");
			}
			if (target == null) {
				throw new NullPointerException("target");
			}
		}
	}
}
