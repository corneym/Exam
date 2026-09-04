package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

public final class QuestionSearchDialog extends Dialog<ButtonType> {

	public QuestionSearchDialog(Window owner, CurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (retrievalService == null) {
			throw new NullPointerException("retrievalService");
		}
		initOwner(owner);
		setTitle("Search Questions");
		setHeaderText("Find questions by current curriculum");
		setResizable(true);
		getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
		getDialogPane().setContent(new QuestionSearchPane(curriculumRepository, retrievalService));
		getDialogPane().setPrefWidth(900);
		getDialogPane().setPrefHeight(700);
	}
}
