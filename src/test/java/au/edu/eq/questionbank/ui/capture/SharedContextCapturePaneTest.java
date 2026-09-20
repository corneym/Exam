package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import au.edu.eq.questionbank.model.QuestionRegion;
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
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class SharedContextCapturePaneTest {

	@TempDir
	Path tempDirectory;
	private ExamBooklet booklet;
	private RecordingContextRepository repository;
	private PdfSession session;
	private SharedContextCapturePane pane;
	private AtomicInteger selectionClearCount;

	@Test
	void acceptedAutomaticPreambleIsExposedWithoutBeingPersisted(FxRobot robot) {
		QuestionRegion transferred = new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2);
		robot.interact(() -> assertTrue(pane.beginAutomaticContext("Question 21 preamble", transferred)));
		assertTrue(pane.isCaptureMode());
		assertTrue(pane.hasCurrentSelection());
		robot.interact(() -> assertTrue(pane.acceptAutomaticRegion()));
		assertFalse(pane.isCaptureMode());
		assertTrue(pane.hasPendingAutomaticRegion());
		robot.interact(() -> {
			assertEquals("Question 21 preamble", pane.getPendingAutomaticContextLabel());
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
		assertEquals("Regions: 1", robot.lookup("#shared-context-region-count").queryAs(Label.class).getText());
	}

	@Test
	void automaticContextDataIsNotAvailableBeforeSelectionIsAccepted(FxRobot robot) {
		robot.interact(() -> {
			pane.beginAutomaticContext("Preamble", new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2));
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
	void cancellingAcceptedAutomaticPreambleLeavesNothingToSave(FxRobot robot) {
		robot.interact(() -> {
			assertTrue(pane.beginAutomaticContext("Preamble", new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2)));
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
		SharedQuestionContext original = repository.save(booklet, "Question 22 preamble", List.of(originalRegion));
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
		assertEquals("Question 22 preamble", beforeCancel.getLabel());
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
		assertEquals("Question 22 preamble", afterCancel.getLabel());
		assertEquals(List.of(originalRegion), afterCancel.getRegions());
	}

	@Test
	void recapturingContextReplacesRegionsWithoutChangingIdentity(FxRobot robot) {
		SharedQuestionContextRegion originalRegion = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20);
		SharedQuestionContext original = repository.save(booklet, "Question 21 preamble", List.of(originalRegion));
		AtomicInteger completed = new AtomicInteger();
		robot.interact(() -> {
			pane.refreshForCurrentBooklet();
			assertTrue(pane.recaptureContext(original, completed::incrementAndGet));
		});
		assertTrue(pane.isCaptureMode());
		assertEquals("Regions: 0",
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
		assertEquals("Question 21 preamble", replaced.getLabel());
		assertEquals(List.of(replacementRegion), replaced.getRegions());
		assertEquals(1, completed.get());
		assertFalse(pane.isCaptureMode());
	}

	@Test
	void rejectsTransferredRegionFromAnotherBookletBeforeChangingCaptureState(FxRobot robot) {
		ExamBooklet other = new ExamBooklet(2, booklet.getExam(), "Paper 2", new SourceDocument(2, "paper-2.pdf"));
		robot.interact(() -> {
			assertThrows(IllegalArgumentException.class,
					() -> pane.beginAutomaticContext("Preamble", new QuestionRegion(other, 1, 0.1, 0.1, 0.5, 0.2)));
			assertFalse(pane.isCaptureMode());
			assertFalse(pane.hasUnsavedContextCapture());
		});
		assertEquals(0, selectionClearCount.get());
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
		public SharedQuestionContext save(ExamBooklet booklet, String label,
				List<SharedQuestionContextRegion> regions) {
			saveCount++;
			SharedQuestionContext context = new SharedQuestionContext(saveCount, booklet, label, regions);
			contexts.add(context);
			return context;
		}

		int saveCount() {
			return saveCount;
		}
	}
}
