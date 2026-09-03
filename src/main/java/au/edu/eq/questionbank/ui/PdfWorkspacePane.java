package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
		EXAM("Page"), ANSWER("Answer page"), VIEWER("Page");

		private final String pageLabel;

		DocumentMode(String pageLabel) {
			this.pageLabel = pageLabel;
		}

		String pageLabel() {
			return pageLabel;
		}
	}

	record RegionSelection(DocumentMode documentMode, int pageNumber, double x, double y, double width, double height) {
	}

	private static final float DISPLAY_DPI = 120;
	private static final double MIN_SELECTION_SIZE = 5.0;
	private static final double PAGE_CONTROL_SPACING = 10.0;
	private static final Insets PAGE_CONTROLS_PADDING = new Insets(6, 6, 16, 6);

	private final Pane pagePane = new Pane();
	private final ImageView pageView = new ImageView();
	private final Rectangle selectionRectangle = new Rectangle();
	private final Button nextButton = new Button("Next");
	private final Button previousButton = new Button("Previous");
	private final Label pageLabel = new Label("No PDF selected");
	private final CheckBox fullWidthSelectionCheckBox = new CheckBox("Full width selection");

	private PdfSession examPdfSession;
	private PdfSession answerPdfSession;
	private PdfSession viewerPdfSession;
	private DocumentMode displayedDocument = DocumentMode.EXAM;
	private DocumentMode viewerReturnDocument = DocumentMode.EXAM;
	private int currentPageNumber = 1;
	private int viewerReturnPageNumber = 1;
	private int examPageNumber = 1;
	private int answerPageNumber = 1;
	private double selectionStartX;
	private double selectionStartY;
	private Predicate<DocumentMode> selectionAvailable = mode -> false;
	private Consumer<RegionSelection> selectionHandler = selection -> {
	};
	private Runnable pageChangeHandler = () -> {
	};

	/**
	 * Creates an empty PDF workspace with navigation and region-selection controls.
	 */
	PdfWorkspacePane() {
		configurePageView();
		configureSelectionRectangle();
		configureSelectionHandlers();
		configureNavigation();
		getChildren().addAll(createScrollPane(), createPageControls());
	}

	@Override
	public void close() throws Exception {
		Exception failure = null;
		try {
			failure = closeSession(examPdfSession, failure);
		} finally {
			examPdfSession = null;
		}
		try {
			failure = closeSession(answerPdfSession, failure);
		} finally {
			answerPdfSession = null;
		}
		try {
			failure = closeSession(viewerPdfSession, failure);
		} finally {
			viewerPdfSession = null;
		}
		if (failure != null) {
			throw failure;
		}
	}

	private Exception closeSession(PdfSession session, Exception existingFailure) {
		if (session == null) {
			return existingFailure;
		}
		try {
			session.close();
		} catch (Exception e) {
			if (existingFailure == null) {
				return e;
			}
			existingFailure.addSuppressed(e);
		}
		return existingFailure;
	}

	private double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private void clearDisplayedPage() {
		pageView.setImage(null);
		clearSelection();
		pageLabel.setText("No PDF selected");
		previousButton.setDisable(true);
		nextButton.setDisable(true);
	}

	private void closeExistingAnswerPdfSession() {
		if (answerPdfSession == null) {
			return;
		}

		try {
			answerPdfSession.close();
		} catch (Exception e) {
			throw new IllegalStateException("Unable to close the previous answer PDF", e);
		} finally {
			answerPdfSession = null;
		}
	}

	private void closeExistingViewerPdfSession() {
		if (viewerPdfSession == null) {
			return;
		}
		try {
			viewerPdfSession.close();
		} catch (Exception e) {
			throw new IllegalStateException("Unable to close the previous viewer PDF", e);
		} finally {
			viewerPdfSession = null;
		}
	}

	private void configureNavigation() {
		previousButton.setDisable(true);
		nextButton.setDisable(true);
		nextButton.setId("next-pdf-page");
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
		scrollPane.setMinHeight(0);
		VBox.setVgrow(scrollPane, Priority.ALWAYS);
		return scrollPane;
	}

	private PdfSession displayedPdfSession() {
		if (displayedDocument == DocumentMode.ANSWER) {
			return answerPdfSession;
		}
		if (displayedDocument == DocumentMode.VIEWER) {
			return viewerPdfSession;
		}
		return examPdfSession;
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
		if (selectionRectangle.getWidth() < MIN_SELECTION_SIZE || selectionRectangle.getHeight() < MIN_SELECTION_SIZE) {
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

	private void rememberCurrentPageNumber() {
		if (displayedDocument == DocumentMode.EXAM) {
			examPageNumber = currentPageNumber;
		} else if (displayedDocument == DocumentMode.ANSWER) {
			answerPageNumber = currentPageNumber;
		}
	}

	private void showCurrentPage() {
		pageChangeHandler.run();
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null) {
			return;
		}
		currentPageNumber = Math.max(1, Math.min(currentPageNumber, displayedSession.getPageCount()));

		try {
			BufferedImage bufferedImage = displayedSession.renderPage(currentPageNumber, DISPLAY_DPI);
			pageView.setImage(SwingFXUtils.toFXImage(bufferedImage, null));
			double aspectRatio = (double) bufferedImage.getHeight() / bufferedImage.getWidth();
			pagePane.prefHeightProperty().unbind();
			pagePane.prefHeightProperty().bind(pagePane.widthProperty().multiply(aspectRatio));
			pageLabel.setText(String.format("%s %d of %d", displayedDocument.pageLabel(), currentPageNumber,
					displayedSession.getPageCount()));
			previousButton.setDisable(currentPageNumber == 1);
			nextButton.setDisable(currentPageNumber == displayedSession.getPageCount());
			rememberCurrentPageNumber();
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

	/**
	 * Removes the visible pending selection rectangle.
	 */
	void clearSelection() {
		selectionRectangle.setVisible(false);
		selectionRectangle.setWidth(0);
		selectionRectangle.setHeight(0);
	}

	void closeViewerPdf() {
		closeExistingViewerPdfSession();
		displayedDocument = viewerReturnDocument;
		currentPageNumber = viewerReturnPageNumber;
		fullWidthSelectionCheckBox.setVisible(true);
		fullWidthSelectionCheckBox.setManaged(true);
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null) {
			clearDisplayedPage();
			return;
		}
		showCurrentPage();
	}

	PdfSession getAnswerPdfSession() {
		return answerPdfSession;
	}

	int getCurrentPageNumber() {
		return currentPageNumber;
	}

	DocumentMode getDisplayedDocument() {
		return displayedDocument;
	}

	PdfSession getExamPdfSession() {
		return examPdfSession;
	}

	boolean hasExamPdf() {
		return examPdfSession != null;
	}

	/**
	 * Opens and displays an answer PDF, replacing any previous answer session.
	 *
	 * @param path the answer PDF path
	 */
	void openAnswerPdf(Path path) {
		if (path == null) {
			throw new NullPointerException("path");
		}
		closeExistingAnswerPdfSession();

		try {
			answerPdfSession = PdfSession.open(path);
			displayedDocument = DocumentMode.ANSWER;
			answerPageNumber = 1;
			currentPageNumber = answerPageNumber;
			pagePane.setCursor(Cursor.CROSSHAIR);
			fullWidthSelectionCheckBox.setVisible(true);
			fullWidthSelectionCheckBox.setManaged(true);
			showCurrentPage();
		} catch (IOException e) {
			throw new RuntimeException("Unable to open answer PDF", e);
		}
	}

	/**
	 * Opens and displays an exam PDF, replacing any previous exam session.
	 *
	 * @param path the exam PDF path
	 */
	void openExamPdf(Path path) {
		if (path == null) {
			throw new NullPointerException("path");
		}
		try {
			if (examPdfSession != null) {
				try {
					examPdfSession.close();
				} finally {
					examPdfSession = null;
				}
			}
			examPdfSession = PdfSession.open(path);
			displayedDocument = DocumentMode.EXAM;
			examPageNumber = 1;
			currentPageNumber = examPageNumber;
			pagePane.setCursor(Cursor.DEFAULT);
			fullWidthSelectionCheckBox.setVisible(true);
			fullWidthSelectionCheckBox.setManaged(true);
			showCurrentPage();
		} catch (Exception e) {
			throw new RuntimeException("Unable to open PDF", e);
		}
	}

	void openViewerPdf(Path path) {
		if (path == null) {
			throw new NullPointerException("path");
		}
		closeExistingViewerPdfSession();
		try {
			viewerPdfSession = PdfSession.open(path);
			if (displayedDocument != DocumentMode.VIEWER) {
				viewerReturnDocument = displayedDocument;
				viewerReturnPageNumber = currentPageNumber;
			}
			displayedDocument = DocumentMode.VIEWER;
			currentPageNumber = 1;
			clearSelection();
			pagePane.setCursor(Cursor.DEFAULT);
			fullWidthSelectionCheckBox.setVisible(false);
			fullWidthSelectionCheckBox.setManaged(false);
			showCurrentPage();
		} catch (IOException e) {
			throw new RuntimeException("Unable to open PDF", e);
		}
	}

	/**
	 * Sets the callback invoked before a newly rendered page is shown.
	 *
	 * @param pageChangeHandler the page-change callback
	 */
	void setPageChangeHandler(Runnable pageChangeHandler) {
		if (pageChangeHandler == null) {
			throw new NullPointerException("pageChangeHandler");
		}
		this.pageChangeHandler = pageChangeHandler;
	}

	/**
	 * Sets the predicate controlling whether a document accepts region selection.
	 *
	 * @param selectionAvailable selection availability by document mode
	 */
	void setSelectionAvailable(Predicate<DocumentMode> selectionAvailable) {
		if (selectionAvailable == null) {
			throw new NullPointerException("selectionAvailable");
		}
		this.selectionAvailable = selectionAvailable;
	}

	void setSelectionCursorEnabled(boolean enabled) {
		pagePane.setCursor(enabled ? Cursor.CROSSHAIR : Cursor.DEFAULT);
	}

	/**
	 * Sets the consumer for completed proportional region selections.
	 *
	 * @param selectionHandler the completed-selection consumer
	 */
	void setSelectionHandler(Consumer<RegionSelection> selectionHandler) {
		if (selectionHandler == null) {
			throw new NullPointerException("selectionHandler");
		}
		this.selectionHandler = selectionHandler;
	}

	/**
	 * Switches between the already opened exam and answer documents.
	 *
	 * @param documentMode the document to display
	 */
	void showDocument(DocumentMode documentMode) {
		if (documentMode == null) {
			throw new NullPointerException("documentMode");
		}

		rememberCurrentPageNumber();
		displayedDocument = documentMode;

		if (documentMode == DocumentMode.EXAM) {
			currentPageNumber = examPageNumber;
		} else if (documentMode == DocumentMode.ANSWER) {
			currentPageNumber = answerPageNumber;
		}

		showCurrentPage();
	}
}
