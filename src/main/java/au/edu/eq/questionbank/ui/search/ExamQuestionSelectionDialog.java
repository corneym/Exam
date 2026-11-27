package au.edu.eq.questionbank.ui.search;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Read-only Question Search dialog for selecting multiple Questions for an
 * examination draft.
 *
 * <p>
 * Existing curriculum navigation, metadata filters and Question previews remain
 * available. The destination examination section fixes the permitted response
 * type.
 *
 * <p>
 * Pending selections persist across Search filter changes. The authoritative
 * examination draft is not modified by this dialog.
 */
public final class ExamQuestionSelectionDialog extends Dialog<List<Question>> {

	private final ButtonType addButtonType = new ButtonType("Add Selected Questions", ButtonBar.ButtonData.OK_DONE);
	private final Map<Long, Question> checkedQuestions = new LinkedHashMap<>();
	private final Button clearSelectionButton = new Button("Clear selection");
	private final ExamBuilderDraft draft;
	private final QuestionSearchPane searchPane;
	private final ExamBuilderDraft.Section section;
	private final Label selectionSummary = new Label();
	private final Supplier<List<Question>> allQuestionsSupplier;
	private List<Question> corpus;
	private final Map<Long, List<Question>> eligibleGroups = new HashMap<>();

	/**
	 * Creates a read-only multiple-Question selection dialog.
	 *
	 * <p>
	 * Selecting any constituent part selects the entire top-level Question. The
	 * complete unfiltered corpus is used to resolve multipart membership.
	 *
	 * @param owner                         owning window
	 * @param draft                         current examination draft
	 * @param section                       destination examination section
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
		this.allQuestionsSupplier = Objects.requireNonNull(allQuestionsSupplier, "allQuestionsSupplier");
		initOwner(Objects.requireNonNull(owner, "owner"));
		searchPane = new QuestionSearchPane(draft.getSubject(), curriculumRepository, retrievalService, this::getCorpus,
				previewService, outputApplicabilityRepository,
				QuestionSearchNarrowing.forResponseType(section.responseType()), performanceRecorder);
		searchPane.setId("exam-question-search-pane");

		// Exam Builder cannot edit authoritative source records.
		searchPane.setReadOnlyQuestionPredicate(_ -> true);
		configureDialog();
		configureSelection();
		configureGeometry();
		setOnShowing(_ -> searchPane.resetToAllQuestionsOnOpen());
		setOnHidden(_ -> searchPane.dispose());
	}

	private void clearSelection() {
		checkedQuestions.clear();
		searchPane.refreshExamSelection();
		updateSelectionSummary();
	}

	private void configureDialog() {
		setTitle("Select Examination Questions");
		setHeaderText(section.toString());
		setResizable(true);
		getDialogPane().setId("exam-question-selection-dialog");
		selectionSummary.setId("exam-question-selection-summary");
		selectionSummary.setMaxWidth(Double.MAX_VALUE);
		clearSelectionButton.setId("exam-question-selection-clear");
		clearSelectionButton.setOnAction(_ -> clearSelection());
		HBox selectionRow = new HBox(12, selectionSummary, clearSelectionButton);
		selectionRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(selectionSummary, Priority.ALWAYS);
		VBox content = new VBox(8, searchPane, selectionRow);
		VBox.setVgrow(searchPane, Priority.ALWAYS);
		getDialogPane().setContent(content);
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
		addButton.setTooltip(
				new Tooltip("Add all checked eligible Questions. " + "Selecting any constituent part selects "
						+ "the entire multipart Question. " + "Selections remain checked when filters change."));
		searchPane.configureExamSelection(section.responseType(), this::resolveWholeQuestion,
				question -> draft.contains(question.getId()), checkedQuestions, this::updateSelectionSummary);
		setResultConverter(buttonType -> {
			if (buttonType != addButtonType || checkedQuestions.isEmpty()) {
				return null;
			}

			// Every selected constituent must belong to a
			// complete eligible group before returning the selection.
			for (Question question : checkedQuestions.values()) {
				List<Question> group = resolveWholeQuestion(question);
				if (group.isEmpty()
						|| group.stream().anyMatch(candidate -> !checkedQuestions.containsKey(candidate.getId()))) {
					return null;
				}
			}
			return List.copyOf(checkedQuestions.values());
		});
		updateSelectionSummary();
	}

	private synchronized List<Question> getCorpus() {
		if (corpus == null) {

			// Search and filter-catalogue background tasks may
			// request the corpus concurrently. Retrieve it only
			// once for the lifetime of this selection dialog.
			corpus = List.copyOf(allQuestionsSupplier.get());
		}
		return corpus;
	}

	private List<Question> resolveWholeQuestion(Question question) {
		List<Question> cached = eligibleGroups.get(question.getId());
		if (cached != null) {
			return cached;
		}
		List<Question> resolved = draft.eligibleWholeQuestion(section, question, getCorpus());
		eligibleGroups.put(question.getId(), resolved);

		// Every eligible constituent resolves to the same group.
		// Cache the group against all its constituent identities
		// to avoid repeatedly scanning the entire corpus.
		for (Question constituent : resolved) {
			eligibleGroups.put(constituent.getId(), resolved);
		}
		return resolved;
	}

	private void updateSelectionSummary() {
		int count = 0;
		boolean valid = true;
		for (Question question : checkedQuestions.values()) {
			List<Question> group = resolveWholeQuestion(question);
			if (group.isEmpty()
					|| group.stream().anyMatch(candidate -> !checkedQuestions.containsKey(candidate.getId()))) {
				valid = false;
				continue;
			}

			// Count each top-level Question once, using its
			// first constituent as the group representative.
			if (group.getFirst().getId() == question.getId()) {
				count++;
			}
		}
		int marks = checkedQuestions.values().stream().mapToInt(Question::getMarks).sum();
		selectionSummary.setText("%d selected — %d %s".formatted(count, marks, marks == 1 ? "mark" : "marks"));
		Button addButton = (Button) getDialogPane().lookupButton(addButtonType);
		addButton.setDisable(count == 0 || !valid);
		clearSelectionButton.setDisable(checkedQuestions.isEmpty());
	}
}
