package au.edu.eq.questionbank.ui.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
public final class PdfWorkspacePane extends VBox implements AutoCloseable {

	private static final int PAGE_FIELD_COLUMNS = 4;
	private static final int PAGE_FIELD_MAX_WIDTH = 70;
	private static final Color SELECTION_FILL = Color.rgb(0, 120, 215, 0.15);
	private static final Color SELECTION_COLOR = Color.rgb(0, 90, 180);
	private static final int SELECTION_STROKE_WIDTH = 2;
	private static final double ANCHOR_STROKE_WIDTH = 1.5;
	private static final int ANCHOR_CLICK_COUNT = 2;
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
	private final TextField pageNumberField = new TextField();
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
	private IntConsumer pageChangedHandler = _ -> {
	};
	private Runnable selectionModeChangedHandler = () -> {
	};
	private BooleanSupplier pageNavigationAllowed = () -> true;
	private final Circle selectionAnchorMarker = new Circle(ANCHOR_MARKER_RADIUS);
	private boolean anchoredSelectionActive;
	private double anchoredSelectionX;
	private double anchoredSelectionY;
	private int anchoredSelectionPageNumber;
	private DocumentMode anchoredSelectionDocument;
	private boolean anchoredSelectionFullWidth;

	// Report user-driven cancellation separately from programmatic clearSelection()
	// so the application can clear the logical owner without creating a callback
	// loop.
	private Runnable selectionCancelledHandler = () -> {
	};

	/**
	 * Creates an empty PDF workspace with navigation and region-selection controls.
	 */
	public PdfWorkspacePane() {
		configurePageView();
		configureSelectionRectangle();
		configureSelectionHandlers();
		configureNavigation();
		getChildren().addAll(createScrollPane(), createPageControls());
	}

	/**
	 * Removes the visible pending selection rectangle.
	 */
	public void clearSelection() {
		clearAnchoredSelectionState();
		selectionRectangle.setVisible(false);
		selectionRectangle.setWidth(0);
		selectionRectangle.setHeight(0);
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
	 * Closes the managed Exam and Answer PDF sessions without closing the workspace
	 * itself.
	 * <p>
	 * Exam metadata correction uses this before relocating managed files so no open
	 * PDFBox document can retain an operating-system file handle on a source that
	 * must be moved.
	 */
	public void closeManagedPdfSessions() {
		documentRequest++;
		boolean managedDocumentDisplayed = displayedDocument == DocumentMode.EXAM
				|| displayedDocument == DocumentMode.ANSWER;
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
			answerPdfPath = null;
		}
		clearSelection();
		if (managedDocumentDisplayed) {
			clearDisplayedPage();
		}
		if (failure != null) {
			throw new IllegalStateException("Unable to close managed PDF documents before relocation", failure);
		}
	}

	/**
	 * Closes the standalone viewer session and restores the previously displayed
	 * exam or answer page.
	 */
	public void closeViewerPdf() {
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
	 * Extracts text from the PDF page currently displayed by this workspace.
	 *
	 * @return text extracted from the current one-based page
	 * @throws IOException           if PDFBox cannot extract the page text
	 * @throws IllegalStateException if no PDF is currently displayed
	 */
	public String extractDisplayedPageText() throws IOException {
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null) {
			throw new IllegalStateException("No PDF is currently displayed");
		}
		return displayedSession.extractPageText(currentPageNumber);
	}

	/**
	 * @return the borrowed answer session, or {@code null}; callers must not close
	 *         it
	 */
	public PdfSession getAnswerPdfSession() {
		return answerPdfSession;
	}

	/**
	 * @return the one-based page currently selected in the workspace
	 */
	public int getCurrentPageNumber() {
		return currentPageNumber;
	}

	/**
	 * @return the active exam, answer or viewer document mode
	 */
	public DocumentMode getDisplayedDocument() {
		return displayedDocument;
	}

	/**
	 * @return the borrowed exam session, or {@code null}; callers must not close it
	 */
	public PdfSession getExamPdfSession() {
		return examPdfSession;
	}

	/**
	 * @return whether an exam PDF session is open
	 */
	public boolean hasExamPdf() {
		return examPdfSession != null;
	}

