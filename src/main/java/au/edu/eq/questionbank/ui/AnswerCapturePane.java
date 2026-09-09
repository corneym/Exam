package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Owns unanswered-question selection, answer source and region state, textual
 * answers, validation, and creation or editing of persisted answers. All
 * control and capture-state access belongs on the JavaFX application thread.
 */
final class AnswerCapturePane extends VBox {

	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double ANSWER_REGIONS_VIEWPORT_HEIGHT = 300.0;
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
	private final TextField answerTextField = new TextField();
	private final Button addAnswerRegionButton = new Button("Add");
	private final Button clearAnswerSelectionButton = new Button("Clear");
	private final Label answerRegionCountLabel = new Label("Regions: 0");
	private final Label answerRegionStatusLabel = new Label();
	private final VBox answerRegionListBox = new VBox(COMPACT_SPACING);
	private final ScrollPane answerRegionsScrollPane = new ScrollPane(answerRegionListBox);
	private final ImageView answerPreviewView = new ImageView();
	// Save and edit controls.
	private final Button saveAnswerButton = new Button("Save");
	private final Button cancelAnswerEditButton = new Button("Cancel");
	// Transient capture and edit state.
	private final List<AnswerRegion> pendingAnswerRegions = new ArrayList<>();
	private AnswerFile answerFile;
	private AnswerRegion currentAnswerSelection;
	private boolean restoringUnansweredQuestionSelection;
	private Question editingAnswerQuestion;
	private Runnable answerEditCompletedHandler = () -> {
	};

	/**
	 * Creates the answer-capture workflow and its persistence integration.
	 */
	AnswerCapturePane(Stage stage, QuestionRepository questionRepository, SqliteAnswerWriter answerWriter,
			PdfFilePicker pdfFilePicker, Consumer<SelectedPdf> answerPdfHandler, Runnable answerDocumentHandler,
			BooleanSupplier answerTransitionAllowed, Runnable selectionClearHandler,
			QuestionExtractor questionExtractor, Supplier<PdfSession> answerPdfSessionSupplier) {
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
				createAnswerTextControls());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
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
		currentAnswerSelection = new AnswerRegion(answerFile, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		Question question = unansweredQuestionField.getValue();
		selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
		answerRegionStatusLabel.setText("Selection pending — Page " + selection.pageNumber());
		setSelectionActionsEnabled(true);
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
	 * Reloads persisted questions that do not yet have an answer.
	 */
	void refreshQuestions() {
		Question selected = unansweredQuestionField.getValue();
		List<Question> unansweredQuestions = questionRepository.findAll().stream()
				.filter(question -> !question.hasAnswer()).toList();
		Question matching = null;
		if (selected != null) {
			for (Question question : unansweredQuestions) {
				if (question.getId() == selected.getId()) {
					matching = question;
					break;
				}
			}
		}
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
		clearPendingAnswerRegions();
		answerTextField.clear();
		if (question == null) {
			selectedAnswerQuestionLabel.setText("No question selected");
			answerTextField.setDisable(true);
			saveAnswerButton.setDisable(true);
			chooseAnswerPdfButton.setDisable(true);
			saveAnswerButton.setText("Save Answer");
			answerRegionCountLabel.setText("Regions: 0");
			answerRegionStatusLabel.setText("");
			return;
		}
		if (answerFile != null && answerFile.getExam().getId() != question.getExam().getId()) {
			answerFile = null;
			selectedAnswerPdfLabel.setText("No PDF selected");
		}
		boolean openedAnswerPdf = false;
		if (question.hasAnswer()) {
			Answer answer = question.getAnswer();
			if (answer.getAnswerText() != null) {
				answerTextField.setText(answer.getAnswerText());
			}
			pendingAnswerRegions.addAll(answer.getRegions());
			if (!answer.getRegions().isEmpty()) {
				AnswerFile storedAnswerFile = answer.getRegions().getFirst().answerFile();
				Path pdfPath = resolveRegisteredAnswerFile(storedAnswerFile);
				if (pdfPath != null) {
					openedAnswerPdf = openRegisteredAnswerFile(storedAnswerFile, pdfPath);
				}
			} else if (answerFile == null) {
				openedAnswerPdf = loadRegisteredAnswerFile(question);
			}
			refreshAnswerRegionList();
			showAcceptedRegionStatus();
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question) + " — answer stored");
			saveAnswerButton.setText("Update Answer");
		} else {
			if (answerFile == null) {
				openedAnswerPdf = loadRegisteredAnswerFile(question);
			}
			selectedAnswerQuestionLabel.setText(answerStatusPrefix(question));
			saveAnswerButton.setText("Save Answer");
			answerRegionCountLabel.setText("Regions: 0");
			answerRegionStatusLabel.setText("");
		}
		answerTextField.setDisable(false);
		saveAnswerButton.setDisable(false);
		chooseAnswerPdfButton.setDisable(false);
		if (answerFile != null && !openedAnswerPdf) {
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
	}

