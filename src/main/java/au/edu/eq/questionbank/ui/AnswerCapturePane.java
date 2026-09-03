package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
 * answers, validation, and the save-answer workflow.
 */
final class AnswerCapturePane extends VBox {

	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double ANSWER_REGIONS_VIEWPORT_HEIGHT = 70.0;
	private static final double ANSWER_PREVIEW_WIDTH = 290.0;
	private static final double ACCEPTED_ANSWER_PREVIEW_WIDTH = 180.0;
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
			return String.format("%s %d — %s — %s", question.getExam().getProvider().getName(),
					question.getExam().getYear(), question.getBooklet().getName(), question.getQuestionCode());
		}
	};

	private final Button addAnswerRegionButton = new Button("Add");
	private final Button chooseAnswerPdfButton = new Button("PDF...");
	private final Button clearAnswerSelectionButton = new Button("Clear");
	private final Button saveAnswerButton = new Button("Save");
	private final ComboBox<Question> unansweredQuestionField = new ComboBox<>();
	private final Label answerRegionCountLabel = new Label("Regions: 0");
	private final Label selectedAnswerPdfLabel = new Label("No PDF selected");
	private final Label selectedAnswerQuestionLabel = new Label("No question selected");
	private final TextField answerTextField = new TextField();
	private final VBox answerRegionListBox = new VBox(COMPACT_SPACING);
	private final ScrollPane answerRegionsScrollPane = new ScrollPane(answerRegionListBox);
	private final ImageView answerPreviewView = new ImageView();

	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final Supplier<PdfSession> answerPdfSessionSupplier;
	private final PdfFilePicker pdfFilePicker;
	private final Consumer<SelectedPdf> answerPdfHandler;
	private final Runnable selectionClearHandler;
	private final Runnable answerDocumentHandler;
	private final List<AnswerRegion> pendingAnswerRegions = new ArrayList<>();
	private final SqliteAnswerWriter answerWriter;

	private AnswerFile answerFile;
	private AnswerRegion currentAnswerSelection;

	/**
	 * Creates the answer-capture workflow and its persistence integration.
	 */
	AnswerCapturePane(Stage stage, QuestionRepository questionRepository, SqliteAnswerWriter answerWriter,
			PdfFilePicker pdfFilePicker, Consumer<SelectedPdf> answerPdfHandler, Runnable answerDocumentHandler,
			Runnable selectionClearHandler, QuestionExtractor questionExtractor,
			Supplier<PdfSession> answerPdfSessionSupplier) {
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

		this.questionRepository = questionRepository;
		this.pdfFilePicker = pdfFilePicker;
		this.answerPdfHandler = answerPdfHandler;
		this.selectionClearHandler = selectionClearHandler;
		this.questionExtractor = questionExtractor;
		this.answerPdfSessionSupplier = answerPdfSessionSupplier;
		this.answerWriter = answerWriter;
		this.answerDocumentHandler = answerDocumentHandler;

		configureControls();
		configureActions(stage);
		getChildren().addAll(createSectionLabel("Answer"), unansweredQuestionField, createAnswerPdfControls(),
				createAnswerRegionControls(), answerPreviewView, answerRegionsScrollPane, createAnswerTextControls());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	private void addCurrentAnswerRegion() {
		if (currentAnswerSelection == null) {
			return;
		}

		pendingAnswerRegions.add(currentAnswerSelection);
		refreshAnswerRegionList();
		currentAnswerSelection = null;
		answerPreviewView.setImage(null);
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

		Path sourcePath = pdfFilePicker.chooseAnyPdf(stage, "Choose answer PDF");
		if (sourcePath == null) {
			return;
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
		answerPreviewView.setImage(null);

		Question question = unansweredQuestionField.getValue();
		if (question != null) {
			selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode() + " — "
					+ pendingAnswerRegions.size() + " region(s) accepted");
		}
	}

	private void clearPendingAnswerRegions() {
		pendingAnswerRegions.clear();
		answerPreviewView.setImage(null);
		refreshAnswerRegionList();
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
		answerRegionCountLabel.setId("answer-region-count");
		selectedAnswerQuestionLabel.setId("selected-answer-question");
		answerRegionsScrollPane.setFitToWidth(true);
		answerRegionsScrollPane.setPrefViewportHeight(ANSWER_REGIONS_VIEWPORT_HEIGHT);
		answerRegionsScrollPane.setVisible(false);
		answerRegionsScrollPane.setManaged(false);
		answerPreviewView.setPreserveRatio(true);
		answerPreviewView.setFitWidth(ANSWER_PREVIEW_WIDTH);
		answerPreviewView.setSmooth(true);
		setSelectionActionsEnabled(false);
	}

	private ImageView createAcceptedAnswerPreview(AnswerRegion region) {

		try {
			BufferedImage preview = questionExtractor.extractRegion(answerPdfSessionSupplier.get(), region);

			ImageView previewView = new ImageView(SwingFXUtils.toFXImage(preview, null));

			previewView.setPreserveRatio(true);
			previewView.setFitWidth(ACCEPTED_ANSWER_PREVIEW_WIDTH);
			previewView.setSmooth(true);

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
				answerRegionCountLabel);
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

		if (answerFile != null && answerFile.getExam().getId() != question.getExam().getId()) {
			answerFile = null;
			selectedAnswerPdfLabel.setText("No PDF selected");
		}

		boolean openedRegisteredAnswerPdf = false;

		if (answerFile == null) {
			openedRegisteredAnswerPdf = loadRegisteredAnswerFile(question);
		}

		selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode());
		answerTextField.setDisable(false);
		saveAnswerButton.setDisable(false);
		chooseAnswerPdfButton.setDisable(false);

		if (answerFile != null && !openedRegisteredAnswerPdf) {
			answerDocumentHandler.run();
		}
	}

	private boolean loadRegisteredAnswerFile(Question question) {
		List<AnswerFile> answerFiles;

		try {
			answerFiles = answerWriter.findAnswerFiles(question.getExam());
		} catch (SQLException e) {
			showAnswerFileError("Could not read the registered answer files.", e.getMessage());
			return false;
		}

		if (answerFiles.isEmpty()) {
			selectedAnswerPdfLabel.setText("No PDF selected");
			return false;
		}

		if (answerFiles.size() > 1) {
			selectedAnswerPdfLabel.setText("Multiple answer PDFs registered — choose PDF...");
			return false;
		}

		AnswerFile registeredAnswerFile = answerFiles.get(0);
		PdfStore pdfStore = new PdfStore(pdfFilePicker.dataRoot());
		Path pdfPath;

		try {
			pdfPath = pdfStore.resolve(registeredAnswerFile.getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException e) {
			showAnswerFileError("The registered answer PDF path is invalid.", e.getMessage());
			return false;
		}

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
			ImageView previewView = createAcceptedAnswerPreview(region);
			Label label = new Label(String.format("Region %d - Page %d", i + 1, region.pageNumber()));
			Button removeButton = new Button("Remove");
			removeButton.setOnAction(event -> removeAnswerRegion(regionIndex));
			VBox details = new VBox(COMPACT_SPACING, label, removeButton);
			HBox row = new HBox(CONTROL_SPACING, previewView, details);
			row.setAlignment(Pos.CENTER_LEFT);
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

	private void saveAnswer(Question question, String answerText) {
		int previousIndex = unansweredQuestionField.getSelectionModel().getSelectedIndex();
		String storedText = answerText.isBlank() ? null : answerText;

		try {
			Answer answer = answerWriter.insertAnswer(question, storedText, pendingAnswerRegions);
			question.setAnswer(answer);

			refreshUnansweredQuestions();

			if (previousIndex >= 0 && !unansweredQuestionField.getItems().isEmpty()) {
				int nextIndex = Math.min(previousIndex, unansweredQuestionField.getItems().size() - 1);
				unansweredQuestionField.getSelectionModel().select(nextIndex);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Unable to save answer", e);
		}
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

	private void showAnswerPreview(AnswerRegion region) {
		try {
			BufferedImage preview = questionExtractor.extractRegion(answerPdfSessionSupplier.get(), region);

			answerPreviewView.setImage(SwingFXUtils.toFXImage(preview, null));

		} catch (IOException e) {
			throw new RuntimeException("Unable to preview answer region", e);
		}
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

	/**
	 * Accepts a proportional answer-page selection as the current pending region.
	 *
	 * @param selection the selected answer-page rectangle
	 */
	void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		currentAnswerSelection = new AnswerRegion(answerFile, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		showAnswerPreview(currentAnswerSelection);
		Question question = unansweredQuestionField.getValue();
		selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode() + " — selection pending");
		setSelectionActionsEnabled(true);
	}

	/**
	 * Discards an unaccepted region when the displayed page changes.
	 */
	void clearCurrentSelectionForPageChange() {
		currentAnswerSelection = null;
		answerPreviewView.setImage(null);
		setSelectionActionsEnabled(false);
	}

	boolean hasAnswerFile() {
		return answerFile != null;
	}

	/**
	 * Reloads persisted questions that do not yet have an answer.
	 */
	void refreshUnansweredQuestions() {
		List<Question> storedQuestions = questionRepository.findAll();
		List<Question> unansweredQuestions = new ArrayList<>();
		for (Question question : storedQuestions) {
			if (!question.hasAnswer()) {
				unansweredQuestions.add(question);
			}
		}
		unansweredQuestionField.getItems().setAll(unansweredQuestions);
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
}
