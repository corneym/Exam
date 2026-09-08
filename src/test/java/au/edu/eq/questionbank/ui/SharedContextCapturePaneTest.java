package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import javafx.scene.Scene;
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
	void transferredQuestionSelectionIsSavedExactlyOnceAsAutomaticPreamble(FxRobot robot) {
		QuestionRegion transferred = new QuestionRegion(booklet, 1, 0.1, 0.1, 0.5, 0.2);
		robot.interact(() -> assertTrue(pane.beginAutomaticContext("Question 21 preamble", transferred)));
		assertTrue(pane.isCaptureMode());
		assertTrue(pane.hasCurrentSelection());
		robot.interact(() -> assertTrue(pane.acceptAutomaticRegion()));
		assertFalse(pane.isCaptureMode());
		assertTrue(pane.hasPendingAutomaticRegion());

		AtomicReference<SharedQuestionContext> savedReference = new AtomicReference<>();
		robot.interact(() -> savedReference.set(pane.saveAutomaticContext()));
		SharedQuestionContext saved = savedReference.get();
		assertNotNull(saved);
		assertEquals(1, repository.saveCount());
		assertEquals("Question 21 preamble", saved.getLabel());
		assertEquals(List.of(new SharedQuestionContextRegion(1, 0.1, 0.1, 0.5, 0.2)), saved.getRegions());
		assertEquals(saved.getId(), pane.getSelectedContext().getId());
		assertFalse(pane.hasPendingAutomaticRegion());
		assertThrows(IllegalStateException.class, pane::saveAutomaticContext);
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
		stage.setScene(new Scene(pane, 650, 400));
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
