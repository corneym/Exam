package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.CurriculumRepositoryLoader;
import au.edu.eq.questionbank.importer.CurriculumSource;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

public class QuestionBankApplication extends Application {

	private static final float DISPLAY_DPI = 120;
	private static final double MIN_SELECTION_SIZE = 5.0;

	public static void main(String[] args) {
		launch(args);
	}

	private PdfSession pdfSession;

	private int currentPageNumber = 1;
	private final ImageView pageView = new ImageView();
	private final Pane pagePane = new Pane();
	private final Rectangle selectionRectangle = new Rectangle();
	private double selectionStartX;
	private double selectionStartY;
	private final QuestionExtractor questionExtractor = new QuestionExtractor();
	private final ImageView previewView = new ImageView();
	private final List<QuestionRegion> pendingRegions = new ArrayList<>();
	private QuestionRegion currentSelection;
	private final Label regionCountLabel = new Label("Regions: 0");
	private final Button addRegionButton = new Button("Add");
	private final Button clearRegionsButton = new Button("Clear Regions");
	private final Button previewQuestionButton = new Button("Preview Question");

	private final Button removeCurrentSelectionButton = new Button("Clear");

	private final VBox regionPreviewBox = new VBox(10);
	private CurriculumSelectionModel curriculumSelectionModel;

	private final ImageView combinedPreviewView = new ImageView();
	private final Button combineRegionsButton = new Button("Combine Regions");

	private final Label pageLabel = new Label();

	private final Button previousButton = new Button("Previous");
	private final Button nextButton = new Button("Next");

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

