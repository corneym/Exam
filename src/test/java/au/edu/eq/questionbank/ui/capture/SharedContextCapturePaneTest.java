package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.SharedQuestionContextRepository;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class SharedContextCapturePaneTest {

	// Test fixtures and collaborators.
	private ExamBooklet booklet;
	private SharedContextCapturePane pane;
	private RecordingContextRepository repository;
	private AtomicInteger selectionClearCount;
	private PdfSession session;
	@TempDir
	Path tempDirectory;

	@Test
	void acceptedAutomaticSharedContextIsExposedWithoutBeingPersisted(FxRobot robot) {
		QuestionRegion transferred = new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2);
		robot.interact(() -> assertTrue(pane.beginAutomaticContext("Question 21 shared context", transferred)));
		assertTrue(pane.isCaptureMode());
		assertTrue(pane.hasCurrentSelection());
		robot.interact(() -> assertTrue(pane.acceptAutomaticRegion()));
		assertFalse(pane.isCaptureMode());
		assertTrue(pane.hasPendingAutomaticRegion());
		robot.interact(() -> {
			assertEquals("Question 21 shared context", pane.getPendingAutomaticContextLabel());
			assertEquals(List.of(new SharedQuestionContextRegion(1, 0.1, 0.1, 0.5, 0.2)),
					pane.getPendingAutomaticContextRegions());
		});
		assertEquals(0, repository.saveCount());
		assertTrue(pane.hasPendingAutomaticRegion());
		robot.interact(pane::cancelAutomaticContext);
		assertFalse(pane.hasPendingAutomaticRegion());
		robot.interact(() -> {
			assertThrows(IllegalStateException.class, pane::getPendingAutomaticContextLabel);
			assertThrows(IllegalStateException.class, pane::getPendingAutomaticContextRegions);
		});
	}

	@Test
	void acceptedRegionRemovesTransientPreviewFromLayout(FxRobot robot) {
		robot.clickOn("#new-shared-context");
		robot.interact(() -> pane.acceptSelection(
				new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 1, 0.10, 0.10, 0.60, 0.20)));
		ImageView currentPreview = robot.lookup("#shared-context-current-preview").queryAs(ImageView.class);
		assertTrue(currentPreview.isVisible());
		assertTrue(currentPreview.isManaged());
		robot.clickOn("#add-shared-context-region");

		// Once the selection becomes an accepted region, its separate transient
		// preview must no longer reserve vertical space above the accepted list.
		assertFalse(currentPreview.isVisible());
		assertFalse(currentPreview.isManaged());
		assertEquals("Content parts: 1", robot.lookup("#shared-context-region-count").queryAs(Label.class).getText());
	}

	@Test
	void automaticContextDataIsNotAvailableBeforeSelectionIsAccepted(FxRobot robot) {
		robot.interact(() -> {
			pane.beginAutomaticContext("Shared Context", new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2));
			assertThrows(IllegalStateException.class, pane::getPendingAutomaticContextLabel);
			assertThrows(IllegalStateException.class, pane::getPendingAutomaticContextRegions);
			assertTrue(pane.hasCurrentSelection());
			assertTrue(pane.isCaptureMode());
		});
		assertEquals(0, repository.saveCount());
	}

	@Test
	void cancelDiscardsManualCaptureWithoutPersistence(FxRobot robot) {
		robot.clickOn("#new-shared-context");
		assertTrue(pane.isCaptureMode());
		robot.interact(() -> pane.acceptSelection(
				new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 1, 0.1, 0.1, 0.5, 0.2)));
		assertTrue(pane.hasCurrentSelection());
		robot.clickOn("#cancel-shared-context");
		assertFalse(pane.isCaptureMode());
		assertFalse(pane.hasCurrentSelection());
		assertFalse(pane.hasUnsavedContextCapture());
		assertEquals(0, repository.saveCount());
		assertTrue(selectionClearCount.get() > 0);
	}

	@Test
	void cancellingAcceptedAutomaticSharedContextLeavesNothingToSave(FxRobot robot) {
		robot.interact(() -> {
			assertTrue(
					pane.beginAutomaticContext("Shared Context", new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2)));
			assertTrue(pane.acceptAutomaticRegion());
			assertTrue(pane.hasUnsavedContextCapture());
			pane.cancelAutomaticContext();
			assertFalse(pane.hasCurrentSelection());
			assertFalse(pane.hasPendingAutomaticRegion());
			assertFalse(pane.hasUnsavedContextCapture());
			assertThrows(IllegalStateException.class, pane::getPendingAutomaticContextLabel);
			assertThrows(IllegalStateException.class, pane::getPendingAutomaticContextRegions);
		});
		assertEquals(0, repository.saveCount());
	}

	@Test
	void cancellingSharedContextRecapturePreservesPersistedContext(FxRobot robot) {
		SharedQuestionContextRegion originalRegion = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20);
		SharedQuestionContext original = repository.save(booklet, "Question 22 shared context",
				List.of(originalRegion));
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> {
			pane.refreshForCurrentBooklet();
			assertTrue(pane.recaptureContext(original, completed::incrementAndGet));
		});
		assertTrue(pane.isCaptureMode());
		assertEquals(0, completed.get());
		SharedQuestionContextRegion replacementRegion = new SharedQuestionContextRegion(1, 0.20, 0.25, 0.60, 0.30);
		robot.interact(
				() -> pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
						replacementRegion.pageNumber(), replacementRegion.x(), replacementRegion.y(),
						replacementRegion.width(), replacementRegion.height())));
		robot.clickOn("#add-shared-context-region");

		// The replacement is only transient until Save Replacement is used.
		assertTrue(pane.hasUnsavedContextCapture());
		SharedQuestionContext beforeCancel = repository.findByBooklet(booklet).getFirst();
		assertEquals(original.getId(), beforeCancel.getId());
		assertEquals("Question 22 shared context", beforeCancel.getLabel());
		assertEquals(List.of(originalRegion), beforeCancel.getRegions());
		robot.clickOn("#cancel-shared-context");

		// Cancelling the correction must discard only the transient replacement and
		// notify the containing workflow that the correction operation has finished.
		assertFalse(pane.isCaptureMode());
		assertFalse(pane.hasCurrentSelection());
		assertFalse(pane.hasUnsavedContextCapture());
		assertEquals(1, completed.get());
		SharedQuestionContext afterCancel = repository.findByBooklet(booklet).getFirst();

		// The persisted context must remain exactly as it was before recapture began.
		assertEquals(original.getId(), afterCancel.getId());
		assertEquals("Question 22 shared context", afterCancel.getLabel());
		assertEquals(List.of(originalRegion), afterCancel.getRegions());
	}

	@Test
	void clipboardButtonClearsOnlyAfterAcceptedSharedContextImage(FxRobot robot) {
		AtomicReference<WritableImage> imageRef = new AtomicReference<>();
		Button paste = robot.lookup("#paste-shared-context-image").queryAs(Button.class);
		robot.interact(() -> {
			((Button) pane.lookup("#new-shared-context")).fire();
			WritableImage image = new WritableImage(4, 3);
			image.getPixelWriter().setColor(0, 0, Color.BLUE);
			imageRef.set(image);
			ClipboardContent contents = new ClipboardContent();
			contents.putImage(image);
			Clipboard.getSystemClipboard().setContent(contents);
		});
		robot.interact(paste::fire);
		robot.interact(() -> {
			assertFalse(Clipboard.getSystemClipboard().hasImage(),
					"Successful Shared Context insertion must clear the clipboard");
			assertEquals("Content parts: 1", ((Label) pane.lookup("#shared-context-region-count")).getText());
		});

		// A second copy of the exact same image must not add a part or be cleared.
		robot.interact(() -> {
			ClipboardContent contents = new ClipboardContent();
			contents.putImage(imageRef.get());
			Clipboard.getSystemClipboard().setContent(contents);
		});
		robot.interact(paste::fire);
		robot.interact(() -> {
			assertTrue(Clipboard.getSystemClipboard().hasImage(),
					"Duplicate rejection must leave the clipboard unchanged");
			assertEquals("Content parts: 1", ((Label) pane.lookup("#shared-context-region-count")).getText());
		});
	}

	@Test
	void clipboardImageCanBeSavedAsImageOnlySharedContext(FxRobot robot) throws Exception {
		byte[] png = testPngBytes();
		robot.interact(() -> {
			pane.lookup("#new-shared-context");
			((Button) pane.lookup("#new-shared-context")).fire();
			((javafx.scene.control.TextField) pane.lookup("#shared-context-label")).setText("Image-only context");
			assertTrue(pane.acceptClipboardPng(png));
			((Button) pane.lookup("#save-shared-context")).fire();
		});
		List<SharedQuestionContext> saved = repository.findByBooklet(booklet);
		assertEquals(1, saved.size());
		assertEquals("Image-only context", saved.getFirst().getLabel());
		assertTrue(saved.getFirst().getRegions().isEmpty());
		assertEquals(List.of(new ImageSharedContextContentPart(png)), saved.getFirst().getContentParts());
	}

	@Test
	void duplicateClipboardImageIsRejectedAndCanBeReacceptedAfterRemoval(FxRobot robot) throws Exception {
		byte[] png = testPngBytes();
		robot.interact(() -> {
			((Button) pane.lookup("#new-shared-context")).fire();
			assertTrue(pane.acceptClipboardPng(png));
			assertFalse(pane.acceptClipboardPng(png));
			assertEquals("Content parts: 1", ((Label) pane.lookup("#shared-context-region-count")).getText());
			((Button) pane.lookup("#remove-shared-context-image-0")).fire();
			assertTrue(pane.acceptClipboardPng(png));
			assertEquals("Content parts: 1", ((Label) pane.lookup("#shared-context-region-count")).getText());
		});
		assertEquals(0, repository.saveCount());
	}

	@Test
	void mixedContentRecaptureRetainsContextIdentity(FxRobot robot) throws Exception {
		byte[] png = testPngBytes();
		SharedQuestionContextRegion originalRegion = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.40, 0.20);
		SharedQuestionContext original = repository.save(booklet, "Existing context", List.of(originalRegion));
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> {
			pane.refreshForCurrentBooklet();
			assertTrue(pane.recaptureContext(original, completed::incrementAndGet));

			// Replacement starts as an empty draft. The original
			// remains persisted until Save Replacement succeeds.
			assertTrue(pane.acceptClipboardPng(png));
			pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM, 1, 0.20, 0.30,
					0.50, 0.25));
			((Button) pane.lookup("#add-shared-context-region")).fire();
		});
		SharedQuestionContext beforeSave = repository.findByBooklet(booklet).getFirst();
		assertEquals(original.getId(), beforeSave.getId());
		assertEquals(List.of(originalRegion), beforeSave.getContentParts());
		robot.interact(() -> ((Button) pane.lookup("#save-shared-context")).fire());
		SharedQuestionContext replacement = repository.findByBooklet(booklet).getFirst();
		assertEquals(original.getId(), replacement.getId());
		assertEquals(original.getLabel(), replacement.getLabel());
		assertEquals(List.of(new ImageSharedContextContentPart(png),
				new SharedQuestionContextRegion(1, 0.20, 0.30, 0.50, 0.25)), replacement.getContentParts());
		assertEquals(1, completed.get());
		assertFalse(pane.isCaptureMode());
	}

	@Test
	void mixedPdfAndImageCapturePreservesPersistenceOrder(FxRobot robot) throws Exception {
		byte[] png = testPngBytes();
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.40, 0.20);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(1, 0.20, 0.35, 0.50, 0.25);
		robot.interact(() -> {
			((Button) pane.lookup("#new-shared-context")).fire();
			((TextField) pane.lookup("#shared-context-label")).setText("Mixed context");
			pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
					first.pageNumber(), first.x(), first.y(), first.width(), first.height()));
			((Button) pane.lookup("#add-shared-context-region")).fire();
			assertTrue(pane.acceptClipboardPng(png));
			pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
					second.pageNumber(), second.x(), second.y(), second.width(), second.height()));
			((Button) pane.lookup("#add-shared-context-region")).fire();
			assertEquals("Content parts: 3", ((Label) pane.lookup("#shared-context-region-count")).getText());
			((Button) pane.lookup("#save-shared-context")).fire();
		});
		assertEquals(1, repository.saveCount());
		SharedQuestionContext saved = repository.findByBooklet(booklet).getFirst();
		assertEquals("Mixed context", saved.getLabel());
		assertEquals(List.of(first, new ImageSharedContextContentPart(png), second), saved.getContentParts());
		assertEquals(List.of(first, second), saved.getRegions());
		assertFalse(pane.isCaptureMode());
	}

	@Test
	void recapturingContextReplacesRegionsWithoutChangingIdentity(FxRobot robot) {
		SharedQuestionContextRegion originalRegion = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20);
		SharedQuestionContext original = repository.save(booklet, "Question 21 shared context",
				List.of(originalRegion));
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> {
			pane.refreshForCurrentBooklet();
			assertTrue(pane.recaptureContext(original, completed::incrementAndGet));
		});
		assertTrue(pane.isCaptureMode());
		assertEquals("Content parts: 0",
				robot.lookup("#shared-context-region-count").queryAs(javafx.scene.control.Label.class).getText());
		Button save = robot.lookup("#save-shared-context").queryButton();
		assertEquals("Save Replacement", save.getText());
		SharedQuestionContext beforeSave = repository.findByBooklet(booklet).getFirst();

		// Starting recapture must not mutate the persisted original.
		assertEquals(original.getId(), beforeSave.getId());
		assertEquals(List.of(originalRegion), beforeSave.getRegions());
		SharedQuestionContextRegion replacementRegion = new SharedQuestionContextRegion(1, 0.20, 0.25, 0.60, 0.30);
		robot.interact(
				() -> pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
						replacementRegion.pageNumber(), replacementRegion.x(), replacementRegion.y(),
						replacementRegion.width(), replacementRegion.height())));
		robot.clickOn("#add-shared-context-region");
		robot.clickOn("#save-shared-context");
		SharedQuestionContext replaced = repository.findByBooklet(booklet).getFirst();
		assertEquals(original.getId(), replaced.getId());
		assertEquals("Question 21 shared context", replaced.getLabel());
		assertEquals(List.of(replacementRegion), replaced.getRegions());
		assertEquals(1, completed.get());
		assertFalse(pane.isCaptureMode());
	}

	@Test
	void rejectsTransferredRegionFromAnotherBookletBeforeChangingCaptureState(FxRobot robot) {
		ExamBooklet other = new ExamBooklet(2, booklet.getExam(), "Paper 2", new SourceDocument(2, "paper-2.pdf"));
		robot.interact(() -> {
			assertThrows(IllegalArgumentException.class, () -> pane.beginAutomaticContext("Shared Context",
					new QuestionRegion(other, 1, 0.1, 0.1, 0.5, 0.2)));
			assertFalse(pane.isCaptureMode());
			assertFalse(pane.hasUnsavedContextCapture());
		});
		assertEquals(0, selectionClearCount.get());
		assertEquals(0, repository.saveCount());
	}

	@Test
	void sharedContextSelectorDescribesStoredMixedContent(FxRobot robot) throws Exception {
		byte[] png = testPngBytes();
		SharedQuestionContext imageOnly = repository.saveContentParts(booklet, "Image context",
				List.of(new ImageSharedContextContentPart(png)));
		SharedQuestionContext mixed = repository.saveContentParts(booklet, "Mixed context", List.of(
				new SharedQuestionContextRegion(1, 0.10, 0.10, 0.40, 0.20), new ImageSharedContextContentPart(png)));
		robot.interact(pane::refreshForCurrentBooklet);
		robot.interact(() -> {
			ComboBox<SharedQuestionContext> selector = (ComboBox<SharedQuestionContext>) pane.lookup("#shared-context");
			assertEquals("Image context — 1 image(s)", selector.getConverter().toString(imageOnly));
			assertEquals("Mixed context — 1 region(s), 1 image(s)", selector.getConverter().toString(mixed));
		});
	}

	@Test
	void sourceWideCorrectionAcceptsMixedContentInOrder(FxRobot robot) throws Exception {
		byte[] png = testPngBytes();
		AtomicInteger completed = new AtomicInteger();
		SharedQuestionContextRegion pdf = new SharedQuestionContextRegion(1, 0.15, 0.20, 0.40, 0.30);
		robot.interact(() -> {
			assertTrue(pane.captureMissingContext("Question 31 context",
					(label, parts) -> repository.saveContentParts(booklet, label, parts), completed::incrementAndGet));
			assertTrue(pane.acceptClipboardPng(png));
			pane.acceptSelection(new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
					pdf.pageNumber(), pdf.x(), pdf.y(), pdf.width(), pdf.height()));
			((Button) pane.lookup("#add-shared-context-region")).fire();
			assertEquals("Content parts: 2", ((Label) pane.lookup("#shared-context-region-count")).getText());
		});
		assertEquals(0, repository.saveCount(), "Nothing persists before Save");
		robot.interact(() -> ((Button) pane.lookup("#save-shared-context")).fire());
		assertEquals(1, completed.get());
		assertEquals(1, repository.saveCount());
		SharedQuestionContext saved = repository.findByBooklet(booklet).getFirst();
		assertEquals(List.of(new ImageSharedContextContentPart(png), pdf), saved.getContentParts());
	}

	@Test
	void stagingAcceptedQuestionRegionPreservesGeometryWithoutSelectionTransferOrPersistence(FxRobot robot) {
		QuestionRegion original = new QuestionRegion(booklet, 1, 0.1, 0.2, 0.5, 0.3);
		SharedQuestionContextRegion expected = new SharedQuestionContextRegion(1, 0.1, 0.2, 0.5, 0.3);
		robot.interact(() -> {
			pane.stageAcceptedQuestionRegion("Question 24 context", original);
			assertFalse(pane.isCaptureMode());
			assertFalse(pane.hasCurrentSelection());
			assertTrue(pane.hasPendingAutomaticRegion());
			assertEquals("Question 24 context", pane.getPendingAutomaticContextLabel());
			assertEquals(List.of(expected), pane.getPendingAutomaticContextRegions());

			// An already accepted Question region must not trigger
			// clearing of an unrelated PDF workspace selection.
			assertEquals(0, selectionClearCount.get());

			// A second staging attempt must not overwrite or duplicate
			// the existing unsaved Shared Context region.
			assertThrows(IllegalStateException.class,
					() -> pane.stageAcceptedQuestionRegion("Duplicate context", original));
			assertEquals(List.of(expected), pane.getPendingAutomaticContextRegions());
			pane.cancelAutomaticContext();
			assertFalse(pane.hasPendingAutomaticRegion());

			// After cancellation the same source can be staged again.
			pane.stageAcceptedQuestionRegion("Question 24 context", original);
			assertEquals(List.of(expected), pane.getPendingAutomaticContextRegions());
		});

		// Staging and restaging must never write a context to SQLite.
		assertEquals(0, repository.saveCount());
	}

	@Start
	void start(Stage stage) throws Exception {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2025, "External assessment");
		booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "paper-1.pdf"));
		Path pdfPath = tempDirectory.resolve("paper-1.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage(new PDRectangle(200, 200)));
			document.save(pdfPath.toFile());
		}
		session = PdfSession.open(pdfPath);
		repository = new RecordingContextRepository();
		selectionClearCount = new AtomicInteger();
		pane = new SharedContextCapturePane(repository, () -> booklet, new QuestionExtractor(), () -> session,
				selectionClearCount::incrementAndGet, () -> true);

		// Give the standalone component fixture enough vertical space for the
		// full-width pending and accepted previews used by the production workflow.
		stage.setScene(new Scene(pane, 650, 650));
		stage.show();
	}

	@Stop
	void stop() throws Exception {
		if (session != null) {
			session.close();
		}
	}

	@Test
	void takingAcceptedAutomaticRegionRestoresOriginalGeometry(FxRobot robot) {
		QuestionRegion original = new QuestionRegion(booklet, 1, 0.15, 0.20, 0.45, 0.30);
		robot.interact(() -> {
			pane.stageAcceptedQuestionRegion("Question 24 context", original);
			QuestionRegion restored = pane.takePendingAutomaticRegion();
			assertEquals(original, restored);
			assertFalse(pane.hasPendingAutomaticRegion());
			assertFalse(pane.isCaptureMode());
			assertFalse(pane.hasCurrentSelection());

			// Releasing an accepted region must not clear the PDF
			// workspace selection or persist a Shared Context.
			assertEquals(0, selectionClearCount.get());
			assertThrows(IllegalStateException.class, pane::takePendingAutomaticRegion);

			// The original source remains eligible for another toggle.
			pane.stageAcceptedQuestionRegion("Question 24 context", restored);
			assertEquals(List.of(new SharedQuestionContextRegion(1, 0.15, 0.20, 0.45, 0.30)),
					pane.getPendingAutomaticContextRegions());
		});
		assertEquals(0, repository.saveCount());
	}

	private byte[] testPngBytes() throws Exception {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xFFFF0000);
		image.setRGB(1, 0, 0xFF00FF00);
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			assertTrue(ImageIO.write(image, "png", output));
			return output.toByteArray();
		}
	}

	private static final class RecordingContextRepository implements SharedQuestionContextRepository {

		private final List<SharedQuestionContext> contexts = new ArrayList<>();
		private int saveCount;

		@Override
		public List<SharedQuestionContext> findByBooklet(ExamBooklet booklet) {
			List<SharedQuestionContext> matches = new ArrayList<>();
			for (SharedQuestionContext context : contexts) {
				if (context.getBooklet().getId() == booklet.getId()) {
					matches.add(context);
				}
			}
			return List.copyOf(matches);
		}

		@Override
		public SharedQuestionContext replace(SharedQuestionContext context, String label,
				List<SharedQuestionContextRegion> regions) {

			// Preserve the existing context identity while replacing its editable data,
			// matching the production repository contract.
			for (int i = 0; i < contexts.size(); i++) {
				SharedQuestionContext existing = contexts.get(i);
				if (existing.getId() != context.getId()) {
					continue;
				}
				SharedQuestionContext replacement = new SharedQuestionContext(existing.getId(), existing.getBooklet(),
						label, regions);
				contexts.set(i, replacement);
				return replacement;
			}
			throw new IllegalArgumentException("Shared context does not exist");
		}

		@Override
		public SharedQuestionContext replaceContentParts(SharedQuestionContext context, String label,
				List<? extends SharedContextContentPart> contentParts) {
			for (int i = 0; i < contexts.size(); i++) {
				SharedQuestionContext existing = contexts.get(i);
				if (existing.getId() == context.getId()) {
					SharedQuestionContext replacement = SharedQuestionContext.fromContentParts(existing.getId(),
							existing.getBooklet(), label, contentParts);
					contexts.set(i, replacement);
					return replacement;
				}
			}
			throw new IllegalArgumentException("Shared context does not exist");
		}

		@Override
		public SharedQuestionContext save(ExamBooklet booklet, String label,
				List<SharedQuestionContextRegion> regions) {
			saveCount++;
			SharedQuestionContext context = new SharedQuestionContext(saveCount, booklet, label, regions);
			contexts.add(context);
			return context;
		}

		@Override
		public SharedQuestionContext saveContentParts(ExamBooklet booklet, String label,
				List<? extends SharedContextContentPart> contentParts) {
			saveCount++;
			SharedQuestionContext context = SharedQuestionContext.fromContentParts(saveCount, booklet, label,
					contentParts);
			contexts.add(context);
			return context;
		}

		int saveCount() {
			return saveCount;
		}
	}
}
