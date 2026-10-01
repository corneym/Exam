package au.edu.eq.questionbank.ui.audit;

import java.util.List;
import java.util.function.BiConsumer;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

/**
 * Dialog containing the operational Corpus Dashboard.
 */
public final class QuestionCorpusAuditDialog extends Dialog<QuestionCorpusAuditDialog.ResolutionRequest> {

	private static final int DIALOG_WIDTH = 1200;
	private static final int DIALOG_HEIGHT = 850;

	private final CorpusDashboardPane dashboardPane;

	/**
	 * Creates the Corpus Dashboard scoped to the application's Working Subject.
	 *
	 * @param owner          owner window
	 * @param workingSubject authoritative Working Subject
	 * @param examStatuses   calculated Exam and booklet audit snapshots
	 * @param questions      current Question snapshot
	 */
	public QuestionCorpusAuditDialog(Window owner, Subject workingSubject, List<ExamCorpusStatus> examStatuses,
			List<Question> questions) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (workingSubject == null) {
			throw new NullPointerException("workingSubject");
		}
		if (examStatuses == null) {
			throw new NullPointerException("examStatuses");
		}
		if (questions == null) {
			throw new NullPointerException("questions");
		}

		// The Dashboard inherits authoritative Subject scope and calculated corpus
		// state from the application composition root.
		initOwner(owner);
		setTitle("Corpus Dashboard");
		setResizable(true);

		dashboardPane = new CorpusDashboardPane(workingSubject, examStatuses, questions);

		ButtonType resolveButtonType = new ButtonType("Resolve Selected", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(resolveButtonType, ButtonType.CLOSE);
		getDialogPane().setContent(dashboardPane);

		Button resolveButton = (Button) getDialogPane().lookupButton(resolveButtonType);
		resolveButton.setId("corpus-resolve-selected");
		resolveButton.setTooltip(
				new Tooltip("Open the correction workflow for the selected Question's next unresolved problem."));

		resolveButton.disableProperty()
				.bind(Bindings.createBooleanBinding(
						() -> dashboardPane.selectedWorkItems().size() != 1
								|| resolutionTarget(dashboardPane.getSelectedWorkItem()) == null,
						dashboardPane.selectedWorkItems()));

		setResultConverter(buttonType -> {
			if (buttonType != resolveButtonType || dashboardPane.selectedWorkItems().size() != 1) {
				return null;
			}

			QuestionCorpusWorkItem item = dashboardPane.getSelectedWorkItem();
			ResolutionTarget target = resolutionTarget(item);
			if (item == null || target == null) {
				return null;
			}

			// Preserve the existing correction-routing contract until #61 replaces it
			// with direct Dashboard actions.
			return new ResolutionRequest(item.question(), target);
		});

		getDialogPane().setPrefWidth(DIALOG_WIDTH);
		getDialogPane().setPrefHeight(DIALOG_HEIGHT);
	}

	static ResolutionTarget resolutionTarget(QuestionCorpusWorkItem item) {
		if (item == null || item.status().isComplete()) {
			return null;
		}

		// Response metadata must be resolved first because it determines which Answer
		// representation is required.
		if (item.status().hasProblem(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE)) {
			return ResolutionTarget.METADATA;
		}

		// Missing Question body or Shared Context belongs to Question capture.
		if (item.status().hasProblem(QuestionCorpusProblem.MISSING_QUESTION_CONTENT)
				|| item.status().hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT)) {
			return ResolutionTarget.QUESTION;
		}
		if (item.status().hasProblem(QuestionCorpusProblem.MISSING_ANSWER)) {
			return ResolutionTarget.ANSWER;
		}
		return null;
	}

	/**
	 * Replaces the complete Dashboard persistence snapshot.
	 *
	 * @param examStatuses        refreshed Exam/booklet audit state
	 * @param questions           refreshed Questions
	 * @param preferredQuestionId Question to reselect when it remains visible, or a
	 *                            non-positive value for no preferred selection
	 */
	public void refreshData(List<ExamCorpusStatus> examStatuses, List<Question> questions, long preferredQuestionId) {

		// Exam/booklet and Question snapshots are replaced together so the live
		// Dashboard never mixes different persistence generations.
		dashboardPane.replaceData(examStatuses, questions, preferredQuestionId);
	}

	/**
	 * Installs the operation used after a confirmed bulk response-type update.
	 *
	 * @param handler consumer of selected Questions and their new response type
	 */
	public void setBulkResponseTypeHandler(BiConsumer<List<Question>, QuestionResponseType> handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		dashboardPane.setBulkResponseTypeHandler((questions, responseType) -> {
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

	/**
	 * Installs the explicit Dashboard persistence reload operation.
	 *
	 * @param handler refresh operation
	 */
	public void setRefreshHandler(Runnable handler) {
		if (handler == null) {
			throw new NullPointerException("handler");
		}

		// The pane owns presentation while the application owns repository access.
		dashboardPane.setRefreshHandler(handler);
	}

	/** Resolution workflow to launch for an incomplete Question. */
	public enum ResolutionTarget {
		/** Question source or shared-context correction. */
		QUESTION,
		/** Question metadata correction. */
		METADATA,
		/** Answer capture or correction. */
		ANSWER
	}

	/**
	 * Requested Question and correction workflow selected by the Dashboard dialog.
	 *
	 * @param question Question to correct
	 * @param target   correction workflow to launch
	 */
	public record ResolutionRequest(Question question, ResolutionTarget target) {

		/** Validates a resolution request. */
		public ResolutionRequest {
			if (question == null) {
				throw new NullPointerException("question");
			}
			if (target == null) {
				throw new NullPointerException("target");
			}
		}
	}
}
