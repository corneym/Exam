package au.edu.eq.questionbank.ui.capture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;

//Reuse the shared provider/year/booklet/natural Question ordering policy.
import au.edu.eq.questionbank.model.QuestionSourceOrder;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.ui.pdf.PdfFilePicker;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import au.edu.eq.questionbank.ui.pdf.SelectedPdf;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Owns unanswered-question selection, answer source and region state, textual
 * answers, validation, and creation or editing of persisted answers. All
 * control and capture-state access belongs on the JavaFX application thread.
 */
public final class AnswerCapturePane extends VBox {

	private static final double REGION_VIEWPORT_EXTRA_HEIGHT = 4.0;
	private static final double ANSWER_REGIONS_VIEWPORT_HEIGHT = 300.0;
	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double REGION_PREVIEW_HORIZONTAL_INSET = 40.0;
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String SECTION_HEADING_STYLE = "-fx-font-weight: bold;";
	private static final StringConverter<Question> QUESTION_CODE_CONVERTER = new StringConverter<>() {

		@Override
		public Question fromString(String string) {
			return null;
		}

		@Override
		public String toString(Question question) {
			if (question == null) {
				return "";
			}
			String answerState = question.hasAnswer() ? " — answered" : "";
			return String.format("%s %d — %s — %s — %s%s", question.getExam().getProvider().getName(),
					question.getExam().getYear(), question.getBooklet().getName(), question.getQuestionCode(),
					marksLabel(question.getMarks()), answerState);
		}
	};
	private final SourceDocumentHashService sourceDocumentHashService = new SourceDocumentHashService();

	// Workflow dependencies and application callbacks.
	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final Supplier<PdfSession> answerPdfSessionSupplier;
	private final PdfFilePicker pdfFilePicker;
	private final Consumer<SelectedPdf> answerPdfHandler;
	private final BiConsumer<SelectedPdf, Consumer<Throwable>> answerPdfLoader;
	private final Runnable selectionClearHandler;
	private final Runnable answerDocumentHandler;
	private final SqliteAnswerWriter answerWriter;
	private final BooleanSupplier answerTransitionAllowed;
	private final IntConsumer answerPageNavigationHandler;

	// Question and answer source selection.
	private final ComboBox<Question> unansweredQuestionField = new ComboBox<>();
	private final Label selectedAnswerQuestionLabel = new Label("No question selected");
	private final Button chooseAnswerPdfButton = new Button("Choose PDF...");
	private final Label selectedAnswerPdfLabel = new Label("No PDF selected");

	// Answer content and region controls.
	private final ToggleGroup multipleChoiceAnswerGroup = new ToggleGroup();
	private final RadioButton answerAButton = new RadioButton("A");
	private final RadioButton answerBButton = new RadioButton("B");
	private final RadioButton answerCButton = new RadioButton("C");
	private final RadioButton answerDButton = new RadioButton("D");

	// Multiple-choice controls use two rows so their full labels remain readable
	// at the minimum supported capture-workspace width.
	private final VBox multipleChoiceAnswerControls = new VBox(COMPACT_SPACING);
	private final Button clearMultipleChoiceAnswerButton = new Button("Clear choice");
	private final Button addAnswerRegionButton = new Button("Add Region");
	private final Button clearAnswerSelectionButton = new Button("Clear");
	private final Label answerRegionCountLabel = new Label("Regions: 0");
	private final Label answerRegionStatusLabel = new Label();
	private final VBox answerRegionListBox = new VBox(COMPACT_SPACING);

	// Keep the PDF action and potentially long filename on separate rows so the
	// action label remains readable at the minimum capture-workspace width.
	private final VBox answerPdfControls = new VBox(COMPACT_SPACING);
	private final ScrollPane answerRegionsScrollPane = new ScrollPane(answerRegionListBox);

	// Save and edit controls.
	private final Button saveAnswerButton = new Button("Save");
	private final Button cancelAnswerEditButton = new Button("Cancel");

	// Transient capture and edit state.
	private final List<AnswerRegion> pendingAnswerRegions = new ArrayList<>();
	private AnswerFile answerFile;
	private AnswerRegion currentAnswerSelection;
	private boolean restoringUnansweredQuestionSelection;

	// Working Subject is transient workspace state. A null value retains the
	// existing all-subject behaviour until the workspace selector is introduced.
	private Subject workingSubject;

	// A question-save snapshot may predate an answer committed while it was
	// loading.
	private final Set<Long> locallyAnsweredQuestionIds = new HashSet<>();
	private Question editingAnswerQuestion;
	private boolean answerSaveInProgress;
	private String preservedAnswerText;
	private Runnable answerEditCompletedHandler = () -> {
	};
	private final Button captureMcqExplanationsButton = new Button("Capture MCQ Explanations");
	private final ComboBox<Question> mcqExplanationQuestionField = new ComboBox<>();
	private final Button finishMcqExplanationCaptureButton = new Button("Done");
	private final HBox mcqExplanationSelectionControls = new HBox(CONTROL_SPACING);
	private boolean mcqExplanationMode;
	private boolean mcqExplanationLoadInProgress;
	private boolean restoringMcqExplanationSelection;
	private Question mcqExplanationReturnQuestion;
	private final Supplier<ExamBooklet> activeBookletSupplier;
	private Question mcqExplanationEditingQuestion;
	private boolean mcqExplanationEditSaved;

