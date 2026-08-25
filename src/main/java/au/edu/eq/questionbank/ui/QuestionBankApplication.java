package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.CurriculumRepositoryLoader;
import au.edu.eq.questionbank.importer.CurriculumSource;
import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.QuestionRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * JavaFX proof-of-concept for displaying examination pages, selecting ordered
 * rectangular question regions, and assigning a curriculum subtopic.
 */
public class QuestionBankApplication extends Application {

	private record ExamMetadataInput(Subject subject, String providerName, Integer year, String assessmentName,
			String bookletName) {
	}

	private static final float DISPLAY_DPI = 120;
	private static final double MIN_SELECTION_SIZE = 5.0;
	private static final double COMPACT_SPACING = 4.0;
	private static final double REGION_PREVIEW_ITEM_SPACING = 5.0;
	private static final double CURRENT_SELECTION_SPACING = 6.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double SECTION_SPACING = 10.0;
	private static final double PROVIDER_FIELD_WIDTH = 100.0;
	private static final double YEAR_FIELD_WIDTH = 70.0;
	private static final double ASSESSMENT_FIELD_WIDTH = 180.0;
	private static final double BOOKLET_FIELD_WIDTH = 140.0;
	private static final double QUESTION_CODE_FIELD_WIDTH = 100.0;
	private static final double REGION_PREVIEW_WIDTH = 300.0;
	private static final double PREVIEW_IMAGE_WIDTH = 320.0;
	private static final double PREVIEW_PANE_WIDTH = 330.0;
	private static final double REGIONS_VIEWPORT_HEIGHT = 300.0;
	private static final double SCENE_WIDTH = 1400.0;
	private static final double SCENE_HEIGHT = 840.0;

	private static final int YEAR_LOOKBACK_YEARS = 15;
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final Insets PREVIEW_PANE_PADDING = new Insets(10);
	private static final Insets PAGE_CONTROLS_PADDING = new Insets(6);
	private static final Insets EXAM_BAR_PADDING = new Insets(6, 10, 6, 10);

