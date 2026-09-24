package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
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
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Dialog containing the curriculum-aware question search workflow.
 */
public final class QuestionSearchDialog extends Dialog<QuestionSearchDialog.EditRequest> {

	private static final int DIALOG_WIDTH = 900;
	private static final int DIALOG_HEIGHT = 700;
	private final QuestionSearchPane searchPane;
	private final BiFunction<Long, CurriculumNode, Question> classificationUpdater;

	// A Search edit temporarily hides and later reuses this same Dialog instance.
	// Preserve teacher-adjusted dimensions across that hide/show cycle.
	private double rememberedWidth = Double.NaN;
	private double rememberedHeight = Double.NaN;
	private double rememberedX = Double.NaN;
	private double rememberedY = Double.NaN;
	private final ButtonType editQuestionButtonType = new ButtonType("Edit Question", ButtonBar.ButtonData.OK_DONE);
	private final ButtonType splitQuestionButtonType = new ButtonType("Split Question...", ButtonBar.ButtonData.OTHER);
	private final ButtonType editMetadataButtonType = new ButtonType("Edit Metadata", ButtonBar.ButtonData.OTHER);
	private final ButtonType editExamButtonType = new ButtonType("Edit Exam", ButtonBar.ButtonData.OTHER);
	private final ButtonType recaptureSharedContextButtonType = new ButtonType("Recapture Shared Context",
			ButtonBar.ButtonData.OTHER);
	private final ButtonType editAnswerButtonType = new ButtonType("Edit Answer", ButtonBar.ButtonData.OTHER);

	/**
	 * Creates a question-search dialog owned by the supplied window.
	 *
	 * @param owner                         dialog owner
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
	public QuestionSearchDialog(Window owner, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService, QuestionOutputApplicabilityRepository outputApplicabilityRepository,
			BiFunction<Long, CurriculumNode, Question> classificationUpdater) {
		this.classificationUpdater = Objects.requireNonNull(classificationUpdater, "classificationUpdater");
		initOwner(Objects.requireNonNull(owner, "owner"));

		// The Pane owns Search state and background retrieval. The Dialog owns modal
		// decisions, persistence callbacks and edit-workflow actions.
		searchPane = new QuestionSearchPane(Objects.requireNonNull(curriculumRepository, "curriculumRepository"),
				Objects.requireNonNull(retrievalService, "retrievalService"),
				Objects.requireNonNull(allQuestionsSupplier, "allQuestionsSupplier"),
				Objects.requireNonNull(previewService, "previewService"),
				Objects.requireNonNull(outputApplicabilityRepository, "outputApplicabilityRepository"));
		configureDialogShell();
		configureSearchPaneCallbacks();
		configureActionButtons();
		configureResultConversion();
		configureGeometry();
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
			Button editMetadataButton, Button editExamButton, Button recaptureSharedContextButton,
			Button editAnswerButton) {
		editQuestionButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());
		splitQuestionButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return searchPane.isClassificationDirty() || selected == null || selected.hasSourceQuestion()
					|| selected.hasSharedContext();
		}, searchPane.selectedResultProperty(), searchPane.classificationDirtyProperty()));
		editMetadataButton.disableProperty().bind(
				Bindings.or(searchPane.selectedResultProperty().isNull(), searchPane.classificationDirtyProperty()));
		editExamButton.disableProperty().bind(
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
			Button editMetadataButton, Button editExamButton, Button recaptureSharedContextButton,
			Button editAnswerButton) {

		// Stable IDs support TestFX without exposing implementation-specific button
		// ordering to UI tests.
		editQuestionButton.setId("question-search-edit-question");
		splitQuestionButton.setId("question-search-split-question");
		editMetadataButton.setId("question-search-edit-metadata");
		editExamButton.setId("question-search-edit-exam");
		recaptureSharedContextButton.setId("question-search-recapture-shared-context");
		editAnswerButton.setId("question-search-edit-answer");
	}

	private void configureActionButtons() {
		getDialogPane().getButtonTypes().addAll(editQuestionButtonType, splitQuestionButtonType, editMetadataButtonType,
				editExamButtonType, recaptureSharedContextButtonType, editAnswerButtonType, ButtonType.CLOSE);
		Button editQuestionButton = buttonFor(editQuestionButtonType);
		Button splitQuestionButton = buttonFor(splitQuestionButtonType);
		Button editMetadataButton = buttonFor(editMetadataButtonType);
		Button editExamButton = buttonFor(editExamButtonType);
		Button recaptureSharedContextButton = buttonFor(recaptureSharedContextButtonType);
		Button editAnswerButton = buttonFor(editAnswerButtonType);
		Button closeButton = buttonFor(ButtonType.CLOSE);
		configureActionButtonIds(editQuestionButton, splitQuestionButton, editMetadataButton, editExamButton,
				recaptureSharedContextButton, editAnswerButton);
		configureActionButtonBindings(editQuestionButton, splitQuestionButton, editMetadataButton, editExamButton,
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

		// Closing with an unsaved Descriptor requires an explicit decision rather
		// than silently abandoning the inline classification edit.
		closeButton.addEventFilter(ActionEvent.ACTION, event -> {
			if (!searchPane.isClassificationDirty()) {
				return;
			}
			event.consume();
			if (confirmPendingClassificationBeforeClose()) {
				close();
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
			if (buttonType == editExamButtonType) {
				return new EditRequest(selected, EditTarget.EXAM);
			}
			if (buttonType == recaptureSharedContextButtonType) {
				return new EditRequest(selected, EditTarget.SHARED_CONTEXT);
			}
			if (buttonType == editAnswerButtonType) {
				return new EditRequest(selected, EditTarget.ANSWER);
			}
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

	public enum EditTarget {
		QUESTION, SPLIT, METADATA, EXAM, SHARED_CONTEXT, ANSWER
	}

	public record EditRequest(Question question, EditTarget target) {

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
