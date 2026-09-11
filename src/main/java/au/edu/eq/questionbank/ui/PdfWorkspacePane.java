package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import au.edu.eq.questionbank.pdf.PdfSession;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * Displays either an exam booklet PDF or an answer PDF and reports rectangular
 * page selections in proportional page coordinates.
 */
final class PdfWorkspacePane extends VBox implements AutoCloseable {

	private static final float DISPLAY_DPI = 120;
	private static final double MIN_SELECTION_SIZE = 5.0;
	private static final double PAGE_CONTROL_SPACING = 10.0;
	private static final Insets PAGE_CONTROLS_PADDING = new Insets(6, 6, 16, 6);
	private static final double ANCHOR_MARKER_RADIUS = 5.0;
	private final Pane pagePane = new Pane();
	private final ImageView pageView = new ImageView();
	private final Rectangle selectionRectangle = new Rectangle();
	private final Button nextButton = new Button("Next");
	private final Button previousButton = new Button("Previous");
	private final Label pageLabel = new Label("No PDF selected");
	private final CheckBox fullWidthSelectionCheckBox = new CheckBox("Full width selection");
	private PdfSession examPdfSession;
	private PdfSession answerPdfSession;
	private Path answerPdfPath;
	private long documentRequest;
	private boolean closed;
	private PdfSession viewerPdfSession;
	private DocumentMode displayedDocument = DocumentMode.EXAM;
	private DocumentMode viewerReturnDocument = DocumentMode.EXAM;
	private int currentPageNumber = 1;
	private int viewerReturnPageNumber = 1;
	private int examPageNumber = 1;
	private int answerPageNumber = 1;
	private double selectionStartX;
	private double selectionStartY;
	private Predicate<DocumentMode> selectionAvailable = _ -> false;
	private Consumer<RegionSelection> selectionHandler = _ -> {
	};
	private BooleanSupplier pageNavigationAllowed = () -> true;
	private final Circle selectionAnchorMarker = new Circle(ANCHOR_MARKER_RADIUS);
	private boolean anchoredSelectionActive;
	private double anchoredSelectionX;
	private double anchoredSelectionY;
	private int anchoredSelectionPageNumber;
	private DocumentMode anchoredSelectionDocument;
	private boolean anchoredSelectionFullWidth;

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
		closed = true;
		documentRequest++;
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

	/**
	 * Removes the visible pending selection rectangle.
	 */
	void clearSelection() {
		clearAnchoredSelectionState();
		selectionRectangle.setVisible(false);
		selectionRectangle.setWidth(0);
		selectionRectangle.setHeight(0);
	}

