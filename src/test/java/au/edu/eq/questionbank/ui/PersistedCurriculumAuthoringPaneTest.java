package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumLifecycleRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftLoader;
import au.edu.eq.questionbank.service.curriculum.CurriculumLifecycleService;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfService;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfStore;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class PersistedCurriculumAuthoringPaneTest {

	@TempDir
	Path tempDir;
	private CurriculumAuthoringPane pane;
	private SqliteDatabase database;
	private CurriculumAuthoringSession session;
	private long descriptorPersistentId;
	private Path curriculumRoot;
	private static final String AUTHORED_TEXT = "    **Resolve forces** using \\(F = m\\,a\\) and {components}.\n\n";

	@Test
	void saveIncludesUnappliedEditorText(FxRobot robot) {
		editDescriptor(robot, "Unapplied wording");
		robot.interact(() -> robot.lookup("#save-curriculum").queryAs(Button.class).fire());
		assertEquals("Unapplied wording", storedDescriptorText());
	}

	@Test
	void finaliseIncludesUnappliedEditorText(FxRobot robot) {
		editDescriptor(robot, "Wording accepted as final");
		robot.interact(() -> robot.lookup("#finalise-curriculum").queryAs(Button.class).fire());
		assertEquals("Wording accepted as final", storedDescriptorText());
		assertEquals(CurriculumStatus.FINAL, session.syllabusVersion().getCurriculumStatus());
	}

	@Test
	void changingTreeSelectionDoesNotDiscardUnappliedWording(FxRobot robot) {
		editDescriptor(robot, "Keep this wording across selection changes");
		robot.interact(() -> {
			var tree = curriculumTree(robot);
			tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1"));
			tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1.1"));
			robot.lookup("#save-curriculum").queryAs(Button.class).fire();
		});
		assertEquals("Keep this wording across selection changes", storedDescriptorText());
	}

	@Test
	void unappliedEditorTextCountsAsUnsavedWork(FxRobot robot) {
		editDescriptor(robot, "Unapplied changes must be protected on close");
		assertTrue(pane.hasUnsavedChanges());
	}

	@Test
	void updateAndSavePreserveExactAuthoredText(FxRobot robot) {
		editDescriptor(robot, AUTHORED_TEXT);
		robot.interact(() -> {
			robot.lookup("#update-curriculum-text").queryAs(Button.class).fire();
			robot.lookup("#save-curriculum").queryAs(Button.class).fire();
		});
		assertEquals(AUTHORED_TEXT, storedDescriptorText());
	}

	@Test
	void invalidPendingTextBlocksSelectionSaveAndFinaliseWithoutLosingEditorText(FxRobot robot) {
		editDescriptor(robot, "  \n");
		robot.interact(() -> {
			var tree = curriculumTree(robot);
			tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1"));
			assertEquals("1.1.1", tree.getSelectionModel().getSelectedItem().getValue().code());
			robot.lookup("#save-curriculum").queryAs(Button.class).fire();
			robot.lookup("#finalise-curriculum").queryAs(Button.class).fire();
			assertEquals("  \n", robot.lookup("#curriculum-edit-text").queryAs(TextArea.class).getText());
		});
		assertEquals("Resolve forces", storedDescriptorText());
		assertEquals(CurriculumStatus.IN_PROGRESS, session.syllabusVersion().getCurriculumStatus());
		assertTrue(pane.hasUnsavedChanges());
	}

	@Test
	void revertingPendingTextRestoresCleanStateWithoutPersistence(FxRobot robot) {
		editDescriptor(robot, "Temporary wording");
		assertTrue(pane.hasUnsavedChanges());
		assertEquals("Resolve forces", storedDescriptorText());
		editDescriptor(robot, "Resolve forces");
		assertFalse(pane.hasUnsavedChanges());
	}

	@Test
	void saveAndFinalisePreserveExactPendingTextWithoutUpdateButton(FxRobot robot) {
		editDescriptor(robot, AUTHORED_TEXT);
		robot.interact(pane::saveCurriculum);
		assertEquals(AUTHORED_TEXT, storedDescriptorText());
		assertFalse(pane.hasUnsavedChanges());
		editDescriptor(robot, AUTHORED_TEXT + "\n");
		robot.interact(pane::finaliseCurriculum);
		assertEquals(AUTHORED_TEXT + "\n", storedDescriptorText());
		assertFalse(pane.hasUnsavedChanges());
	}

	@Test
	void attachingPdfRetainsPendingEditorText(FxRobot robot) throws Exception {
		Path pdf = tempDir.resolve("pending-text-source.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(pdf.toFile());
		}
		editDescriptor(robot, AUTHORED_TEXT);
		robot.interact(() -> {
			try {
				pane.attachSyllabusPdf(pdf);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			assertEquals(AUTHORED_TEXT, robot.lookup("#curriculum-edit-text").queryAs(TextArea.class).getText());
			assertTrue(pane.hasUnsavedChanges());
			pane.saveCurriculum();
		});
		assertEquals(AUTHORED_TEXT, storedDescriptorText());
	}

	private void editDescriptor(FxRobot robot, String wording) {
		robot.interact(() -> {
			var tree = curriculumTree(robot);
			tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1.1"));
			robot.lookup("#curriculum-edit-text").queryAs(TextArea.class).setText(wording);
		});
	}

	@SuppressWarnings("unchecked")
	private TreeView<au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode> curriculumTree(FxRobot robot) {
		return robot.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
	}

	private String storedDescriptorText() {
		return new SqliteCurriculumRepository(database).findByCode(session.syllabusVersion(), "1.1.1")
				.orElseThrow().getName();
	}

	@AfterEach
	void close() throws Exception {
		pane.close();
	}

	@Test
	void savesAttachesFinalisesAndReopensExistingCurriculum(FxRobot robot) throws Exception {
		@SuppressWarnings("unchecked")
		TreeView<au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode> tree = robot
				.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
		TextArea editText = robot.lookup("#curriculum-edit-text").queryAs(TextArea.class);
		Button update = robot.lookup("#update-curriculum-text").queryAs(Button.class);
		Button save = robot.lookup("#save-curriculum").queryAs(Button.class);
		Button finalise = robot.lookup("#finalise-curriculum").queryAs(Button.class);
		Button reopen = robot.lookup("#reopen-curriculum").queryAs(Button.class);
		Button browse = robot.lookup("#browse-syllabus-pdf").queryAs(Button.class);
		robot.interact(() -> {
			tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1.1"));
			editText.setText("Resolve forces into components");
			update.fire();
			save.fire();
		});
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		SyllabusVersion reloaded = repository.findVersionById(session.syllabusVersion().getId()).orElseThrow();
		var persistedDescriptor = repository.findByCode(reloaded, "1.1.1").orElseThrow();
		assertEquals(descriptorPersistentId, persistedDescriptor.getId());
		assertEquals("Resolve forces into components", persistedDescriptor.getName());
		Path externalPdf = tempDir.resolve("Engineering Syllabus.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(externalPdf.toFile());
		}
		robot.interact(() -> {
			try {
				pane.attachSyllabusPdf(externalPdf);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		String relativePath = session.syllabusVersion().getSourcePdfPath();
		assertEquals("Engineering/2025/sources/Engineering-Syllabus.pdf", relativePath);
		Path managedPdf = curriculumRoot.resolve(relativePath);
		assertTrue(Files.isRegularFile(managedPdf));
		Files.delete(externalPdf);
		assertTrue(Files.isRegularFile(managedPdf));
		robot.interact(finalise::fire);
		assertEquals(CurriculumStatus.FINAL, session.syllabusVersion().getCurriculumStatus());
		assertTrue(save.isDisabled());
		assertTrue(finalise.isDisabled());
		assertTrue(browse.isDisabled());
		assertTrue(update.isDisabled());
		assertFalse(reopen.isDisabled());
		SyllabusVersion finalReload = repository.findVersionById(session.syllabusVersion().getId()).orElseThrow();
		assertEquals(CurriculumStatus.FINAL, finalReload.getCurriculumStatus());
		robot.interact(reopen::fire);
		assertEquals(CurriculumStatus.IN_PROGRESS, session.syllabusVersion().getCurriculumStatus());
		assertFalse(save.isDisabled());
		assertFalse(finalise.isDisabled());
		assertFalse(browse.isDisabled());
		assertFalse(update.isDisabled());
		assertTrue(reopen.isDisabled());
	}

	@Start
	void start(Stage stage) throws Exception {
		Path dataRoot = tempDir.resolve("data");
		Files.createDirectories(dataRoot);
		curriculumRoot = dataRoot.resolve("curriculum");
		database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);
		var unit = writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		var topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		var descriptor = writer.insertDescriptor(topic, "1.1.1", "Resolve forces", 0);
		descriptorPersistentId = descriptor.getId();
		session = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database)).load(syllabus);
		SqliteCurriculumAuthoringWriter authoringWriter = new SqliteCurriculumAuthoringWriter(database);
		CurriculumSourcePdfService sourcePdfService = new CurriculumSourcePdfService(
				new CurriculumSourcePdfStore(curriculumRoot), new SqliteCurriculumSourcePdfRepository(database));
		CurriculumLifecycleService lifecycleService = new CurriculumLifecycleService(authoringWriter,
				new SqliteCurriculumLifecycleRepository(database),
				Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"), ZoneOffset.UTC));
		pane = new CurriculumAuthoringPane(stage, curriculumRoot, session, authoringWriter, sourcePdfService,
				lifecycleService);
		stage.setScene(new Scene(pane, 1200, 760));
		stage.show();
	}

	@Test
	void tracksUnsavedDraftChangesAcrossSaveAndFinalise(FxRobot robot) throws Exception {
		@SuppressWarnings("unchecked")
		TreeView<au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode> tree = robot
				.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
		TextArea editText = robot.lookup("#curriculum-edit-text").queryAs(TextArea.class);
		Button update = robot.lookup("#update-curriculum-text").queryAs(Button.class);
		assertFalse(pane.hasUnsavedChanges());
		robot.interact(() -> {
			tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1.1"));
			editText.setText("Resolve force components");
			update.fire();
		});
		assertTrue(pane.hasUnsavedChanges());
		robot.interact(pane::saveCurriculum);
		assertFalse(pane.hasUnsavedChanges());
		robot.interact(() -> {
			editText.setText("Resolve force components graphically");
			update.fire();
		});
		assertTrue(pane.hasUnsavedChanges());
		robot.interact(pane::finaliseCurriculum);
		assertFalse(pane.hasUnsavedChanges());
		assertEquals(CurriculumStatus.FINAL, session.syllabusVersion().getCurriculumStatus());
	}

	private TreeItem<au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode> findByCode(
			TreeItem<au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode> item, String code) {
		var value = item.getValue();
		if (value != null && value.code().equals(code)) {
			return item;
		}
		for (var child : item.getChildren()) {
			var found = findByCode(child, code);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
