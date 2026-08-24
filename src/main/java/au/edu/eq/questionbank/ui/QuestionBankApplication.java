package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.CurriculumRepositoryLoader;
import au.edu.eq.questionbank.importer.CurriculumSource;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.CurriculumRepository;
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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * JavaFX proof-of-concept for displaying examination pages, selecting ordered
 * rectangular question regions, and assigning a curriculum subtopic.
 */
public class QuestionBankApplication extends Application {

	private static final float DISPLAY_DPI = 120;
	private static final double MIN_SELECTION_SIZE = 5.0;

	public static void main(String[] args) {
		launch(args);
	}

	private PdfSession pdfSession;
	private ExamBooklet booklet;

	private final QuestionExtractor questionExtractor = new QuestionExtractor();
	private CurriculumSelectionModel curriculumSelectionModel;
	private QuestionRegion currentSelection;
	private final List<QuestionRegion> pendingRegions = new ArrayList<>();
	private Path currentPdfPath;

	private final Pane pagePane = new Pane();
	private final Rectangle selectionRectangle = new Rectangle();

	private final Button addRegionButton = new Button("Add");
	private final Button clearRegionsButton = new Button("Clear Regions");
	private final Button removeCurrentSelectionButton = new Button("Clear");
	private final Button combineRegionsButton = new Button("Combine Regions");
	private final Button previousButton = new Button("Previous");
	private final Button nextButton = new Button("Next");
	private final Button choosePdfButton = new Button("Choose PDF...");
	private final Button setExamButton = new Button("Set Exam");

	private final CheckBox fullWidthSelectionCheckBox = new CheckBox("Full width selection");

	private final ImageView previewView = new ImageView();
	private final ImageView pageView = new ImageView();
	private final ImageView combinedPreviewView = new ImageView();

	private final Label regionCountLabel = new Label("Regions: 0");
	private final Label pageLabel = new Label();
	private final Label selectedPdfLabel = new Label("No PDF selected");

	private final TextField providerField = new TextField();
	private final TextField yearField = new TextField();
	private final TextField assessmentField = new TextField();
	private final TextField bookletField = new TextField();

	private final VBox regionPreviewBox = new VBox(10);

