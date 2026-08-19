package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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

	private final Label pageLabel = new Label();

	private final Button previousButton = new Button("Previous");
	private final Button nextButton = new Button("Next");

	@Override
	public void start(Stage stage) throws Exception {
		ApplicationConfig config = ApplicationConfig.load(Path.of("questionbank.properties"));
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

		HBox controls = new HBox(10, previousButton, pageLabel, nextButton);
		controls.setAlignment(Pos.CENTER);

		BorderPane root = new BorderPane();
		root.setCenter(scrollPane);
		root.setRight(createPreviewPane());
		root.setBottom(controls);

		Scene scene = new Scene(root, 1300, 800);
		stage.setTitle("Exam Question Bank");
		stage.setScene(scene);
		stage.show();

		showCurrentPage();
	}

	@Override
	public void stop() throws Exception {
		if (pdfSession != null) {
			pdfSession.close();
		}
	}

	private double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private void configureMouseSelection() {
		pageView.setOnMousePressed(event -> {
			System.out.printf("PRESS %.1f, %.1f%n", event.getX(), event.getY());
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			selectionStartX = clamp(event.getX(), 0, pagePane.getWidth());
			selectionStartY = clamp(event.getY(), 0, pagePane.getHeight());
			selectionRectangle.setX(selectionStartX);
			selectionRectangle.setY(selectionStartY);
			selectionRectangle.setWidth(0);
			selectionRectangle.setHeight(0);
			selectionRectangle.setVisible(true);
		});

		pageView.setOnMouseDragged(event -> {
			System.out.printf("DRAG %.1f, %.1f%n", event.getX(), event.getY());
			if (!event.isPrimaryButtonDown()) {
				return;
			}
			double currentX = clamp(event.getX(), 0, pagePane.getWidth());
			double currentY = clamp(event.getY(), 0, pagePane.getHeight());
			double left = Math.min(selectionStartX, currentX);
			double top = Math.min(selectionStartY, currentY);
			double width = Math.abs(currentX - selectionStartX);
			double height = Math.abs(currentY - selectionStartY);
			selectionRectangle.setX(left);
			selectionRectangle.setY(top);
			selectionRectangle.setWidth(width);
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
	}

	private void configureSelectionRectangle() {
		selectionRectangle.setFill(Color.rgb(0, 120, 215, 0.15));
		selectionRectangle.setFill(Color.rgb(255, 120, 215, 0.30));
		selectionRectangle.setStroke(Color.rgb(0, 90, 180));
		selectionRectangle.setStroke(Color.RED);
		selectionRectangle.setStrokeWidth(2);
		selectionRectangle.setStrokeWidth(3);
		selectionRectangle.setVisible(false);
		selectionRectangle.setMouseTransparent(true);
		pagePane.getChildren().add(selectionRectangle);
		selectionRectangle.toFront();
	}

	private VBox createPreviewPane() {

		Label previewLabel = new Label("Selection preview");
		ScrollPane previewScrollPane = new ScrollPane(previewView);

		previewScrollPane.setFitToWidth(true);
		previewScrollPane.setPrefWidth(360);

		VBox previewPane = new VBox(10, previewLabel, previewScrollPane);
		previewPane.setPadding(new Insets(10));
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
		double pageHeight = pagePane.getBoundsInLocal().getHeight();
		double normalizedX = selectionRectangle.getX() / pageWidth;
		double normalizedY = selectionRectangle.getY() / pageHeight;
		double normalizedWidth = width / pageWidth;
		double normalizedHeight = height / pageHeight;
		QuestionRegion region = new QuestionRegion(currentPageNumber, normalizedX, normalizedY, normalizedWidth,
				normalizedHeight);
		System.out.println("Selected region: ");
		System.out.println(region);
		showRegionPreview(region);
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

	private void showCurrentPage() {
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
}
