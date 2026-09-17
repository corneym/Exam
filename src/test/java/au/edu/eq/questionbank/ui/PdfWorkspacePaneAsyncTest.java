package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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

import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
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
