package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
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

	/**
	 * Creates a question-search dialog owned by the supplied window.
	 *
	 * @param owner                dialog owner
	 * @param curriculumRepository current curriculum hierarchy lookup
	 * @param retrievalService     curriculum-aware question retrieval
	 * @param allQuestionsSupplier complete stored Question retrieval
	 * @param previewService       stored question image preview service
	 * @throws NullPointerException if any argument is {@code null}
	 */
	/**
	 * Creates a question-search dialog owned by the supplied window.
	 *
	 * @param owner                 dialog owner
	 * @param curriculumRepository  current curriculum hierarchy lookup
	 * @param retrievalService      curriculum-aware question retrieval
	 * @param allQuestionsSupplier  complete stored Question retrieval
	 * @param previewService        stored question image preview service
	 * @param classificationUpdater persistence operation for a classification-only
	 *                              Question update
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public QuestionSearchDialog(Window owner, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService, BiFunction<Long, CurriculumNode, Question> classificationUpdater) {
		if (owner == null) {
			throw new NullPointerException("owner");
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
		if (classificationUpdater == null) {
			throw new NullPointerException("classificationUpdater");
		}
		this.classificationUpdater = classificationUpdater;
		initOwner(owner);
		setTitle("Search Questions");
		setHeaderText("Find questions across the bank or by current curriculum");
		setResizable(true);
		ButtonType editQuestionButtonType = new ButtonType("Edit Question", ButtonBar.ButtonData.OK_DONE);
		ButtonType splitQuestionButtonType = new ButtonType("Split Question...", ButtonBar.ButtonData.OTHER);
		ButtonType editMetadataButtonType = new ButtonType("Edit Metadata", ButtonBar.ButtonData.OTHER);
		ButtonType editExamButtonType = new ButtonType("Edit Exam", ButtonBar.ButtonData.OTHER);
		ButtonType recaptureSharedContextButtonType = new ButtonType("Recapture Shared Context",
				ButtonBar.ButtonData.OTHER);
		ButtonType editAnswerButtonType = new ButtonType("Edit Answer", ButtonBar.ButtonData.OTHER);
		getDialogPane().getButtonTypes().addAll(editQuestionButtonType, splitQuestionButtonType, editMetadataButtonType,
				editExamButtonType, recaptureSharedContextButtonType, editAnswerButtonType, ButtonType.CLOSE);
		searchPane = new QuestionSearchPane(curriculumRepository, retrievalService, allQuestionsSupplier,
				previewService);

		// Search delegates dirty result-navigation decisions to the Dialog so the
		// warning and classification persistence remain at the Dialog boundary.
		searchPane.setClassificationNavigationGuard(this::confirmPendingClassificationBeforeNavigation);

		// The inline Save button commits only the Descriptor refinement and leaves
		// Search open on the refreshed Question.
		searchPane.setClassificationSaveHandler(() -> {
			Question updated = savePendingClassification();
			if (updated != null) {
				searchPane.refreshAfterEdit(updated.getId());
			}
		});
		getDialogPane().setContent(searchPane);
		Button editQuestionButton = (Button) getDialogPane().lookupButton(editQuestionButtonType);
		Button splitQuestionButton = (Button) getDialogPane().lookupButton(splitQuestionButtonType);
		Button editMetadataButton = (Button) getDialogPane().lookupButton(editMetadataButtonType);
		Button editExamButton = (Button) getDialogPane().lookupButton(editExamButtonType);
		Button recaptureSharedContextButton = (Button) getDialogPane().lookupButton(recaptureSharedContextButtonType);
		Button editAnswerButton = (Button) getDialogPane().lookupButton(editAnswerButtonType);
		Button closeButton = (Button) getDialogPane().lookupButton(ButtonType.CLOSE);
		editQuestionButton.setId("question-search-edit-question");
		splitQuestionButton.setId("question-search-split-question");
		editMetadataButton.setId("question-search-edit-metadata");
		editExamButton.setId("question-search-edit-exam");
		recaptureSharedContextButton.setId("question-search-recapture-shared-context");
		editAnswerButton.setId("question-search-edit-answer");
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

		// Consume the normal Dialog action while dirty so Save Question persists the
		// Descriptor without closing Search.
		editQuestionButton.addEventFilter(ActionEvent.ACTION, event -> {
			if (!searchPane.isClassificationDirty()) {
				return;
			}

			// A dirty Descriptor must be resolved before full Question editing
			// begins, but Edit Question itself remains available.
			event.consume();
			Question question = resolvePendingClassificationForEdit();
			if (question == null) {

				// Cancel leaves Search open with the pending Descriptor intact.
				return;
			}
			setResult(new EditRequest(question, EditTarget.QUESTION));
			close();
		});

		// Closing with an unsaved Descriptor requires an explicit Save or Cancel
		// decision rather than silently discarding the inline edit.
		closeButton.addEventFilter(ActionEvent.ACTION, event -> {
			if (!searchPane.isClassificationDirty()) {
				return;
			}
			event.consume();
			if (confirmPendingClassificationBeforeClose()) {
				close();
			}
		});
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

		// Search controls have a practical minimum width below which selector labels
		// and actions cease to be usable.
		getDialogPane().setMinWidth(DIALOG_WIDTH);
		getDialogPane().setPrefWidth(DIALOG_WIDTH);
		getDialogPane().setPrefHeight(DIALOG_HEIGHT);
		configureSizePersistence();
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
		if (getDialogPane().getScene().getWindow() instanceof Stage dialogStage) {

			// DialogPane minimum size controls layout, while Stage minimum width
			// prevents the native window itself from being dragged below that limit.
			dialogStage.setMinWidth(DIALOG_WIDTH);
		}
		if (Double.isFinite(rememberedWidth) && rememberedWidth > 0) {

			// A geometry value remembered before the minimum-width rule was introduced
			// must not restore the Dialog below its current usable minimum.
			setWidth(Math.max(DIALOG_WIDTH, rememberedWidth));
		}
		if (Double.isFinite(rememberedHeight) && rememberedHeight > 0) {
			setHeight(rememberedHeight);
		}

		// Restoring screen-height geometry can itself cause the native window manager
		// to adjust the Dialog position. Apply X/Y only after that sizing pass.
		Platform.runLater(() -> {
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
