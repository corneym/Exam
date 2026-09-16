package au.edu.eq.questionbank.ui;

import java.util.List;
import java.util.function.BiConsumer;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Alert;
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
		resolveButton.disableProperty()
				.bind(Bindings.createBooleanBinding(
						() -> auditPane.selectedWorkItems().size() != 1
								|| resolutionTarget(auditPane.getSelectedWorkItem()) == null,
						auditPane.selectedWorkItems()));
		setResultConverter(buttonType -> {
			if (buttonType != resolveButtonType) {
				return null;
			}
			if (auditPane.selectedWorkItems().size() != 1) {
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

	void setBulkResponseTypeHandler(BiConsumer<List<Question>, QuestionResponseType> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}
		auditPane.setBulkResponseTypeHandler((questions, responseType) -> {
			String responseTypeLabel = switch (responseType) {
			case MULTIPLE_CHOICE -> "Multiple choice";
			case WRITTEN_RESPONSE -> "Written response";
			case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN cannot be applied as a bulk resolution");
			};
			ButtonType applyButton = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
			Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
			confirmation.initOwner(getOwner());
			confirmation.setTitle("Resolve Response Types");
			confirmation
					.setHeaderText("Set " + questions.size() + " selected question(s) to " + responseTypeLabel + "?");
			confirmation.setContentText("Only the response type will be changed.");
			confirmation.getButtonTypes().setAll(applyButton, ButtonType.CANCEL);
			ButtonType decision = confirmation.showAndWait().orElse(ButtonType.CANCEL);
			if (decision != applyButton) {
				return;
			}
			handler.accept(questions, responseType);
		});
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
