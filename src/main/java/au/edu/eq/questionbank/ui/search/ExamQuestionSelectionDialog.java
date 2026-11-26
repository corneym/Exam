package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.assessment.QuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.ui.ScreenAwareWindows;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraft;
import javafx.beans.binding.Bindings;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

/**
 * Read-only Question Search dialog for selecting a Question for an examination
 * draft.
 *
 * <p>
 * Existing curriculum navigation, metadata filters and Question previews are
 * reused without permitting changes to authoritative Question Bank records.
 *
 * <p>
 * Each dialog instance is intended for one selection session.
 */
public final class ExamQuestionSelectionDialog extends Dialog<Question> {

	private final ButtonType addButtonType = new ButtonType("Add to Examination", ButtonBar.ButtonData.OK_DONE);
	private final ExamBuilderDraft draft;
	private final QuestionSearchPane searchPane;
	private final ExamBuilderDraft.Section section;

	/**
	 * Creates a read-only Question selection dialog.
	 *
	 * @param owner                         owning window
	 * @param draft                         current examination draft
	 * @param section                       section receiving the selected Question
	 * @param curriculumRepository          curriculum hierarchy lookup
	 * @param retrievalService              curriculum-aware Question retrieval
	 * @param allQuestionsSupplier          complete stored Question retrieval
	 * @param previewService                stored Question preview service
	 * @param outputApplicabilityRepository revision-output applicability lookup
	 * @param performanceRecorder           shared application diagnostics
	 * @throws NullPointerException if a required dependency is null
	 */
	public ExamQuestionSelectionDialog(Window owner, ExamBuilderDraft draft, ExamBuilderDraft.Section section,
			CurriculumRepository curriculumRepository, QuestionRetrievalService retrievalService,
			Supplier<List<Question>> allQuestionsSupplier, QuestionPreviewService previewService,
			QuestionOutputApplicabilityRepository outputApplicabilityRepository,
			PerformanceRecorder performanceRecorder) {
		this.draft = Objects.requireNonNull(draft, "draft");
		this.section = Objects.requireNonNull(section, "section");
		initOwner(Objects.requireNonNull(owner, "owner"));
		searchPane = new QuestionSearchPane(draft.getSubject(), curriculumRepository, retrievalService,
				allQuestionsSupplier, previewService, outputApplicabilityRepository,
				QuestionSearchNarrowing.unrestricted(), performanceRecorder);

		// Exam Builder may inspect any Question belonging
		// to its Subject, but must never edit source records.
		searchPane.setReadOnlyQuestionPredicate(_ -> true);
		configureDialog();
		configureSelection();
		configureGeometry();
		setOnShowing(_ -> searchPane.resetToAllQuestionsOnOpen());

		// Search owns asynchronous retrieval and preview tasks.
		// Cancel them when this single-use selection session ends.
		setOnHidden(_ -> searchPane.dispose());
	}

	private void configureDialog() {
		setTitle("Select Examination Question");
		setHeaderText(section.toString());
		setResizable(true);
		getDialogPane().setId("exam-question-selection-dialog");
		getDialogPane().setContent(searchPane);
		getDialogPane().getButtonTypes().setAll(addButtonType, ButtonType.CANCEL);
	}

	private void configureGeometry() {
		Rectangle2D bounds = ScreenAwareWindows.visualBounds(getOwner());
		double availableWidth = Math.max(300, bounds.getWidth() - 32);
		double availableHeight = Math.max(300, bounds.getHeight() - 48);
		getDialogPane().setMinWidth(Math.min(1100, availableWidth));
		getDialogPane().setPrefWidth(Math.min(1600, availableWidth));
		getDialogPane().setPrefHeight(Math.min(900, availableHeight));
	}

	private void configureSelection() {
		Button addButton = (Button) getDialogPane().lookupButton(addButtonType);
		addButton.setId("exam-question-selection-add");
		addButton.disableProperty().bind(Bindings.createBooleanBinding(
				() -> !draft.canAdd(section, searchPane.getSelectedQuestion()), searchPane.selectedResultProperty()));
		addButton
				.setTooltip(new Tooltip("Select a Question with a complete Answer " + "and the required response type. "
						+ "Questions already in the examination " + "cannot be selected again."));
		setResultConverter(buttonType -> {
			if (buttonType != addButtonType) {
				return null;
			}
			Question selected = searchPane.getSelectedQuestion();

			// Revalidate at confirmation rather than relying
			// exclusively on the button's enabled state.
			return draft.canAdd(section, selected) ? selected : null;
		});
	}
}