	private static final Insets COMPACT_BUTTON_PADDING = new Insets(2, 8, 2, 8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;"
			+ "-fx-border-radius: 3;";
	private static final String BORDERED_INPUT_GROUP_STYLE = BORDER_STYLE + "-fx-padding: 5;";
	private static final String SECTION_HEADING_STYLE = "-fx-font-weight: bold;";

	private static final String SUCCESS_STATUS_STYLE = "-fx-text-fill: #2e7d32;";

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

	public static void main(String[] args) {
		launch(args);
	}

	private PdfSession pdfSession;
	private PdfSession answerPdfSession;
	private boolean showingAnswerPdf;
	private ExamBooklet booklet;

	private final ExamMetadataOptionsRepository examMetadataOptionsRepository = new ExamMetadataOptionsRepository();
	private final QuestionRepository questionRepository = new InMemoryQuestionRepository();
	private final QuestionExtractor questionExtractor = new QuestionExtractor();
	private CurriculumSelectionModel curriculumSelectionModel;
	private QuestionRegion currentSelection;
	private final List<QuestionRegion> pendingRegions = new ArrayList<>();
	private Path currentPdfPath;
	private CurriculumSelectorPane curriculumSelectorPane;
	private AnswerFile answerFile;
	private Path currentAnswerPdfPath;

	private final Pane pagePane = new Pane();
	private final Rectangle selectionRectangle = new Rectangle();

	private final Button addRegionButton = new Button("Add");
	private final Button chooseAnswerPdfButton = new Button("Choose Answer PDF...");
	private final Button choosePdfButton = new Button("Choose PDF...");
	private final Button clearRegionsButton = new Button("Clear Regions");
	private final Button combineRegionsButton = new Button("Preview Question");
	private final Button nextButton = new Button("Next");
	private final Button previousButton = new Button("Previous");
	private final Button removeCurrentSelectionButton = new Button("Clear");
	private final Button saveAnswerButton = new Button("Save Answer");
	private final Button saveQuestionButton = new Button("Save Question");
	private final Button setExamButton = new Button("Set Exam");

	private final CheckBox fullWidthSelectionCheckBox = new CheckBox("Full width selection");

	private final ComboBox<String> assessmentField = new ComboBox<>();
	private final ComboBox<String> bookletField = new ComboBox<>();
	private final ComboBox<String> providerField = new ComboBox<>();
	private final ComboBox<Question> unansweredQuestionField = new ComboBox<>();
	private final ComboBox<Integer> yearField = new ComboBox<>();

	private final ImageView previewView = new ImageView();
	private final ImageView pageView = new ImageView();
	private final ImageView combinedPreviewView = new ImageView();

	private final Label examSubjectLabel = new Label("Not selected");
	private final Label regionCountLabel = new Label("Regions: 0");
	private final Label pageLabel = new Label();
	private final Label saveStatusLabel = new Label();
	private final Label selectedAnswerPdfLabel = new Label("No answer PDF selected");
	private final Label selectedAnswerQuestionLabel = new Label("No question selected");
	private final Label selectedPdfLabel = new Label("No PDF selected");

	private final TextField answerTextField = new TextField();
	private final TextField questionCodeField = new TextField();

	private final VBox regionPreviewBox = new VBox(SECTION_SPACING);

	private int currentPageNumber = 1;
	private long nextQuestionId = 1;
	private long nextAnswerId = 1;
	private double selectionStartX;
	private double selectionStartY;

	@Override
	public void start(Stage stage) throws Exception {
		ApplicationConfig config;
		try {
			config = ApplicationConfig.load(Path.of("questionbank.properties"));
		} catch (ConfigurationException e) {
			showStartupError("Configuration Error", e.getMessage());
			return;
		} catch (IOException e) {
			showStartupError("Configuration Error", "Could not read questionbank.properties:\n" + e.getMessage());
			return;
		}
		startApplication(stage, config);

	}

	@Override
	public void stop() throws Exception {
		if (pdfSession != null) {
			pdfSession.close();
		}
		if (answerPdfSession != null) {
			answerPdfSession.close();
		}
	}

	private void addCurrentRegion() {

		if (currentSelection == null) {
			return;
		}

		pendingRegions.add(currentSelection);
		clearCurrentSelection();
		combinedPreviewView.setImage(null);
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
	}

	private void addRegionPreview(QuestionRegion region, int regionIndex) {
		try {
			BufferedImage image = questionExtractor.extractRegion(pdfSession, region);
			ImageView imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
			imageView.setPreserveRatio(true);
			imageView.setFitWidth(REGION_PREVIEW_WIDTH);
			imageView.setSmooth(true);

			Label label = new Label(String.format("Region %d - Page %d", regionIndex + 1, region.pageNumber()));
			Button removeButton = new Button("Remove");
			removeButton.setOnAction(event -> removeRegion(regionIndex));
			HBox header = new HBox(SECTION_SPACING, label, removeButton);
			VBox regionBox = new VBox(REGION_PREVIEW_ITEM_SPACING, header, imageView);
			regionPreviewBox.getChildren().add(regionBox);
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview region", e);
		}
	}

	private void applyExamMetadataToControls(ExamMetadataInput input) {
		providerField.setValue(input.providerName());
		assessmentField.setValue(input.assessmentName());
		bookletField.setValue(input.bookletName());
	}

	private void chooseAnswerPdf(Stage stage, Path pdfDataRoot) {
		Question question = unansweredQuestionField.getValue();

		if (question == null) {
			showAnswerError("Select a question first.");
			return;
		}

		FileChooser chooser = new FileChooser();
		chooser.setTitle("Choose answer PDF");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));

		File root = pdfDataRoot.toFile();
		if (root.isDirectory()) {
			chooser.setInitialDirectory(root);
		}

		File selectedFile = chooser.showOpenDialog(stage);

		if (selectedFile == null) {
			return;
		}

		Path selectedPath = selectedFile.toPath().toAbsolutePath().normalize();
		Path rootPath = pdfDataRoot.toAbsolutePath().normalize();
		if (!selectedPath.startsWith(rootPath)) {
			showAlert(Alert.AlertType.ERROR, null, "Answer PDF must be inside the configured PDF data folder.",
					rootPath.toString());
			return;
		}
		try {
			if (answerPdfSession != null) {
				answerPdfSession.close();
			}

			answerPdfSession = PdfSession.open(currentAnswerPdfPath);
			showingAnswerPdf = true;
			currentPageNumber = 1;

			pagePane.setCursor(Cursor.DEFAULT);
			showCurrentPage();

		} catch (Exception e) {
			throw new RuntimeException("Unable to open answer PDF", e);
		}

