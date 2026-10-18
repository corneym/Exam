package au.edu.eq.questionbank.ui.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import javafx.scene.control.Label;
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

	@Test
	void clearDocumentsRemovesEveryDocumentAndReturnsToEmptyWorkspace(FxRobot robot) throws Exception {
		Path examPath = createPdf("clear-exam.pdf");
		Path answerPath = createPdf("clear-answer.pdf");
		Path viewerPath = createPdf("clear-viewer.pdf");
		ImageView pageView = robot.lookup("#pdf-page-view").queryAs(ImageView.class);
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.openAnswerPdf(answerPath);
			pane.openViewerPdf(viewerPath);

			// Include viewer-specific state so clearing proves that no document workflow
			// leaks into the next application context.
			pane.showViewerCompletionAction("Finish Inspection", () -> {
			});
		});
		assertNotNull(pane.getExamPdfSession());
		assertNotNull(pane.getAnswerPdfSession());
		assertNotNull(pageView.getImage());
		robot.interact(pane::clearDocuments);

		// The workspace remains reusable, but every document owned by the previous
		// application context has gone.
		assertNull(pane.getExamPdfSession());
		assertNull(pane.getAnswerPdfSession());
		assertNull(pageView.getImage());
		assertEquals("No PDF selected", ((javafx.scene.control.Label) pane.lookup("#pdf-page-label")).getText());
		assertTrue(pane.lookup("#finish-pdf-inspection").isVisible() == false);
		assertThrows(IllegalStateException.class, () -> pane.extractDisplayedPageText());
	}

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
	void crossDocumentDirectPageRequestsRenderOnceAndPreserveRememberedPages(FxRobot robot) throws Exception {
		Path examPath = createTextPdf("switch-exam.pdf", "Exam 1", "Exam 2", "Exam 3");
		Path answerPath = createTextPdf("switch-answer.pdf", "Answer 1", "Answer 2", "Answer 3");
		List<String> renders = new ArrayList<>();
		List<Integer> publishedPages = new ArrayList<>();
		TextField pageNumber = robot.lookup("#pdf-page-number").queryAs(TextField.class);
		Label pageLabel = robot.lookup("#pdf-page-label").queryAs(Label.class);
		Button previous = robot.lookup("#previous-pdf-page").queryAs(Button.class);
		Button next = robot.lookup("#next-pdf-page").queryAs(Button.class);
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.openAnswerPdf(answerPath);
			pane.setPageRenderObserver((mode, page) -> renders.add(mode.name() + ":" + page));
			pane.setPageChangedHandler(publishedPages::add);

			// Destination remembers Exam page 1, but page 3 is requested.
			pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 3);
		});
		assertEquals(List.of("EXAM:3"), renders);
		assertEquals(List.of(3), publishedPages);
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pane.getDisplayedDocument());
		assertEquals(3, pane.getCurrentPageNumber());
		assertEquals("3", pageNumber.getText());
		assertEquals("Page 3 of 3", pageLabel.getText());
		assertFalse(previous.isDisabled());
		assertTrue(next.isDisabled());
		robot.interact(() -> pane.showPage(PdfWorkspacePane.DocumentMode.ANSWER, 2));
		assertEquals(List.of("EXAM:3", "ANSWER:2"), renders);
		assertEquals(List.of(3, 2), publishedPages);
		assertEquals("Answer page 2 of 3", pageLabel.getText());
		assertEquals("2", pageNumber.getText());
		assertFalse(previous.isDisabled());
		assertFalse(next.isDisabled());

		// The previous Exam page remains remembered.
		robot.interact(() -> pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 3));
		assertEquals(List.of("EXAM:3", "ANSWER:2", "EXAM:3"), renders);
		assertEquals(List.of(3, 2, 3), publishedPages);

		// The previous Answer page remains remembered as well.
		robot.interact(() -> pane.showPage(PdfWorkspacePane.DocumentMode.ANSWER, 2));
		assertEquals(List.of("EXAM:3", "ANSWER:2", "EXAM:3", "ANSWER:2"), renders);
		assertEquals(List.of(3, 2, 3, 2), publishedPages);

		// Same-document, same-page requests must not render or publish.
		robot.interact(() -> {
			pageNumber.setText("99");
			pane.showPage(PdfWorkspacePane.DocumentMode.ANSWER, 2);
		});
		assertEquals(4, renders.size());
		assertEquals(4, publishedPages.size());
		assertEquals("2", pageNumber.getText());

		// The existing showDocument() API must still restore the
		// remembered page using exactly one render.
		robot.interact(() -> pane.showDocument(PdfWorkspacePane.DocumentMode.EXAM));
		assertEquals(List.of("EXAM:3", "ANSWER:2", "EXAM:3", "ANSWER:2", "EXAM:3"), renders);
		assertEquals(List.of(3, 2, 3, 2, 3), publishedPages);
	}

	@Test
	void crossDocumentPageSwitchCancelsStaleAsyncAnswerLoad(FxRobot robot) throws Exception {
		Path examPath = createPdf("stale-navigation-exam.pdf");
		Path answerPath = createPdf("stale-navigation-answer.pdf");
		Path replacementPath = createPdf("stale-navigation-replacement.pdf");
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		List<String> renders = new ArrayList<>();
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.openAnswerPdf(answerPath);
			pane.showDocument(PdfWorkspacePane.DocumentMode.EXAM);
		});
		var originalAnswerSession = pane.getAnswerPdfSession();
		robot.interact(() -> {
			pane.setPageRenderObserver((mode, page) -> renders.add(mode.name() + ":" + page));
			pane.openAnswerPdfAsync(replacementPath, error -> {
				failure.set(error);
				completed.countDown();
			});

			// The already-open Answer PDF is authoritative for this
			// immediate navigation request. The pending replacement
			// must not overwrite it when its worker completes.
			pane.showPage(PdfWorkspacePane.DocumentMode.ANSWER, 2);
		});
		assertTrue(completed.await(10, TimeUnit.SECONDS));
		assertInstanceOf(CancellationException.class, failure.get());
		assertSame(originalAnswerSession, pane.getAnswerPdfSession());
		assertEquals(PdfWorkspacePane.DocumentMode.ANSWER, pane.getDisplayedDocument());
		assertEquals(2, pane.getCurrentPageNumber());
		assertEquals(List.of("ANSWER:2"), renders);

		// The stale worker must have released its own file handle.
		Files.delete(replacementPath);
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
	void focusedStoredRegionSurvivesCrossDocumentNavigation(FxRobot robot) throws Exception {
		Path examPath = createTextPdf("region-exam.pdf", "Exam 1", "Exam 2", "Exam 3");
		Path answerPath = createTextPdf("region-answer.pdf", "Answer 1", "Answer 2", "Answer 3");
		List<String> renders = new ArrayList<>();
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.openAnswerPdf(answerPath);
			pane.setPageRenderObserver((mode, page) -> renders.add(mode.name() + ":" + page));
			pane.focusStoredRegions(List.of(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 3,
					0.10, 0.20, 0.70, 0.30)));
		});
		assertEquals(List.of("EXAM:3"), renders);
		assertTrue(robot.lookup(".pdf-stored-region-highlight").tryQuery().isPresent());
		robot.interact(() -> pane.showPage(PdfWorkspacePane.DocumentMode.ANSWER, 2));
		assertEquals(List.of("EXAM:3", "ANSWER:2"), renders);
		assertTrue(robot.lookup(".pdf-stored-region-highlight").tryQuery().isEmpty());

		// Returning to the saved region's page restores its overlay
		// without first rendering the Exam's remembered page.
		robot.interact(() -> pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 3));
		assertEquals(List.of("EXAM:3", "ANSWER:2", "EXAM:3"), renders);
		Rectangle highlight = robot.lookup(".pdf-stored-region-highlight").queryAs(Rectangle.class);
		ImageView pageView = robot.lookup("#pdf-page-view").queryAs(ImageView.class);
		assertEquals(pageView.getBoundsInLocal().getWidth() * 0.10, highlight.getX(), 1.0);
		assertEquals(pageView.getBoundsInLocal().getHeight() * 0.20, highlight.getY(), 1.0);
		assertEquals(pageView.getBoundsInLocal().getWidth() * 0.70, highlight.getWidth(), 1.0);
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
	void invalidCrossDocumentRequestsLeaveDisplayedStateUnchanged(FxRobot robot) throws Exception {
		Path examPath = createPdf("invalid-cross-document.pdf");
		List<String> renders = new ArrayList<>();
		List<Integer> publishedPages = new ArrayList<>();
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.setPageRenderObserver((mode, page) -> renders.add(mode.name() + ":" + page));
			pane.setPageChangedHandler(publishedPages::add);
			assertThrows(NullPointerException.class, () -> pane.showPage(null, 1));
			assertThrows(IllegalStateException.class, () -> pane.showPage(PdfWorkspacePane.DocumentMode.ANSWER, 1));
			assertThrows(IllegalArgumentException.class, () -> pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 0));
			assertThrows(IllegalArgumentException.class, () -> pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 99));
		});
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pane.getDisplayedDocument());
		assertEquals(1, pane.getCurrentPageNumber());
		assertTrue(renders.isEmpty());
		assertTrue(publishedPages.isEmpty());
		TextField pageNumber = robot.lookup("#pdf-page-number").queryAs(TextField.class);
		assertEquals("1", pageNumber.getText());
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
	void loadsExamPdfAsynchronously(FxRobot robot) throws Exception {
		Path path = createPdf("exam-async.pdf");
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		robot.interact(() -> pane.openExamPdfAsync(path, error -> {
			failure.set(error);
			done.countDown();
		}));

		// Completion proves the worker opened and rendered the document before JavaFX
		// published it into the workspace.
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertNull(failure.get());
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pane.getDisplayedDocument());
		assertNotNull(pane.getExamPdfSession());
		ImageView pageView = robot.lookup("#pdf-page-view").queryAs(ImageView.class);
		assertNotNull(pageView.getImage());
	}

	@Test
	void measuresLegacyAndOptimisedRenderCounts(FxRobot robot) throws Exception {
		Path examPath = createTextPdf("benchmark-exam.pdf", "Exam 1", "Exam 2", "Exam 3");
		Path answerPath = createTextPdf("benchmark-answer.pdf", "Answer 1", "Answer 2", "Answer 3");
		List<String> renders = new ArrayList<>();
		List<String> legacyRenders = new ArrayList<>();
		List<String> optimisedRenders = new ArrayList<>();
		long[] elapsedNanos = new long[2];
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.openAnswerPdf(answerPath);
			pane.setPageRenderObserver((mode, page) -> renders.add(mode.name() + ":" + page));

			// Reproduce the former two-step navigation sequence.
			long start = System.nanoTime();
			pane.showDocument(PdfWorkspacePane.DocumentMode.EXAM);
			pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 3);
			elapsedNanos[0] = System.nanoTime() - start;
			legacyRenders.addAll(renders);

			// Leave Exam remembering page 2 and Answer displaying page 1.
			pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 2);
			pane.showDocument(PdfWorkspacePane.DocumentMode.ANSWER);
			renders.clear();

			// The replacement operation renders only the requested page 3.
			start = System.nanoTime();
			pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 3);
			elapsedNanos[1] = System.nanoTime() - start;
			optimisedRenders.addAll(renders);
		});
		assertEquals(List.of("EXAM:1", "EXAM:3"), legacyRenders);
		assertEquals(List.of("EXAM:3"), optimisedRenders);
		System.out.printf("PDF switching: legacy %d renders (%.2f ms), " + "optimised %d render (%.2f ms)%n",
				legacyRenders.size(), elapsedNanos[0] / 1_000_000.0, optimisedRenders.size(),
				elapsedNanos[1] / 1_000_000.0);
	}

	@Test
	void ordinaryViewerDoesNotRetainInspectionCompletionAction(FxRobot robot) throws Exception {
		Path inspectionPath = createPdf("inspection.pdf");
		Path ordinaryViewerPath = createPdf("ordinary-viewer.pdf");
		Button finishInspection = robot.lookup("#finish-pdf-inspection").queryAs(Button.class);
		robot.interact(() -> {
			pane.openViewerPdf(inspectionPath);

			// Simulate the specialised booklet-inspection workflow enabling its
			// completion action.
			pane.showViewerCompletionAction("Finish Inspection", () -> {
			});
		});
		assertTrue(finishInspection.isVisible());
		assertTrue(finishInspection.isManaged());
		robot.interact(() -> pane.openViewerPdf(ordinaryViewerPath));

		// Every ordinary viewer session resets workflow-specific actions. A
		// Finish Inspection button must never leak into File > Open PDF viewing.
		assertFalse(finishInspection.isVisible());
		assertFalse(finishInspection.isManaged());
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

	@Test
	void viewerSwitchesRenderOnlyRequestedPagesAndRestoreReturnPage(FxRobot robot) throws Exception {
		Path examPath = createTextPdf("viewer-switch-exam.pdf", "Exam 1", "Exam 2", "Exam 3");
		Path viewerPath = createTextPdf("viewer-switch.pdf", "Viewer 1", "Viewer 2", "Viewer 3");
		List<String> renders = new ArrayList<>();
		List<Integer> publishedPages = new ArrayList<>();
		robot.interact(() -> {
			pane.openExamPdf(examPath);
			pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 3);
			pane.openViewerPdf(viewerPath);
			pane.showViewerCompletionAction("Finish Inspection", () -> {
			});
			pane.setPageRenderObserver((mode, page) -> renders.add(mode.name() + ":" + page));
			pane.setPageChangedHandler(publishedPages::add);
			pane.showPage(PdfWorkspacePane.DocumentMode.EXAM, 2);
			pane.showPage(PdfWorkspacePane.DocumentMode.VIEWER, 3);
			pane.closeViewerPdf();
		});
		assertEquals(List.of("EXAM:2", "VIEWER:3", "EXAM:3"), renders);
		assertEquals(List.of(2, 3, 3), publishedPages);
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pane.getDisplayedDocument());
		assertEquals(3, pane.getCurrentPageNumber());
		Label pageLabel = robot.lookup("#pdf-page-label").queryAs(Label.class);
		Button completion = robot.lookup("#finish-pdf-inspection").queryAs(Button.class);
		assertEquals("Page 3 of 3", pageLabel.getText());
		assertFalse(completion.isVisible());
		assertFalse(completion.isManaged());
		assertThrows(IllegalStateException.class, () -> pane.showPage(PdfWorkspacePane.DocumentMode.VIEWER, 2));
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