	private void startApplication(Stage primaryStage, ApplicationConfig config) throws IOException {
		curriculumSelectionModel = loadCurriculum(config);
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path pdfPath = pdfStore.resolve("chemistry/QCAA/2024/" + "snr_chemistry_24_ea_p1_mc_question.pdf");
		pdfSession = PdfSession.open(pdfPath);

		configurePageView();
		configureSelectionRectangle();
		configureMouseSelection();
		configurePreviewView();

		ScrollPane scrollPane = new ScrollPane(pagePane);
		scrollPane.setFitToWidth(false);
		scrollPane.setFitToHeight(false);

		previousButton.setOnAction(event -> previousPage());
		nextButton.setOnAction(event -> nextPage());
		addRegionButton.setOnAction(event -> addCurrentRegion());
		clearRegionsButton.setOnAction(event -> clearRegions());
		combineRegionsButton.setOnAction(event -> combineRegions());
		removeCurrentSelectionButton.setOnAction(event -> clearCurrentSelection());

		// bottom pane controls
		HBox controls = new HBox(10, previousButton, pageLabel, nextButton, clearRegionsButton, regionCountLabel);
		controls.setAlignment(Pos.CENTER);

		BorderPane root = new BorderPane();
		root.setCenter(scrollPane);
		root.setRight(createPreviewPane());
		root.setBottom(controls);

		Scene scene = new Scene(root, 1400, 840);
		primaryStage.setTitle("Exam Question Bank");
		primaryStage.setScene(scene);
		primaryStage.show();

		showCurrentPage();
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

	private void configureMouseSelection() {
		pageView.setOnMousePressed(event -> {
//			System.out.printf("PRESS %.1f, %.1f%n", event.getX(), event.getY());
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
//			selectionStartX = clamp(event.getX(), 0, pagePane.getWidth());
			selectionStartY = clamp(event.getY(), 0, pagePane.getHeight());
			selectionRectangle.setX(0);
			selectionRectangle.setY(selectionStartY);
			selectionRectangle.setWidth(pageView.getBoundsInLocal().getWidth());
			selectionRectangle.setHeight(0);
			selectionRectangle.setVisible(true);
		});

		pageView.setOnMouseDragged(event -> {
//			System.out.printf("DRAG %.1f, %.1f%n", event.getX(), event.getY());
			if (!event.isPrimaryButtonDown()) {
				return;
			}
//			double currentX = clamp(event.getX(), 0, pagePane.getWidth());
			double currentY = clamp(event.getY(), 0, pagePane.getHeight());
//			double left = Math.min(selectionStartX, currentX);
			double top = Math.min(selectionStartY, currentY);
//			double width = Math.abs(currentX - selectionStartX);
			double height = Math.abs(currentY - selectionStartY);
//			selectionRectangle.setX(left);
			selectionRectangle.setY(top);
//			selectionRectangle.setWidth(width);
			selectionRectangle.setHeight(height);
		});

		pageView.setOnMouseReleased(event -> {
			System.out.printf("RELEASE %.1f, %.1f%n", event.getX(), event.getY());
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			createQuestionRegion();
		});

	}

	private void configurePageView() {
		pageView.setPreserveRatio(true);
		pagePane.getChildren().add(pageView);
		pagePane.setCursor(Cursor.CROSSHAIR);
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
//		selectionRectangle.setFill(Color.rgb(255, 120, 215, 0.30));
		selectionRectangle.setStroke(Color.rgb(0, 90, 180));
//		selectionRectangle.setStroke(Color.RED);
		selectionRectangle.setStrokeWidth(2);
//		selectionRectangle.setStrokeWidth(3);
		selectionRectangle.setVisible(false);
		selectionRectangle.setMouseTransparent(true);
		pagePane.getChildren().add(selectionRectangle);
		selectionRectangle.toFront();
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
		previewPane.setPrefWidth(360);
		return previewPane;
	}

	private void createQuestionRegion() {
//		double width = selectionRectangle.getWidth();
		double height = selectionRectangle.getHeight();

		// Ignore clicks and accidental microscopic drags
		if (height < MIN_SELECTION_SIZE) {
			selectionRectangle.setVisible(false);
			return;
		}

//		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pagePane.getBoundsInLocal().getHeight();
//		double normalizedX = selectionRectangle.getX() / pageWidth;
		double normalizedY = selectionRectangle.getY() / pageHeight;
//		double normalizedWidth = width / pageWidth;
		double normalizedHeight = height / pageHeight;
		currentSelection = new QuestionRegion(currentPageNumber, 0.0, normalizedY, 1.0, normalizedHeight);
		System.out.println("Selected region: ");
		System.out.println(currentSelection);
		showRegionPreview(currentSelection);
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

	private void previewQuestion() {
		if (pendingRegions.isEmpty()) {
			return;
		}
		try {
			BufferedImage preview = questionExtractor.extractRegions(pdfSession, pendingRegions);
			previewView.setImage(SwingFXUtils.toFXImage(preview, null));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview question", e);
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

	private void setRegionCountLabel(int count) {
		regionCountLabel.setText(String.format("Regions: %d", count));
	}

	private void showCurrentPage() {
		clearCurrentSelection();
		try {
			BufferedImage bufferedImage = pdfSession.renderPage(currentPageNumber, DISPLAY_DPI);
			pageView.setImage(SwingFXUtils.toFXImage(bufferedImage, null));

			// make the pane the same size as the rendered image --> mouse coordinates
			// correspond to image coordinates
			pagePane.setPrefSize(bufferedImage.getWidth(), bufferedImage.getHeight());
			pagePane.setMinSize(bufferedImage.getWidth(), bufferedImage.getHeight());
			pagePane.setMaxSize(bufferedImage.getWidth(), bufferedImage.getHeight());

			selectionRectangle.setVisible(false);

			pageLabel.setText(String.format("Page %d of %d", currentPageNumber, pdfSession.getPageCount()));
			previousButton.setDisable(currentPageNumber == 1);
			nextButton.setDisable(currentPageNumber == pdfSession.getPageCount());
		} catch (IOException e) {
			throw new RuntimeException("Unable to render PDF page", e);
		}
	}

	private void showRegionPreview(QuestionRegion region) {

		try {
			BufferedImage preview = questionExtractor.extractRegion(pdfSession, region);
			previewView.setImage(SwingFXUtils.toFXImage(preview, null));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview selected region", e);
		}
	}

	private void showStartupError(String title, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle(title);
		alert.setHeaderText("The application could not start.");
		alert.setContentText(message);
		alert.showAndWait();
	}
}
