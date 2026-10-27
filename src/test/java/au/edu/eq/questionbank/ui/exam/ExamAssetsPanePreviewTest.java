package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
	private Subject workingSubject;

	// UI fixtures and chooser observations.
	private final AtomicBoolean answerChooserSawClearedPreview = new AtomicBoolean();
	private ExamAssetsPane examAssetsPane;
	private final AtomicInteger legacyImportRequests = new AtomicInteger();
	private PdfWorkspacePane pdfWorkspace;
	private Stage stage;
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

	@Test
	@SuppressWarnings("unchecked")
	void compactExamActionsRouteByLegacySelectionAndKeepLifecycleStatesLegible(FxRobot robot) throws Exception {
		Button addExam = robot.lookup("#exam-assets-add-new-exam").queryAs(Button.class);
		Button cancelNewExam = robot.lookup("#exam-assets-new-exam-cancel").queryAs(Button.class);
		Button saveNewExam = robot.lookup("#exam-assets-new-exam-save").queryAs(Button.class);
		CheckBox legacyImport = robot.lookup("#exam-assets-legacy-import").queryAs(CheckBox.class);
		ComboBox<Exam> examSelector = robot.lookup("#exam-assets-exam").queryAs(ComboBox.class);
		ComboBox<String> provider = robot.lookup("#exam-assets-provider").queryAs(ComboBox.class);
		ComboBox<Integer> year = robot.lookup("#exam-assets-year").queryAs(ComboBox.class);
		ComboBox<String> assessment = robot.lookup("#exam-assets-assessment").queryAs(ComboBox.class);
		Label state = robot.lookup("#exam-assets-state").queryAs(Label.class);
		HBox existingExamControls = robot.lookup("#exam-assets-existing-exam-controls").queryAs(HBox.class);
		HBox examCreationControls = robot.lookup("#exam-assets-exam-creation-controls").queryAs(HBox.class);

		assertEquals("+", addExam.getText());
		assertEquals("Add Exam", addExam.getAccessibleText());
		assertNotNull(addExam.getTooltip());
		assertFalse(legacyImport.isSelected());
		assertEquals(List.of(state, examSelector), existingExamControls.getChildren());
		assertEquals(List.of(addExam, legacyImport), examCreationControls.getChildren());

		// The compact selector still exposes the complete selected identity without
		// forcing the EXAM row to consume the available workspace width.
		assertTrue(examSelector.getWidth() <= 240.5);
		assertNotNull(examSelector.getTooltip());
		assertEquals("2026 QCAA A deliberately long External Assessment identity",
				examSelector.getTooltip().getText());

		// Unchecked + starts the ordinary New Exam transaction.
		robot.interact(addExam::fire);
		assertEquals("NEW EXAM", state.getText());
		assertEquals(0, legacyImportRequests.get());
		assertTrue(legacyImport.isDisabled());
		robot.interact(legacyImport::fire);
		assertFalse(legacyImport.isSelected());
		robot.interact(cancelNewExam::fire);
		assertEquals("State: ACTIVE", state.getText());
		assertFalse(legacyImport.isDisabled());

		// Toggling legacy mode alone is passive. Only the subsequent + action routes to
		// the application-owned importer, without creating or selecting another Exam.
		int persistedExamCount = examWriter.findExamsForSubject(workingSubject).size();
		Exam selectedExam = examSelector.getValue();
		robot.interact(legacyImport::fire);
		assertEquals(persistedExamCount, examWriter.findExamsForSubject(workingSubject).size());
		assertEquals(selectedExam, examSelector.getValue());
		assertEquals(0, legacyImportRequests.get());
		robot.interact(addExam::fire);
		assertEquals(1, legacyImportRequests.get());
		assertEquals(selectedExam, examSelector.getValue());

		// Saving an ordinary New Exam also ends the transaction and restores the mode
		// control. A failed or incomplete Save would leave it disabled.
		robot.interact(legacyImport::fire);
		robot.interact(addExam::fire);
		assertTrue(legacyImport.isDisabled());
		robot.interact(() -> {
			provider.getEditor().setText("QCAA");
			year.getSelectionModel().select(Integer.valueOf(2025));
			assessment.getEditor().setText("Saved compact-control Exam");
		});
		assertFalse(saveNewExam.isDisabled());
		robot.interact(saveNewExam::fire);
		assertFalse(legacyImport.isDisabled());
		assertEquals("State: ACTIVE", state.getText());

		// Both lifecycle values retain their full preferred width when the containing
		// stage is reduced.
		robot.interact(() -> stage.setWidth(560));
		assertEquals("State: ACTIVE", state.getText());
		assertTrue(state.getWidth() + 0.5 >= state.prefWidth(-1));
		Exam completedExam = new Exam(exam.getId(), exam.getSubject(), exam.getProvider(), exam.getYear(), exam.getName(),
				ExamCaptureState.COMPLETE);
		ExamAssetsPane.ExamSnapshot completedSnapshot = new ExamAssetsPane.ExamSnapshot(completedExam,
				new ExamAssetExpectations(null, 0, null, 0), List.of(), List.of());
		robot.interact(() -> examAssetsPane
				.applySubjectSnapshot(new ExamAssetsPane.SubjectSnapshot(workingSubject, List.of(completedExam),
						completedSnapshot)));
		assertEquals("State: COMPLETE", state.getText());
		assertTrue(state.getWidth() + 0.5 >= state.prefWidth(-1));
	}

	@Start
	void start(Stage stage) throws Exception {
		this.stage = stage;
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("exam-assets-preview.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		workingSubject = curriculumWriter.insertSubject("Chemistry");
		examWriter = new SqliteExamWriter(database);
		answerWriter = new SqliteAnswerWriter(database, examWriter);
		ExamProvider provider = examWriter.insertExamProvider("QCAA");
		exam = examWriter.insertExam(workingSubject, provider, 2026,
				"A deliberately long External Assessment identity");
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
		examAssetsPane.setLegacyQuestionImportHandler(legacyImportRequests::incrementAndGet);
		examAssetsPane.refresh(workingSubject);

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
