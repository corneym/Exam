package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class QuestionSearchDialogTest {

	@TempDir
	Path tempDirectory;
	private Stage stage;

	@Start
	public void start(Stage stage) {
		this.stage = stage;

		// A JavaFX Dialog inspects its owner's Scene while establishing ownership, so
		// the test Stage must have a real Scene before the Dialog is constructed.
		stage.setScene(new Scene(new StackPane(), 300, 200));
		stage.show();
	}

	@Test
	void resizedDimensionsSurviveHideAndReshow(FxRobot robot) {
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository();

		// Search results are irrelevant to this regression; an empty retrieval
		// boundary keeps the test focused on Dialog lifecycle behaviour.
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];

		// JavaFX Dialog construction creates its backing Stage immediately, so the
		// constructor itself must run on the FX application thread.
		robot.interact(() -> dialogHolder[0] = new QuestionSearchDialog(stage, curriculumRepository, retrievalService,
				() -> List.of(), previewService));
		QuestionSearchDialog dialog = dialogHolder[0];
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			dialog.setWidth(1120);
			dialog.setHeight(810);
		});
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1120, dialog.getWidth(), 1.0);
		assertEquals(810, dialog.getHeight(), 1.0);

		// Edit actions hide Search before the application later shows the same Dialog
		// instance again. Reproduce that lifecycle directly.
		robot.interact(dialog::hide);
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1120, dialog.getWidth(), 1.0);
		assertEquals(810, dialog.getHeight(), 1.0);
		robot.interact(dialog::close);
	}
}