	private int currentPageNumber = 1;
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
	}

	private void addRegionPreview(QuestionRegion region, int regionIndex) {
		try {
			BufferedImage image = questionExtractor.extractRegion(pdfSession, region);
			ImageView imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
			imageView.setPreserveRatio(true);
			imageView.setFitWidth(300);
			imageView.setSmooth(true);

			Label label = new Label(String.format("Region %d - Page %d", regionIndex + 1, region.pageNumber()));
			Button removeButton = new Button("Remove");
			removeButton.setOnAction(event -> removeRegion(regionIndex));
			HBox header = new HBox(10, label, removeButton);
			VBox regionBox = new VBox(5, header, imageView);
			regionPreviewBox.getChildren().add(regionBox);
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview region", e);
		}
	}

	private void choosePdf(Stage stage, Path pdfDataRoot) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Choose exam PDF");
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
			Alert alert = new Alert(Alert.AlertType.ERROR);
			alert.setHeaderText("PDF must be inside the configured PDF data folder.");
			alert.setContentText(rootPath.toString());
			alert.showAndWait();
			return;
		}

		try {
			if (pdfSession != null) {
				pdfSession.close();
			}
			pdfSession = PdfSession.open(selectedPath);
			currentPdfPath = selectedPath;
			booklet = null;
			pagePane.setCursor(Cursor.DEFAULT);
			currentPageNumber = 1;
			clearRegions();
			selectedPdfLabel.setText(selectedFile.getName());
			showCurrentPage();
		} catch (Exception e) {
			throw new RuntimeException("Unable to open PDF", e);
		}
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
		choosePdfButton.setOnAction(event -> choosePdf(stage, config.pdfDataRoot()));
		clearRegionsButton.setOnAction(event -> clearRegions());
		combineRegionsButton.setOnAction(event -> combineRegions());
		nextButton.setOnAction(event -> nextPage());
		previousButton.setOnAction(event -> previousPage());
		removeCurrentSelectionButton.setOnAction(event -> clearCurrentSelection());
		setExamButton.setOnAction(event -> setExamMetadata(config.pdfDataRoot()));
	}

	private void configureMouseSelection() {
		pageView.setOnMousePressed(event -> {
//			System.out.printf("PRESS %.1f, %.1f%n", event.getX(), event.getY());
			if (booklet == null) {
				return;
			}
			if (event.getButton() != MouseButton.PRIMARY) {
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
		});

		pageView.setOnMouseDragged(event -> {
//			System.out.printf("DRAG %.1f, %.1f%n", event.getX(), event.getY());
			if (booklet == null) {
				return;
			}
			if (!event.isPrimaryButtonDown()) {
				return;
			}
			double pageWidth = pageView.getBoundsInLocal().getWidth();
			double pageHeight = pageView.getBoundsInLocal().getHeight();

			double currentY = clamp(event.getY(), 0, pageHeight);
			double top = Math.min(selectionStartY, currentY);
			double height = Math.abs(currentY - selectionStartY);
			selectionRectangle.setY(top);
			selectionRectangle.setHeight(height);

			if (fullWidthSelectionCheckBox.isSelected()) {
				selectionRectangle.setX(0);
				selectionRectangle.setWidth(pageWidth);
			} else {
				double currentX = clamp(event.getX(), 0, pageWidth);
				double left = Math.min(selectionStartX, currentX);
				double width = Math.abs(currentX - selectionStartX);
				selectionRectangle.setX(left);
				selectionRectangle.setWidth(width);
			}
		});

		pageView.setOnMouseReleased(event -> {
			System.out.printf("RELEASE %.1f, %.1f%n", event.getX(), event.getY());
			if (booklet == null) {
				return;
			}
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			createQuestionRegion();
		});

	}

	private void configurePageView() {
		pageView.setPreserveRatio(true);
		pagePane.getChildren().add(pageView);
		pagePane.setCursor(Cursor.DEFAULT);
		fullWidthSelectionCheckBox.setSelected(true);
	}

	private void configurePreviewView() {
		previewView.setPreserveRatio(true);
		previewView.setFitWidth(320);
		previewView.setSmooth(true);

		combinedPreviewView.setPreserveRatio(true);
		combinedPreviewView.setFitWidth(320);
		combinedPreviewView.setSmooth(true);
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

	private HBox createExamBar() {
		providerField.setPromptText("QCAA");
		providerField.setPrefWidth(100);

		yearField.setPromptText("2024");
		yearField.setPrefWidth(70);

		assessmentField.setPromptText("External Assessment");
		assessmentField.setPrefWidth(180);

		bookletField.setPromptText("Paper 1 MCQ");
		bookletField.setPrefWidth(140);

		HBox examDetails = new HBox(8, new Label("Provider"), providerField, new Label("Year"), yearField,
				new Label("Assessment"), assessmentField, new Label("Booklet"), bookletField, setExamButton);

		examDetails.setAlignment(Pos.CENTER_LEFT);
		examDetails.setStyle(
				"-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;" + "-fx-border-radius: 3;" + "-fx-padding: 5;");

		HBox pdfDetails = new HBox(8, choosePdfButton, selectedPdfLabel);

		pdfDetails.setAlignment(Pos.CENTER_LEFT);
		pdfDetails.setStyle(
				"-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;" + "-fx-border-radius: 3;" + "-fx-padding: 5;");

		Label examDetailsLabel = new Label("Exam Details:");
		examDetailsLabel.setStyle("-fx-font-weight: bold");
		HBox examBar = new HBox(8, examDetailsLabel, examDetails, pdfDetails);

		examBar.setAlignment(Pos.CENTER_LEFT);
		examBar.setPadding(new Insets(6));

		return examBar;
	}

	private VBox createPdfWorkspace() {
		// PDF controls
		HBox pageControls = new HBox(10, previousButton, pageLabel, nextButton, fullWidthSelectionCheckBox);
		pageControls.setAlignment(Pos.CENTER);
		pageControls.setPadding(new Insets(6));
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

		CurriculumSelectorPane curriculumSelectorPane = new CurriculumSelectorPane(curriculumSelectionModel);

		ScrollPane regionsScrollPane = new ScrollPane(regionPreviewBox);

		regionsScrollPane.setFitToWidth(true);
		regionsScrollPane.setPrefViewportHeight(300);
		addRegionButton.setPadding(new Insets(2, 8, 2, 8));
		removeCurrentSelectionButton.setPadding(new Insets(2, 8, 2, 8));
		HBox currentSelectionButtons = new HBox(6, currentLabel, addRegionButton, removeCurrentSelectionButton);

		VBox previewPane = new VBox(10, curriculumSelectorPane, new Separator(), currentSelectionButtons, previewView,
				new Separator(), regionsLabel, regionCountLabel, regionsScrollPane, combineRegionsButton,
				new Separator(), combinedLabel, combinedPreviewView);
		previewPane.setPadding(new Insets(10));
		previewPane.setPrefWidth(330);
		previewPane.setMinWidth(330);
		previewPane.setMaxWidth(330);
		return previewPane;
	}

	private void createQuestionRegion() {
		double width = selectionRectangle.getWidth();
		double height = selectionRectangle.getHeight();

		// Ignore clicks and accidental microscopic drags
		if (width < MIN_SELECTION_SIZE || height < MIN_SELECTION_SIZE) {
			selectionRectangle.setVisible(false);
			return;
		}

		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		double normalizedX = selectionRectangle.getX() / pageWidth;
		double normalizedWidth = width / pageWidth;
		double normalizedY = selectionRectangle.getY() / pageHeight;
		double normalizedHeight = height / pageHeight;
		if (booklet == null) {
			clearCurrentSelection();

			Alert alert = new Alert(Alert.AlertType.WARNING);
			alert.setHeaderText("Exam details have not been set.");
			alert.setContentText("Enter the exam and booklet details, then click Set Exam.");
			alert.showAndWait();

			return;
		}
		currentSelection = new QuestionRegion(booklet, currentPageNumber, normalizedX, normalizedY, normalizedWidth,
				normalizedHeight);
		showRegionPreview(currentSelection);
	}

	private BorderPane createRootLayout(VBox pdfWorkspace) {
		BorderPane root = new BorderPane();
		root.setTop(createExamBar());
		root.setLeft(createPreviewPane());
		root.setCenter(pdfWorkspace);
		return root;
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

	private void nextPage() {
		if (currentPageNumber < pdfSession.getPageCount()) {
			currentPageNumber++;
			showCurrentPage();
		}
	}

	private void previousPage() {
		if (currentPageNumber > 1) {
			currentPageNumber--;
			showCurrentPage();
		}
	}

	private void refreshRegionPreviews() {
		regionPreviewBox.getChildren().clear();
		for (int i = 0; i < pendingRegions.size(); i++) {
			QuestionRegion region = pendingRegions.get(i);
			addRegionPreview(region, i);
		}
	}

	private void removeRegion(int regionIndex) {
		pendingRegions.remove(regionIndex);
		combinedPreviewView.setImage(null);
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
	}

	private void setExamMetadata(Path pdfDataRoot) {
		if (pdfSession == null) {
			showExamMetadataError("Choose a PDF first.");
			return;
		}

		String providerName = providerField.getText().trim();
		String yearText = yearField.getText().trim();
		String assessmentName = assessmentField.getText().trim();
		String bookletName = bookletField.getText().trim();

		if (providerName.isBlank() || yearText.isBlank() || assessmentName.isBlank() || bookletName.isBlank()) {
			showExamMetadataError("Complete all exam details.");
			return;
		}

		int year;
		try {
			year = Integer.parseInt(yearText);
		} catch (NumberFormatException e) {
			showExamMetadataError("Year must be a number.");
			return;
		}

		Subject subject = curriculumSelectionModel.getSubject();
		ExamProvider provider = new ExamProvider(1, providerName);
		Exam exam = new Exam(1, subject, provider, year, assessmentName);
		SourceDocument sourceDocument = new SourceDocument(1,
				pdfDataRoot.toAbsolutePath().normalize().relativize(currentPdfPath).toString());
		booklet = new ExamBooklet(1, exam, bookletName, sourceDocument);
		pagePane.setCursor(Cursor.CROSSHAIR);
	}

	private void setRegionCountLabel(int count) {
		regionCountLabel.setText(String.format("Regions: %d", count));
	}

	private void showCurrentPage() {
		clearCurrentSelection();
		try {
			BufferedImage bufferedImage = pdfSession.renderPage(currentPageNumber, DISPLAY_DPI);
			pageView.setImage(SwingFXUtils.toFXImage(bufferedImage, null));

			selectionRectangle.setVisible(false);

			pageLabel.setText(String.format("Page %d of %d", currentPageNumber, pdfSession.getPageCount()));
			previousButton.setDisable(currentPageNumber == 1);
			nextButton.setDisable(currentPageNumber == pdfSession.getPageCount());
		} catch (IOException e) {
			throw new RuntimeException("Unable to render PDF page", e);
		}
	}

	private void showExamMetadataError(String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText("Exam details are incomplete.");
		alert.setContentText(message);
		alert.showAndWait();
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
		Scene scene = new Scene(root, 1400, 840);
		primaryStage.setTitle("Exam Question Bank");
		primaryStage.setScene(scene);
		primaryStage.show();
	}

	private void showStartupError(String title, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle(title);
		alert.setHeaderText("The application could not start.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void startApplication(Stage primaryStage, ApplicationConfig config) throws IOException {
		curriculumSelectionModel = loadCurriculum(config);
		configureActions(primaryStage, config);
		configurePageView();
		configureSelectionRectangle();
		configureMouseSelection();
		configurePreviewView();
		configureToolTips();
		VBox pdfWorkspace = createPdfWorkspace();
		BorderPane root = createRootLayout(pdfWorkspace);
		showStage(primaryStage, root);
	}
}