	/**
	 * Opens and displays an answer PDF, replacing any previous answer session.
	 *
	 * @param path the answer PDF path
	 */
	public void openAnswerPdf(Path path) {
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
	public void openAnswerPdfAsync(Path path, Consumer<Throwable> completed) {
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

	/**
	 * Opens and displays an exam PDF, replacing any previous exam session.
	 *
	 * @param path the exam PDF path
	 */
	public void openExamPdf(Path path) {
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
	public void openViewerPdf(Path path) {
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
	 * Sets the consumer notified after a PDF page has been displayed.
	 *
	 * @param pageChangedHandler receives the current one-based page number
	 */
	public void setPageChangedHandler(IntConsumer pageChangedHandler) {
		if (pageChangedHandler == null) {
			throw new NullPointerException("pageChangedHandler");
		}
		this.pageChangedHandler = pageChangedHandler;
	}

	/**
	 * Sets the guard consulted before Previous or Next changes the displayed PDF
	 * page.
	 *
	 * @param pageNavigationAllowed returns {@code true} when navigation may proceed
	 */
	public void setPageNavigationAllowed(BooleanSupplier pageNavigationAllowed) {
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
	public void setSelectionAvailable(Predicate<DocumentMode> selectionAvailable) {
		if (selectionAvailable == null) {
			throw new NullPointerException("selectionAvailable");
		}
		this.selectionAvailable = selectionAvailable;
	}

	/**
	 * Sets the callback invoked when a user gesture removes the visible pending
	 * selection without publishing a replacement region.
	 *
	 * @param selectionCancelledHandler callback used to clear the corresponding
	 *                                  logical capture selection
	 */
	public void setSelectionCancelledHandler(Runnable selectionCancelledHandler) {
		if (selectionCancelledHandler == null) {
			throw new NullPointerException("selectionCancelledHandler");
		}

		// Programmatic clearSelection() deliberately does not invoke this callback.
		// Only a user gesture that abandons the visible rectangle reports cancellation.
		this.selectionCancelledHandler = selectionCancelledHandler;
	}

	/**
	 * Updates the cursor to reflect selection availability.
	 *
	 * @param enabled whether region selection is enabled
	 */
	public void setSelectionCursorEnabled(boolean enabled) {
		pagePane.setCursor(enabled ? Cursor.CROSSHAIR : Cursor.DEFAULT);
	}

	/**
	 * Sets the consumer for completed proportional region selections.
	 *
	 * @param selectionHandler the completed-selection consumer
	 */
	public void setSelectionHandler(Consumer<RegionSelection> selectionHandler) {
		if (selectionHandler == null) {
			throw new NullPointerException("selectionHandler");
		}
		this.selectionHandler = selectionHandler;
	}

	/**
	 * Sets the callback invoked when the full-width selection mode changes.
	 *
	 * @param selectionModeChangedHandler callback used to clear any logical pending
	 *                                    capture selection
	 */
	public void setSelectionModeChangedHandler(Runnable selectionModeChangedHandler) {
		if (selectionModeChangedHandler == null) {
			throw new NullPointerException("selectionModeChangedHandler");
		}
		this.selectionModeChangedHandler = selectionModeChangedHandler;
	}

	/**
	 * Switches between the already opened exam and answer documents.
	 *
	 * @param documentMode the document to display
	 */
	public void showDocument(DocumentMode documentMode) {
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

	/**
	 * Displays a specific one-based page of an already opened document.
	 *
	 * @param documentMode document whose page should be displayed
	 * @param pageNumber   one-based page number
	 * @throws NullPointerException     if {@code documentMode} is {@code null}
	 * @throws IllegalStateException    if that document is not currently open
	 * @throws IllegalArgumentException if the page number is outside the document
	 */
	public void showPage(DocumentMode documentMode, int pageNumber) {
		if (documentMode == null) {
			throw new NullPointerException("documentMode");
		}
		PdfSession targetSession = switch (documentMode) {
		case EXAM -> examPdfSession;
		case ANSWER -> answerPdfSession;
		case VIEWER -> viewerPdfSession;
		};
		if (targetSession == null) {
			throw new IllegalStateException("No " + documentMode + " PDF is currently open");
		}
		if (pageNumber < 1 || pageNumber > targetSession.getPageCount()) {
			throw new IllegalArgumentException(
					"Page " + pageNumber + " is outside the document range 1-" + targetSession.getPageCount());
		}

		// Switch documents using the existing workspace lifecycle before selecting
		// the requested page.
		if (displayedDocument != documentMode) {
			showDocument(documentMode);
		}
		if (currentPageNumber == pageNumber) {
			restorePageNumberField();
			return;
		}
		currentPageNumber = pageNumber;
		showCurrentPage();
	}

	private void applyPageImage(Image image, PdfSession session) {
		pageView.setImage(image);
		double aspectRatio = image.getHeight() / image.getWidth();
		pagePane.prefHeightProperty().unbind();
		pagePane.prefHeightProperty().bind(pagePane.widthProperty().multiply(aspectRatio));
		pageLabel.setText(
				String.format("%s %d of %d", displayedDocument.pageLabel(), currentPageNumber, session.getPageCount()));
		previousButton.setDisable(currentPageNumber == 1);
		nextButton.setDisable(currentPageNumber == session.getPageCount());
		pageNumberField.setDisable(false);
		pageNumberField.setText(Integer.toString(currentPageNumber));
		rememberCurrentPageNumber();
		pageChangedHandler.accept(currentPageNumber);
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
		pageNumberField.clear();
		pageNumberField.setDisable(true);
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
		pageNumberField.setId("pdf-page-number");
		pageNumberField.setPrefColumnCount(PAGE_FIELD_COLUMNS);
		pageNumberField.setMaxWidth(PAGE_FIELD_MAX_WIDTH);
		pageNumberField.setDisable(true);
		pageNumberField.setPromptText("Page");
		pageNumberField.setOnAction(_ -> goToEnteredPage());
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
		fullWidthSelectionCheckBox.selectedProperty().addListener((_, _, _) -> {

			// Both the rectangle and its capture owner must forget bounds made under the
			// old mode.
			clearSelection();
			selectionModeChangedHandler.run();
		});
	}

	private void configureSelectionRectangle() {
		selectionRectangle.setFill(SELECTION_FILL);
		selectionRectangle.setStroke(SELECTION_COLOR);
		selectionRectangle.setStrokeWidth(SELECTION_STROKE_WIDTH);
		selectionRectangle.setVisible(false);
		selectionRectangle.setMouseTransparent(true);
		selectionAnchorMarker.setFill(SELECTION_COLOR);
		selectionAnchorMarker.setStroke(Color.WHITE);
		selectionAnchorMarker.setStrokeWidth(ANCHOR_STROKE_WIDTH);
		selectionAnchorMarker.setVisible(false);
		selectionAnchorMarker.setMouseTransparent(true);
		pagePane.getChildren().addAll(selectionRectangle, selectionAnchorMarker);
		selectionRectangle.toFront();
		selectionAnchorMarker.toFront();
	}

	private HBox createPageControls() {
		HBox pageControls = new HBox(PAGE_CONTROL_SPACING, previousButton, pageLabel, new Label("Go to:"),
				pageNumberField, nextButton, fullWidthSelectionCheckBox);
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

	private void goToEnteredPage() {
		PdfSession displayedSession = displayedPdfSession();
		if (displayedSession == null) {
			pageNumberField.clear();
			return;
		}
		int requestedPage;
		try {
			requestedPage = Integer.parseInt(pageNumberField.getText().strip());
		} catch (NumberFormatException e) {
			restorePageNumberField();
			return;
		}
		if (requestedPage < 1 || requestedPage > displayedSession.getPageCount()) {
			restorePageNumberField();
			return;
		}
		if (requestedPage == currentPageNumber) {
			restorePageNumberField();
			return;
		}
		if (!pageNavigationAllowed.getAsBoolean()) {
			restorePageNumberField();
			return;
		}
		currentPageNumber = requestedPage;
		showCurrentPage();
	}

	private void handleSelectionClicked(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != ANCHOR_CLICK_COUNT
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

				// A click or undersized drag means the user has abandoned the visible
				// pending selection. Clear its visual state and tell the application to
				// discard the corresponding logical capture selection as well.
				clearSelection();
				selectionCancelledHandler.run();
			}
			return;
		}

		// A valid completed region replaces any anchored-selection state and is
		// published normally to the owning capture workflow.
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

	private void restorePageNumberField() {
		pageNumberField.setText(Integer.toString(currentPageNumber));
		pageNumberField.selectAll();
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

	public enum DocumentMode {

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
	public record RegionSelection(DocumentMode documentMode, int pageNumber, double x, double y, double width,
			double height) {
	}

	private record LoadedAnswerPage(PdfSession session, Image image, int pageNumber) {
	}
}
