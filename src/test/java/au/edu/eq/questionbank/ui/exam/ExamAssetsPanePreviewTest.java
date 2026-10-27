package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class ExamAssetsPanePreviewTest {

	// Persisted fixtures used to prove that preview clearing is UI-only.
	private AnswerFile answerFile;
	private SqliteAnswerWriter answerWriter;
	private ExamBooklet booklet;
	private Exam exam;
	private SqliteExamWriter examWriter;
	@TempDir
	Path tempDirectory;

	// UI fixtures and chooser observations.
	private final AtomicBoolean answerChooserSawClearedPreview = new AtomicBoolean();
	private ExamAssetsPane examAssetsPane;
	private PdfWorkspacePane pdfWorkspace;
	private final AtomicBoolean questionChooserSawClearedPreview = new AtomicBoolean();

	@AfterEach
	void closePdfWorkspace() throws Exception {
		pdfWorkspace.close();
	}

	@Test
	void addActionsClearEitherDisplayedAssetWithoutChangingPersistence(FxRobot robot) throws Exception {
		Path questionPreview = createPdf("question-preview.pdf");
		Path answerPreview = createPdf("answer-preview.pdf");

		// Exercise every Question/Answer preview-to-add combination. The chooser
		// returns null so each action also proves that cancellation does not restore the
		// old preview or start a structural transaction.
		assertAddClearsPreview(robot, questionPreview, "#exam-assets-add-question-booklet",
				questionChooserSawClearedPreview);
		assertAddClearsPreview(robot, answerPreview, "#exam-assets-add-question-booklet",
				questionChooserSawClearedPreview);
		assertAddClearsPreview(robot, questionPreview, "#exam-assets-add-answer-booklet",
				answerChooserSawClearedPreview);
		assertAddClearsPreview(robot, answerPreview, "#exam-assets-add-answer-booklet",
				answerChooserSawClearedPreview);

		// The clear operation must not delete, replace or unassign any saved asset.
		assertEquals(1, examWriter.findExamBooklets(exam).size());
		assertEquals(booklet.getId(), examWriter.findExamBooklets(exam).getFirst().getId());
		assertEquals(1, answerWriter.findAnswerFiles(exam).size());
		assertNotNull(answerWriter.findAnswerFile(booklet));
		assertEquals(answerFile.getId(), answerWriter.findAnswerFile(booklet).getId());
	}

	@Test
	void addActionInvalidatesPendingLoadAndAllowsLaterPreview(FxRobot robot) throws Exception {
		Path stalePreview = createPdf("stale-preview.pdf");
		Path laterPreview = createPdf("later-preview.pdf");
		CountDownLatch loadCompleted = new CountDownLatch(1);
		AtomicReference<Throwable> loadFailure = new AtomicReference<>();
		Button addAnswer = robot.lookup("#exam-assets-add-answer-booklet").queryAs(Button.class);

		robot.interact(() -> {
			pdfWorkspace.openAnswerPdfAsync(stalePreview, failure -> {
				loadFailure.set(failure);
				loadCompleted.countDown();
			});

			// Semantic activation runs in the same FX turn, before the pending worker can
			// publish its result.
			answerChooserSawClearedPreview.set(false);
			addAnswer.fire();
		});

		assertTrue(loadCompleted.await(10, TimeUnit.SECONDS), "Pending PDF load did not complete");
		assertInstanceOf(CancellationException.class, loadFailure.get());
		assertTrue(answerChooserSawClearedPreview.get(), "Preview must clear before source selection");
		assertPdfWorkspaceCleared();

		// The stale worker must release its file and a later explicit preview must still
		// open normally.
		Files.delete(stalePreview);
		robot.interact(() -> pdfWorkspace.openViewerPdf(laterPreview));
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace.getDisplayedDocument());
		ImageView pageView = robot.lookup("#pdf-page-view").queryAs(ImageView.class);
		assertNotNull(pageView.getImage());
	}

	@Start
	void start(Stage stage) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("exam-assets-preview.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		examWriter = new SqliteExamWriter(database);
		answerWriter = new SqliteAnswerWriter(database, examWriter);
		ExamProvider provider = examWriter.insertExamProvider("QCAA");
		exam = examWriter.insertExam(chemistry, provider, 2026, "External Assessment");
		SourceDocument questionSource = examWriter.insertSourceDocument("subjects/Chemistry/question.pdf");
		booklet = examWriter.insertExamBooklet(exam, questionSource, "Paper 1",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, 10);
		answerFile = answerWriter.findOrCreateAnswerFile(booklet, "Marking guide",
				"subjects/Chemistry/answer.pdf");

		pdfWorkspace = new PdfWorkspacePane();
		examAssetsPane = new ExamAssetsPane(examWriter, answerWriter, new ExamMetadataOptionsRepository(),
				(currentExam, _, _, _) -> currentExam, _ -> {
				}, _ -> {
				}, _ -> {
				}, _ -> {
				}, pdfWorkspace::clearDocuments, () -> null, _ -> {
				}, _ -> {
				}, () -> {
					answerChooserSawClearedPreview.set(pdfWorkspaceCleared());
					return null;
				}, (_, _, _, _) -> null, () -> {
					questionChooserSawClearedPreview.set(pdfWorkspaceCleared());
					return null;
				}, _ -> false, () -> {
				}, (_, _, _, _, _, _, _) -> null);
		examAssetsPane.refresh(chemistry);

		BorderPane root = new BorderPane();
		root.setLeft(examAssetsPane);
		root.setCenter(pdfWorkspace);
		stage.setScene(new Scene(root, 1100, 720));
		stage.show();
	}

	private void assertAddClearsPreview(FxRobot robot, Path previewPath, String addButtonSelector,
			AtomicBoolean chooserObservation) {
		Button addButton = robot.lookup(addButtonSelector).queryAs(Button.class);
		robot.interact(() -> {
			pdfWorkspace.openViewerPdf(previewPath);
			chooserObservation.set(false);
		});
		assertEquals(PdfWorkspacePane.DocumentMode.VIEWER, pdfWorkspace.getDisplayedDocument());

		// The workflow test exercises the control's semantic action; pointer hit-testing
		// is unrelated to the reported defect.
		robot.interact(addButton::fire);
		assertTrue(chooserObservation.get(), "Preview must clear before source selection");
		assertPdfWorkspaceCleared();
	}

	private void assertPdfWorkspaceCleared() {
		assertTrue(pdfWorkspaceCleared());
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace.getDisplayedDocument());
		assertNull(pdfWorkspace.getExamPdfSession());
		assertNull(pdfWorkspace.getAnswerPdfSession());
	}

	private Path createPdf(String name) throws Exception {
		Path path = tempDirectory.resolve(name);
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		return path;
	}

	private boolean pdfWorkspaceCleared() {
		return pdfWorkspace.getDisplayedDocument() == PdfWorkspacePane.DocumentMode.EXAM
				&& pdfWorkspace.getExamPdfSession() == null && pdfWorkspace.getAnswerPdfSession() == null;
	}
}
