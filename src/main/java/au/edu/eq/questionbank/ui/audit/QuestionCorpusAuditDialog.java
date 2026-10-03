package au.edu.eq.questionbank.ui.audit;

import java.util.List;
import java.util.function.BiConsumer;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverage;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * Dialog containing the operational Corpus Dashboard.
 */
public final class QuestionCorpusAuditDialog extends Dialog<QuestionCorpusAuditDialog.ResolutionRequest> {

	private static final int DIALOG_WIDTH = 1200;
	private static final int DIALOG_HEIGHT = 940;
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

		// Existing callers without mapping reporting retain an explicit empty mapping
		// snapshot.
		this(owner, workingSubject, examStatuses, questions, List.of());
	}

	/**
	 * Creates the Corpus Dashboard scoped to the application's Working Subject.
	 *
	 * @param owner            owner window
	 * @param workingSubject   authoritative Working Subject
	 * @param examStatuses     calculated Exam and booklet audit snapshots
	 * @param questions        current Question snapshot
	 * @param mappingCoverages curriculum mapping-review coverage for historical to
	 *                         current syllabus pairs
	 */
	public QuestionCorpusAuditDialog(Window owner, Subject workingSubject, List<ExamCorpusStatus> examStatuses,
			List<Question> questions, List<CurriculumMappingCoverage> mappingCoverages) {
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
		if (mappingCoverages == null) {
			throw new NullPointerException("mappingCoverages");
		}

		// The Dashboard inherits authoritative Subject scope and all reporting
		// snapshots from the application composition root.
		initOwner(owner);
		setTitle("Corpus Dashboard");
		setResizable(true);
		dashboardPane = new CorpusDashboardPane(workingSubject, examStatuses, questions, mappingCoverages);
		dashboardPane.setAnswerCaptureHandler(
				question -> completeResolution(new ResolutionRequest(question, ResolutionTarget.ANSWER)));
		dashboardPane.setExamAssetsHandler(
				(exam, booklet) -> completeResolution(ResolutionRequest.forExamAssets(exam, booklet)));
		dashboardPane.setNewQuestionCaptureHandler(
				booklet -> completeResolution(ResolutionRequest.forNewQuestionCapture(booklet)));
		dashboardPane.setQuestionCorrectionHandler(
				question -> completeResolution(new ResolutionRequest(question, ResolutionTarget.QUESTION)));

		// Every operational action is named inside the Dashboard itself. The dialog
		// therefore needs only its ordinary Close control.
		getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

		// Closing the modal Dashboard is navigation, not a correction request.
		setResultConverter(_ -> null);
		getDialogPane().setContent(dashboardPane);
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
	 * Replaces the complete Dashboard persistence and mapping-review snapshot.
	 *
	 * @param examStatuses        refreshed Exam/booklet audit state
	 * @param questions           refreshed Questions
	 * @param mappingCoverages    refreshed curriculum mapping-review coverage
	 * @param preferredQuestionId Question to reselect when it remains visible, or a
	 *                            non-positive value for no preferred selection
	 */
	public void refreshData(List<ExamCorpusStatus> examStatuses, List<Question> questions,
			List<CurriculumMappingCoverage> mappingCoverages, long preferredQuestionId) {

		// Publish mapping reporting beside, but independently from, ordinary corpus
		// completeness state.
		dashboardPane.replaceData(examStatuses, questions, mappingCoverages, preferredQuestionId);
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

	private void completeResolution(ResolutionRequest request) {

		// Publish the explicit Dashboard action before closing the modal dialog so the
		// application can launch exactly that workflow.
		setResult(request);
		close();
	}

	/** Resolution workflow to launch for an incomplete corpus item. */
	public enum ResolutionTarget {
		/** Question source or shared-context correction. */
		QUESTION,
		/** Question metadata correction. */
		METADATA,
		/** Answer capture. */
		ANSWER,
		/** Start capture for Questions not yet represented by persisted rows. */
		NEW_QUESTION_CAPTURE,
		/** Structural Exam or booklet correction through Exam/Assets. */
		EXAM_ASSETS
	}

	/**
	 * Requested correction workflow selected by the Dashboard.
	 *
	 * @param question Question to correct for Question-level workflows, otherwise
	 *                 {@code null}
	 * @param target   correction workflow
	 * @param exam     Exam to manage for structural correction, otherwise
	 *                 {@code null}
	 * @param booklet  specific structurally affected booklet, or {@code null} for
	 *                 an Exam-wide structural finding
	 */
	public record ResolutionRequest(Question question, ResolutionTarget target, Exam exam, ExamBooklet booklet) {

		/**
		 * Creates a Question-level correction request.
		 *
		 * @param question Question to correct
		 * @param target   Question-level correction target
		 */
		public ResolutionRequest(Question question, ResolutionTarget target) {
			this(question, target, null, null);
		}

		/**
		 * Creates a request to add new Questions to an existing booklet.
		 *
		 * @param booklet exact booklet selected for new-Question capture
		 * @return new-Question capture request
		 */
		public static ResolutionRequest forNewQuestionCapture(ExamBooklet booklet) {
			if (booklet == null) {
				throw new NullPointerException("booklet");
			}

			// No persisted Question exists for the missing source Questions, so booklet
			// identity is the authoritative routing target.
			return new ResolutionRequest(null, ResolutionTarget.NEW_QUESTION_CAPTURE, booklet.getExam(), booklet);
		}

		/**
		 * Creates an Exam/Assets structural correction request.
		 *
		 * @param exam    Exam requiring structural review
		 * @param booklet specific affected booklet, or {@code null} for Exam-wide work
		 * @return structural correction request
		 */
		public static ResolutionRequest forExamAssets(Exam exam, ExamBooklet booklet) {
			return new ResolutionRequest(null, ResolutionTarget.EXAM_ASSETS, exam, booklet);
		}

		/** Validates the correction request. */
		public ResolutionRequest {
			if (target == null) {
				throw new NullPointerException("target");
			}
			if (target == ResolutionTarget.EXAM_ASSETS) {
				if (exam == null) {
					throw new NullPointerException("exam");
				}
				if (question != null) {
					throw new IllegalArgumentException("Exam/Assets requests cannot contain a Question");
				}
				if (booklet != null && booklet.getExam().getId() != exam.getId()) {
					throw new IllegalArgumentException("Booklet must belong to the requested Exam");
				}
			} else if (target == ResolutionTarget.NEW_QUESTION_CAPTURE) {
				if (exam == null || booklet == null) {
					throw new NullPointerException("New Question capture requires an Exam and booklet");
				}
				if (question != null) {
					throw new IllegalArgumentException("New Question capture cannot contain an existing Question");
				}
				if (booklet.getExam().getId() != exam.getId()) {
					throw new IllegalArgumentException("Booklet must belong to the requested Exam");
				}
			} else {
				if (question == null) {
					throw new NullPointerException("question");
				}
				if (exam != null || booklet != null) {
					throw new IllegalArgumentException("Question correction requests cannot contain Exam assets");
				}
			}
		}
	}
}
