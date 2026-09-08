package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

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
	private QuestionPreviewService previewService;
	private QuestionRetrievalService retrievalService;
	private Stage stage;

	@Test
	public void broadeningFromDescriptorToSubjectRestoresSubjectScope(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ListView<QuestionRetrievalResult> resultsList = robot.lookup("#question-search-results").queryListView();
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
		assertEquals(historicalQuestion.getId(), resultsList.getItems().get(0).getQuestion().getId());
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
		ListView<QuestionRetrievalResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		robot.interact(() -> subjectBox.setValue(chemistry));
		robot.interact(() -> unitBox.setValue(currentUnit));
		robot.interact(() -> topicBox.setValue(currentTopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(1, resultsList.getItems().size());
		QuestionRetrievalResult result = resultsList.getItems().get(0);
		assertEquals(historicalQuestion.getId(), result.getQuestion().getId());
		assertEquals(historicalDescriptor, result.getOriginalClassification());
		assertEquals(List.of(currentDescriptor), result.getCurrentApplicability());
		robot.interact(() -> resultsList.getSelectionModel().select(0));
		assertTrue(detailsArea.getText().contains("2019 1.1.1 Historical descriptor"));
		assertTrue(detailsArea.getText().contains("2025 1.1.1.1 Current descriptor"));
		assertTrue(detailsArea.getText().contains("Calculate the requested quantity."));
	}

	@Test
	public void hidingDialogPreventsFurtherHierarchyWork(FxRobot robot) {
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		QuestionSearchPane[] paneHolder = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchDialog dialog = new QuestionSearchDialog(stage, curriculumRepository, retrievalService,
					previewService);
			QuestionSearchPane pane = (QuestionSearchPane) dialog.getDialogPane().getContent();
			dialogHolder[0] = dialog;
			paneHolder[0] = pane;
			dialog.show();
			dialog.hide();
		});
		ComboBox<Subject> subjectBox = (ComboBox<Subject>) paneHolder[0].lookup("#question-search-subject");
		ComboBox<CurriculumNode> unitBox = (ComboBox<CurriculumNode>) paneHolder[0].lookup("#question-search-unit");
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(unitBox.getItems().isEmpty());
		assertTrue(unitBox.isDisable());
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
		QuestionRetrievalRepository retrievalRepository = currentNodes -> {
			if (currentNodes.contains(currentDescriptor)) {
				return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor));
			}
			return List.of();
		};
		retrievalService = new QuestionRetrievalService(retrievalRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		previewService = new QuestionPreviewService(new PdfStore(Path.of(".")), new QuestionExtractor());
		QuestionSearchPane pane = new QuestionSearchPane(curriculumRepository, retrievalService, previewService);
		stage.setScene(new Scene(pane, 700, 600));
		stage.show();
	}

	@Test
	public void subjectSelectionTriggersSearchAutomatically(FxRobot robot) throws TimeoutException {
		ComboBox<Subject> subjectBox = robot.lookup("#question-search-subject").queryComboBox();
		ListView<QuestionRetrievalResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> subjectBox.setValue(chemistry));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(historicalQuestion.getId(), resultsList.getItems().get(0).getQuestion().getId());
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
		ListView<QuestionRetrievalResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> subjectBox.setValue(chemistry));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(historicalQuestion.getId(), resultsList.getItems().get(0).getQuestion().getId());
	}

	private static final class DelayedCurriculumRepository extends InMemoryCurriculumRepository {

		private volatile CurriculumNode delayedParent;
		private volatile CountDownLatch delayStarted = new CountDownLatch(0);
		private volatile CountDownLatch delayRelease = new CountDownLatch(0);
		private volatile CountDownLatch delayFinished = new CountDownLatch(0);

		private DelayedCurriculumRepository(List<Subject> subjects, List<SyllabusVersion> syllabusVersions,
				List<CurriculumNode> curriculumNodes) {
			super(subjects, syllabusVersions, curriculumNodes);
		}

		@Override
		public List<CurriculumNode> findChildren(CurriculumNode parent) {
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
					/*
					 * Deliberately ignore cancellation so this test proves a late hierarchy result
					 * cannot overwrite the newer UI state.
					 */
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

		private boolean hasDelayFinished() {
			return delayFinished.getCount() == 0;
		}

		private boolean hasDelayStarted() {
			return delayStarted.getCount() == 0;
		}

		private void releaseDelayedChildren() {
			delayedParent = null;
			delayRelease.countDown();
		}
	}
}