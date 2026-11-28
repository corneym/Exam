package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

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

import au.edu.eq.questionbank.Constants;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
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
import au.edu.eq.questionbank.ui.model.ExamBuilderDraft;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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
		WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
				() -> scope.getValue() == QuestionSearchScope.ALL_QUESTIONS && results.getItems().size() == 1
						&& allQuestionsLoads.get() >= 1);
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
		WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
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
		WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
				() -> allQuestionsLoads.get() > secondOpeningLoads && results.getItems().size() == 1
						&& scope.getValue() == QuestionSearchScope.ALL_QUESTIONS);
		robot.interact(() -> {
			dialog.close();
			dialog.dispose();
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void completedExamQuestionsRemainReadOnlyInSearch(boolean examInspection, FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
		Unit unit = new Unit(3, syllabus, "1", "Unit one", 1);
		Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic one", 1);
		Subtopic subtopic = new Subtopic(5, syllabus, topic, "1.1.1", "Subtopic", 1);
		Descriptor descriptor = new Descriptor(6, syllabus, subtopic, "1.1.1.1", "Descriptor", 1);
		InMemoryCurriculumRepository curriculum = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus), List.of(unit, topic, subtopic, descriptor));
		ExamProvider provider = new ExamProvider(7, "QCAA");
		Exam completedExam = new Exam(8, chemistry, provider, 2025, "Completed Exam", ExamCaptureState.COMPLETE);
		Exam activeExam = new Exam(9, chemistry, provider, 2024, "Active Exam", ExamCaptureState.ACTIVE);
		ExamBooklet completedBooklet = new ExamBooklet(10, completedExam, "Paper 1",
				new SourceDocument(11, "completed-paper.pdf"));
		ExamBooklet activeBooklet = new ExamBooklet(12, activeExam, "Paper 1",
				new SourceDocument(13, "active-paper.pdf"));

		// The standalone Questions would ordinarily permit splitting,
		// Answer editing and inline Descriptor refinement.
		Question completedStandalone = new Question(21, completedBooklet, "1", "Completed Question", 2, List.of(),
				subtopic, false);
		completedStandalone.setAnswer(new Answer(31, "Completed Answer", List.of()));
		Question activeStandalone = new Question(22, activeBooklet, "1", "Active Question", 2, List.of(), subtopic,
				false);
		activeStandalone.setAnswer(new Answer(32, "Active Answer", List.of()));

		// Multipart Questions additionally exercise Shared Context editing.
		SourceQuestion completedSource = new SourceQuestion(41, completedBooklet, "2");
		SourceQuestion activeSource = new SourceQuestion(42, activeBooklet, "2");
		Question completedPart = new Question(23, completedBooklet, "2a", "Completed part", 2, List.of(), descriptor,
				false, completedSource, null);
		Question activePart = new Question(24, activeBooklet, "2a", "Active part", 2, List.of(), descriptor, false,
				activeSource, null);
		QuestionSearchNarrowing narrowing = examInspection ? QuestionSearchNarrowing.forExam(completedExam)
				: QuestionSearchNarrowing.unrestricted();
		QuestionRetrievalRepository repository = _ -> List.of();
		QuestionRetrievalService retrieval = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculum));
		QuestionPreviewService preview = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		QuestionSearchDialog[] holder = new QuestionSearchDialog[1];
		robot.interact(() -> {
			holder[0] = new QuestionSearchDialog(stage, chemistry, curriculum, retrieval,
					() -> List.of(completedStandalone, completedPart, activeStandalone, activePart), preview,
					new InMemoryQuestionOutputApplicabilityRepository(), (_, _) -> {
						throw new AssertionError("Classification persistence was not expected");
					}, narrowing);
			holder[0].show();
		});
		QuestionSearchDialog dialog = holder[0];
		try {
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-selected-descriptor")
					.queryComboBox();
			Button editQuestion = robot.lookup("#question-search-edit-question").queryButton();
			Button splitQuestion = robot.lookup("#question-search-split-question").queryButton();
			Button editMetadata = robot.lookup("#question-search-edit-metadata").queryButton();
			Button editSharedContext = robot.lookup("#question-search-recapture-shared-context").queryButton();
			Button editAnswer = robot.lookup("#question-search-edit-answer").queryButton();
			Button saveClassification = robot.lookup("#question-search-save-classification").queryButton();
			Button include = robot.lookup("#question-search-output-include").queryButton();
			Button exclude = robot.lookup("#question-search-output-exclude").queryButton();
			Button close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
			List<Button> editControls = List.of(editQuestion, splitQuestion, editMetadata, editSharedContext,
					editAnswer, saveClassification, include, exclude);
			int expectedCount = examInspection ? 2 : 4;
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
					() -> onFx(robot, () -> results.getItems().size() == expectedCount));
			for (Question question : List.of(completedStandalone, completedPart)) {
				robot.interact(() -> {
					results.getSelectionModel().select(results.getItems().stream()
							.filter(result -> result.question().getId() == question.getId()).findFirst().orElseThrow());

					// Selecting a COMPLETE Question must not activate
					// any editing control or disable ordinary inspection.
					for (Button control : editControls) {
						assertTrue(control.isDisabled(), control.getId());
					}
					assertTrue(descriptorBox.isDisabled());
					assertFalse(results.isDisabled());
					assertFalse(close.isDisabled());

					// Even direct result conversion must not publish
					// an edit request for a COMPLETE Question.
					for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
						assertNull(dialog.getResultConverter().call(type));
					}
				});
			}
			if (!examInspection) {
				robot.interact(() -> {
					results.getSelectionModel()
							.select(results.getItems().stream()
									.filter(result -> result.question().getId() == activeStandalone.getId()).findFirst()
									.orElseThrow());

					// The restriction belongs to the selected Question,
					// not the entire Search dialog or Working Subject.
					assertFalse(editQuestion.isDisabled());
					assertFalse(splitQuestion.isDisabled());
					assertFalse(editMetadata.isDisabled());
					assertFalse(editAnswer.isDisabled());
					assertFalse(descriptorBox.isDisabled());
					assertTrue(editSharedContext.isDisabled());
					results.getSelectionModel()
							.select(results.getItems().stream()
									.filter(result -> result.question().getId() == activePart.getId()).findFirst()
									.orElseThrow());
					assertFalse(editQuestion.isDisabled());
					assertFalse(editMetadata.isDisabled());
					assertFalse(editSharedContext.isDisabled());
					assertTrue(splitQuestion.isDisabled());

					// Returning to a COMPLETE Question must restore
					// every restriction immediately.
					results.getSelectionModel()
							.select(results.getItems().stream()
									.filter(result -> result.question().getId() == completedStandalone.getId())
									.findFirst().orElseThrow());
					for (Button control : editControls) {
						assertTrue(control.isDisabled(), control.getId());
					}
					assertTrue(descriptorBox.isDisabled());
				});
			}
		} finally {
			robot.interact(() -> {
				dialog.close();
				dialog.dispose();
			});
		}
	}

	@Test
	void contributorAnswerEditingIsIndependentOfQuestionEditing(FxRobot robot) throws TimeoutException {
		Subject subject = new Subject(100, "Chemistry");
		Question question = scopeTestQuestion(subject, 1000);
		question.setAnswer(new Answer(2000, "Contributor answer", List.of()));
		AtomicInteger searchLoads = new AtomicInteger();
		QuestionSearchDialog[] holder = new QuestionSearchDialog[1];
		robot.interact(() -> {
			holder[0] = createScopeTestDialog(subject, List.of(question), QuestionSearchNarrowing.unrestricted(),
					searchLoads);
			holder[0].setReadOnlyQuestionPredicate(_ -> true);
			holder[0].setReadOnlyAnswerPredicate(_ -> false);
			holder[0].show();
		});
		QuestionSearchDialog dialog = holder[0];
		try {
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
					() -> onFx(robot, () -> !results.getItems().isEmpty()));
			Button editQuestion = robot.lookup("#question-search-edit-question").queryButton();
			Button editAnswer = robot.lookup("#question-search-edit-answer").queryButton();
			robot.interact(() -> {
				results.getSelectionModel().selectFirst();

				// A contributor-owned Answer may be corrected
				// without changing its issued parent Question.
				assertTrue(editQuestion.isDisabled());
				assertFalse(editAnswer.isDisabled());
				ButtonType answerType = dialog.getDialogPane().getButtonTypes().stream()
						.filter(type -> dialog.getDialogPane().lookupButton(type) == editAnswer).findFirst()
						.orElseThrow();
				var request = dialog.getResultConverter().call(answerType);
				assertNotNull(request);
				assertEquals(QuestionSearchDialog.EditTarget.ANSWER, request.target());
				assertEquals(question.getId(), request.question().getId());

				// Issued Answers must be protected independently.
				dialog.setReadOnlyAnswerPredicate(_ -> true);
				assertTrue(editAnswer.isDisabled());
				assertNull(dialog.getResultConverter().call(answerType));

				// Contributor-owned Questions remain editable.
				dialog.setReadOnlyQuestionPredicate(_ -> false);
				assertFalse(editQuestion.isDisabled());
				assertTrue(editAnswer.isDisabled());
			});
		} finally {
			robot.interact(dialog::close);
		}
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
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
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
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
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
	void examQuestionSelectionRequiresEligibleQuestion(FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(100, "Chemistry");
		Question first = examSelectionQuestion(chemistry, 1000, true, 2024, QuestionResponseType.MULTIPLE_CHOICE);
		Question second = examSelectionQuestion(chemistry, 2000, true, 2025, QuestionResponseType.MULTIPLE_CHOICE);
		Question unanswered = examSelectionQuestion(chemistry, 3000, false, 2025, QuestionResponseType.MULTIPLE_CHOICE);
		Question alreadySelected = examSelectionQuestion(chemistry, 4000, true, 2025,
				QuestionResponseType.MULTIPLE_CHOICE);
		Question written = examSelectionQuestion(chemistry, 5000, true, 2025, QuestionResponseType.WRITTEN_RESPONSE);
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		var section = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		assertTrue(draft.add(section, alreadySelected));
		List<Question> sourceQuestions = List.of(first, second, unanswered, alreadySelected, written);
		ExamQuestionSelectionDialog dialog = createExamSelectionDialog(robot, draft, section, sourceQuestions);
		try {
			robot.interact(dialog::show);
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			ComboBox<?> responseFilter = robot.lookup("#question-search-response-type-filter").queryComboBox();
			ComboBox<?> yearFilter = robot.lookup("#question-search-year-from-filter").queryComboBox();
			Button add = robot.lookup("#exam-question-selection-add").queryButton();
			Label summary = robot.lookup("#exam-question-selection-summary").queryAs(Label.class);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
					() -> results.getItems().size() == 4 && yearFilter.getItems().size() == 3);
			assertTrue(responseFilter.isDisabled());
			assertEquals(1, responseFilter.getItems().size());
			assertTrue(results.getItems().stream()
					.allMatch(result -> result.question().getResponseType() == QuestionResponseType.MULTIPLE_CHOICE));
			assertTrue(add.isDisabled());
			assertTrue(examQuestionCheckBox(robot, results, unanswered.getId()).isDisabled());
			assertTrue(examQuestionCheckBox(robot, results, alreadySelected.getId()).isDisabled());
			CheckBox firstCheck = examQuestionCheckBox(robot, results, first.getId());
			robot.interact(firstCheck::fire);
			assertFalse(add.isDisabled());
			assertEquals("1 selected — 1 mark", summary.getText());

			// Year choices are descending. Select 2025,
			// which temporarily hides the checked 2024 Question.
			robot.interact(() -> yearFilter.getSelectionModel().select(1));
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 3
					&& results.getItems().stream().noneMatch(result -> result.question().getId() == first.getId()));
			assertEquals("1 selected — 1 mark", summary.getText());
			CheckBox secondCheck = examQuestionCheckBox(robot, results, second.getId());
			robot.interact(secondCheck::fire);
			assertEquals("2 selected — 2 marks", summary.getText());
			Button clearFilters = robot.lookup("#question-search-clear-metadata-filters").queryButton();
			robot.interact(clearFilters::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 4);
			assertTrue(examQuestionCheckBox(robot, results, first.getId()).isSelected());
			assertTrue(responseFilter.isDisabled());
			assertEquals(1, responseFilter.getItems().size());
			assertTrue(robot.lookup("#question-search-save-classification").queryButton().isDisabled());
			assertTrue(robot.lookup("#question-search-output-include").queryButton().isDisabled());
			assertTrue(robot.lookup("#question-search-output-exclude").queryButton().isDisabled());
			robot.interact(add::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> !dialog.isShowing());
			assertEquals(List.of(first, second), dialog.getResult());

			// Confirming the chooser returns the selected Questions;
			// only the owning Exam Builder can modify the draft.
			assertEquals(1, draft.getTotalSelectedCount());
		} finally {
			robot.interact(() -> {
				if (dialog.isShowing()) {
					dialog.close();
				}
			});
		}
	}

	@Test
	void examQuestionSelectionRestrictsBothWrittenPapers(FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(100, "Chemistry");
		Question multipleChoice = examSelectionQuestion(chemistry, 1000, true, 2025,
				QuestionResponseType.MULTIPLE_CHOICE);
		Question written = examSelectionQuestion(chemistry, 2000, true, 2025, QuestionResponseType.WRITTEN_RESPONSE);
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		for (var section : List.of(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN,
				ExamBuilderDraft.Section.PAPER_TWO_WRITTEN)) {
			ExamQuestionSelectionDialog dialog = createExamSelectionDialog(robot, draft, section,
					List.of(multipleChoice, written));
			try {
				robot.interact(dialog::show);
				ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
				ComboBox<?> responseFilter = robot.lookup("#question-search-response-type-filter").queryComboBox();
				WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
				assertEquals(written.getId(), results.getItems().getFirst().question().getId());
				assertTrue(responseFilter.isDisabled());
				CheckBox checkBox = examQuestionCheckBox(robot, results, written.getId());
				robot.interact(checkBox::fire);
				Button clearSelection = robot.lookup("#exam-question-selection-clear").queryButton();
				robot.interact(clearSelection::fire);
				Button add = robot.lookup("#exam-question-selection-add").queryButton();
				assertTrue(add.isDisabled());
				assertTrue(draft.isEmpty());
			} finally {
				robot.interact(dialog::close);
			}
		}
	}

	@Test
	void examSelectionFiltersAnnualReservationsAndRetainsPendingSelections(FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(100, "Chemistry");
		Question original = examSelectionQuestion(chemistry, 1000, true, 2025, QuestionResponseType.WRITTEN_RESPONSE);
		SourceQuestion source = new SourceQuestion(1, original.getBooklet(), "21");
		Question partA = new Question(1008, original.getBooklet(), "21a", "", 2, List.of(),
				original.getClassification(), false, source, null, QuestionResponseType.WRITTEN_RESPONSE);
		Question partB = new Question(1018, original.getBooklet(), "21b", "", 3, List.of(),
				original.getClassification(), false, source, null, QuestionResponseType.WRITTEN_RESPONSE);
		partA.setAnswer(original.getAnswer());
		partB.setAnswer(new Answer(1019, null, original.getAnswer().getRegions()));
		Question available = examSelectionQuestion(chemistry, 2000, true, 2025, QuestionResponseType.WRITTEN_RESPONSE);
		Question selected = examSelectionQuestion(chemistry, 3000, true, 2025, QuestionResponseType.WRITTEN_RESPONSE);
		Question unanswered = examSelectionQuestion(chemistry, 4000, false, 2025,
				QuestionResponseType.WRITTEN_RESPONSE);
		List<Question> corpus = List.of(partA, partB, available, selected, unanswered);
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, selected));

		// Reserve only one constituent explicitly. The entire
		// group must nevertheless become unavailable.
		ExamQuestionSelectionDialog dialog = createExamSelectionDialog(robot, draft, section, corpus, 2026,
				Set.of(partA.getId()));
		try {
			robot.interact(dialog::show);
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			CheckBox availableOnly = robot.lookup("#exam-question-available-only").queryAs(CheckBox.class);
			Label summary = robot.lookup("#exam-question-selection-summary").queryAs(Label.class);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 5);
			CheckBox firstPart = examQuestionCheckBox(robot, results, partA.getId());
			CheckBox secondPart = examQuestionCheckBox(robot, results, partB.getId());
			assertTrue(firstPart.isDisabled());
			assertTrue(secondPart.isDisabled());
			assertTrue(secondPart.getTooltip().getText().contains("Committed for cohort 2026"));
			CheckBox duplicate = examQuestionCheckBox(robot, results, selected.getId());
			assertTrue(duplicate.isDisabled());
			assertEquals("Already in this draft", duplicate.getTooltip().getText());
			assertTrue(examQuestionCheckBox(robot, results, unanswered.getId()).isDisabled());
			CheckBox eligible = examQuestionCheckBox(robot, results, available.getId());
			assertFalse(eligible.isDisabled());
			robot.interact(eligible::fire);
			assertEquals("1 selected — 1 mark", summary.getText());
			robot.interact(availableOnly::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 1
					&& results.getItems().getFirst().question().getId() == available.getId());
			assertTrue(examQuestionCheckBox(robot, results, available.getId()).isSelected());
			assertEquals("1 selected — 1 mark", summary.getText());

			// Returning to the complete result set must restore
			// the unavailable rows without changing pending selection.
			robot.interact(availableOnly::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 5);
			assertTrue(examQuestionCheckBox(robot, results, partB.getId()).isDisabled());
			assertTrue(examQuestionCheckBox(robot, results, available.getId()).isSelected());
			assertEquals("1 selected — 1 mark", summary.getText());
			Button add = robot.lookup("#exam-question-selection-add").queryButton();
			robot.interact(add::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> !dialog.isShowing());
			assertEquals(List.of(available), dialog.getResult());

			// The owning examination is not changed until it
			// accepts the result from the selection dialog.
			assertTrue(draft.getQuestions(section).isEmpty());
		} finally {
			robot.interact(() -> {
				if (dialog.isShowing()) {
					dialog.close();
				}
			});
		}
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
	void readOnlyQuestionDisablesEditingWithoutDisablingInspection(FxRobot robot) throws TimeoutException {
		Subject subject = new Subject(100, "Chemistry");
		Question question = scopeTestQuestion(subject, 1000);
		AtomicInteger searchLoads = new AtomicInteger();
		QuestionSearchDialog[] holder = new QuestionSearchDialog[1];
		robot.interact(() -> {
			holder[0] = createScopeTestDialog(subject, List.of(question), QuestionSearchNarrowing.unrestricted(),
					searchLoads);
			holder[0].setReadOnlyQuestionPredicate(candidate -> candidate.getId() == question.getId());
			holder[0].show();
		});
		QuestionSearchDialog dialog = holder[0];
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> !results.getItems().isEmpty());
		robot.interact(() -> results.getSelectionModel().selectFirst());
		assertTrue(robot.lookup("#question-search-edit-question").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-split-question").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-edit-metadata").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-recapture-shared-context").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-edit-answer").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-save-classification").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-output-include").queryButton().isDisabled());
		assertTrue(robot.lookup("#question-search-output-exclude").queryButton().isDisabled());
		assertFalse(results.isDisabled());
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

	@Test
	void wholeMultipartSelectionIncludesFilteredConstituents(FxRobot robot) throws TimeoutException {
		Subject chemistry = new Subject(100, "Chemistry");
		Question original = examSelectionQuestion(chemistry, 1000, true, 2025, QuestionResponseType.WRITTEN_RESPONSE);
		SourceQuestion source = new SourceQuestion(1, original.getBooklet(), "21");
		Question partA = new Question(1008, original.getBooklet(), "21a", "First constituent", 2, List.of(),
				original.getClassification(), false, source, null, QuestionResponseType.WRITTEN_RESPONSE);

		// Give the second constituent a different original
		// syllabus classification so filtering can hide it
		// independently of the first constituent.
		SyllabusVersion historical = new SyllabusVersion(2000, chemistry, "2019", false);
		Unit historicalUnit = new Unit(2001, historical, "3", "Unit 3", 1);
		Topic historicalTopic = new Topic(2002, historical, historicalUnit, "3.1", "Equilibrium", 1);
		Subtopic historicalSubtopic = new Subtopic(2003, historical, historicalTopic, "3.1.1", "Equilibrium", 1);
		Question partB = new Question(1018, original.getBooklet(), "21b", "Second constituent", 3, List.of(),
				historicalSubtopic, false, source, null, QuestionResponseType.WRITTEN_RESPONSE);
		partA.setAnswer(original.getAnswer());
		partB.setAnswer(new Answer(1019, null, original.getAnswer().getRegions()));
		List<Question> corpus = List.of(partB, partA);
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		ExamQuestionSelectionDialog dialog = createExamSelectionDialog(robot, draft, section, corpus);
		try {
			robot.interact(dialog::show);
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			ComboBox<?> syllabusFilter = robot.lookup("#question-search-original-syllabus-filter").queryComboBox();
			Label summary = robot.lookup("#exam-question-selection-summary").queryAs(Label.class);
			Button clearFilters = robot.lookup("#question-search-clear-metadata-filters").queryButton();
			ButtonType addType = dialog.getDialogPane().getButtonTypes().stream()
					.filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
			ButtonBase add = (ButtonBase) dialog.getDialogPane().lookupButton(addType);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
					() -> results.getItems().size() == 2 && syllabusFilter.getItems().size() == 3);

			// The catalogue orders original syllabus names
			// ascending: All, 2019, 2025.
			robot.interact(() -> syllabusFilter.getSelectionModel().select(2));
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 1
					&& results.getItems().getFirst().question().getId() == partA.getId());
			CheckBox firstCheck = examQuestionCheckBox(robot, results, partA.getId());
			robot.interact(firstCheck::fire);

			// Part B is not visible, but its three marks must
			// be included in the single top-level selection.
			assertEquals("1 selected — 5 marks", summary.getText());
			assertFalse(add.isDisabled());
			assertTrue(draft.isEmpty());
			robot.interact(clearFilters::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
			assertTrue(examQuestionCheckBox(robot, results, partA.getId()).isSelected());
			assertTrue(examQuestionCheckBox(robot, results, partB.getId()).isSelected());

			// Unchecking either constituent must uncheck
			// the entire pending top-level Question.
			CheckBox secondCheck = examQuestionCheckBox(robot, results, partB.getId());
			robot.interact(secondCheck::fire);
			WaitForAsyncUtils.waitForFxEvents();
			assertEquals("0 selected — 0 marks", summary.getText());
			assertTrue(add.isDisabled());
			assertFalse(examQuestionCheckBox(robot, results, partA.getId()).isSelected());
			assertFalse(examQuestionCheckBox(robot, results, partB.getId()).isSelected());

			// Select through the second constituent instead.
			secondCheck = examQuestionCheckBox(robot, results, partB.getId());
			robot.interact(secondCheck::fire);
			assertEquals("1 selected — 5 marks", summary.getText());
			robot.interact(add::fire);
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> !dialog.isShowing());

			// Return the complete Question in source order,
			// irrespective of search order or chosen constituent.
			assertEquals(List.of(partA, partB), dialog.getResult());

			// The chooser itself must not mutate the draft.
			assertTrue(draft.isEmpty());
		} finally {
			robot.interact(() -> {
				if (dialog.isShowing()) {
					dialog.close();
				}
			});
		}

		// Confirm the returned selection in the owning draft.
		assertTrue(draft.addSelections(section, dialog.getResult(), corpus));

		// Both constituents must now be unavailable even
		// when selecting Questions for the other paper.
		ExamQuestionSelectionDialog otherPaper = createExamSelectionDialog(robot, draft,
				ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, corpus);
		try {
			robot.interact(otherPaper::show);
			ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
			WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
			assertTrue(examQuestionCheckBox(robot, results, partA.getId()).isDisabled());
			assertTrue(examQuestionCheckBox(robot, results, partB.getId()).isDisabled());
		} finally {
			robot.interact(otherPaper::close);
		}
	}

	private ExamQuestionSelectionDialog createExamSelectionDialog(FxRobot robot, ExamBuilderDraft draft,
			ExamBuilderDraft.Section section, List<Question> questions) {
		return createExamSelectionDialog(robot, draft, section, questions, 2026, Set.of());
	}

	private ExamQuestionSelectionDialog createExamSelectionDialog(FxRobot robot, ExamBuilderDraft draft,
			ExamBuilderDraft.Section section, List<Question> questions, int cohortYear, Set<Long> lockedQuestionIds) {
		InMemoryCurriculumRepository curriculum = new InMemoryCurriculumRepository();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of();
		QuestionRetrievalService retrieval = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculum));
		QuestionPreviewService preview = new QuestionPreviewService(new PdfStore(tempDirectory),
				new QuestionExtractor());
		PerformanceRecorder recorder = new PerformanceRecorder(false, tempDirectory.resolve("performance.csv"));
		AtomicReference<ExamQuestionSelectionDialog> holder = new AtomicReference<>();
		robot.interact(() -> holder.set(
				new ExamQuestionSelectionDialog(stage, draft, section, curriculum, retrieval, () -> questions, preview,
						new InMemoryQuestionOutputApplicabilityRepository(), recorder, cohortYear, lockedQuestionIds)));
		return holder.get();
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

	private CheckBox examQuestionCheckBox(FxRobot robot, ListView<QuestionSearchResult> results, long questionId)
			throws TimeoutException {
		int index = IntStream.range(0, results.getItems().size())
				.filter(position -> results.getItems().get(position).question().getId() == questionId).findFirst()
				.orElseThrow();

		// Materialise the relevant virtualised cell without
		// relying on pointer position or hit testing.
		robot.interact(() -> results.scrollTo(index));
		String selector = "#exam-question-check-" + questionId;
		WaitForAsyncUtils.waitFor(Constants.TIMEOUT, TimeUnit.SECONDS,
				() -> robot.lookup(selector).tryQuery().isPresent());
		return robot.lookup(selector).queryAs(CheckBox.class);
	}

	private Question examSelectionQuestion(Subject subject, long baseId, boolean answered, int year,
			QuestionResponseType responseType) {
		SyllabusVersion syllabus = new SyllabusVersion(baseId, subject, "2025", true);
		Unit unit = new Unit(baseId + 1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(baseId + 2, syllabus, unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = new Subtopic(baseId + 3, syllabus, topic, "3.1.1", "Equilibrium", 1);
		ExamProvider provider = new ExamProvider(baseId + 4, "QCAA");
		Exam exam = new Exam(baseId + 5, subject, provider, year, "External");
		SourceDocument document = new SourceDocument(baseId + 6, "exam-" + baseId + ".pdf");
		ExamBooklet booklet = new ExamBooklet(baseId + 7, exam, "Paper 1", document);
		Question question = new Question(baseId + 8, booklet, "7", "Test examination Question", 1, List.of(), subtopic,
				false, null, null, responseType);
		if (answered) {
			if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
				question.setAnswer(new Answer(baseId + 9, "C", List.of()));
			} else {
				AnswerFile answerFile = new AnswerFile(baseId + 10, exam, "Marking guide",
						new SourceDocument(baseId + 11, "answers-" + baseId + ".pdf"));
				question.setAnswer(
						new Answer(baseId + 9, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2))));
			}
		}
		return question;
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
