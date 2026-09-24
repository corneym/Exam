package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
public class QuestionSearchPaneTest {

	private Subject chemistry;
	private Unit currentUnit;
	private Topic currentTopic;
	private Subtopic currentSubtopic;
	private Descriptor currentDescriptor;
	private Unit historicalUnit;
	private Descriptor historicalDescriptor;
	private Question historicalQuestion;
	private Topic noMatchTopic;
	private Descriptor noMatchDescriptor;
	private DelayedCurriculumRepository curriculumRepository;
	private DelayedQuestionRetrievalRepository retrievalRepository;
	private QuestionPreviewService previewService;
	private QuestionRetrievalService retrievalService;
	private InMemoryQuestionOutputApplicabilityRepository outputApplicabilityRepository;
	private Stage stage;

	@Test
	public void allQuestionsCanBeFilteredBySubject(FxRobot robot) throws TimeoutException {
		Subject physics = new Subject(40, "Physics");
		SyllabusVersion physicsVersion = new SyllabusVersion(41, physics, "2025", false);
		Unit physicsUnit = new Unit(42, physicsVersion, "1", "Physics Unit", 1);
		Topic physicsTopic = new Topic(43, physicsVersion, physicsUnit, "1.1", "Physics Topic", 1);
		Descriptor physicsDescriptor = new Descriptor(44, physicsVersion, physicsTopic, "1.1.1", "Physics Descriptor",
				1);
		ExamProvider provider = new ExamProvider(45, "QCAA");
		Exam physicsExam = new Exam(46, physics, provider, 2020, "Physics examination");
		SourceDocument sourceDocument = new SourceDocument(47, "Physics/2020/paper1.pdf");
		ExamBooklet booklet = new ExamBooklet(48, physicsExam, "Paper 1", sourceDocument);
		Question physicsQuestion = new Question(49, booklet, "1", "", 2, List.of(), physicsDescriptor, false);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry, physics),
				List.of(physicsVersion), List.of(physicsUnit, physicsTopic, physicsDescriptor));
		replaceSearchPane(robot, repository, retrievalService, () -> List.of(historicalQuestion, physicsQuestion));
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(chemistry)
				&& subjectBox.getItems().contains(physics) && resultsList.getItems().size() == 2);
		assertFalse(subjectBox.isDisable());
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().question().getId() == historicalQuestion.getId());
		robot.interact(() -> subjectBox.setValue(physics));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().question().getId() == physicsQuestion.getId());
	}

	@Test
	public void allQuestionsRestartsCancelledInitialSubjectLoad(FxRobot robot) throws TimeoutException {
		SyllabusVersion historicalVersion = historicalDescriptor.getSyllabusVersion();
		SyllabusVersion currentVersion = currentUnit.getSyllabusVersion();
		DelayedCurriculumRepository delayedRepository = new DelayedCurriculumRepository(List.of(chemistry),
				List.of(historicalVersion, currentVersion),
				List.of(currentUnit, currentTopic, currentSubtopic, currentDescriptor));

		// Delay the constructor's initial Subject lookup so switching scope cancels
		// that request before any Subject reaches the UI.
		delayedRepository.delaySubjects();
		QuestionSearchPane pane = replaceSearchPane(robot, delayedRepository, retrievalService);
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, delayedRepository::hasSubjectDelayStarted);
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));

		// All Questions now needs the Subject list as an optional filter, so it starts
		// a fresh Subject load after cancelling the constructor's stale request.
		delayedRepository.releaseDelayedSubjects();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(chemistry));
		assertFalse(subjectBox.isDisable());
		assertTrue(unitBox.isDisable());
		assertFalse(pane.isDisabled());
	}

	@Test
	public void allQuestionsScopeShowsCompleteBankWithoutCurriculumApplicability(FxRobot robot)
			throws TimeoutException {
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		ListView<QuestionSearchPane.QuestionOutputApplicabilityRow> outputList = robot
				.lookup("#question-search-output-applicability").queryListView();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);

		// All Questions is independent of curriculum navigation and therefore disables
		// controls that would otherwise imply those values are filtering the result.
		assertFalse(subjectBox.isDisable());
		assertTrue(unitBox.isDisable());
		QuestionSearchResult result = resultsList.getItems().getFirst();
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, result.scope());
		assertEquals(historicalQuestion.getId(), result.question().getId());
		assertTrue(result.currentApplicability().isEmpty());
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());

		// Empty applicability here means it was not evaluated, not that the Question
		// failed current-curriculum mapping.
		assertTrue(detailsArea.getText().contains("Not evaluated in All Questions scope."));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 1);
		QuestionSearchPane.QuestionOutputApplicabilityRow outputRow = outputList.getItems().getFirst();

		// All Questions itself still carries no inferred applicability, but selecting
		// a Question performs a separate current-curriculum lookup for revision-output
		// information.
		assertEquals(currentDescriptor, outputRow.currentNode());
		assertFalse(outputRow.excluded());
	}

	@Test
	public void broadeningFromDescriptorToSubjectRestoresSubjectScope(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		robot.interact(() -> unitBox.setValue(currentUnit));
		robot.interact(() -> topicBox.setValue(noMatchTopic));
		robot.interact(() -> classificationBox.setValue(noMatchDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> "No questions found.".equals(statusLabel.getText()));
		assertTrue(resultsList.getItems().isEmpty());
		robot.clickOn("#question-search-subject");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getValue() == null
				&& "Select unit".equals(unitBox.getButtonCell().getText()) && resultsList.getItems().size() == 1);
		assertEquals(null, unitBox.getValue());
		assertEquals("Select unit", unitBox.getButtonCell().getText());
		assertEquals(historicalQuestion.getId(), resultsList.getItems().get(0).question().getId());
	}

	@Test
	public void clickingSelectedSubtopicRestoresSubtopicSearchScope(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		robot.interact(() -> subjectBox.setValue(chemistry));
		robot.interact(() -> unitBox.setValue(currentUnit));
		robot.interact(() -> topicBox.setValue(currentTopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		assertEquals(currentDescriptor, descriptorBox.getValue());
		robot.clickOn("#question-search-classification");
		assertEquals(currentSubtopic, classificationBox.getValue());
		assertEquals(null, descriptorBox.getValue());
	}

	@Test
	public void descriptorSearchDisplaysResultAndProvenance(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		robot.interact(() -> unitBox.setValue(currentUnit));
		robot.interact(() -> topicBox.setValue(currentTopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(1, resultsList.getItems().size());
		QuestionSearchResult result = resultsList.getItems().get(0);
		assertEquals(historicalQuestion.getId(), result.question().getId());

		// The Search wrapper preserves the Question's original stored classification
		// and the retrieval service's current-applicability explanation separately.
		assertEquals(historicalDescriptor, result.question().getClassification());
		assertEquals(List.of(currentDescriptor), result.currentApplicability());
		robot.interact(() -> resultsList.getSelectionModel().select(0));
		assertTrue(detailsArea.getText().contains("2019 1.1.1 Historical descriptor"));
		assertTrue(detailsArea.getText().contains("2025 1.1.1.1 Current descriptor"));
		assertTrue(detailsArea.getText().contains("Calculate the requested quantity."));
	}

	@Test
	public void dirtyDescriptorGuardsMovingToAnotherResult(FxRobot robot) throws TimeoutException {
		Question subtopicQuestion = new Question(50, historicalQuestion.getBooklet(), "22", "", 2, List.of(),
				currentSubtopic, false);
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService,
				() -> List.of(historicalQuestion, subtopicQuestion));
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> selectedDescriptorBox = robot.lookup("#question-search-selected-descriptor")
				.queryComboBox();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 2);
		QuestionSearchResult subtopicResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == subtopicQuestion.getId()).findFirst().orElseThrow();
		QuestionSearchResult otherResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == historicalQuestion.getId()).findFirst().orElseThrow();
		robot.interact(() -> resultsList.getSelectionModel().select(subtopicResult));
		robot.interact(() -> selectedDescriptorBox.setValue(currentDescriptor));
		assertTrue(pane.classificationDirtyProperty().get());
		pane.setClassificationNavigationGuard(() -> false);
		Node otherResultCell = robot.from(resultsList).lookup(".list-cell")
				.match(node -> node instanceof ListCell<?> cell && cell.getItem() == otherResult).query();

		// This regression deliberately uses the pointer because the production defect
		// occurred while ListView was processing a real mouse-selection transaction.
		robot.clickOn(otherResultCell);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			QuestionSearchResult selected = resultsList.getSelectionModel().getSelectedItem();
			return selected != null && selected.question().getId() == subtopicQuestion.getId();
		});

		// Cancelling navigation preserves both the selected Question and its dirty
		// Descriptor refinement.
		assertEquals(subtopicQuestion.getId(), resultsList.getSelectionModel().getSelectedItem().question().getId());
		assertTrue(pane.classificationDirtyProperty().get());

		// Discard clears the pending edit without persisting a replacement
		// classification.
		robot.interact(pane::classificationDiscarded);
		assertFalse(pane.classificationDirtyProperty().get());
		robot.interact(() -> resultsList.getSelectionModel().select(subtopicResult));
		robot.interact(() -> selectedDescriptorBox.setValue(currentDescriptor));
		assertTrue(pane.classificationDirtyProperty().get());
		pane.setClassificationNavigationGuard(() -> {

			// Simulate the Dialog successfully persisting the pending
			// classification before allowing navigation.
			pane.classificationSaved(subtopicQuestion.getId());
			return true;
		});
		robot.interact(() -> resultsList.getSelectionModel().select(otherResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			QuestionSearchResult selected = resultsList.getSelectionModel().getSelectedItem();
			return selected != null && selected.question().getId() == historicalQuestion.getId();
		});
		assertFalse(pane.classificationDirtyProperty().get());
	}

	@Test
	public void disposalWhileHierarchyLoadIsInFlightIgnoresLateCompletion(FxRobot robot) throws TimeoutException {
		QuestionSearchPane pane = (QuestionSearchPane) stage.getScene().getRoot();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		curriculumRepository.delayChildrenFor(currentUnit);
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, curriculumRepository::hasDelayStarted);
		robot.interact(() -> {
			pane.dispose();
			pane.dispose();
		});
		curriculumRepository.releaseDelayedChildren();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, curriculumRepository::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(topicBox.getItems().isEmpty());
		assertTrue(resultsList.getItems().isEmpty());
		assertEquals("", statusLabel.getText());
	}

	@Test
	public void disposalWhilePreviewIsInFlightIgnoresLateCompletion(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("question-search-dispose-preview-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		Exam exam = historicalQuestion.getExam();
		SourceDocument sourceDocument = new SourceDocument(70, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(71, exam, "Preview booklet", sourceDocument);
		Question question = new Question(72, booklet, "P3", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), currentDescriptor, false);
		QuestionRetrievalRepository repository = currentNodes -> {
			if (!currentNodes.contains(currentDescriptor)) {
				return List.of();
			}
			return List.of(new QuestionApplicabilityMatch(question, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		DelayedQuestionExtractor extractor = new DelayedQuestionExtractor(question.getId());
		QuestionPreviewService replacementPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, service, replacementPreviewService);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(chemistry));
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayStarted);
		robot.interact(() -> {
			pane.dispose();
			pane.dispose();
		});
		extractor.releaseDelayedExtraction();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(null, preview.getImage());
		assertEquals("", previewStatus.getText());
		assertTrue(detailsArea.getText().isBlank());
		assertTrue(resultsList.getItems().isEmpty());
	}

	@Test
	public void disposalWhileSearchIsInFlightIgnoresLateCompletion(FxRobot robot) throws TimeoutException {
		QuestionSearchPane pane = (QuestionSearchPane) stage.getScene().getRoot();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topicBox.getItems().contains(currentTopic));
		robot.interact(() -> topicBox.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(currentSubtopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> descriptorBox.getItems().contains(currentDescriptor));
		retrievalRepository.delayNextRequestFor(currentDescriptor);
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, retrievalRepository::hasDelayStarted);
		robot.interact(pane::dispose);
		retrievalRepository.releaseDelayedRequest();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, retrievalRepository::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(resultsList.getItems().isEmpty());
		assertEquals("", statusLabel.getText());
	}

	@Test
	public void disposingDialogPreventsFurtherHierarchyWork(FxRobot robot) {
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		QuestionSearchPane[] paneHolder = new QuestionSearchPane[1];
		robot.interact(() -> extracted(dialogHolder, paneHolder));
		ComboBox<Subject> subjectBox = robot.from(paneHolder[0]).lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.from(paneHolder[0]).lookup("#question-search-unit").queryComboBox();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(unitBox.getItems().isEmpty());
		assertTrue(unitBox.isDisable());
	}

	@Test
	public void hierarchyFailureClearsExistingSearchState(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> resultsList.getItems().size() == 1 && unitBox.getItems().contains(currentUnit));
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		assertFalse(detailsArea.getText().isBlank());
		assertEquals("No stored question image.", previewStatus.getText());
		curriculumRepository.failChildrenFor(currentUnit);
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> statusLabel.getText().startsWith("Curriculum navigation failed:"));
		assertTrue(resultsList.getItems().isEmpty());
		assertTrue(detailsArea.getText().isBlank());
		assertTrue(previewStatus.getText().isBlank());
	}

	@Test
	public void missingAndCorruptSourcePdfsReportPreviewUnavailable(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("question-search-broken-pdf-");
		Files.writeString(pdfRoot.resolve("corrupt.pdf"), "This is not a PDF.");
		Exam exam = historicalQuestion.getExam();
		SourceDocument missingSource = new SourceDocument(80, "missing.pdf");
		SourceDocument corruptSource = new SourceDocument(81, "corrupt.pdf");
		ExamBooklet missingBooklet = new ExamBooklet(82, exam, "Missing PDF", missingSource);
		ExamBooklet corruptBooklet = new ExamBooklet(83, exam, "Corrupt PDF", corruptSource);
		Question missingQuestion = new Question(84, missingBooklet, "M1", "", 1,
				List.of(new QuestionRegion(missingBooklet, 1, 0.10, 0.10, 0.50, 0.20)), currentDescriptor, false);
		Question corruptQuestion = new Question(85, corruptBooklet, "C1", "", 1,
				List.of(new QuestionRegion(corruptBooklet, 1, 0.10, 0.10, 0.50, 0.20)), currentDescriptor, false);
		QuestionRetrievalRepository repository = currentNodes -> {
			if (!currentNodes.contains(currentDescriptor)) {
				return List.of();
			}
			return List.of(new QuestionApplicabilityMatch(missingQuestion, currentDescriptor),
					new QuestionApplicabilityMatch(corruptQuestion, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService replacementPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot),
				new QuestionExtractor());
		replaceSearchPane(robot, curriculumRepository, service, replacementPreviewService);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(chemistry));
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 2);
		QuestionSearchResult missingResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == missingQuestion.getId()).findFirst().orElseThrow();
		QuestionSearchResult corruptResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == corruptQuestion.getId()).findFirst().orElseThrow();
		robot.interact(() -> resultsList.getSelectionModel().select(missingResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> previewStatus.getText().startsWith("Question preview unavailable:"));
		assertEquals(null, preview.getImage());
		robot.interact(() -> resultsList.getSelectionModel().select(corruptResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> previewStatus.getText().startsWith("Question preview unavailable:"));
		assertEquals(null, preview.getImage());
	}

	@Test
	public void multipleCurrentSyllabusesAreReportedAsNavigationFailure(FxRobot robot) throws TimeoutException {
		Subject physics = new Subject(40, "Physics");
		SyllabusVersion firstCurrent = new SyllabusVersion(41, physics, "2025", true);
		SyllabusVersion secondCurrent = new SyllabusVersion(42, physics, "2026", true);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(physics),
				List.of(firstCurrent, secondCurrent), List.of());
		QuestionRetrievalRepository emptyRetrieval = _ -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(emptyRetrieval,
				new CurriculumSearchNodeExpansionService(repository));
		replaceSearchPane(robot, repository, service);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(physics));
		robot.interact(() -> subjectBox.setValue(physics));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> statusLabel.getText()
				.equals("Curriculum navigation failed: " + "Subject has multiple current syllabus versions"));
		assertTrue(unitBox.getItems().isEmpty());
		assertTrue(unitBox.isDisable());
		assertTrue(resultsList.getItems().isEmpty());
	}

	@Test
	public void narrowedSearchStillShowsCompleteRevisionOutputApplicability(FxRobot robot) throws TimeoutException {
		QuestionRetrievalRepository multipleApplicabilityRepository = currentNodes -> {

			// Return only applicability inside the requested Search scope.
			// A Descriptor search therefore carries one matching placement,
			// while a Subject-wide lookup carries both.
			if (currentNodes.contains(currentDescriptor) && currentNodes.contains(noMatchDescriptor)) {
				return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
						new QuestionApplicabilityMatch(historicalQuestion, noMatchDescriptor));
			}
			if (currentNodes.contains(currentDescriptor)) {
				return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor));
			}
			if (currentNodes.contains(noMatchDescriptor)) {
				return List.of(new QuestionApplicabilityMatch(historicalQuestion, noMatchDescriptor));
			}
			return List.of();
		};
		QuestionRetrievalService multipleApplicabilityService = new QuestionRetrievalService(
				multipleApplicabilityRepository, new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, multipleApplicabilityService);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionSearchPane.QuestionOutputApplicabilityRow> outputList = robot
				.lookup("#question-search-output-applicability").queryListView();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topicBox.getItems().contains(currentTopic));
		robot.interact(() -> topicBox.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(currentSubtopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> descriptorBox.getItems().contains(currentDescriptor));
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		QuestionSearchResult searchResult = resultsList.getItems().getFirst();

		// Search itself is deliberately narrowed to one Descriptor.
		assertEquals(List.of(currentDescriptor), searchResult.currentApplicability());
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 2);

		// Revision output is independent of the Search filter and therefore exposes
		// both Subject-wide current placements for the selected Question.
		assertTrue(outputList.getItems().stream().anyMatch(row -> row.currentNode().equals(currentDescriptor)));
		assertTrue(outputList.getItems().stream().anyMatch(row -> row.currentNode().equals(noMatchDescriptor)));
	}

	@Test
	public void nativeDialogCloseGuardsDirtyDescriptor(FxRobot robot) throws TimeoutException {
		Question subtopicQuestion = new Question(50, historicalQuestion.getBooklet(), "22", "", 2, List.of(),
				currentSubtopic, false);
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		QuestionSearchPane[] paneHolder = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchDialog dialog = new QuestionSearchDialog(stage, curriculumRepository, retrievalService,
					() -> List.of(subtopicQuestion), previewService, outputApplicabilityRepository, (_, _) -> {

						// This regression exercises Cancel and Discard.
						// Neither path may attempt classification persistence.
						throw new AssertionError("Classification persistence was not expected");
					});
			dialogHolder[0] = dialog;
			paneHolder[0] = (QuestionSearchPane) dialog.getDialogPane().getContent();
			dialog.show();
		});
		QuestionSearchDialog dialog = dialogHolder[0];
		QuestionSearchPane pane = paneHolder[0];
		ComboBox<QuestionSearchScope> scopeBox = robot.from(pane).lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.from(pane).lookup("#question-search-results")
				.queryListView();
		ComboBox<CurriculumNode> selectedDescriptorBox = robot.from(pane).lookup("#question-search-selected-descriptor")
				.queryComboBox();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> selectedDescriptorBox.getItems().contains(currentDescriptor));
		robot.interact(() -> selectedDescriptorBox.setValue(currentDescriptor));
		assertTrue(pane.isClassificationDirty());

		// JavaFX's native window-close path reaches Dialog.close() without firing
		// the DialogPane Close button ActionEvent. Queue the close because its
		// dirty-state handler opens a modal confirmation Alert.
		Platform.runLater(dialog::close);
		fireShowingDialogButton(robot, "Unsaved Question", "Cancel");
		WaitForAsyncUtils.waitForFxEvents();

		// Cancel must veto the native close and retain the unsaved refinement.
		assertTrue(dialog.isShowing());
		assertTrue(pane.isClassificationDirty());
		Platform.runLater(dialog::close);
		fireShowingDialogButton(robot, "Unsaved Question", "Discard Changes");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !dialog.isShowing());

		// Discard allows the same native close route to complete without
		// persisting the Descriptor refinement.
		assertFalse(pane.isClassificationDirty());
		robot.interact(dialog::dispose);
	}

	@Test
	public void refreshAfterEditRetainsAllQuestionsScopeAndReselectsUpdatedQuestion(FxRobot robot)
			throws TimeoutException {
		AtomicReference<List<Question>> allQuestions = new AtomicReference<>(List.of(historicalQuestion));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService, allQuestions::get);
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
		Question editedQuestion = new Question(historicalQuestion.getId(), historicalQuestion.getBooklet(), "21b",
				historicalQuestion.getQuestionText(), historicalQuestion.getMarks(), historicalQuestion.getRegions(),
				historicalQuestion.getClassification(), historicalQuestion.isSharedContextCaptureRequired(),
				historicalQuestion.getSourceQuestion(), historicalQuestion.getSharedContext(),
				historicalQuestion.getResponseType());

		// Simulate the repository returning the corrected Question after an edit.
		allQuestions.set(List.of(editedQuestion));
		robot.interact(() -> pane.refreshAfterEdit(editedQuestion.getId()));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> pane.getSelectedQuestion() != null && "21b".equals(pane.getSelectedQuestion().getQuestionCode()));

		// Refresh must preserve the scope the teacher was viewing rather than silently
		// reverting to curriculum-aware Search.
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scopeBox.getValue());
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, resultsList.getSelectionModel().getSelectedItem().scope());
		assertEquals(editedQuestion.getId(), pane.getSelectedQuestion().getId());
	}

	@Test
	public void returningFromAllQuestionsRestoresCurrentSyllabusSearch(FxRobot robot) throws TimeoutException {
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.CURRENT_SYLLABUS);
		assertEquals(chemistry, subjectBox.getValue());
		assertFalse(subjectBox.isDisable());
	}

	@Test
	public void revisionOutputApplicabilityCanExcludeAndRestoreOnePlacement(FxRobot robot) throws TimeoutException {
		QuestionRetrievalRepository multipleApplicabilityRepository = currentNodes -> {
			if (!currentNodes.contains(currentDescriptor) || !currentNodes.contains(noMatchDescriptor)) {
				return List.of();
			}

			// One historical Question is applicable at two distinct current
			// Descriptors so the test can prove that only the selected placement
			// is changed.
			return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
					new QuestionApplicabilityMatch(historicalQuestion, noMatchDescriptor));
		};
		QuestionRetrievalService multipleApplicabilityService = new QuestionRetrievalService(
				multipleApplicabilityRepository, new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, multipleApplicabilityService);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionSearchPane.QuestionOutputApplicabilityRow> outputList = robot
				.lookup("#question-search-output-applicability").queryListView();
		Button includeButton = robot.lookup("#question-search-output-include").queryButton();
		Button excludeButton = robot.lookup("#question-search-output-exclude").queryButton();
		Label outputStatus = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 2);
		QuestionSearchPane.QuestionOutputApplicabilityRow firstPlacement = outputList.getItems().stream()
				.filter(row -> row.currentNode().equals(currentDescriptor)).findFirst().orElseThrow();
		robot.interact(() -> outputList.getSelectionModel().select(firstPlacement));

		// An included placement can be excluded, but Include is meaningless until
		// that exclusion has actually been persisted.
		assertTrue(includeButton.isDisable());
		assertFalse(excludeButton.isDisable());
		robot.clickOn("#question-search-output-exclude");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> outputList.getItems().stream().filter(row -> row.currentNode().equals(currentDescriptor))
						.anyMatch(QuestionSearchPane.QuestionOutputApplicabilityRow::excluded));
		assertEquals(Set.of(currentDescriptor.getId()),
				outputApplicabilityRepository.findExcludedCurrentNodeIds(historicalQuestion));

		// The second placement remains independently included.
		assertFalse(outputList.getItems().stream().filter(row -> row.currentNode().equals(noMatchDescriptor))
				.findFirst().orElseThrow().excluded());
		assertEquals("2 current placements: 1 included, 1 excluded.", outputStatus.getText());
		assertFalse(includeButton.isDisable());
		assertTrue(excludeButton.isDisable());
		robot.clickOn("#question-search-output-include");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> outputList.getItems().stream().filter(row -> row.currentNode().equals(currentDescriptor))
						.noneMatch(QuestionSearchPane.QuestionOutputApplicabilityRow::excluded));

		// Removing the exception restores normal curriculum-derived applicability.
		assertEquals(Set.of(), outputApplicabilityRepository.findExcludedCurrentNodeIds(historicalQuestion));
		assertEquals("2 current placements: 2 included, 0 excluded.", outputStatus.getText());
		assertTrue(includeButton.isDisable());
		assertFalse(excludeButton.isDisable());
	}

	@Test
	public void selectedDescriptorQuestionShowsStoredClassificationPath(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> selectedUnitBox = robot.lookup("#question-search-selected-unit").queryComboBox();
		ComboBox<CurriculumNode> selectedTopicBox = robot.lookup("#question-search-selected-topic").queryComboBox();
		ComboBox<CurriculumNode> selectedDescriptorBox = robot.lookup("#question-search-selected-descriptor")
				.queryComboBox();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		assertEquals(historicalUnit, selectedUnitBox.getValue());
		assertEquals(historicalDescriptor.getParent(), selectedTopicBox.getValue());
		assertEquals(historicalDescriptor, selectedDescriptorBox.getValue());
		assertTrue(selectedUnitBox.isDisable());
		assertTrue(selectedTopicBox.isDisable());
		assertTrue(selectedDescriptorBox.isDisable());
	}

	@Test
	public void selectedQuestionShowsRevisionOutputExclusion(FxRobot robot) throws TimeoutException {
		outputApplicabilityRepository.setExcluded(historicalQuestion, currentDescriptor, true);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionSearchPane.QuestionOutputApplicabilityRow> outputList = robot
				.lookup("#question-search-output-applicability").queryListView();
		Label outputStatus = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 1);
		QuestionSearchPane.QuestionOutputApplicabilityRow row = outputList.getItems().getFirst();
		assertEquals(currentDescriptor, row.currentNode());
		assertTrue(row.excluded());

		// The selected Question remains curriculum-applicable, but its one current
		// placement is explicitly suppressed from revision output.
		assertEquals("1 current placement: 0 included, 1 excluded.", outputStatus.getText());
	}

	@Test
	public void selectedSubtopicQuestionShowsBlankDescriptorWithChoices(FxRobot robot) throws TimeoutException {
		Question subtopicQuestion = new Question(50, historicalQuestion.getBooklet(), "22", "", 2, List.of(),
				currentSubtopic, false);
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService,
				() -> List.of(subtopicQuestion));
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> selectedSubtopicBox = robot.lookup("#question-search-selected-subtopic")
				.queryComboBox();
		ComboBox<CurriculumNode> selectedDescriptorBox = robot.lookup("#question-search-selected-descriptor")
				.queryComboBox();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		assertEquals(currentSubtopic, selectedSubtopicBox.getValue());
		assertEquals(null, selectedDescriptorBox.getValue());
		assertEquals(List.of(currentDescriptor), selectedDescriptorBox.getItems());

		// Editing is enabled in the next drop together with dirty-state protection.
		// A stored Subtopic may be refined to one of its immediate Descriptors
		// directly from Search.
		assertFalse(selectedDescriptorBox.isDisable());
		robot.interact(() -> selectedDescriptorBox.setValue(currentDescriptor));
		Button saveClassificationButton = robot.lookup("#question-search-save-classification").queryButton();

		// Save must retain its complete label even when the surrounding GridPane
		// competes for horizontal space.
		assertEquals(Region.USE_PREF_SIZE, saveClassificationButton.getMinWidth());

		// Selecting a Descriptor enables its dedicated Save action without changing
		// the meaning of Edit Question.
		assertFalse(saveClassificationButton.isDisable());

		// Selecting the Descriptor enters the inline dirty state and temporarily
		// locks navigation until Save Question is used.
		assertTrue(pane.classificationDirtyProperty().get());
		assertTrue(scopeBox.isDisable());

		// Result navigation remains available so attempting to leave a dirty Question
		// can invoke the Save / Discard Changes / Cancel guard.
		assertFalse(resultsList.isDisable());
		assertFalse(selectedDescriptorBox.isDisable());
	}

	@Test
	public void staleAllQuestionsCompletionCannotOverwriteNewerCurrentSyllabusSearch(FxRobot robot)
			throws TimeoutException {
		DelayedAllQuestionsSupplier allQuestions = new DelayedAllQuestionsSupplier(List.of(historicalQuestion));
		replaceSearchPane(robot, curriculumRepository, retrievalService, allQuestions);
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, allQuestions::hasDelayStarted);

		// Supersede the still-running all-bank request with a normal current-syllabus
		// Subject search.
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(chemistry));
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.CURRENT_SYLLABUS);

		// Allow the cancelled All Questions task to complete late. Generation and task
		// identity checks must prevent it from replacing the newer result.
		allQuestions.release();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, allQuestions::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(QuestionSearchScope.CURRENT_SYLLABUS, scopeBox.getValue());
		assertEquals(1, resultsList.getItems().size());
		assertEquals(QuestionSearchScope.CURRENT_SYLLABUS, resultsList.getItems().getFirst().scope());
		assertEquals(historicalQuestion.getId(), resultsList.getItems().getFirst().question().getId());
	}

	@Test
	public void staleHierarchyLoadCannotRestoreLowerScope(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		curriculumRepository.delayChildrenFor(currentUnit);
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> curriculumRepository.hasDelayStarted());
		robot.clickOn("#question-search-subject");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> unitBox.getValue() == null && topicBox.getItems().isEmpty());
		curriculumRepository.releaseDelayedChildren();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> curriculumRepository.hasDelayFinished());
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(null, unitBox.getValue());
		assertEquals("Select unit", unitBox.getButtonCell().getText());
		assertTrue(topicBox.getItems().isEmpty());
		assertEquals("Select topic", topicBox.getButtonCell().getText());
	}

	@Test
	public void stalePreviewCompletionCannotReplaceNewerPreview(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("question-search-preview-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		Exam exam = historicalQuestion.getExam();
		SourceDocument sourceDocument = new SourceDocument(60, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(61, exam, "Preview booklet", sourceDocument);
		Question first = new Question(62, booklet, "P1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), currentDescriptor, false);
		Question second = new Question(63, booklet, "P2", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.40, 0.50, 0.20)), currentDescriptor, false);
		QuestionRetrievalRepository repository = currentNodes -> {
			if (!currentNodes.contains(currentDescriptor)) {
				return List.of();
			}
			return List.of(new QuestionApplicabilityMatch(first, currentDescriptor),
					new QuestionApplicabilityMatch(second, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		DelayedQuestionExtractor extractor = new DelayedQuestionExtractor(first.getId());
		QuestionPreviewService replacementPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		replaceSearchPane(robot, curriculumRepository, service, replacementPreviewService);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(chemistry));
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 2);
		QuestionSearchResult firstResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == first.getId()).findFirst().orElseThrow();
		QuestionSearchResult secondResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == second.getId()).findFirst().orElseThrow();
		robot.interact(() -> resultsList.getSelectionModel().select(firstResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayStarted);
		robot.interact(() -> resultsList.getSelectionModel().select(secondResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> preview.getImage() != null && preview.getImage().getWidth() == 22.0);
		extractor.releaseDelayedExtraction();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(22.0, preview.getImage().getWidth());
	}

	@Test
	public void staleQuestionSearchCompletionCannotOverwriteNewerScope(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topicBox.getItems().contains(currentTopic));
		robot.interact(() -> topicBox.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(currentSubtopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> descriptorBox.getItems().contains(currentDescriptor));
		retrievalRepository.delayNextRequestFor(currentDescriptor);
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, retrievalRepository::hasDelayStarted);
		robot.interact(() -> topicBox.setValue(noMatchTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(noMatchDescriptor));
		robot.interact(() -> classificationBox.setValue(noMatchDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> "No questions found.".equals(statusLabel.getText()));
		assertTrue(resultsList.getItems().isEmpty());
		retrievalRepository.releaseDelayedRequest();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, retrievalRepository::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(resultsList.getItems().isEmpty());
		assertEquals("No questions found.", statusLabel.getText());
		assertEquals(noMatchDescriptor, classificationBox.getValue());
	}

	@Start
	public void start(Stage stage) {
		this.stage = stage;
		chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historicalVersion = new SyllabusVersion(2, chemistry, "2019", false);
		SyllabusVersion currentVersion = new SyllabusVersion(3, chemistry, "2025", true);
		historicalUnit = new Unit(4, historicalVersion, "1", "Historical Unit 1", 1);
		Topic historicalTopic = new Topic(5, historicalVersion, historicalUnit, "1.1", "Historical Topic", 1);
		historicalDescriptor = new Descriptor(6, historicalVersion, historicalTopic, "1.1.1", "Historical descriptor",
				1);
		currentUnit = new Unit(7, currentVersion, "1", "Current Unit 1", 1);
		currentTopic = new Topic(8, currentVersion, currentUnit, "1.1", "Current Topic", 1);
		currentSubtopic = new Subtopic(9, currentVersion, currentTopic, "1.1.1", "Current Subtopic", 1);
		currentDescriptor = new Descriptor(10, currentVersion, currentSubtopic, "1.1.1.1", "Current descriptor", 1);
		noMatchTopic = new Topic(16, currentVersion, currentUnit, "1.2", "No-match Topic", 2);
		noMatchDescriptor = new Descriptor(17, currentVersion, noMatchTopic, "1.2.1", "No-match descriptor", 1);
		curriculumRepository = new DelayedCurriculumRepository(List.of(chemistry),
				List.of(historicalVersion, currentVersion),
				List.of(historicalUnit, historicalTopic, historicalDescriptor, currentUnit, currentTopic,
						currentSubtopic, currentDescriptor, noMatchTopic, noMatchDescriptor));
		ExamProvider provider = new ExamProvider(11, "QCAA");
		Exam exam = new Exam(12, chemistry, provider, 2020, "External Assessment");
		SourceDocument sourceDocument = new SourceDocument(13, "Chemistry/2020/paper1.pdf");
		ExamBooklet booklet = new ExamBooklet(14, exam, "Paper 1", sourceDocument);
		historicalQuestion = new Question(15, booklet, "21a", "Calculate the requested quantity.", 3, List.of(),
				historicalDescriptor, false);
		retrievalRepository = new DelayedQuestionRetrievalRepository(currentDescriptor, historicalQuestion);
		retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		previewService = new QuestionPreviewService(new PdfStore(Path.of(".")), new QuestionExtractor());
		outputApplicabilityRepository = new InMemoryQuestionOutputApplicabilityRepository();
		QuestionSearchPane pane = new QuestionSearchPane(curriculumRepository, retrievalService,
				() -> List.of(historicalQuestion), previewService, outputApplicabilityRepository);
		stage.setScene(new Scene(pane, 700, 600));
		stage.show();
	}

	@Test
	public void subjectSelectionTriggersSearchAutomatically(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(historicalQuestion.getId(), resultsList.getItems().get(0).question().getId());
	}

	@Test
	public void subjectSelectionUsesOnlyCurrentSyllabus(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().size() == 1);
		assertEquals(currentUnit, unitBox.getItems().get(0));
		assertFalse(unitBox.getItems().contains(historicalUnit));
		assertFalse(unitBox.isDisable());
	}

	@Test
	public void subjectWithoutCurrentSyllabusLeavesNavigationDisabled(FxRobot robot) throws TimeoutException {
		Subject historyOnly = new Subject(30, "History Only");
		SyllabusVersion historical = new SyllabusVersion(31, historyOnly, "2020", false);
		Unit historicalUnitOnly = new Unit(32, historical, "1", "Historical Unit", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(historyOnly),
				List.of(historical), List.of(historicalUnitOnly));
		QuestionRetrievalRepository emptyRetrieval = _ -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(emptyRetrieval,
				new CurriculumSearchNodeExpansionService(repository));
		replaceSearchPane(robot, repository, service);
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> subjectBox.getItems().contains(historyOnly));
		robot.interact(() -> subjectBox.setValue(historyOnly));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> "No current syllabus available.".equals(statusLabel.getText()));
		assertTrue(unitBox.getItems().isEmpty());
		assertTrue(unitBox.isDisable());
		assertTrue(resultsList.getItems().isEmpty());
	}

	@Test
	public void subtopicSelectionExposesDescriptorChildren(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topicBox.getItems().contains(currentTopic));
		robot.interact(() -> topicBox.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> classificationBox.getItems().equals(List.of(currentSubtopic)));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> descriptorBox.getItems().equals(List.of(currentDescriptor)));
		assertFalse(descriptorBox.isDisable());
	}

	@Test
	public void unitSelectionTriggersSearchAutomatically(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> subjectBox.setValue(chemistry));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(historicalQuestion.getId(), resultsList.getItems().get(0).question().getId());
	}

	@Test
	public void zeroRegionLegacyQuestionReportsNoStoredImage(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> "No stored question image.".equals(previewStatus.getText()));
		assertEquals(null, preview.getImage());
	}

	private Path createOnePagePdf(Path path) throws IOException {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		return path;
	}

	private void extracted(QuestionSearchDialog[] dialogHolder, QuestionSearchPane[] paneHolder) {
		QuestionSearchDialog dialog = new QuestionSearchDialog(stage, curriculumRepository, retrievalService,
				() -> List.of(historicalQuestion), previewService, outputApplicabilityRepository, (_, _) -> {

					// This test exercises Dialog disposal only. A classification
					// persistence request would indicate unrelated behaviour.
					throw new AssertionError("Classification update was not expected");
				});
		QuestionSearchPane pane = (QuestionSearchPane) dialog.getDialogPane().getContent();
		dialogHolder[0] = dialog;
		paneHolder[0] = pane;
		dialog.show();
		dialog.hide();
		dialog.dispose();
	}

	private void fireShowingDialogButton(FxRobot robot, String dialogTitle, String buttonText) throws TimeoutException {
		AtomicReference<DialogPane> dialogPane = new AtomicReference<>();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			dialogPane.set(null);
			robot.interact(() -> linkPaneToWindow(dialogTitle, dialogPane));
			return dialogPane.get() != null;
		});
		DialogPane pane = dialogPane.get();
		ButtonType buttonType = pane.getButtonTypes().stream()
				.filter(candidate -> buttonText.equals(candidate.getText())).findFirst()
				.orElseThrow(() -> new AssertionError("Dialog button not found: " + buttonText));
		Node buttonNode = pane.lookupButton(buttonType);
		if (!(buttonNode instanceof Button button)) {
			throw new AssertionError("Dialog button is not a Button: " + buttonText);
		}

		// Fire the control belonging to the actual showing Dialog rather than using a
		// text lookup that could match a retained hidden Dialog node.
		robot.interact(button::fire);
		WaitForAsyncUtils.waitForFxEvents();
	}

	private void linkPaneToWindow(String dialogTitle, AtomicReference<DialogPane> dialogPane) {
		for (Window window : Window.getWindows()) {
			if (!window.isShowing() || window.getScene() == null || !(window instanceof Stage showingStage)
					|| !dialogTitle.equals(showingStage.getTitle())) {
				continue;
			}
			Node root = window.getScene().getRoot();
			if (root instanceof DialogPane pane) {
				dialogPane.set(pane);
			} else {
				Node candidate = root.lookup(".dialog-pane");
				if (candidate instanceof DialogPane pane) {
					dialogPane.set(pane);
				}
			}
			if (dialogPane.get() != null) {
				break;
			}
		}
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service) {
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();

			// Replacement panes retain the same revision-output repository so
			// individual tests may preconfigure inclusion state.
			pane[0] = new QuestionSearchPane(repository, service, () -> List.of(historicalQuestion), previewService,
					outputApplicabilityRepository);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		return pane[0];
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service, QuestionPreviewService replacementPreviewService) {
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();

			// Keep complete-bank retrieval and output-applicability persistence
			// stable while this overload varies only the preview service.
			pane[0] = new QuestionSearchPane(repository, service, () -> List.of(historicalQuestion),
					replacementPreviewService, outputApplicabilityRepository);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		return pane[0];
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service, Supplier<List<Question>> allQuestionsSupplier) {
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();

			// This overload varies the complete-bank source while retaining the
			// same revision-output state for selected Questions.
			pane[0] = new QuestionSearchPane(repository, service, allQuestionsSupplier, previewService,
					outputApplicabilityRepository);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		return pane[0];
	}

	private static final class DelayedAllQuestionsSupplier implements Supplier<List<Question>> {

		private final List<Question> questions;
		private final CountDownLatch delayStarted = new CountDownLatch(1);
		private final CountDownLatch delayRelease = new CountDownLatch(1);
		private final CountDownLatch delayFinished = new CountDownLatch(1);

		private DelayedAllQuestionsSupplier(List<Question> questions) {
			this.questions = List.copyOf(questions);
		}

		@Override
		public List<Question> get() {
			delayStarted.countDown();
			boolean released = false;
			while (!released) {
				try {
					delayRelease.await();
					released = true;
				} catch (InterruptedException exception) {

					// Ignore task cancellation deliberately so the obsolete all-bank request
					// can finish after a newer current-syllabus search has already completed.
				}
			}
			delayFinished.countDown();
			return questions;
		}

		private boolean hasDelayFinished() {
			return delayFinished.getCount() == 0;
		}

		private boolean hasDelayStarted() {
			return delayStarted.getCount() == 0;
		}

		private void release() {
			delayRelease.countDown();
		}
	}

	private static final class DelayedCurriculumRepository extends InMemoryCurriculumRepository {

		private volatile CurriculumNode delayedParent;
		private volatile CurriculumNode failingParent;
		private volatile CountDownLatch delayStarted = new CountDownLatch(0);
		private volatile CountDownLatch delayRelease = new CountDownLatch(0);
		private volatile CountDownLatch delayFinished = new CountDownLatch(0);
		private volatile boolean delaySubjectLoading;
		private volatile CountDownLatch subjectDelayStarted = new CountDownLatch(0);
		private volatile CountDownLatch subjectDelayRelease = new CountDownLatch(0);
		private volatile CountDownLatch subjectDelayFinished = new CountDownLatch(0);

		private DelayedCurriculumRepository(List<Subject> subjects, List<SyllabusVersion> syllabusVersions,
				List<CurriculumNode> curriculumNodes) {
			super(subjects, syllabusVersions, curriculumNodes);
		}

		@Override
		public List<Subject> findAllSubjects() {
			if (!delaySubjectLoading) {
				return super.findAllSubjects();
			}
			CountDownLatch release = subjectDelayRelease;
			subjectDelayStarted.countDown();
			boolean released = false;
			while (!released) {
				try {
					release.await();
					released = true;
				} catch (InterruptedException exception) {

					// Ignore cancellation deliberately so this test can prove that a completed
					// stale Subject load is discarded after Search scope changes.
				}
			}
			try {
				return super.findAllSubjects();
			} finally {
				subjectDelayFinished.countDown();
			}
		}

		@Override
		public List<CurriculumNode> findChildren(CurriculumNode parent) {
			CurriculumNode parentToFail = failingParent;
			if (parentToFail != null && parentToFail.equals(parent)) {
				throw new IllegalStateException("Simulated hierarchy failure");
			}
			CurriculumNode parentToDelay = delayedParent;
			if (parentToDelay == null || !parentToDelay.equals(parent)) {
				return super.findChildren(parent);
			}
			CountDownLatch release = delayRelease;
			delayStarted.countDown();
			boolean released = false;
			while (!released) {
				try {
					release.await();
					released = true;
				} catch (InterruptedException e) {

					// Deliberately ignore cancellation so this test proves a late hierarchy result
					// cannot overwrite the newer UI state.
				}
			}
			try {
				return super.findChildren(parent);
			} finally {
				delayFinished.countDown();
			}
		}

		private void delayChildrenFor(CurriculumNode parent) {
			delayStarted = new CountDownLatch(1);
			delayRelease = new CountDownLatch(1);
			delayFinished = new CountDownLatch(1);
			delayedParent = parent;
		}

		private void delaySubjects() {
			delaySubjectLoading = true;
			subjectDelayStarted = new CountDownLatch(1);
			subjectDelayRelease = new CountDownLatch(1);
			subjectDelayFinished = new CountDownLatch(1);
		}

		private void failChildrenFor(CurriculumNode parent) {
			failingParent = parent;
		}

		private boolean hasDelayFinished() {
			return delayFinished.getCount() == 0;
		}

		private boolean hasDelayStarted() {
			return delayStarted.getCount() == 0;
		}

		private boolean hasSubjectDelayFinished() {
			return subjectDelayFinished.getCount() == 0;
		}

		private boolean hasSubjectDelayStarted() {
			return subjectDelayStarted.getCount() == 0;
		}

		private void releaseDelayedChildren() {
			delayedParent = null;
			delayRelease.countDown();
		}

		private void releaseDelayedSubjects() {

			// Future Subject loads should complete normally; only the already-running load
			// remains blocked on this latch.
			delaySubjectLoading = false;
			subjectDelayRelease.countDown();
		}
	}

	private static final class DelayedQuestionExtractor extends QuestionExtractor {

		private final long delayedQuestionId;
		private volatile CountDownLatch delayStarted = new CountDownLatch(1);
		private volatile CountDownLatch delayRelease = new CountDownLatch(1);
		private volatile CountDownLatch delayFinished = new CountDownLatch(1);

		private DelayedQuestionExtractor(long delayedQuestionId) {
			this.delayedQuestionId = delayedQuestionId;
		}

		@Override
		public BufferedImage extractQuestion(PdfSession session, Question question) throws IOException {
			if (question.getId() == delayedQuestionId) {
				delayStarted.countDown();
				boolean released = false;
				while (!released) {
					try {
						delayRelease.await();
						released = true;
					} catch (InterruptedException e) {

						// Ignore cancellation deliberately so the old preview can finish after the
						// newer UI state.
					}
				}
				delayFinished.countDown();
				return new BufferedImage(11, 11, BufferedImage.TYPE_INT_RGB);
			}
			return new BufferedImage(22, 22, BufferedImage.TYPE_INT_RGB);
		}

		private boolean hasDelayFinished() {
			return delayFinished.getCount() == 0;
		}

		private boolean hasDelayStarted() {
			return delayStarted.getCount() == 0;
		}

		private void releaseDelayedExtraction() {
			delayRelease.countDown();
		}
	}

	private static final class DelayedQuestionRetrievalRepository implements QuestionRetrievalRepository {

		private final CurriculumNode matchingNode;
		private final Question matchingQuestion;
		private volatile CurriculumNode delayedNode;
		private volatile CountDownLatch delayStarted = new CountDownLatch(0);
		private volatile CountDownLatch delayRelease = new CountDownLatch(0);
		private volatile CountDownLatch delayFinished = new CountDownLatch(0);

		private DelayedQuestionRetrievalRepository(CurriculumNode matchingNode, Question matchingQuestion) {
			this.matchingNode = matchingNode;
			this.matchingQuestion = matchingQuestion;
		}

		@Override
		public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
			CurriculumNode nodeToDelay = delayedNode;
			if (nodeToDelay != null && currentNodes.size() == 1 && currentNodes.getFirst().equals(nodeToDelay)) {
				CountDownLatch release = delayRelease;
				delayStarted.countDown();
				boolean released = false;
				while (!released) {
					try {
						release.await();
						released = true;
					} catch (InterruptedException e) {

						// Ignore cancellation deliberately so a superseded retrieval can finish after
						// the newer search.
					}
				}
				delayFinished.countDown();
			}
			if (currentNodes.contains(matchingNode)) {
				return List.of(new QuestionApplicabilityMatch(matchingQuestion, matchingNode));
			}
			return List.of();
		}

		private void delayNextRequestFor(CurriculumNode node) {
			delayedNode = node;
			delayStarted = new CountDownLatch(1);
			delayRelease = new CountDownLatch(1);
			delayFinished = new CountDownLatch(1);
		}

		private boolean hasDelayFinished() {
			return delayFinished.getCount() == 0;
		}

		private boolean hasDelayStarted() {
			return delayStarted.getCount() == 0;
		}

		private void releaseDelayedRequest() {
			delayedNode = null;
			delayRelease.countDown();
		}
	}
}