	private void clearPendingAnswerRegions() {
		pendingAnswerRegions.clear();
		currentAnswerSelection = null;
		answerPreviewView.setImage(null);
		answerRegionListBox.getChildren().clear();
		answerRegionsScrollPane.setVisible(false);
		answerRegionsScrollPane.setManaged(false);
		answerRegionCountLabel.setText("Regions: 0");
		answerRegionStatusLabel.setText("");
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);
	}

	private void configureActions(Stage stage) {
		addAnswerRegionButton.setOnAction(event -> addCurrentAnswerRegion());
		chooseAnswerPdfButton.setOnAction(event -> chooseAnswerPdf(stage));
		clearAnswerSelectionButton.setOnAction(event -> clearCurrentAnswerSelection());
		saveAnswerButton.setOnAction(event -> validateAnswerForSave());
		cancelAnswerEditButton.setOnAction(event -> cancelAnswerEdit());
	}

	private void configureControls() {
		unansweredQuestionField.setId("unanswered-question");
		unansweredQuestionField.setPromptText("Select unanswered question");
		unansweredQuestionField.setMaxWidth(Double.MAX_VALUE);
		unansweredQuestionField.setConverter(QUESTION_CODE_CONVERTER);
		unansweredQuestionField.valueProperty().addListener(
				(observable, oldQuestion, newQuestion) -> handleUnansweredQuestionChanged(oldQuestion, newQuestion));
		answerTextField.setId("answer-text");
		answerTextField.setPromptText("Answer text, e.g. B");
		answerTextField.setDisable(true);
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
		selectedAnswerQuestionLabel.setId("selected-answer-question");
		cancelAnswerEditButton.setId("cancel-answer-edit");
		cancelAnswerEditButton.setVisible(false);
		cancelAnswerEditButton.setManaged(false);
		answerRegionsScrollPane.setFitToWidth(true);
		answerRegionListBox.setFillWidth(true);
		answerRegionListBox.setMaxWidth(Double.MAX_VALUE);
		answerRegionsScrollPane.setMaxWidth(Double.MAX_VALUE);
		answerRegionsScrollPane.setPrefViewportHeight(ANSWER_REGIONS_VIEWPORT_HEIGHT);
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
			previewView.fitWidthProperty()
					.bind(Bindings.createDoubleBinding(
							() -> Math.max(0.0, answerRegionsScrollPane.getViewportBounds().getWidth() - 8.0),
							answerRegionsScrollPane.viewportBoundsProperty()));
			return previewView;
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview accepted answer region", e);
		}
	}

	private HBox createAnswerPdfControls() {
		HBox controls = new HBox(CONTROL_SPACING, chooseAnswerPdfButton, selectedAnswerPdfLabel);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createAnswerRegionControls() {
		HBox controls = new HBox(CONTROL_SPACING, addAnswerRegionButton, clearAnswerSelectionButton,
				answerRegionCountLabel, answerRegionStatusLabel);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createAnswerTextControls() {
		HBox controls = new HBox(CONTROL_SPACING, answerTextField, saveAnswerButton, cancelAnswerEditButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private String findValidationError(Question question, String answerText) {
		return AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(question != null,
				currentAnswerSelection != null, answerText, pendingAnswerRegions.size()));
	}

	private void finishAnswerEdit() {
		Runnable completedHandler = answerEditCompletedHandler;
		answerEditCompletedHandler = () -> {
		};
		editingAnswerQuestion = null;
		clearPendingAnswerRegions();
		answerTextField.clear();
		answerFile = null;
		selectedAnswerPdfLabel.setText("No PDF selected");
		unansweredQuestionField.setDisable(false);
		cancelAnswerEditButton.setVisible(false);
		cancelAnswerEditButton.setManaged(false);
		saveAnswerButton.setText("Save Answer");
		refreshQuestions();
		applyUnansweredQuestionChange(unansweredQuestionField.getValue());
		completedHandler.run();
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

	private boolean loadRegisteredAnswerFile(Question question) {
		List<AnswerFile> answerFiles;
		try {
			answerFiles = answerWriter.findAnswerFiles(question.getExam());
		} catch (SQLException e) {
			showAnswerFileError("Could not read the registered answer files.", e.getMessage());
			return false;
		}
		AnswerFile registeredAnswerFile = selectRegisteredAnswerFile(answerFiles);
		if (registeredAnswerFile == null) {
			return false;
		}
		Path pdfPath = resolveRegisteredAnswerFile(registeredAnswerFile);
		if (pdfPath == null) {
			return false;
		}
		return openRegisteredAnswerFile(registeredAnswerFile, pdfPath);
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
		return true;
	}

	private void refreshAnswerRegionList() {
		answerRegionListBox.getChildren().clear();
		for (int i = 0; i < pendingAnswerRegions.size(); i++) {
			AnswerRegion region = pendingAnswerRegions.get(i);
			int regionIndex = i;
			Button removeButton = new Button("Remove");
			removeButton.setOnAction(event -> removeAnswerRegion(regionIndex));
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
		boolean hasRegions = !pendingAnswerRegions.isEmpty();
		answerRegionsScrollPane.setVisible(hasRegions);
		answerRegionsScrollPane.setManaged(hasRegions);
	}

	private void removeAnswerRegion(int regionIndex) {
		pendingAnswerRegions.remove(regionIndex);
		refreshAnswerRegionList();
		showAcceptedRegionStatus();
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
		int previousIndex = unansweredQuestionField.getSelectionModel().getSelectedIndex();
		String storedText = answerText.isBlank() ? null : answerText;
		try {
			Answer answer;
			if (question.hasAnswer()) {
				answer = answerWriter.updateAnswer(question, question.getAnswer().getId(), storedText,
						pendingAnswerRegions);
			} else {
				answer = answerWriter.insertAnswer(question, storedText, pendingAnswerRegions);
			}
			question.setAnswer(answer);
			if (editingAnswerQuestion != null) {
				finishAnswerEdit();
				return;
			}
			clearPendingAnswerRegions();
			answerTextField.clear();
			refreshQuestions();
			selectNextUnansweredQuestion(previousIndex);
		} catch (SQLException e) {
			throw new IllegalStateException("Unable to save answer", e);
		}
	}

	private void selectNextUnansweredQuestion(int previousIndex) {
		Question nextQuestion = null;
		if (!unansweredQuestionField.getItems().isEmpty()) {
			int nextIndex = previousIndex < 0 ? 0
					: Math.min(previousIndex, unansweredQuestionField.getItems().size() - 1);
			nextQuestion = unansweredQuestionField.getItems().get(nextIndex);
		}
		restoringUnansweredQuestionSelection = true;
		try {
			unansweredQuestionField.setValue(nextQuestion);
		} finally {
			restoringUnansweredQuestionSelection = false;
		}
		applyUnansweredQuestionChange(nextQuestion);
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

	private void setSelectionActionsEnabled(boolean enabled) {
		addAnswerRegionButton.setDisable(!enabled);
		clearAnswerSelectionButton.setDisable(!enabled);
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

	private void validateAnswerForSave() {
		Question question = unansweredQuestionField.getValue();
		String answerText = answerTextField.getText().trim();
		String validationError = findValidationError(question, answerText);
		if (validationError != null) {
			showError(validationError);
			return;
		}
		saveAnswer(question, answerText);
	}
}
