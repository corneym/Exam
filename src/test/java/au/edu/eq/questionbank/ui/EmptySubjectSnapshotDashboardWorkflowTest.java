package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.service.workpackage.SubjectSnapshotIntakePreview;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class EmptySubjectSnapshotDashboardWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void firstSubjectSnapshotImportsIntoEmptyInstallation(FxRobot robot) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		Map<String, Integer> originalCounts = CorpusDashboardWorkflowTest.snapshotGraphCounts(database);
		assertTrue(originalCounts.values().stream().allMatch(count -> count == 0));
		assertTrue(new SqliteCurriculumRepository(database).findAllSubjects().isEmpty());
		assertTrue(CorpusDashboardWorkflowTest.snapshotBackupFiles(applicationConfig).isEmpty());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		robot.interact(() -> {
			assertTrue(subjects.getItems().isEmpty());
			assertNull(subjects.getValue());
		});
		Path archive = CorpusDashboardWorkflowTest.createSubjectSnapshotArchive("Engineering");
		CorpusDashboardWorkflowTest.startSnapshotImport(this, archive);
		DialogPane preview = CorpusDashboardWorkflowTest.assertSnapshotPreview(robot, "Engineering");

		// The application must remain genuinely empty
		// until the user approves the first import.
		assertEquals(originalCounts, CorpusDashboardWorkflowTest.snapshotGraphCounts(database));
		assertTrue(CorpusDashboardWorkflowTest.snapshotBackupFiles(applicationConfig).isEmpty());
		CorpusDashboardWorkflowTest.fireSnapshotDialogButton(robot, preview, ButtonBar.ButtonData.OK_DONE);
		CorpusDashboardWorkflowTest.awaitSnapshotImportSuccess(robot, "Engineering");
		CorpusDashboardWorkflowTest.assertSnapshotImportReleased(this);
		assertEquals(Map.of("subjects", 1, "syllabus_versions", 1, "curriculum_nodes", 4, "exams", 1, "exam_booklets",
				1, "answer_files", 1, "questions", 1, "answers", 1, "subject_import_packages", 1,
				"imported_subject_authorities", 1), CorpusDashboardWorkflowTest.snapshotGraphCounts(database));
		assertFalse(CorpusDashboardWorkflowTest.snapshotBackupFiles(applicationConfig).isEmpty());
		robot.interact(() -> {
			assertEquals(List.of("Engineering"), subjects.getItems().stream().map(Subject::getName).toList());
			assertFalse(subjects.isDisabled());
		});

		// Verify that an installation with no previous Subject
		// selection can enter the ordinary imported-Subject Dashboard.
		CorpusDashboardWorkflowTest.assertImportedSnapshotDashboard(this, robot, "Engineering");
		Subject imported = new SqliteCurriculumRepository(database).findAllSubjects().getFirst();
		var authority = new SqliteImportedSubjectAuthorityRepository(database).find(imported.getId()).orElseThrow();
		var inspection = new SubjectSnapshotIntakePreview(database).inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.ALREADY_IMPORTED, inspection.state());
		assertEquals(inspection.fingerprint().packageId(), authority.latestPackageId());
		assertEquals(inspection.fingerprint().originInstallationId(), authority.originInstallationId());
		assertEquals(inspection.fingerprint().subjectId(), authority.originSubjectPortableId());
		database.verifyIntegrity();
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage, false);
	}
}
