package au.edu.eq.questionbank.ui;

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
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;

//Reuse the shared provider/year/booklet/natural Question ordering policy.
import au.edu.eq.questionbank.model.QuestionSourceOrder;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
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
final class AnswerCapturePane extends VBox {

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

	// A question-save snapshot may predate an answer committed while it was
	// loading.
	private final Set<Long> locallyAnsweredQuestionIds = new HashSet<>();
	private Question editingAnswerQuestion;
	private boolean answerSaveInProgress;
	private String preservedAnswerText;
	private Runnable answerEditCompletedHandler = () -> {
	};

	/**
	 * Creates the answer-capture workflow and its persistence integration.
	 */
	AnswerCapturePane(Stage stage, QuestionRepository questionRepository, SqliteAnswerWriter answerWriter,
			PdfFilePicker pdfFilePicker, Consumer<SelectedPdf> answerPdfHandler, Runnable answerDocumentHandler,
			BooleanSupplier answerTransitionAllowed, Runnable selectionClearHandler,
			QuestionExtractor questionExtractor, Supplier<PdfSession> answerPdfSessionSupplier,
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
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (answerDocumentHandler == null) {
			throw new NullPointerException("answerDocumentHandler");
		}
		if (answerTransitionAllowed == null) {
			throw new NullPointerException("answerTransitionAllowed");
		}
		this.questionRepository = questionRepository;
		this.pdfFilePicker = pdfFilePicker;
		this.answerPdfHandler = answerPdfHandler;
		this.answerPdfLoader = Objects.requireNonNull(answerPdfLoader, "answerPdfLoader");
		this.selectionClearHandler = selectionClearHandler;
		this.questionExtractor = questionExtractor;
		this.answerPdfSessionSupplier = answerPdfSessionSupplier;
		this.answerWriter = answerWriter;
		this.answerDocumentHandler = answerDocumentHandler;
		this.answerTransitionAllowed = answerTransitionAllowed;
		configureControls();
		configureActions(stage);
		getChildren().addAll(createSectionLabel("Answer"), unansweredQuestionField, selectedAnswerQuestionLabel,
				createAnswerPdfControls(), createAnswerRegionControls(), answerRegionsScrollPane,
				createMultipleChoiceAnswerControls());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	/**
	 * Returns Questions still awaiting Answers in deterministic source order.
	 *
	 * @param questions                  current Question snapshot
	 * @param locallyAnsweredQuestionIds Questions answered after an older snapshot
	 *                                   was loaded
	 * @return unanswered Questions in provider/year/booklet/natural-code order
	 */
	static List<Question> unansweredQuestionsInSourceOrder(List<Question> questions,
			Set<Long> locallyAnsweredQuestionIds) {
		Objects.requireNonNull(questions, "questions");
		Objects.requireNonNull(locallyAnsweredQuestionIds, "locallyAnsweredQuestionIds");
		/*
		 * Exclude both persisted Answers and Questions known to have been answered
		 * locally since the supplied snapshot was obtained, then apply the common
		 * source-order policy used by other corpus work queues.
		 */
		return questions.stream()
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
	void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
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

	boolean canCaptureRegions() {
		return !answerSaveInProgress && answerFile != null
				&& isWrittenResponseQuestion(unansweredQuestionField.getValue());
	}

	boolean captureAnswer(Question question) {
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
	void clearCurrentSelectionForPageChange() {
		currentAnswerSelection = null;
		answerRegionStatusLabel.setText("");
		setSelectionActionsEnabled(false);
	}

	/**
	 * Discards only the unaccepted Answer selection after another capture workflow
	 * takes ownership of the single PDF selection.
	 */
	void discardCurrentSelectionForOwnershipLoss() {
		/*
		 * The new workflow already owns the visible PDF rectangle, so clear only this
		 * pane's stale local state and never call selectionClearHandler here.
		 */
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
	boolean editAnswer(Question question, Runnable editCompletedHandler) {
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
		saveAnswerButton.setText("Update Answer");
		cancelAnswerEditButton.setVisible(true);
		cancelAnswerEditButton.setManaged(true);
		return true;
	}

	/**
	 * @return whether accepted answer regions are loaded in the current capture or
	 *         edit
	 */
	boolean hasAcceptedRegions() {
		return !pendingAnswerRegions.isEmpty();
	}

	/**
	 * @return whether an answer PDF has been selected or restored for the current
	 *         question
	 */
	boolean hasAnswerFile() {
		return answerFile != null;
	}

	/**
	 * @return whether answer persistence is currently running
	 */
	boolean isSaveInProgress() {
		return answerSaveInProgress;
	}

	/**
	 * Reloads persisted questions that do not yet have an answer.
	 */
	void refreshQuestions() {
		refreshQuestions(questionRepository.findAll());
	}

	void refreshQuestions(List<Question> questions) {
		Question editTarget = editingAnswerQuestion;
		Question selected = editTarget == null ? unansweredQuestionField.getValue() : editTarget;
		/*
		 * Build the Answer work queue independently of repository insertion order so
		 * that sustained Answer capture follows the examination's natural source order.
		 */
		List<Question> unansweredQuestions = unansweredQuestionsInSourceOrder(questions, locallyAnsweredQuestionIds);
		Question matching = editTarget;
		if (matching == null && selected != null) {
			for (Question question : unansweredQuestions) {
				/*
				 * Preserve the currently selected Question across queue refreshes by persistent
				 * identity rather than object instance.
				 */
				if (question.getId() == selected.getId()) {
					matching = question;
					break;
				}
			}
		}
		/*
		 * Suppress the normal selection listener while replacing the queue and
		 * restoring the previous selection.
		 */
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.getItems().setAll(unansweredQuestions);
			unansweredQuestionField.setValue(matching);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
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
			answerFile = answerWriter.findOrCreateAnswerFile(question.getExam(), selectedPdf.file().getName(),
					selectedPdf.relativePath());
		} catch (SQLException e) {
			throw new IllegalStateException("Unable to save answer PDF", e);
		}
		answerPdfHandler.accept(selectedPdf);
		selectedAnswerPdfLabel.setText(selectedPdf.file().getName());
		updateAnswerPdfControlsVisibility(question);
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
		if (answerFile != null && answerFile.getExam().getId() != question.getExam().getId()) {
			answerFile = null;
			selectedAnswerPdfLabel.setText("No PDF selected");
		}
		boolean writtenResponse = isWrittenResponseQuestion(question);
		boolean openedAnswerPdf = false;
		if (question.hasAnswer()) {
			Answer answer = question.getAnswer();
			selectMultipleChoiceAnswer(answer.getAnswerText());
			/*
			 * Preserve any historical regions regardless of response type. MCQ mode simply
			 * does not display or require them.
			 */
			pendingAnswerRegions.addAll(answer.getRegions());
			if (writtenResponse) {
				if (!answer.getRegions().isEmpty()) {
					AnswerFile storedAnswerFile = answer.getRegions().getFirst().answerFile();
					Path pdfPath = resolveRegisteredAnswerFile(storedAnswerFile);
					if (pdfPath != null) {
						openedAnswerPdf = openRegisteredAnswerFile(storedAnswerFile, pdfPath);
					}
				} else if (answerFile == null) {
					openedAnswerPdf = loadRegisteredAnswerFile(question);
				}
			}
			refreshAnswerRegionList();
			showAcceptedRegionStatus();
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — answer stored");
			saveAnswerButton.setText("Update Answer");
		} else {
			if (writtenResponse && loadDocument && answerFile == null) {
				openedAnswerPdf = loadRegisteredAnswerFile(question);
			}
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
			saveAnswerButton.setText("Save Answer");
			answerRegionCountLabel.setText("Regions: 0");
			answerRegionStatusLabel.setText("");
		}
		updateMultipleChoiceAnswerVisibility(question);
		updateAnswerRegionControlsVisibility(question);
		chooseAnswerPdfButton.setDisable(!writtenResponse);
		updateAnswerPdfControlsVisibility(question);
		if (question.getResponseType() == QuestionResponseType.UNKNOWN) {
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — response type unresolved; "
					+ "use Edit Metadata before capturing an answer.");
		}
		refreshSaveButtonState();
		if (loadDocument && writtenResponse && answerFile != null && !openedAnswerPdf) {
			answerDocumentHandler.run();
		}
	}

	private void cancelAnswerEdit() {
		if (editingAnswerQuestion == null) {
			return;
		}
		finishAnswerEdit();
	}

	private void chooseAnswerPdf(Stage stage) {
		Question question = unansweredQuestionField.getValue();
		if (question == null) {
			showError("Select a question first.");
			return;
		}
		if (!isWrittenResponseQuestion(question)) {
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

	private void configureActions(Stage stage) {
		addAnswerRegionButton.setOnAction(_ -> addCurrentAnswerRegion());
		chooseAnswerPdfButton.setOnAction(_ -> chooseAnswerPdf(stage));
		clearAnswerSelectionButton.setOnAction(_ -> clearCurrentAnswerSelection());
		saveAnswerButton.setOnAction(_ -> validateAnswerForSave());
		cancelAnswerEditButton.setOnAction(_ -> cancelAnswerEdit());
		clearMultipleChoiceAnswerButton.setOnAction(_ -> {
			clearMultipleChoiceAnswer();
			refreshSaveButtonState();
		});
	}

	private void configureControls() {
		unansweredQuestionField.setId("unanswered-question");
		unansweredQuestionField.setPromptText("Select unanswered question");
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
		chooseAnswerPdfButton.setDisable(true);
		chooseAnswerPdfButton.setId("choose-answer-pdf");
		addAnswerRegionButton.setId("add-answer-region");
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
		answerRegionsScrollPane.setPrefHeight(ANSWER_REGIONS_VIEWPORT_HEIGHT);
		answerRegionsScrollPane.setMaxHeight(ANSWER_REGIONS_VIEWPORT_HEIGHT);
		answerRegionsScrollPane.setMaxWidth(Double.MAX_VALUE);
		answerRegionsScrollPane.setVisible(false);
		answerRegionsScrollPane.setManaged(false);
		setSelectionActionsEnabled(false);
	}

	private ImageView createAcceptedAnswerPreview(AnswerRegion region) {
		try {
			BufferedImage clippedImage = questionExtractor.extractRegion(answerPdfSessionSupplier.get(), region);
			ImageView previewView = new ImageView(SwingFXUtils.toFXImage(clippedImage, null));
			previewView.setPreserveRatio(true);
			previewView.setSmooth(true);
			previewView.setCache(true);
			/*
			 * Bind to the containing Answer pane rather than the ScrollPane viewport. The
			 * Answer pane is already laid out when the first region is accepted, and its
			 * width is unaffected by the ScrollPane's vertical scrollbar.
			 */
			previewView.fitWidthProperty().bind(Bindings.createDoubleBinding(
					() -> Math.max(0.0, getWidth() - REGION_PREVIEW_HORIZONTAL_INSET), widthProperty()));
			return previewView;
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview accepted answer region", e);
		}
	}

	private VBox createAnswerPdfControls() {
		/*
		 * A filename can be much wider than the capture workspace. Give it a dedicated
		 * wrapping row so it can never force the Choose PDF action below its readable
		 * width.
		 */
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

	private boolean isMultipleChoiceQuestion(Question question) {
		return question != null && question.getResponseType() == QuestionResponseType.MULTIPLE_CHOICE;
	}

	private boolean isWrittenResponseQuestion(Question question) {
		return question != null && question.getResponseType() == QuestionResponseType.WRITTEN_RESPONSE;
	}

	private void loadNextAnswerDocument(Question question) {
		if (question == null || !isWrittenResponseQuestion(question)) {
			finishAnswerSaveTransition(null);
			return;
		}
		answerRegionStatusLabel.setText("Answer saved — loading next question...");
		if (answerFile != null) {
			openNextAnswerDocument(answerFile);
			return;
		}
		Task<List<AnswerFile>> task = new Task<>() {

			@Override
			protected List<AnswerFile> call() throws SQLException {
				return answerWriter.findAnswerFiles(question.getExam());
			}
		};
		task.setOnSucceeded(_ -> {
			if (!sameQuestion(question, unansweredQuestionField.getValue())) {
				finishAnswerSaveTransition(new CancellationException("Answer selection changed"));
				return;
			}
			AnswerFile file = selectRegisteredAnswerFile(task.getValue());
			if (file == null) {
				finishAnswerSaveTransition(null);
			} else {
				openNextAnswerDocument(file);
			}
		});
		task.setOnFailed(_ -> finishAnswerSaveTransition(task.getException()));
		Thread.ofVirtual().name("next-answer-file").start(task);
	}

	private boolean loadRegisteredAnswerFile(Question question) {
		List<AnswerFile> answerFiles;
		try {
			answerFiles = answerWriter.findAnswerFiles(question.getExam());
		} catch (SQLException e) {
			showAnswerFileError("Could not read the registered answer files.", e.getMessage());
			updateAnswerPdfControlsVisibility(question);
			return false;
		}
		AnswerFile registeredAnswerFile = selectRegisteredAnswerFile(answerFiles);
		if (registeredAnswerFile == null) {
			updateAnswerPdfControlsVisibility(question);
			return false;
		}
		Path pdfPath = resolveRegisteredAnswerFile(registeredAnswerFile);
		if (pdfPath == null) {
			updateAnswerPdfControlsVisibility(question);
			return false;
		}
		boolean opened = openRegisteredAnswerFile(registeredAnswerFile, pdfPath);
		updateAnswerPdfControlsVisibility(question);
		return opened;
	}

	private void openNextAnswerDocument(AnswerFile file) {
		try {
			Path path = new PdfStore(pdfFilePicker.dataRoot()).resolve(file.getSourceDocument().getRelativePath());
			SelectedPdf selected = new SelectedPdf(path.toFile(), path, pdfFilePicker.dataRoot());
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
		boolean showRegions = hasRegions && isWrittenResponseQuestion(unansweredQuestionField.getValue());
		/*
		 * Stored regions remain in pendingAnswerRegions even when the current response
		 * type does not display region capture. This is important when legacy
		 * answer-region data exists for a Question later classified as MCQ.
		 */
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
			removeButton.setOnAction(_ -> removeAnswerRegion(regionIndex));
			HBox controls = new HBox(removeButton);
			controls.setAlignment(Pos.CENTER_LEFT);
			VBox row = new VBox(COMPACT_SPACING);
			if (answerFile != null && region.answerFile().getId() == answerFile.getId()) {
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
			question.setAnswer(answer);
			locallyAnsweredQuestionIds.add(question.getId());
			if (editing) {
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
		/*
		 * Preserve older arbitrary textual answers even though the current capture UI
		 * only exposes A-D choices.
		 */
		preservedAnswerText = answerText;
	}

	private AnswerFile selectRegisteredAnswerFile(List<AnswerFile> answerFiles) {
		if (answerFiles.isEmpty()) {
			selectedAnswerPdfLabel.setText("No PDF selected");
			return null;
		}
		if (answerFiles.size() > 1) {
			selectedAnswerPdfLabel.setText("Multiple answer PDFs registered — choose PDF...");
			return null;
		}
		return answerFiles.get(0);
	}

	private void setMultipleChoiceAnswerEnabled(boolean enabled) {
		answerAButton.setDisable(!enabled);
		answerBButton.setDisable(!enabled);
		answerCButton.setDisable(!enabled);
		answerDButton.setDisable(!enabled);
		clearMultipleChoiceAnswerButton.setDisable(!enabled);
	}

	private void setSelectionActionsEnabled(boolean enabled) {
		boolean effective = enabled && isWrittenResponseQuestion(unansweredQuestionField.getValue());
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

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText("Answer is incomplete.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showSelectedAnswerDocument() {
		Question question = unansweredQuestionField.getValue();
		if (question == null || answerSaveInProgress || !isWrittenResponseQuestion(question)) {
			return;
		}
		if (answerFile != null && answerFile.getExam().getId() == question.getExam().getId()) {
			answerDocumentHandler.run();
			return;
		}
		loadRegisteredAnswerFile(question);
	}

	private void updateAnswerPdfControlsVisibility(Question question) {
		boolean writtenResponse = isWrittenResponseQuestion(question);
		boolean answerPdfKnown = writtenResponse && answerFile != null
				&& answerFile.getExam().getId() == question.getExam().getId();
		boolean visible = writtenResponse && !answerPdfKnown;
		answerPdfControls.setVisible(visible);
		answerPdfControls.setManaged(visible);
	}

	private void updateAnswerRegionControlsVisibility(Question question) {
		boolean visible = isWrittenResponseQuestion(question);
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
			setSelectionActionsEnabled(false);
		}
	}

	private void updateMultipleChoiceAnswerVisibility(Question question) {
		boolean visible = isMultipleChoiceQuestion(question);
		multipleChoiceAnswerControls.setVisible(visible);
		multipleChoiceAnswerControls.setManaged(visible);
		setMultipleChoiceAnswerEnabled(visible);
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
