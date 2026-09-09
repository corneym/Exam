package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * Dialog containing the curriculum-aware question search workflow.
 */
public final class QuestionSearchDialog extends Dialog<Question> {

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
		ButtonType editButtonType = new ButtonType("Edit Question", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(editButtonType, ButtonType.CLOSE);
		searchPane = new QuestionSearchPane(curriculumRepository, retrievalService, previewService);
		getDialogPane().setContent(searchPane);
		Node editButton = getDialogPane().lookupButton(editButtonType);
		editButton.disableProperty().bind(searchPane.selectedResultProperty().isNull());
		setResultConverter(buttonType -> {
			if (buttonType != editButtonType) {
				return null;
			}
			return searchPane.getSelectedQuestion();
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
}
