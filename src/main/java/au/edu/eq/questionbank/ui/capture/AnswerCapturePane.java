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
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import au.edu.eq.questionbank.ui.pdf.SelectedPdf;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
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
	private static final AnswerQuestionConverter QUESTION_CODE_CONVERTER = new AnswerQuestionConverter();
	private final SourceDocumentHashService sourceDocumentHashService = new SourceDocumentHashService();

	// Workflow dependencies and application callbacks.
	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final Supplier<PdfSession> answerPdfSessionSupplier;
	private final Consumer<SelectedPdf> answerPdfHandler;
	private final Path pdfDataRoot;
	private final BiConsumer<SelectedPdf, Consumer<Throwable>> answerPdfLoader;
	private final Runnable selectionClearHandler;
	private final Runnable answerDocumentHandler;
	private final SqliteAnswerWriter answerWriter;
	private final BooleanSupplier answerTransitionAllowed;
	private final IntConsumer answerPageNavigationHandler;

	// Question and answer source selection.
	private final ComboBox<Question> unansweredQuestionField = new ComboBox<>();
	private final Label selectedAnswerQuestionLabel = new Label("No question selected");
	private final Label selectedAnswerPdfLabel = new Label("No Answer PDF assigned");

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
	private long answerCaptureCompletionQuestionId = -1L;
	private Runnable answerCaptureCompletedHandler = () -> {
	};

	// Dashboard-owned Answer capture may further restrict the Subject queue to one
	// selected Exam without changing any persisted Question state.
	private Exam examScope;

	// Retain the complete most recently published Question snapshot so changing
	// transient Exam scope never requires a synchronous repository reload.
	private List<Question> questionSnapshot = List.of();

	/**
	 * Creates the answer-capture workflow and its persistence integration.
	 *
	 * @param questionRepository          source of persisted Questions
	 * @param answerWriter                writer for Answer persistence
	 * @param pdfDataRoot                 managed PDF storage root
	 * @param answerPdfHandler            callback that opens an assigned Answer PDF
	 * @param answerDocumentHandler       callback that displays the Answer document
	 * @param answerTransitionAllowed     guard for changing Answer targets
	 * @param activeBookletSupplier       supplier of the Question booklet currently
	 *                                    active for capture
	 * @param selectionClearHandler       callback that clears the shared PDF
	 *                                    selection
	 * @param questionExtractor           extractor used to preview accepted regions
	 * @param answerPdfSessionSupplier    supplier of the active Answer PDF session
	 * @param answerPageNavigationHandler callback that navigates Answer pages
	 * @param answerPdfLoader             asynchronous Answer PDF loader
	 */
	public AnswerCapturePane(QuestionRepository questionRepository, SqliteAnswerWriter answerWriter, Path pdfDataRoot,
			Consumer<SelectedPdf> answerPdfHandler, Runnable answerDocumentHandler,
			BooleanSupplier answerTransitionAllowed, Supplier<ExamBooklet> activeBookletSupplier,
			Runnable selectionClearHandler, QuestionExtractor questionExtractor,
			Supplier<PdfSession> answerPdfSessionSupplier, IntConsumer answerPageNavigationHandler,
			BiConsumer<SelectedPdf, Consumer<Throwable>> answerPdfLoader) {
		if (questionRepository == null) {
			throw new NullPointerException("questionRepository");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (pdfDataRoot == null) {
			throw new NullPointerException("pdfDataRoot");
		}
		if (answerPdfHandler == null) {
			throw new NullPointerException("answerPdfHandler");
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

		// Answer capture consumes authoritative AnswerFile assignments created through
		// Exam / Assets. It no longer owns native PDF selection.
		this.questionRepository = questionRepository;
		this.answerWriter = answerWriter;
		this.pdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		this.answerPdfHandler = answerPdfHandler;
		this.answerPdfLoader = Objects.requireNonNull(answerPdfLoader, "answerPdfLoader");
		this.answerDocumentHandler = answerDocumentHandler;
		this.answerTransitionAllowed = answerTransitionAllowed;
		this.activeBookletSupplier = activeBookletSupplier;
		this.selectionClearHandler = selectionClearHandler;
		this.questionExtractor = questionExtractor;
		this.answerPdfSessionSupplier = answerPdfSessionSupplier;
		this.answerPageNavigationHandler = answerPageNavigationHandler;
		configureControls();
		configureActions();
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

		// Ordinary Answer capture remains Subject-scoped but not Exam-scoped.
		return unansweredQuestionsInSourceOrder(questions, locallyAnsweredQuestionIds, workingSubject, null);
	}

	static List<Question> unansweredQuestionsInSourceOrder(List<Question> questions,
			Set<Long> locallyAnsweredQuestionIds, Subject workingSubject, Exam examScope) {
		Objects.requireNonNull(questions, "questions");
		Objects.requireNonNull(locallyAnsweredQuestionIds, "locallyAnsweredQuestionIds");

		// Subject is the application boundary. Dashboard Answer capture may
		// additionally
		// constrain the queue to the Exam from which that workflow was launched.
		return questions.stream()
				.filter(question -> workingSubject == null
						|| question.getExam().getSubject().getId() == workingSubject.getId())
				.filter(question -> examScope == null || question.getExam().getId() == examScope.getId())
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
	 * Abandons a clean MCQ explanation session before leaving Capture.
	 * <p>
	 * Persisted A-D Answer state is retained. The operation is rejected if the
	 * current explanation edit contains unsaved work.
	 *
	 * @throws IllegalStateException if explanation capture contains unsaved work
	 */
	public void abandonMcqExplanationCapture() {
		if (!mcqExplanationMode) {
			return;
		}
		if (!canAbandonMcqExplanationCapture()) {
			throw new IllegalStateException("MCQ explanation capture contains unsaved work");
		}

		// Dashboard navigation deliberately abandons only clean transient edit state.
		// No persistence callback from that edit may fire after the workspace leaves.
		answerEditCompletedHandler = () -> {
		};
		editingAnswerQuestion = null;
		mcqExplanationEditingQuestion = null;
		mcqExplanationEditSaved = false;
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		setVisibleAndManaged(cancelAnswerEditButton, false);
		saveAnswerButton.setText("Save Answer");

		// The explanation session and its marking-PDF identity belong only to the
		// Dashboard capture workspace being left.
		resetMcqExplanationPresentation();
		clearAnswerFile("No Answer PDF assigned");
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
	 * Returns whether the active MCQ explanation session may be abandoned without
	 * losing unsaved work.
	 *
	 * @return {@code true} only for an active, clean explanation session
	 */
	public boolean canAbandonMcqExplanationCapture() {
		if (!mcqExplanationMode || answerSaveInProgress || currentAnswerSelection != null
				|| !pendingAnswerRegions.isEmpty()) {
			return false;
		}
		if (editingAnswerQuestion == null) {

			// An explanation session waiting at its selector owns no unsaved Answer edit.
			return true;
		}
		String storedAnswer = editingAnswerQuestion.getAnswer().getAnswerText();
		String currentAnswer = currentAnswerText();
		String storedChoice = storedAnswer == null ? "" : storedAnswer.strip();
		String currentChoice = currentAnswer == null ? "" : currentAnswer.strip();

		// Opening a candidate restores its persisted A-D choice. Changing that choice
		// is
		// still unsaved Answer work even if no explanation rectangle has been accepted.
		return storedChoice.equalsIgnoreCase(currentChoice);
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

		// Ordinary sequential Answer capture has no external completion owner.
		return captureAnswer(question, () -> {
		});
	}

	/**
	 * Starts Answer capture for one unanswered Question and runs an operation after
	 * the Answer has been successfully persisted and the save transition has
	 * finished.
	 *
	 * @param question         Question to capture
	 * @param completedHandler operation to run after successful capture
	 * @return {@code true} when the transition into capture was accepted
	 */
	public boolean captureAnswer(Question question, Runnable completedHandler) {
		if (answerSaveInProgress) {
			return false;
		}
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
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
		Question matching = findQuestionById(unansweredQuestionField.getItems(), question);
		if (matching == null) {
			return false;
		}

		// Programmatic capture owns the target transition and therefore suppresses the
		// ordinary ComboBox listener.
		setUnansweredQuestionSilently(matching);
		applyUnansweredQuestionChange(matching);
		answerCaptureCompletionQuestionId = matching.getId();
		answerCaptureCompletedHandler = completedHandler;
		return true;
	}

	/**
	 * Starts required MCQ explanation capture at one Dashboard-selected Question.
	 * <p>
	 * The Answer pane must already be scoped to the Question's Exam. After the
	 * first Question is saved, the existing retrofit workflow advances through the
	 * remaining missing explanations in that booklet.
	 *
	 * @param question first Question whose explanation is missing
	 * @return {@code true} when explanation loading was started
	 * @throws NullPointerException  if {@code question} is {@code null}
	 * @throws IllegalStateException if Dashboard Exam scope does not match the
	 *                               Question
	 */
	public boolean captureMcqExplanations(Question question) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (examScope == null || examScope.getId() != question.getExam().getId()) {
			throw new IllegalStateException("MCQ explanation capture requires matching Dashboard Exam scope");
		}

		// Dashboard supplies the exact first Question while the common retrofit loader
		// reconstructs the authoritative candidate list from persistence.
		return startMcqExplanationCapture(question.getBooklet(), question, false);
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

		// Existing-Answer editing has its own completion lifecycle and therefore
		// abandons any unfinished direct missing-Answer capture callback.
		clearAnswerCaptureCompletion();

		// Existing-Answer editing owns the selector until Save or Cancel.
		clearPendingAnswerRegions();
		editingAnswerQuestion = question;
		answerEditCompletedHandler = editCompletedHandler;
		unansweredQuestionField.setDisable(true);
		setUnansweredQuestionSilently(question);
		applyUnansweredQuestionChange(question);

		// Persisted Answer regions determine the initial marking-PDF page.
		showFirstStoredAnswerRegionPage(question);
		saveAnswerButton.setText("Update Answer");
		setVisibleAndManaged(cancelAnswerEditButton, true);
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

		// Exam / Assets may have changed the authoritative assignment.
		selectedAnswerPdfLabel.setText("No Answer PDF assigned");
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
		questionSnapshot = List.copyOf(questions);
		Question editTarget = editingAnswerQuestion;
		Question selected = editTarget == null ? unansweredQuestionField.getValue() : editTarget;

		// Build the Answer queue independently of repository insertion order and apply
		// only the transient Working Subject filter.
		List<Question> unansweredQuestions = unansweredQuestionsInSourceOrder(questionSnapshot,
				locallyAnsweredQuestionIds, workingSubject, examScope);
		Question matching = editTarget == null ? findQuestionById(unansweredQuestions, selected) : editTarget;
		replaceUnansweredQuestionsSilently(unansweredQuestions, matching);

		// Active-booklet changes affect availability of the retrofit workflow as well
		// as the ordinary unanswered queue.
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
		selectedAnswerPdfLabel.setText("No Answer PDF assigned");
		if (question == null || !usesAnswerDocument(question)) {
			updateAnswerPdfControlsVisibility(question);
			return;
		}
		applyUnansweredQuestionChange(question);
	}

	/**
	 * Restricts Answer capture to one Exam, or removes that restriction.
	 * <p>
	 * This is transient Dashboard workflow state and does not alter persisted
	 * Question or Exam data.
	 *
	 * @param examScope Exam whose unanswered Questions are available, or
	 *                  {@code null} for the complete Working Subject
	 */
	public void setExamScope(Exam examScope) {
		Question previouslySelected = unansweredQuestionField.getValue();
		this.examScope = examScope;

		// Re-filter the already-published application snapshot rather than performing a
		// repository read merely because the Dashboard changed workflow scope.
		refreshQuestions(questionSnapshot);
		if (previouslySelected != null && unansweredQuestionField.getValue() == null) {

			// Presentation belonging to an Exam that has just left scope must not survive
			// beside the replacement queue.
			applyUnansweredQuestionChange(null, false);
		}
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

		// Exam scope belongs to one Dashboard launch and cannot survive an
		// authoritative
		// Subject transition.
		examScope = null;
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
		AnswerFile registeredAnswerFile = registerSelectedAnswerFile(question, selectedPdf);
		activateSelectedAnswerFile(question, selectedPdf, registeredAnswerFile);
	}

	private void activateSelectedAnswerFile(Question question, SelectedPdf selectedPdf,
			AnswerFile registeredAnswerFile) {

		// Persistence succeeds before this source becomes active in the shared PDF
		// workspace.
		answerFile = registeredAnswerFile;
		answerPdfHandler.accept(selectedPdf);
		selectedAnswerPdfLabel.setText(answerFile.getName());
		updateAnswerPdfControlsVisibility(question);
		updateAnswerRegionControlsVisibility(question);
		refreshAnswerRegionList();
	}

	private boolean activeBookletSupportsMcqExplanations(ExamBooklet activeBooklet) {
		if (activeBooklet == null) {
			return false;
		}
		try {

			// MCQ explanation capture is authoritative only when the AnswerFile assigned
			// to this exact Question booklet explicitly declares explanation content.
			AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(activeBooklet);
			return assignedAnswerFile != null && assignedAnswerFile.hasAnswerExplanations();
		} catch (SQLException exception) {

			// Failure to verify authoritative metadata must never expose an action whose
			// prerequisites are unknown.
			return false;
		}
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
		double viewportWidth = answerRegionsScrollPane.getViewportBounds().getWidth();
		if (viewportWidth <= 0.0) {

			// During the first layout pulse the viewport may not yet have a real width.
			// Fall back to the containing pane without creating a permanent binding to it.
			viewportWidth = Math.max(0.0, getWidth() - REGION_PREVIEW_HORIZONTAL_INSET);
		}

		// Measure the preview list against the stable inner viewport rather than
		// against
		// a width which may change when scrollbars appear or disappear.
		double contentHeight = answerRegionListBox.prefHeight(viewportWidth);
		return Math.min(ANSWER_REGIONS_VIEWPORT_HEIGHT, contentHeight + REGION_VIEWPORT_EXTRA_HEIGHT);
	}

	private void applyRestoredQuestionSelection(Question previousQuestion) {

		// Restoration is already the resolution of a rejected transition and must not
		// invoke that transition guard again.
		setUnansweredQuestionSilently(previousQuestion);
	}

	private void applyUnansweredQuestionChange(Question question) {
		applyUnansweredQuestionChange(question, true);
	}

	private void applyUnansweredQuestionChange(Question question, boolean loadDocument) {
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		if (question == null) {
			showNoAnswerQuestion();
			return;
		}
		boolean usesAnswerDocument = usesAnswerDocument(question);
		prepareAnswerFileForQuestion(usesAnswerDocument);
		restoreAnswerState(question);
		if (loadDocument && usesAnswerDocument) {

			// Resolve the source from the Question's own booklet rather than carrying
			// another booklet's AnswerFile into this target.
			loadAssignedAnswerFile(question);
		}
		refreshAnswerPresentation(question);
	}

	private void beginAnswerSave() {

		// Freeze the complete Answer pane while one persistence transaction owns its
		// transient state.
		answerSaveInProgress = true;
		setDisable(true);
	}

	private void beginMcqExplanationCandidateLoad() {

		// Publish loading state before background persistence work begins.
		mcqExplanationLoadInProgress = true;
		captureMcqExplanationsButton.setDisable(true);
		captureMcqExplanationsButton.setText("Loading...");
	}

	private void beginMcqExplanationCapture() {
		ExamBooklet requestedBooklet = activeBookletSupplier.get();
		if (requestedBooklet == null) {
			return;
		}

		// The ordinary Capture entry point still belongs to the active booklet and
		// begins without preselecting one candidate.
		startMcqExplanationCapture(requestedBooklet, null, true);
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

	private void clearAnswerCaptureCompletion() {
		answerCaptureCompletionQuestionId = -1L;
		answerCaptureCompletedHandler = () -> {
		};
	}

	private void clearAnswerFile(String label) {

		// AnswerFile identity and its visible filename are one presentation state and
		// must never be cleared independently.
		answerFile = null;
		selectedAnswerPdfLabel.setText(label);
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
			completeEditedAnswerSave(question);
			return;
		}
		completeNewAnswerSave(question, previousIndex);
	}

	private void completeEditedAnswerSave(Question question) {
		if (mcqExplanationMode && sameQuestion(question, mcqExplanationEditingQuestion)) {

			// Only a successfully persisted retrofit edit consumes its session
			// candidate.
			mcqExplanationEditSaved = true;
		}
		Runnable completedHandler = null;
		try {
			completedHandler = finishAnswerEditState(false);
		} finally {

			// Edit completion must always release save state even if presentation
			// cleanup itself fails.
			finishAnswerSaveTransition(null);
		}
		if (completedHandler != null) {
			completedHandler.run();
		}
	}

	private void completeMcqExplanationEdit() {
		if (!mcqExplanationMode) {
			return;
		}
		Question completedQuestion = mcqExplanationEditingQuestion;
		boolean saved = mcqExplanationEditSaved;
		finishMcqExplanationEditState();
		if (!saved || completedQuestion == null) {

			// Cancelled edits remain available in the current explanation session.
			restoreMcqExplanationCandidateAfterCancel();
			return;
		}
		int completedIndex = removeMcqExplanationCandidate(completedQuestion);
		if (mcqExplanationQuestionField.getItems().isEmpty()) {

			// The final required explanation has been persisted. End the retrofit session
			// immediately so stale A-D Answer controls are not left active with no
			// Question remaining to edit.
			finishMcqExplanationCapture();
			return;
		}

		// Continue directly with the next outstanding explanation in source order.
		selectNextMcqExplanationCandidate(completedIndex);
	}

	private void completeNewAnswerSave(Question question, int previousIndex) {

		// Ordinary sequential capture removes the persisted Question from the queue and
		// advances to its next source-order neighbour.
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		Question nextQuestion = removeSavedQuestionAndSelectNext(question, previousIndex);
		loadNextAnswerDocument(nextQuestion);
	}

	private void configureActions() {
		addAnswerRegionButton.setOnAction(_ -> addCurrentAnswerRegion());
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

	private void configureAnswerPdfControls() {
		answerPdfControls.setId("answer-pdf-controls");

		// Answer source identity is informational here. Assignment and replacement are
		// owned by Exam / Assets.
		selectedAnswerPdfLabel.setId("selected-answer-pdf");
		selectedAnswerPdfLabel.setWrapText(true);
		selectedAnswerPdfLabel.setMaxWidth(Double.MAX_VALUE);
		selectedAnswerPdfLabel.setTooltip(new Tooltip("Answer PDFs are assigned and replaced through Exam / Assets."));
	}

	private void configureAnswerPersistenceControls() {
		saveAnswerButton.setId("save-answer");
		saveAnswerButton.setDisable(true);
		saveAnswerButton.setText("Save Answer");
		saveAnswerButton.setTooltip(new Tooltip(
				"Persist the selected choice and captured explanation regions, or the required written-response regions."));
		saveAnswerButton.setMinWidth(Region.USE_PREF_SIZE);
		cancelAnswerEditButton.setId("cancel-answer-edit");
		cancelAnswerEditButton.setMinWidth(Region.USE_PREF_SIZE);
		setVisibleAndManaged(cancelAnswerEditButton, false);
	}

	private void configureAnswerRegionControls() {
		addAnswerRegionButton.setId("add-answer-region");
		addAnswerRegionButton
				.setTooltip(new Tooltip("Accept the current Answer PDF selection as part of this Question's Answer."));
		clearAnswerSelectionButton.setId("clear-answer-selection");
		answerRegionCountLabel.setId("answer-region-count");
		answerRegionStatusLabel.setId("answer-region-status");
		answerRegionStatusLabel.setText("");

		// Preserve complete action labels at the minimum supported capture-workspace
		// width.
		addAnswerRegionButton.setMinWidth(Region.USE_PREF_SIZE);
		clearAnswerSelectionButton.setMinWidth(Region.USE_PREF_SIZE);
		answerRegionCountLabel.setMinWidth(Region.USE_PREF_SIZE);
		answerRegionStatusLabel.setWrapText(true);
		answerRegionStatusLabel.setMaxWidth(Double.MAX_VALUE);
		setSelectionActionsEnabled(false);
	}

	private void configureAnswerRegionPreview() {
		answerRegionsScrollPane.setFitToWidth(true);
		answerRegionsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

		// Reserve the vertical-scrollbar width whenever accepted Answer regions are
		// visible. AS_NEEDED can repeatedly change viewport width at the exact
		// threshold
		// where preview height causes the scrollbar to appear, producing a layout loop.
		answerRegionsScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
		answerRegionListBox.setFillWidth(true);
		answerRegionListBox.setMaxWidth(Double.MAX_VALUE);
		answerRegionsScrollPane.setMinHeight(0);
		answerRegionsScrollPane.setMaxHeight(ANSWER_REGIONS_VIEWPORT_HEIGHT);

		// Preferred height now depends on the stable viewport geometry and preview
		// collection only. It no longer feeds the containing pane's width back into
		// itself.
		answerRegionsScrollPane.prefHeightProperty()
				.bind(Bindings.createDoubleBinding(this::answerRegionsPreferredHeight,
						answerRegionListBox.getChildren(), answerRegionsScrollPane.viewportBoundsProperty()));
		answerRegionsScrollPane.setMaxWidth(Double.MAX_VALUE);
		setVisibleAndManaged(answerRegionsScrollPane, false);
	}

	private void configureAnswerStatusControls() {
		selectedAnswerQuestionLabel.setId("selected-answer-question");

		// Answer status may include marks, stored state and workflow guidance, so allow
		// it to wrap rather than competing with action controls.
		selectedAnswerQuestionLabel.setWrapText(true);
		selectedAnswerQuestionLabel.setMaxWidth(Double.MAX_VALUE);
	}

	private void configureControls() {

		// Keep control construction grouped by presentation concern so later Answer
		// workflow changes do not accumulate in one monolithic configuration method.
		configureQuestionSelector();
		configureMultipleChoiceControls();
		configureAnswerPdfControls();
		configureAnswerRegionControls();
		configureAnswerPersistenceControls();
		configureAnswerStatusControls();
		configureAnswerRegionPreview();
		configureMcqExplanationControls();
	}

	private void configureMcqExplanationControls() {
		captureMcqExplanationsButton.setId("capture-mcq-explanations");
		captureMcqExplanationsButton.setMinWidth(Region.USE_PREF_SIZE);
		captureMcqExplanationsButton.setDisable(true);
		captureMcqExplanationsButton.setTooltip(
				new Tooltip("Capture missing marking-PDF explanation regions for answered multiple-choice Questions."));
		mcqExplanationQuestionField.setId("mcq-explanation-question");
		mcqExplanationQuestionField.setPromptText("Select answered MCQ");
		mcqExplanationQuestionField.setConverter(QUESTION_CODE_CONVERTER);
		mcqExplanationQuestionField.setMaxWidth(Double.MAX_VALUE);
		finishMcqExplanationCaptureButton.setId("finish-mcq-explanations");
		finishMcqExplanationCaptureButton.setMinWidth(Region.USE_PREF_SIZE);

		// The retrofit selector replaces the normal entry action only while the
		// explicit
		// MCQ-explanation workflow is active.
		HBox.setHgrow(mcqExplanationQuestionField, Priority.ALWAYS);
		mcqExplanationSelectionControls.getChildren().setAll(mcqExplanationQuestionField,
				finishMcqExplanationCaptureButton);
		mcqExplanationSelectionControls.setAlignment(Pos.CENTER_LEFT);
		setVisibleAndManaged(mcqExplanationSelectionControls, false);
	}

	private void configureMultipleChoiceControls() {
		answerAButton.setId("answer-choice-a");
		answerBButton.setId("answer-choice-b");
		answerCButton.setId("answer-choice-c");
		answerDButton.setId("answer-choice-d");
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

		// Selecting a current A-D choice supersedes any older arbitrary textual Answer
		// retained only for backwards compatibility.
		multipleChoiceAnswerGroup.selectedToggleProperty().addListener((_, _, newToggle) -> {
			if (newToggle != null) {
				preservedAnswerText = null;
			}
			refreshSaveButtonState();
		});
		setMultipleChoiceAnswerEnabled(false);
	}

	private void configureQuestionSelector() {
		unansweredQuestionField.setId("unanswered-question");
		unansweredQuestionField.setPromptText("Select unanswered question");
		unansweredQuestionField
				.setTooltip(new Tooltip("Lists Questions without an Answer in source order for the current booklet."));
		unansweredQuestionField.setMaxWidth(Double.MAX_VALUE);
		unansweredQuestionField.setConverter(QUESTION_CODE_CONVERTER);
		unansweredQuestionField.setOnShowing(_ -> showSelectedAnswerDocument());

		// Selection changes remain guarded by the Answer-transition workflow rather
		// than performing persistence or document work directly in this listener.
		unansweredQuestionField.valueProperty().addListener(
				(_, oldQuestion, newQuestion) -> handleUnansweredQuestionChanged(oldQuestion, newQuestion));
	}

	private ImageView createAcceptedAnswerPreview(AnswerRegion region) {
		try {
			BufferedImage clippedImage = questionExtractor.extractRegion(answerPdfSessionSupplier.get(), region);
			ImageView previewView = new ImageView(SwingFXUtils.toFXImage(clippedImage, null));
			previewView.setPreserveRatio(true);
			previewView.setSmooth(true);
			previewView.setCache(true);

			// Size the preview from the reserved ScrollPane viewport. This avoids a
			// self-referential pane-width -> preview-height -> pane-layout cycle.
			previewView.fitWidthProperty()
					.bind(Bindings.createDoubleBinding(
							() -> Math.max(0.0,
									answerRegionsScrollPane.getViewportBounds().getWidth()
											- REGION_PREVIEW_HORIZONTAL_INSET),
							answerRegionsScrollPane.viewportBoundsProperty()));
			return previewView;
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview accepted answer region", e);
		}
	}

	private VBox createAnswerPdfControls() {

		// Answer capture displays the authoritative assigned source but provides no
		// second path for selecting or replacing it.
		answerPdfControls.getChildren().setAll(selectedAnswerPdfLabel);
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

	private VBox createAnswerRegionRow(AnswerRegion region, int regionIndex) {
		Button removeButton = new Button("Remove");

		// Removing a region changes only transient Answer-edit state until Save.
		removeButton.setOnAction(_ -> removeAnswerRegion(regionIndex));
		HBox controls = new HBox(removeButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		VBox row = new VBox(COMPACT_SPACING);
		if (answerFile != null && region.answerFile().getId() == answerFile.getId()) {

			// Only the currently displayed AnswerFile can supply an immediate preview.
			row.getChildren().addAll(createAcceptedAnswerPreview(region), controls);
		} else {
			Label unavailable = new Label("Preview unavailable for page " + region.pageNumber());
			row.getChildren().addAll(unavailable, controls);
		}
		row.setFillWidth(true);
		return row;
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

	private void enterMcqExplanationCapture(List<Question> candidates, Long preferredQuestionId) {
		mcqExplanationMode = true;
		mcqExplanationReturnQuestion = unansweredQuestionField.getValue();

		// Ordinary unanswered capture and retrofit selection are separate explicit
		// modes. Hide the ordinary selector rather than presenting two active targets.
		setVisibleAndManaged(unansweredQuestionField, false);
		setVisibleAndManaged(captureMcqExplanationsButton, false);
		setVisibleAndManaged(mcqExplanationSelectionControls, true);
		restoringMcqExplanationSelection = true;
		try {
			mcqExplanationQuestionField.getItems().setAll(candidates);
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}

		// Clear ordinary Answer presentation while retaining its queue for return when
		// retrofit mode ends.
		applyUnansweredQuestionChange(null, false);
		selectedAnswerQuestionLabel.setText("Select an answered MCQ to capture explanation regions.");
		if (preferredQuestionId == null) {
			return;
		}
		Question preferred = candidates.stream()
				.filter(candidate -> candidate.getId() == preferredQuestionId.longValue()).findFirst().orElse(null);
		if (preferred != null) {

			// Dashboard entry owns an exact work item, so begin that edit immediately.
			mcqExplanationQuestionField.setValue(preferred);
		}
	}

	private void failAnswerSave(Throwable failure) {
		answerSaveInProgress = false;
		setDisable(false);
		refreshSaveButtonState();

		// Persistence failure leaves transient Answer content available for correction
		// and retry.
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText("Answer could not be saved.");
		alert.setContentText(
				failure == null || failure.getMessage() == null ? "The answer was not saved." : failure.getMessage());
		alert.showAndWait();
	}

	private AnswerFile findAssignedAnswerFileForDisplay(Question question) {
		try {
			AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(question.getBooklet());
			if (assignedAnswerFile == null) {

				// An unresolved booklet must never inherit another booklet's Answer
				// source merely because both belong to the same Exam.
				showAnswerFileRequired(question);
			}
			return assignedAnswerFile;
		} catch (SQLException exception) {
			showAnswerFileRequired(question);
			showAnswerFileError("Could not read the answer PDF assigned to this booklet.", exception.getMessage());
			return null;
		}
	}

	private Question findQuestionById(List<Question> questions, Question target) {
		if (target == null) {
			return null;
		}

		// Question objects may have been reconstructed by another repository read, so
		// persistent identity rather than Java object identity restores selection.
		return questions.stream().filter(question -> question.getId() == target.getId()).findFirst().orElse(null);
	}

	private String findValidationError(Question question, String answerText) {
		if (mcqExplanationMode && sameQuestion(question, mcqExplanationEditingQuestion)
				&& currentAnswerSelection == null && pendingAnswerRegions.isEmpty()) {

			// A retrofit candidate exists specifically because its explanation is missing.
			// Saving the unchanged A-D Answer must not falsely consume that required work.
			return "Capture at least one MCQ explanation region before saving.";
		}
		QuestionResponseType responseType = question == null ? null : question.getResponseType();
		return AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(question != null, responseType,
				currentAnswerSelection != null, answerText, pendingAnswerRegions.size()));
	}

	private void finishAnswerEdit(boolean reloadQuestions) {
		finishAnswerEditState(reloadQuestions).run();
	}

	private Runnable finishAnswerEditState(boolean reloadQuestions) {
		Runnable completedHandler = answerEditCompletedHandler;
		answerEditCompletedHandler = () -> {
		};
		boolean explanationEdit = mcqExplanationMode;
		editingAnswerQuestion = null;
		clearPendingAnswerRegions();
		clearMultipleChoiceAnswer();
		cancelAnswerEditButton.setVisible(false);
		cancelAnswerEditButton.setManaged(false);
		saveAnswerButton.setText("Save Answer");
		if (explanationEdit) {

			// Every explanation candidate in one retrofit session belongs to the same
			// AnswerFile. Keep that already-open document and its current page alive while
			// advancing to the next candidate.
			unansweredQuestionField.setDisable(true);
			return completedHandler;
		}
		answerFile = null;
		selectedAnswerPdfLabel.setText("No Answer PDF assigned");
		unansweredQuestionField.setDisable(false);
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

					// Source correction belongs to Exam / Assets rather than Answer capture.
					"Your answer is stored. Correct the Answer PDF assignment in Exam / Assets before continuing. "
							+ failure.getMessage());
		}

		// Direct Dashboard correction returns only after the complete Answer save
		// transition, including any asynchronous next-document handling, has finished.
		Runnable completedHandler = takeAnswerCaptureCompletion();
		if (completedHandler != null) {
			completedHandler.run();
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

	private void finishMcqExplanationEditState() {

		// Release edit ownership before deciding whether the candidate remains in this
		// retrofit session.
		mcqExplanationEditingQuestion = null;
		mcqExplanationEditSaved = false;
		unansweredQuestionField.setDisable(true);
		mcqExplanationQuestionField.setDisable(false);
		finishMcqExplanationCaptureButton.setDisable(false);
	}

	private void finishNextAnswerDocumentLoad(AnswerFile file, Throwable failure) {
		if (failure == null) {
			answerFile = file;
			selectedAnswerPdfLabel.setText(file.getName());
		} else {

			// Failure does not create an alternative capture-side source-selection path.
			clearAnswerFile("No Answer PDF assigned");
		}
		finishAnswerSaveTransition(failure);
	}

	private void handleMcqExplanationCandidateLoadFailure(Subject requestedSubject, ExamBooklet requestedBooklet,
			boolean activeBookletOwned, Throwable failure) {
		finishMcqExplanationCandidateLoad();
		if (!mcqExplanationRequestIsCurrent(requestedSubject, requestedBooklet, activeBookletOwned)) {
			return;
		}
		String message = failure == null || failure.getMessage() == null ? "The eligible Questions could not be loaded."
				: failure.getMessage();
		showAnswerFileError("MCQ explanation Questions could not be loaded.", message);
	}

	private void handleMcqExplanationCandidateLoadSuccess(Subject requestedSubject, ExamBooklet requestedBooklet,
			boolean activeBookletOwned, Long preferredQuestionId, List<Question> candidates) {
		finishMcqExplanationCandidateLoad();
		if (!mcqExplanationRequestIsCurrent(requestedSubject, requestedBooklet, activeBookletOwned)) {
			return;
		}
		if (candidates.isEmpty()) {
			selectedAnswerQuestionLabel.setText("No MCQs in this booklet are missing required explanation regions.");
			return;
		}
		if (preferredQuestionId != null
				&& candidates.stream().noneMatch(candidate -> candidate.getId() == preferredQuestionId.longValue())) {

			// A Dashboard snapshot can become stale while the worker is loading. Never
			// silently substitute a different Question for an explicitly selected row.
			selectedAnswerQuestionLabel.setText("The selected MCQ no longer requires explanation capture.");
			return;
		}
		enterMcqExplanationCapture(candidates, preferredQuestionId);
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

	private void handleNextAnswerFileLoaded(Question question, AnswerFile file) {
		if (!sameQuestion(question, unansweredQuestionField.getValue())) {
			finishAnswerSaveTransition(new CancellationException("Answer selection changed"));
			return;
		}
		if (file == null) {

			// Never carry the preceding booklet's PDF into an unresolved booklet.
			// Missing source assignment is corrected in Exam / Assets.
			clearAnswerFile("No Answer PDF assigned");
			finishAnswerSaveTransition(null);
			return;
		}
		openNextAnswerDocument(file);
	}

	private void handleNextAnswerFileLoadFailure(Throwable failure) {

		// A failed lookup must not leave the preceding booklet's AnswerFile appearing
		// to belong to the new Question.
		clearAnswerFile("No Answer PDF assigned");
		finishAnswerSaveTransition(failure);
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
		AnswerFile assignedAnswerFile = findAssignedAnswerFileForDisplay(question);
		if (assignedAnswerFile == null) {
			return false;
		}
		if (answerFile != null && answerFile.getId() == assignedAnswerFile.getId()) {
			return redisplayAssignedAnswerFile(question, assignedAnswerFile);
		}
		return openDifferentAssignedAnswerFile(question, assignedAnswerFile);
	}

	private List<Question> loadMcqExplanationCandidates(List<Question> questions, Subject subject,
			ExamBooklet activeBooklet) throws SQLException {
		Objects.requireNonNull(questions, "questions");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(activeBooklet, "activeBooklet");
		AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(activeBooklet);
		if (assignedAnswerFile == null || !assignedAnswerFile.hasAnswerExplanations()) {

			// A booklet without declared explanation material has no explanation
			// requirement and must never inherit candidates from another booklet.
			return List.of();
		}
		return questions.stream().filter(question -> subject.equals(question.getExam().getSubject()))
				.filter(question -> question.getBooklet().getId() == activeBooklet.getId())
				.filter(this::isMultipleChoiceQuestion).filter(Question::hasAnswer).filter(question -> {

					// Retrofit requires an authoritative A-D Answer. Invalid legacy text
					// remains ordinary Answer-correction work.
					String answerText = question.getAnswer().getAnswerText();
					String validationError = AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(true,
							QuestionResponseType.MULTIPLE_CHOICE, false, answerText, 0));
					return validationError == null;
				}).filter(question -> {

					// Only genuinely missing explanations belong in the work queue. Already
					// explained MCQs remain authoritative and are not re-presented as work.
					return question.getAnswer().getRegions().isEmpty();
				}).sorted(QuestionSourceOrder.comparator()).toList();
	}

	private void loadNextAnswerDocument(Question question) {
		if (question == null || !usesAnswerDocument(question)) {

			// Nothing remains that may legitimately retain the previous booklet's
			// Answer source.
			clearAnswerFile("No Answer PDF assigned");
			finishAnswerSaveTransition(null);
			return;
		}
		answerRegionStatusLabel.setText("Answer saved — loading next question...");
		CaptureBackgroundTask<AnswerFile> task = new CaptureBackgroundTask<>(
				() -> answerWriter.findAnswerFile(question.getBooklet()));
		task.setOnSucceeded(_ -> handleNextAnswerFileLoaded(question, task.getValue()));
		task.setOnFailed(_ -> handleNextAnswerFileLoadFailure(task.getException()));
		startVirtualTask("next-answer-file", task);
	}

	private boolean mcqExplanationCaptureUnavailable(ExamBooklet requestedBooklet) {

		// Entry requires an idle Answer workflow and an authoritative AnswerFile
		// explicitly marked as containing MCQ explanations.
		return requestedBooklet == null || mcqExplanationMode || mcqExplanationLoadInProgress || workingSubject == null
				|| answerSaveInProgress || editingAnswerQuestion != null
				|| requestedBooklet.getExam().getSubject().getId() != workingSubject.getId()
				|| !activeBookletSupportsMcqExplanations(requestedBooklet);
	}

	private boolean mcqExplanationRequestIsCurrent(Subject requestedSubject, ExamBooklet requestedBooklet,
			boolean activeBookletOwned) {
		if (!Objects.equals(workingSubject, requestedSubject)) {

			// A later Subject transition always invalidates the completed worker.
			return false;
		}
		if (activeBookletOwned) {

			// Normal Capture owns the active structural booklet throughout candidate
			// loading.
			return sameBooklet(requestedBooklet, activeBookletSupplier.get());
		}

		// Dashboard entry does not change ExamMetadataPane merely to edit Answer
		// explanations. Its transient Exam scope is therefore the ownership guard.
		return examScope != null && examScope.getId() == requestedBooklet.getExam().getId();
	}

	private RadioButton multipleChoiceButtonFor(String answerText) {

		// A-D matching remains case-insensitive for older persisted Answer text.
		return switch (answerText) {
		case "A", "a" -> answerAButton;
		case "B", "b" -> answerBButton;
		case "C", "c" -> answerCButton;
		case "D", "d" -> answerDButton;
		default -> null;
		};
	}

	private boolean openDifferentAssignedAnswerFile(Question question, AnswerFile assignedAnswerFile) {

		// Clear only identity before resolution. The existing label is retained until
		// either the new document opens or the failure state is known.
		answerFile = null;
		Path pdfPath = resolveRegisteredAnswerFile(assignedAnswerFile);
		if (pdfPath == null) {
			showAnswerFileRequired(question);
			return false;
		}
		boolean opened = openRegisteredAnswerFile(assignedAnswerFile, pdfPath);
		updateAnswerPdfControlsVisibility(question);
		return opened;
	}

	private void openNextAnswerDocument(AnswerFile file) {
		try {

			// Resolve the persisted managed relative path against the Answer PDF store.
			Path path = new PdfStore(pdfDataRoot).resolve(file.getSourceDocument().getRelativePath());
			SelectedPdf selected = new SelectedPdf(path.toFile(), path, pdfDataRoot);

			// Save transition remains active until the asynchronous PDF loader reports
			// completion.
			answerPdfLoader.accept(selected, failure -> finishNextAnswerDocumentLoad(file, failure));
		} catch (RuntimeException exception) {

			// A bad persisted path must still release the save transition.
			answerFile = null;
			finishAnswerSaveTransition(exception);
		}
	}

	private boolean openRegisteredAnswerFile(AnswerFile registeredAnswerFile, Path pdfPath) {
		if (!Files.isRegularFile(pdfPath)) {
			showAnswerFileError("The registered answer PDF is unavailable.", pdfPath.toString());
			return false;
		}
		SelectedPdf selectedPdf = new SelectedPdf(pdfPath.toFile(), pdfPath, pdfDataRoot);
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

	private Answer persistAnswer(Question question, long existingAnswerId, String storedText,
			List<AnswerRegion> regions, boolean updating) throws SQLException {

		// Existing Answers preserve identity; new Answers use the insert path.
		if (updating) {
			return answerWriter.updateAnswer(question, existingAnswerId, storedText, regions);
		}
		return answerWriter.insertAnswer(question, storedText, regions);
	}

	private void prepareAnswerFileForQuestion(boolean usesAnswerDocument) {
		if (!usesAnswerDocument) {

			// UNKNOWN response types cannot retain a booklet-specific Answer source.
			clearAnswerFile("No Answer PDF assigned");
		}
	}

	private boolean redisplayAssignedAnswerFile(Question question, AnswerFile assignedAnswerFile) {
		if (answerPdfSessionSupplier.get() == null) {

			// Dashboard return deliberately closes managed PDF sessions. The retained
			// AnswerFile identity therefore cannot prove that its PDF is still open.
			// Reopen the authoritative assigned file instead of trying to redisplay a
			// session that no longer exists.
			return openDifferentAssignedAnswerFile(question, assignedAnswerFile);
		}
		answerFile = assignedAnswerFile;
		selectedAnswerPdfLabel.setText(assignedAnswerFile.getName());
		try {

			// Consecutive Questions may genuinely share one still-open AnswerFile.
			answerDocumentHandler.run();
		} catch (RuntimeException exception) {
			showAnswerFileRequired(question);
			showAnswerFileError("The assigned answer PDF could not be displayed.", exception.getMessage());
			return false;
		}
		updateAnswerPdfControlsVisibility(question);
		return true;
	}

	private void refreshAnswerPresentation(Question question) {
		refreshAnswerRegionList();
		if (question.hasAnswer()) {
			showAcceptedRegionStatus();
		}
		updateMultipleChoiceAnswerVisibility(question);
		updateAnswerRegionControlsVisibility(question);

		// Both supported response types may own an Answer document, so presentation
		// derives PDF-control visibility directly from the selected Question.
		updateAnswerPdfControlsVisibility(question);
		if (question.getResponseType() == QuestionResponseType.UNKNOWN) {
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — response type unresolved; "
					+ "use Edit Metadata before capturing an answer.");
		}
		refreshSaveButtonState();
	}

	private void refreshAnswerRegionList() {
		boolean showRegions = !pendingAnswerRegions.isEmpty()
				&& answerRegionControlsVisibleFor(unansweredQuestionField.getValue());

		// The AnswerFile flag changes MCQ explanation presentation but written-response
		// regions retain the same preview rules.
		setVisibleAndManaged(answerRegionsScrollPane, showRegions);
		answerRegionListBox.getChildren().clear();
		if (!showRegions) {
			return;
		}
		for (int index = 0; index < pendingAnswerRegions.size(); index++) {
			answerRegionListBox.getChildren().add(createAnswerRegionRow(pendingAnswerRegions.get(index), index));
		}
		answerRegionListBox.requestLayout();
		answerRegionsScrollPane.requestLayout();
	}

	private void refreshMcqExplanationActionState() {
		ExamBooklet activeBooklet = activeBookletSupplier.get();

		// The button represents real available work, so expose it only when the active
		// booklet's persisted AnswerFile explicitly supports MCQ explanations.
		boolean unavailable = workingSubject == null || mcqExplanationLoadInProgress || mcqExplanationMode
				|| answerSaveInProgress || editingAnswerQuestion != null
				|| !activeBookletSupportsMcqExplanations(activeBooklet);
		captureMcqExplanationsButton.setDisable(unavailable);
	}

	private void refreshSaveButtonState() {
		Question question = unansweredQuestionField.getValue();
		String answerText = currentAnswerText();
		boolean ready = !answerSaveInProgress && findValidationError(question, answerText) == null;
		saveAnswerButton.setDisable(!ready);
	}

	private AnswerFile registerSelectedAnswerFile(Question question, SelectedPdf selectedPdf) {
		try {

			// Persist the managed-file hash before publishing the AnswerFile identity.
			String contentSha256 = sourceDocumentHashService.sha256(selectedPdf.path());
			return answerWriter.findOrCreateAnswerFile(question.getBooklet(), selectedPdf.file().getName(),
					selectedPdf.relativePath(), contentSha256);
		} catch (IOException | SQLException exception) {
			throw new IllegalStateException("Unable to save answer PDF", exception);
		}
	}

	private void removeAnswerRegion(int regionIndex) {
		pendingAnswerRegions.remove(regionIndex);
		refreshAnswerRegionList();
		showAcceptedRegionStatus();
		refreshSaveButtonState();
	}

	private int removeMcqExplanationCandidate(Question completedQuestion) {
		int completedIndex = -1;
		for (int index = 0; index < mcqExplanationQuestionField.getItems().size(); index++) {
			if (mcqExplanationQuestionField.getItems().get(index).getId() == completedQuestion.getId()) {
				completedIndex = index;
				break;
			}
		}
		restoringMcqExplanationSelection = true;
		try {

			// Removal is session workflow state only; it does not persist explanation
			// completeness against the Question.
			if (completedIndex >= 0) {
				mcqExplanationQuestionField.getItems().remove(completedIndex);
			}
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}
		return completedIndex;
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

	private void replaceUnansweredQuestionsSilently(List<Question> questions, Question selected) {
		restoringUnansweredQuestionSelection = true;
		try {

			// Replace queue contents and selection as one suppressed UI transaction.
			unansweredQuestionField.getItems().setAll(questions);
			unansweredQuestionField.setValue(selected);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
	}

	private void resetMcqExplanationPresentation() {
		mcqExplanationMode = false;
		mcqExplanationReturnQuestion = null;
		mcqExplanationEditingQuestion = null;
		mcqExplanationEditSaved = false;
		setVisibleAndManaged(captureMcqExplanationsButton, true);
		refreshMcqExplanationActionState();
		setVisibleAndManaged(mcqExplanationSelectionControls, false);
		mcqExplanationQuestionField.setDisable(false);
		finishMcqExplanationCaptureButton.setDisable(false);
		restoringMcqExplanationSelection = true;
		try {

			// Retrofit candidates are session-local and cannot leak into the next
			// invocation.
			mcqExplanationQuestionField.getItems().clear();
			mcqExplanationQuestionField.setValue(null);
		} finally {
			restoringMcqExplanationSelection = false;
		}
		setVisibleAndManaged(unansweredQuestionField, true);
		unansweredQuestionField.setDisable(false);
	}

	private Path resolveRegisteredAnswerFile(AnswerFile registeredAnswerFile) {
		PdfStore pdfStore = new PdfStore(pdfDataRoot);
		try {
			return pdfStore.resolve(registeredAnswerFile.getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException e) {
			showAnswerFileError("The registered answer PDF path is invalid.", e.getMessage());
			return null;
		}
	}

	private void restoreAnswerState(Question question) {
		if (!question.hasAnswer()) {
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
			saveAnswerButton.setText("Save Answer");
			answerRegionCountLabel.setText("Regions: 0");
			answerRegionStatusLabel.setText("");
			return;
		}
		Answer answer = question.getAnswer();
		selectMultipleChoiceAnswer(answer.getAnswerText());

		// Persisted regions remain authoritative transient edit state while their
		// booklet mapping determines which document supplies their preview.
		pendingAnswerRegions.addAll(answer.getRegions());
		selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — answer stored");
		saveAnswerButton.setText("Update Answer");
	}

	private void restoreMcqExplanationCandidateAfterCancel() {

		// Cancel leaves the candidate in the current session and returns control to
		// explicit selection.
		clearMcqExplanationSelection();
		selectedAnswerQuestionLabel.setText("Select an answered MCQ to capture explanation regions.");
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
		if (answerCaptureCompletionQuestionId > 0 && answerCaptureCompletionQuestionId != question.getId()) {

			// Changing to and saving another Question means the original direct
			// Dashboard correction was abandoned.
			clearAnswerCaptureCompletion();
		}
		int previousIndex = unansweredQuestionField.getSelectionModel().getSelectedIndex();
		String storedText = answerText.isBlank() ? null : answerText;

		// Snapshot transient Answer content before background persistence takes
		// ownership of the save.
		List<AnswerRegion> regions = List.copyOf(pendingAnswerRegions);
		boolean updating = question.hasAnswer();
		long existingAnswerId = updating ? question.getAnswer().getId() : 0;
		boolean editing = editingAnswerQuestion != null;
		beginAnswerSave();
		CaptureBackgroundTask<Answer> saveTask = new CaptureBackgroundTask<>(
				() -> persistAnswer(question, existingAnswerId, storedText, regions, updating));
		saveTask.setOnSucceeded(_ -> completeAnswerSave(question, saveTask.getValue(), editing, previousIndex));
		saveTask.setOnFailed(_ -> failAnswerSave(saveTask.getException()));
		startDaemonTask("answer-save", saveTask);
	}

	private void selectMultipleChoiceAnswer(String answerText) {
		multipleChoiceAnswerGroup.selectToggle(null);
		preservedAnswerText = null;
		if (answerText == null || answerText.isBlank()) {
			return;
		}
		RadioButton answerButton = multipleChoiceButtonFor(answerText.trim());
		if (answerButton != null) {
			multipleChoiceAnswerGroup.selectToggle(answerButton);
			return;
		}

		// Preserve older arbitrary textual answers even though current capture exposes
		// only A-D choices.
		preservedAnswerText = answerText;
	}

	private void selectNextMcqExplanationCandidate(int completedIndex) {
		int nextIndex = completedIndex < 0 ? 0
				: Math.min(completedIndex, mcqExplanationQuestionField.getItems().size() - 1);

		// Normal ComboBox selection deliberately invokes the existing edit workflow
		// for the next candidate.
		mcqExplanationQuestionField.getSelectionModel().select(nextIndex);
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

	private void setUnansweredQuestionSilently(Question question) {
		restoringUnansweredQuestionSelection = true;
		try {

			// Programmatic queue maintenance must not enter the ordinary target-change
			// listener and repeat transition guards.
			unansweredQuestionField.setValue(question);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
	}

	private void setVisibleAndManaged(javafx.scene.Node node, boolean visible) {

		// JavaFX layout participation must always follow visual visibility for
		// conditional Answer-capture controls.
		node.setVisible(visible);
		node.setManaged(visible);
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

	private void showAnswerFileRequired(Question question) {

		// Answer capture cannot manufacture missing assessment structure. The source
		// must be assigned through the Exam / Assets workflow.
		clearAnswerFile("No Answer PDF assigned - use Exam / Assets");
		updateAnswerPdfControlsVisibility(question);
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

	private void showNoAnswerQuestion() {
		clearAnswerFile("No Answer PDF assigned");
		selectedAnswerQuestionLabel.setText("No question selected");
		updateMultipleChoiceAnswerVisibility(null);
		updateAnswerRegionControlsVisibility(null);
		saveAnswerButton.setDisable(true);
		saveAnswerButton.setText("Save Answer");
		answerRegionCountLabel.setText("Regions: 0");
		answerRegionStatusLabel.setText("");
		updateAnswerPdfControlsVisibility(null);
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

	private void startDaemonTask(String threadName, Runnable task) {

		// Preserve the existing daemon-thread semantics used by Answer persistence.
		Thread thread = new Thread(task, threadName);
		thread.setDaemon(true);
		thread.start();
	}

	private boolean startMcqExplanationCapture(ExamBooklet requestedBooklet, Question preferredQuestion,
			boolean activeBookletOwned) {
		if (mcqExplanationCaptureUnavailable(requestedBooklet)) {
			return false;
		}
		if (preferredQuestion != null && preferredQuestion.getBooklet().getId() != requestedBooklet.getId()) {
			throw new IllegalArgumentException("Preferred MCQ does not belong to the requested booklet");
		}
		if (hasUnsavedOrdinaryAnswerDraft()) {
			showAnswerWorkflowWarning("Unsaved Answer work",
					"Save or clear the current Answer work before capturing MCQ explanations.");
			return false;
		}
		if (!answerTransitionAllowed.getAsBoolean()) {
			return false;
		}
		Subject requestedSubject = workingSubject;
		Long preferredQuestionId = preferredQuestion == null ? null : Long.valueOf(preferredQuestion.getId());
		beginMcqExplanationCandidateLoad();
		CaptureBackgroundTask<List<Question>> task = new CaptureBackgroundTask<>(
				() -> loadMcqExplanationCandidates(questionRepository.findAll(), requestedSubject, requestedBooklet));
		task.setOnSucceeded(_ -> handleMcqExplanationCandidateLoadSuccess(requestedSubject, requestedBooklet,
				activeBookletOwned, preferredQuestionId, task.getValue()));
		task.setOnFailed(_ -> handleMcqExplanationCandidateLoadFailure(requestedSubject, requestedBooklet,
				activeBookletOwned, task.getException()));
		startVirtualTask("mcq-explanation-candidates", task);
		return true;
	}

	private void startVirtualTask(String threadName, Runnable task) {

		// Lookup-style background work uses lightweight virtual threads while JavaFX
		// Task retains success/failure delivery on the application thread.
		Thread.ofVirtual().name(threadName).start(task);
	}

	private Runnable takeAnswerCaptureCompletion() {
		if (answerCaptureCompletionQuestionId <= 0) {
			return null;
		}

		// Successful direct Answer capture consumes its Dashboard return callback once.
		Runnable completedHandler = answerCaptureCompletedHandler;
		clearAnswerCaptureCompletion();
		return completedHandler;
	}

	private void updateAnswerPdfControlsVisibility(Question question) {
		boolean visible = usesAnswerDocument(question);

		// The row now reports the authoritative Exam/Assets assignment rather than
		// appearing only when capture wants the user to choose a source.
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
