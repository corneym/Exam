package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import au.edu.eq.questionbank.pdf.PdfSession;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Displays either an exam booklet PDF or an answer PDF and reports rectangular
 * page selections in proportional page coordinates.
 */
final class PdfWorkspacePane extends VBox implements AutoCloseable {

	enum DocumentMode {
		EXAM("Page"), ANSWER("Answer page");

		private final String pageLabel;

		DocumentMode(String pageLabel) {
			this.pageLabel = pageLabel;
		}

		String pageLabel() {
			return pageLabel;
		}
	}

	record RegionSelection(DocumentMode documentMode, int pageNumber, double x, double y, double width,
			double height) {
	}

	private static final float DISPLAY_DPI = 120;
	private static final double MIN_SELECTION_SIZE = 5.0;
	private static final double PAGE_CONTROL_SPACING = 10.0;
	private static final Insets PAGE_CONTROLS_PADDING = new Insets(6);

	private final Pane pagePane = new Pane();
	private final ImageView pageView = new ImageView();
	private final Rectangle selectionRectangle = new Rectangle();
	private final Button nextButton = new Button("Next");
	private final Button previousButton = new Button("Previous");
	private final Label pageLabel = new Label("No PDF selected");
	private final CheckBox fullWidthSelectionCheckBox = new CheckBox("Full width selection");

	private PdfSession examPdfSession;
	private PdfSession answerPdfSession;
	private DocumentMode displayedDocument = DocumentMode.EXAM;
	private int currentPageNumber = 1;
	private double selectionStartX;
	private double selectionStartY;
	private Predicate<DocumentMode> selectionAvailable = mode -> false;
	private Consumer<RegionSelection> selectionHandler = selection -> {
	};
	private Runnable pageChangeHandler = () -> {
	};

	PdfWorkspacePane() {
		configurePageView();
		configureSelectionRectangle();
		configureSelectionHandlers();
		configureNavigation();
		getChildren().addAll(createScrollPane(), createPageControls());
	}

	@Override
	public void close() throws Exception {
		if (examPdfSession != null) {
			examPdfSession.close();
		}
		if (answerPdfSession != null) {
			answerPdfSession.close();
		}
	}

	void clearSelection() {
		selectionRectangle.setVisible(false);
		selectionRectangle.setWidth(0);
		selectionRectangle.setHeight(0);
	}

	DocumentMode getDisplayedDocument() {
		return displayedDocument;
	}

	PdfSession getExamPdfSession() {
		return examPdfSession;
	}

	int getCurrentPageNumber() {
		return currentPageNumber;
	}

	boolean hasExamPdf() {
		return examPdfSession != null;
	}

	void openAnswerPdf(Path path) {
		Objects.requireNonNull(path, "path");
		closeExistingAnswerPdfSession();

		try {
			answerPdfSession = PdfSession.open(path);
			displayedDocument = DocumentMode.ANSWER;
			currentPageNumber = 1;
			pagePane.setCursor(Cursor.CROSSHAIR);
			showCurrentPage();
		} catch (IOException e) {
			throw new RuntimeException("Unable to open answer PDF", e);
		}
	}

	void openExamPdf(Path path) {
		Objects.requireNonNull(path, "path");
		try {
			if (examPdfSession != null) {
				examPdfSession.close();
			}
			examPdfSession = PdfSession.open(path);
			displayedDocument = DocumentMode.EXAM;
			currentPageNumber = 1;
			pagePane.setCursor(Cursor.DEFAULT);
			showCurrentPage();
		} catch (Exception e) {
			throw new RuntimeException("Unable to open PDF", e);
		}
	}

	void setPageChangeHandler(Runnable pageChangeHandler) {
		this.pageChangeHandler = Objects.requireNonNull(pageChangeHandler, "pageChangeHandler");
	}

	void setSelectionAvailable(Predicate<DocumentMode> selectionAvailable) {
		this.selectionAvailable = Objects.requireNonNull(selectionAvailable, "selectionAvailable");
	}

