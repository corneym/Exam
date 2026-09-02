package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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

	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final CurriculumSelectorPane curriculumSelectorPane;
	private final Supplier<ExamBooklet> bookletSupplier;
	private final Supplier<PdfSession> examPdfSessionSupplier;
	private final Runnable selectionClearHandler;
	private final Runnable questionsChangedHandler;

	private QuestionRegion currentSelection;
	private final List<QuestionRegion> pendingRegions = new ArrayList<>();

	/**
	 * Creates the question-capture workflow and its repository integration.
	 */
	QuestionCapturePane(QuestionRepository questionRepository, QuestionExtractor questionExtractor,
			CurriculumSelectionModel curriculumSelectionModel, CurriculumSelectorPane curriculumSelectorPane,
			Supplier<ExamBooklet> bookletSupplier, Supplier<PdfSession> examPdfSessionSupplier,
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

		this.questionRepository = questionRepository;
		this.questionExtractor = questionExtractor;
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.curriculumSelectorPane = curriculumSelectorPane;
		this.bookletSupplier = bookletSupplier;
		this.examPdfSessionSupplier = examPdfSessionSupplier;
		this.selectionClearHandler = selectionClearHandler;
		this.questionsChangedHandler = questionsChangedHandler;

		configureControls();
		configureActions();
		getChildren().addAll(createSectionLabel("Question"), createQuestionControls(), saveStatusLabel, new Separator(),
				createCurrentSelectionControls(), previewView, new Separator(), new Label("Accepted regions"),
				regionCountLabel, createRegionsScrollPane());
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
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

	private void configureActions() {
		addRegionButton.setOnAction(event -> addCurrentRegion());
		clearRegionsButton.setOnAction(event -> clearQuestionRegions());
		removeCurrentSelectionButton.setOnAction(event -> clearPendingSelection());
		saveQuestionButton.setOnAction(event -> validateQuestionForSave());
	}

	private void configureControls() {
		questionCodeField.setId("question-code");
		questionCodeField.setPromptText("Q1");
		questionCodeField.setPrefWidth(QUESTION_CODE_FIELD_WIDTH);
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

	private String findValidationError() {
		return QuestionCaptureValidator.findError(new QuestionCaptureValidator.State(bookletSupplier.get() != null,
				questionCodeField.getText().trim(), marksField.getText().trim(),
				curriculumSelectionModel.getSubject() != null, curriculumSelectionModel.getUnit() != null,
				curriculumSelectionModel.getTopic() != null, curriculumSelectionModel.getClassification() != null,
				currentSelection != null, pendingRegions.size()));
	}

	private void refreshRegionPreviews() {
		regionPreviewBox.getChildren().clear();
		for (int i = 0; i < pendingRegions.size(); i++) {
			addRegionPreview(pendingRegions.get(i), i);
		}
		updateRegionsScrollPane();
	}

	private void removeRegion(int regionIndex) {
		pendingRegions.remove(regionIndex);
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
	}

	private void resetQuestionEntry() {
		questionCodeField.clear();
		marksField.clear();
		clearRegions();
		curriculumSelectorPane.clearClassificationBelowSubject();
	}

	private void saveQuestion() {
		ExamBooklet booklet = bookletSupplier.get();
		int marks = Integer.parseInt(marksField.getText().trim());
		Question question = questionRepository.save(booklet, questionCodeField.getText().trim(), "", marks,
				pendingRegions, curriculumSelectionModel.getClassification(), false);
		questionsChangedHandler.run();
		saveStatusLabel.setText(String.format("Saved %s (%d mark(s), %d region(s))", question.getQuestionCode(),
				question.getMarks(), question.getRegions().size()));
		resetQuestionEntry();
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
		saveQuestion();
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
		clearRegions();
	}
}