	/**
	 * Creates the answer-capture workflow and its persistence integration.
	 *
	 * @param stage                       owner used by PDF selection
	 * @param questionRepository          source of persisted Questions
	 * @param answerWriter                writer for Answer persistence
	 * @param pdfFilePicker               managed PDF-selection service
	 * @param answerPdfHandler            callback that registers a selected Answer
	 *                                    PDF
	 * @param answerDocumentHandler       callback that displays the Answer document
	 * @param answerTransitionAllowed     guard for changing Answer targets
	 * @param selectionClearHandler       callback that clears the shared PDF
	 *                                    selection
	 * @param questionExtractor           extractor used to preview accepted regions
	 * @param answerPdfSessionSupplier    supplier of the active Answer PDF session
	 * @param answerPageNavigationHandler callback that navigates Answer pages
	 * @param answerPdfLoader             asynchronous Answer PDF loader
	 * @param activeBookletSupplier       supplier of the Question booklet currently
	 *                                    active for capture
	 */
	public AnswerCapturePane(Stage stage, QuestionRepository questionRepository, SqliteAnswerWriter answerWriter,
			PdfFilePicker pdfFilePicker, Consumer<SelectedPdf> answerPdfHandler, Runnable answerDocumentHandler,
			BooleanSupplier answerTransitionAllowed, Supplier<ExamBooklet> activeBookletSupplier,
			Runnable selectionClearHandler, QuestionExtractor questionExtractor,
			Supplier<PdfSession> answerPdfSessionSupplier, IntConsumer answerPageNavigationHandler,
			BiConsumer<SelectedPdf, Consumer<Throwable>> answerPdfLoader) {
		if (questionRepository == null) {
			throw new NullPointerException("questionRepository");
		}
		if (pdfFilePicker == null) {
			throw new NullPointerException("pdfFilePicker");
		}
		if (answerPdfHandler == null) {
			throw new NullPointerException("answerPdfHandler");
		}
		if (selectionClearHandler == null) {
			throw new NullPointerException("selectionClearHandler");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		if (answerPdfSessionSupplier == null) {
			throw new NullPointerException("answerPdfSessionSupplier");
		}
		if (answerPageNavigationHandler == null) {
			throw new NullPointerException("answerPageNavigationHandler");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (answerDocumentHandler == null) {
			throw new NullPointerException("answerDocumentHandler");
		}
		if (answerTransitionAllowed == null) {
			throw new NullPointerException("answerTransitionAllowed");
		}
		if (activeBookletSupplier == null) {
			throw new NullPointerException("activeBookletSupplier");
		}
		this.questionRepository = questionRepository;
		this.pdfFilePicker = pdfFilePicker;
		this.answerPdfHandler = answerPdfHandler;
		this.answerPdfLoader = Objects.requireNonNull(answerPdfLoader, "answerPdfLoader");
		this.selectionClearHandler = selectionClearHandler;
		this.questionExtractor = questionExtractor;
		this.answerPdfSessionSupplier = answerPdfSessionSupplier;
		this.answerPageNavigationHandler = answerPageNavigationHandler;
		this.answerWriter = answerWriter;
		this.answerDocumentHandler = answerDocumentHandler;
		this.answerTransitionAllowed = answerTransitionAllowed;
		this.activeBookletSupplier = activeBookletSupplier;
		configureControls();
		configureActions(stage);
		getChildren().addAll(createSectionLabel("Answer"), unansweredQuestionField,
				createMcqExplanationWorkflowControls(), selectedAnswerQuestionLabel, createAnswerPdfControls(),
				createAnswerRegionControls(), answerRegionsScrollPane, createMultipleChoiceAnswerControls());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	static List<Question> unansweredQuestionsInSourceOrder(List<Question> questions,
			Set<Long> locallyAnsweredQuestionIds) {

		// Preserve the existing bank-wide behaviour for callers that do not yet supply
		// a Working Subject.
		return unansweredQuestionsInSourceOrder(questions, locallyAnsweredQuestionIds, null);
	}

	/**
	 * Returns Questions still awaiting Answers in deterministic source order,
	 * optionally restricted to one Working Subject.
	 *
	 * @param questions                  current Question snapshot
	 * @param locallyAnsweredQuestionIds Questions answered after an older snapshot
	 *                                   was loaded
	 * @param workingSubject             active Working Subject, or {@code null} for
	 *                                   all Subjects
	 * @return unanswered Questions in provider/year/booklet/natural-code order
	 */
	static List<Question> unansweredQuestionsInSourceOrder(List<Question> questions,
			Set<Long> locallyAnsweredQuestionIds, Subject workingSubject) {
		Objects.requireNonNull(questions, "questions");
		Objects.requireNonNull(locallyAnsweredQuestionIds, "locallyAnsweredQuestionIds");

		// Subject filtering is transient workspace state. It removes unrelated
		// Questions from the active queue without altering any persisted Question.
		return questions.stream()
				.filter(question -> workingSubject == null || question.getExam().getSubject().equals(workingSubject))
				.filter(question -> !question.hasAnswer() && !locallyAnsweredQuestionIds.contains(question.getId()))
				.sorted(QuestionSourceOrder.comparator()).toList();
	}

	private static String answerStatusPrefix(Question question) {
		return "Answering " + question.getQuestionCode() + " — " + marksLabel(question.getMarks());
	}

	private static String marksLabel(int marks) {
		return marks == 1 ? "1 mark" : marks + " marks";
	}

	/**
	 * Accepts a proportional answer-page selection as the current pending region.
	 *
	 * @param selection the selected answer-page rectangle
	 */
	public void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		if (!canCaptureRegions()) {
			currentAnswerSelection = null;
			selectionClearHandler.run();
			setSelectionActionsEnabled(false);
			return;
		}
		currentAnswerSelection = new AnswerRegion(answerFile, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		Question question = unansweredQuestionField.getValue();
		selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
		answerRegionStatusLabel.setText("Selection pending — Page " + selection.pageNumber());
		setSelectionActionsEnabled(true);
		refreshSaveButtonState();
	}

	/**
	 * Returns whether a region can currently be captured for the selected answer.
	 *
	 * @return {@code true} when the selected Answer workflow permits PDF-region
	 *         capture and an AnswerFile is available
	 */
	public boolean canCaptureRegions() {
		return !answerSaveInProgress && answerRegionsAvailableFor(unansweredQuestionField.getValue());
	}

	/**
	 * Starts Answer capture for an unanswered Question.
	 *
	 * @param question Question to capture
	 * @return {@code true} when the transition into capture was accepted
	 */
	public boolean captureAnswer(Question question) {
		if (answerSaveInProgress) {
			return false;
		}
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (question.hasAnswer()) {
			throw new IllegalArgumentException("Question already has an answer");
		}
		if (question.getResponseType() == QuestionResponseType.UNKNOWN) {
			throw new IllegalArgumentException("Question response type must be resolved before answer capture");
		}
		if (!answerTransitionAllowed.getAsBoolean()) {
			return false;
		}
		refreshQuestions();
		Question matching = unansweredQuestionField.getItems().stream()
				.filter(candidate -> candidate.getId() == question.getId()).findFirst().orElse(null);
		if (matching == null) {
			return false;
		}
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.setValue(matching);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
		applyUnansweredQuestionChange(matching);
		return true;
	}

	/**
	 * Discards an unaccepted region when the displayed page changes.
	 */
	public void clearCurrentSelectionForPageChange() {
		currentAnswerSelection = null;
		answerRegionStatusLabel.setText("");
		setSelectionActionsEnabled(false);
	}

	/**
	 * Discards only the unaccepted Answer selection after another capture workflow
	 * takes ownership of the single PDF selection.
	 */
	public void discardCurrentSelectionForOwnershipLoss() {

		// The new workflow already owns the visible PDF rectangle, so clear only this
		// pane's stale local state and never call selectionClearHandler here.
		currentAnswerSelection = null;
		setSelectionActionsEnabled(false);

		// Restore the status derived from any already accepted Answer regions.
		showAcceptedRegionStatus();
		refreshSaveButtonState();
	}

	/**
	 * Loads a persisted answer for editing after the transition guard succeeds. The
	 * selected question remains locked until the edit ends.
	 *
	 * @param question             the question with an existing answer
	 * @param editCompletedHandler callback when editing finishes or is cancelled
	 * @return whether the edit was started
	 * @throws IllegalArgumentException if the question has no answer
	 * @throws NullPointerException     if either argument is null
	 */
	public boolean editAnswer(Question question, Runnable editCompletedHandler) {
		if (answerSaveInProgress) {
			return false;
		}
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (editCompletedHandler == null) {
			throw new NullPointerException("editCompletedHandler");
		}
		if (!question.hasAnswer()) {
			throw new IllegalArgumentException("Question does not have an answer to edit");
		}
		if (!answerTransitionAllowed.getAsBoolean()) {
			return false;
		}
		clearPendingAnswerRegions();
		editingAnswerQuestion = question;
		answerEditCompletedHandler = editCompletedHandler;
		unansweredQuestionField.setDisable(true);
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.setValue(question);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
		applyUnansweredQuestionChange(question);

		// applyUnansweredQuestionChange restores the registered Answer PDF. Once that
		// document is available, position it at the first persisted Answer region.
		showFirstStoredAnswerRegionPage(question);
		saveAnswerButton.setText("Update Answer");
		cancelAnswerEditButton.setVisible(true);
		cancelAnswerEditButton.setManaged(true);
		return true;
	}

	/**
	 * Returns whether accepted Answer regions are held in transient capture state.
	 *
	 * @return whether accepted answer regions are loaded in the current capture or
	 *         edit
	 */
	public boolean hasAcceptedRegions() {
		return !pendingAnswerRegions.isEmpty();
	}

	/**
	 * Returns whether an existing Answer is currently open for editing.
	 *
	 * @return whether Answer edit state is active
	 */
	public boolean isEditingAnswer() {
		return editingAnswerQuestion != null;
	}

	/**
	 * Returns whether Answer persistence is in progress.
	 *
	 * @return whether answer persistence is currently running
	 */
	public boolean isSaveInProgress() {
		return answerSaveInProgress;
	}

	/**
	 * Reloads Answer capture after a booklet-level AnswerFile correction.
	 * <p>
	 * A correction may have deleted region-only Answers that were previously added
	 * to the local answered-ID suppression set, so persistence becomes
	 * authoritative again before rebuilding the queue.
	 */
	public void refreshAfterAnswerFileCorrection() {
		if (answerSaveInProgress || editingAnswerQuestion != null || !pendingAnswerRegions.isEmpty()
				|| currentAnswerSelection != null) {
			throw new IllegalStateException("Cannot refresh Answer capture while Answer work is in progress");
		}

		// Persisted correction may have made previously answered written-response
		// Questions unanswered again.
		locallyAnsweredQuestionIds.clear();
		answerFile = null;
		selectedAnswerPdfLabel.setText("No PDF selected");
		refreshQuestions();

		// refreshQuestions deliberately suppresses its selection listener, so
		// explicitly
		// rebuild the presentation and reopen the newly assigned booklet AnswerFile.
		applyUnansweredQuestionChange(unansweredQuestionField.getValue());
	}

	/**
	 * Reloads persisted questions that do not yet have an answer.
	 */
	public void refreshQuestions() {
		refreshQuestions(questionRepository.findAll());
	}

	/**
	 * Rebuilds the unanswered queue from a supplied Question snapshot.
	 *
	 * @param questions complete Questions to filter and order
	 */
	public void refreshQuestions(List<Question> questions) {
		Question editTarget = editingAnswerQuestion;
		Question selected = editTarget == null ? unansweredQuestionField.getValue() : editTarget;

		// Build the Answer work queue independently of repository insertion order so
		// that sustained Answer capture follows the examination's natural source order.
		// Apply the transient Working Subject while constructing the visible Answer
		// queue. No persisted Question or Answer data is changed by this filter.
		List<Question> unansweredQuestions = unansweredQuestionsInSourceOrder(questions, locallyAnsweredQuestionIds,
				workingSubject);
		Question matching = editTarget;
		if (matching == null && selected != null) {
			for (Question question : unansweredQuestions) {

				// Preserve the currently selected Question across queue refreshes by persistent
				// identity rather than object instance.
				if (question.getId() == selected.getId()) {
					matching = question;
					break;
				}
			}
		}

		// Suppress the normal selection listener while replacing the queue and
		// restoring the previous selection.
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.getItems().setAll(unansweredQuestions);
			unansweredQuestionField.setValue(matching);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}

		// Activation of another Exam booklet rebuilds this queue, so use the same
		// publication point to recalculate retrofit availability.
		refreshMcqExplanationActionState();
	}

	/**
	 * Reopens the current Question's registered Answer PDF after managed source
	 * paths may have changed.
	 */
	public void reopenSelectedAnswerDocument() {
		Question question = unansweredQuestionField.getValue();
		if (answerSaveInProgress || !pendingAnswerRegions.isEmpty() || currentAnswerSelection != null) {
			throw new IllegalStateException("Cannot reopen the Answer document while Answer capture is in progress");
		}

		// The old AnswerFile object contains the pre-correction SourceDocument path.
		// Force a fresh database lookup rather than reusing that stale object.
		answerFile = null;
		selectedAnswerPdfLabel.setText("No PDF selected");
		if (question == null || !usesAnswerDocument(question)) {
			updateAnswerPdfControlsVisibility(question);
			return;
		}
		applyUnansweredQuestionChange(question);
	}

	/**
	 * Changes the transient Subject used to filter the Answer-capture work queue.
	 *
	 * @param workingSubject Subject to display, or {@code null} to display all
	 *                       Subjects
	 */
	public void setWorkingSubject(Subject workingSubject) {

		// Preserve the existing standalone API while allowing the application-level
		// Subject coordinator to bypass this repository read.
		setWorkingSubject(workingSubject, questionRepository.findAll());
	}

	/**
	 * Changes the transient Working Subject and rebuilds the Answer work queue from
	 * an already-loaded Question snapshot.
	 *
	 * @param workingSubject Subject to display, or {@code null} to display all
	 *                       Subjects
	 * @param questions      complete Question snapshot to filter
	 * @throws NullPointerException if {@code questions} is {@code null}
	 */
	public void setWorkingSubject(Subject workingSubject, List<Question> questions) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		Question previouslySelected = unansweredQuestionField.getValue();
		if (mcqExplanationMode && editingAnswerQuestion == null) {

			// Retrofit candidates belong to exactly one Working Subject. An accepted
			// Subject transition discards that presentation before the replacement
			// Subject snapshot is published.
			resetMcqExplanationPresentation();
		}
		this.workingSubject = workingSubject;
		refreshMcqExplanationActionState();

		// Reuse the application-owned corpus snapshot instead of independently reading
		// the complete Question repository.
		refreshQuestions(questions);
		if (previouslySelected != null && unansweredQuestionField.getValue() == null) {

			// The old target no longer belongs to the accepted Working Subject. Clear its
			// presentation as well as removing it from the queue.
			applyUnansweredQuestionChange(null, false);
		}
	}

	/**
	 * @return whether an answer PDF has been selected or restored for the current
	 *         question
	 */
	boolean hasAnswerFile() {
		return answerFile != null;
	}

	/**
	 * Persists and opens the answer PDF selected for a question.
	 *
	 * @param question    the question being answered
	 * @param selectedPdf the selected answer PDF
	 */
	void selectAnswerPdf(Question question, SelectedPdf selectedPdf) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (selectedPdf == null) {
			throw new NullPointerException("selectedPdf");
		}
		try {

			// Hash the managed Answer PDF before publishing its SourceDocument so the
			// persisted identity always describes the authoritative stored bytes.
			String contentSha256 = sourceDocumentHashService.sha256(selectedPdf.path());

			// Register or reuse the AnswerFile and persist its relationship to this
			// specific ExamBooklet.
			answerFile = answerWriter.findOrCreateAnswerFile(question.getBooklet(), selectedPdf.file().getName(),
					selectedPdf.relativePath(), contentSha256);
		} catch (IOException | SQLException exception) {
			throw new IllegalStateException("Unable to save answer PDF", exception);
		}

		// Persistence succeeds before the document becomes active in the workspace.
		answerPdfHandler.accept(selectedPdf);
		selectedAnswerPdfLabel.setText(answerFile.getName());

		// Selecting or reusing an AnswerFile can change whether an MCQ is permitted to
		// capture optional explanation regions. Recompute both PDF and region controls
		// from the authoritative persisted AnswerFile immediately.
		updateAnswerPdfControlsVisibility(question);
		updateAnswerRegionControlsVisibility(question);
		refreshAnswerRegionList();
	}

