package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CurriculumAuthoringApplicationRegressionTest {

	@TempDir
	Path tempDir;
	private QuestionBankApplication application;
	private Stage primaryStage;
	private SqliteDatabase database;
	private final AtomicInteger exitRequests = new AtomicInteger();

	@Test
	void applicationExitProtectsAppliedButUnsavedDraft(FxRobot robot) throws Exception {
		assertExitProtectsCurriculum(robot, true);
	}

	@Test
	void applicationExitProtectsUnappliedText(FxRobot robot) throws Exception {
		assertExitProtectsCurriculum(robot, false);
	}

	@Test
	void applicationExitSavesPendingTextBeforeShutdown(FxRobot robot) throws Exception {
		openExisting(robot);
		editExistingDescriptor(robot, false);
		Platform.runLater(
				() -> Event.fireEvent(primaryStage, new WindowEvent(primaryStage, WindowEvent.WINDOW_CLOSE_REQUEST)));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			DialogPane dialog = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
			assertEquals("Save changes before closing?", dialog.getHeaderText());
			var save = dialog.getButtonTypes().stream()
					.filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
			((Button) dialog.lookupButton(save)).fire();
		});
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1, exitRequests.get());
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Subject subject = repository.findAllSubjects().getFirst();
		var version = repository.findVersionsForSubject(subject).getFirst();
		assertEquals("Edited descriptor", repository.findByCode(version, "1.1.1").orElseThrow().getName());
	}

	@Test
	void cancellingCloseRetainsAppliedUnsavedDraft(FxRobot robot) throws Exception {
		openExisting(robot);
		Stage authoring = authoringStage(robot);
		editExistingDescriptor(robot, true);
		Platform.runLater(
				() -> Event.fireEvent(authoring, new WindowEvent(authoring, WindowEvent.WINDOW_CLOSE_REQUEST)));
		WaitForAsyncUtils.waitForFxEvents();
		AtomicBoolean prompted = new AtomicBoolean();
		robot.interact(() -> prompted.set(cancelUnsavedDialog()));
		assertTrue(prompted.get(), "Applied unsaved work must prompt before closing");
		assertTrue(authoring.isShowing(), "Cancel must retain the editing session");
		assertEquals("Edited descriptor", robot.lookup("#curriculum-edit-text").queryAs(TextArea.class).getText());
		assertDuplicateRejected(robot);
	}

	@AfterEach
	void close(FxRobot robot) throws Exception {
		robot.interact(() -> {
			cancelUnsavedDialog();
			for (Window window : List.copyOf(Window.getWindows())) {
				if (window != primaryStage) {
					window.hide();
				}
			}
			// Detach controls while their database still exists: losing focus can
			// trigger curriculum lookups during TestFX's later window cleanup.
			primaryStage.hide();
			primaryStage.setScene(null);
		});
		WaitForAsyncUtils.waitForFxEvents();
		application.stop();
	}

	@Test
	void closingAuthoringWindowProtectsUnappliedText(FxRobot robot) throws Exception {
		openExisting(robot);
		Stage authoring = authoringStage(robot);
		editExistingDescriptor(robot, false);
		Platform.runLater(
				() -> Event.fireEvent(authoring, new WindowEvent(authoring, WindowEvent.WINDOW_CLOSE_REQUEST)));
		WaitForAsyncUtils.waitForFxEvents();
		AtomicBoolean prompted = new AtomicBoolean();
		robot.interact(() -> prompted.set(cancelUnsavedDialog()));
		assertAll(() -> assertTrue(prompted.get(), "Closing must offer to save or discard unapplied text"),
				() -> assertTrue(authoring.isShowing(), "Cancel must keep the authoring session open"));
	}

	@Test
	void differentSyllabusVersionsCanBeOpenTogether(FxRobot robot) throws Exception {
		openExisting(robot);
		Subject subject = new SqliteCurriculumRepository(database).findAllSubjects().getFirst();
		new SqliteCurriculumWriter(database).insertSyllabusVersion(subject, "2026", false);
		openAuthoringChooser(robot);
		fireDialogButton(robot, "Open or create a curriculum", "Open Existing");
		DialogPane existingDialog = awaitVisibleDialog(robot, "Choose an existing syllabus to edit");
		robot.interact(() -> {
			DialogPane dialog = existingDialog;
			ComboBox<?> choices = (ComboBox<?>) dialog.lookup(".combo-box");
			for (int i = 0; i < choices.getItems().size(); i++) {
				if (((SyllabusVersion) choices.getItems().get(i)).getName().equals("2026")) {
					choices.getSelectionModel().select(i);
					break;
				}
			}
		});
		fireDialogButton(robot, "Choose an existing syllabus to edit", "OK");
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> assertEquals(2, authoringWindows().size()));
	}

	@Test
	void duplicateOpenIsRejectedAndClosingReleasesTheSyllabus(FxRobot robot) throws Exception {
		openExisting(robot);
		Stage first = authoringStage(robot);
		assertDuplicateRejected(robot);
		robot.interact(() -> Event.fireEvent(first, new WindowEvent(first, WindowEvent.WINDOW_CLOSE_REQUEST)));
		assertFalse(first.isShowing());
		openExisting(robot);
		assertNotSame(first, authoringStage(robot));
		robot.interact(() -> assertEquals(1, authoringWindows().size()));
	}

	@Test
	void finalViewAlsoReservesTheSyllabusBeforeReopening(FxRobot robot) throws Exception {
		openExisting(robot);
		Stage first = authoringStage(robot);
		robot.interact(() -> robot.lookup("#finalise-curriculum").queryAs(Button.class).fire());
		assertDuplicateRejected(robot);
		robot.interact(() -> {
			((CurriculumAuthoringPane) first.getScene().getRoot()).reopenCurriculum();
			assertEquals(1, authoringWindows().size());
		});
		assertDuplicateRejected(robot);
	}

	@Test
	void newlyAuthoredSubjectBecomesAvailableToCaptureWithoutRestart(FxRobot robot) throws Exception {
		openAuthoringChooser(robot);
		robot.clickOn("New Curriculum");
		robot.clickOn("#new-curriculum-subject");
		robot.clickOn("#new-curriculum-subject-name").write("Engineering");
		robot.clickOn("#new-curriculum-version").write("2025");
		robot.clickOn("#new-curriculum-current");
		robot.clickOn("#create-curriculum");
		awaitAuthoring(robot);
		CurriculumAuthoringPane pane = (CurriculumAuthoringPane) robot.lookup("#curriculum-draft-tree").query()
				.getScene().getRoot();
		Path pdf = createSyllabusPdf();
		robot.interact(() -> {
			try {
				pane.attachSyllabusPdf(pdf);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			captureText(robot, "Fundamentals", "#add-curriculum-unit");
			captureText(robot, "Forces", "#add-curriculum-topic");
			var tree = tree(robot);
			tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst().getChildren().getFirst());
			captureText(robot, "Resolve forces", "#add-curriculum-descriptor");
			robot.lookup("#save-curriculum").queryAs(Button.class).fire();
		});
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Subject engineering = repository.findAllSubjects().stream()
				.filter(subject -> subject.getName().equals("Engineering")).findFirst().orElseThrow();
		var version = repository.findVersionsForSubject(engineering).getFirst();
		assertEquals("Resolve forces", repository.findByCode(version, "1.1.1").orElseThrow().getName());
		Stage authoring = authoringStage(robot);
		robot.interact(() -> Event.fireEvent(authoring, new WindowEvent(authoring, WindowEvent.WINDOW_CLOSE_REQUEST)));
		assertTrue(subjects(robot, "#curriculum-subject").contains(engineering),
				"Classification must offer the newly authored subject");
	}

	@Test
	void savedAuthoringChangesRefreshExistingClassificationChoices(FxRobot robot) throws Exception {
		robot.interact(() -> {
			for (String id : List.of("#curriculum-subject", "#curriculum-syllabus", "#curriculum-unit",
					"#curriculum-topic", "#curriculum-descriptor")) {
				robot.lookup(id).queryAs(ComboBox.class).getSelectionModel().selectFirst();
			}
		});
		openExisting(robot);
		editExistingDescriptor(robot, true);
		robot.interact(() -> robot.lookup("#save-curriculum").queryAs(Button.class).fire());
		Stage authoring = authoringStage(robot);
		robot.interact(() -> Event.fireEvent(authoring, new WindowEvent(authoring, WindowEvent.WINDOW_CLOSE_REQUEST)));
		ComboBox<?> choices = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);
		assertTrue(
				choices.getItems().stream().filter(CurriculumNode.class::isInstance).map(CurriculumNode.class::cast)
						.anyMatch(node -> node.getName().equals("Edited descriptor")),
				"Classification choices must reflect persisted authoring changes");
	}

	@Start
	void start(Stage stage) throws Exception {
		Path pdfRoot = Files.createDirectories(tempDir.resolve("pdf"));
		Path curriculumRoot = Files.createDirectories(tempDir.resolve("curriculum"));
		database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		var version = writer.insertSyllabusVersion(chemistry, "2025", true);
		var unit = writer.insertUnit(version, "1", "Unit", 0);
		var topic = writer.insertTopic(unit, "1.1", "Topic", 0);
		writer.insertDescriptor(topic, "1.1.1", "Original descriptor", 0);
		application = new QuestionBankApplication();
		primaryStage = stage;
		// Use the application's existing bootstrap and exit seam, as in its workflow
		// tests. No test may call Platform.exit and terminate the shared FX toolkit.
		Method start = QuestionBankApplication.class.getDeclaredMethod("startApplication", Stage.class,
				ApplicationConfig.class);
		start.setAccessible(true);
		start.invoke(application, stage, new ApplicationConfig(pdfRoot, curriculumRoot, databasePath()));
		Field exitAction = QuestionBankApplication.class.getDeclaredField("applicationExitAction");
		exitAction.setAccessible(true);
		exitAction.set(application, (Runnable) exitRequests::incrementAndGet);
	}

	private void assertDuplicateRejected(FxRobot robot) throws Exception {
		openAuthoringChooser(robot);
		fireDialogButton(robot, "Open or create a curriculum", "Open Existing");
		fireDialogButton(robot, "Choose an existing syllabus to edit", "OK");
		awaitVisibleDialog(robot, "This curriculum is already open for editing.");
		robot.interact(() -> assertEquals(1, authoringWindows().size()));
		fireDialogButton(robot, "This curriculum is already open for editing.", "OK");
	}

	private void assertExitProtectsCurriculum(FxRobot robot, boolean applyText) throws Exception {
		openExisting(robot);
		Stage authoring = authoringStage(robot);
		editExistingDescriptor(robot, applyText);
		Platform.runLater(
				() -> Event.fireEvent(primaryStage, new WindowEvent(primaryStage, WindowEvent.WINDOW_CLOSE_REQUEST)));
		WaitForAsyncUtils.waitForFxEvents();
		AtomicBoolean prompted = new AtomicBoolean();
		robot.interact(() -> prompted.set(cancelUnsavedDialog()));
		assertAll(() -> assertTrue(prompted.get(), "Application exit must check the authoring session"),
				() -> assertEquals(0, exitRequests.get(), "Cancel must prevent application exit"),
				() -> assertTrue(authoring.isShowing()));
	}

	private Stage authoringStage(FxRobot robot) {
		return (Stage) robot.lookup("#curriculum-draft-tree").query().getScene().getWindow();
	}

	private List<Window> authoringWindows() {
		return Window.getWindows().stream().filter(
				window -> window.getScene() != null && window.getScene().getRoot() instanceof CurriculumAuthoringPane)
				.toList();
	}

	private void awaitAuthoring(FxRobot robot) throws Exception {
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup("#curriculum-draft-tree").tryQuery().isPresent());
	}

	private DialogPane awaitVisibleDialog(FxRobot robot, String headerText) throws Exception {
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> robot.lookup(".dialog-pane").queryAll().stream().filter(DialogPane.class::isInstance)
						.map(DialogPane.class::cast)
						.anyMatch(dialog -> dialog.isVisible() && headerText.equals(dialog.getHeaderText())));
		return robot.lookup(".dialog-pane").queryAll().stream().filter(DialogPane.class::isInstance)
				.map(DialogPane.class::cast).filter(DialogPane::isVisible)
				.filter(dialog -> headerText.equals(dialog.getHeaderText())).findFirst().orElseThrow();
	}

	private boolean cancelUnsavedDialog() {
		for (Window window : List.copyOf(Window.getWindows())) {
			if (window.getScene() == null) {
				continue;
			}
			if (window.getScene().getRoot().lookup(".dialog-pane") instanceof DialogPane dialog
					&& "Save changes before closing?".equals(dialog.getHeaderText())) {
				var cancel = dialog.getButtonTypes().stream()
						.filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE).findFirst()
						.orElseThrow();
				((Button) dialog.lookupButton(cancel)).fire();
				return true;
			}
		}
		return false;
	}

	private void captureText(FxRobot robot, String wording, String buttonId) {
		TextArea text = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		int start = text.getText().indexOf(wording);
		assertTrue(start >= 0, "Generated PDF must contain the fixture wording");
		text.selectRange(start, start + wording.length());
		robot.lookup(buttonId).queryAs(Button.class).fire();
	}

	private Path createSyllabusPdf() throws Exception {
		Path path = tempDir.resolve("syllabus.pdf");
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("Fundamentals Forces Resolve forces");
				content.endText();
			}
			document.save(path.toFile());
		}
		return path;
	}

	private Path databasePath() {
		return tempDir.resolve("questionbank.db");
	}

	private void editExistingDescriptor(FxRobot robot, boolean apply) {
		robot.interact(() -> {
			var tree = tree(robot);
			var descriptor = tree.getRoot().getChildren().getFirst().getChildren().getFirst().getChildren().getFirst();
			tree.getSelectionModel().select(descriptor);
			robot.lookup("#curriculum-edit-text").queryAs(TextArea.class).setText("Edited descriptor");
			if (apply) {
				robot.lookup("#update-curriculum-text").queryAs(Button.class).fire();
			}
		});
	}

	private void fireDialogButton(FxRobot robot, String headerText, String buttonText) throws Exception {
		DialogPane dialog = awaitVisibleDialog(robot, headerText);
		robot.interact(() -> {
			ButtonType buttonType = dialog.getButtonTypes().stream().filter(type -> buttonText.equals(type.getText()))
					.findFirst().orElseThrow();
			((Button) dialog.lookupButton(buttonType)).fire();
		});
		WaitForAsyncUtils.waitForFxEvents();
	}

	private void openAuthoringChooser(FxRobot robot) throws Exception {
		MenuBar menuBar = robot.lookup(node -> node instanceof MenuBar).queryAs(MenuBar.class);
		var item = menuBar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
				.filter(menu -> "author-curriculum-pdf".equals(menu.getId())).findFirst().orElseThrow();
		Platform.runLater(item::fire);
		awaitVisibleDialog(robot, "Open or create a curriculum");
	}

	private void openExisting(FxRobot robot) throws Exception {
		openAuthoringChooser(robot);
		fireDialogButton(robot, "Open or create a curriculum", "Open Existing");
		fireDialogButton(robot, "Choose an existing syllabus to edit", "OK");
		awaitAuthoring(robot);
	}

	private List<Subject> subjects(FxRobot robot, String id) {
		ComboBox<?> box = robot.lookup(id).queryAs(ComboBox.class);
		return box.getItems().stream().map(Subject.class::cast).toList();
	}

	@SuppressWarnings("unchecked")
	private TreeView<CurriculumDraftNode> tree(FxRobot robot) {
		return robot.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
	}
}