		currentAnswerPdfPath = selectedPath;
		SourceDocument sourceDocument = new SourceDocument(1, rootPath.relativize(selectedPath).toString());
		answerFile = new AnswerFile(1, question.getExam(), selectedFile.getName(), sourceDocument);
		selectedAnswerPdfLabel.setText(selectedFile.getName());
	}

	private void choosePdf(Stage stage, Path pdfDataRoot) {
		FileChooser chooser = createPdfFileChooser(pdfDataRoot);
		File selectedFile = chooser.showOpenDialog(stage);

		if (selectedFile == null) {
			return;
		}

		Path selectedPath = selectedFile.toPath().toAbsolutePath().normalize();
		Path rootPath = pdfDataRoot.toAbsolutePath().normalize();

		if (!isInsidePdfDataRoot(selectedPath, rootPath)) {
			showAlert(Alert.AlertType.ERROR, null, "PDF must be inside the configured PDF data folder.",
					rootPath.toString());
			return;
		}

		openSelectedPdf(selectedFile, selectedPath);
	}

	private double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private void clearCurrentSelection() {
		currentSelection = null;
		selectionRectangle.setVisible(false);
		selectionRectangle.setWidth(0);
		selectionRectangle.setHeight(0);
		previewView.setImage(null);
	}

	private void clearExamMetadata() {
		providerField.getSelectionModel().clearSelection();
		providerField.getEditor().clear();
		yearField.getSelectionModel().clearSelection();
		assessmentField.getSelectionModel().clearSelection();
		assessmentField.getEditor().clear();
		bookletField.getSelectionModel().clearSelection();
		bookletField.getEditor().clear();
		booklet = null;
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
		combinedPreviewView.setImage(null);
		refreshRegionPreviews();
		setRegionCountLabel(0);
	}

	private void combineRegions() {
		if (pendingRegions.isEmpty()) {
			return;
		}
		try {
			BufferedImage combined = questionExtractor.extractRegions(pdfSession, pendingRegions);
			combinedPreviewView.setImage(SwingFXUtils.toFXImage(combined, null));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview question", e);
		}
	}

	private void configureActions(Stage stage, ApplicationConfig config) {
		addRegionButton.setOnAction(event -> addCurrentRegion());
		chooseAnswerPdfButton.setOnAction(event -> chooseAnswerPdf(stage, config.pdfDataRoot()));
		choosePdfButton.setOnAction(event -> choosePdf(stage, config.pdfDataRoot()));
		clearRegionsButton.setOnAction(event -> clearQuestionRegions());
		combineRegionsButton.setOnAction(event -> combineRegions());
		nextButton.setOnAction(event -> nextPage());
		previousButton.setOnAction(event -> previousPage());
		removeCurrentSelectionButton.setOnAction(event -> clearPendingSelection());
		saveAnswerButton.setOnAction(event -> validateTextAnswerForSave());
		saveQuestionButton.setOnAction(event -> validateQuestionForSave());
		setExamButton.setOnAction(event -> setExamMetadata(config.pdfDataRoot()));
	}

	private void configureBorderedPanel(Region panel) {
		panel.setPadding(PANEL_PADDING);
		panel.setStyle(BORDER_STYLE);
	}

	private void configureExamMetadataFields() {
		providerField.setPromptText("QCAA");
		providerField.setPrefWidth(PROVIDER_FIELD_WIDTH);
		providerField.setEditable(true);

		int currentYear = Year.now().getValue();
		for (int year = currentYear; year >= currentYear - YEAR_LOOKBACK_YEARS; year--) {
			yearField.getItems().add(year);
		}
		yearField.setPrefWidth(YEAR_FIELD_WIDTH);

		assessmentField.setPromptText("External Assessment");
		assessmentField.setPrefWidth(ASSESSMENT_FIELD_WIDTH);
		assessmentField.setEditable(true);

		bookletField.setPromptText("Paper 1 MCQ");
		bookletField.setPrefWidth(BOOKLET_FIELD_WIDTH);
		bookletField.setEditable(true);
	}

	private void configureMouseSelection() {
		pageView.setOnMousePressed(this::handleSelectionPressed);
		pageView.setOnMouseDragged(this::handleSelectionDragged);
		pageView.setOnMouseReleased(this::handleSelectionReleased);
	}

	private void configurePageView() {
		pageView.setPreserveRatio(true);
		pagePane.getChildren().add(pageView);
		pagePane.setCursor(Cursor.DEFAULT);
		fullWidthSelectionCheckBox.setSelected(true);
	}

	private void configurePreviewImageView(ImageView imageView) {
		imageView.setPreserveRatio(true);
		imageView.setFitWidth(PREVIEW_IMAGE_WIDTH);
		imageView.setSmooth(true);
	}

	private void configurePreviewView() {
		configurePreviewImageView(previewView);
		configurePreviewImageView(combinedPreviewView);
	}

	private void configureSelectionRectangle() {
		selectionRectangle.setFill(Color.rgb(0, 120, 215, 0.15));
		selectionRectangle.setStroke(Color.rgb(0, 90, 180));
		selectionRectangle.setStrokeWidth(2);
		selectionRectangle.setVisible(false);
		selectionRectangle.setMouseTransparent(true);
		pagePane.getChildren().add(selectionRectangle);
		selectionRectangle.toFront();
	}

	private void configureToolTips() {
		fullWidthSelectionCheckBox
				.setTooltip(new Tooltip("When selected, drag vertically to capture the full page width.\n"
						+ "Clear this option to draw a rectangular region."));
		setExamButton.setTooltip(new Tooltip("Apply these exam details before selecting question regions."));
		choosePdfButton.setTooltip(new Tooltip("Choose the PDF containing the exam booklet."));
	}

	private void configureUnansweredQuestionField() {
		unansweredQuestionField.setPromptText("Select unanswered question");
		unansweredQuestionField.setMaxWidth(Double.MAX_VALUE);
		unansweredQuestionField.setConverter(QUESTION_CODE_CONVERTER);
	}

	private VBox createAnswerDetailsPane() {
		configureUnansweredQuestionField();

		answerTextField.setPromptText("Answer text, e.g. B");
		answerTextField.setDisable(true);
		saveAnswerButton.setDisable(true);

		unansweredQuestionField.valueProperty()
				.addListener((observable, oldQuestion, newQuestion) -> handleUnansweredQuestionChanged(newQuestion));

		HBox answerControls = new HBox(CONTROL_SPACING, answerTextField, saveAnswerButton);
		HBox answerPdfControls = new HBox(CONTROL_SPACING, chooseAnswerPdfButton, selectedAnswerPdfLabel);

		answerPdfControls.setAlignment(Pos.CENTER_LEFT);
		answerControls.setAlignment(Pos.CENTER_LEFT);

		VBox answerDetails = new VBox(COMPACT_SPACING, createSectionLabel("Answer"), unansweredQuestionField,
				selectedAnswerQuestionLabel, answerPdfControls, answerControls);

		configureBorderedPanel(answerDetails);
		return answerDetails;
	}

	private HBox createCurrentSelectionControls(Label currentLabel) {
		addRegionButton.setPadding(COMPACT_BUTTON_PADDING);
		removeCurrentSelectionButton.setPadding(COMPACT_BUTTON_PADDING);
		return new HBox(CURRENT_SELECTION_SPACING, currentLabel, addRegionButton, removeCurrentSelectionButton);
	}

	private CurriculumSelectorPane createCurriculumSelectorPane() {
		CurriculumSelectorPane selectorPane = new CurriculumSelectorPane(curriculumSelectionModel);
		Subject subject = curriculumSelectionModel.getSubject();
		examSubjectLabel.setText(subject == null ? "Not selected" : subject.getName());
		selectorPane.selectedSubjectProperty().addListener((observable, oldSubject, newSubject) -> {
			examSubjectLabel.setText(newSubject == null ? "Not selected" : newSubject.getName());
		});
		return selectorPane;
	}

	private HBox createExamBar() {
		configureExamMetadataFields();

		HBox examDetails = createExamDetailsPane();
		HBox pdfDetails = createPdfDetailsPane();
		HBox examDetailsGroup = createExamDetailsGroup(pdfDetails, examDetails);
		HBox examBar = new HBox(examDetailsGroup);
		examBar.setAlignment(Pos.CENTER_LEFT);
		examBar.setPadding(EXAM_BAR_PADDING);
		return examBar;
	}

	private ExamBooklet createExamBooklet(Path pdfDataRoot, ExamMetadataInput input) {
		ExamProvider provider = new ExamProvider(1, input.providerName());
		Exam exam = new Exam(1, input.subject(), provider, input.year(), input.assessmentName());
		SourceDocument sourceDocument = new SourceDocument(1,
				pdfDataRoot.toAbsolutePath().normalize().relativize(currentPdfPath).toString());
		return new ExamBooklet(1, exam, input.bookletName(), sourceDocument);
	}

	private HBox createExamDetailsGroup(HBox pdfDetails, HBox examDetails) {
		HBox group = new HBox(CONTROL_SPACING, createSectionLabel("Exam Details:"), pdfDetails, examDetails);
		group.setAlignment(Pos.CENTER_LEFT);
		configureBorderedPanel(group);
		return group;
	}

	private HBox createExamDetailsPane() {
		HBox examDetails = new HBox(CONTROL_SPACING, new Label("Subject"), examSubjectLabel, new Label("Provider"),
				providerField, new Label("Year"), yearField, new Label("Assessment"), assessmentField,
				new Label("Booklet"), bookletField, setExamButton);
		examDetails.setAlignment(Pos.CENTER_LEFT);
		examDetails.setStyle(BORDERED_INPUT_GROUP_STYLE);
		return examDetails;
	}

	private HBox createPdfDetailsPane() {
		HBox pdfDetails = new HBox(CONTROL_SPACING, choosePdfButton, selectedPdfLabel);
		pdfDetails.setAlignment(Pos.CENTER_LEFT);
		pdfDetails.setStyle(BORDERED_INPUT_GROUP_STYLE);
		return pdfDetails;
	}

	private FileChooser createPdfFileChooser(Path pdfDataRoot) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Choose exam PDF");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));

		File root = pdfDataRoot.toFile();
		if (root.isDirectory()) {
			chooser.setInitialDirectory(root);
		}
		return chooser;
	}

	private VBox createPdfWorkspace() {
		// PDF controls
		HBox pageControls = new HBox(SECTION_SPACING, previousButton, pageLabel, nextButton,
				fullWidthSelectionCheckBox);
		pageControls.setAlignment(Pos.CENTER);
		pageControls.setPadding(PAGE_CONTROLS_PADDING);
		previousButton.setDisable(true);
		nextButton.setDisable(true);
		pageLabel.setText("No PDF selected");

		ScrollPane scrollPane = new ScrollPane(pagePane);
		scrollPane.setFitToWidth(true);
		scrollPane.setFitToHeight(false);
		pageView.fitWidthProperty().bind(pagePane.widthProperty());

		VBox pdfWorkspace = new VBox(scrollPane, pageControls);
		VBox.setVgrow(scrollPane, Priority.ALWAYS);
		return pdfWorkspace;
	}

	private VBox createPreviewPane() {
		Label currentLabel = new Label("Current selection");
		Label regionsLabel = new Label("Accepted regions");
		Label combinedLabel = new Label("Combined question");

		curriculumSelectorPane = createCurriculumSelectorPane();
		VBox questionDetails = createQuestionDetailsPane();
		VBox answerDetails = createAnswerDetailsPane();
		HBox currentSelectionButtons = createCurrentSelectionControls(currentLabel);
		ScrollPane regionsScrollPane = createRegionsScrollPane();

		VBox previewPane = new VBox(SECTION_SPACING, curriculumSelectorPane, questionDetails, new Separator(),
				answerDetails, new Separator(), currentSelectionButtons, previewView, new Separator(), regionsLabel,
				regionCountLabel, regionsScrollPane, combineRegionsButton, new Separator(), combinedLabel,
				combinedPreviewView);
		previewPane.setPadding(PREVIEW_PANE_PADDING);
		setFixedWidth(previewPane, PREVIEW_PANE_WIDTH);
		return previewPane;
	}

	private VBox createQuestionDetailsPane() {
		questionCodeField.setPromptText("Q1");
		questionCodeField.setPrefWidth(QUESTION_CODE_FIELD_WIDTH);
		HBox questionControls = new HBox(CONTROL_SPACING, questionCodeField, saveQuestionButton);
		questionControls.setAlignment(Pos.CENTER_LEFT);
		saveStatusLabel.setStyle(SUCCESS_STATUS_STYLE);

		VBox questionDetails = new VBox(COMPACT_SPACING, createSectionLabel("Question"), questionControls,
				saveStatusLabel);
		configureBorderedPanel(questionDetails);
		return questionDetails;
	}

	private void createQuestionRegion() {
		if (isSelectionTooSmall()) {
			selectionRectangle.setVisible(false);
			return;
		}

		if (booklet == null) {
			clearCurrentSelection();
			showAlert(Alert.AlertType.WARNING, null, "Exam details have not been set.",
					"Enter the exam and booklet details, then click Set Exam.");
			return;
		}

		currentSelection = createRegionFromSelection();
		showRegionPreview(currentSelection);
		saveStatusLabel.setText("Selection pending — click Add or Clear");
	}

	private QuestionRegion createRegionFromSelection() {
		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		double normalizedX = selectionRectangle.getX() / pageWidth;
		double normalizedY = selectionRectangle.getY() / pageHeight;
		double normalizedWidth = selectionRectangle.getWidth() / pageWidth;
		double normalizedHeight = selectionRectangle.getHeight() / pageHeight;

		return new QuestionRegion(booklet, currentPageNumber, normalizedX, normalizedY, normalizedWidth,
				normalizedHeight);
	}

	private ScrollPane createRegionsScrollPane() {
		ScrollPane regionsScrollPane = new ScrollPane(regionPreviewBox);
		regionsScrollPane.setFitToWidth(true);
		regionsScrollPane.setPrefViewportHeight(REGIONS_VIEWPORT_HEIGHT);
		return regionsScrollPane;
	}

	private BorderPane createRootLayout(VBox pdfWorkspace) {
		BorderPane root = new BorderPane();
		root.setTop(createExamBar());
		root.setLeft(createPreviewPane());
		root.setCenter(pdfWorkspace);
		return root;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private String findExamMetadataPrerequisiteError() {
		if (pdfSession == null) {
			return "Choose a PDF first.";
		}
		if (curriculumSelectionModel.getSubject() == null) {
			return "Select a subject before setting the exam.";
		}
		return null;
	}

	private String findQuestionValidationError() {
		if (booklet == null) {
			return "Set the exam details first.";
		}

		if (questionCodeField.getText().trim().isBlank()) {
			return "Enter a question code.";
		}

		if (curriculumSelectionModel.getSubject() == null) {
			return "Select a subject.";
		}

		if (curriculumSelectionModel.getUnit() == null) {
			return "Select a unit.";
		}

		if (curriculumSelectionModel.getTopic() == null) {
			return "Select a topic.";
		}

		if (curriculumSelectionModel.getSubtopic() == null) {
			return "Select a subtopic.";
		}
		if (currentSelection != null) {
			return "The current selection has not been added to Accepted regions.";
		}
		if (pendingRegions.isEmpty()) {
			return "Add at least one question region.";
		}
		return null;
	}

	private PdfSession getDisplayedPdfSession() {
		return showingAnswerPdf ? answerPdfSession : pdfSession;
	}

	private void handleSelectionDragged(MouseEvent event) {
		if (showingAnswerPdf || booklet == null || !event.isPrimaryButtonDown()) {
			return;
		}

		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		double currentY = clamp(event.getY(), 0, pageHeight);

		selectionRectangle.setY(Math.min(selectionStartY, currentY));
		selectionRectangle.setHeight(Math.abs(currentY - selectionStartY));
		updateHorizontalSelection(pageWidth, event.getX());
	}

	private void handleSelectionPressed(MouseEvent event) {
		if (showingAnswerPdf || booklet == null || event.getButton() != MouseButton.PRIMARY) {
			return;
		}

		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		selectionStartY = clamp(event.getY(), 0, pageHeight);

		if (fullWidthSelectionCheckBox.isSelected()) {
			selectionStartX = 0;
			selectionRectangle.setWidth(pageWidth);
		} else {
			selectionStartX = clamp(event.getX(), 0, pageWidth);
			selectionRectangle.setWidth(0);
		}

		selectionRectangle.setX(selectionStartX);
		selectionRectangle.setY(selectionStartY);
		selectionRectangle.setHeight(0);
		selectionRectangle.setVisible(true);
	}

	private void handleSelectionReleased(MouseEvent event) {
		if (!showingAnswerPdf && booklet != null && event.getButton() == MouseButton.PRIMARY) {
			createQuestionRegion();
		}
	}

	private void handleUnansweredQuestionChanged(Question question) {
		answerTextField.clear();

		if (question == null) {
			selectedAnswerQuestionLabel.setText("No question selected");
			answerTextField.setDisable(true);
			saveAnswerButton.setDisable(true);
			return;
		}

		selectedAnswerQuestionLabel.setText("Answering " + question.getQuestionCode());

		answerTextField.setDisable(false);
		saveAnswerButton.setDisable(false);
	}

	private boolean isComplete(ExamMetadataInput input) {
		return !input.providerName().isBlank() && input.year() != null && !input.assessmentName().isBlank()
				&& !input.bookletName().isBlank();
	}

	private boolean isInsidePdfDataRoot(Path selectedPath, Path rootPath) {
		return selectedPath.startsWith(rootPath);
	}

	private boolean isSelectionTooSmall() {
		return selectionRectangle.getWidth() < MIN_SELECTION_SIZE
				|| selectionRectangle.getHeight() < MIN_SELECTION_SIZE;
	}

	private CurriculumSelectionModel loadCurriculum(ApplicationConfig config) throws IOException {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		Path chemistryRoot = config.curriculumDataRoot().resolve("chemistry");
		CurriculumSource source2019 = new CurriculumSource(syllabus2019,
				List.of(chemistryRoot.resolve("2019").resolve("CHM Study Checklist [2019 Syllabus].xlsx")));
		CurriculumSource source2025 = new CurriculumSource(syllabus2025, List.of(
				chemistryRoot.resolve("2025").resolve("CHM Study Checklist - Unit 1 and 2 [2025 Syllabus].xlsx"),
				chemistryRoot.resolve("2025").resolve("CHM Study Checklist - Unit 3 and 4 [2025 Syllabus].xlsx")));
		CurriculumRepository repository = new CurriculumRepositoryLoader().load(List.of(source2019, source2025));

		return new CurriculumSelectionModel(repository);
	}

	private void loadExamMetadataOptions() {
		providerField.getItems().setAll(examMetadataOptionsRepository.getProviders());
		assessmentField.getItems().setAll(examMetadataOptionsRepository.getAssessments());
		bookletField.getItems().setAll(examMetadataOptionsRepository.getBooklets());
	}

	private void nextPage() {
		PdfSession displayedSession = getDisplayedPdfSession();
		if (displayedSession != null && currentPageNumber < displayedSession.getPageCount()) {
			currentPageNumber++;
			showCurrentPage();
		}
	}

	private void openSelectedPdf(File selectedFile, Path selectedPath) {
		try {
			if (pdfSession != null) {
				pdfSession.close();
			}
			pdfSession = PdfSession.open(selectedPath);
			showingAnswerPdf = false;
			currentPdfPath = selectedPath;
			clearExamMetadata();
			pagePane.setCursor(Cursor.DEFAULT);
			currentPageNumber = 1;
			clearRegions();
			selectedPdfLabel.setText(selectedFile.getName());
			showCurrentPage();
		} catch (Exception e) {
			throw new RuntimeException("Unable to open PDF", e);
		}
	}

	private void previousPage() {
		if (currentPageNumber > 1) {
			currentPageNumber--;
			showCurrentPage();
		}
	}

	private ExamMetadataInput readExamMetadataInput() {
		return new ExamMetadataInput(curriculumSelectionModel.getSubject(), providerField.getEditor().getText().trim(),
				yearField.getValue(), assessmentField.getEditor().getText().trim(),
				bookletField.getEditor().getText().trim());
	}

	private void refreshRegionPreviews() {
		regionPreviewBox.getChildren().clear();
		for (int i = 0; i < pendingRegions.size(); i++) {
			QuestionRegion region = pendingRegions.get(i);
			addRegionPreview(region, i);
		}
	}

	private void refreshUnansweredQuestions() {
		List<Question> unansweredQuestions = questionRepository.findAll().stream()
				.filter(question -> !question.hasAnswer()).toList();

		unansweredQuestionField.getItems().setAll(unansweredQuestions);
	}

	private void rememberExamMetadataOptions(ExamMetadataInput input) {
		examMetadataOptionsRepository.addProvider(input.providerName());
		examMetadataOptionsRepository.addAssessment(input.assessmentName());
		examMetadataOptionsRepository.addBooklet(input.bookletName());
		loadExamMetadataOptions();
	}

	private void removeRegion(int regionIndex) {
		pendingRegions.remove(regionIndex);
		combinedPreviewView.setImage(null);
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
	}

	private void resetQuestionEntry() {
		questionCodeField.clear();
		clearRegions();
		curriculumSelectorPane.clearClassificationBelowSubject();
	}

	private void saveQuestion() {
		Question question = new Question(nextQuestionId++, booklet.getExam(), questionCodeField.getText().trim(), "",
				pendingRegions, curriculumSelectionModel.getSubtopic());
		questionRepository.save(question);
		refreshUnansweredQuestions();
		saveStatusLabel.setText(
				String.format("Saved %s (%d region(s))", question.getQuestionCode(), question.getRegions().size()));
		resetQuestionEntry();
	}

	private void saveTextAnswer(Question question, String answerText) {
		Answer answer = new Answer(nextAnswerId++, answerText, List.of());

		question.setAnswer(answer);

		String questionCode = question.getQuestionCode();

		unansweredQuestionField.getSelectionModel().clearSelection();
		refreshUnansweredQuestions();

		selectedAnswerQuestionLabel.setText("Saved answer for " + questionCode);
	}

	private void setExamMetadata(Path pdfDataRoot) {
		String prerequisiteError = findExamMetadataPrerequisiteError();
		if (prerequisiteError != null) {
			showExamMetadataError(prerequisiteError);
			return;
		}

		ExamMetadataInput input = readExamMetadataInput();
		if (!isComplete(input)) {
			showExamMetadataError("Complete all exam details.");
			return;
		}

		booklet = createExamBooklet(pdfDataRoot, input);
		rememberExamMetadataOptions(input);
		applyExamMetadataToControls(input);
		pagePane.setCursor(Cursor.CROSSHAIR);
	}

	private void setFixedWidth(Region region, double width) {
		region.setPrefWidth(width);
		region.setMinWidth(width);
		region.setMaxWidth(width);
	}

	private void setRegionCountLabel(int count) {
		regionCountLabel.setText(String.format("Regions: %d", count));
	}

	private void showAlert(Alert.AlertType type, String title, String header, String message) {
		Alert alert = new Alert(type);
		if (title != null) {
			alert.setTitle(title);
		}
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showAnswerError(String message) {
		showAlert(Alert.AlertType.WARNING, null, "Answer is incomplete.", message);
	}

	private void showCurrentPage() {
		clearCurrentSelection();
		PdfSession displayedSession = getDisplayedPdfSession();
		if (displayedSession == null) {
			return;
		}

		try {
			BufferedImage bufferedImage = displayedSession.renderPage(currentPageNumber, DISPLAY_DPI);
			pageView.setImage(SwingFXUtils.toFXImage(bufferedImage, null));
			selectionRectangle.setVisible(false);
			String pageType = showingAnswerPdf ? "Answer page" : "Page";
			pageLabel.setText(
					String.format("%s %d of %d", pageType, currentPageNumber, displayedSession.getPageCount()));
			previousButton.setDisable(currentPageNumber == 1);
			nextButton.setDisable(currentPageNumber == displayedSession.getPageCount());
		} catch (IOException e) {
			throw new RuntimeException("Unable to render PDF page", e);
		}
	}

	private void showExamMetadataError(String message) {
		showAlert(Alert.AlertType.WARNING, null, "Exam details are incomplete.", message);
	}

	private void showQuestionError(String message) {
		showAlert(Alert.AlertType.WARNING, null, "Question is incomplete.", message);
	}

	private void showQuestionPendingStatus() {
		String questionCode = questionCodeField.getText().trim();
		String prefix = questionCode.isBlank() ? "Question pending" : "Pending " + questionCode;
		saveStatusLabel.setText(String.format("%s — %d region(s) accepted", prefix, pendingRegions.size()));
	}

	private void showRegionPreview(QuestionRegion region) {

		try {
			BufferedImage preview = questionExtractor.extractRegion(pdfSession, region);
			previewView.setImage(SwingFXUtils.toFXImage(preview, null));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview selected region", e);
		}
	}

	private void showStage(Stage primaryStage, BorderPane root) {
		Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);
		primaryStage.setTitle("Exam Question Bank");
		primaryStage.setScene(scene);
		primaryStage.show();
	}

	private void showStartupError(String title, String message) {
		showAlert(Alert.AlertType.ERROR, title, "The application could not start.", message);
	}

	private void startApplication(Stage primaryStage, ApplicationConfig config) throws IOException {
		curriculumSelectionModel = loadCurriculum(config);
		configureActions(primaryStage, config);
		configurePageView();
		configureSelectionRectangle();
		configureMouseSelection();
		configurePreviewView();
		configureToolTips();
		loadExamMetadataOptions();
		VBox pdfWorkspace = createPdfWorkspace();
		BorderPane root = createRootLayout(pdfWorkspace);
		showStage(primaryStage, root);
	}

	private void updateHorizontalSelection(double pageWidth, double eventX) {
		if (fullWidthSelectionCheckBox.isSelected()) {
			selectionRectangle.setX(0);
			selectionRectangle.setWidth(pageWidth);
			return;
		}

		double currentX = clamp(eventX, 0, pageWidth);
		selectionRectangle.setX(Math.min(selectionStartX, currentX));
		selectionRectangle.setWidth(Math.abs(currentX - selectionStartX));
	}

	private void validateQuestionForSave() {
		String validationError = findQuestionValidationError();
		if (validationError != null) {
			showQuestionError(validationError);
			return;
		}

		saveQuestion();
	}

	private void validateTextAnswerForSave() {
		Question question = unansweredQuestionField.getValue();

		if (question == null) {
			showAnswerError("Select a question.");
			return;
		}

		String answerText = answerTextField.getText().trim();

		if (answerText.isBlank()) {
			showAnswerError("Enter answer text.");
			return;
		}

		saveTextAnswer(question, answerText);
	}
}