	private void addCurrentAnswerRegion() {
		if (currentAnswerSelection == null) {
			return;
		}
		pendingAnswerRegions.add(currentAnswerSelection);
		refreshAnswerRegionList();
		currentAnswerSelection = null;
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);
		showAcceptedRegionStatus();
		refreshSaveButtonState();
	}

	private boolean answerRegionControlsVisibleFor(Question question) {
		if (isWrittenResponseQuestion(question)) {

			// Written-response Questions always expose their Answer-region workflow.
			// Capture itself remains disabled until an AnswerFile is available.
			return true;
		}

		// MCQ regions are supplementary explanation material and therefore appear
		// only when the assigned AnswerFile explicitly declares that capability.
		return isMultipleChoiceQuestion(question) && answerFile != null && answerFile.hasAnswerExplanations();
	}

	private boolean answerRegionsAvailableFor(Question question) {

		// Region capture requires both a visible region workflow and an authoritative
		// AnswerFile from which region coordinates can be captured.
		return answerFile != null && answerRegionControlsVisibleFor(question);
	}

	private double answerRegionsPreferredHeight() {

		// Measure what the accepted-region content wants to be, rather than its
		// current laid-out height. The latter can still be near zero immediately
		// after the first preview is added.
		double contentHeight = answerRegionListBox.prefHeight(answerRegionListBox.getWidth());
		return Math.min(ANSWER_REGIONS_VIEWPORT_HEIGHT, contentHeight + REGION_VIEWPORT_EXTRA_HEIGHT);
	}

	private void applyRestoredQuestionSelection(Question previousQuestion) {
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.setValue(previousQuestion);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
	}

	private void applyUnansweredQuestionChange(Question question) {
		applyUnansweredQuestionChange(question, true);
	}

	private void applyUnansweredQuestionChange(Question question, boolean loadDocument) {
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		if (question == null) {

			// No active Question means no booklet-specific AnswerFile can remain current.
			answerFile = null;
			selectedAnswerPdfLabel.setText("No PDF selected");
			selectedAnswerQuestionLabel.setText("No question selected");
			updateMultipleChoiceAnswerVisibility(null);
			updateAnswerRegionControlsVisibility(null);
			saveAnswerButton.setDisable(true);
			chooseAnswerPdfButton.setDisable(true);
			saveAnswerButton.setText("Save Answer");
			answerRegionCountLabel.setText("Regions: 0");
			answerRegionStatusLabel.setText("");
			updateAnswerPdfControlsVisibility(null);
			return;
		}
		boolean usesAnswerDocument = usesAnswerDocument(question);

		// UNKNOWN Questions cannot own an AnswerFile until their response type has been
		// resolved.
		if (!usesAnswerDocument) {
			answerFile = null;
			selectedAnswerPdfLabel.setText("No PDF selected");
		}
		if (question.hasAnswer()) {
			Answer answer = question.getAnswer();
			selectMultipleChoiceAnswer(answer.getAnswerText());

			// Persisted Answer regions remain authoritative Answer content. The booklet
			// mapping determines which document is displayed for those regions.
			pendingAnswerRegions.addAll(answer.getRegions());
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — answer stored");
			saveAnswerButton.setText("Update Answer");
		} else {
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
			saveAnswerButton.setText("Save Answer");
			answerRegionCountLabel.setText("Regions: 0");
			answerRegionStatusLabel.setText("");
		}
		if (loadDocument && usesAnswerDocument) {

			// Resolve the active Answer PDF from the Question's booklet, never merely
			// from another Question belonging to the same Exam.
			loadAssignedAnswerFile(question);
		}
		refreshAnswerRegionList();
		if (question.hasAnswer()) {
			showAcceptedRegionStatus();
		}
		updateMultipleChoiceAnswerVisibility(question);
		updateAnswerRegionControlsVisibility(question);

		// MCQ and written-response Questions may both have an assigned answer document.
		chooseAnswerPdfButton.setDisable(!usesAnswerDocument);
		updateAnswerPdfControlsVisibility(question);
		if (question.getResponseType() == QuestionResponseType.UNKNOWN) {
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — response type unresolved; "
					+ "use Edit Metadata before capturing an answer.");
		}
		refreshSaveButtonState();
	}

	private void beginMcqExplanationCapture() {
		if (mcqExplanationMode || mcqExplanationLoadInProgress || workingSubject == null || answerSaveInProgress
				|| editingAnswerQuestion != null) {
			return;
		}
		ExamBooklet requestedBooklet = activeBookletSupplier.get();
		if (requestedBooklet == null) {

			// The workflow is booklet-scoped. It cannot infer a target merely from the
			// Working Subject or an Exam selected elsewhere.
			return;
		}
		if (hasUnsavedOrdinaryAnswerDraft()) {
			showAnswerWorkflowWarning("Unsaved Answer work",
					"Save or clear the current Answer work before capturing MCQ explanations.");
			return;
		}
		if (!answerTransitionAllowed.getAsBoolean()) {
			return;
		}
		Subject requestedSubject = workingSubject;
		mcqExplanationLoadInProgress = true;
		captureMcqExplanationsButton.setDisable(true);
		captureMcqExplanationsButton.setText("Loading...");
		Task<List<Question>> task = new Task<>() {

			@Override
			protected List<Question> call() throws Exception {

				// Candidate discovery and AnswerFile lookup are persistence work and stay
				// off the JavaFX application thread.
				return loadMcqExplanationCandidates(questionRepository.findAll(), requestedSubject, requestedBooklet);
			}
		};
		task.setOnSucceeded(_ -> {
			finishMcqExplanationCandidateLoad();
			if (!Objects.equals(workingSubject, requestedSubject)
					|| !sameBooklet(requestedBooklet, activeBookletSupplier.get())) {

				// Subject or active-booklet context changed while the worker was running.
				// Its result is stale and must never enter the current workspace.
				return;
			}
			List<Question> candidates = task.getValue();
			if (candidates.isEmpty()) {
				selectedAnswerQuestionLabel
						.setText("No answered MCQs in the active booklet are available for explanation capture.");
				return;
			}
			enterMcqExplanationCapture(candidates);
		});
		task.setOnFailed(_ -> {
			finishMcqExplanationCandidateLoad();
			if (!Objects.equals(workingSubject, requestedSubject)
					|| !sameBooklet(requestedBooklet, activeBookletSupplier.get())) {
				return;
			}
			Throwable failure = task.getException();
			String message = failure == null || failure.getMessage() == null
					? "The eligible Questions could not be loaded."
					: failure.getMessage();
			showAnswerFileError("MCQ explanation Questions could not be loaded.", message);
		});
		Thread.ofVirtual().name("mcq-explanation-candidates").start(task);
	}

	private void cancelAnswerEdit() {
		if (editingAnswerQuestion == null) {
			return;
		}

		// Retrofit mode already owns an immutable candidate list and the ordinary
		// unanswered queue remains intact. Cancelling one explanation edit therefore
		// needs no synchronous repository reload.
		finishAnswerEdit(!mcqExplanationMode);
	}

	private void chooseAnswerPdf(Stage stage) {
		Question question = unansweredQuestionField.getValue();
		if (question == null) {
			showError("Select a question first.");
			return;
		}
		if (!usesAnswerDocument(question)) {

			// UNKNOWN response types must be resolved before an answer source is attached.
			return;
		}
		Path sourcePath = pdfFilePicker.chooseAnyPdf(stage, "Choose answer PDF");
		if (sourcePath == null) {
			return;
		}
		if (!answerTransitionAllowed.getAsBoolean()) {
			return;
		}
		if (hasAcceptedRegions()) {
			clearPendingAnswerRegions();
		}
		Exam exam = question.getExam();
		PdfStore pdfStore = new PdfStore(pdfFilePicker.dataRoot());
		try {
			Path storedPath = pdfStore.importExamPdf(sourcePath, exam.getSubject().getName(),
					exam.getProvider().getName(), exam.getYear());
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, pdfFilePicker.dataRoot());
			selectAnswerPdf(question, selectedPdf);
		} catch (IOException e) {
			showAnswerPdfError(e.getMessage());
		}
	}

	private void clearCurrentAnswerSelection() {
		currentAnswerSelection = null;
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);
		Question question = unansweredQuestionField.getValue();
		if (question != null) {
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
		}
		showAcceptedRegionStatus();
		refreshSaveButtonState();
	}

	private void clearMcqExplanationSelection() {
		restoringMcqExplanationSelection = true;
		try {
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}
	}

	private void clearMultipleChoiceAnswer() {
		multipleChoiceAnswerGroup.selectToggle(null);
		preservedAnswerText = null;
	}

	private void clearPendingAnswerRegions() {
		pendingAnswerRegions.clear();
		currentAnswerSelection = null;
		answerRegionListBox.getChildren().clear();
		answerRegionsScrollPane.setVisible(false);
		answerRegionsScrollPane.setManaged(false);
		answerRegionCountLabel.setText("Regions: 0");
		answerRegionStatusLabel.setText("");
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);
		refreshSaveButtonState();
	}

	// Update local choices only after persistence succeeds, then load the next PDF
	// asynchronously.
	private void completeAnswerSave(Question question, Answer answer, boolean editing, int previousIndex) {
		question.setAnswer(answer);
		locallyAnsweredQuestionIds.add(question.getId());
		if (editing) {
			if (mcqExplanationMode && sameQuestion(question, mcqExplanationEditingQuestion)) {

				// Only a successfully persisted retrofit edit consumes its current
				// candidate. Cancel and failed saves must leave it available.
				mcqExplanationEditSaved = true;
			}
			Runnable completedHandler = null;
			try {
				completedHandler = finishAnswerEditState(false);
			} finally {
				finishAnswerSaveTransition(null);
			}
			if (completedHandler != null) {
				completedHandler.run();
			}
			return;
		}
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		Question next = removeSavedQuestionAndSelectNext(question, previousIndex);
		loadNextAnswerDocument(next);
	}

	private void completeMcqExplanationEdit() {
		if (!mcqExplanationMode) {
			return;
		}
		Question completedQuestion = mcqExplanationEditingQuestion;
		boolean saved = mcqExplanationEditSaved;
		mcqExplanationEditingQuestion = null;
		mcqExplanationEditSaved = false;
		unansweredQuestionField.setDisable(true);
		mcqExplanationQuestionField.setDisable(false);
		finishMcqExplanationCaptureButton.setDisable(false);
		if (!saved || completedQuestion == null) {

			// Cancel returns to the selector but deliberately retains the candidate.
			clearMcqExplanationSelection();
			selectedAnswerQuestionLabel.setText("Select an answered MCQ to capture explanation regions.");
			return;
		}
		int completedIndex = -1;
		for (int index = 0; index < mcqExplanationQuestionField.getItems().size(); index++) {
			if (mcqExplanationQuestionField.getItems().get(index).getId() == completedQuestion.getId()) {
				completedIndex = index;
				break;
			}
		}
		restoringMcqExplanationSelection = true;
		try {

			// Remove only from this workflow session. No persisted per-MCQ explanation
			// completeness state is inferred or stored.
			if (completedIndex >= 0) {
				mcqExplanationQuestionField.getItems().remove(completedIndex);
			}
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}
		if (mcqExplanationQuestionField.getItems().isEmpty()) {
			selectedAnswerQuestionLabel.setText("No remaining MCQ explanation candidates in this booklet.");
			return;
		}
		int nextIndex = completedIndex < 0 ? 0
				: Math.min(completedIndex, mcqExplanationQuestionField.getItems().size() - 1);

		// Ordinary ComboBox selection is intentional here. Its listener enters the
		// existing Answer editor for the next candidate immediately.
		mcqExplanationQuestionField.getSelectionModel().select(nextIndex);
	}

	private void configureActions(Stage stage) {
		addAnswerRegionButton.setOnAction(_ -> addCurrentAnswerRegion());
		chooseAnswerPdfButton.setOnAction(_ -> chooseAnswerPdf(stage));
		clearAnswerSelectionButton.setOnAction(_ -> clearCurrentAnswerSelection());
		saveAnswerButton.setOnAction(_ -> validateAnswerForSave());
		cancelAnswerEditButton.setOnAction(_ -> cancelAnswerEdit());
		captureMcqExplanationsButton.setOnAction(_ -> beginMcqExplanationCapture());
		mcqExplanationQuestionField.valueProperty()
				.addListener((_, _, question) -> handleMcqExplanationQuestionChanged(question));
		finishMcqExplanationCaptureButton.setOnAction(_ -> finishMcqExplanationCapture());
		clearMultipleChoiceAnswerButton.setOnAction(_ -> {

			// Clearing the A-D choice changes only transient Answer state until Save.
			clearMultipleChoiceAnswer();
			refreshSaveButtonState();
		});
	}

	private void configureControls() {
		unansweredQuestionField.setId("unanswered-question");
		unansweredQuestionField.setPromptText("Select unanswered question");
		unansweredQuestionField
				.setTooltip(new Tooltip("Lists Questions without an Answer in source order for the current booklet."));
		unansweredQuestionField.setMaxWidth(Double.MAX_VALUE);
		unansweredQuestionField.setConverter(QUESTION_CODE_CONVERTER);
		unansweredQuestionField.setOnShowing(_ -> showSelectedAnswerDocument());
		unansweredQuestionField.valueProperty().addListener(
				(_, oldQuestion, newQuestion) -> handleUnansweredQuestionChanged(oldQuestion, newQuestion));
		answerAButton.setId("answer-choice-a");
		answerBButton.setId("answer-choice-b");
		answerCButton.setId("answer-choice-c");
		answerDButton.setId("answer-choice-d");
		answerPdfControls.setId("answer-pdf-controls");

		// The PDF action retains its full label, while the selected filename may wrap
		// across the available pane width instead of competing horizontally with it.
		chooseAnswerPdfButton.setMinWidth(Region.USE_PREF_SIZE);
		selectedAnswerPdfLabel.setId("selected-answer-pdf");
		selectedAnswerPdfLabel.setWrapText(true);
		selectedAnswerPdfLabel.setMaxWidth(Double.MAX_VALUE);
		multipleChoiceAnswerControls.setId("multiple-choice-answer-controls");
		answerAButton.setToggleGroup(multipleChoiceAnswerGroup);
		answerBButton.setToggleGroup(multipleChoiceAnswerGroup);
		answerCButton.setToggleGroup(multipleChoiceAnswerGroup);
		answerDButton.setToggleGroup(multipleChoiceAnswerGroup);
		answerAButton.setUserData("A");
		answerBButton.setUserData("B");
		answerCButton.setUserData("C");
		answerDButton.setUserData("D");
		String choiceTooltip = "Store the selected A-D choice as this multiple-choice Question's Answer.";
		answerAButton.setTooltip(new Tooltip(choiceTooltip));
		answerBButton.setTooltip(new Tooltip(choiceTooltip));
		answerCButton.setTooltip(new Tooltip(choiceTooltip));
		answerDButton.setTooltip(new Tooltip(choiceTooltip));
		clearMultipleChoiceAnswerButton.setId("clear-answer-choice");
		multipleChoiceAnswerGroup.selectedToggleProperty().addListener((_, _, newToggle) -> {
			if (newToggle != null) {
				preservedAnswerText = null;
			}
			refreshSaveButtonState();
		});
		setMultipleChoiceAnswerEnabled(false);
		saveAnswerButton.setId("save-answer");
		saveAnswerButton.setDisable(true);
		saveAnswerButton.setText("Save Answer");
		saveAnswerButton.setTooltip(new Tooltip(
				"Persist the selected choice and any optional explanation regions, or the required written-response regions."));
		chooseAnswerPdfButton.setDisable(true);
		chooseAnswerPdfButton.setId("choose-answer-pdf");
		chooseAnswerPdfButton
				.setTooltip(new Tooltip("Open the Answer or marking PDF assigned to this Question booklet."));
		addAnswerRegionButton.setId("add-answer-region");
		addAnswerRegionButton.setTooltip(new Tooltip(
				"Accept the current marking-PDF selection as Answer content or optional MCQ explanation material."));
		chooseAnswerPdfButton.setDisable(true);
		chooseAnswerPdfButton.setId("choose-answer-pdf");
		chooseAnswerPdfButton.setTooltip(new Tooltip(
				"Choose the Answer PDF assigned to this booklet before capturing written-response regions."));
		addAnswerRegionButton.setId("add-answer-region");
		addAnswerRegionButton
				.setTooltip(new Tooltip("Accept the current Answer PDF selection as part of this Question's Answer."));
		clearAnswerSelectionButton.setId("clear-answer-selection");
		answerRegionCountLabel.setId("answer-region-count");
		answerRegionStatusLabel.setId("answer-region-status");
		answerRegionStatusLabel.setText("");

		// Preserve the full visible text of Answer-capture actions when the capture
		// workspace is at its supported minimum width.
		addAnswerRegionButton.setMinWidth(Region.USE_PREF_SIZE);
		clearAnswerSelectionButton.setMinWidth(Region.USE_PREF_SIZE);
		answerRegionCountLabel.setMinWidth(Region.USE_PREF_SIZE);
		saveAnswerButton.setMinWidth(Region.USE_PREF_SIZE);
		cancelAnswerEditButton.setMinWidth(Region.USE_PREF_SIZE);

		// Status messages vary in length, so they wrap instead of competing with action
		// buttons for horizontal space.
		answerRegionStatusLabel.setWrapText(true);
		answerRegionStatusLabel.setMaxWidth(Double.MAX_VALUE);
		selectedAnswerQuestionLabel.setId("selected-answer-question");

		// Answer status can include marks, stored-answer state, and workflow warnings.
		// Allow it to use multiple lines instead of truncating at narrow widths.
		selectedAnswerQuestionLabel.setWrapText(true);
		selectedAnswerQuestionLabel.setMaxWidth(Double.MAX_VALUE);
		cancelAnswerEditButton.setId("cancel-answer-edit");
		cancelAnswerEditButton.setVisible(false);
		cancelAnswerEditButton.setManaged(false);
		answerRegionsScrollPane.setFitToWidth(true);
		answerRegionsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		answerRegionsScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		answerRegionListBox.setFillWidth(true);
		answerRegionListBox.setMaxWidth(Double.MAX_VALUE);
		answerRegionsScrollPane.setMinHeight(0);
		answerRegionsScrollPane.setMaxHeight(ANSWER_REGIONS_VIEWPORT_HEIGHT);

		// Size the accepted-region area to its actual content instead of immediately
		// claiming the full 300 px viewport height when the first region is added.
		answerRegionsScrollPane.prefHeightProperty()
				.bind(Bindings.createDoubleBinding(this::answerRegionsPreferredHeight,

						// Adding or removing a preview changes the preferred content height.
						answerRegionListBox.getChildren(),

						// Resizing the Answer pane changes the preview width and therefore
						// the height required to preserve the image aspect ratio.
						widthProperty()));
		answerRegionsScrollPane.setMaxWidth(Double.MAX_VALUE);
		answerRegionsScrollPane.setVisible(false);
		answerRegionsScrollPane.setManaged(false);
		setSelectionActionsEnabled(false);

		// MCQ explanation retrofit is explicitly entered and remains unavailable until
		// the application supplies an authoritative Working Subject.
		configureMcqExplanationControls();
	}

	private void configureMcqExplanationControls() {
		captureMcqExplanationsButton.setId("capture-mcq-explanations");
		captureMcqExplanationsButton.setMinWidth(Region.USE_PREF_SIZE);
		captureMcqExplanationsButton.setDisable(true);
		captureMcqExplanationsButton.setTooltip(new Tooltip(
				"Add or edit optional marking-PDF explanation regions for already-answered multiple-choice Questions."));
		mcqExplanationQuestionField.setId("mcq-explanation-question");
		mcqExplanationQuestionField.setPromptText("Select answered MCQ");
		mcqExplanationQuestionField.setConverter(QUESTION_CODE_CONVERTER);
		mcqExplanationQuestionField.setMaxWidth(Double.MAX_VALUE);
		finishMcqExplanationCaptureButton.setId("finish-mcq-explanations");
		finishMcqExplanationCaptureButton.setMinWidth(Region.USE_PREF_SIZE);

		// The answered-MCQ selector replaces the entry action only while the explicit
		// retrofit workflow is active.
		HBox.setHgrow(mcqExplanationQuestionField, Priority.ALWAYS);
		mcqExplanationSelectionControls.getChildren().setAll(mcqExplanationQuestionField,
				finishMcqExplanationCaptureButton);
		mcqExplanationSelectionControls.setAlignment(Pos.CENTER_LEFT);
		mcqExplanationSelectionControls.setVisible(false);
		mcqExplanationSelectionControls.setManaged(false);
	}

	private ImageView createAcceptedAnswerPreview(AnswerRegion region) {
		try {
			BufferedImage clippedImage = questionExtractor.extractRegion(answerPdfSessionSupplier.get(), region);
			ImageView previewView = new ImageView(SwingFXUtils.toFXImage(clippedImage, null));
			previewView.setPreserveRatio(true);
			previewView.setSmooth(true);
			previewView.setCache(true);

			// Bind to the containing Answer pane rather than the ScrollPane viewport. The
			// Answer pane is already laid out when the first region is accepted, and its
			// width is unaffected by the ScrollPane's vertical scrollbar.
			previewView.fitWidthProperty().bind(Bindings.createDoubleBinding(
					() -> Math.max(0.0, getWidth() - REGION_PREVIEW_HORIZONTAL_INSET), widthProperty()));
			return previewView;
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview accepted answer region", e);
		}
	}

	private VBox createAnswerPdfControls() {

		// A filename can be much wider than the capture workspace. Give it a dedicated
		// wrapping row so it can never force the Choose PDF action below its readable
		// width.
		answerPdfControls.getChildren().setAll(chooseAnswerPdfButton, selectedAnswerPdfLabel);
		answerPdfControls.setFillWidth(true);
		return answerPdfControls;
	}

	private VBox createAnswerRegionControls() {

		// Keep pending-selection actions together without placing variable-length
		// status text on the same horizontal row.
		HBox selectionControls = new HBox(CONTROL_SPACING, addAnswerRegionButton, clearAnswerSelectionButton,
				answerRegionCountLabel);
		selectionControls.setAlignment(Pos.CENTER_LEFT);

		// Save and Cancel occupy their own row so their labels retain their preferred
		// widths independently of capture status text.
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox saveControls = new HBox(CONTROL_SPACING, spacer, saveAnswerButton, cancelAnswerEditButton);
		saveControls.setAlignment(Pos.CENTER_LEFT);

		// The status label occupies a dedicated wrapping row between selection and
		// persistence actions.
		VBox controls = new VBox(COMPACT_SPACING, selectionControls, answerRegionStatusLabel, saveControls);
		controls.setFillWidth(true);
		return controls;
	}

	private VBox createMcqExplanationWorkflowControls() {

		// Normal Answer capture exposes one explicit entry action. Retrofit mode then
		// replaces that action with its answered-MCQ selector and Done control.
		VBox controls = new VBox(COMPACT_SPACING, captureMcqExplanationsButton, mcqExplanationSelectionControls);
		controls.setFillWidth(true);
		return controls;
	}

	private VBox createMultipleChoiceAnswerControls() {
		Label label = new Label("Multiple choice answer:");
		label.setId("multiple-choice-answer-label");

		// Keep each choice and the Clear choice action at its preferred width so JavaFX
		// cannot shorten their visible text when the capture workspace is narrow.
		answerAButton.setMinWidth(Region.USE_PREF_SIZE);
		answerBButton.setMinWidth(Region.USE_PREF_SIZE);
		answerCButton.setMinWidth(Region.USE_PREF_SIZE);
		answerDButton.setMinWidth(Region.USE_PREF_SIZE);
		clearMultipleChoiceAnswerButton.setMinWidth(Region.USE_PREF_SIZE);

		// Put the prompt on its own row. The second row then contains only the compact
		// answer choices and Clear choice action, which fit comfortably at 400 px.
		HBox choiceControls = new HBox(CONTROL_SPACING, answerAButton, answerBButton, answerCButton, answerDButton,
				clearMultipleChoiceAnswerButton);
		choiceControls.setAlignment(Pos.CENTER_LEFT);
		multipleChoiceAnswerControls.getChildren().setAll(label, choiceControls);
		multipleChoiceAnswerControls.setFillWidth(true);
		multipleChoiceAnswerControls.setVisible(false);
		multipleChoiceAnswerControls.setManaged(false);
		return multipleChoiceAnswerControls;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private String currentAnswerText() {
		if (multipleChoiceAnswerGroup.getSelectedToggle() != null) {
			return (String) multipleChoiceAnswerGroup.getSelectedToggle().getUserData();
		}
		return preservedAnswerText == null ? "" : preservedAnswerText;
	}

	private void enterMcqExplanationCapture(List<Question> candidates) {
		mcqExplanationMode = true;
		mcqExplanationReturnQuestion = unansweredQuestionField.getValue();

		// Ordinary unanswered capture and retrofit selection are separate explicit
		// modes. Hide the ordinary selector rather than presenting two active targets.
		unansweredQuestionField.setVisible(false);
		unansweredQuestionField.setManaged(false);
		unansweredQuestionField.setDisable(true);
		captureMcqExplanationsButton.setVisible(false);
		captureMcqExplanationsButton.setManaged(false);
		mcqExplanationSelectionControls.setVisible(true);
		mcqExplanationSelectionControls.setManaged(true);
		restoringMcqExplanationSelection = true;
		try {
			mcqExplanationQuestionField.getItems().setAll(candidates);
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}

		// Clear the ordinary Answer presentation while retaining its queue for return
		// when retrofit mode ends.
		applyUnansweredQuestionChange(null, false);
		selectedAnswerQuestionLabel.setText("Select an answered MCQ to capture explanation regions.");
	}

	private String findValidationError(Question question, String answerText) {
		QuestionResponseType responseType = question == null ? null : question.getResponseType();
		return AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(question != null, responseType,
				currentAnswerSelection != null, answerText, pendingAnswerRegions.size()));
	}

	private void finishAnswerEdit() {
		finishAnswerEdit(true);
	}

	private void finishAnswerEdit(boolean reloadQuestions) {
		finishAnswerEditState(reloadQuestions).run();
	}

	private Runnable finishAnswerEditState(boolean reloadQuestions) {
		Runnable completedHandler = answerEditCompletedHandler;
		answerEditCompletedHandler = () -> {
		};
		editingAnswerQuestion = null;
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		answerFile = null;
		selectedAnswerPdfLabel.setText("No PDF selected");
		unansweredQuestionField.setDisable(false);
		cancelAnswerEditButton.setVisible(false);
		cancelAnswerEditButton.setManaged(false);
		saveAnswerButton.setText("Save Answer");
		if (reloadQuestions) {
			refreshQuestions();
		} else {
			refreshQuestions(List.copyOf(unansweredQuestionField.getItems()));
		}
		applyUnansweredQuestionChange(unansweredQuestionField.getValue());
		return completedHandler;
	}

	private void finishAnswerSaveTransition(Throwable failure) {
		answerSaveInProgress = false;
		setDisable(false);
		updateAnswerPdfControlsVisibility(unansweredQuestionField.getValue());
		refreshSaveButtonState();
		if (failure == null) {
			answerRegionStatusLabel.setText("Answer saved");
		} else if (failure instanceof CancellationException) {
			answerRegionStatusLabel.setText("Answer saved — PDF view changed");
		} else {
			answerRegionStatusLabel.setText("Answer saved — next PDF could not be loaded");
			showAnswerFileError("Answer saved, but the next question's PDF could not be loaded.",
					"Your answer is stored. Choose an answer PDF to continue. " + failure.getMessage());
		}
	}

	private void finishMcqExplanationCandidateLoad() {
		mcqExplanationLoadInProgress = false;
		captureMcqExplanationsButton.setText("Capture MCQ Explanations");

		// Recalculate from the current Subject and active booklet rather than the
		// context that belonged to the completed worker.
		refreshMcqExplanationActionState();
	}

	private void finishMcqExplanationCapture() {
		if (!mcqExplanationMode || editingAnswerQuestion != null || answerSaveInProgress) {
			return;
		}
		Question returnQuestion = mcqExplanationReturnQuestion;
		resetMcqExplanationPresentation();

		// Return to the ordinary unanswered queue without rereading the Question bank.
		// The queue remained intact while retrofit editing temporarily used the same
		// Answer editor.
		Question matchingReturnQuestion = null;
		if (returnQuestion != null) {
			matchingReturnQuestion = unansweredQuestionField.getItems().stream()
					.filter(candidate -> candidate.getId() == returnQuestion.getId()).findFirst().orElse(null);
		}
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.setValue(matchingReturnQuestion);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
		applyUnansweredQuestionChange(matchingReturnQuestion);
	}

	private void handleMcqExplanationQuestionChanged(Question question) {
		if (restoringMcqExplanationSelection || !mcqExplanationMode || question == null
				|| editingAnswerQuestion != null) {
			return;
		}
		mcqExplanationEditingQuestion = question;
		mcqExplanationEditSaved = false;
		boolean started = editAnswer(question, this::completeMcqExplanationEdit);
		if (!started) {

			// A rejected transition owns no candidate and changes nothing in the
			// retrofit list.
			mcqExplanationEditingQuestion = null;
			mcqExplanationEditSaved = false;
			clearMcqExplanationSelection();
			return;
		}

		// editAnswer now owns the selected Question until Save or Cancel completes.
		mcqExplanationQuestionField.setDisable(true);
		finishMcqExplanationCaptureButton.setDisable(true);
	}

	private void handleUnansweredQuestionChanged(Question previousQuestion, Question question) {
		if (restoringUnansweredQuestionSelection || sameQuestion(previousQuestion, question)) {
			return;
		}
		if (!answerTransitionAllowed.getAsBoolean()) {
			restoreUnansweredQuestionSelection(previousQuestion);
			return;
		}
		applyUnansweredQuestionChange(question);
	}

	private boolean hasUnsavedOrdinaryAnswerDraft() {
		Question question = unansweredQuestionField.getValue();
		if (currentAnswerSelection != null || !pendingAnswerRegions.isEmpty()) {

			// Pending or accepted PDF regions are unsaved Answer content regardless of
			// response type.
			return true;
		}
		if (question == null || question.hasAnswer() || !isMultipleChoiceQuestion(question)) {
			return false;
		}

		// An A-D choice can exist without any Answer regions. It must not be silently
		// discarded merely because the user enters explanation-retrofit mode.
		return !currentAnswerText().isBlank();
	}

	private boolean isMultipleChoiceQuestion(Question question) {
		return question != null && question.getResponseType() == QuestionResponseType.MULTIPLE_CHOICE;
	}

	private boolean isWrittenResponseQuestion(Question question) {
		return question != null && question.getResponseType() == QuestionResponseType.WRITTEN_RESPONSE;
	}

	private boolean loadAssignedAnswerFile(Question question) {
		AnswerFile assignedAnswerFile;
		try {

			// The ExamBooklet mapping is authoritative. Do not infer the file from the
			// number of AnswerFiles registered for the surrounding Exam.
			assignedAnswerFile = answerWriter.findAnswerFile(question.getBooklet());
		} catch (SQLException e) {
			answerFile = null;
			selectedAnswerPdfLabel.setText("Choose an answer PDF");
			showAnswerFileError("Could not read the answer PDF assigned to this booklet.", e.getMessage());
			updateAnswerPdfControlsVisibility(question);
			return false;
		}
		if (assignedAnswerFile == null) {

			// An unresolved booklet must never inherit the previously displayed file merely
			// because that file belongs to the same Exam.
			answerFile = null;
			selectedAnswerPdfLabel.setText("Choose an answer PDF");
			updateAnswerPdfControlsVisibility(question);
			return false;
		}
		if (answerFile != null && answerFile.getId() == assignedAnswerFile.getId()) {

			// MCQ and Paper 1 may deliberately share one AnswerFile. Reuse the already
			// loaded document rather than reopening the same PDF on every manual selection.
			answerFile = assignedAnswerFile;
			selectedAnswerPdfLabel.setText(assignedAnswerFile.getName());
			try {
				answerDocumentHandler.run();
			} catch (RuntimeException e) {
				answerFile = null;
				selectedAnswerPdfLabel.setText("Choose an answer PDF");
				showAnswerFileError("The assigned answer PDF could not be displayed.", e.getMessage());
				updateAnswerPdfControlsVisibility(question);
				return false;
			}
			updateAnswerPdfControlsVisibility(question);
			return true;
		}

		// A different booklet may point at a different AnswerFile. Clear the old file
		// before attempting to open the newly assigned document.
		answerFile = null;
		Path pdfPath = resolveRegisteredAnswerFile(assignedAnswerFile);
		if (pdfPath == null) {
			selectedAnswerPdfLabel.setText("Choose an answer PDF");
			updateAnswerPdfControlsVisibility(question);
			return false;
		}
		boolean opened = openRegisteredAnswerFile(assignedAnswerFile, pdfPath);
		updateAnswerPdfControlsVisibility(question);
		return opened;
	}

	private List<Question> loadMcqExplanationCandidates(List<Question> questions, Subject subject,
			ExamBooklet activeBooklet) throws SQLException {
		Objects.requireNonNull(questions, "questions");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(activeBooklet, "activeBooklet");
		AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(activeBooklet);
		if (assignedAnswerFile == null || !assignedAnswerFile.hasAnswerExplanations()) {

			// The active Question booklet has no explanation-capable Answer source, so
			// no Question from another booklet may substitute for it.
			return List.of();
		}
		return questions.stream().filter(question -> subject.equals(question.getExam().getSubject()))
				.filter(question -> question.getBooklet().getId() == activeBooklet.getId())
				.filter(this::isMultipleChoiceQuestion).filter(Question::hasAnswer).filter(question -> {

					// Retrofit enriches an authoritative A-D Answer. Invalid legacy text
					// remains ordinary Answer-correction work.
					String answerText = question.getAnswer().getAnswerText();
					String validationError = AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(true,
							QuestionResponseType.MULTIPLE_CHOICE, false, answerText, 0));
					return validationError == null;
				}).sorted(QuestionSourceOrder.comparator()).toList();
	}

	private void loadNextAnswerDocument(Question question) {
		if (question == null || !usesAnswerDocument(question)) {

			// Nothing remains that can legitimately retain the previous booklet's Answer
			// source.
			answerFile = null;
			selectedAnswerPdfLabel.setText("No PDF selected");
			finishAnswerSaveTransition(null);
			return;
		}
		answerRegionStatusLabel.setText("Answer saved — loading next question...");
		Task<AnswerFile> task = new Task<>() {

			@Override
			protected AnswerFile call() throws SQLException {

				// The next Question may belong to another booklet in the same Exam, so its
				// own persisted mapping must be resolved before choosing a document.
				return answerWriter.findAnswerFile(question.getBooklet());
			}
		};
		task.setOnSucceeded(_ -> {
			if (!sameQuestion(question, unansweredQuestionField.getValue())) {
				finishAnswerSaveTransition(new CancellationException("Answer selection changed"));
				return;
			}
			AnswerFile file = task.getValue();
			if (file == null) {

				// Never carry the preceding booklet's PDF into an unresolved booklet.
				answerFile = null;
				selectedAnswerPdfLabel.setText("Choose an answer PDF");
				finishAnswerSaveTransition(null);
				return;
			}

			// Keep the existing asynchronous PDF-loading path even when the next booklet
			// shares the same AnswerFile. Existing save-transition and failure handling
			// relies on this callback completing before capture is re-enabled.
			openNextAnswerDocument(file);
		});
		task.setOnFailed(_ -> {

			// A failed lookup must not leave the preceding booklet's AnswerFile appearing
			// to belong to the new Question.
			answerFile = null;
			selectedAnswerPdfLabel.setText("Choose an answer PDF");
			finishAnswerSaveTransition(task.getException());
		});
		Thread.ofVirtual().name("next-answer-file").start(task);
	}

	private void openNextAnswerDocument(AnswerFile file) {
		try {
			Path path = new PdfStore(pdfFilePicker.dataRoot()).resolve(file.getSourceDocument().getRelativePath());
			SelectedPdf selected = new SelectedPdf(path.toFile(), path, pdfFilePicker.dataRoot());

			// Load asynchronously because this path is used immediately after an Answer
			// save. The save transition remains active until PDF loading completes.
			answerPdfLoader.accept(selected, failure -> {
				if (failure == null) {
					answerFile = file;
					selectedAnswerPdfLabel.setText(file.getName());
				} else {
					answerFile = null;
					selectedAnswerPdfLabel.setText("Choose an answer PDF");
				}
				finishAnswerSaveTransition(failure);
			});
		} catch (RuntimeException e) {

			// A bad persisted path must finish the save transition cleanly rather than
			// leaving Answer capture permanently disabled.
			answerFile = null;
			finishAnswerSaveTransition(e);
		}
	}

	private boolean openRegisteredAnswerFile(AnswerFile registeredAnswerFile, Path pdfPath) {
		if (!Files.isRegularFile(pdfPath)) {
			showAnswerFileError("The registered answer PDF is unavailable.", pdfPath.toString());
			return false;
		}
		SelectedPdf selectedPdf = new SelectedPdf(pdfPath.toFile(), pdfPath, pdfFilePicker.dataRoot());
		try {
			answerPdfHandler.accept(selectedPdf);
		} catch (RuntimeException e) {
			showAnswerFileError("The registered answer PDF could not be opened.", e.getMessage());
			return false;
		}
		answerFile = registeredAnswerFile;
		selectedAnswerPdfLabel.setText(registeredAnswerFile.getName());
		updateAnswerPdfControlsVisibility(unansweredQuestionField.getValue());
		return true;
	}

	private void refreshAnswerRegionList() {
		boolean hasRegions = !pendingAnswerRegions.isEmpty();
		boolean showRegions = hasRegions && answerRegionControlsVisibleFor(unansweredQuestionField.getValue());

		// The AnswerFile flag controls whether MCQ regions are treated as explanation
		// material. Written-response regions retain their existing behaviour.
		answerRegionsScrollPane.setManaged(showRegions);
		answerRegionsScrollPane.setVisible(showRegions);
		answerRegionListBox.getChildren().clear();
		if (!showRegions) {
			return;
		}
		for (int i = 0; i < pendingAnswerRegions.size(); i++) {
			AnswerRegion region = pendingAnswerRegions.get(i);
			int regionIndex = i;
			Button removeButton = new Button("Remove");

			// Removing a region changes only transient Answer-edit state until Save.
			removeButton.setOnAction(_ -> removeAnswerRegion(regionIndex));
			HBox controls = new HBox(removeButton);
			controls.setAlignment(Pos.CENTER_LEFT);
			VBox row = new VBox(COMPACT_SPACING);
			if (answerFile != null && region.answerFile().getId() == answerFile.getId()) {

				// Preview only regions belonging to the AnswerFile currently displayed in
				// the shared PDF workspace.
				ImageView previewView = createAcceptedAnswerPreview(region);
				row.getChildren().addAll(previewView, controls);
			} else {
				Label missingPreviewLabel = new Label("Preview unavailable for page " + region.pageNumber());
				row.getChildren().addAll(missingPreviewLabel, controls);
			}
			row.setFillWidth(true);
			answerRegionListBox.getChildren().add(row);
		}
		answerRegionListBox.requestLayout();
		answerRegionsScrollPane.requestLayout();
	}

	private void refreshMcqExplanationActionState() {
		ExamBooklet activeBooklet = activeBookletSupplier.get();

		// Retrofit capture always belongs to the explicitly active Question booklet.
		// Determining whether that booklet's AnswerFile is explanation-capable remains
		// asynchronous and occurs only after the user enters the workflow.
		boolean unavailable = workingSubject == null || activeBooklet == null || mcqExplanationLoadInProgress
				|| mcqExplanationMode || answerSaveInProgress || editingAnswerQuestion != null;
		captureMcqExplanationsButton.setDisable(unavailable);
	}

	private void refreshSaveButtonState() {
		Question question = unansweredQuestionField.getValue();
		String answerText = currentAnswerText();
		boolean ready = !answerSaveInProgress && findValidationError(question, answerText) == null;
		saveAnswerButton.setDisable(!ready);
	}

	private void removeAnswerRegion(int regionIndex) {
		pendingAnswerRegions.remove(regionIndex);
		refreshAnswerRegionList();
		showAcceptedRegionStatus();
		refreshSaveButtonState();
	}

	private Question removeSavedQuestionAndSelectNext(Question savedQuestion, int previousIndex) {
		Question nextQuestion = null;
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.getItems().removeIf(question -> question.getId() == savedQuestion.getId());
			if (!unansweredQuestionField.getItems().isEmpty()) {
				int nextIndex = previousIndex < 0 ? 0
						: Math.min(previousIndex, unansweredQuestionField.getItems().size() - 1);
				nextQuestion = unansweredQuestionField.getItems().get(nextIndex);
			}
			unansweredQuestionField.setValue(nextQuestion);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
		applyUnansweredQuestionChange(nextQuestion, false);
		return nextQuestion;
	}

	private void resetMcqExplanationPresentation() {
		mcqExplanationMode = false;
		mcqExplanationReturnQuestion = null;
		mcqExplanationEditingQuestion = null;
		mcqExplanationEditSaved = false;
		captureMcqExplanationsButton.setVisible(true);
		captureMcqExplanationsButton.setManaged(true);
		refreshMcqExplanationActionState();
		mcqExplanationSelectionControls.setVisible(false);
		mcqExplanationSelectionControls.setManaged(false);
		mcqExplanationQuestionField.setDisable(false);
		finishMcqExplanationCaptureButton.setDisable(false);
		restoringMcqExplanationSelection = true;
		try {
			mcqExplanationQuestionField.getItems().clear();
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}
		unansweredQuestionField.setVisible(true);
		unansweredQuestionField.setManaged(true);
		unansweredQuestionField.setDisable(false);
	}

	private Path resolveRegisteredAnswerFile(AnswerFile registeredAnswerFile) {
		PdfStore pdfStore = new PdfStore(pdfFilePicker.dataRoot());
		try {
			return pdfStore.resolve(registeredAnswerFile.getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException e) {
			showAnswerFileError("The registered answer PDF path is invalid.", e.getMessage());
			return null;
		}
	}

	private void restoreUnansweredQuestionSelection(Question previousQuestion) {
		Platform.runLater(() -> applyRestoredQuestionSelection(previousQuestion));
	}

	private boolean sameBooklet(ExamBooklet first, ExamBooklet second) {
		if (first == second) {
			return true;
		}
		if (first == null || second == null) {
			return false;
		}

		// Persistent booklet identity is authoritative even when separate repository
		// reads reconstructed different Java objects.
		return first.getId() == second.getId();
	}

	private boolean sameQuestion(Question first, Question second) {
		if (first == second) {
			return true;
		}
		if (first == null || second == null) {
			return false;
		}
		return first.getId() == second.getId();
	}

	private void saveAnswer(Question question, String answerText) {
		if (answerSaveInProgress) {
			return;
		}
		int previousIndex = unansweredQuestionField.getSelectionModel().getSelectedIndex();
		String storedText = answerText.isBlank() ? null : answerText;

		// Snapshot accepted regions before the background task takes ownership of the
		// save.
		List<AnswerRegion> regions = List.copyOf(pendingAnswerRegions);
		boolean updating = question.hasAnswer();
		long existingAnswerId = updating ? question.getAnswer().getId() : 0;
		boolean editing = editingAnswerQuestion != null;
		answerSaveInProgress = true;
		setDisable(true);
		Task<Answer> saveTask = new Task<>() {

			@Override
			protected Answer call() throws Exception {
				if (updating) {
					return answerWriter.updateAnswer(question, existingAnswerId, storedText, regions);
				}
				return answerWriter.insertAnswer(question, storedText, regions);
			}
		};
		saveTask.setOnSucceeded(_ -> {
			Answer answer = saveTask.getValue();
			completeAnswerSave(question, answer, editing, previousIndex);
		});
		saveTask.setOnFailed(_ -> {
			answerSaveInProgress = false;
			setDisable(false);
			refreshSaveButtonState();
			Throwable failure = saveTask.getException();
			Alert alert = new Alert(Alert.AlertType.ERROR);
			alert.setHeaderText("Answer could not be saved.");
			alert.setContentText(failure == null || failure.getMessage() == null ? "The answer was not saved."
					: failure.getMessage());
			alert.showAndWait();
		});
		Thread saveThread = new Thread(saveTask, "answer-save");
		saveThread.setDaemon(true);
		saveThread.start();
	}

	private void selectMultipleChoiceAnswer(String answerText) {
		multipleChoiceAnswerGroup.selectToggle(null);
		preservedAnswerText = null;
		if (answerText == null || answerText.isBlank()) {
			return;
		}
		String value = answerText.trim();
		if ("A".equalsIgnoreCase(value)) {
			multipleChoiceAnswerGroup.selectToggle(answerAButton);
			return;
		}
		if ("B".equalsIgnoreCase(value)) {
			multipleChoiceAnswerGroup.selectToggle(answerBButton);
			return;
		}
		if ("C".equalsIgnoreCase(value)) {
			multipleChoiceAnswerGroup.selectToggle(answerCButton);
			return;
		}
		if ("D".equalsIgnoreCase(value)) {
			multipleChoiceAnswerGroup.selectToggle(answerDButton);
			return;
		}

		// Preserve older arbitrary textual answers even though the current capture UI
		// only exposes A-D choices.
		preservedAnswerText = answerText;
	}

	private void setMultipleChoiceAnswerEnabled(boolean enabled) {
		answerAButton.setDisable(!enabled);
		answerBButton.setDisable(!enabled);
		answerCButton.setDisable(!enabled);
		answerDButton.setDisable(!enabled);
		clearMultipleChoiceAnswerButton.setDisable(!enabled);
	}

	private void setSelectionActionsEnabled(boolean enabled) {
		boolean effective = enabled && answerRegionsAvailableFor(unansweredQuestionField.getValue());
		addAnswerRegionButton.setDisable(!effective);
		clearAnswerSelectionButton.setDisable(!effective);
	}

	private void showAcceptedRegionStatus() {
		answerRegionCountLabel.setText("Regions: " + pendingAnswerRegions.size());
		if (pendingAnswerRegions.isEmpty()) {
			answerRegionStatusLabel.setText("");
			return;
		}
		if (pendingAnswerRegions.size() == 1) {
			answerRegionStatusLabel.setText("Page " + pendingAnswerRegions.getFirst().pageNumber());
			return;
		}
		answerRegionStatusLabel.setText(pendingAnswerRegions.size() + " regions selected");
	}

	private void showAnswerFileError(String header, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showAnswerPdfError(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText("The answer PDF could not be imported.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showAnswerWorkflowWarning(String header, String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);

		// Workflow warnings are distinct from save validation errors because the
		// current Answer may be valid but not yet persisted.
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText("Answer is incomplete.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showFirstStoredAnswerRegionPage(Question question) {
		if (!answerRegionsAvailableFor(question) || !question.hasAnswer()
				|| question.getAnswer().getRegions().isEmpty()) {
			return;
		}
		AnswerRegion firstRegion = question.getAnswer().getRegions().getFirst();

		// Navigate only when the PDF currently loaded into the Answer workspace is
		// the same persisted AnswerFile that owns the stored region.
		if (answerFile == null || firstRegion.answerFile().getId() != answerFile.getId()
				|| answerPdfSessionSupplier.get() == null) {
			return;
		}
		answerPageNavigationHandler.accept(firstRegion.pageNumber());
	}

	private void showSelectedAnswerDocument() {
		Question question = unansweredQuestionField.getValue();
		if (question == null || answerSaveInProgress || !usesAnswerDocument(question)) {
			return;
		}

		// Opening the Answer selector must resolve the current booklet's mapping rather
		// than assuming the currently loaded same-Exam PDF is suitable.
		loadAssignedAnswerFile(question);
	}

	private void updateAnswerPdfControlsVisibility(Question question) {
		boolean usesAnswerDocument = usesAnswerDocument(question);

		// answerFile is maintained as the file resolved for the currently selected
		// booklet. A null value therefore means this booklet still needs a PDF choice.
		boolean answerPdfKnown = usesAnswerDocument && answerFile != null;
		boolean visible = usesAnswerDocument && !answerPdfKnown;
		answerPdfControls.setVisible(visible);
		answerPdfControls.setManaged(visible);
	}

	private void updateAnswerRegionControlsVisibility(Question question) {
		boolean visible = answerRegionControlsVisibleFor(question);

		// Written responses always expose region controls, even while awaiting an
		// AnswerFile. MCQs expose them only when the assigned AnswerFile is marked as
		// containing explanation material.
		addAnswerRegionButton.setVisible(visible);
		addAnswerRegionButton.setManaged(visible);
		clearAnswerSelectionButton.setVisible(visible);
		clearAnswerSelectionButton.setManaged(visible);
		answerRegionCountLabel.setVisible(visible);
		answerRegionCountLabel.setManaged(visible);
		answerRegionStatusLabel.setVisible(visible);
		answerRegionStatusLabel.setManaged(visible);
		boolean showRegions = visible && !pendingAnswerRegions.isEmpty();
		answerRegionsScrollPane.setVisible(showRegions);
		answerRegionsScrollPane.setManaged(showRegions);
		if (!visible) {

			// A hidden region workflow must never retain an actionable PDF selection.
			setSelectionActionsEnabled(false);
		}
	}

	private void updateMultipleChoiceAnswerVisibility(Question question) {
		boolean visible = isMultipleChoiceQuestion(question);
		multipleChoiceAnswerControls.setVisible(visible);
		multipleChoiceAnswerControls.setManaged(visible);
		setMultipleChoiceAnswerEnabled(visible);
	}

	private boolean usesAnswerDocument(Question question) {

		// Both supported response types use the assigned marking material. Written
		// responses capture required regions; MCQs may additionally capture optional
		// explanation regions when their AnswerFile declares that capability.
		return isMultipleChoiceQuestion(question) || isWrittenResponseQuestion(question);
	}

	private void validateAnswerForSave() {
		Question question = unansweredQuestionField.getValue();
		String answerText = currentAnswerText();
		String validationError = findValidationError(question, answerText);
		if (validationError != null) {
			showError(validationError);
			return;
		}
		saveAnswer(question, answerText);
	}
}
