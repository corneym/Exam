package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * Dialog containing the curriculum-aware question search workflow.
 */
public final class QuestionSearchDialog extends Dialog<QuestionSearchDialog.EditRequest> {

	private final QuestionSearchPane searchPane;

	/**
	 * Creates a question-search dialog owned by the supplied window.
	 *
	 * @param owner                dialog owner
	 * @param curriculumRepository current curriculum hierarchy lookup
	 * @param retrievalService     curriculum-aware question retrieval
	 * @param previewService       stored question image preview service
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public QuestionSearchDialog(Window owner, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, QuestionPreviewService previewService) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (retrievalService == null) {
			throw new NullPointerException("retrievalService");
		}
		if (previewService == null) {
			throw new NullPointerException("previewService");
		}
		initOwner(owner);
		setTitle("Search Questions");
		setHeaderText("Find questions by current curriculum");
		setResizable(true);
		ButtonType editQuestionButtonType = new ButtonType("Edit Question", ButtonBar.ButtonData.OK_DONE);
		ButtonType editAnswerButtonType = new ButtonType("Edit Answer", ButtonBar.ButtonData.OTHER);
		getDialogPane().getButtonTypes().addAll(editQuestionButtonType, editAnswerButtonType, ButtonType.CLOSE);
		searchPane = new QuestionSearchPane(curriculumRepository, retrievalService, previewService);
		getDialogPane().setContent(searchPane);
		Node editQuestionButton = getDialogPane().lookupButton(editQuestionButtonType);
		Node editAnswerButton = getDialogPane().lookupButton(editAnswerButtonType);
		editQuestionButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());
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
			if (buttonType == editAnswerButtonType) {
				return new EditRequest(selected, EditTarget.ANSWER);
			}
			return null;
		});
		getDialogPane().setPrefWidth(900);
		getDialogPane().setPrefHeight(700);
	}

	void dispose() {
		searchPane.dispose();
	}

	void refreshAfterEdit(long questionId) {
		searchPane.refreshAfterEdit(questionId);
	}

	enum EditTarget {
		QUESTION, ANSWER
	}

	record EditRequest(Question question, EditTarget target) {

		EditRequest {
			if (question == null) {
				throw new NullPointerException("question");
			}
			if (target == null) {
				throw new NullPointerException("target");
			}
		}
	}
}
