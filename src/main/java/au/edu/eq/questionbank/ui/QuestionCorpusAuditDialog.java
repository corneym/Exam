package au.edu.eq.questionbank.ui;

import java.util.List;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * Dialog containing the question-corpus completeness work queue.
 */
final class QuestionCorpusAuditDialog extends Dialog<QuestionCorpusAuditDialog.ResolutionRequest> {

	private final QuestionCorpusAuditPane auditPane;

	QuestionCorpusAuditDialog(Window owner, List<Question> questions) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		initOwner(owner);
		setTitle("Question Corpus Audit");
		setHeaderText("Review and complete question-bank data");
		setResizable(true);
		auditPane = new QuestionCorpusAuditPane(questions);
		ButtonType resolveButtonType = new ButtonType("Resolve Selected", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(resolveButtonType, ButtonType.CLOSE);
		getDialogPane().setContent(auditPane);
		Node resolveButton = getDialogPane().lookupButton(resolveButtonType);
		resolveButton.setId("corpus-resolve-selected");
		resolveButton.disableProperty().bind(Bindings.createBooleanBinding(
				() -> resolutionTarget(auditPane.getSelectedWorkItem()) == null, auditPane.selectedWorkItemProperty()));
		setResultConverter(buttonType -> {
			if (buttonType != resolveButtonType) {
				return null;
			}
			QuestionCorpusWorkItem item = auditPane.getSelectedWorkItem();
			ResolutionTarget target = resolutionTarget(item);
			if (item == null || target == null) {
				return null;
			}
			return new ResolutionRequest(item.question(), target);
		});
		getDialogPane().setPrefWidth(1100);
		getDialogPane().setPrefHeight(700);
	}

	static ResolutionTarget resolutionTarget(QuestionCorpusWorkItem item) {
		if (item == null || item.status().isComplete()) {
			return null;
		}
		if (item.status().hasProblem(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE)) {
			return ResolutionTarget.METADATA;
		}
		if (item.status().hasProblem(QuestionCorpusProblem.MISSING_QUESTION_SOURCE)
				|| item.status().hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT)) {
			return ResolutionTarget.QUESTION;
		}
		if (item.status().hasProblem(QuestionCorpusProblem.MISSING_ANSWER)) {
			return ResolutionTarget.ANSWER;
		}
		return null;
	}

	void refreshQuestions(List<Question> questions, long preferredQuestionId) {
		auditPane.refreshQuestions(questions, preferredQuestionId);
	}

	enum ResolutionTarget {
		QUESTION, METADATA, ANSWER
	}

	record ResolutionRequest(Question question, ResolutionTarget target) {

		ResolutionRequest {
			if (question == null) {
				throw new NullPointerException("question");
			}
			if (target == null) {
				throw new NullPointerException("target");
			}
		}
	}
}
