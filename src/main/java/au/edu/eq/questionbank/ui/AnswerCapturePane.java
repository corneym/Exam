package au.edu.eq.questionbank.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.repository.QuestionRepository;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Owns unanswered-question selection, answer source and region state, textual
 * answers, validation, and the save-answer workflow.
 */
final class AnswerCapturePane extends VBox {

	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
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
			return question == null ? "" : question.getQuestionCode();
		}
	};

	private final Button addAnswerRegionButton = new Button("Add");
	private final Button chooseAnswerPdfButton = new Button("PDF...");
	private final Button clearAnswerSelectionButton = new Button("Clear");
	private final Button saveAnswerButton = new Button("Save");
	private final Button undoAnswerRegionButton = new Button("Undo");
	private final ComboBox<Question> unansweredQuestionField = new ComboBox<>();
	private final Label answerRegionCountLabel = new Label("Regions: 0");
	private final Label selectedAnswerPdfLabel = new Label("No PDF selected");
	private final Label selectedAnswerQuestionLabel = new Label("No question selected");
	private final TextField answerTextField = new TextField();

	private final QuestionRepository questionRepository;
	private final PdfFilePicker pdfFilePicker;
	private final Consumer<SelectedPdf> answerPdfHandler;
	private final Runnable selectionClearHandler;
	private final List<AnswerRegion> pendingAnswerRegions = new ArrayList<>();

	private AnswerFile answerFile;
	private AnswerRegion currentAnswerSelection;
	private long nextAnswerId = 1;

	AnswerCapturePane(Stage stage, QuestionRepository questionRepository, PdfFilePicker pdfFilePicker,
			Consumer<SelectedPdf> answerPdfHandler, Runnable selectionClearHandler) {
		this.questionRepository = Objects.requireNonNull(questionRepository, "questionRepository");
		this.pdfFilePicker = Objects.requireNonNull(pdfFilePicker, "pdfFilePicker");
		this.answerPdfHandler = Objects.requireNonNull(answerPdfHandler, "answerPdfHandler");
		this.selectionClearHandler = Objects.requireNonNull(selectionClearHandler, "selectionClearHandler");

		configureControls();
		configureActions(stage);
		getChildren().addAll(createSectionLabel("Answer"), unansweredQuestionField, createAnswerPdfControls(),
				createAnswerRegionControls(), createAnswerTextControls());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	private void addCurrentAnswerRegion() {
		if (currentAnswerSelection == null) {
			return;
		}

		pendingAnswerRegions.add(currentAnswerSelection);
		undoAnswerRegionButton.setDisable(false);
		currentAnswerSelection = null;
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);
		showAcceptedRegionStatus();
	}

	private void chooseAnswerPdf(Stage stage) {
		Question question = unansweredQuestionField.getValue();
		if (question == null) {
			showError("Select a question first.");
			return;
		}

		SelectedPdf selectedPdf = pdfFilePicker.choose(stage, "Choose answer PDF",
				"Answer PDF must be inside the configured PDF data folder.");
		if (selectedPdf == null) {
			return;
		}

		selectAnswerPdf(question, selectedPdf);
	}

	private void clearCurrentAnswerSelection() {
		currentAnswerSelection = null;
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);

		Question question = unansweredQuestionField.getValue();
		if (question != null) {
			selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode() + " — "
					+ pendingAnswerRegions.size() + " region(s) accepted");
		}
	}

	private void clearPendingAnswerRegions() {
		pendingAnswerRegions.clear();
		currentAnswerSelection = null;
		selectionClearHandler.run();
		setSelectionActionsEnabled(false);
		answerRegionCountLabel.setText("Regions: 0");
	}

	private void configureActions(Stage stage) {
		addAnswerRegionButton.setOnAction(event -> addCurrentAnswerRegion());
		chooseAnswerPdfButton.setOnAction(event -> chooseAnswerPdf(stage));
		clearAnswerSelectionButton.setOnAction(event -> clearCurrentAnswerSelection());
		saveAnswerButton.setOnAction(event -> validateAnswerForSave());
		undoAnswerRegionButton.setOnAction(event -> undoLastAnswerRegion());
	}

	private void configureControls() {
		unansweredQuestionField.setId("unanswered-question");
		unansweredQuestionField.setPromptText("Select unanswered question");
		unansweredQuestionField.setMaxWidth(Double.MAX_VALUE);
		unansweredQuestionField.setConverter(QUESTION_CODE_CONVERTER);
		unansweredQuestionField.valueProperty()
				.addListener((observable, oldQuestion, newQuestion) -> handleUnansweredQuestionChanged(newQuestion));

		answerTextField.setId("answer-text");
		answerTextField.setPromptText("Answer text, e.g. B");
		answerTextField.setDisable(true);
		saveAnswerButton.setId("save-answer");
		saveAnswerButton.setDisable(true);
		chooseAnswerPdfButton.setDisable(true);
		chooseAnswerPdfButton.setId("choose-answer-pdf");
		addAnswerRegionButton.setId("add-answer-region");
		clearAnswerSelectionButton.setId("clear-answer-selection");
		undoAnswerRegionButton.setId("undo-answer-region");
		undoAnswerRegionButton.setDisable(true);
		answerRegionCountLabel.setId("answer-region-count");
		selectedAnswerQuestionLabel.setId("selected-answer-question");
		setSelectionActionsEnabled(false);
	}

	private HBox createAnswerPdfControls() {
		HBox controls = new HBox(CONTROL_SPACING, chooseAnswerPdfButton, selectedAnswerPdfLabel);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createAnswerRegionControls() {
		HBox controls = new HBox(CONTROL_SPACING, addAnswerRegionButton, clearAnswerSelectionButton,
				answerRegionCountLabel, undoAnswerRegionButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createAnswerTextControls() {
		HBox controls = new HBox(CONTROL_SPACING, answerTextField, saveAnswerButton);
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

	private void handleUnansweredQuestionChanged(Question question) {
		clearPendingAnswerRegions();
		answerTextField.clear();

		if (question == null) {
			selectedAnswerQuestionLabel.setText("No question selected");
			answerTextField.setDisable(true);
			saveAnswerButton.setDisable(true);
			chooseAnswerPdfButton.setDisable(true);
			return;
		}

		selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode());
		answerTextField.setDisable(false);
		saveAnswerButton.setDisable(false);
		chooseAnswerPdfButton.setDisable(false);
	}

	private void saveAnswer(Question question, String answerText) {
		String storedText = answerText.isBlank() ? null : answerText;
		question.setAnswer(new Answer(nextAnswerId++, storedText, pendingAnswerRegions));
		unansweredQuestionField.getSelectionModel().clearSelection();
		refreshUnansweredQuestions();
	}

	private void setSelectionActionsEnabled(boolean enabled) {
		addAnswerRegionButton.setDisable(!enabled);
		clearAnswerSelectionButton.setDisable(!enabled);
	}

	private void showAcceptedRegionStatus() {
		answerRegionCountLabel.setText("Regions: " + pendingAnswerRegions.size());
		Question question = unansweredQuestionField.getValue();
		selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode() + " — "
				+ pendingAnswerRegions.size() + " region(s) accepted");
	}

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText("Answer is incomplete.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void undoLastAnswerRegion() {
		if (pendingAnswerRegions.isEmpty()) {
			return;
		}
		pendingAnswerRegions.removeLast();
		answerRegionCountLabel.setText("Regions: " + pendingAnswerRegions.size());
		undoAnswerRegionButton.setDisable(pendingAnswerRegions.isEmpty());
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

	void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		currentAnswerSelection = new AnswerRegion(answerFile, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		Question question = unansweredQuestionField.getValue();
		selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode() + " — selection pending");
		setSelectionActionsEnabled(true);
	}

	void clearCurrentSelectionForPageChange() {
		currentAnswerSelection = null;
		setSelectionActionsEnabled(false);
	}

	boolean hasAnswerFile() {
		return answerFile != null;
	}

	void refreshUnansweredQuestions() {
		List<Question> unansweredQuestions = questionRepository.findAll().stream()
				.filter(question -> !question.hasAnswer()).toList();
		unansweredQuestionField.getItems().setAll(unansweredQuestions);
	}

	void selectAnswerPdf(Question question, SelectedPdf selectedPdf) {
		Objects.requireNonNull(question, "question");
		Objects.requireNonNull(selectedPdf, "selectedPdf");
		SourceDocument sourceDocument = new SourceDocument(1, selectedPdf.relativePath());
		answerFile = new AnswerFile(1, question.getExam(), selectedPdf.file().getName(), sourceDocument);
		answerPdfHandler.accept(selectedPdf);
		selectedAnswerPdfLabel.setText(selectedPdf.file().getName());
	}
}
