package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SourceQuestionRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Owns the question-capture controls, pending regions, previews, validation,
 * and save workflow.
 */
final class QuestionCapturePane extends VBox {

	private static final double COMPACT_SPACING = 4.0;
	private static final double REGION_PREVIEW_ITEM_SPACING = 5.0;
	private static final double CURRENT_SELECTION_SPACING = 6.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double SECTION_SPACING = 10.0;
	private static final double QUESTION_CODE_FIELD_WIDTH = 100.0;
	private static final double REGION_PREVIEW_WIDTH = 290.0;
	private static final double PREVIEW_IMAGE_WIDTH = 290.0;
	private static final double REGIONS_VIEWPORT_HEIGHT = 300.0;
	private static final double MARKS_FIELD_WIDTH = 60.0;
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final Insets COMPACT_BUTTON_PADDING = new Insets(2, 8, 2, 8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String SECTION_HEADING_STYLE = "-fx-font-weight: bold;";
	private static final String SUCCESS_STATUS_STYLE = "-fx-text-fill: #2e7d32;";
	private final Button addRegionButton = new Button("Add");
	private final Button clearRegionsButton = new Button("Clear Regions");
	private final Button removeCurrentSelectionButton = new Button("Clear");
	private final Button saveQuestionButton = new Button("Save Question");
	private final ImageView previewView = new ImageView();
	private final Label regionCountLabel = new Label("Regions: 0");
	private final Label saveStatusLabel = new Label();
	private final TextField questionCodeField = new TextField();
	private final TextField marksField = new TextField();
	private final VBox regionPreviewBox = new VBox(SECTION_SPACING);
	private final ScrollPane regionsScrollPane = new ScrollPane(regionPreviewBox);
	private final ComboBox<Question> importedQuestionBox = new ComboBox<>();
	private final Button newQuestionButton = new Button("New");
	private final Label captureHintLabel = new Label();
	private Question importedQuestion;
	private boolean refreshingImportedQuestions;
	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final CurriculumSelectorPane curriculumSelectorPane;
	private final Supplier<ExamBooklet> bookletSupplier;
	private final Supplier<PdfSession> examPdfSessionSupplier;
	private final Runnable selectionClearHandler;
	private final Runnable questionsChangedHandler;
	private final Predicate<Question> importedQuestionActivationHandler;
	private final BooleanSupplier questionTargetChangeAllowed;
	private final SharedContextCapturePane sharedContextCapturePane;

	private QuestionRegion currentSelection;
	private final List<QuestionRegion> pendingRegions = new ArrayList<>();
	private final ComboBox<String> sourceQuestionField = new ComboBox<>();
	private final Button createSourceQuestionButton = new Button("Create");
	private final SourceQuestionRepository sourceQuestionRepository;

	/**
	 * Creates the question-capture workflow and its repository integration.
	 */
	QuestionCapturePane(QuestionRepository questionRepository, SourceQuestionRepository sourceQuestionRepository,
			SharedContextCapturePane sharedContextCapturePane, QuestionExtractor questionExtractor,
			CurriculumSelectionModel curriculumSelectionModel, CurriculumSelectorPane curriculumSelectorPane,
			Supplier<ExamBooklet> bookletSupplier, Supplier<PdfSession> examPdfSessionSupplier,
			Predicate<Question> importedQuestionActivationHandler, BooleanSupplier questionTargetChangeAllowed,
			Runnable selectionClearHandler, Runnable questionsChangedHandler) {
		if (questionRepository == null) {
			throw new NullPointerException("questionRepository");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		if (curriculumSelectionModel == null) {
			throw new NullPointerException("curriculumSelectionModel");
		}
		if (curriculumSelectorPane == null) {
			throw new NullPointerException("curriculumSelectorPane");
		}
		if (bookletSupplier == null) {
			throw new NullPointerException("bookletSupplier");
		}
		if (examPdfSessionSupplier == null) {
			throw new NullPointerException("examPdfSessionSupplier");
		}
		if (selectionClearHandler == null) {
			throw new NullPointerException("selectionClearHandler");
		}
		if (questionsChangedHandler == null) {
			throw new NullPointerException("questionsChangedHandler");
		}
		if (importedQuestionActivationHandler == null) {
			throw new NullPointerException("importedQuestionActivationHandler");
		}
		if (questionTargetChangeAllowed == null) {
			throw new NullPointerException("questionTargetChangeAllowed");
		}
		if (sourceQuestionRepository == null) {
			throw new NullPointerException("sourceQuestionRepository");
		}
		if (sharedContextCapturePane == null) {
			throw new NullPointerException("sharedContextCapturePane");
		}
		this.questionRepository = questionRepository;
		this.questionExtractor = questionExtractor;
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.curriculumSelectorPane = curriculumSelectorPane;
		this.bookletSupplier = bookletSupplier;
		this.examPdfSessionSupplier = examPdfSessionSupplier;
		this.selectionClearHandler = selectionClearHandler;
		this.questionsChangedHandler = questionsChangedHandler;
		this.importedQuestionActivationHandler = importedQuestionActivationHandler;
		this.questionTargetChangeAllowed = questionTargetChangeAllowed;
		this.sourceQuestionRepository = sourceQuestionRepository;
		this.sharedContextCapturePane = sharedContextCapturePane;
		configureControls();
		configureActions();
		getChildren().addAll(createSectionLabel("Question"), new Label("Imported question awaiting capture"),
				createImportedQuestionControls(), captureHintLabel, createSourceQuestionControls(),
				sharedContextCapturePane, createQuestionControls(), saveStatusLabel, new Separator(),
				createCurrentSelectionControls(), previewView, new Separator(), new Label("Accepted regions"),
				regionCountLabel, createRegionsScrollPane());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	/**
	 * Accepts a proportional exam-page selection as the current pending region.
	 *
	 * @param selection the selected exam-page rectangle
	 */
	void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			clearCurrentSelection();
			showAlert(Alert.AlertType.WARNING, "Exam details have not been set.",
					"Enter the exam and booklet details, then click Set Exam.");
			return;
		}
		currentSelection = new QuestionRegion(booklet, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		showRegionPreview(currentSelection);
		saveStatusLabel.setText("Selection pending — click Add or Clear");
	}

	void acceptSharedContextSelection(PdfWorkspacePane.RegionSelection selection) {

		sharedContextCapturePane.acceptSelection(selection);
	}

	private void addCurrentRegion() {
		if (currentSelection == null) {
			return;
		}
		pendingRegions.add(currentSelection);
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
	}

	private void addRegionPreview(QuestionRegion region, int regionIndex) {
		try {
			BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
			ImageView imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
			imageView.setPreserveRatio(true);
			imageView.setFitWidth(REGION_PREVIEW_WIDTH);
			imageView.setSmooth(true);
			Label label = new Label(String.format("Region %d - Page %d", regionIndex + 1, region.pageNumber()));
			Button removeButton = new Button("Remove");
			removeButton.setOnAction(event -> removeRegion(regionIndex));
			HBox header = new HBox(SECTION_SPACING, label, removeButton);
			regionPreviewBox.getChildren().add(new VBox(REGION_PREVIEW_ITEM_SPACING, header, imageView));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview region", e);
		}
	}

	/**
	 * Discards the current unaccepted region selection.
	 */
	void clearCurrentSelection() {
		currentSelection = null;
		selectionClearHandler.run();
		previewView.setImage(null);
	}

	/**
	 * Clears all transient question regions when the exam PDF changes.
	 */
	void clearForNewPdf() {
		importedQuestion = null;
		questionCodeField.setDisable(false);
		marksField.setDisable(false);
		curriculumSelectorPane.setDisable(false);
		saveQuestionButton.setText("Save Question");
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
		resetQuestionEntry();
		refreshSourceQuestionOptions();
		sharedContextCapturePane.refreshForCurrentBooklet();
		refreshImportedQuestions();
	}

	private void clearImportedQuestionSelection() {
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(null);
		} finally {
			refreshingImportedQuestions = false;
		}
		importedQuestion = null;
		questionCodeField.setDisable(false);
		marksField.setDisable(false);
		curriculumSelectorPane.setDisable(false);
		saveQuestionButton.setText("Save Question");
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
		resetQuestionEntry();
	}

	private void clearPendingSelection() {
		clearCurrentSelection();
		showQuestionPendingStatus();
	}

	private void clearQuestionRegions() {
		clearRegions();
		showQuestionPendingStatus();
	}

	private void clearRegions() {
		pendingRegions.clear();
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(0);
	}

	void clearSharedContextCurrentSelection() {
		sharedContextCapturePane.clearCurrentSelection();
	}

	private void clearSourceQuestionSelection() {
		sourceQuestionField.getSelectionModel().clearSelection();
		sourceQuestionField.getEditor().clear();
	}

	private void configureActions() {
		addRegionButton.setOnAction(event -> addCurrentRegion());
		clearRegionsButton.setOnAction(event -> clearQuestionRegions());
		createSourceQuestionButton.setOnAction(event -> createSourceQuestion());
		removeCurrentSelectionButton.setOnAction(event -> clearPendingSelection());
		saveQuestionButton.setOnAction(event -> validateQuestionForSave());
		importedQuestionBox.setOnAction(event -> {
			if (!refreshingImportedQuestions) {
				loadImportedQuestion(importedQuestionBox.getValue());
			}
		});
		newQuestionButton.setOnAction(event -> startNewQuestion());
	}

	private void configureControls() {
		questionCodeField.setId("question-code");
		questionCodeField.setPromptText("Q1");
		questionCodeField.setPrefWidth(QUESTION_CODE_FIELD_WIDTH);
		sourceQuestionField.setId("source-question");
		sourceQuestionField.setPromptText("Optional, e.g. 21");
		sourceQuestionField.setEditable(true);
		sourceQuestionField.setPrefWidth(160.0);
		createSourceQuestionButton.setId("create-source-question");
		marksField.setId("question-marks");
		marksField.setPromptText("1");
		marksField.setPrefWidth(MARKS_FIELD_WIDTH);
		saveQuestionButton.setId("save-question");
		saveStatusLabel.setId("question-save-status");
		regionCountLabel.setId("question-region-count");
		addRegionButton.setId("add-question-region");
		removeCurrentSelectionButton.setId("clear-question-selection");
		addRegionButton.setPadding(COMPACT_BUTTON_PADDING);
		removeCurrentSelectionButton.setPadding(COMPACT_BUTTON_PADDING);
		configurePreviewImageView(previewView);
		saveStatusLabel.setStyle(SUCCESS_STATUS_STYLE);
		importedQuestionBox.setId("imported-question");
		importedQuestionBox.setPromptText("Select imported question");
		importedQuestionBox.setMaxWidth(Double.MAX_VALUE);
		importedQuestionBox.setConverter(new StringConverter<Question>() {

			@Override
			public Question fromString(String text) {
				return null;
			}

			@Override
			public String toString(Question question) {
				if (question == null) {
					return "";
				}
				ExamBooklet booklet = question.getBooklet();
				Exam exam = booklet.getExam();
				return String.format("%s %d — %s — %s — %d mark(s)", exam.getProvider().getName(), exam.getYear(),
						booklet.getName(), question.getQuestionCode(), question.getMarks());
			}
		});
		newQuestionButton.setId("new-question");
		captureHintLabel.setId("question-capture-hint");
		captureHintLabel.setWrapText(true);
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
	}

	private void configurePreviewImageView(ImageView imageView) {
		imageView.setPreserveRatio(true);
		imageView.setFitWidth(PREVIEW_IMAGE_WIDTH);
		imageView.setSmooth(true);
	}

	private HBox createCurrentSelectionControls() {
		return new HBox(CURRENT_SELECTION_SPACING, new Label("Current selection"), addRegionButton,
				removeCurrentSelectionButton);
	}

	private HBox createImportedQuestionControls() {
		HBox controls = new HBox(CONTROL_SPACING, importedQuestionBox, newQuestionButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createQuestionControls() {
		HBox controls = new HBox(CONTROL_SPACING, questionCodeField, new Label("Marks"), marksField,
				saveQuestionButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private ScrollPane createRegionsScrollPane() {
		regionsScrollPane.setFitToWidth(true);
		regionsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		regionsScrollPane.setMinHeight(0);
		regionsScrollPane.setMaxHeight(REGIONS_VIEWPORT_HEIGHT);
		regionsScrollPane.setVisible(false);
		regionsScrollPane.setManaged(false);
		return regionsScrollPane;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private void createSourceQuestion() {
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			showAlert(Alert.AlertType.WARNING, "Exam details have not been set.",
					"Select an exam booklet before creating a source question.");
			return;
		}
		String code = sourceQuestionCode();
		if (code.isBlank()) {
			showAlert(Alert.AlertType.WARNING, "Source question is incomplete.", "Enter a source question code.");
			return;
		}
		SourceQuestion sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, code)
				.orElseGet(() -> sourceQuestionRepository.save(booklet, code));
		refreshSourceQuestionOptions();
		sourceQuestionField.setValue(sourceQuestion.getSourceQuestionCode());
	}

	private HBox createSourceQuestionControls() {
		Label label = new Label("Source question");
		HBox controls = new HBox(CONTROL_SPACING, label, sourceQuestionField, createSourceQuestionButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private Question findExistingQuestionWithSameCode() {
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			return null;
		}
		String questionCode = questionCodeField.getText().trim();
		for (Question question : questionRepository.findAll()) {
			if (question.getBooklet().getId() == booklet.getId() && question.getQuestionCode().equals(questionCode)) {
				return question;
			}
		}
		return null;
	}

	private String findValidationError() {
		String validationError = QuestionCaptureValidator.findError(new QuestionCaptureValidator.State(
				bookletSupplier.get() != null, questionCodeField.getText().trim(), marksField.getText().trim(),
				curriculumSelectionModel.getSubject() != null, curriculumSelectionModel.getUnit() != null,
				curriculumSelectionModel.getTopic() != null, curriculumSelectionModel.getClassification() != null,
				currentSelection != null, pendingRegions.size()));
		if (validationError != null) {
			return validationError;
		}
		String sourceCode = sourceQuestionCode();
		if (sourceCode.isBlank()) {
			return null;
		}
		if (importedQuestion != null && importedQuestion.isSharedContextUnresolved()
				&& sharedContextCapturePane.getSelectedContext() == null) {

			return "This imported question requires shared context. Select an existing shared context or capture and save a new one before attaching the question regions.";
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode).isEmpty()) {
			return "Source question " + sourceCode + " has not been created. "
					+ "Click Create, or clear the Source question field.";
		}
		return null;
	}

	boolean hasAcceptedRegions() {
		return !pendingRegions.isEmpty();
	}

	boolean isCapturingSharedContext() {
		return sharedContextCapturePane.isCaptureMode();
	}

	private void loadImportedQuestion(Question question) {
		Question previousQuestion = importedQuestion;
		if (question != previousQuestion && hasAcceptedRegions() && !questionTargetChangeAllowed.getAsBoolean()) {
			refreshingImportedQuestions = true;
			try {
				importedQuestionBox.setValue(previousQuestion);
			} finally {
				refreshingImportedQuestions = false;
			}
			return;
		}
		if (question != null && !importedQuestionActivationHandler.test(question)) {
			refreshingImportedQuestions = true;
			try {
				importedQuestionBox.setValue(previousQuestion);
			} finally {
				refreshingImportedQuestions = false;
			}
			return;
		}
		clearRegions();
		importedQuestion = question;
		refreshSourceQuestionOptions();
		sharedContextCapturePane.refreshForCurrentBooklet();
		if (question == null) {
			questionCodeField.setDisable(false);
			marksField.setDisable(false);
			curriculumSelectorPane.setDisable(false);
			saveQuestionButton.setText("Save Question");
			captureHintLabel.setVisible(false);
			captureHintLabel.setManaged(false);
			return;
		}
		questionCodeField.setText(question.getQuestionCode());
		marksField.setText(Integer.toString(question.getMarks()));
		if (question.hasSourceQuestion()) {
			sourceQuestionField.setValue(question.getSourceQuestion().getSourceQuestionCode());
		} else {
			clearSourceQuestionSelection();
		}
		if (question.hasSharedContext()) {
			sharedContextCapturePane.selectContext(question.getSharedContext());
		}
		curriculumSelectorPane.selectClassificationPath(question.getClassification());
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		curriculumSelectorPane.setDisable(true);
		saveQuestionButton.setText("Attach Regions");
		if (question.isSharedContextUnresolved()) {
			captureHintLabel.setText(
					"Shared context required — select an existing shared context or capture and save a new one before attaching question regions.");

			captureHintLabel.setVisible(true);
			captureHintLabel.setManaged(true);

		} else {
			captureHintLabel.setVisible(false);
			captureHintLabel.setManaged(false);
		}
		showQuestionPendingStatus();
	}

	void refreshImportedQuestions() {
		Question selected = importedQuestion;
		List<Question> awaitingCapture = new ArrayList<>();
		for (Question question : questionRepository.findAll()) {
			if (question.getRegions().isEmpty()) {
				awaitingCapture.add(question);
			}
		}
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getItems().setAll(awaitingCapture);
			Question matchingSelection = null;
			if (selected != null) {
				for (Question question : awaitingCapture) {
					if (question.getId() == selected.getId()) {
						matchingSelection = question;
						break;
					}
				}
			}
			importedQuestionBox.setValue(matchingSelection);
			importedQuestion = matchingSelection;
		} finally {
			refreshingImportedQuestions = false;
		}
	}

	private void refreshRegionPreviews() {
		regionPreviewBox.getChildren().clear();
		for (int i = 0; i < pendingRegions.size(); i++) {
			addRegionPreview(pendingRegions.get(i), i);
		}
		updateRegionsScrollPane();
	}

	private void refreshSourceQuestionOptions() {
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			sourceQuestionField.getItems().clear();
			clearSourceQuestionSelection();
			return;
		}
		String currentCode = sourceQuestionCode();
		List<String> codes = sourceQuestionRepository.findByBooklet(booklet).stream()
				.map(SourceQuestion::getSourceQuestionCode).toList();
		sourceQuestionField.getItems().setAll(codes);
		if (currentCode.isBlank()) {
			return;
		}
		if (codes.contains(currentCode)) {
			sourceQuestionField.setValue(currentCode);
		} else {
			sourceQuestionField.getEditor().setText(currentCode);
		}
	}

	private void removeRegion(int regionIndex) {
		pendingRegions.remove(regionIndex);
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
	}

	private void resetAfterQuestionSave(int previousImportedIndex) {
		importedQuestion = null;
		questionCodeField.setDisable(false);
		marksField.setDisable(false);
		curriculumSelectorPane.setDisable(false);
		saveQuestionButton.setText("Save Question");
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
		resetQuestionEntry();
		refreshImportedQuestions();
		if (previousImportedIndex < 0 || importedQuestionBox.getItems().isEmpty()) {
			return;
		}
		int nextIndex = Math.min(previousImportedIndex, importedQuestionBox.getItems().size() - 1);
		Question nextQuestion = importedQuestionBox.getItems().get(nextIndex);
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getSelectionModel().select(nextIndex);
		} finally {
			refreshingImportedQuestions = false;
		}
		loadImportedQuestion(nextQuestion);
	}

	private void resetQuestionEntry() {
		questionCodeField.clear();
		marksField.clear();
		clearSourceQuestionSelection();
		sharedContextCapturePane.clearForQuestion();
		clearRegions();
		curriculumSelectorPane.clearClassificationBelowSubject();
	}

	private void saveQuestion() {
		Question question;
		int previousImportedIndex = -1;
		ExamBooklet booklet = bookletSupplier.get();
		SourceQuestion sourceQuestion = selectedSourceQuestion(booklet);
		SharedQuestionContext sharedContext = sharedContextCapturePane.getSelectedContext();
		if (importedQuestion != null) {
			previousImportedIndex = importedQuestionBox.getSelectionModel().getSelectedIndex();
			if (previousImportedIndex < 0) {
				previousImportedIndex = 0;
			}
			question = questionRepository.attachRegions(importedQuestion.getId(), pendingRegions, sourceQuestion,
					sharedContext);
			saveStatusLabel.setText(String.format("Captured %s (%d mark(s), %d region(s))", question.getQuestionCode(),
					question.getMarks(), question.getRegions().size()));
		} else {
			int marks = Integer.parseInt(marksField.getText().trim());
			question = questionRepository.save(booklet, questionCodeField.getText().trim(), "", marks, pendingRegions,
					curriculumSelectionModel.getClassification(), false, sourceQuestion, sharedContext);
			saveStatusLabel.setText(String.format("Saved %s (%d mark(s), %d region(s))", question.getQuestionCode(),
					question.getMarks(), question.getRegions().size()));
		}
		questionsChangedHandler.run();
		resetAfterQuestionSave(previousImportedIndex);
	}

	private SourceQuestion selectedSourceQuestion(ExamBooklet booklet) {
		String code = sourceQuestionCode();
		if (code.isBlank()) {
			return null;
		}
		return sourceQuestionRepository.findByBookletAndCode(booklet, code)
				.orElseThrow(() -> new IllegalStateException("Source question has not been created: " + code));
	}

	private void setRegionCountLabel(int count) {
		regionCountLabel.setText(String.format("Regions: %d", count));
	}

	private void showAlert(Alert.AlertType type, String header, String message) {
		Alert alert = new Alert(type);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showQuestionPendingStatus() {
		String questionCode = questionCodeField.getText().trim();
		String prefix = questionCode.isBlank() ? "Question pending" : "Pending " + questionCode;
		saveStatusLabel.setText(String.format("%s — %d region(s) accepted", prefix, pendingRegions.size()));
	}

	private void showRegionPreview(QuestionRegion region) {
		try {
			BufferedImage preview = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
			previewView.setImage(SwingFXUtils.toFXImage(preview, null));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview selected region", e);
		}
	}

	private String sourceQuestionCode() {
		return sourceQuestionField.getEditor().getText().trim();
	}

	private void startNewQuestion() {
		if (hasAcceptedRegions() && !questionTargetChangeAllowed.getAsBoolean()) {
			return;
		}
		clearImportedQuestionSelection();
	}

	private void updateRegionsScrollPane() {
		boolean hasRegions = !pendingRegions.isEmpty();
		regionsScrollPane.setVisible(hasRegions);
		regionsScrollPane.setManaged(hasRegions);
		if (!hasRegions) {
			regionsScrollPane.setPrefHeight(0);
			return;
		}
		double contentHeight = regionPreviewBox.prefHeight(REGION_PREVIEW_WIDTH);
		regionsScrollPane.setPrefHeight(Math.min(contentHeight + 4, REGIONS_VIEWPORT_HEIGHT));
	}

	private void validateQuestionForSave() {
		String validationError = findValidationError();
		if (validationError != null) {
			showAlert(Alert.AlertType.WARNING, "Question is incomplete.", validationError);
			return;
		}
		if (importedQuestion == null) {
			Question existingQuestion = findExistingQuestionWithSameCode();
			if (existingQuestion != null) {
				showAlert(Alert.AlertType.WARNING, "Question already exists.",
						"Question " + existingQuestion.getQuestionCode() + " already exists for this booklet.");
				return;
			}
		}
		try {
			saveQuestion();
		} catch (IllegalStateException e) {
			showAlert(Alert.AlertType.ERROR, "Question could not be saved.",
					"The question was not saved. Your current question details and accepted regions have been retained.");
		}
	}
}
