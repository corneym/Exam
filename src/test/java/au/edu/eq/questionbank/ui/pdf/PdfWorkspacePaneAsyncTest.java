package au.edu.eq.questionbank.ui.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class PdfWorkspacePaneAsyncTest {

	@TempDir
	Path tempDir;
	private PdfWorkspacePane pane;

	@AfterEach
	void close() throws Exception {
		pane.close();
	}

	@Test
	void closesManagedDocumentsIndependently(FxRobot robot) throws Exception {
		Path examPath = createPdf("temporary-exam.pdf");
		Path answerPath = createPdf("temporary-answer.pdf");
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.openAnswerPdf(answerPath);
		});
		assertNotNull(pane.getExamPdfSession());
		assertNotNull(pane.getAnswerPdfSession());
		robot.interact(pane::closeAnswerPdf);

		// Closing a temporary Answer edit must not inadvertently close an Exam session
		// that happens to be open independently.
		assertNull(pane.getAnswerPdfSession());
		assertNotNull(pane.getExamPdfSession());
		robot.interact(() -> pane.showDocument(PdfWorkspacePane.DocumentMode.EXAM));
		robot.interact(pane::closeExamPdf);
		assertNull(pane.getExamPdfSession());
	}

	@Test
	void discardsAndClosesALoadWhenTheUserChangesDocument(FxRobot robot) throws Exception {
		Path path = createPdf("stale.pdf");
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		robot.interact(() -> {
			pane.openAnswerPdfAsync(path, error -> {
				failure.set(error);
				done.countDown();
			});

			// Invalidate before the worker's completion can run on the FX thread.
			pane.showDocument(PdfWorkspacePane.DocumentMode.EXAM);
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertInstanceOf(CancellationException.class, failure.get());
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pane.getDisplayedDocument());
		assertNull(pane.getAnswerPdfSession());
		Files.delete(path);
	}

	@Test
	void extractsTextFromTheCurrentlyDisplayedViewerPage(FxRobot robot) throws Exception {
		Path path = createTextPdf("syllabus.pdf", "Unit one content", "Unit two content");
		robot.interact(() -> pane.openViewerPdf(path));
		assertEquals("Unit one content", pane.extractDisplayedPageText().trim());
		assertEquals(1, pane.getCurrentPageNumber());
		robot.interact(() -> ((Button) pane.lookup("#next-pdf-page")).fire());
		assertEquals("Unit two content", pane.extractDisplayedPageText().trim());
		assertEquals(2, pane.getCurrentPageNumber());
	}

	@Test
	void failedLoadRetainsTheExistingAnswerDocument(FxRobot robot) throws Exception {
		Path path = createPdf("existing.pdf");
		robot.interact(() -> pane.openAnswerPdf(path));
		var session = pane.getAnswerPdfSession();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		robot.interact(() -> pane.openAnswerPdfAsync(tempDir.resolve("missing.pdf"), error -> {
			failure.set(error);
			done.countDown();
		}));
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertNotNull(failure.get());
		assertSame(session, pane.getAnswerPdfSession());
		assertEquals(2, session.getPageCount());
	}

	@Test
	void focusesStoredRegionAndKeepsHighlightAligned(FxRobot robot) throws Exception {
		Path path = createTallPdf("stored-region-focus.pdf");
		robot.interact(() -> {
			pane.openExamPdf(path);
			pane.focusStoredRegions(List.of(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 1,
					0.10, 0.70, 0.80, 0.10)));
		});
		ScrollPane scrollPane = robot.lookup("#pdf-page-scroll").queryAs(ScrollPane.class);
		ImageView pageView = robot.lookup("#pdf-page-view").queryAs(ImageView.class);

		// Wait for a genuine tall-page layout and for the pending focus request to
		// move the viewport; draining runLater callbacks alone does not imply a pulse.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> pageView.getBoundsInLocal().getHeight() > scrollPane.getViewportBounds().getHeight()
						&& scrollPane.getVvalue() > 0);
		Rectangle highlight = robot.lookup(".pdf-stored-region-highlight").queryAs(Rectangle.class);

		// The grey stored-region overlay uses the same proportional coordinates as
		// persisted capture data.
		assertEquals(pageView.getBoundsInLocal().getWidth() * 0.10, highlight.getX(), 1.0);
		assertEquals(pageView.getBoundsInLocal().getHeight() * 0.70, highlight.getY(), 1.0);
		assertEquals(pageView.getBoundsInLocal().getWidth() * 0.80, highlight.getWidth(), 1.0);
		assertTrue(scrollPane.getVvalue() > 0,
				"A lower stored region should be brought towards the top of the viewport");
	}

	@Test
	void jumpsDirectlyToEnteredPageNumber(FxRobot robot) throws Exception {
		Path path = createTextPdf("page-jump.pdf", "Page one", "Page two", "Page three");
		robot.interact(() -> pane.openViewerPdf(path));
		TextField pageNumber = robot.lookup("#pdf-page-number").queryAs(TextField.class);
		assertEquals("1", pageNumber.getText());
		robot.interact(() -> {
			pageNumber.setText("3");
			pageNumber.fireEvent(new ActionEvent());
		});
		assertEquals(3, pane.getCurrentPageNumber());
		assertEquals("3", pageNumber.getText());
		assertEquals("Page three", pane.extractDisplayedPageText().trim());
	}

	@Test
	void loadsAnswerPdfAndReusesTheDisplayedPage(FxRobot robot) throws Exception {
		Path path = createPdf("answer.pdf");
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		robot.interact(() -> pane.openAnswerPdfAsync(path, error -> {
			failure.set(error);
			done.countDown();
		}));
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertNull(failure.get());
		assertEquals(PdfWorkspacePane.DocumentMode.ANSWER, pane.getDisplayedDocument());
		assertEquals(2, pane.getAnswerPdfSession().getPageCount());
		robot.interact(() -> ((Button) pane.lookup("#next-pdf-page")).fire());
		var session = pane.getAnswerPdfSession();
		var image = ((ImageView) pane.lookup("#pdf-page-view")).getImage();
		robot.interact(() -> pane.openAnswerPdfAsync(path, failure::set));
		assertNull(failure.get());
		assertSame(session, pane.getAnswerPdfSession());
		assertSame(image, ((ImageView) pane.lookup("#pdf-page-view")).getImage());
		assertTrue(pane.lookup("#next-pdf-page").isDisabled(), "Remain on page two");
	}

	@Test
	void rejectsInvalidDirectPageNumbers(FxRobot robot) throws Exception {
		Path path = createPdf("invalid-page-jump.pdf");
		robot.interact(() -> pane.openViewerPdf(path));
		TextField pageNumber = robot.lookup("#pdf-page-number").queryAs(TextField.class);
		robot.interact(() -> {
			pageNumber.setText("99");
			pageNumber.fireEvent(new ActionEvent());
		});
		assertEquals(1, pane.getCurrentPageNumber());
		assertEquals("1", pageNumber.getText());
		robot.interact(() -> {
			pageNumber.setText("not-a-number");
			pageNumber.fireEvent(new ActionEvent());
		});
		assertEquals(1, pane.getCurrentPageNumber());
		assertEquals("1", pageNumber.getText());
	}

	@Test
	void rejectsTextExtractionWhenNoPdfIsDisplayed() {
		assertThrows(IllegalStateException.class, () -> pane.extractDisplayedPageText());
	}

	@Test
	void showsRequestedPageOfOpenDocument(FxRobot robot) throws Exception {
		Path path = createTextPdf("programmatic-page-jump.pdf", "Page one", "Page two", "Page three");
		robot.interact(() -> {
			pane.openViewerPdf(path);
			pane.showPage(PdfWorkspacePane.DocumentMode.VIEWER, 3);
		});
		assertEquals(3, pane.getCurrentPageNumber());
		assertEquals("Page three", pane.extractDisplayedPageText().trim());
	}

	@Start
	void start(Stage stage) {
		pane = new PdfWorkspacePane();
		stage.setScene(new Scene(pane, 640, 480));
		stage.show();
	}

	private Path createPdf(String name) throws Exception {
		Path path = tempDir.resolve(name);
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		return path;
	}

	private Path createTallPdf(String name) throws Exception {
		Path path = tempDir.resolve(name);
		try (PDDocument document = new PDDocument()) {

			// A tall page ensures the region can sit below the initial viewport and
			// therefore exercises real ScrollPane positioning.
			document.addPage(new PDPage(new PDRectangle(300, 900)));
			document.save(path.toFile());
		}
		return path;
	}

	private Path createTextPdf(String name, String... pageTexts) throws Exception {
		Path path = tempDir.resolve(name);
		try (PDDocument document = new PDDocument()) {
			for (String pageText : pageTexts) {
				PDPage page = new PDPage(new PDRectangle(300, 300));
				document.addPage(page);
				try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
					contentStream.beginText();
					contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					contentStream.newLineAtOffset(40, 250);
					contentStream.showText(pageText);
					contentStream.endText();
				}
			}
			document.save(path.toFile());
		}
		return path;
	}
}
