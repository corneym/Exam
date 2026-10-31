package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class ExamAssetsPdfReplacementWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void questionRowTargetsSavedAssetWithNoOrDifferentActiveCapture(boolean activateOther, FxRobot robot)
			throws Exception {
		if (activateOther) {
			prepareExamAndClassification(robot);
		} else {
			selectFirst(robot, "#curriculum-subject");
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> robot.lookup("#corpus-dashboard-add-exam").tryQuery().isPresent());
		}
		Subject subject = this.<Subject>comboBox(robot, "#curriculum-subject").getValue();
		ExamBooklet other = examMetadataPane().getBooklet();
		String relativePath = "Chemistry/QCAA/2026/target.pdf";
		Path managed = pdfDataRoot.resolve(relativePath);
		createPdf(managed, 1);
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		ExamBooklet target = new SqliteExamImporter(new SqliteDatabase(databasePath), writer).importExam(subject,
				"QCAA", 2026, "Replacement test", "Chosen booklet", relativePath,
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, new SourceDocumentHashService().sha256(managed));
		Path replacement = databasePath.getParent().resolve("replacement.pdf");
		createPdf(replacement, 2);
		ExamAssetsPane assets = field(application, "examAssetsPane", ExamAssetsPane.class);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showExamAssetsMode", new Class<?>[] {Exam.class, ExamBooklet.class, Runnable.class},
					target.getExam(), target, null);
			return null;
		}).get();
		String selector = "#exam-assets-question-replace-" + target.getId();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot.lookup(selector).tryQuery().isPresent());
		fireControl(robot, "#exam-assets-question-view-" + target.getId());
		// Only native file selection is substituted. The row target, impact dialog,
		// safe service, persistence and refresh remain the production workflow.
		robot.interact(() -> assets.setQuestionPdfReplacementHandler(booklet -> {
			try {
				invoke(application, "replaceQuestionPdf", new Class<?>[] {Stage.class, ApplicationConfig.class,
						ExamBooklet.class, Path.class, boolean.class}, primaryStage, applicationConfig,
						booklet, replacement, true);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		}));
		fireControlLater(robot, selector);
		waitForDialogShowing(robot, "Replace Question PDF");
		DialogPane cancelled = showingDialogPane(robot, "Replace Question PDF");
		assertTrue(cancelled.getContentText().contains("Chosen booklet"));
		fireDialogAction(robot, cancelled, ButtonBar.ButtonData.CANCEL_CLOSE);
		waitForDialogHidden(robot, "Replace Question PDF");
		assertEquals(target.getSourceDocument().getContentSha256(), new SourceDocumentHashService().sha256(managed));
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace().getDisplayedDocument());

		fireControlLater(robot, selector);
		waitForDialogShowing(robot, "Replace Question PDF");
		fireDialogAction(robot, showingDialogPane(robot, "Replace Question PDF"), ButtonBar.ButtonData.OK_DONE);
		waitForDialogShowing(robot, "Question PDF Replaced");
		DialogPane success = showingDialogPane(robot, "Question PDF Replaced");
		fireControl(robot, (Button) success.lookupButton(ButtonType.OK));
		waitForDialogHidden(robot, "Question PDF Replaced");
		assertEquals(new SourceDocumentHashService().sha256(replacement), new SourceDocumentHashService().sha256(managed));
		assertEquals(new SourceDocumentHashService().sha256(replacement),
				writer.findExamBookletBySourceDocumentPath(relativePath).getSourceDocument().getContentSha256());
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace().getDisplayedDocument());
		assertFalse(robot.lookup(selector).queryAs(Button.class).isDisabled());
		if (activateOther) {
			assertEquals(other.getId(), examMetadataPane().getBooklet().getId());
			assertEquals(other.getSourceDocument().getContentSha256(), writer.findExamBookletBySourceDocumentPath(
					other.getSourceDocument().getRelativePath()).getSourceDocument().getContentSha256());
		} else {
			assertNull(examMetadataPane().getBooklet());
		}
	}

	private void createPdf(Path path, int pages) throws Exception {
		Files.createDirectories(path.getParent());
		try (PDDocument document = new PDDocument()) {
			for (int page = 0; page < pages; page++) {
				document.addPage(new PDPage());
			}
			document.save(path.toFile());
		}
	}

	private void fireDialogAction(FxRobot robot, DialogPane dialog, ButtonBar.ButtonData action) {
		ButtonType type = dialog.getButtonTypes().stream().filter(button -> button.getButtonData() == action)
				.findFirst().orElseThrow();
		fireControl(robot, (Button) dialog.lookupButton(type));
	}
}
