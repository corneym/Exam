package au.edu.eq.questionbank.ui.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

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
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class ExamSetupPaneTest {

	@TempDir
	Path tempDirectory;
	private final AtomicReference<ExamBooklet> openedBooklet = new AtomicReference<>();
	private Exam externalExam;
	private ExamBooklet paper1;
	private ExamBooklet paper2;
	private final AtomicReference<ExamBooklet> inspectedBooklet = new AtomicReference<>();

	@Test
	void showsSelectedExamAssetsAndOpensPersistedBooklet(FxRobot robot) {
		@SuppressWarnings("unchecked")
		ComboBox<Exam> examField = (ComboBox<Exam>) robot.lookup("#exam-setup-exam").query();
		@SuppressWarnings("unchecked")
		ListView<ExamBooklet> booklets = (ListView<ExamBooklet>) robot.lookup("#exam-setup-booklets").query();
		@SuppressWarnings("unchecked")
		ListView<String> answerFiles = (ListView<String>) robot.lookup("#exam-setup-answer-files").query();
		Label summary = robot.lookup("#exam-setup-summary").queryAs(Label.class);
		Button openButton = robot.lookup("#exam-setup-open-booklet").queryAs(Button.class);
		Button inspectButton = robot.lookup("#exam-setup-inspect-booklet").queryAs(Button.class);

		// The current Subject exposes all of its Exams and selects the newest one
		// without activating capture automatically.
		assertEquals(2, examField.getItems().size());
		assertNotNull(examField.getValue());
		assertEquals(externalExam.getId(), examField.getValue().getId());
		assertEquals(2, booklets.getItems().size());
		assertEquals(paper1.getId(), booklets.getItems().get(0).getId());

		// Inspection emits the persisted booklet separately from capture activation.
		robot.interact(() -> booklets.getSelectionModel().select(paper1));
		robot.interact(inspectButton::fire);
		assertNotNull(inspectedBooklet.get());
		assertEquals(paper1.getId(), inspectedBooklet.get().getId());
		assertEquals(paper2.getId(), booklets.getItems().get(1).getId());

		// Shared Answer assets appear once while still describing every booklet that
		// currently uses the persisted AnswerFile.
		assertEquals(1, answerFiles.getItems().size());
		assertTrue(answerFiles.getItems().getFirst().contains("Paper 1"));
		assertTrue(answerFiles.getItems().getFirst().contains("Paper 2"));
		assertTrue(summary.getText().contains("2 available / 2 expected"));
		assertTrue(summary.getText().contains("1 available / 1 expected"));

		// Workflow tests activate semantic controls directly rather than relying on
		// pointer hit-testing.
		robot.interact(() -> booklets.getSelectionModel().select(paper2));
		robot.interact(openButton::fire);
		assertNotNull(openedBooklet.get());
		assertEquals(paper2.getId(), openedBooklet.get().getId());
	}

	@Start
	void start(Stage stage) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("exam-setup.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		ExamProvider qcaa = examWriter.insertExamProvider("QCAA");
		externalExam = examWriter.insertExam(chemistry, qcaa, 2025, "External Assessment");
		Exam olderExam = examWriter.insertExam(chemistry, qcaa, 2024, "Practice Assessment");
		SourceDocument paper1Source = examWriter.insertSourceDocument("Chemistry/QCAA/2025/paper1.pdf");
		SourceDocument paper2Source = examWriter.insertSourceDocument("Chemistry/QCAA/2025/paper2.pdf");
		paper1 = examWriter.insertExamBooklet(externalExam, paper1Source, "Paper 1",
				ExamBookletQuestionFormat.MULTIPLE_CHOICE, 10);
		paper2 = examWriter.insertExamBooklet(externalExam, paper2Source, "Paper 2",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, 12);
		AnswerFile markingGuide = answerWriter.findOrCreateAnswerFile(paper1, "Marking guide",
				"Chemistry/QCAA/2025/marking-guide.pdf", "a".repeat(64));
		answerWriter.assignAnswerFile(paper2, markingGuide);

		// Expectations describe intended Exam structure independently of the real
		// assets currently available.
		examWriter.updateExamAssetExpectations(externalExam, 2, 1);

		// An Exam without source assets must still appear in the Exam selector.
		assertTrue(examWriter.findExamBooklets(olderExam).isEmpty());
		ExamSetupPane pane = new ExamSetupPane(examWriter, answerWriter, inspectedBooklet::set, openedBooklet::set);
		pane.refresh(chemistry);
		stage.setScene(new Scene(pane, 700, 620));
		stage.show();
	}
}