	/**
	 * Closes the standalone viewer session and restores the previously displayed
	 * exam or answer page.
	 */
	void closeViewerPdf() {
		documentRequest++;
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

	/**
	 * @return the borrowed answer session, or {@code null}; callers must not close
	 *         it
	 */
	PdfSession getAnswerPdfSession() {
		return answerPdfSession;
	}

	/**
	 * @return the one-based page currently selected in the workspace
	 */
	int getCurrentPageNumber() {
		return currentPageNumber;
	}

	/**
	 * @return the active exam, answer or viewer document mode
	 */
	DocumentMode getDisplayedDocument() {
		return displayedDocument;
	}

	/**
	 * @return the borrowed exam session, or {@code null}; callers must not close it
	 */
	PdfSession getExamPdfSession() {
		return examPdfSession;
	}

	/**
	 * @return whether an exam PDF session is open
	 */
	boolean hasExamPdf() {
		return examPdfSession != null;
	}

	/**
	 * Opens and displays an answer PDF, replacing any previous answer session.
	 *
	 * @param path the answer PDF path
	 */
	void openAnswerPdf(Path path) {
		documentRequest++;
		if (path == null) {
			throw new NullPointerException("path");
		}
		closeExistingAnswerPdfSession();
		try {
			answerPdfSession = PdfSession.open(path);
			answerPdfPath = path.toAbsolutePath().normalize();
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
	 * Loads the answer document after a save without rendering on the FX thread.
	 * The worker owns a fresh session until it is handed to the UI, so it never
	 * shares a PDFBox session with selection extraction or navigation.
	 */
	void openAnswerPdfAsync(Path path, Consumer<Throwable> completed) {
		if (closed) {
			completed.accept(new CancellationException("PDF workspace has closed"));
			return;
		}
		Path normalizedPath = path.toAbsolutePath().normalize();
		long request = ++documentRequest;
		boolean sameDocument = answerPdfSession != null && normalizedPath.equals(answerPdfPath);
		if (sameDocument && displayedDocument == DocumentMode.ANSWER) {
			completed.accept(null);
			return;
		}
		int requestedPage = sameDocument ? answerPageNumber : 1;
		Task<LoadedAnswerPage> task = new Task<>() {
			@Override
			protected LoadedAnswerPage call() throws Exception {
				PdfSession session = PdfSession.open(normalizedPath);
				try {
					int page = Math.min(requestedPage, session.getPageCount());
					BufferedImage rendered = session.renderPage(page, DISPLAY_DPI);
					return new LoadedAnswerPage(session, SwingFXUtils.toFXImage(rendered, null), page);
				} catch (Exception | Error failure) {
					try {
						session.close();
					} catch (Exception closeFailure) {
						failure.addSuppressed(closeFailure);
					}
					throw failure;
				}
			}
		};
		task.setOnSucceeded(_ -> {
			LoadedAnswerPage loaded = task.getValue();
			if (request != documentRequest) {
				CancellationException stale = new CancellationException("PDF view changed during loading");
				Exception closeFailure = closeSession(loaded.session(), null);
				if (closeFailure != null) {
					stale.addSuppressed(closeFailure);
				}
				completed.accept(stale);
				return;
			}
			try {
				closeExistingAnswerPdfSession();
			} catch (RuntimeException failure) {
				Exception closeFailure = closeSession(loaded.session(), null);
				if (closeFailure != null) {
					failure.addSuppressed(closeFailure);
				}
				completed.accept(failure);
				return;
			}
			rememberCurrentPageNumber();
			clearSelection();
			answerPdfSession = loaded.session();
			answerPdfPath = normalizedPath;
			displayedDocument = DocumentMode.ANSWER;
			answerPageNumber = loaded.pageNumber();
			currentPageNumber = answerPageNumber;
			pagePane.setCursor(Cursor.CROSSHAIR);
			fullWidthSelectionCheckBox.setVisible(true);
			fullWidthSelectionCheckBox.setManaged(true);
			applyPageImage(loaded.image(), answerPdfSession);
			completed.accept(null);
		});
		task.setOnFailed(_ -> completed.accept(request == documentRequest ? task.getException()
				: new CancellationException("PDF view changed during loading")));
		Thread.ofVirtual().name("answer-pdf-load").start(task);
	}

	private record LoadedAnswerPage(PdfSession session, Image image, int pageNumber) {
	}

	/**
	 * Opens and displays an exam PDF, replacing any previous exam session.
	 *
	 * @param path the exam PDF path
	 */
	void openExamPdf(Path path) {
		documentRequest++;
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

	/**
	 * Opens a standalone PDF for viewing without making it an exam or answer
	 * source.
	 *
	 * @param path the PDF to open
	 */
	void openViewerPdf(Path path) {
		documentRequest++;
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
	 * Sets the guard consulted before Previous or Next changes the displayed PDF
	 * page.
	 *
	 * @param pageNavigationAllowed returns {@code true} when navigation may proceed
	 */
	void setPageNavigationAllowed(BooleanSupplier pageNavigationAllowed) {
		if (pageNavigationAllowed == null) {
			throw new NullPointerException("pageNavigationAllowed");
		}
		this.pageNavigationAllowed = pageNavigationAllowed;
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

	/**
	 * Updates the cursor to reflect selection availability.
	 *
	 * @param enabled whether region selection is enabled
	 */
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
		documentRequest++;
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

	private void beginAnchoredSelection(MouseEvent event) {
		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		if (pageWidth <= 0 || pageHeight <= 0) {
			return;
		}
		double x = clamp(event.getX(), 0, pageWidth);
		double y = clamp(event.getY(), 0, pageHeight);
		anchoredSelectionActive = true;
		anchoredSelectionX = x / pageWidth;
		anchoredSelectionY = y / pageHeight;
		anchoredSelectionPageNumber = currentPageNumber;
		anchoredSelectionDocument = displayedDocument;
		anchoredSelectionFullWidth = fullWidthSelectionCheckBox.isSelected();
		selectionAnchorMarker.setCenterX(x);
		selectionAnchorMarker.setCenterY(y);
		selectionAnchorMarker.setVisible(true);
		if (anchoredSelectionFullWidth) {
			selectionRectangle.setX(0);
			selectionRectangle.setWidth(pageWidth);
		} else {
			selectionRectangle.setX(x);
			selectionRectangle.setWidth(0);
		}
		selectionRectangle.setY(y);
		selectionRectangle.setHeight(0);
		selectionRectangle.setVisible(true);
		selectionRectangle.toFront();
		selectionAnchorMarker.toFront();
	}

	private double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private void clearAnchoredSelectionState() {
		anchoredSelectionActive = false;
		anchoredSelectionDocument = null;
		anchoredSelectionPageNumber = 0;
		anchoredSelectionX = 0;
		anchoredSelectionY = 0;
		anchoredSelectionFullWidth = false;
		selectionAnchorMarker.setVisible(false);
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

	private void completeAnchoredSelection(MouseEvent event) {
		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		if (pageWidth <= 0 || pageHeight <= 0) {
			return;
		}
		double anchorX = anchoredSelectionX * pageWidth;
		double anchorY = anchoredSelectionY * pageHeight;
		double currentX = clamp(event.getX(), 0, pageWidth);
		double currentY = clamp(event.getY(), 0, pageHeight);
		selectionRectangle.setY(Math.min(anchorY, currentY));
		selectionRectangle.setHeight(Math.abs(currentY - anchorY));
		if (anchoredSelectionFullWidth) {
			selectionRectangle.setX(0);
			selectionRectangle.setWidth(pageWidth);
		} else {
			selectionRectangle.setX(Math.min(anchorX, currentX));
			selectionRectangle.setWidth(Math.abs(currentX - anchorX));
		}
		if (selectionRectangle.getWidth() < MIN_SELECTION_SIZE || selectionRectangle.getHeight() < MIN_SELECTION_SIZE) {
			selectionAnchorMarker.setVisible(true);
			return;
		}
		clearAnchoredSelectionState();
		selectionRectangle.setVisible(true);
		publishSelection();
	}

	private void configureNavigation() {
		previousButton.setDisable(true);
		nextButton.setDisable(true);
		nextButton.setId("next-pdf-page");
		previousButton.setOnAction(_ -> previousPage());
		nextButton.setOnAction(_ -> nextPage());
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
		pageView.setOnMouseClicked(this::handleSelectionClicked);
	}

	private void configureSelectionRectangle() {
		selectionRectangle.setFill(Color.rgb(0, 120, 215, 0.15));
		selectionRectangle.setStroke(Color.rgb(0, 90, 180));
		selectionRectangle.setStrokeWidth(2);
		selectionRectangle.setVisible(false);
		selectionRectangle.setMouseTransparent(true);
		selectionAnchorMarker.setFill(Color.rgb(0, 90, 180));
		selectionAnchorMarker.setStroke(Color.WHITE);
		selectionAnchorMarker.setStrokeWidth(1.5);
		selectionAnchorMarker.setVisible(false);
		selectionAnchorMarker.setMouseTransparent(true);
		pagePane.getChildren().addAll(selectionRectangle, selectionAnchorMarker);
		selectionRectangle.toFront();
		selectionAnchorMarker.toFront();
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

	private void handleSelectionClicked(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2
				|| !selectionAvailable.test(displayedDocument)) {
			return;
		}
		if (!anchoredSelectionActive || anchoredSelectionDocument != displayedDocument
				|| anchoredSelectionPageNumber != currentPageNumber) {
			beginAnchoredSelection(event);
		} else {
			completeAnchoredSelection(event);
		}
		event.consume();
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
			if (!anchoredSelectionActive) {
				selectionRectangle.setVisible(false);
			}
			return;
		}
		clearAnchoredSelectionState();
		publishSelection();
	}

	private void nextPage() {
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null || currentPageNumber >= displayedSession.getPageCount()) {
			return;
		}
		if (!pageNavigationAllowed.getAsBoolean()) {
			return;
		}
		currentPageNumber++;
		showCurrentPage();
	}

	private void previousPage() {
		if (currentPageNumber <= 1) {
			return;
		}
		if (!pageNavigationAllowed.getAsBoolean()) {
			return;
		}
		currentPageNumber--;
		showCurrentPage();
	}

	private void publishSelection() {
		double pageWidth = pageView.getBoundsInLocal().getWidth();
		double pageHeight = pageView.getBoundsInLocal().getHeight();
		selectionHandler.accept(new RegionSelection(displayedDocument, currentPageNumber,
				selectionRectangle.getX() / pageWidth, selectionRectangle.getY() / pageHeight,
				selectionRectangle.getWidth() / pageWidth, selectionRectangle.getHeight() / pageHeight));
	}

	private void rememberCurrentPageNumber() {
		if (displayedDocument == DocumentMode.EXAM) {
			examPageNumber = currentPageNumber;
		} else if (displayedDocument == DocumentMode.ANSWER) {
			answerPageNumber = currentPageNumber;
		}
	}

	private void showCurrentPage() {
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null) {
			return;
		}
		currentPageNumber = Math.max(1, Math.min(currentPageNumber, displayedSession.getPageCount()));
		if (anchoredSelectionActive && (anchoredSelectionDocument != displayedDocument
				|| anchoredSelectionPageNumber != currentPageNumber)) {
			clearSelection();
		}
		try {
			BufferedImage bufferedImage = displayedSession.renderPage(currentPageNumber, DISPLAY_DPI);
			applyPageImage(SwingFXUtils.toFXImage(bufferedImage, null), displayedSession);
		} catch (IOException e) {
			throw new RuntimeException("Unable to render PDF page", e);
		}
	}

	private void applyPageImage(Image image, PdfSession session) {
		pageView.setImage(image);
		double aspectRatio = image.getHeight() / image.getWidth();
		pagePane.prefHeightProperty().unbind();
		pagePane.prefHeightProperty().bind(pagePane.widthProperty().multiply(aspectRatio));
		pageLabel.setText(String.format("%s %d of %d", displayedDocument.pageLabel(), currentPageNumber,
				session.getPageCount()));
		previousButton.setDisable(currentPageNumber == 1);
		nextButton.setDisable(currentPageNumber == session.getPageCount());
		rememberCurrentPageNumber();
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

	/**
	 * A rectangle in displayed-page coordinates, with a top-left origin.
	 * Coordinates are proportional to the rendered page, independent of DPI; page
	 * numbers are one-based. The document mode identifies the source workflow.
	 *
	 * @param documentMode the source document mode
	 * @param pageNumber   the one-based source page
	 * @param x            the proportional left edge
	 * @param y            the proportional top edge
	 * @param width        the proportional width
	 * @param height       the proportional height
	 */
	record RegionSelection(DocumentMode documentMode, int pageNumber, double x, double y, double width, double height) {
	}
}
