package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CurriculumExamWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void addSubjectCreatesAndActivatesStandaloneWorkingSubject(FxRobot robot) throws Exception {
		Button addSubject = lookup(robot, "#add-subject", Button.class);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");

		// Open the real application Subject-creation action. The dialog is modal, so
		// schedule the control while leaving the test thread available to complete it.
		Platform.runLater(addSubject::fire);
		waitForDialogShowing(robot, "Add Subject");
		DialogPane dialog = showingDialogPane(robot, "Add Subject");
		Node editorNode = dialog.lookup("#new-subject-name");
		assertTrue(editorNode instanceof TextField);
		TextField subjectName = (TextField) editorNode;
		robot.interact(() -> subjectName.setText("Geography"));
		Node okNode = dialog.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);
		robot.interact(((Button) okNode)::fire);
		WaitForAsyncUtils.waitForFxEvents();
		Subject geography = subjects.getItems().stream().filter(subject -> "Geography".equals(subject.getName()))
				.findFirst().orElseThrow(() -> new AssertionError("New Subject was not loaded into the selector"));

		// Creation must immediately establish the new Subject as authoritative
		// application context.
		assertEquals(geography, subjects.getValue());
		assertEquals(geography, field(application, "workingSubject", Subject.class));
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(
				new SqliteDatabase(databasePath));

		// The Subject itself must be persisted independently of curriculum creation.
		assertTrue(curriculumRepository.findAllSubjects().stream()
				.anyMatch(subject -> subject.getId() == geography.getId() && "Geography".equals(subject.getName())));
		assertTrue(curriculumRepository.findVersionsForSubject(geography).isEmpty());

		// Subject creation must likewise create no implicit Exam structure.
		SqliteExamWriter storedExamWriter = new SqliteExamWriter(new SqliteDatabase(databasePath));
		assertTrue(storedExamWriter.findExamsForSubject(geography).isEmpty());
	}

	@Test
	void applicationStartsOnCorpusDashboardHome(FxRobot robot) {
		Node home = robot.lookup("#corpus-dashboard-home").query();
		Node dashboardSection = robot.lookup("#corpus-dashboard-home-section").query();
		Node subjectHost = robot.lookup("#corpus-dashboard-subject-host").query();
		Node subjectContext = robot.lookup("#working-subject-context").query();
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");

		// The application opens on its operational home surface before any Subject has
		// been chosen.
		assertTrue(home.isVisible());
		assertNull(subjects.getValue());
		assertTrue(robot.lookup("#corpus-dashboard-no-subject").tryQuery().isPresent());

		// The one authoritative Subject row now belongs inside the Dashboard title
		// region rather than a full-width strip above the application.
		assertEquals(subjectHost, subjectContext.getParent());
		assertNotNull(dashboardSection);
		assertTrue(robot.lookup("#corpus-dashboard-refresh").tryQuery().isEmpty());

		// Capture remains constructed and reusable, but it is not the startup surface.
		assertTrue(robot.lookup("#workspace-split-pane").tryQuery().isEmpty());
	}

	@Test
	void canSelectAndPersistHistoricalSyllabusClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		assertEquals(2, syllabuses.getItems().size());
		assertNotNull(syllabuses.getValue());
		assertEquals("2025", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("1", units.getItems().getFirst().getCode());
		assertTrue(subtopics.getItems().isEmpty());
		assertNotNull(descriptors.getValue());
		assertEquals("2025", model.getClassification().getSyllabusVersion().getName());
		SyllabusVersion historicalSelection = selectSyllabus(robot, "2019");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("2019", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("3", units.getItems().getFirst().getCode());
		assertTrue(topics.getItems().isEmpty());
		assertTrue(subtopics.getItems().isEmpty());
		assertTrue(descriptors.getItems().isEmpty());
		assertFalse(units.isDisabled());
		assertTrue(topics.isDisabled());
		assertTrue(subtopics.isDisabled());
		assertTrue(descriptors.isDisabled());
		assertNull(units.getValue());
		assertNull(topics.getValue());
		assertNull(subtopics.getValue());
		assertNull(descriptors.getValue());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumNode historicalClassification = model.getClassification();
		assertNotNull(historicalClassification);
		assertEquals("3.1.1", historicalClassification.getCode());
		assertEquals(historicalSelection, historicalClassification.getSyllabusVersion());
		Question question = captureQuestion(robot, "H1");
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertEquals(historicalClassification.getId(), restored.getClassification().getId());
		assertEquals(historicalSelection, restored.getClassification().getSyllabusVersion());
		assertFalse(restored.getClassification().getSyllabusVersion().isCurrent());
		assertEquals(2024, restored.getExam().getYear());
		assertEquals(historicalSelection, syllabuses.getValue());
		assertEquals(historicalSelection, model.getSyllabusVersion());
		assertNull(model.getClassification());
		assertFalse(units.isDisabled());
	}

	@Test
	void canSelectSubjectWithOnlyHistoricalSyllabus(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		selectSubject(robot, "Biology");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			AtomicBoolean loaded = new AtomicBoolean();
			robot.interact(() ->

			// Biology deliberately has one historical syllabus and no current version.
			// Wait for that observable snapshot rather than assuming one FX pulse is
			// sufficient for the background persistence work.
			loaded.set(subjects.getValue() != null && "Biology".equals(subjects.getValue().getName())
					&& syllabuses.getItems().size() == 1 && "2019".equals(syllabuses.getItems().getFirst().getName())
					&& syllabuses.getValue() == null));
			return loaded.get();
		});
		assertEquals("Biology", subjects.getValue().getName());
		assertNull(examMetadataPane().getBooklet());
		assertEquals(1, syllabuses.getItems().size());
		assertEquals("2019", syllabuses.getItems().getFirst().getName());
		assertNull(syllabuses.getValue());
		assertFalse(syllabuses.isDisabled());
		assertTrue(units.getItems().isEmpty());
		assertTrue(units.isDisabled());
		SyllabusVersion historical = syllabuses.getItems().getFirst();
		robot.interact(() -> syllabuses.setValue(historical));
		WaitForAsyncUtils.waitForFxEvents();

		// Explicit historical-syllabus selection remains an ordinary curriculum
		// navigation operation after the Working Subject snapshot has completed.
		assertEquals(historical, syllabuses.getValue());
		assertEquals(1, units.getItems().size());
		assertEquals("2", units.getItems().getFirst().getCode());
		assertFalse(units.isDisabled());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		assertEquals(historical, model.getClassification().getSyllabusVersion());
		assertEquals("2.1.1", model.getClassification().getCode());
	}

	@Test
	void capturesQuestionWithSubtopicClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Physics");
		Question savedQuestion = captureQuestion(robot, "P1");
		assertEquals("Physics", savedQuestion.getExam().getSubject().getName());
		assertEquals(CurriculumLevel.SUBTOPIC, savedQuestion.getClassification().getLevel());
		assertEquals(savedQuestion.getExam().getSubject(),
				savedQuestion.getClassification().getSyllabusVersion().getSubject());
	}

	@Test
	void changingSubjectInvalidatesPreviouslySetExamMetadata(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		assertNotNull(examMetadataPane().getBooklet());
		PdfWorkspacePane workspace = pdfWorkspace();
		var pageView = lookup(robot, "#pdf-page-view", javafx.scene.image.ImageView.class);
		var pageLabel = lookup(robot, "#pdf-page-label", javafx.scene.control.Label.class);
		assertTrue(workspace.hasExamPdf());
		assertNotNull(pageView.getImage());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		selectSubject(robot, "Physics");

		// Accepted Subject change invalidates its previous Exam and PDF immediately;
		// it does not wait for the asynchronous Subject snapshot to finish loading.
		assertNull(examMetadataPane().getBooklet());
		assertEquals("Physics", subjects.getValue().getName());
		assertFalse(workspace.hasExamPdf());
		assertNull(workspace.getAnswerPdfSession());
		assertNull(pageView.getImage());
		assertEquals("No PDF selected", pageLabel.getText());
	}

	@Test
	void clearingSyllabusThenSubjectClearsDependentControls(FxRobot robot) throws Exception {

		// Classification is now a specialised Capture surface rather than startup
		// content. Enter Capture before testing its syllabus hierarchy.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showCaptureWorkspaceMode", new Class<?>[0]);
			return null;
		}).get();

		// Dynamic re-parenting is complete on the FX thread, but TestFX selector lookup
		// needs the following JavaFX pulse before querying the newly mounted subtree.
		WaitForAsyncUtils.waitForFxEvents();
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		selectSubject(robot, "Chemistry");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			AtomicBoolean loaded = new AtomicBoolean();
			robot.interact(() ->

			// Chemistry's current syllabus and root Unit prove that asynchronous
			// curriculum publication has completed.
			loaded.set(subjects.getValue() != null && "Chemistry".equals(subjects.getValue().getName())
					&& syllabuses.getItems().size() == 2 && syllabuses.getValue() != null
					&& "2025".equals(syllabuses.getValue().getName()) && !units.getItems().isEmpty()));
			return loaded.get();
		});

		// Classification controls are deliberately inactive while Question capture is
		// idle. Enter the production new-Question workflow before testing hierarchy
		// enablement rules.
		fireControl(robot, "#capture-mode-new");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		robot.interact(() -> syllabuses.getSelectionModel().clearSelection());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertNull(syllabuses.getValue());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertFalse(syllabuses.isDisabled());
		assertEquals(2, syllabuses.getItems().size());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, subtopics, descriptors)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
		selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		robot.interact(() ->

		// Clearing Working Subject is itself an accepted Subject transition, but it
		// requires no replacement persistence snapshot.
		subjects.getSelectionModel().clearSelection());
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertNull(syllabuses.getValue());
		assertTrue(syllabuses.getItems().isEmpty());
		assertTrue(syllabuses.isDisabled());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, subtopics, descriptors)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}

		// Clearing the authoritative application Subject must also clear the Subject
		// used to filter both capture queues.
		assertNull(field(application, "workingSubject", Subject.class));
		assertNull(field(questionCapturePane(), "workingSubject", Subject.class));
		assertNull(field(answerCapturePane(), "workingSubject", Subject.class));
	}

	@Test
	void corpusAuditInheritsWorkingSubject(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Subject workingSubject = field(application, "workingSubject", Subject.class);
		assertNotNull(workingSubject);
		Menu questionMenu = (Menu) invoke(application, "createQuestionMenu",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, applicationConfig);
		MenuItem dashboardItem = questionMenu.getItems().stream()
				.filter(item -> "question-corpus-audit".equals(item.getId())).findFirst()
				.orElseThrow(() -> new AssertionError("Questions -> Corpus Dashboard menu item not found"));

		// Corpus Dashboard is now ordinary main-window navigation rather than a modal
		// dialog.
		robot.interact(dashboardItem::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-exams").tryQuery().isPresent());
		Node home = robot.lookup("#corpus-dashboard-home").query();
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		assertTrue(home.isVisible());
		assertEquals(workingSubject, subjects.getValue());

		// The moved selector is the authoritative application control; no second
		// Dashboard Subject filter exists.
		assertNull(home.lookup("#corpus-dashboard-filter-subject"));
	}

	@Test
	void corpusDashboardRefreshesPersistedMappingReviewStatusWithoutChangingCorpusCompleteness(FxRobot robot)
			throws Exception {
		prepareExamAndClassification(robot);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		Subject chemistry = curriculumRepository.findAllSubjects().stream()
				.filter(subject -> "Chemistry".equals(subject.getName())).findFirst().orElseThrow();
		SyllabusVersion sourceVersion = curriculumRepository.findVersionsForSubject(chemistry).stream()
				.filter(version -> "2019".equals(version.getName())).findFirst().orElseThrow();
		SyllabusVersion targetVersion = curriculumRepository.findVersionsForSubject(chemistry).stream()
				.filter(SyllabusVersion::isCurrent).findFirst().orElseThrow();
		CurriculumNode sourceDescriptor = curriculumRepository.findByCode(sourceVersion, "3.1.1").orElseThrow();
		Menu questionMenu = (Menu) invoke(application, "createQuestionMenu",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, applicationConfig);
		MenuItem dashboardItem = questionMenu.getItems().stream()
				.filter(item -> "question-corpus-audit".equals(item.getId())).findFirst()
				.orElseThrow(() -> new AssertionError("Questions -> Corpus Dashboard menu item not found"));

		// Navigate back to the embedded production Dashboard and wait for its
		// asynchronous persistence generation.
		robot.interact(dashboardItem::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#corpus-dashboard-mapping-review").tryQuery().isPresent());
		Label mappingReview = lookup(robot, "#corpus-dashboard-mapping-review", Label.class);
		Button needsAttention = lookup(robot, "#corpus-dashboard-summary-attention", Button.class);
		assertTrue(mappingReview.getText().contains("2019 \u2192 2025"));
		assertTrue(mappingReview.getText().contains("0/1 resolved"));
		assertTrue(mappingReview.getText().contains("1 remaining"));
		String corpusAttentionBeforeReview = needsAttention.getText();

		// Persist a real review decision, then exercise the application-owned automatic
		// Dashboard refresh boundary used when curriculum workflows return home.
		new SqliteCurriculumMappingReviewWriter(database).confirmNoMatch(sourceDescriptor, targetVersion);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshCorpusDashboardHome", new Class<?>[] { long.class }, -1L);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> mappingReview.getText().contains("complete (1/1 resolved)"));

		// Mapping review remains independent of ordinary corpus completeness.
		assertEquals(corpusAttentionBeforeReview, needsAttention.getText());
	}

	@Test
	void corpusDashboardRemainsUsableWithoutWorkingSubject(FxRobot robot) throws Exception {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		robot.interact(() -> subjects.getSelectionModel().clearSelection());
		WaitForAsyncUtils.waitForFxEvents();
		assertNull(field(application, "workingSubject", Subject.class));
		Menu questionMenu = (Menu) invoke(application, "createQuestionMenu",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, applicationConfig);
		MenuItem dashboardItem = questionMenu.getItems().stream()
				.filter(item -> "question-corpus-audit".equals(item.getId())).findFirst()
				.orElseThrow(() -> new AssertionError("Questions -> Corpus Dashboard menu item not found"));

		// No prerequisite dialog is required because Subject selection itself now lives
		// on the home surface.
		robot.interact(dashboardItem::fire);
		assertTrue(robot.lookup("#corpus-dashboard-home").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-no-subject").tryQuery().isPresent());
		assertTrue(robot.lookup("#curriculum-subject").tryQuery().isPresent());
		assertTrue(robot.lookup("#corpus-dashboard-exams").tryQuery().isEmpty());
	}

	@Test
	void dashboardAnswerCaptureCanReturnWithoutSaving(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "DASH-ANSWER");
		AtomicBoolean returned = new AtomicBoolean();
		Boolean started = WaitForAsyncUtils.asyncFx(() -> (Boolean) invoke(application, "showDashboardAnswerCapture",
				new Class<?>[] { Question.class, Runnable.class }, question, (Runnable) () -> returned.set(true)))
				.get();
		assertTrue(started.booleanValue());
		Button returnToDashboard = lookup(robot, "#return-corpus-dashboard", Button.class);
		assertTrue(returnToDashboard.isVisible());
		assertTrue(returnToDashboard.isManaged());

		// The user can recognise an accidental Answer-capture route and return without
		// needing to save or switch capture modes.
		robot.interact(returnToDashboard::fire);
		assertTrue(returned.get());
		assertFalse(returnToDashboard.isVisible());
		assertFalse(returnToDashboard.isManaged());
	}

	@Test
	void dashboardAnswerCaptureHidesClassificationUntilReturn(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Question question = captureQuestion(robot, "DASH-CLASSIFICATION");
		Node classification = robot.lookup("#classification-context").query();
		assertTrue(classification.isVisible());
		assertTrue(classification.isManaged());
		assertFalse(classification.isDisable());
		AtomicBoolean returned = new AtomicBoolean();
		Boolean started = WaitForAsyncUtils.asyncFx(() -> (Boolean) invoke(application, "showDashboardAnswerCapture",
				new Class<?>[] { Question.class, Runnable.class }, question, (Runnable) () -> returned.set(true)))
				.get();
		assertTrue(started.booleanValue());

		// Answer capture uses already-persisted Question classification. Classification
		// is therefore removed from layout entirely rather than merely disabled.
		assertFalse(classification.isVisible());
		assertFalse(classification.isManaged());
		Button returnToDashboard = lookup(robot, "#return-corpus-dashboard", Button.class);
		robot.interact(returnToDashboard::fire);
		assertTrue(returned.get());

		// Ending the Dashboard task restores the reusable Capture workspace state for
		// whichever specialised workflow is entered next.
		assertTrue(classification.isVisible());
		assertTrue(classification.isManaged());
		assertFalse(classification.isDisable());
	}

	@Test
	void dashboardExamAssetsFitsInitialWorkspaceWidth(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showExamAssetsMode", new Class<?>[] { Exam.class, ExamBooklet.class, Runnable.class },
					booklet.getExam(), booklet, (Runnable) () -> {
					});
			return null;
		}).get();
		WaitForAsyncUtils.waitForFxEvents();
		ExamAssetsPane assets = field(application, "examAssetsPane", ExamAssetsPane.class);
		ScrollPane preview = field(application, "previewScrollPane", ScrollPane.class);

		// The Dashboard return action is the extra control that exposed the regression.
		Button returnButton = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnButton.isVisible());

		// Fit-to-width must be able to size Exam/Assets inside the normal left
		// viewport;
		// its minimum-content width must not push controls underneath the split
		// divider.
		assertTrue(assets.getWidth() <= preview.getViewportBounds().getWidth() + 0.5,
				"Exam / Assets must fit inside the left workspace viewport");
	}

	@Test
	void dashboardExamAssetsReturnWaitsForStructuralWorkToFinish(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		AtomicInteger returned = new AtomicInteger();
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showExamAssetsMode", new Class<?>[] { Exam.class, ExamBooklet.class, Runnable.class },
					booklet.getExam(), booklet, (Runnable) returned::incrementAndGet);
			return null;
		}).get();
		Button returnToDashboard = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnToDashboard.isVisible());
		assertTrue(returnToDashboard.isManaged());
		assertFalse(returnToDashboard.isDisable());
		Button editExam = lookup(robot, "#exam-assets-edit", Button.class);

		// A staged structural edit must disable Dashboard return rather than silently
		// discarding the user's work.
		robot.interact(editExam::fire);
		assertTrue(returnToDashboard.isDisable());
		Button cancelEdit = lookup(robot, "#exam-assets-cancel", Button.class);
		robot.interact(cancelEdit::fire);
		assertFalse(returnToDashboard.isDisable());

		// Once the structural workspace is settled, the configured return operation
		// runs exactly once and removes itself from Exam/Assets.
		robot.interact(returnToDashboard::fire);
		assertEquals(1, returned.get());
		assertFalse(returnToDashboard.isVisible());
		assertFalse(returnToDashboard.isManaged());
	}

	@Test
	void dashboardNewQuestionCaptureReturnClosesManagedPdf(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		AtomicBoolean returned = new AtomicBoolean();
		Boolean started = WaitForAsyncUtils
				.asyncFx(() -> (Boolean) invoke(application, "showDashboardNewQuestionCapture",
						new Class<?>[] { ExamBooklet.class, ApplicationConfig.class, Runnable.class }, booklet,
						applicationConfig, (Runnable) () -> returned.set(true)))
				.get();
		assertTrue(started.booleanValue());
		ImageView pageView = lookup(robot, "#pdf-page-view", ImageView.class);

		// Prove the Dashboard route really opened the managed Question PDF before
		// exercising the return path.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> pageView.getImage() != null);
		Button returnToDashboard = lookup(robot, "#return-corpus-dashboard", Button.class);
		robot.interact(returnToDashboard::fire);
		assertTrue(returned.get());

		// Returning from Dashboard-owned capture must not leave the temporary Exam PDF
		// visible underneath the Dashboard.
		assertNull(pageView.getImage());
	}

	@Test
	void dashboardStructuralRouteOpensRequestedExamAndBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		ExamAssetsPane assets = field(application, "examAssetsPane", ExamAssetsPane.class);

		// Exercise the application-level structural route rather than manipulating the
		// Exam/Assets controls directly.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showExamAssetsMode", new Class<?>[] { Exam.class, ExamBooklet.class, Runnable.class },
					booklet.getExam(), booklet, (Runnable) () -> {
					});
			return null;
		}).get();
		@SuppressWarnings("unchecked")
		ComboBox<Exam> exams = field(assets, "examBox", ComboBox.class);
		ToggleGroup bookletSelection = field(assets, "questionBookletSelectionGroup", ToggleGroup.class);
		assertNotNull(exams.getValue());
		assertEquals(booklet.getExam().getId(), exams.getValue().getId());
		assertNotNull(bookletSelection.getSelectedToggle());
		assertTrue(bookletSelection.getSelectedToggle().getUserData() instanceof ExamBooklet);
		ExamBooklet selectedBooklet = (ExamBooklet) bookletSelection.getSelectedToggle().getUserData();
		assertEquals(booklet.getId(), selectedBooklet.getId());

		// Structural routing selects the booklet for management but must not itself
		// change authoritative capture activation.
		assertEquals(booklet.getId(), examMetadataPane().getBooklet().getId());
	}

	@Test
	void examAssetsEditsExamLevelAssetExpectationsAndKeepsLabelsReadable(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showExamAssetsMode", new Class<?>[0]);
			return null;
		}).get();
		Label providerLabel = lookup(robot, "#exam-assets-provider-label", Label.class);
		Label expectedQuestionLabel = lookup(robot, "#exam-assets-expected-question-booklets-label", Label.class);
		Label expectedAnswerLabel = lookup(robot, "#exam-assets-expected-answer-booklets-label", Label.class);

		// Exam Details labels must retain their complete text beside expandable
		// editors.
		assertEquals(Region.USE_PREF_SIZE, providerLabel.getMinWidth());
		assertEquals(Region.USE_PREF_SIZE, expectedQuestionLabel.getMinWidth());
		assertEquals(Region.USE_PREF_SIZE, expectedAnswerLabel.getMinWidth());
		TextField expectedQuestions = lookup(robot, "#exam-assets-expected-question-booklets", TextField.class);
		TextField expectedAnswers = lookup(robot, "#exam-assets-expected-answer-booklets", TextField.class);
		Button edit = lookup(robot, "#exam-assets-edit", Button.class);
		Button save = lookup(robot, "#exam-assets-save", Button.class);
		assertTrue(expectedQuestions.isDisabled());
		assertTrue(expectedAnswers.isDisabled());
		robot.interact(edit::fire);
		assertFalse(expectedQuestions.isDisabled());
		assertFalse(expectedAnswers.isDisabled());
		robot.interact(() -> {

			// These are Exam-level asset expectations, not Expected Questions within an
			// individual Question booklet.
			expectedQuestions.setText("1");
			expectedAnswers.setText("0");
		});
		assertFalse(save.isDisable());
		robot.interact(save::fire);
		ExamAssetsPane assets = field(application, "examAssetsPane", ExamAssetsPane.class);
		@SuppressWarnings("unchecked")
		ComboBox<Exam> exams = field(assets, "examBox", ComboBox.class);
		Exam persistedExam = exams.getValue();
		assertNotNull(persistedExam);
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		ExamAssetExpectations expectations = writer.findExamAssetExpectations(persistedExam);
		assertEquals(Integer.valueOf(1), expectations.expectedQuestionBookletCount());
		assertEquals(Integer.valueOf(0), expectations.expectedAnswerFileCount());
	}

	@Test
	void importedExamPdfUsesSubjectProviderYearHierarchy(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Path expectedPath = pdfDataRoot.resolve("Chemistry").resolve("QCAA").resolve("2024")
				.resolve(examPdf.getFileName());
		assertTrue(Files.isRegularFile(expectedPath));
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		assertEquals(pdfDataRoot.relativize(expectedPath).toString(), booklet.getSourceDocument().getRelativePath());

		// Fresh Exam import records the SHA-256 identity of the final managed PDF.
		String expectedHash = new SourceDocumentHashService().sha256(expectedPath);
		assertEquals(expectedHash, booklet.getSourceDocument().getContentSha256());
	}

	@Test
	void knownManagedExamPdfReusesStoredMetadataAndBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet original = examMetadataPane().getBooklet();
		assertNotNull(original);
		long originalExamId = original.getExam().getId();
		long originalBookletId = original.getId();
		long originalSourceDocumentId = original.getSourceDocument().getId();
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		assertEquals(1, writer.findAllExamBooklets().size());

		// The current normal workflow reopens a persisted booklet through Exam/Assets
		// rather than by selecting its managed PDF through the retired import dialog.
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		RadioButton selectedBooklet = lookup(robot, "#exam-assets-question-select-" + originalBookletId,
				RadioButton.class);
		assertTrue(selectedBooklet.isSelected());
		Button useSelected = lookup(robot, "#exam-assets-use-selected-booklet", Button.class);
		assertFalse(useSelected.isDisabled());
		fireControl(robot, useSelected);
		WaitForAsyncUtils.waitForFxEvents();
		ExamBooklet reopened = examMetadataPane().getBooklet();
		assertNotNull(reopened);

		// Activating an existing Exam/Assets row must retain every authoritative
		// persistent identity rather than importing another Exam, booklet or source.
		assertEquals(originalExamId, reopened.getExam().getId());
		assertEquals(originalBookletId, reopened.getId());
		assertEquals(originalSourceDocumentId, reopened.getSourceDocument().getId());
		assertEquals(original.getExam().getProvider().getName(), reopened.getExam().getProvider().getName());
		assertEquals(original.getExam().getYear(), reopened.getExam().getYear());
		assertEquals(original.getExam().getName(), reopened.getExam().getName());
		assertEquals(original.getName(), reopened.getName());

		// A fresh repository read proves capture activation did not manufacture a
		// duplicate persisted hierarchy.
		assertEquals(1, writer.findAllExamBooklets().size());
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
	}

	@Test
	void legacyUnspecifiedBookletRequiresFormatWhenOpened(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);

		// Existing Questions must retain their own persisted response type when the
		// migrated booklet later acquires explicit format metadata.
		Question existingQuestion = captureQuestion(robot, "OLD1");
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, existingQuestion.getResponseType());
		SqliteDatabase database = new SqliteDatabase(databasePath);

		// Simulate a booklet migrated from a schema that had no Question-format field.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE exam_booklets
						SET question_format = 'UNSPECIFIED'
						WHERE id = ?
						""")) {
			statement.setLong(1, originalBooklet.getId());
			assertEquals(1, statement.executeUpdate());
		}
		SqliteExamWriter writer = new SqliteExamWriter(database);
		ExamBooklet legacyBooklet = writer
				.findExamBookletBySourceDocumentPath(originalBooklet.getSourceDocument().getRelativePath());
		assertNotNull(legacyBooklet);
		assertEquals(ExamBookletQuestionFormat.UNSPECIFIED, legacyBooklet.getQuestionFormat());

		// Exam/Assets is now the authoritative place for resolving migrated structural
		// metadata such as an unspecified Question-booklet format.
		fireControl(robot, "#change-exam-assets");
		WaitForAsyncUtils.waitForFxEvents();
		long bookletId = originalBooklet.getId();
		RadioButton multipleChoice = lookup(robot, "#exam-assets-question-format-mcq-" + bookletId, RadioButton.class);
		RadioButton writtenResponse = lookup(robot, "#exam-assets-question-format-written-" + bookletId,
				RadioButton.class);
		RadioButton both = lookup(robot, "#exam-assets-question-format-both-" + bookletId, RadioButton.class);
		Button edit = lookup(robot, "#exam-assets-question-edit-" + bookletId, Button.class);
		Button save = lookup(robot, "#exam-assets-question-save-" + bookletId, Button.class);

		// UNSPECIFIED is migration-only state. Exam/Assets represents it as no selected
		// normal format rather than exposing UNSPECIFIED as a user choice.
		assertFalse(multipleChoice.isSelected());
		assertFalse(writtenResponse.isSelected());
		assertFalse(both.isSelected());
		assertTrue(multipleChoice.isDisabled());
		assertTrue(writtenResponse.isDisabled());
		assertTrue(both.isDisabled());
		fireControl(robot, edit);
		assertFalse(both.isDisabled());

		// A migrated booklet cannot be saved from Edit mode until a real supported
		// Question format has been deliberately selected.
		assertTrue(save.isDisabled());
		fireControl(robot, both);
		assertFalse(save.isDisabled());
		fireControl(robot, save);
		WaitForAsyncUtils.waitForFxEvents();
		ExamBooklet persisted = writer
				.findExamBookletBySourceDocumentPath(originalBooklet.getSourceDocument().getRelativePath());
		assertNotNull(persisted);

		// Both in the UI maps to MIXED in authoritative persistence.
		assertEquals(ExamBookletQuestionFormat.MIXED, persisted.getQuestionFormat());
		Question reloadedExisting = new SqliteQuestionRepository(database).findById(existingQuestion.getId())
				.orElseThrow();

		// Correcting booklet structure must never rewrite an existing Question's
		// independently persisted response type.
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, reloadedExisting.getResponseType());
	}

	@Test
	void markingActiveExamCompletePersistsStateAndBlocksStructuralChange(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		assertNotNull(activeBooklet);
		assertFalse(activeBooklet.getExam().isComplete());
		MenuItem markComplete = examMenuItem("mark-active-exam-complete");

		// The menu action opens a modal confirmation, so schedule it on the FX thread
		// and let the test thread handle the resulting dialogs.
		Platform.runLater(markComplete::fire);
		waitForDialogShowing(robot, "Mark Exam Complete");
		fireDialogButton(robot, "Mark Complete");

		// Successful persistence produces the production confirmation alert.
		waitForDialogShowing(robot, "Exam State");
		fireDialogButton(robot, "OK");

		// The active capture object must immediately reflect the persisted lifecycle.
		ExamBooklet completedBooklet = examMetadataPane().getBooklet();
		assertNotNull(completedBooklet);
		assertTrue(completedBooklet.getExam().isComplete());
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		ExamBooklet reloaded = writer
				.findExamBookletBySourceDocumentPath(activeBooklet.getSourceDocument().getRelativePath());
		assertNotNull(reloaded);

		// A fresh read proves COMPLETE was persisted rather than existing only in the
		// JavaFX-side Exam object.
		assertTrue(reloaded.getExam().isComplete());

		// Booklet planning is structural and therefore provides a direct regression
		// check that completion now activates the repository lock.
		assertThrows(IllegalStateException.class,
				() -> writer.updateExamBookletPlanning(reloaded, reloaded.getQuestionFormat(), 20));
	}

	@Test
	void newExamBookletPersistsSelectedQuestionFormat(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "Paper 1",
				ExamBookletQuestionFormat.MULTIPLE_CHOICE);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);

		// The active object must immediately expose the explicit format selected while
		// importing the booklet.
		assertEquals(ExamBookletQuestionFormat.MULTIPLE_CHOICE, booklet.getQuestionFormat());
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		ExamBooklet restored = writer
				.findExamBookletBySourceDocumentPath(booklet.getSourceDocument().getRelativePath());

		// Re-reading from SQLite proves the UI selection was persisted rather than
		// existing only in the JavaFX form or active domain object.
		assertNotNull(restored);
		assertEquals(ExamBookletQuestionFormat.MULTIPLE_CHOICE, restored.getQuestionFormat());
	}

	@Test
	void reactivatingCompletedExamRestoresStructuralEditing(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		MenuItem markComplete = examMenuItem("mark-active-exam-complete");

		// First move the Exam through the real user-facing completion workflow.
		Platform.runLater(markComplete::fire);
		waitForDialogShowing(robot, "Mark Exam Complete");
		fireDialogButton(robot, "Mark Complete");
		waitForDialogShowing(robot, "Exam State");
		fireDialogButton(robot, "OK");
		assertTrue(examMetadataPane().getBooklet().getExam().isComplete());
		MenuItem reactivate = examMenuItem("reactivate-active-exam");

		// Reactivation itself does not require destructive confirmation, but the
		// production action reports successful persistence with an information alert.
		Platform.runLater(reactivate::fire);
		waitForDialogShowing(robot, "Exam State");
		fireDialogButton(robot, "OK");
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		assertNotNull(activeBooklet);
		assertFalse(activeBooklet.getExam().isComplete());
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		ExamBooklet reloaded = writer
				.findExamBookletBySourceDocumentPath(originalBooklet.getSourceDocument().getRelativePath());
		assertNotNull(reloaded);

		// Reloading proves Reactivate persisted ACTIVE rather than merely changing the
		// active UI object.
		assertFalse(reloaded.getExam().isComplete());

		// Structural editing must work again after explicit reactivation.
		ExamBooklet replanned = writer.updateExamBookletPlanning(reloaded, reloaded.getQuestionFormat(), 20);
		assertEquals(Integer.valueOf(20), replanned.getExpectedQuestionCount());
	}

	@Test
	void refreshingSubjectsReloadsVersionsWithoutReplacingHistoricalSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		SyllabusVersion historical = selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode classification = model.getClassification();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(new SqliteDatabase(databasePath));
		SyllabusVersion imported = writer.insertSyllabusVersion(historical.getSubject(), "2015", false);
		writer.insertSubject("Astronomy");
		CurriculumSelectorPane pane = field(application, "curriculumSelectorPane", CurriculumSelectorPane.class);
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		robot.interact(pane::refreshSubjects);
		assertEquals(3, syllabuses.getItems().size());
		assertTrue(syllabuses.getItems().contains(imported));
		assertEquals(historical, syllabuses.getValue());
		assertEquals(historical, model.getSyllabusVersion());
		assertEquals(historical.getSubject(), model.getSubject());
		assertEquals(classification, model.getClassification());
		assertClassificationControlShows(robot, classification);
		assertEquals("3", units.getItems().getFirst().getCode());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		assertEquals(4, comboBox(robot, "#curriculum-subject").getItems().size());
	}

	@Test
	void replacesActiveQuestionPdfAfterExplicitImpactConfirmation(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		Question originalQuestion = captureQuestion(robot, "REPLACE1");
		assertFalse(originalQuestion.getRegions().isEmpty());
		long questionId = originalQuestion.getId();
		long bookletId = originalBooklet.getId();
		long sourceDocumentId = originalBooklet.getSourceDocument().getId();
		Path replacementPdf = createReplacementQuestionPdf(
				databasePath.getParent().resolve("replacement-question-booklet.pdf"));
		String replacementHash = new SourceDocumentHashService().sha256(replacementPdf);
		assertFalse(replacementHash.equals(originalBooklet.getSourceDocument().getContentSha256()));

		// Invoke the production replacement workflow with an explicit path so this
		// behavioural test does not automate the platform-native FileChooser.
		Platform.runLater(() -> {
			try {
				invoke(application, "replaceActiveQuestionPdf",
						new Class<?>[] { Stage.class, ApplicationConfig.class, Path.class }, primaryStage,
						applicationConfig, replacementPdf);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		waitForDialogShowing(robot, "Replace Question PDF");
		DialogPane confirmation = showingDialogPane(robot, "Replace Question PDF");
		assertNotNull(confirmation);
		assertEquals("Existing PDF-derived capture will be invalidated.", confirmation.getHeaderText());
		ButtonType replaceButton = confirmation.getButtonTypes().stream()
				.filter(buttonType -> "Replace PDF".equals(buttonType.getText())).findFirst().orElseThrow();
		Node replaceNode = confirmation.lookupButton(replaceButton);
		assertTrue(replaceNode instanceof Button);

		// Fire the DialogPane-owned semantic action rather than locating rendered text.
		robot.interact(((Button) replaceNode)::fire);
		waitForDialogShowing(robot, "Question PDF Replaced");
		DialogPane success = showingDialogPane(robot, "Question PDF Replaced");
		assertNotNull(success);
		Node okNode = success.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);
		robot.interact(((Button) okNode)::fire);
		waitForDialogHidden(robot, "Question PDF Replaced");
		WaitForAsyncUtils.waitForFxEvents();
		Question reloaded = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(questionId)
				.orElseThrow();

		// The old PDF coordinates are invalidated without deleting Question identity or
		// ordinary metadata.
		assertEquals(questionId, reloaded.getId());
		assertEquals("REPLACE1", reloaded.getQuestionCode());
		assertEquals(originalQuestion.getMarks(), reloaded.getMarks());
		assertEquals(originalQuestion.getClassification().getId(), reloaded.getClassification().getId());
		assertEquals(originalQuestion.getResponseType(), reloaded.getResponseType());
		assertTrue(reloaded.getRegions().isEmpty());
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		assertNotNull(activeBooklet);

		// Replacement does not manufacture a new Booklet or SourceDocument identity.
		assertEquals(bookletId, activeBooklet.getId());
		assertEquals(sourceDocumentId, activeBooklet.getSourceDocument().getId());
		assertEquals(replacementHash, activeBooklet.getSourceDocument().getContentSha256());
		Path managedPdf = new PdfStore(pdfDataRoot).resolve(activeBooklet.getSourceDocument().getRelativePath());

		// The managed asset now contains the selected replacement bytes and the
		// workspace has successfully reopened that valid PDF.
		assertEquals(replacementHash, new SourceDocumentHashService().sha256(managedPdf));
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
	}

	@Test
	void resettingClassificationWithoutSyllabusKeepsUnitsDisabled(FxRobot robot) throws Exception {

		// Classification controls live in the Capture workspace now that Dashboard is
		// the application home surface.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showCaptureWorkspaceMode", new Class<?>[0]);
			return null;
		}).get();

		// Allow the dynamically mounted Capture subtree to participate in the scene
		// before TestFX performs selector-based lookup.
		WaitForAsyncUtils.waitForFxEvents();
		CurriculumSelectorPane pane = field(application, "curriculumSelectorPane", CurriculumSelectorPane.class);

		// This test exercises CurriculumSelectorPane dependency state rather than the
		// application's idle-capture state, so activate Classification first.
		fireControl(robot, "#capture-mode-new");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		robot.interact(pane::clearClassificationBelowSubject);
		assertTrue(units.isDisabled());
		selectSubject(robot, "Biology");
		robot.interact(pane::clearClassificationBelowSubject);
		assertTrue(units.isDisabled());
		assertNull(syllabuses.getValue());
		assertFalse(syllabuses.isDisabled());
	}

	@Test
	void searchQuestionsRequiresWorkingSubject(FxRobot robot) throws Exception {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");

		// Reproduce the production failure state explicitly: Search remains a valid
		// menu action even though no authoritative Working Subject is selected.
		robot.interact(() -> subjects.getSelectionModel().clearSelection());
		WaitForAsyncUtils.waitForFxEvents();
		assertNull(field(application, "workingSubject", Subject.class));

		// Build and fire the real Questions menu action rather than calling the Search
		// dialog constructor directly, because the defect occurred in application
		// wiring.
		Menu questionMenu = (Menu) invoke(application, "createQuestionMenu",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, applicationConfig);
		MenuItem searchItem = questionMenu.getItems().stream().filter(item -> "_Search...".equals(item.getText()))
				.findFirst().orElseThrow(() -> new AssertionError("Questions -> Search menu item not found"));

		// Search normally enters a modal showAndWait() loop, so schedule the action and
		// leave the test thread available to inspect the prerequisite warning.
		Platform.runLater(searchItem::fire);
		waitForDialogShowing(robot, "Search Questions");
		DialogPane warning = showingDialogPane(robot, "Search Questions");
		assertEquals("No Working Subject is selected.", warning.getHeaderText());

		// Resolve the warning through its actual DialogPane button so the test remains
		// deterministic under both desktop JavaFX and the headless CI harness.
		Node okNode = warning.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);
		robot.interact(((Button) okNode)::fire);
		WaitForAsyncUtils.waitForFxEvents();

		// A missing Working Subject must stop before the actual Search surface is
		// built.
		assertTrue(robot.lookup("#question-search-results").tryQuery().isEmpty());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	@Test
	void subjectChangeInvalidatesDashboardExamAssetsReturn(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		AtomicInteger returned = new AtomicInteger();
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "showExamAssetsMode", new Class<?>[] { Exam.class, ExamBooklet.class, Runnable.class },
					booklet.getExam(), booklet, (Runnable) returned::incrementAndGet);
			return null;
		}).get();
		Button returnToDashboard = lookup(robot, "#exam-assets-return-dashboard", Button.class);
		assertTrue(returnToDashboard.isVisible());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject otherSubject = subjects.getItems().stream().filter(subject -> !subject.equals(subjects.getValue()))
				.findFirst().orElseThrow();

		// Changing the authoritative Working Subject invalidates the Dashboard session
		// from which Exam/Assets was originally launched.
		robot.interact(() -> subjects.setValue(otherSubject));
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !returnToDashboard.isVisible());
		assertFalse(returnToDashboard.isVisible());
		assertFalse(returnToDashboard.isManaged());
		assertEquals(0, returned.get());
		Node busyOverlay = lookup(robot, "#workspace-busy-overlay", Node.class);

		// No stale transition from the previous Subject may leave the workspace covered
		// by an indefinite spinner.
		assertFalse(busyOverlay.isVisible());
	}

	@Test
	void workingSubjectLoadFailureLeavesNewSubjectVisibleButDependentStateEmpty(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		Subject physics = subjects.getItems().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		setField(application, "workingSubjectCurriculumSnapshotLoader",
				(Function<Subject, CurriculumSelectionModel.SubjectSnapshot>) _ -> {

					// Simulate a persistence failure before any new Subject-dependent
					// curriculum or Question data can be published.
					throw new IllegalStateException("Synthetic curriculum load failure");
				});
		robot.interact(() ->

		// Direct selection is deterministic because pointer behaviour is not under
		// test.
		subjects.getSelectionModel().select(physics));
		waitForDialogShowing(robot, "Working Subject");
		DialogPane errorDialog = showingDialogPane(robot, "Working Subject");
		assertNotNull(errorDialog);
		assertEquals("Data for the Working Subject could not be loaded.", errorDialog.getHeaderText());

		// The accepted Subject itself remains authoritative even though its dependent
		// persistence snapshot failed.
		assertEquals(physics, subjects.getValue());
		assertEquals(physics, field(application, "workingSubject", Subject.class));
		assertEquals(physics, model.getSubject());
		assertEquals(physics, field(questionCapturePane(), "workingSubject", Subject.class));
		assertEquals(physics, field(answerCapturePane(), "workingSubject", Subject.class));

		// Previous Chemistry state was cleared synchronously before the worker ran, so
		// failure cannot leave it presented as if it belonged to Physics.
		assertNull(model.getSyllabusVersion());
		assertNull(model.getClassification());
		assertTrue(syllabuses.getItems().isEmpty());
		assertNull(syllabuses.getValue());
		assertTrue(units.getItems().isEmpty());
		assertNull(examMetadataPane().getBooklet());
		Node okNode = errorDialog.lookupButton(ButtonType.OK);
		assertTrue(okNode instanceof Button);

		// Close the actual DialogPane-owned control without pointer hit-testing.
		robot.interact(((Button) okNode)::fire);
		waitForDialogHidden(robot, "Working Subject");
	}

	@Test
	void workspaceBusyOverlayShowsAndClears(FxRobot robot) throws Exception {
		WaitForAsyncUtils.asyncFx(() -> {

			// Reproduce the real Dashboard transition that previously removed the busy
			// overlay from the workspace host.
			invoke(application, "showCaptureWorkspaceMode", new Class<?>[0]);
			invoke(application, "showWorkspaceBusy", new Class<?>[] { String.class }, "Opening Question booklet...");
			return null;
		}).get();

		// The workspace was dynamically re-parented into the application body. Wait for
		// its JavaFX pulse before locating controls through TestFX.
		WaitForAsyncUtils.waitForFxEvents();
		Node overlay = lookup(robot, "#workspace-busy-overlay", Node.class);
		Label message = lookup(robot, "#workspace-busy-label", Label.class);
		assertTrue(overlay.isVisible());
		assertTrue(overlay.isManaged());
		assertEquals("Opening Question booklet...", message.getText());
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "hideWorkspaceBusy", new Class<?>[0]);
			return null;
		}).get();

		// Completion hides progress without removing it from the reusable workspace.
		assertFalse(overlay.isVisible());
		assertFalse(overlay.isManaged());
		assertNotNull(overlay.getParent());
	}

	private Path createReplacementQuestionPdf(Path path) throws Exception {
		try (PDDocument document = new PDDocument()) {

			// Use three blank pages so the replacement is a valid but byte-distinct PDF
			// from the standard two-page Exam fixture.
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		return path;
	}

	private MenuItem examMenuItem(String itemId) throws Exception {

		// Build the production Exam menu so the test fires the same action handler as
		// the real menu rather than invoking lifecycle persistence directly.
		Menu examMenu = (Menu) invoke(application, "createExamMenu",
				new Class<?>[] { Stage.class, ApplicationConfig.class }, primaryStage, applicationConfig);
		return examMenu.getItems().stream().filter(item -> itemId.equals(item.getId())).findFirst()
				.orElseThrow(() -> new AssertionError("Exam menu item not found: " + itemId));
	}

	private void selectSubject(FxRobot robot, String subjectName) {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject selectedSubject = null;
		for (Subject subject : subjects.getItems()) {
			if (subjectName.equals(subject.getName())) {
				selectedSubject = subject;
				break;
			}
		}
		assertNotNull(selectedSubject);
		Subject subjectSelection = selectedSubject;
		robot.interact(() -> subjects.setValue(subjectSelection));
	}

	private SyllabusVersion selectSyllabus(FxRobot robot, String name) {
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		for (SyllabusVersion version : syllabuses.getItems()) {
			if (name.equals(version.getName())) {
				robot.interact(() -> syllabuses.setValue(version));
				return version;
			}
		}
		throw new AssertionError("Syllabus not found: " + name);
	}
}
