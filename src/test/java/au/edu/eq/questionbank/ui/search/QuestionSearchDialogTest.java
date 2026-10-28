package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

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
	void actionButtonsShareDialogRowAndUseEqualWidth(FxRobot robot) {
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		robot.interact(() -> extracted(curriculumRepository, retrievalService, previewService, dialogHolder));
		QuestionSearchDialog dialog = dialogHolder[0];
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();
		Button editQuestion = robot.lookup("#question-search-edit-question").queryButton();
		Button splitQuestion = robot.lookup("#question-search-split-question").queryButton();
		Button editMetadata = robot.lookup("#question-search-edit-metadata").queryButton();
		Button sharedContext = robot.lookup("#question-search-recapture-shared-context").queryButton();
		Button editAnswer = robot.lookup("#question-search-edit-answer").queryButton();
		Button close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE);

		// The five Question-edit actions participate in one ButtonBar uniform-size
		// group, so their actual width follows the widest action label.
		assertTrue(ButtonBar.isButtonUniformSize(editQuestion));
		assertTrue(ButtonBar.isButtonUniformSize(splitQuestion));
		assertTrue(ButtonBar.isButtonUniformSize(editMetadata));
		assertTrue(ButtonBar.isButtonUniformSize(sharedContext));
		assertTrue(ButtonBar.isButtonUniformSize(editAnswer));
		assertEquals(editQuestion.getWidth(), splitQuestion.getWidth(), 1.0);
		assertEquals(editQuestion.getWidth(), editMetadata.getWidth(), 1.0);
		assertEquals(editQuestion.getWidth(), sharedContext.getWidth(), 1.0);
		assertEquals(editQuestion.getWidth(), editAnswer.getWidth(), 1.0);

		// Close shares the same row but is not widened to match the edit actions.
		assertFalse(ButtonBar.isButtonUniformSize(close));
		assertTrue(editQuestion.getParent() == close.getParent());
		assertTrue(robot.lookup("#question-search-action-row").tryQuery().isEmpty());
		VBox leftColumn = robot.lookup("#question-search-left-column").queryAs(VBox.class);
		VBox resultsColumn = robot.lookup("#question-search-results-column").queryAs(VBox.class);
		VBox rightColumn = robot.lookup("#question-search-right-column").queryAs(VBox.class);
		assertEquals(new Insets(12), leftColumn.getPadding());
		assertTrue(resultsColumn.getChildren().size() == 1);
		assertEquals(new Insets(12), rightColumn.getPadding());
		robot.interact(dialog::close);
	}

	@Test
	void allQuestionsIsDefaultOnFirstOpenAndEveryReopen(FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(100, "Chemistry");
		Subject physics = new Subject(101, "Physics");
		Question chemistryQuestion = scopeTestQuestion(chemistry, 1000);
		Question physicsQuestion = scopeTestQuestion(physics, 2000);
		AtomicInteger allQuestionsLoads = new AtomicInteger();
		QuestionSearchDialog[] holder = new QuestionSearchDialog[1];
		robot.interact(() -> {
			holder[0] = createScopeTestDialog(chemistry, List.of(chemistryQuestion, physicsQuestion),
					QuestionSearchNarrowing.unrestricted(), allQuestionsLoads);
		});
		QuestionSearchDialog dialog = holder[0];

		// First opening must start in All Questions without
		// requiring the teacher to change the scope.
		robot.interact(dialog::show);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> scope.getValue() == QuestionSearchScope.ALL_QUESTIONS
				&& results.getItems().size() == 1 && allQuestionsLoads.get() >= 1);
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scope.getValue());
		assertEquals(chemistryQuestion.getId(), results.getItems().getFirst().question().getId());
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, results.getItems().getFirst().scope());

		// Physics must not enter a Search whose authoritative
		// Working Subject is Chemistry.
		assertFalse(
				results.getItems().stream().anyMatch(result -> result.question().getId() == physicsQuestion.getId()));

		// Changing the scope remains possible during the session.
		robot.interact(() -> scope.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
		assertEquals(QuestionSearchScope.CURRENT_SYLLABUS, scope.getValue());

		// Hide the existing Dialog, as the application does when
		// leaving Search for a Question-edit workflow.
		robot.interact(dialog::hide);
		WaitForAsyncUtils.waitForFxEvents();
		int previousLoads = allQuestionsLoads.get();

		// Reopening the SAME Dialog must not retain Current Syllabus.
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> scope.getValue() == QuestionSearchScope.ALL_QUESTIONS && results.getItems().size() == 1
						&& results.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS
						&& allQuestionsLoads.get() > previousLoads);
		assertEquals(chemistryQuestion.getId(), results.getItems().getFirst().question().getId());
		assertFalse(scope.isDisable());

		// Even when All Questions was the final scope, reopening
		// must perform a fresh retrieval, not reuse a stale list.
		robot.interact(dialog::hide);
		WaitForAsyncUtils.waitForFxEvents();
		int secondOpeningLoads = allQuestionsLoads.get();
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> allQuestionsLoads.get() > secondOpeningLoads
				&& results.getItems().size() == 1 && scope.getValue() == QuestionSearchScope.ALL_QUESTIONS);
		robot.interact(() -> {
			dialog.close();
			dialog.dispose();
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void descriptorQueueRetainsQuestionsAfterFailedSaveAndCancelledClose(boolean dashboardQueue, FxRobot robot)
			throws TimeoutException {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit = new Unit(3, syllabus, "1", "Unit one", 1);
		Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic one", 1);
		Subtopic subtopic = new Subtopic(5, syllabus, topic, "1.1.1", "Subtopic", 1);
		Descriptor descriptor = new Descriptor(6, syllabus, subtopic, "1.1.1.1", "Descriptor", 1);
		InMemoryCurriculumRepository curriculum = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus), List.of(unit, topic, subtopic, descriptor));
		ExamProvider provider = new ExamProvider(7, "QCAA");
		Exam exam = new Exam(8, chemistry, provider, 2025, "External Assessment");
		ExamBooklet booklet = new ExamBooklet(10, exam, "Paper 1", new SourceDocument(11, "paper1.pdf"));
		ExamBooklet sibling = new ExamBooklet(12, exam, "Paper 2", new SourceDocument(13, "paper2.pdf"));
		Exam otherExam = new Exam(14, chemistry, provider, 2024, "Other Assessment");
		ExamBooklet otherBooklet = new ExamBooklet(15, otherExam, "Paper 1", new SourceDocument(16, "other-paper.pdf"));
		Question first = new Question(21, booklet, "1", "", 1, List.of(), subtopic, false);
		Question second = new Question(22, booklet, "2", "", 1, List.of(), subtopic, false);
		Question classified = new Question(23, booklet, "3", "", 1, List.of(), descriptor, false);
		Question siblingQuestion = new Question(24, sibling, "4", "", 1, List.of(), subtopic, false);
		Question otherExamQuestion = new Question(25, otherBooklet, "5", "", 1, List.of(), subtopic, false);
		List<Question> questions = List.of(first, second, classified, siblingQuestion, otherExamQuestion);
		QuestionSearchNarrowing narrowing = dashboardQueue
				? QuestionSearchNarrowing
						.forQuestionWork(List.of(first, siblingQuestion, otherExamQuestion, classified))
				: QuestionSearchNarrowing.forMissingDescriptors(booklet);
		List<Long> expectedIds = dashboardQueue ? List.of(21L, 24L, 25L) : List.of(21L, 22L);
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrieval = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculum));
		QuestionPreviewService preview = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		AtomicInteger attemptedWrites = new AtomicInteger();
		QuestionSearchDialog[] holder = new QuestionSearchDialog[1];
		robot.interact(() -> {
			holder[0] = new QuestionSearchDialog(stage, chemistry, curriculum, retrieval, () -> questions, preview,
					new InMemoryQuestionOutputApplicabilityRepository(), (_, _) -> {
						attemptedWrites.incrementAndGet();
						throw new IllegalStateException("Simulated classification write failure");
					}, narrowing);
		});
		QuestionSearchDialog dialog = holder[0];
		robot.interact(dialog::show);
		@SuppressWarnings("unchecked")
		ListView<QuestionSearchResult> results = (ListView<QuestionSearchResult>) dialog.getDialogPane()
				.lookup("#question-search-results");
		@SuppressWarnings("unchecked")
		ComboBox<CurriculumNode> descriptorBox = (ComboBox<CurriculumNode>) dialog.getDialogPane()
				.lookup("#question-search-selected-descriptor");
		Button save = (Button) dialog.getDialogPane().lookup("#question-search-save-classification");
		ComboBox<?> scope = (ComboBox<?>) dialog.getDialogPane().lookup("#question-search-scope");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> onFx(robot,
						() -> results.getItems().size() == expectedIds.size()
								&& results.getSelectionModel().getSelectedItem() != null
								&& descriptorBox.getItems().contains(descriptor)));

		// Exact queue membership survives either the booklet or displayed-ID route.
		assertEquals(expectedIds,
				results.getItems().stream().map(result -> result.question().getId()).sorted().toList());
		assertTrue(onFx(robot,
				() -> results.getItems().stream().allMatch(
						result -> (dashboardQueue || result.question().getBooklet().getId() == booklet.getId())
								&& result.question().getClassification().getLevel() == CurriculumLevel.SUBTOPIC)));
		assertTrue(onFx(robot, scope::isDisable));
		long selectedQuestionId = results.getSelectionModel().getSelectedItem().question().getId();
		robot.interact(() -> descriptorBox.setValue(descriptor));
		assertFalse(onFx(robot, save::isDisable));

		// The real Save action invokes a failing persistence operation.
		// A modal error is expected, so schedule the action.
		Platform.runLater(save::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> showingAlertPane(robot, "Save Question") != null);
		DialogPane error = showingAlertPane(robot, "Save Question");
		assertEquals("The Question classification could not be saved.", error.getHeaderText());
		assertEquals("Simulated classification write failure", error.getContentText());
		fireShowingAlertButton(robot, "Save Question", ButtonBar.ButtonData.OK_DONE);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> showingAlertPane(robot, "Save Question") == null);

		// Failure must not shrink the queue or report successful persistence.
		assertEquals(1, attemptedWrites.get());
		assertFalse(dialog.consumePersistedClassificationChange());
		assertTrue(onFx(robot,
				() -> results.getItems().size() == expectedIds.size()
						&& results.getSelectionModel().getSelectedItem().question().getId() == selectedQuestionId
						&& descriptor.equals(descriptorBox.getValue())));
		Button close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE);

		// Cancelling the unsaved-change prompt preserves the edit and queue.
		Platform.runLater(close::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> showingAlertPane(robot, "Unsaved Question") != null);
		fireShowingAlertButton(robot, "Unsaved Question", ButtonBar.ButtonData.CANCEL_CLOSE);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> showingAlertPane(robot, "Unsaved Question") == null);
		assertTrue(onFx(robot, dialog::isShowing));
		assertTrue(onFx(robot, () -> results.getItems().size() == expectedIds.size()
				&& results.getSelectionModel().getSelectedItem().question().getId() == selectedQuestionId));
		assertFalse(onFx(robot, save::isDisable));

		// Explicitly discarding the pending edit closes Search without
		// persisting a Descriptor or removing any Question.
		Platform.runLater(close::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> showingAlertPane(robot, "Unsaved Question") != null);
		fireShowingAlertButton(robot, "Unsaved Question", ButtonBar.ButtonData.OTHER);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !onFx(robot, dialog::isShowing));
		assertFalse(dialog.consumePersistedClassificationChange());
		assertEquals(1, attemptedWrites.get());
		assertEquals(CurriculumLevel.SUBTOPIC, first.getClassification().getLevel());
		assertEquals(CurriculumLevel.SUBTOPIC, second.getClassification().getLevel());
		assertEquals(CurriculumLevel.SUBTOPIC, siblingQuestion.getClassification().getLevel());
		assertEquals(CurriculumLevel.SUBTOPIC, otherExamQuestion.getClassification().getLevel());
		assertEquals(booklet, first.getBooklet());
		assertEquals(sibling, siblingQuestion.getBooklet());
		assertEquals(otherExam, otherExamQuestion.getExam());
		assertEquals(expectedIds,
				results.getItems().stream().map(result -> result.question().getId()).sorted().toList());
		robot.interact(dialog::dispose);
	}

	@Test
	void examAndBookletNarrowingSurvivesAllQuestionsReset(FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(110, "Chemistry");
		Question selectedQuestion = scopeTestQuestion(chemistry, 3000);
		Question otherQuestion = scopeTestQuestion(chemistry, 4000);
		List<Question> allQuestions = List.of(selectedQuestion, otherQuestion);

		// Verify both supported Dashboard launch constraints.
		List<QuestionSearchNarrowing> narrowings = List.of(
				QuestionSearchNarrowing.forBooklet(selectedQuestion.getBooklet()),
				QuestionSearchNarrowing.forExam(selectedQuestion.getExam()));
		for (QuestionSearchNarrowing narrowing : narrowings) {
			AtomicInteger allQuestionsLoads = new AtomicInteger();
			QuestionSearchDialog[] holder = new QuestionSearchDialog[1];
			robot.interact(() -> {
				holder[0] = createScopeTestDialog(chemistry, allQuestions, narrowing, allQuestionsLoads);
			});
			QuestionSearchDialog dialog = holder[0];
			robot.interact(dialog::show);
			ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			Label narrowingLabel = robot.lookup("#question-search-narrowing").queryAs(Label.class);
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> scope.getValue() == QuestionSearchScope.ALL_QUESTIONS && results.getItems().size() == 1
							&& results.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
			assertEquals(narrowing.description(), narrowingLabel.getText());
			assertEquals(selectedQuestion.getId(), results.getItems().getFirst().question().getId());

			// Change the scope during the current Search session.
			robot.interact(() -> scope.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
			assertEquals(QuestionSearchScope.CURRENT_SYLLABUS, scope.getValue());
			robot.interact(dialog::hide);
			WaitForAsyncUtils.waitForFxEvents();
			int previousLoads = allQuestionsLoads.get();
			robot.interact(dialog::show);
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> scope.getValue() == QuestionSearchScope.ALL_QUESTIONS && results.getItems().size() == 1
							&& results.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS
							&& allQuestionsLoads.get() > previousLoads);

			// The opening reset must not discard the immutable
			// Dashboard Exam or booklet constraint.
			assertEquals(narrowing.description(), narrowingLabel.getText());
			assertEquals(selectedQuestion.getId(), results.getItems().getFirst().question().getId());
			assertFalse(
					results.getItems().stream().anyMatch(result -> result.question().getId() == otherQuestion.getId()));
			robot.interact(() -> {
				dialog.close();
				dialog.dispose();
			});
		}
	}

	@Test
	void examEditingIsNotOfferedFromQuestionSearch(FxRobot robot) {
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		robot.interact(() -> extracted(curriculumRepository, retrievalService, previewService, dialogHolder));
		QuestionSearchDialog dialog = dialogHolder[0];
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();

		// Exam identity correction has moved to Exam/Assets and must no longer appear
		// as
		// an alternative Question Search action.
		assertTrue(robot.lookup("#question-search-edit-exam").tryQuery().isEmpty());
		assertTrue(dialog.getDialogPane().getButtonTypes().stream()
				.noneMatch(buttonType -> "Edit Exam".equals(buttonType.getText())));
		robot.interact(dialog::close);
	}

	@Test
	void fullHeightPositionSurvivesHideAndReshow(FxRobot robot) {
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		Rectangle2D[] screenBounds = new Rectangle2D[1];
		robot.interact(
				() -> extracted(curriculumRepository, retrievalService, previewService, dialogHolder, screenBounds));
		QuestionSearchDialog dialog = dialogHolder[0];
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();
		Rectangle2D bounds = screenBounds[0];
		robot.interact(() -> {
			dialog.setWidth(Math.min(1000, bounds.getWidth() - 80));
			dialog.setX(bounds.getMinX() + 40);
			dialog.setY(bounds.getMinY());
			dialog.setHeight(bounds.getHeight());
		});
		WaitForAsyncUtils.waitForFxEvents();
		double expectedWidth = dialog.getWidth();
		double expectedHeight = dialog.getHeight();
		double expectedX = dialog.getX();
		double expectedY = dialog.getY();
		robot.interact(dialog::hide);
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(expectedWidth, dialog.getWidth(), 1.0);
		assertEquals(expectedHeight, dialog.getHeight(), 1.0);
		assertEquals(expectedX, dialog.getX(), 1.0);
		assertEquals(expectedY, dialog.getY(), 1.0);
		robot.interact(dialog::close);
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
		robot.interact(() -> extracted(curriculumRepository, retrievalService, previewService, dialogHolder));
		QuestionSearchDialog dialog = dialogHolder[0];
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();

		// The new two-column Search workspace requires enough horizontal room for
		// both task columns to remain usable.
		robot.interact(() -> dialog.setWidth(700));
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(dialog.getWidth() >= 1100);
		robot.interact(() -> {
			dialog.setWidth(1220);
			dialog.setHeight(810);
		});
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1220, dialog.getWidth(), 1.0);
		assertEquals(810, dialog.getHeight(), 1.0);

		// Edit actions hide Search before the application later shows the same Dialog
		// instance again. Reproduce that lifecycle directly.
		robot.interact(dialog::hide);
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(dialog::show);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(1220, dialog.getWidth(), 1.0);
		assertEquals(810, dialog.getHeight(), 1.0);
		robot.interact(dialog::close);
	}

	private QuestionSearchDialog createScopeTestDialog(Subject workingSubject, List<Question> questions,
			QuestionSearchNarrowing narrowing, AtomicInteger allQuestionsLoads) {
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		return new QuestionSearchDialog(stage, workingSubject, curriculumRepository, retrievalService, () -> {
			allQuestionsLoads.incrementAndGet();
			return questions;
		}, previewService, new InMemoryQuestionOutputApplicabilityRepository(), (_, _) -> {
			throw new AssertionError("Classification persistence was not expected");
		}, narrowing);
	}

	private QuestionSearchDialog extracted(InMemoryCurriculumRepository curriculumRepository,
			QuestionRetrievalService retrievalService, QuestionPreviewService previewService,
			QuestionSearchDialog[] dialogHolder) {
		Subject workingSubject = new Subject(1, "Chemistry");

		// Geometry behaviour is independent of curriculum contents, but the production
		// Dialog now always receives an authoritative workspace Working Subject.
		return dialogHolder[0] = new QuestionSearchDialog(stage, workingSubject, curriculumRepository, retrievalService,
				() -> List.of(), previewService, new InMemoryQuestionOutputApplicabilityRepository(), (_, _) -> {

					// Geometry tests must never attempt Question persistence.
					throw new AssertionError("Classification update was not expected");
				});
	}

	private void extracted(InMemoryCurriculumRepository curriculumRepository, QuestionRetrievalService retrievalService,
			QuestionPreviewService previewService, QuestionSearchDialog[] dialogHolder, Rectangle2D[] screenBounds) {
		screenBounds[0] = Screen.getPrimary().getVisualBounds();
		Subject workingSubject = new Subject(1, "Chemistry");

		// Geometry behaviour is independent of curriculum contents, but production
		// Search always receives its workspace Working Subject.
		dialogHolder[0] = new QuestionSearchDialog(stage, workingSubject, curriculumRepository, retrievalService,
				() -> List.of(), previewService, new InMemoryQuestionOutputApplicabilityRepository(), (_, _) -> {

					// Geometry tests must never attempt Question persistence.
					throw new AssertionError("Classification update was not expected");
				});
	}

	private void fireShowingAlertButton(FxRobot robot, String title, ButtonBar.ButtonData buttonData) {
		DialogPane pane = showingAlertPane(robot, title);
		assertNotNull(pane, "Expected showing alert: " + title);
		ButtonType type = pane.getButtonTypes().stream().filter(candidate -> candidate.getButtonData() == buttonData)
				.findFirst().orElseThrow();
		ButtonBase button = (ButtonBase) pane.lookupButton(type);

		// Fire the actual dialog-owned control without pointer hit-testing.
		robot.interact(button::fire);
	}

	private boolean onFx(FxRobot robot, BooleanSupplier condition) {
		AtomicBoolean result = new AtomicBoolean();
		robot.interact(() -> result.set(condition.getAsBoolean()));
		return result.get();
	}

	private Question scopeTestQuestion(Subject subject, long baseId) {
		SyllabusVersion historicalVersion = new SyllabusVersion(baseId, subject, "2019", false);
		Unit unit = new Unit(baseId + 1, historicalVersion, "1", "Historical Unit", 1);
		Topic topic = new Topic(baseId + 2, historicalVersion, unit, "1.1", "Historical Topic", 1);
		Descriptor descriptor = new Descriptor(baseId + 3, historicalVersion, topic, "1.1.1", "Historical Descriptor",
				1);
		ExamProvider provider = new ExamProvider(baseId + 4, "QCAA");
		Exam exam = new Exam(baseId + 5, subject, provider, 2020, "Test Examination");
		SourceDocument document = new SourceDocument(baseId + 6, "test-" + baseId + ".pdf");
		ExamBooklet booklet = new ExamBooklet(baseId + 7, exam, "Paper 1", document);

		// Metadata-only Question: preview rendering is irrelevant
		// to testing the Search scope and contextual constraints.
		return new Question(baseId + 8, booklet, "Q1", "Test Question", 1, List.of(), descriptor, false);
	}

	private DialogPane showingAlertPane(FxRobot robot, String title) {
		AtomicReference<DialogPane> result = new AtomicReference<>();
		robot.interact(() -> {
			for (Window window : Window.getWindows()) {
				if (!(window instanceof Stage candidate) || !candidate.isShowing()
						|| !title.equals(candidate.getTitle()) || candidate.getScene() == null) {
					continue;
				}
				if (candidate.getScene().getRoot() instanceof DialogPane pane) {
					result.set(pane);
					return;
				}
			}
		});
		return result.get();
	}
}
