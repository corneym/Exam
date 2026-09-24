package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Screen;
import javafx.stage.Window;

/**
 * Dialog containing the curriculum-aware question search workflow.
 */
public final class QuestionSearchDialog extends Dialog<QuestionSearchDialog.EditRequest> {

	private static final int DIALOG_WIDTH = 900;
	private static final int DIALOG_HEIGHT = 700;
	private final QuestionSearchPane searchPane;

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
	public QuestionSearchDialog(Window owner, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, Supplier<List<Question>> allQuestionsSupplier,
			QuestionPreviewService previewService) {
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
		getDialogPane().setContent(searchPane);
		Node editQuestionButton = getDialogPane().lookupButton(editQuestionButtonType);
		Node splitQuestionButton = getDialogPane().lookupButton(splitQuestionButtonType);
		Node editMetadataButton = getDialogPane().lookupButton(editMetadataButtonType);
		Node editExamButton = getDialogPane().lookupButton(editExamButtonType);
		Node recaptureSharedContextButton = getDialogPane().lookupButton(recaptureSharedContextButtonType);
		Node editAnswerButton = getDialogPane().lookupButton(editAnswerButtonType);
		editQuestionButton.setId("question-search-edit-question");
		splitQuestionButton.setId("question-search-split-question");
		editMetadataButton.setId("question-search-edit-metadata");
		editExamButton.setId("question-search-edit-exam");
		recaptureSharedContextButton.setId("question-search-recapture-shared-context");
		editAnswerButton.setId("question-search-edit-answer");
		editQuestionButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());

		// A Question already belonging to a SourceQuestion or shared context is
		// already structurally multipart/shared and cannot be treated as the legacy
		// single Question that this correction workflow expects.
		splitQuestionButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return selected == null || selected.hasSourceQuestion() || selected.hasSharedContext();
		}, searchPane.selectedResultProperty()));
		editMetadataButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());
		editExamButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());
		recaptureSharedContextButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return selected == null || !selected.hasSharedContext();
		}, searchPane.selectedResultProperty()));
		editAnswerButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			Question selected = searchPane.getSelectedQuestion();
			return selected == null || !selected.hasAnswer();
		}, searchPane.selectedResultProperty()));
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

			// Restore size before position so the visibility check uses the window's
			// remembered dimensions.
			if (Double.isFinite(rememberedWidth) && rememberedWidth > 0) {
				setWidth(rememberedWidth);
			}
			if (Double.isFinite(rememberedHeight) && rememberedHeight > 0) {
				setHeight(rememberedHeight);
			}

			// Do not force a remembered location that is now completely outside every
			// available screen.
			if (isRememberedPositionVisible()) {
				setX(rememberedX);
				setY(rememberedY);
			}
		});
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