	void setSelectionHandler(Consumer<RegionSelection> selectionHandler) {
		this.selectionHandler = Objects.requireNonNull(selectionHandler, "selectionHandler");
	}

	void setSelectionCursorEnabled(boolean enabled) {
		pagePane.setCursor(enabled ? Cursor.CROSSHAIR : Cursor.DEFAULT);
	}

	void showDocument(DocumentMode documentMode) {
		displayedDocument = Objects.requireNonNull(documentMode, "documentMode");
		showCurrentPage();
	}

	private double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private void closeExistingAnswerPdfSession() {
		if (answerPdfSession == null) {
			return;
		}

		try {
			answerPdfSession.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private HBox createPageControls() {
		HBox pageControls = new HBox(PAGE_CONTROL_SPACING, previousButton, pageLabel, nextButton,
				fullWidthSelectionCheckBox);
		pageControls.setAlignment(Pos.CENTER);
		pageControls.setPadding(PAGE_CONTROLS_PADDING);
		return pageControls;
	}

	private ScrollPane createScrollPane() {
		ScrollPane scrollPane = new ScrollPane(pagePane);
		scrollPane.setFitToWidth(true);
		scrollPane.setFitToHeight(false);
		VBox.setVgrow(scrollPane, Priority.ALWAYS);
		return scrollPane;
	}

	private void configureNavigation() {
		previousButton.setDisable(true);
		nextButton.setDisable(true);
		previousButton.setOnAction(event -> previousPage());
		nextButton.setOnAction(event -> nextPage());
	}

	private void configurePageView() {
		pageView.setId("pdf-page-view");
		pageView.setPreserveRatio(true);
		pageView.fitWidthProperty().bind(pagePane.widthProperty());
		pagePane.getChildren().add(pageView);
		pagePane.setCursor(Cursor.DEFAULT);
		fullWidthSelectionCheckBox.setSelected(true);
	}

	private void configureSelectionHandlers() {
		pageView.setOnMousePressed(this::handleSelectionPressed);
		pageView.setOnMouseDragged(this::handleSelectionDragged);
		pageView.setOnMouseReleased(this::handleSelectionReleased);
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

	private PdfSession displayedPdfSession() {
		return displayedDocument == DocumentMode.ANSWER ? answerPdfSession : examPdfSession;
	}

	private void handleSelectionDragged(MouseEvent event) {
		if (!event.isPrimaryButtonDown() || !selectionAvailable.test(displayedDocument)) {
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
		if (event.getButton() != MouseButton.PRIMARY || !selectionAvailable.test(displayedDocument)) {
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
		if (event.getButton() != MouseButton.PRIMARY || !selectionAvailable.test(displayedDocument)) {
			return;
		}
		if (selectionRectangle.getWidth() < MIN_SELECTION_SIZE
				|| selectionRectangle.getHeight() < MIN_SELECTION_SIZE) {
			selectionRectangle.setVisible(false);
			return;
		}

		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		selectionHandler.accept(new RegionSelection(displayedDocument, currentPageNumber,
				selectionRectangle.getX() / pageWidth, selectionRectangle.getY() / pageHeight,
				selectionRectangle.getWidth() / pageWidth, selectionRectangle.getHeight() / pageHeight));
	}

	private void nextPage() {
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession != null && currentPageNumber < displayedSession.getPageCount()) {
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
		pageChangeHandler.run();
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null) {
			return;
		}

		try {
			BufferedImage bufferedImage = displayedSession.renderPage(currentPageNumber, DISPLAY_DPI);
			pageView.setImage(SwingFXUtils.toFXImage(bufferedImage, null));
			pageLabel.setText(String.format("%s %d of %d", displayedDocument.pageLabel(), currentPageNumber,
					displayedSession.getPageCount()));
			previousButton.setDisable(currentPageNumber == 1);
			nextButton.setDisable(currentPageNumber == displayedSession.getPageCount());
		} catch (IOException e) {
			throw new RuntimeException("Unable to render PDF page", e);
		}
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
}
