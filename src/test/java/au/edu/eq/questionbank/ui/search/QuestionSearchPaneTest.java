package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
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
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

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
	public void allQuestionsScopeShowsWorkingSubjectWithoutCurriculumApplicability(FxRobot robot)
			throws TimeoutException {
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		ListView<QuestionOutputApplicabilityRow> outputList = robot.lookup("#question-search-output-applicability")
				.queryListView();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);

		// All Questions stays within the workspace Working Subject but does not use
		// current-syllabus hierarchy controls to filter those stored Questions.
		assertTrue(robot.lookup("#question-search-subject").tryQuery().isEmpty());
		assertTrue(unitBox.isDisable());
		QuestionSearchResult result = resultsList.getItems().getFirst();
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, result.scope());
		assertEquals(historicalQuestion.getId(), result.question().getId());
		assertTrue(result.currentApplicability().isEmpty());
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());

		// Empty applicability on the Search result means curriculum applicability was
		// not evaluated as part of All Questions itself.
		assertTrue(detailsArea.getText().contains("Not evaluated in All Questions scope."));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 1);
		QuestionOutputApplicabilityRow outputRow = outputList.getItems().getFirst();

		// Selecting the Question still performs the independent applicability lookup
		// required for Revision Output controls.
		assertEquals(currentDescriptor, outputRow.currentNode());
		assertFalse(outputRow.excluded());
	}

	@Test
	public void allQuestionsUsesWorkingSubjectFilter(FxRobot robot) throws TimeoutException {
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

		// Supply Questions from two Subjects so the test proves that All Questions is
		// constrained by the authoritative workspace Working Subject rather than merely
		// reflecting the contents returned by the complete-bank supplier.
		replaceSearchPane(robot, repository, retrievalService, () -> List.of(historicalQuestion, physicsQuestion));
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();

		// Search no longer exposes a competing Subject selector.
		assertTrue(robot.lookup("#question-search-subject").tryQuery().isEmpty());

		// Changing Search scope is a semantic ComboBox action, so select the value
		// directly rather than relying on pointer interaction.
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));

		// The pane was constructed with Chemistry as its Working Subject. The Physics
		// Question must therefore remain outside Search even in All Questions scope.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().question().getId() == historicalQuestion.getId());
		assertEquals(historicalQuestion.getId(), resultsList.getItems().getFirst().question().getId());
	}

	@Test
	public void burstSelectionsPreserveLatestResultWithHeavyPdfAndSqlite(FxRobot robot) throws Exception {
		final int selectionChanges = 20;
		final int heldWorkers = 3;
		Path directory = Files.createTempDirectory("question-search-burst-");
		Path pdfRoot = directory.resolve("pdf");
		Files.createDirectories(pdfRoot);
		createDensePdf(pdfRoot.resolve("questions.pdf"));
		Path databasePath = directory.resolve("questions.db");
		Path performanceCsv = directory.resolve("performance.csv");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		PerformanceRecorder recorder = new PerformanceRecorder(true, performanceCsv);
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Burst Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);
		Unit unit = writer.insertUnit(version, "1", "Unit 1", 1);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = writer.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		Descriptor descriptor = writer.insertDescriptor(subtopic, "1.1.1.1", "Descriptor 1", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(subject, "QCAA", 2025, "External Assessment", "Paper 1",
				"questions.pdf");
		SqliteQuestionRepository sqliteQuestions = new SqliteQuestionRepository(database, recorder);
		List<Question> questions = new ArrayList<>();
		Map<QuestionRegion, Long> questionIdsByRegion = new HashMap<>();
		for (int index = 0; index <= selectionChanges; index++) {
			double x = 0.10 + index * 0.01;
			double width = index == selectionChanges ? 0.65 : 0.40;
			QuestionRegion region = new QuestionRegion(booklet, 1, x, 0.10, width, 0.20);
			Question question = sqliteQuestions.save(booklet, "B" + index, "", 2, List.of(region), descriptor, false);
			questions.add(question);
			questionIdsByRegion.put(region, question.getId());
		}
		List<Question> sourceQuestions = List.copyOf(questions);
		long finalQuestionId = sourceQuestions.getLast().getId();
		Set<Long> heldQuestionIds = sourceQuestions.subList(0, heldWorkers).stream().map(Question::getId)
				.collect(Collectors.toSet());
		PostWorkGate pdfGate = new PostWorkGate(heldQuestionIds);
		PostWorkGate sqliteGate = new PostWorkGate(heldQuestionIds);
		RealSelectionWorkProbe previewProbe = new RealSelectionWorkProbe();
		RealSelectionWorkProbe applicabilityProbe = new RealSelectionWorkProbe();
		AtomicLong actualPdfNanos = new AtomicLong();
		AtomicLong actualSqliteNanos = new AtomicLong();
		QuestionRetrievalRepository measuredRepository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> nodes) {
				return sqliteQuestions.findApplicableToNodes(nodes);
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> nodes,
					long questionId, long parentOperationId) {
				try {
					return applicabilityProbe.measure(questionId, () -> {
						long startedAt = System.nanoTime();
						List<QuestionApplicabilityMatch> matches = sqliteQuestions
								.findApplicableToNodesForQuestion(nodes, questionId, parentOperationId);
						actualSqliteNanos.addAndGet(System.nanoTime() - startedAt);

						// Retrieval has finished, but the Task remains
						// in flight until the test releases it.
						sqliteGate.awaitAfterWork(questionId);
						return matches;
					});
				} catch (RuntimeException failure) {
					throw failure;
				} catch (Exception failure) {
					throw new IllegalStateException("Burst SQLite retrieval failed", failure);
				}
			}
		};
		QuestionExtractor measuredExtractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
				Long questionId = questionIdsByRegion.get(region);
				if (questionId == null) {
					throw new IOException("Unknown burst PDF region");
				}
				try {
					return previewProbe.measure(questionId, () -> {
						long startedAt = System.nanoTime();

						// Execute the real PDFBox render and crop before
						// introducing any controlled waiting.
						BufferedImage image = super.extractRegion(session, region);
						actualPdfNanos.addAndGet(System.nanoTime() - startedAt);

						// The owning renderer retains its PDF session
						// until this extraction returns.
						pdfGate.awaitAfterWork(questionId);
						return image;
					});
				} catch (IOException failure) {
					throw failure;
				} catch (Exception failure) {
					throw new IOException("Burst PDF extraction failed", failure);
				}
			}
		};
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		QuestionRetrievalService service = new QuestionRetrievalService(measuredRepository,
				new CurriculumSearchNodeExpansionService(curriculum), recorder);
		QuestionPreviewService measuredPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot),
				measuredExtractor);
		InMemoryQuestionOutputApplicabilityRepository exclusions = new InMemoryQuestionOutputApplicabilityRepository();
		exclusions.setExcluded(sourceQuestions.getLast(), descriptor, true);
		robot.interact(() -> {
			QuestionSearchPane previous = (QuestionSearchPane) stage.getScene().getRoot();
			previous.dispose();
			QuestionSearchPane pane = new QuestionSearchPane(subject, curriculum, service, () -> sourceQuestions,
					measuredPreviewService, exclusions, QuestionSearchNarrowing.unrestricted(), recorder);
			stage.setScene(new Scene(pane, 700, 600));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		Label applicabilityStatus = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().size() == selectionChanges + 1);
		List<QuestionSearchResult> orderedResults = sourceQuestions.stream()
				.map(question -> results.getItems().stream()
						.filter(result -> result.question().getId() == question.getId()).findFirst().orElseThrow())
				.toList();
		double burstDispatchMilliseconds;
		double finalResponseMilliseconds;
		try {

			// Start three actual PDF renders and three actual SQLite
			// retrievals. Hold them after their expensive work finishes.
			for (int index = 0; index < heldWorkers; index++) {
				QuestionSearchResult result = orderedResults.get(index);
				long questionId = result.question().getId();
				long expectedRemaining = heldWorkers - index - 1L;
				previewProbe.select(questionId);
				applicabilityProbe.select(questionId);
				robot.interact(() -> results.getSelectionModel().select(result));
				WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
						() -> pdfGate.remaining() == expectedRemaining && sqliteGate.remaining() == expectedRemaining);
			}
			assertEquals(heldWorkers, previewProbe.active());
			assertEquals(heldWorkers, applicabilityProbe.active());
			long burstStartedAt = System.nanoTime();

			// Process all remaining selections within one JavaFX turn.
			// This removes the inter-selection waits that allowed
			// Batch 02 workers to finish before supersession.
			robot.interact(() -> {
				for (int index = heldWorkers; index < orderedResults.size(); index++) {
					QuestionSearchResult result = orderedResults.get(index);
					long questionId = result.question().getId();
					previewProbe.select(questionId);
					applicabilityProbe.select(questionId);
					results.getSelectionModel().select(result);
				}
			});
			burstDispatchMilliseconds = (System.nanoTime() - burstStartedAt) / 1_000_000.0;
			try {
				WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS,
						() -> results.getSelectionModel().getSelectedItem() != null
								&& results.getSelectionModel().getSelectedItem().question().getId() == finalQuestionId
								&& output.getItems().size() == 1 && output.getItems().getFirst().excluded()
								&& preview.getImage() != null && preview.getImage().getWidth() > 700.0);
			} catch (TimeoutException failure) {
				System.out.printf(
						"Issue 28 burst timeout | " + "preview=%s | applicability=%s | "
								+ "PDF active=%d | SQLite active=%d%n",
						previewStatus.getText(), applicabilityStatus.getText(), previewProbe.active(),
						applicabilityProbe.active());
				throw failure;
			}
			finalResponseMilliseconds = (System.nanoTime() - burstStartedAt) / 1_000_000.0;

			// Final content must publish while obsolete operations
			// are still held and their PDF sessions remain owned
			// by their original background renderers.
			assertEquals(heldWorkers, pdfGate.heldCount());
			assertEquals(heldWorkers, sqliteGate.heldCount());
			assertEquals(finalQuestionId, results.getSelectionModel().getSelectedItem().question().getId());
			assertEquals(descriptor, output.getItems().getFirst().currentNode());
			assertTrue(output.getItems().getFirst().excluded());
			System.out.printf("Issue 28 burst | changes=%d | " + "dispatch=%.3f ms | final=%.3f ms%n", selectionChanges,
					burstDispatchMilliseconds, finalResponseMilliseconds);
		} finally {

			// Never strand virtual threads or their PDF sessions,
			// including when an assertion or timeout fails.
			pdfGate.release();
			sqliteGate.release();
		}
		WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS,
				() -> previewProbe.active() == 0 && applicabilityProbe.active() == 0
						&& previewProbe.completed() >= heldWorkers + 1
						&& applicabilityProbe.completed() >= heldWorkers + 1);
		WaitForAsyncUtils.waitForFxEvents();

		// The now-completed obsolete workers must not publish stale
		// images or restore obsolete Include/Exclude state.
		assertEquals(finalQuestionId, results.getSelectionModel().getSelectedItem().question().getId());
		assertTrue(preview.getImage() != null && preview.getImage().getWidth() > 700.0);
		assertEquals(1, output.getItems().size());
		assertEquals(descriptor, output.getItems().getFirst().currentNode());
		assertTrue(output.getItems().getFirst().excluded());
		assertEquals(previewProbe.started(), previewProbe.completed());
		assertEquals(applicabilityProbe.started(), applicabilityProbe.completed());
		assertTrue(previewProbe.superseded() >= heldWorkers);
		assertTrue(applicabilityProbe.superseded() >= heldWorkers);
		System.out.printf(
				"Burst PDF | started=%d completed=%d superseded=%d " + "peakConcurrent=%d actualRender=%.3f ms "
						+ "supersededElapsed=%.3f ms%n",
				previewProbe.started(), previewProbe.completed(), previewProbe.superseded(),
				previewProbe.peakConcurrent(), actualPdfNanos.get() / 1_000_000.0,
				previewProbe.supersededMilliseconds());
		System.out.printf(
				"Burst SQLite | started=%d completed=%d superseded=%d " + "peakConcurrent=%d actualRetrieval=%.3f ms "
						+ "supersededElapsed=%.3f ms%n",
				applicabilityProbe.started(), applicabilityProbe.completed(), applicabilityProbe.superseded(),
				applicabilityProbe.peakConcurrent(), actualSqliteNanos.get() / 1_000_000.0,
				applicabilityProbe.supersededMilliseconds());
		System.out.printf("Burst sampled heap high-water: %.2f MiB%n",
				Math.max(previewProbe.sampledHeapBytes(), applicabilityProbe.sampledHeapBytes()) / 1048576.0);
		System.out.println("Burst performance CSV: " + performanceCsv);
	}

	@Test
	public void catalogueRefreshDoesNotSupersedePostEditSelection(FxRobot robot) throws TimeoutException {
		AtomicReference<List<Question>> source = new AtomicReference<>(List.of(historicalQuestion));
		AtomicInteger searchReads = new AtomicInteger();
		Supplier<List<Question>> supplier = () -> {
			if ("question-search".equals(Thread.currentThread().getName())) {
				searchReads.incrementAndGet();
			}
			return source.get();
		};
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService, supplier);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<?> provider = robot.lookup("#question-search-provider-filter").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		Label searchStatus = robot.lookup("#question-search-status").queryAs(Label.class);
		Label catalogueStatus = robot.lookup("#question-search-metadata-filter-status").queryAs(Label.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));

		// Establish a completed Search and initial catalogue
		// before exercising the post-edit refresh.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 1 && provider.getItems().size() == 2
						&& catalogueStatus.getText().isEmpty() && "1 question found.".equals(searchStatus.getText()));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> results.getSelectionModel().selectFirst());
		Question updated = new Question(historicalQuestion.getId(), historicalQuestion.getBooklet(),
				historicalQuestion.getQuestionCode(), historicalQuestion.getQuestionText(),
				historicalQuestion.getMarks(), historicalQuestion.getRegions(), currentDescriptor, false);
		int searchesBeforeEdit = searchReads.get();
		robot.interact(() -> {
			source.set(List.of(updated));

			// A persisted classification change requires one
			// Search refresh and one catalogue reload.
			pane.refreshAfterEdit(historicalQuestion.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			Question selected = pane.getSelectedQuestion();
			return selected != null && selected.getId() == historicalQuestion.getId()
					&& selected.getClassification().equals(currentDescriptor) && catalogueStatus.getText().isEmpty()
					&& "1 question found.".equals(searchStatus.getText());
		});
		WaitForAsyncUtils.waitForFxEvents();

		// The catalogue reload must not silently launch
		// a second Search after the edited row is published.
		assertEquals(searchesBeforeEdit + 1, searchReads.get(),
				"An unchanged filter catalogue must not " + "trigger another Search");
		assertEquals(historicalQuestion.getId(), pane.getSelectedQuestion().getId());
		assertEquals(currentDescriptor, pane.getSelectedQuestion().getClassification());
	}

	@Test
	public void classificationControlsUseAlignedRowsAndSaveBelowDescriptor(FxRobot robot) throws TimeoutException {
		Question subtopicQuestion = new Question(8100, historicalQuestion.getBooklet(), "23a",
				"Question requiring Descriptor refinement.", 2, List.of(), currentSubtopic, false);
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService,
				() -> List.of(subtopicQuestion));
		GridPane classificationPane = robot.lookup("#question-search-selected-classification").queryAs(GridPane.class);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> unit = robot.lookup("#question-search-selected-unit").queryComboBox();
		ComboBox<CurriculumNode> topic = robot.lookup("#question-search-selected-topic").queryComboBox();
		ComboBox<CurriculumNode> subtopic = robot.lookup("#question-search-selected-subtopic").queryComboBox();
		ComboBox<CurriculumNode> descriptor = robot.lookup("#question-search-selected-descriptor").queryComboBox();
		Button save = robot.lookup("#question-search-save-classification").queryButton();
		AtomicInteger saveRequests = new AtomicInteger();
		robot.interact(() -> pane.setClassificationSaveHandler(saveRequests::incrementAndGet));
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		robot.interact(() -> results.getSelectionModel().selectFirst());
		assertTrue(classificationPane.isVisible());
		assertTrue(classificationPane.isManaged());
		List<String> labels = List.of("Unit", "Topic", "Subtopic", "Descriptor");
		List<ComboBox<CurriculumNode>> controls = List.of(unit, topic, subtopic, descriptor);
		for (int index = 0; index < labels.size(); index++) {
			String expectedLabel = labels.get(index);
			int expectedRow = index + 2;
			Label label = classificationPane.getChildren().stream().filter(node -> node instanceof Label)
					.map(node -> (Label) node).filter(node -> expectedLabel.equals(node.getText())).findFirst()
					.orElseThrow();
			ComboBox<CurriculumNode> control = controls.get(index);
			assertEquals(Integer.valueOf(0), GridPane.getColumnIndex(label));
			assertEquals(Integer.valueOf(expectedRow), GridPane.getRowIndex(label));
			assertEquals(Integer.valueOf(1), GridPane.getColumnIndex(control));
			assertEquals(Integer.valueOf(expectedRow), GridPane.getRowIndex(control));
			assertEquals(Priority.ALWAYS, GridPane.getHgrow(control));
		}

		// Save must be on a separate row, with no third column.
		assertEquals(Integer.valueOf(0), GridPane.getColumnIndex(save));
		assertEquals(Integer.valueOf(6), GridPane.getRowIndex(save));
		assertEquals(2, GridPane.getColumnSpan(save));
		assertEquals(HPos.LEFT, GridPane.getHalignment(save));

		// Exercise the Search dialog's nominal minimum width.
		robot.interact(() -> stage.setWidth(1100));
		WaitForAsyncUtils.waitForFxEvents();
		robot.interact(() -> {
			classificationPane.applyCss();
			classificationPane.layout();
			Label unitLabel = classificationPane.getChildren().stream().filter(node -> node instanceof Label)
					.map(node -> (Label) node).filter(label -> "Unit".equals(label.getText())).findFirst()
					.orElseThrow();
			assertEquals(unitLabel.getBoundsInParent().getMinX(), save.getBoundsInParent().getMinX(), 1.0);
			assertTrue(descriptor.getWidth() > 150, "Descriptor must remain usable at minimum width");
			assertTrue(save.getBoundsInParent().getMaxX() <= classificationPane.getWidth(),
					"Save must remain within the classification pane");
		});

		// Moving the existing button must not alter its behaviour.
		assertTrue(save.isDisable());
		robot.interact(() -> descriptor.setValue(currentDescriptor));
		assertFalse(save.isDisable());
		robot.interact(save::fire);
		assertEquals(1, saveRequests.get());
	}

	@Test
	public void classificationRefreshPreservesPreviewAndScrollPosition(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("search-refresh-preview-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(4601, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(4602, historicalQuestion.getExam(), "Refresh booklet", document);
		List<Question> originalQuestions = new ArrayList<>();
		final int targetPosition = 40;
		for (int index = 0; index < 48; index++) {
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.10 + index * 0.001, 0.10, 0.50, 0.20);
			originalQuestions.add(
					new Question(4700 + index, booklet, String.format("%02d", index + 1), "Question " + (index + 1), 2,
							List.of(region), index == targetPosition ? currentSubtopic : currentDescriptor, false));
		}
		Question originalTarget = originalQuestions.get(targetPosition);
		Question updatedTarget = new Question(originalTarget.getId(), originalTarget.getBooklet(),
				originalTarget.getQuestionCode(), originalTarget.getQuestionText(), originalTarget.getMarks(),
				originalTarget.getRegions(), currentDescriptor, false);
		AtomicReference<List<Question>> source = new AtomicReference<>(List.copyOf(originalQuestions));
		AtomicInteger renderCount = new AtomicInteger();
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
				renderCount.incrementAndGet();
				return new BufferedImage(45, 34, BufferedImage.TYPE_INT_RGB);
			}
		};
		QuestionPreviewService measuredPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane previous = (QuestionSearchPane) stage.getScene().getRoot();
			previous.dispose();
			pane[0] = new QuestionSearchPane(chemistry, curriculumRepository, retrievalService, source::get,
					measuredPreviewService, outputApplicabilityRepository);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> descriptor = robot.lookup("#question-search-selected-descriptor").queryComboBox();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == originalQuestions.size());
		QuestionSearchResult targetResult = results.getItems().stream()
				.filter(result -> result.question().getId() == originalTarget.getId()).findFirst().orElseThrow();
		QuestionSearchResult unaffectedResult = results.getItems().getFirst();
		robot.interact(() -> results.getSelectionModel().select(targetResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> preview.getImage() != null && renderCount.get() == 1);
		Image originalImage = preview.getImage();
		int selectedIndex = results.getSelectionModel().getSelectedIndex();
		AtomicReference<ScrollBar> verticalScroll = new AtomicReference<>();
		robot.interact(() -> {
			results.applyCss();
			results.layout();
			verticalScroll.set(results.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
					.map(ScrollBar.class::cast).filter(bar -> bar.getOrientation() == Orientation.VERTICAL).findFirst()
					.orElseThrow());
			results.scrollTo(selectedIndex);
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> verticalScroll.get().getValue() > verticalScroll.get().getMin());
		AtomicReference<Double> originalScrollPosition = new AtomicReference<>();
		robot.interact(() -> originalScrollPosition.set(verticalScroll.get().getValue()));
		robot.interact(() -> {
			descriptor.setValue(currentDescriptor);
			assertTrue(pane[0].isClassificationDirty());
			List<Question> replacements = new ArrayList<>(originalQuestions);
			replacements.set(targetPosition, updatedTarget);
			source.set(List.copyOf(replacements));
			pane[0].classificationSaved(originalTarget.getId());
			pane[0].refreshAfterEdit(originalTarget.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> pane[0].getSelectedQuestion() != null
				&& pane[0].getSelectedQuestion().getClassification().equals(currentDescriptor));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(originalTarget.getId(), pane[0].getSelectedQuestion().getId());
		assertEquals(selectedIndex, results.getSelectionModel().getSelectedIndex());
		assertSame(originalImage, preview.getImage(), "Classification-only Save must retain the existing preview");
		assertEquals(1, renderCount.get(), "Classification-only Save must not render the PDF again");
		assertEquals(originalScrollPosition.get(), verticalScroll.get().getValue(), 0.001,
				"Classification Save must preserve list scroll position");
		assertSame(unaffectedResult, results.getItems().getFirst());
		assertEquals(currentDescriptor, descriptor.getValue());
	}

	@Test
	public void classificationRefreshRemovesQuestionThatLeavesSearchScope(FxRobot robot) throws TimeoutException {
		Question removableQuestion = new Question(9801, historicalQuestion.getBooklet(), "21b",
				"Question leaving the current search.", 2, List.of(), currentSubtopic, false);
		AtomicReference<List<QuestionApplicabilityMatch>> matches = new AtomicReference<>(
				List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
						new QuestionApplicabilityMatch(removableQuestion, currentDescriptor)));
		QuestionRetrievalRepository repository = nodes -> matches.get().stream()
				.filter(match -> nodes.contains(match.getCurrentNode())).toList();
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, service,
				() -> List.of(historicalQuestion, removableQuestion));
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		Label status = robot.lookup("#question-search-status").queryAs(Label.class);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 2 && scope.getValue() == QuestionSearchScope.CURRENT_SYLLABUS);
		QuestionSearchResult selectedResult = results.getItems().stream()
				.filter(result -> result.question().getId() == removableQuestion.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		robot.interact(() -> {

			// Simulate a persisted classification change that
			// removes this Question from the active retrieval scope.
			matches.set(List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor)));
			pane.refreshAfterEdit(removableQuestion.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 1
						&& results.getItems().getFirst().question().getId() == historicalQuestion.getId()
						&& "1 question found.".equals(status.getText()));
		assertEquals(QuestionSearchScope.CURRENT_SYLLABUS, scope.getValue());
		assertFalse(
				results.getItems().stream().anyMatch(result -> result.question().getId() == removableQuestion.getId()));
		assertEquals(historicalQuestion.getId(), results.getItems().getFirst().question().getId());
	}

	@Test
	public void classificationSaveRefreshesOnlyEditedSearchRow(FxRobot robot) throws TimeoutException {
		Question subtopicQuestion = new Question(8000, historicalQuestion.getBooklet(), "22a",
				"Question needing a Descriptor.", 2, List.of(), currentSubtopic, false);
		Question classifiedQuestion = new Question(subtopicQuestion.getId(), subtopicQuestion.getBooklet(),
				subtopicQuestion.getQuestionCode(), subtopicQuestion.getQuestionText(), subtopicQuestion.getMarks(),
				subtopicQuestion.getRegions(), currentDescriptor, false);
		AtomicReference<List<Question>> questions = new AtomicReference<>(
				List.of(historicalQuestion, subtopicQuestion));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService, questions::get);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> descriptor = robot.lookup("#question-search-selected-descriptor").queryComboBox();
		TextArea details = robot.lookup("#question-search-details").queryAs(TextArea.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		QuestionSearchResult original = results.getItems().stream()
				.filter(result -> result.question().getId() == subtopicQuestion.getId()).findFirst().orElseThrow();
		QuestionSearchResult unaffected = results.getItems().stream()
				.filter(result -> result.question().getId() == historicalQuestion.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(original));
		int selectedIndex = results.getSelectionModel().getSelectedIndex();

		// Detect a wholesale ListView replacement, rather than
		// merely checking that the final Question is correct.
		AtomicInteger wholesaleReplacements = new AtomicInteger();
		results.getItems().addListener((ListChangeListener<QuestionSearchResult>) change -> {
			while (change.next()) {
				if (change.getRemovedSize() > 1 || change.getAddedSize() > 1) {
					wholesaleReplacements.incrementAndGet();
				}
			}
		});
		robot.interact(() -> {
			descriptor.setValue(currentDescriptor);
			assertTrue(pane.isClassificationDirty());

			// Simulate successful persistence at the same boundary
			// used by the containing Search Dialog.
			questions.set(List.of(historicalQuestion, classifiedQuestion));
			pane.classificationSaved(subtopicQuestion.getId());
			pane.refreshAfterEdit(subtopicQuestion.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> pane.getSelectedQuestion() != null
				&& pane.getSelectedQuestion().getClassification().equals(currentDescriptor));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(0, wholesaleReplacements.get());
		assertEquals(2, results.getItems().size());
		assertEquals(selectedIndex, results.getSelectionModel().getSelectedIndex());
		assertEquals(subtopicQuestion.getId(), pane.getSelectedQuestion().getId());
		assertEquals(currentDescriptor, pane.getSelectedQuestion().getClassification());
		assertEquals(currentDescriptor, descriptor.getValue());
		assertTrue(details.getText().contains("Question needing a Descriptor."));
		QuestionSearchResult remaining = results.getItems().stream()
				.filter(result -> result.question().getId() == historicalQuestion.getId()).findFirst().orElseThrow();
		assertSame(unaffected, remaining);
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scope.getValue());
	}

	@Test
	public void clickingSelectedSubtopicRestoresSubtopicSearchScope(FxRobot robot) throws TimeoutException {
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();

		// Working Subject navigation is established automatically before the test
		// narrows Search through the current curriculum hierarchy.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topicBox.getItems().contains(currentTopic));
		robot.interact(() -> topicBox.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(currentSubtopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> descriptorBox.getItems().contains(currentDescriptor));
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		assertEquals(currentDescriptor, descriptorBox.getValue());

		// Pointer activation is intentional here: this regression specifically verifies
		// the UI behaviour of clicking the already-selected hierarchy control.
		robot.clickOn("#question-search-classification");
		assertEquals(currentSubtopic, classificationBox.getValue());
		assertEquals(null, descriptorBox.getValue());
	}

	@Test
	public void descriptorAssignmentQueueAdvancesWithinBookletAndCompletes(FxRobot robot) throws TimeoutException {
		ExamBooklet targetBooklet = historicalQuestion.getBooklet();
		ExamBooklet siblingBooklet = new ExamBooklet(9901, historicalQuestion.getExam(), "Paper 2",
				new SourceDocument(9902, "Chemistry/2020/paper2.pdf"));
		Exam otherExam = new Exam(9903, chemistry, historicalQuestion.getExam().getProvider(), 2021, "Other Exam");
		ExamBooklet otherExamBooklet = new ExamBooklet(9904, otherExam, "Paper 1",
				new SourceDocument(9905, "Chemistry/2021/paper1.pdf"));
		Question first = new Question(9911, targetBooklet, "Q1", "", 2, List.of(), currentSubtopic, false);
		Question second = new Question(9912, targetBooklet, "Q2", "", 2, List.of(), currentSubtopic, false);
		Question third = new Question(9913, targetBooklet, "Q3", "", 2, List.of(), currentSubtopic, false);
		Question alreadyClassified = new Question(9914, targetBooklet, "Q4", "", 2, List.of(), currentDescriptor,
				false);
		Question siblingQuestion = new Question(9915, siblingBooklet, "Q1", "", 2, List.of(), currentSubtopic, false);
		Question otherExamQuestion = new Question(9916, otherExamBooklet, "Q1", "", 2, List.of(), currentSubtopic,
				false);
		AtomicReference<List<Question>> source = new AtomicReference<>(
				List.of(first, second, third, alreadyClassified, siblingQuestion, otherExamQuestion));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService, source::get,
				QuestionSearchNarrowing.forMissingDescriptors(targetBooklet));
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<CurriculumNode> descriptor = robot.lookup("#question-search-selected-descriptor").queryComboBox();
		Label status = robot.lookup("#question-search-status").queryAs(Label.class);

		// The initial outstanding queue must include only Subtopic-classified
		// Questions in the selected booklet.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 3 && results.getSelectionModel().getSelectedItem() != null);
		assertEquals(List.of(9911L, 9912L, 9913L),
				results.getItems().stream().map(item -> item.question().getId()).toList());
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scope.getValue());
		assertTrue(scope.isDisable());
		assertEquals(first.getId(), pane.getSelectedQuestion().getId());
		assertTrue(status.getText().contains("3 Questions still need"));

		// The scope is immutable even if a programmatic change is attempted.
		robot.interact(() -> scope.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scope.getValue());

		// Select the middle Question, then cancel the pending refinement.
		robot.interact(() -> results.getSelectionModel().select(1));
		robot.interact(() -> descriptor.setValue(currentDescriptor));
		assertTrue(pane.isClassificationDirty());
		robot.interact(pane::classificationDiscarded);
		assertFalse(pane.isClassificationDirty());
		assertEquals(3, results.getItems().size());
		assertEquals(second.getId(), pane.getSelectedQuestion().getId());

		// Simulate successful classification persistence for Q2.
		Question classifiedSecond = new Question(second.getId(), targetBooklet, second.getQuestionCode(),
				second.getQuestionText(), second.getMarks(), second.getRegions(), currentDescriptor, false);
		robot.interact(() -> {
			descriptor.setValue(currentDescriptor);
			assertTrue(pane.isClassificationDirty());
			source.set(List.of(first, classifiedSecond, third, alreadyClassified, siblingQuestion, otherExamQuestion));
			pane.classificationSaved(second.getId());
			pane.refreshAfterEdit(second.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2
				&& pane.getSelectedQuestion() != null && pane.getSelectedQuestion().getId() == third.getId());

		// The removed middle row is replaced by the next outstanding row.
		assertEquals(List.of(first.getId(), third.getId()),
				results.getItems().stream().map(item -> item.question().getId()).toList());
		assertEquals(1, results.getSelectionModel().getSelectedIndex());
		assertTrue(status.getText().contains("2 Questions still need"));

		// Classify the last row. The queue must wrap to the first
		// remaining Question rather than leaving nothing selected.
		Question classifiedThird = new Question(third.getId(), targetBooklet, third.getQuestionCode(),
				third.getQuestionText(), third.getMarks(), third.getRegions(), currentDescriptor, false);
		robot.interact(() -> {
			descriptor.setValue(currentDescriptor);
			assertTrue(pane.isClassificationDirty());
			source.set(List.of(first, classifiedSecond, classifiedThird, alreadyClassified, siblingQuestion,
					otherExamQuestion));
			pane.classificationSaved(third.getId());
			pane.refreshAfterEdit(third.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& pane.getSelectedQuestion() != null && pane.getSelectedQuestion().getId() == first.getId());
		assertTrue(status.getText().contains("1 Question still needs"));

		// Resolve the final outstanding Question.
		Question classifiedFirst = new Question(first.getId(), targetBooklet, first.getQuestionCode(),
				first.getQuestionText(), first.getMarks(), first.getRegions(), currentDescriptor, false);
		robot.interact(() -> {
			descriptor.setValue(currentDescriptor);
			assertTrue(pane.isClassificationDirty());
			source.set(List.of(classifiedFirst, classifiedSecond, classifiedThird, alreadyClassified, siblingQuestion,
					otherExamQuestion));
			pane.classificationSaved(first.getId());
			pane.refreshAfterEdit(first.getId());
		});
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().isEmpty() && status.getText().contains("Descriptor assignment complete"));
		assertTrue(results.getItems().isEmpty());
		assertNull(pane.getSelectedQuestion());
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scope.getValue());

		// Completion applies only to the selected booklet. The two
		// unclassified Questions outside it still exist in persistence.
		assertEquals(2, source.get().stream()
				.filter(question -> question.getClassification().getLevel() != CurriculumLevel.DESCRIPTOR).count());
	}

	@Test
	public void descriptorSaveKeepsTallPreviewAndApplicabilityUsable(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("search-tall-save-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(9810, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(9811, historicalQuestion.getExam(), "Tall preview booklet", document);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.70);
		Question original = new Question(9812, booklet, "T1", "", 2, List.of(region), currentSubtopic, false);
		Question updated = new Question(9812, booklet, "T1", "", 2, List.of(region), currentDescriptor, false);
		AtomicReference<List<Question>> stored = new AtomicReference<>(List.of(original));
		QuestionRetrievalRepository repository = nodes -> List
				.of(new QuestionApplicabilityMatch(stored.get().getFirst(), currentDescriptor),
						new QuestionApplicabilityMatch(stored.get().getFirst(), noMatchDescriptor))
				.stream().filter(match -> nodes.contains(match.getCurrentNode())).toList();
		QuestionRetrievalService retrieval = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		AtomicInteger renderCount = new AtomicInteger();
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion requested) throws IOException {
				renderCount.incrementAndGet();
				return new BufferedImage(500, 2500, BufferedImage.TYPE_INT_RGB);
			}
		};
		QuestionPreviewService service = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			((QuestionSearchPane) stage.getScene().getRoot()).dispose();
			pane[0] = new QuestionSearchPane(chemistry, curriculumRepository, retrieval, stored::get, service,
					outputApplicabilityRepository);
			pane[0].setClassificationSaveHandler(() -> {
				assertEquals(currentDescriptor, pane[0].getPendingClassification());

				// Simulate the authoritative persistence change.
				stored.set(List.of(updated));
				pane[0].classificationSaved(original.getId());
				pane[0].refreshAfterEdit(original.getId());
			});
			stage.setScene(new Scene(pane[0], 1100, 760));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		ComboBox<CurriculumNode> descriptor = robot.lookup("#question-search-selected-descriptor").queryComboBox();
		Button save = robot.lookup("#question-search-save-classification").queryButton();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		robot.interact(() -> results.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> preview.getImage() != null && output.getItems().size() == 2 && renderCount.get() == 1);
		Image originalImage = preview.getImage();

		// Save while the second current-curriculum placement is selected.
		robot.interact(() -> output.getSelectionModel().select(1));
		assertEquals(noMatchDescriptor, output.getSelectionModel().getSelectedItem().currentNode());
		AtomicInteger applicabilityReloads = new AtomicInteger();
		output.getItems().addListener((ListChangeListener<QuestionOutputApplicabilityRow>) change -> {
			while (change.next()) {
				if (change.wasAdded() && change.getAddedSize() == 2) {
					applicabilityReloads.incrementAndGet();
				}
			}
		});
		robot.interact(() -> descriptor.setValue(currentDescriptor));
		assertFalse(save.isDisable());
		robot.interact(save::fire);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> pane[0].getSelectedQuestion() != null
						&& pane[0].getSelectedQuestion().getClassification().equals(currentDescriptor)
						&& applicabilityReloads.get() > 0 && output.getItems().size() == 2
						&& output.getSelectionModel().getSelectedItem() != null);
		assertSame(originalImage, preview.getImage());
		assertEquals(1, renderCount.get());
		assertEquals(noMatchDescriptor, output.getSelectionModel().getSelectedItem().currentNode());

		// Exercise the same tall-preview layout after classification Save.
		assertOversizedPreviewLayout(robot);
		assertSame(originalImage, preview.getImage());
		assertEquals(1, renderCount.get());
	}

	@Test
	public void descriptorSearchDisplaysResultAndProvenance(FxRobot robot) throws TimeoutException {
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);

		// Working Subject navigation supplies the current hierarchy without a separate
		// Subject-selection action inside Search.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topicBox.getItems().contains(currentTopic));
		robot.interact(() -> topicBox.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(currentSubtopic));
		robot.interact(() -> classificationBox.setValue(currentSubtopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> descriptorBox.getItems().contains(currentDescriptor));
		robot.interact(() -> descriptorBox.setValue(currentDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(1, resultsList.getItems().size());
		QuestionSearchResult result = resultsList.getItems().getFirst();
		assertEquals(historicalQuestion.getId(), result.question().getId());

		// The Search wrapper preserves the Question's original stored classification
		// and its derived current applicability as separate information.
		assertEquals(historicalDescriptor, result.question().getClassification());
		assertEquals(List.of(currentDescriptor), result.currentApplicability());
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
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
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);

		// Working Subject navigation is loaded automatically before a lower hierarchy
		// request is deliberately held in flight.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		curriculumRepository.delayChildrenFor(currentUnit);
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, curriculumRepository::hasDelayStarted);
		robot.interact(() -> {

			// Disposal is intentionally repeated to retain the idempotency coverage of
			// the original regression.
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

			// This fixture supplies one Question only when the current Descriptor is
			// applicable to the requested Search nodes.
			return List.of(new QuestionApplicabilityMatch(question, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		DelayedQuestionExtractor extractor = new DelayedQuestionExtractor(question.getRegions().getFirst());
		QuestionPreviewService replacementPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, service, replacementPreviewService);
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);

		// The replacement Pane begins its Working Subject search automatically.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayStarted);
		robot.interact(() -> {

			// Repeated disposal must remain harmless while preview work is in flight.
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
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);

		// Working Subject navigation is established automatically before narrowing to
		// the Descriptor whose Search request will be deliberately delayed.
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
	public void disposingWithPendingPreviewDoesNotStartRendering(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("question-search-pending-dispose-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(800, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(801, historicalQuestion.getExam(), "Pending preview booklet", document);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20);
		Question question = new Question(802, booklet, "D1", "", 2, List.of(region), currentDescriptor, false);
		AtomicInteger renderCount = new AtomicInteger();
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion requestedRegion) throws IOException {
				renderCount.incrementAndGet();
				return new BufferedImage(22, 22, BufferedImage.TYPE_INT_RGB);
			}
		};
		QuestionPreviewService measuredPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		QuestionRetrievalRepository repository = currentNodes -> currentNodes.contains(currentDescriptor)
				? List.of(new QuestionApplicabilityMatch(question, currentDescriptor))
				: List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionSearchPane[] replacement = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane previous = (QuestionSearchPane) stage.getScene().getRoot();
			previous.dispose();
			replacement[0] = new QuestionSearchPane(chemistry, curriculumRepository, service, () -> List.of(question),
					measuredPreviewService, outputApplicabilityRepository);
			stage.setScene(new Scene(replacement[0], 700, 600));
		});
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		CountDownLatch verificationDelay = new CountDownLatch(1);
		robot.interact(() -> {

			// Selection schedules a preview, then immediate disposal
			// must cancel that pending request.
			results.getSelectionModel().selectFirst();
			replacement[0].dispose();

			// Wait using the JavaFX clock so the cancelled delay
			// would have expired if it were still active.
			PauseTransition verification = new PauseTransition(Duration.millis(200));
			verification.setOnFinished(_ -> verificationDelay.countDown());
			verification.play();
		});
		assertTrue(verificationDelay.await(5, TimeUnit.SECONDS));
		assertEquals(0, renderCount.get());
		assertEquals(null, preview.getImage());
		assertTrue(results.getItems().isEmpty());
	}

	@Test
	public void explainsSearchScopeAndOutputApplicabilityWithoutChangingClassification(FxRobot robot) {
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		Button includeButton = robot.lookup("#question-search-output-include").queryButton();
		Button excludeButton = robot.lookup("#question-search-output-exclude").queryButton();
		assertNotNull(scopeBox.getTooltip());
		assertTrue(scopeBox.getTooltip().getText().contains("Working Subject"));
		assertNotNull(includeButton.getTooltip());
		assertTrue(includeButton.getTooltip().getText().contains("does not alter Question classification"));
		assertNotNull(excludeButton.getTooltip());
		assertTrue(excludeButton.getTooltip().getText().contains("does not alter Question classification"));
	}

	@Test
	public void hierarchyFailureClearsExistingSearchState(FxRobot robot) throws TimeoutException {
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		TextArea detailsArea = robot.lookup("#question-search-details").queryAs(TextArea.class);
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);

		// Initial Working Subject navigation and Search should both complete before
		// the lower hierarchy failure is introduced.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> resultsList.getItems().size() == 1 && unitBox.getItems().contains(currentUnit));
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		assertFalse(detailsArea.getText().isBlank());
		assertEquals("No stored question image.", previewStatus.getText());
		curriculumRepository.failChildrenFor(currentUnit);
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> statusLabel.getText().startsWith("Curriculum navigation failed:"));

		// A failed hierarchy transition must not leave results or preview information
		// from the previous broader Search visible.
		assertTrue(resultsList.getItems().isEmpty());
		assertTrue(detailsArea.getText().isBlank());
		assertTrue(previewStatus.getText().isBlank());
	}

	@Test
	public void launchNarrowingAppliesToCurrentAndAllQuestionScopes(FxRobot robot) throws TimeoutException {
		ExamProvider provider = historicalQuestion.getExam().getProvider();
		Exam secondExam = new Exam(60, chemistry, provider, 2021, "Mock Exam");
		SourceDocument secondDocument = new SourceDocument(61, "Chemistry/2021/paper2.pdf");
		ExamBooklet secondBooklet = new ExamBooklet(62, secondExam, "Paper 2", secondDocument);
		Question secondQuestion = new Question(63, secondBooklet, "5", "Second Question", 2, List.of(),
				historicalDescriptor, false);
		QuestionRetrievalRepository twoQuestionRepository = _ -> List.of(
				new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
				new QuestionApplicabilityMatch(secondQuestion, currentDescriptor));
		QuestionRetrievalService twoQuestionService = new QuestionRetrievalService(twoQuestionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, twoQuestionService,
				() -> List.of(historicalQuestion, secondQuestion), QuestionSearchNarrowing.forBooklet(secondBooklet));
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		Label narrowing = robot.lookup("#question-search-narrowing").queryAs(Label.class);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();

		// Dashboard launch narrowing is visible and applies to the initial
		// current-syllabus retrieval.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		assertTrue(narrowing.getText().contains("Paper 2"));
		assertEquals(secondQuestion.getId(), results.getItems().getFirst().question().getId());

		// Changing the ordinary Search scope must not escape the immutable launch
		// narrowing.
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
		assertEquals(secondQuestion.getId(), results.getItems().getFirst().question().getId());
	}

	@Test
	public void metadataControlsCascadeProviderExamAndBooklet(FxRobot robot) throws TimeoutException {
		ExamProvider neap = new ExamProvider(101, "NEAP");
		Exam neapExam = new Exam(102, chemistry, neap, 2022, "Practice Exam");
		ExamBooklet neapBooklet = new ExamBooklet(103, neapExam, "Practice Questions",
				new SourceDocument(104, "neap.pdf"));
		Question neapQuestion = new Question(105, neapBooklet, "Q5", "", 2, List.of(), currentDescriptor, false, null,
				null, QuestionResponseType.WRITTEN_RESPONSE);
		Exam originalExam = historicalQuestion.getExam();
		ExamBooklet secondBooklet = new ExamBooklet(106, originalExam, "Paper 2",
				new SourceDocument(107, "paper2.pdf"));
		Question secondQuestion = new Question(108, secondBooklet, "Q6", "", 1, List.of(), historicalDescriptor, false,
				null, null, QuestionResponseType.MULTIPLE_CHOICE);
		List<Question> sourceQuestions = List.of(historicalQuestion, neapQuestion, secondQuestion);
		QuestionRetrievalRepository repository = nodes -> {
			if (!nodes.contains(currentDescriptor)) {
				return List.of();
			}
			return sourceQuestions.stream().map(question -> new QuestionApplicabilityMatch(question, currentDescriptor))
					.toList();
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, service, () -> sourceQuestions);
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		TitledPane moreFilters = robot.lookup("#question-search-more-filters").queryAs(TitledPane.class);
		robot.interact(() -> moreFilters.setExpanded(true));
		ComboBox<?> provider = robot.lookup("#question-search-provider-filter").queryComboBox();
		ComboBox<?> exam = robot.lookup("#question-search-exam-filter").queryComboBox();
		ComboBox<?> booklet = robot.lookup("#question-search-booklet-filter").queryComboBox();
		ComboBox<?> fromYear = robot.lookup("#question-search-year-from-filter").queryComboBox();
		ComboBox<?> toYear = robot.lookup("#question-search-year-to-filter").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		Label status = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> provider.getItems().size() == 3 && results.getItems().size() == 3);
		selectMetadataFilter(robot, provider, "QCAA");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		assertFalse(exam.getItems().stream().anyMatch(item -> item.toString().contains("NEAP")));
		selectMetadataFilter(robot, exam, "QCAA 2020 — External Assessment");
		selectMetadataFilter(robot, booklet, "QCAA 2020 — External Assessment — Paper 2");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == secondQuestion.getId());

		// Switching provider invalidates the selected Exam
		// and Booklet rather than leaving contradictory filters.
		selectMetadataFilter(robot, provider, "NEAP");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == neapQuestion.getId());
		assertEquals("All exams", exam.getValue().toString());
		assertEquals("All booklets", booklet.getValue().toString());
		selectMetadataFilter(robot, fromYear, "2020");
		selectMetadataFilter(robot, toYear, "2020");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().isEmpty() && "No questions found.".equals(status.getText()));

		// Moving From beyond To resets the incompatible To
		// boundary and restores the 2022 Question.
		selectMetadataFilter(robot, fromYear, "2022");
		assertEquals("Any year", toYear.getValue().toString());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == neapQuestion.getId());
	}

	@Test
	public void metadataControlsFilterOriginalSyllabusAndResponseType(FxRobot robot) throws TimeoutException {
		Exam exam = historicalQuestion.getExam();
		ExamBooklet currentBooklet = new ExamBooklet(120, exam, "Current Booklet",
				new SourceDocument(121, "current.pdf"));
		Question written = new Question(122, currentBooklet, "Q7", "", 2, List.of(), currentDescriptor, false, null,
				null, QuestionResponseType.WRITTEN_RESPONSE);
		replaceSearchPane(robot, curriculumRepository, retrievalService, () -> List.of(historicalQuestion, written));
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		TitledPane moreFilters = robot.lookup("#question-search-more-filters").queryAs(TitledPane.class);
		robot.interact(() -> moreFilters.setExpanded(true));
		ComboBox<?> response = robot.lookup("#question-search-response-type-filter").queryComboBox();
		ComboBox<?> syllabus = robot.lookup("#question-search-original-syllabus-filter").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> syllabus.getItems().size() == 3 && results.getItems().size() == 2);
		assertFalse(response.getItems().stream().anyMatch(item -> "Unknown".equals(item.toString())));
		selectMetadataFilter(robot, response, "Written Response");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == written.getId());
		selectMetadataFilter(robot, syllabus, "2019");
		Label status = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().isEmpty() && "No questions found.".equals(status.getText()));

		// All response types must include UNKNOWN Questions.
		selectMetadataFilter(robot, response, "All response types");
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == historicalQuestion.getId());
		Button clear = robot.lookup("#question-search-clear-metadata-filters").queryButton();
		robot.interact(clear::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		assertEquals("All original syllabuses", syllabus.getValue().toString());
		assertEquals("All response types", response.getValue().toString());
	}

	@Test
	public void metadataFiltersApplyInBothSearchScopes(FxRobot robot) throws TimeoutException {
		ExamProvider neap = new ExamProvider(70, "NEAP");
		Exam secondExam = new Exam(71, chemistry, neap, 2022, "Practice Exam");
		ExamBooklet secondBooklet = new ExamBooklet(72, secondExam, "Question Booklet",
				new SourceDocument(73, "neap-2022.pdf"));
		Question secondQuestion = new Question(74, secondBooklet, "Q5", "Second Question", 2, List.of(),
				historicalDescriptor, false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
		QuestionRetrievalRepository repository = nodes -> {
			if (!nodes.contains(currentDescriptor)) {
				return List.of();
			}
			return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
					new QuestionApplicabilityMatch(secondQuestion, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, service,
				() -> List.of(historicalQuestion, secondQuestion));
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();

		// Both Questions are applicable to the current syllabus.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		QuestionSearchMetadataFilter qcaaOnly = new QuestionSearchMetadataFilter(chemistry,
				historicalQuestion.getExam().getProvider(), 2020, 2020, historicalQuestion.getExam(),
				historicalQuestion.getBooklet(), null, historicalDescriptor.getSyllabusVersion());
		robot.interact(() -> pane.setMetadataFilter(qcaaOnly));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == historicalQuestion.getId());

		// The same metadata constraints apply to All Questions.
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);

		// A new immutable filter selects the other provider,
		// year, response type and source Exam.
		QuestionSearchMetadataFilter neapWritten = new QuestionSearchMetadataFilter(chemistry, neap, 2022, 2022,
				secondExam, secondBooklet, QuestionResponseType.WRITTEN_RESPONSE,
				historicalDescriptor.getSyllabusVersion());
		robot.interact(() -> pane.setMetadataFilter(neapWritten));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == secondQuestion.getId());

		// Clearing every metadata constraint restores all
		// Questions within the original Working Subject.
		robot.interact(() -> pane.setMetadataFilter(QuestionSearchMetadataFilter.unrestricted(chemistry)));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
	}

	@Test
	public void metadataFiltersNeverBroadenDashboardNarrowing(FxRobot robot) throws TimeoutException {
		ExamProvider neap = new ExamProvider(80, "NEAP");
		Exam otherExam = new Exam(81, chemistry, neap, 2023, "Practice");
		ExamBooklet otherBooklet = new ExamBooklet(82, otherExam, "Paper 1",
				new SourceDocument(83, "practice-2023.pdf"));
		Question otherQuestion = new Question(84, otherBooklet, "Q1", "", 2, List.of(), historicalDescriptor, false);
		QuestionRetrievalRepository repository = nodes -> {
			if (!nodes.contains(currentDescriptor)) {
				return List.of();
			}
			return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
					new QuestionApplicabilityMatch(otherQuestion, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, service,
				() -> List.of(historicalQuestion, otherQuestion),
				QuestionSearchNarrowing.forBooklet(historicalQuestion.getBooklet()));
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		Label status = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == historicalQuestion.getId());

		// The Search-local filter asks for another Exam,
		// but Dashboard narrowing must still take precedence.
		QuestionSearchMetadataFilter otherExamFilter = new QuestionSearchMetadataFilter(chemistry, neap, null, null,
				otherExam, otherBooklet, null, null);
		robot.interact(() -> pane.setMetadataFilter(otherExamFilter));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().isEmpty() && "No questions found.".equals(status.getText()));

		// Switching to All Questions must not escape the
		// Dashboard Booklet constraint.
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().isEmpty() && "No questions found.".equals(status.getText()));

		// Removing the Search-local filter restores only the
		// originally permitted Dashboard Booklet.
		robot.interact(() -> pane.setMetadataFilter(QuestionSearchMetadataFilter.unrestricted(chemistry)));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == historicalQuestion.getId());
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

			// Both Questions are curriculum-applicable; only their source-PDF state
			// differs for the preview failure assertions below.
			return List.of(new QuestionApplicabilityMatch(missingQuestion, currentDescriptor),
					new QuestionApplicabilityMatch(corruptQuestion, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService replacementPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot),
				new QuestionExtractor());
		replaceSearchPane(robot, curriculumRepository, service, replacementPreviewService);
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);

		// Working Subject Search begins automatically and supplies both applicable
		// Questions without requiring a Search-local Subject selection.
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
	public void multipartPreviewRetainsContentAndStableSizing(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("search-multipart-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(9820, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(9821, historicalQuestion.getExam(), "Multipart booklet", document);
		QuestionRegion first = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.60, 0.20);
		QuestionRegion second = new QuestionRegion(booklet, 1, 0.10, 0.40, 0.60, 0.20);
		BufferedImage captured = new BufferedImage(280, 120, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(captured, "png", png));
		Question mixed = new Question(9822, booklet, "M1", "", 2, List.of(first, second), historicalDescriptor, false,
				null, null, QuestionResponseType.UNKNOWN, List.of(new PdfQuestionContentPart(first),
						new ImageQuestionContentPart(png.toByteArray()), new PdfQuestionContentPart(second)));
		AtomicInteger renders = new AtomicInteger();
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
				renders.incrementAndGet();
				return new BufferedImage(320, region.equals(first) ? 160 : 80, BufferedImage.TYPE_INT_RGB);
			}
		};
		QuestionPreviewService service = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		openOversizedPreviewFixture(robot, mixed, service);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		ScrollPane scroll = robot.lookup("#question-search-preview-scroll").queryAs(ScrollPane.class);
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> preview.getImage() != null
				&& output.getItems().size() == 2 && scroll.getViewportBounds().getWidth() > 0);
		Image assembled = preview.getImage();

		// Two PDF fragments and one stored image, in their supplied order.
		assertEquals(320.0, assembled.getWidth());
		assertEquals(360.0, assembled.getHeight());
		assertEquals(2, renders.get());
		double[][] sizes = { { 1100, 760 }, { 900, 650 }, { 1140, 790 } };
		for (double[] size : sizes) {
			robot.interact(() -> {
				stage.setWidth(size[0]);
				stage.setHeight(size[1]);
			});
			WaitForAsyncUtils.waitForFxEvents();
			robot.interact(() -> {
				scroll.applyCss();
				scroll.layout();
				assertEquals(scroll.getViewportBounds().getWidth(), preview.getFitWidth(), 1.0);
				assertTrue(output.getHeight() >= 90.0);
				assertTrue(preview.isPreserveRatio());
			});
		}

		// Layout and window resizing must not reconstruct source content.
		assertSame(assembled, preview.getImage());
		assertEquals(2, renders.get());
	}

	@Test
	public void multipleApplicabilityPlacementsPreserveSelectionOnRefresh(FxRobot robot) throws TimeoutException {
		Question secondQuestion = new Question(8301, historicalQuestion.getBooklet(), "24",
				"Second placement test Question.", 2, List.of(), currentSubtopic, false);
		AtomicReference<List<QuestionApplicabilityMatch>> matches = new AtomicReference<>(
				List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
						new QuestionApplicabilityMatch(historicalQuestion, noMatchDescriptor),
						new QuestionApplicabilityMatch(secondQuestion, currentDescriptor)));
		QuestionRetrievalRepository repository = nodes -> matches.get().stream()
				.filter(match -> nodes.contains(match.getCurrentNode())).toList();
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, service,
				() -> List.of(historicalQuestion, secondQuestion));
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		QuestionSearchResult firstResult = results.getItems().stream()
				.filter(result -> result.question().getId() == historicalQuestion.getId()).findFirst().orElseThrow();
		QuestionSearchResult secondResult = results.getItems().stream()
				.filter(result -> result.question().getId() == secondQuestion.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(firstResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> output.getItems().size() == 2 && output.getSelectionModel().getSelectedItem() != null);
		assertEquals(currentDescriptor, output.getSelectionModel().getSelectedItem().currentNode());

		// Select the second placement rather than accepting the default.
		robot.interact(() -> output.getSelectionModel().select(1));
		assertEquals(noMatchDescriptor, output.getSelectionModel().getSelectedItem().currentNode());
		AtomicInteger reloadPublications = new AtomicInteger();
		output.getItems().addListener((ListChangeListener<QuestionOutputApplicabilityRow>) change -> {
			while (change.next()) {
				if (change.wasAdded()) {
					reloadPublications.incrementAndGet();
				}
			}
		});
		int nextPublication = reloadPublications.get() + 1;
		robot.interact(() -> pane.refreshAfterEdit(historicalQuestion.getId()));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> reloadPublications.get() >= nextPublication
						&& output.getSelectionModel().getSelectedItem() != null
						&& output.getSelectionModel().getSelectedItem().currentNode().equals(noMatchDescriptor));

		// Switching Questions must not carry the old placement into
		// the new Question's list.
		robot.interact(() -> results.getSelectionModel().select(secondResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> output.getItems().size() == 1 && output.getSelectionModel().getSelectedItem() != null
						&& output.getSelectionModel().getSelectedItem().currentNode().equals(currentDescriptor));

		// Returning to the first Question starts with its first row.
		robot.interact(() -> results.getSelectionModel().select(firstResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> output.getItems().size() == 2 && output.getSelectionModel().getSelectedItem() != null
						&& output.getSelectionModel().getSelectedItem().currentNode().equals(currentDescriptor));
		robot.interact(() -> output.getSelectionModel().select(1));

		// The selected placement disappears from persistence.
		// Refresh must fall back to the first remaining placement.
		matches.set(List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
				new QuestionApplicabilityMatch(secondQuestion, currentDescriptor)));
		int expectedPublication = reloadPublications.get() + 1;
		robot.interact(() -> pane.refreshAfterEdit(historicalQuestion.getId()));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> reloadPublications.get() >= expectedPublication
				&& output.getItems().size() == 1 && output.getSelectionModel().getSelectedItem() != null);
		assertEquals(currentDescriptor, output.getSelectionModel().getSelectedItem().currentNode());
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

		// Physics is the application-level Working Subject for this fixture, allowing
		// navigation validation to occur without a Search-local Subject selector.
		replaceSearchPane(robot, physics, repository, service);
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> statusLabel.getText()
				.equals("Curriculum navigation failed: Subject has multiple current syllabus versions"));
		assertTrue(unitBox.getItems().isEmpty());
		assertTrue(unitBox.isDisable());
		assertTrue(resultsList.getItems().isEmpty());
	}

	@Test
	public void narrowedSearchStillShowsCompleteRevisionOutputApplicability(FxRobot robot) throws TimeoutException {
		AtomicInteger selectiveCalls = new AtomicInteger();
		QuestionRetrievalRepository multipleApplicabilityRepository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
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
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
					long questionId, long parentOperationId) {
				assertEquals(historicalQuestion.getId(), questionId);
				selectiveCalls.incrementAndGet();

				// This lightweight test repository reuses its broad fixture data.
				// The production SQLite implementation constrains Question ID in SQL.
				return findApplicableToNodes(currentNodes).stream()
						.filter(match -> match.getQuestion().getId() == questionId).toList();
			}
		};
		QuestionRetrievalService multipleApplicabilityService = new QuestionRetrievalService(
				multipleApplicabilityRepository, new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, multipleApplicabilityService);
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> outputList = robot.lookup("#question-search-output-applicability")
				.queryListView();
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

		// The visible Search contains only the selected Descriptor.
		assertEquals(List.of(currentDescriptor), searchResult.currentApplicability());
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 2);

		// Revision Output must have used the selective Question API,
		// without inheriting the visible Descriptor restriction.
		assertTrue(selectiveCalls.get() > 0);
		assertEquals(Set.of(currentDescriptor.getId(), noMatchDescriptor.getId()), outputList.getItems().stream()
				.map(row -> row.currentNode().getId()).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	public void nativeDialogCloseGuardsDirtyDescriptor(FxRobot robot) throws TimeoutException {
		Question subtopicQuestion = new Question(50, historicalQuestion.getBooklet(), "22", "", 2, List.of(),
				currentSubtopic, false);
		QuestionSearchDialog[] dialogHolder = new QuestionSearchDialog[1];
		QuestionSearchPane[] paneHolder = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchDialog dialog = new QuestionSearchDialog(stage, chemistry, curriculumRepository,
					retrievalService, () -> List.of(subtopicQuestion), previewService, outputApplicabilityRepository,
					(_, _) -> {

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
	public void oversizedClipboardImageKeepsApplicabilityUsable(FxRobot robot) throws Exception {
		BufferedImage capturedImage = new BufferedImage(900, 2900, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(capturedImage, "png", png));

		// Represent the persisted content of a clipboard capture,
		// not a PDF-derived QuestionRegion.
		ImageQuestionContentPart imagePart = new ImageQuestionContentPart(png.toByteArray());
		Question question = new Question(9701, historicalQuestion.getBooklet(), "IMG1", "Oversized clipboard image.", 2,
				List.of(), historicalDescriptor, false, null, null, QuestionResponseType.UNKNOWN, List.of(imagePart));
		QuestionPreviewService service = new QuestionPreviewService(new PdfStore(Path.of(".")),
				new QuestionExtractor());
		openOversizedPreviewFixture(robot, question, service);
		assertOversizedPreviewLayout(robot);
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Button exclude = robot.lookup("#question-search-output-exclude").queryButton();

		// The first of two placements is automatically selected.
		assertEquals(2, output.getItems().size());
		assertNotNull(output.getSelectionModel().getSelectedItem());
		assertFalse(exclude.isDisable());
	}

	@Test
	public void oversizedPdfRegionPreviewIsStableAcrossResizes(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("search-oversized-pdf-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(9600, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(9601, historicalQuestion.getExam(), "Oversized PDF booklet", document);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.70);
		Question question = new Question(9602, booklet, "PDF1", "", 2, List.of(region), historicalDescriptor, false);
		AtomicInteger renders = new AtomicInteger();
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion requested) throws IOException {
				renders.incrementAndGet();

				// Simulate a very tall PDF-derived region while
				// retaining the actual PDF-backed preview workflow.
				return new BufferedImage(500, 2500, BufferedImage.TYPE_INT_RGB);
			}
		};
		QuestionPreviewService service = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		openOversizedPreviewFixture(robot, question, service);
		assertOversizedPreviewLayout(robot);

		// Window resizing and layout pulses must not cause another
		// PDF extraction for the same selected Question.
		assertEquals(1, renders.get());
	}

	@Test
	public void previewTimingsCorrelateCancelledAndPublishedWork(FxRobot robot) throws Exception {
		Path pdfRoot = Files.createTempDirectory("question-search-preview-timing-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		Path csv = pdfRoot.resolve("performance.csv");
		Exam exam = historicalQuestion.getExam();
		SourceDocument sourceDocument = new SourceDocument(300, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(301, exam, "Timing booklet", sourceDocument);
		Question first = new Question(302, booklet, "P1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), currentDescriptor, false);
		Question second = new Question(303, booklet, "P2", "", 1,
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
		DelayedQuestionExtractor extractor = new DelayedQuestionExtractor(first.getRegions().getFirst());
		QuestionPreviewService timedPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();
			QuestionSearchPane pane = new QuestionSearchPane(chemistry, curriculumRepository, service,
					() -> List.of(first, second), timedPreviewService, outputApplicabilityRepository,
					QuestionSearchNarrowing.unrestricted(), recorder);
			stage.setScene(new Scene(pane, 700, 600));
		});
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 2);
		QuestionSearchResult firstResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == first.getId()).findFirst().orElseThrow();
		QuestionSearchResult secondResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == second.getId()).findFirst().orElseThrow();

		// Begin a preview that deliberately remains in flight.
		robot.interact(() -> resultsList.getSelectionModel().select(firstResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayStarted);

		// Supersede the first preview with a different Question.
		robot.interact(() -> resultsList.getSelectionModel().select(secondResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> preview.getImage() != null && preview.getImage().getWidth() == 22.0);
		extractor.releaseDelayedExtraction();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayFinished);

		// Wait for both background render records, not merely
		// the earlier cancellation or JavaFX display update.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
			try {
				if (!Files.isRegularFile(csv)) {
					return false;
				}
				return Files.readAllLines(csv).stream().filter(line -> line.contains(",search.preview.render,"))
						.count() == 2;
			} catch (IOException failure) {
				return false;
			}
		});
		WaitForAsyncUtils.waitForFxEvents();
		List<String[]> records = Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1)).toList();
		List<String[]> loads = records.stream().filter(row -> row[3].equals("search.preview.load")).toList();
		List<String[]> renders = records.stream().filter(row -> row[3].equals("search.preview.render")).toList();
		List<String[]> publications = records.stream().filter(row -> row[3].equals("search.preview.publish")).toList();
		assertEquals(2, loads.size());
		assertEquals(2, renders.size());
		assertEquals(1, publications.size());
		String[] cancelledLoad = loads.stream().filter(row -> row[5].equals("false")).findFirst().orElseThrow();
		String[] successfulLoad = loads.stream().filter(row -> row[5].equals("true")).findFirst().orElseThrow();

		// Every render belongs to one of the two preview requests.
		for (String[] render : renders) {
			assertTrue(render[2].equals(cancelledLoad[1]) || render[2].equals(successfulLoad[1]));
			assertEquals("true", render[5]);
			assertEquals("1", render[6]);
		}
		String[] publication = publications.getFirst();

		// Only the successful selection reaches JavaFX publication.
		assertEquals(successfulLoad[1], publication[2]);
		assertEquals("true", publication[5]);
		assertEquals("1", publication[6]);
		assertEquals("1", successfulLoad[6]);

		// The stale preview must never overwrite the newer image.
		assertEquals(22.0, preview.getImage().getWidth());
	}

	@Test
	public void prototypeDebounceAvoidsObsoletePdfRenders() throws Exception {
		final int selectionChanges = 20;
		final int debounceMilliseconds = 40;
		Path directory = Files.createTempDirectory("question-search-debounce-");
		Path pdfPath = createDensePdf(directory.resolve("questions.pdf"));
		QuestionExtractor extractor = new QuestionExtractor();
		List<QuestionRegion> regions = new ArrayList<>();
		ExamBooklet booklet = historicalQuestion.getBooklet();
		for (int index = 0; index <= selectionChanges; index++) {
			double x = 0.10 + index * 0.01;
			double width = index == selectionChanges ? 0.65 : 0.40;
			regions.add(new QuestionRegion(booklet, 1, x, 0.10, width, 0.20));
		}

		// Measure an eager strategy that renders every requested
		// Question using the same real PDF and region geometry.
		long eagerRenderNanos = 0;
		for (QuestionRegion region : regions) {
			try (PdfSession session = PdfSession.open(pdfPath)) {
				long startedAt = System.nanoTime();
				BufferedImage image = extractor.extractRegion(session, region);
				assertTrue(image.getWidth() > 0);
				eagerRenderNanos += System.nanoTime() - startedAt;
			}
		}
		CountDownLatch firstRendered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch finalPublished = new CountDownLatch(1);
		AtomicInteger actualRenders = new AtomicInteger();
		AtomicInteger publishedWidth = new AtomicInteger();
		AtomicLong actualRenderNanos = new AtomicLong();
		AtomicLong publishedQuestionId = new AtomicLong(-1);
		AtomicReference<Throwable> workerFailure = new AtomicReference<>();
		long firstQuestionId = 1000;
		long finalQuestionId = firstQuestionId + selectionChanges;
		try (LatestPreviewDebouncer scheduler = new LatestPreviewDebouncer(debounceMilliseconds)) {

			// Allow the first Question to begin real PDF work.
			// Hold its result afterwards, as in Batch 03.
			scheduler.select(firstQuestionId, () -> {
				try (PdfSession session = PdfSession.open(pdfPath)) {
					long startedAt = System.nanoTime();
					BufferedImage image = extractor.extractRegion(session, regions.getFirst());
					actualRenderNanos.addAndGet(System.nanoTime() - startedAt);
					actualRenders.incrementAndGet();
					firstRendered.countDown();
					if (!releaseFirst.await(15, TimeUnit.SECONDS)) {
						throw new IllegalStateException("First PDF release timed out");
					}
					if (scheduler.isCurrent(firstQuestionId)) {
						publishedQuestionId.set(firstQuestionId);
						publishedWidth.set(image.getWidth());
					}
				} catch (Exception | Error failure) {
					workerFailure.compareAndSet(null, failure);
				}
			});
			assertTrue(firstRendered.await(15, TimeUnit.SECONDS));
			long burstStartedAt = System.nanoTime();

			// Rapidly replace pending work. These 20 requests
			// have the same order and PDF regions as Batch 03,
			// but no TestFX inter-selection delay.
			for (int index = 1; index <= selectionChanges; index++) {
				final int capturedIndex = index;
				final long questionId = firstQuestionId + index;
				final QuestionRegion region = regions.get(index);
				scheduler.select(questionId, () -> {
					try (PdfSession session = PdfSession.open(pdfPath)) {
						long startedAt = System.nanoTime();
						BufferedImage image = extractor.extractRegion(session, region);
						actualRenderNanos.addAndGet(System.nanoTime() - startedAt);
						actualRenders.incrementAndGet();
						if (scheduler.isCurrent(questionId)) {
							publishedQuestionId.set(questionId);
							publishedWidth.set(image.getWidth());
							if (capturedIndex == selectionChanges) {
								finalPublished.countDown();
							}
						}
					} catch (Exception | Error failure) {
						workerFailure.compareAndSet(null, failure);
					}
				});
			}

			// The last preview must be available without requiring
			// the first, already-rendered worker to finish.
			try {
				assertTrue(finalPublished.await(15, TimeUnit.SECONDS), "Final preview did not complete");
			} finally {
				releaseFirst.countDown();
			}
			double latestResponseMilliseconds = (System.nanoTime() - burstStartedAt) / 1_000_000.0;
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> scheduler.active() == 0);
			assertEquals(null, workerFailure.get());
			assertEquals(finalQuestionId, publishedQuestionId.get());
			assertTrue(publishedWidth.get() > 700);
			assertEquals(actualRenders.get(), scheduler.completed());

			// At least 16 of the 20 pending changes should be
			// eliminated under this rapid burst.
			assertTrue(scheduler.skipped() >= 16);

			// The held initial render and latest render may overlap,
			// but intermediate selections should mostly be skipped.
			assertTrue(actualRenders.get() <= 5);
			System.out.printf("Issue 28 coalescing experiment | changes=%d " + "delay=%d ms | final=%.3f ms%n",
					selectionChanges, debounceMilliseconds, latestResponseMilliseconds);
			System.out.printf("Eager PDF | renders=%d | " + "aggregateRender=%.3f ms%n", regions.size(),
					eagerRenderNanos / 1_000_000.0);
			System.out.printf(
					"Coalesced PDF | renders=%d | skipped=%d " + "peakConcurrent=%d | " + "aggregateRender=%.3f ms%n",
					actualRenders.get(), scheduler.skipped(), scheduler.peakConcurrent(),
					actualRenderNanos.get() / 1_000_000.0);
		}
	}

	@Test
	public void questionPreviewFitsAvailableColumnWidth(FxRobot robot) throws TimeoutException {
		ScrollPane previewPane = robot.lookup("#question-search-preview-scroll").queryAs(ScrollPane.class);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);

		// Wait until the ScrollPane skin has established a real viewport before
		// comparing the image sizing contract.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> previewPane.getViewportBounds().getWidth() > 0);
		assertEquals(previewPane.getViewportBounds().getWidth(), preview.getFitWidth(), 1.0);

		// A wide Question must be scaled into the available column rather than
		// requiring horizontal scrolling.
		assertEquals(ScrollPane.ScrollBarPolicy.NEVER, previewPane.getHbarPolicy());
		assertTrue(preview.isPreserveRatio());
	}

	@Test
	public void rapidPreviewSelectionsCoalescePendingRenderWork(FxRobot robot) throws Exception {
		final int selectionChanges = 20;
		final long finalQuestionId = 900 + selectionChanges;
		Path pdfRoot = Files.createTempDirectory("question-search-production-coalescing-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(898, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(899, historicalQuestion.getExam(), "Coalescing booklet", document);
		List<Question> questions = new ArrayList<>();
		Map<QuestionRegion, Long> questionIdsByRegion = new HashMap<>();
		for (int index = 0; index <= selectionChanges; index++) {
			long questionId = 900 + index;
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.10 + index * 0.01, 0.10, 0.50, 0.20);
			Question question = new Question(questionId, booklet, "C" + index, "", 2, List.of(region),
					currentDescriptor, false);
			questions.add(question);
			questionIdsByRegion.put(region, questionId);
		}
		List<Question> sourceQuestions = List.copyOf(questions);
		AtomicInteger renderCount = new AtomicInteger();
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
				Long questionId = questionIdsByRegion.get(region);
				if (questionId == null) {
					throw new IOException("Unknown coalescing test region");
				}
				renderCount.incrementAndGet();

				// The final preview has a distinguishable width.
				return new BufferedImage(questionId == finalQuestionId ? 22 : 11, 22, BufferedImage.TYPE_INT_RGB);
			}
		};
		QuestionPreviewService measuredPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		QuestionRetrievalRepository repository = currentNodes -> {
			if (!currentNodes.contains(currentDescriptor)) {
				return List.of();
			}
			return sourceQuestions.stream().map(question -> new QuestionApplicabilityMatch(question, currentDescriptor))
					.toList();
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		outputApplicabilityRepository.setExcluded(sourceQuestions.getLast(), currentDescriptor, true);
		robot.interact(() -> {
			QuestionSearchPane previous = (QuestionSearchPane) stage.getScene().getRoot();
			previous.dispose();
			QuestionSearchPane pane = new QuestionSearchPane(chemistry, curriculumRepository, service,
					() -> sourceQuestions, measuredPreviewService, outputApplicabilityRepository);
			stage.setScene(new Scene(pane, 700, 600));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().size() == selectionChanges + 1);
		long startedAt = System.nanoTime();

		// Dispatch every selection within a single JavaFX turn.
		// Only the final pending preview should reach PDF rendering.
		robot.interact(() -> {
			for (Question question : sourceQuestions) {
				QuestionSearchResult result = results.getItems().stream()
						.filter(candidate -> candidate.question().getId() == question.getId()).findFirst()
						.orElseThrow();
				results.getSelectionModel().select(result);
			}
		});
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> results.getSelectionModel().getSelectedItem() != null
						&& results.getSelectionModel().getSelectedItem().question().getId() == finalQuestionId
						&& preview.getImage() != null && preview.getImage().getWidth() == 22.0
						&& output.getItems().size() == 1 && output.getItems().getFirst().excluded());
		double responseMilliseconds = (System.nanoTime() - startedAt) / 1_000_000.0;
		assertEquals(1, renderCount.get());
		assertEquals(finalQuestionId, results.getSelectionModel().getSelectedItem().question().getId());
		assertEquals(22.0, preview.getImage().getWidth());
		assertEquals(currentDescriptor, output.getItems().getFirst().currentNode());
		assertTrue(output.getItems().getFirst().excluded());
		System.out.printf(
				"Issue 28 production coalescing | " + "changes=%d | PDF renders=%d | " + "avoided=%d | final=%.3f ms%n",
				selectionChanges, renderCount.get(), selectionChanges + 1 - renderCount.get(), responseMilliseconds);
	}

	@Test
	public void rapidSelectionsKeepLatestQuestionApplicabilityResponsive(FxRobot robot) throws TimeoutException {
		Question second = new Question(350, historicalQuestion.getBooklet(), "R2", "Second rapid-selection Question", 2,
				List.of(), currentSubtopic, false);
		Question third = new Question(351, historicalQuestion.getBooklet(), "R3", "Third rapid-selection Question", 2,
				List.of(), historicalDescriptor, false);
		List<QuestionApplicabilityMatch> matches = List.of(
				new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
				new QuestionApplicabilityMatch(second, currentSubtopic),
				new QuestionApplicabilityMatch(third, noMatchDescriptor));
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch firstRelease = new CountDownLatch(1);
		CountDownLatch firstFinished = new CountDownLatch(1);
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> nodes) {
				return matches.stream().filter(match -> nodes.contains(match.getCurrentNode())).toList();
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> nodes,
					long questionId, long parentOperationId) {
				if (questionId == historicalQuestion.getId()) {
					firstStarted.countDown();

					// Simulate a slow database call that continues after
					// its JavaFX Task has been superseded.
					boolean released = false;
					while (!released) {
						try {
							firstRelease.await();
							released = true;
						} catch (InterruptedException ignored) {

							// Retain the stale-operation scenario.
						}
					}
					firstFinished.countDown();
				}
				return matches.stream().filter(match -> match.getQuestion().getId() == questionId)
						.filter(match -> nodes.contains(match.getCurrentNode())).toList();
			}
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		outputApplicabilityRepository.setExcluded(third, noMatchDescriptor, true);
		replaceSearchPane(robot, curriculumRepository, service, () -> List.of(historicalQuestion, second, third));
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Label status = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);
		try {
			robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 3);
			QuestionSearchResult firstResult = results.getItems().stream()
					.filter(result -> result.question().getId() == historicalQuestion.getId()).findFirst()
					.orElseThrow();
			QuestionSearchResult secondResult = results.getItems().stream()
					.filter(result -> result.question().getId() == second.getId()).findFirst().orElseThrow();
			QuestionSearchResult thirdResult = results.getItems().stream()
					.filter(result -> result.question().getId() == third.getId()).findFirst().orElseThrow();
			robot.interact(() -> results.getSelectionModel().select(firstResult));
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> firstStarted.getCount() == 0);

			// The first Question remains deliberately blocked while
			// the next selection must complete independently.
			long secondStarted = System.nanoTime();
			robot.interact(() -> results.getSelectionModel().select(secondResult));
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> output.getItems().size() == 1
							&& output.getItems().getFirst().currentNode().equals(currentSubtopic)
							&& status.getText().equals("1 current placement: 1 included, 0 excluded."));
			double secondMilliseconds = (System.nanoTime() - secondStarted) / 1_000_000.0;
			assertEquals(1, firstFinished.getCount());
			assertEquals(second.getId(), results.getSelectionModel().getSelectedItem().question().getId());

			// Select another Question without releasing the original
			// slow retrieval. This placement is explicitly excluded.
			long thirdStarted = System.nanoTime();
			robot.interact(() -> results.getSelectionModel().select(thirdResult));
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
					() -> output.getItems().size() == 1
							&& output.getItems().getFirst().currentNode().equals(noMatchDescriptor)
							&& output.getItems().getFirst().excluded()
							&& status.getText().equals("1 current placement: 0 included, 1 excluded."));
			double thirdMilliseconds = (System.nanoTime() - thirdStarted) / 1_000_000.0;
			assertEquals(1, firstFinished.getCount());
			System.out.printf("Rapid selection while earlier retrieval blocked: " + "second=%.3f ms | third=%.3f ms%n",
					secondMilliseconds, thirdMilliseconds);

			// The old retrieval now finishes after the third Question
			// has already published its current applicability.
			firstRelease.countDown();
			WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> firstFinished.getCount() == 0);
			WaitForAsyncUtils.waitForFxEvents();
			assertEquals(third.getId(), results.getSelectionModel().getSelectedItem().question().getId());
			assertEquals(1, output.getItems().size());
			assertEquals(noMatchDescriptor, output.getItems().getFirst().currentNode());
			assertTrue(output.getItems().getFirst().excluded());
			assertEquals("1 current placement: 0 included, 1 excluded.", status.getText());
		} finally {

			// Never leave a test-owned background operation blocked
			// if an assertion or timeout fails.
			firstRelease.countDown();
		}
	}

	@Test
	public void rapidSelectionsMeasureRealPdfAndSqliteWork(FxRobot robot) throws Exception {
		final int selectionChanges = 20;
		Path directory = Files.createTempDirectory("question-search-real-work-");
		Path pdfRoot = directory.resolve("pdf");
		Files.createDirectories(pdfRoot);
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		Path databasePath = directory.resolve("questions.db");
		Path performanceCsv = directory.resolve("performance.csv");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		PerformanceRecorder recorder = new PerformanceRecorder(true, performanceCsv);
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Measurement Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);
		Unit unit = writer.insertUnit(version, "1", "Unit 1", 1);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = writer.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		Descriptor descriptor = writer.insertDescriptor(subtopic, "1.1.1.1", "Descriptor 1", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(subject, "QCAA", 2025, "External Assessment", "Paper 1",
				"questions.pdf");
		SqliteQuestionRepository sqliteQuestions = new SqliteQuestionRepository(database, recorder);
		List<Question> questions = new ArrayList<>();
		Map<Double, Long> questionIdsByX = new HashMap<>();
		for (int index = 0; index <= selectionChanges; index++) {
			double x = 0.10 + index * 0.01;

			// The final Question has a distinguishably wider PDF
			// region, allowing the UI test to identify its preview.
			double width = index == selectionChanges ? 0.65 : 0.40;
			QuestionRegion region = new QuestionRegion(booklet, 1, x, 0.10, width, 0.20);
			Question question = sqliteQuestions.save(booklet, "R" + index, "", 2, List.of(region), descriptor, false);
			questions.add(question);
			questionIdsByX.put(x, question.getId());
		}
		List<Question> sourceQuestions = List.copyOf(questions);
		long finalQuestionId = sourceQuestions.getLast().getId();
		RealSelectionWorkProbe previewProbe = new RealSelectionWorkProbe();
		RealSelectionWorkProbe applicabilityProbe = new RealSelectionWorkProbe();
		QuestionRetrievalRepository measuredRepository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> nodes) {
				return sqliteQuestions.findApplicableToNodes(nodes);
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> nodes,
					long questionId, long parentOperationId) {
				try {
					return applicabilityProbe.measure(questionId, () -> sqliteQuestions
							.findApplicableToNodesForQuestion(nodes, questionId, parentOperationId));
				} catch (RuntimeException failure) {
					throw failure;
				} catch (Exception failure) {
					throw new IllegalStateException("Measured SQLite retrieval failed", failure);
				}
			}
		};
		QuestionExtractor measuredExtractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
				Long questionId = questionIdsByX.get(region.x());
				if (questionId == null) {
					throw new IOException("Unknown measurement region");
				}
				try {

					// Perform the actual PDFBox render and crop.
					return previewProbe.measure(questionId, () -> super.extractRegion(session, region));
				} catch (IOException failure) {
					throw failure;
				} catch (Exception failure) {
					throw new IOException("Measured PDF extraction failed", failure);
				}
			}
		};
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		QuestionRetrievalService service = new QuestionRetrievalService(measuredRepository,
				new CurriculumSearchNodeExpansionService(curriculum), recorder);
		QuestionPreviewService measuredPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot),
				measuredExtractor);
		InMemoryQuestionOutputApplicabilityRepository exclusions = new InMemoryQuestionOutputApplicabilityRepository();
		exclusions.setExcluded(sourceQuestions.getLast(), descriptor, true);
		robot.interact(() -> {
			QuestionSearchPane previous = (QuestionSearchPane) stage.getScene().getRoot();
			previous.dispose();
			QuestionSearchPane pane = new QuestionSearchPane(subject, curriculum, service, () -> sourceQuestions,
					measuredPreviewService, exclusions, QuestionSearchNarrowing.unrestricted(), recorder);
			stage.setScene(new Scene(pane, 700, 600));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().size() == selectionChanges + 1);
		long workloadStarted = System.nanoTime();
		long finalSelectionStarted = 0;
		for (Question question : sourceQuestions) {
			QuestionSearchResult result = results.getItems().stream()
					.filter(candidate -> candidate.question().getId() == question.getId()).findFirst().orElseThrow();

			// Record the requested selection before JavaFX starts its
			// corresponding asynchronous retrieval/rendering tasks.
			previewProbe.select(question.getId());
			applicabilityProbe.select(question.getId());
			if (question.getId() == finalQuestionId) {
				finalSelectionStarted = System.nanoTime();
			}
			robot.interact(() -> results.getSelectionModel().select(result));
		}
		WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
				() -> results.getSelectionModel().getSelectedItem() != null
						&& results.getSelectionModel().getSelectedItem().question().getId() == finalQuestionId
						&& output.getItems().size() == 1 && output.getItems().getFirst().excluded()
						&& preview.getImage() != null && preview.getImage().getWidth() > 700.0);
		double finalMilliseconds = (System.nanoTime() - finalSelectionStarted) / 1_000_000.0;

		// Observe completion of the operations currently in flight.
		// Do not require all 21 requests to start background work:
		// some may be superseded before execution begins.
		WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
				() -> previewProbe.active() == 0 && applicabilityProbe.active() == 0);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(finalQuestionId, results.getSelectionModel().getSelectedItem().question().getId());
		assertEquals(descriptor, output.getItems().getFirst().currentNode());
		assertTrue(output.getItems().getFirst().excluded());
		assertTrue(preview.getImage().getWidth() > 700.0);
		assertTrue(previewProbe.started() > 0);
		assertTrue(applicabilityProbe.started() > 0);
		assertEquals(previewProbe.started(), previewProbe.completed());
		assertEquals(applicabilityProbe.started(), applicabilityProbe.completed());
		System.out.printf("Issue 28 real work | changes=%d | total=%.3f ms | " + "final=%.3f ms%n", selectionChanges,
				(System.nanoTime() - workloadStarted) / 1_000_000.0, finalMilliseconds);
		System.out.printf(
				"Real PDF | started=%d completed=%d superseded=%d " + "peakConcurrent=%d total=%.3f ms "
						+ "supersededElapsed=%.3f ms%n",
				previewProbe.started(), previewProbe.completed(), previewProbe.superseded(),
				previewProbe.peakConcurrent(), previewProbe.totalMilliseconds(), previewProbe.supersededMilliseconds());
		System.out.printf(
				"Real SQLite | started=%d completed=%d superseded=%d " + "peakConcurrent=%d total=%.3f ms "
						+ "supersededElapsed=%.3f ms%n",
				applicabilityProbe.started(), applicabilityProbe.completed(), applicabilityProbe.superseded(),
				applicabilityProbe.peakConcurrent(), applicabilityProbe.totalMilliseconds(),
				applicabilityProbe.supersededMilliseconds());
		System.out.printf("Real-work sampled heap high-water: %.2f MiB%n",
				Math.max(previewProbe.sampledHeapBytes(), applicabilityProbe.sampledHeapBytes()) / 1048576.0);
		System.out.println("Real-work performance CSV: " + performanceCsv);
	}

	@Test
	public void rapidSelectionWorkloadMeasuresSupersededPreviewAndApplicability(FxRobot robot) throws Exception {
		final int selectionChanges = 20;
		final long finalQuestionId = 500 + selectionChanges;
		Path pdfRoot = Files.createTempDirectory("question-search-overlap-");
		createOnePagePdf(pdfRoot.resolve("questions.pdf"));
		SourceDocument document = new SourceDocument(498, "questions.pdf");
		ExamBooklet booklet = new ExamBooklet(499, historicalQuestion.getExam(), "Overlap booklet", document);
		List<Question> questions = new ArrayList<>();
		Map<QuestionRegion, Long> questionIdsByRegion = new HashMap<>();
		for (int index = 0; index <= selectionChanges; index++) {
			long questionId = 500 + index;
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.10 + index * 0.01, 0.10, 0.50, 0.20);
			Question question = new Question(questionId, booklet, "R" + index, "", 2, List.of(region),
					currentDescriptor, false);
			questions.add(question);
			questionIdsByRegion.put(region, questionId);
		}
		List<Question> sourceQuestions = List.copyOf(questions);
		Map<QuestionRegion, Long> regionIds = Map.copyOf(questionIdsByRegion);
		RapidSelectionWorkProbe previewProbe = new RapidSelectionWorkProbe(finalQuestionId);
		RapidSelectionWorkProbe applicabilityProbe = new RapidSelectionWorkProbe(finalQuestionId);
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> nodes) {
				if (!nodes.contains(currentDescriptor)) {
					return List.of();
				}
				return sourceQuestions.stream()
						.map(question -> new QuestionApplicabilityMatch(question, currentDescriptor)).toList();
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> nodes,
					long questionId, long parentOperationId) {
				return applicabilityProbe
						.measure(questionId,
								() -> nodes.contains(currentDescriptor)
										? sourceQuestions.stream().filter(question -> question.getId() == questionId)
												.map(question -> new QuestionApplicabilityMatch(question,
														currentDescriptor))
												.toList()
										: List.of());
			}
		};
		QuestionExtractor extractor = new QuestionExtractor() {

			@Override
			public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
				Long questionId = regionIds.get(region);
				if (questionId == null) {
					throw new IOException("Unknown test PDF region");
				}
				return previewProbe.measure(questionId, () -> new BufferedImage(questionId == finalQuestionId ? 22 : 11,
						22, BufferedImage.TYPE_INT_RGB));
			}
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService measuredPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		Question finalQuestion = sourceQuestions.getLast();
		outputApplicabilityRepository.setExcluded(finalQuestion, currentDescriptor, true);
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();
			QuestionSearchPane pane = new QuestionSearchPane(chemistry, curriculumRepository, service,
					() -> sourceQuestions, measuredPreviewService, outputApplicabilityRepository);
			stage.setScene(new Scene(pane, 700, 600));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		try {
			robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> results.getItems().size() == selectionChanges + 1
					&& scope.getValue() == QuestionSearchScope.ALL_QUESTIONS);

			// Each selection must start both background operations
			// before it is superseded by the next selection.
			for (int index = 0; index < selectionChanges; index++) {
				QuestionSearchResult result = results.getItems().get(index);
				long questionId = result.question().getId();
				int expectedStarted = index + 1;
				previewProbe.select(questionId);
				applicabilityProbe.select(questionId);
				robot.interact(() -> results.getSelectionModel().select(result));
				WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> previewProbe.started() >= expectedStarted
						&& applicabilityProbe.started() >= expectedStarted);
			}

			// All preceding operations are still in flight.
			assertEquals(selectionChanges, previewProbe.active());
			assertEquals(selectionChanges, applicabilityProbe.active());
			QuestionSearchResult lastResult = results.getItems().getLast();
			previewProbe.select(finalQuestionId);
			applicabilityProbe.select(finalQuestionId);
			long finalSelectionStarted = System.nanoTime();
			robot.interact(() -> results.getSelectionModel().select(lastResult));
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
					() -> output.getItems().size() == 1 && output.getItems().getFirst().excluded()
							&& preview.getImage() != null && preview.getImage().getWidth() == 22.0);
			double finalSelectionMilliseconds = (System.nanoTime() - finalSelectionStarted) / 1_000_000.0;

			// The latest Question is ready even though the
			// previous 20 workers remain unfinished.
			assertEquals(selectionChanges, previewProbe.active());
			assertEquals(selectionChanges, applicabilityProbe.active());
			previewProbe.release();
			applicabilityProbe.release();
			WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> previewProbe.completed() == selectionChanges + 1
					&& applicabilityProbe.completed() == selectionChanges + 1);
			WaitForAsyncUtils.waitForFxEvents();
			assertEquals(selectionChanges + 1, previewProbe.started());
			assertEquals(selectionChanges + 1, applicabilityProbe.started());
			assertEquals(selectionChanges, previewProbe.discarded());
			assertEquals(selectionChanges, applicabilityProbe.discarded());
			assertEquals(0, previewProbe.active());
			assertEquals(0, applicabilityProbe.active());

			// Obsolete operations must never replace the final
			// preview or the final Question's excluded placement.
			assertEquals(finalQuestionId, results.getSelectionModel().getSelectedItem().question().getId());
			assertEquals(22.0, preview.getImage().getWidth());
			assertEquals(currentDescriptor, output.getItems().getFirst().currentNode());
			assertTrue(output.getItems().getFirst().excluded());
			System.out.printf("Issue 28 controlled baseline | changes=%d | " + "final=%.3f ms%n", selectionChanges,
					finalSelectionMilliseconds);
			System.out.printf(
					"Preview | started=%d completed=%d discarded=%d " + "peakConcurrent=%d discardedElapsed=%.3f ms%n",
					previewProbe.started(), previewProbe.completed(), previewProbe.discarded(),
					previewProbe.peakConcurrent(), previewProbe.discardedMilliseconds());
			System.out.printf(
					"Applicability | started=%d completed=%d discarded=%d "
							+ "peakConcurrent=%d discardedElapsed=%.3f ms%n",
					applicabilityProbe.started(), applicabilityProbe.completed(), applicabilityProbe.discarded(),
					applicabilityProbe.peakConcurrent(), applicabilityProbe.discardedMilliseconds());
			System.out.printf("Sampled heap high-water: %.2f MiB%n",
					Math.max(previewProbe.sampledHeapBytes(), applicabilityProbe.sampledHeapBytes()) / 1048576.0);
		} finally {

			// A failed assertion must never strand test workers.
			previewProbe.release();
			applicabilityProbe.release();
		}
	}

	@Test
	public void refreshAfterEditRetainsAllQuestionsScopeAndReselectsUpdatedQuestion(FxRobot robot)
			throws TimeoutException {
		AtomicReference<List<Question>> allQuestions = new AtomicReference<>(List.of(historicalQuestion));
		AtomicReference<Supplier<List<Question>>> activeSupplier = new AtomicReference<>(allQuestions::get);
		QuestionSearchPane pane = replaceSearchPane(robot, curriculumRepository, retrievalService,
				() -> activeSupplier.get().get());
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		Question editedQuestion = new Question(historicalQuestion.getId(), historicalQuestion.getBooklet(), "21b",
				historicalQuestion.getQuestionText(), historicalQuestion.getMarks(), historicalQuestion.getRegions(),
				historicalQuestion.getClassification(), historicalQuestion.isSharedContextCaptureRequired(),
				historicalQuestion.getSourceQuestion(), historicalQuestion.getSharedContext(),
				historicalQuestion.getResponseType());

		// Hold the replacement persistence generation so the test can inspect what the
		// teacher sees while the refresh is still running.
		DelayedAllQuestionsSupplier delayedRefresh = new DelayedAllQuestionsSupplier(List.of(editedQuestion));
		activeSupplier.set(delayedRefresh);
		robot.interact(() -> pane.refreshAfterEdit(editedQuestion.getId()));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, delayedRefresh::hasDelayStarted);

		// Refreshing a saved edit must not blank the accepted Search generation while
		// persistence is being queried in the background.
		assertEquals(1, resultsList.getItems().size());
		assertEquals(historicalQuestion.getId(), resultsList.getSelectionModel().getSelectedItem().question().getId());
		delayedRefresh.release();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> pane.getSelectedQuestion() != null && "21b".equals(pane.getSelectedQuestion().getQuestionCode()));

		// The completed refresh preserves the scope the teacher was viewing and
		// reselects the freshly loaded persisted Question.
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scopeBox.getValue());
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, resultsList.getSelectionModel().getSelectedItem().scope());
		assertEquals(editedQuestion.getId(), pane.getSelectedQuestion().getId());
	}

	@Test
	public void returningFromAllQuestionsRestoresCurrentSyllabusSearch(FxRobot robot) throws TimeoutException {
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();

		// Initial Search runs automatically against the Working Subject's current
		// syllabus.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.CURRENT_SYLLABUS);
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1
				&& resultsList.getItems().getFirst().scope() == QuestionSearchScope.CURRENT_SYLLABUS);

		// Scope transitions must not recreate or require an independent Subject
		// selector.
		assertTrue(robot.lookup("#question-search-subject").tryQuery().isEmpty());
		assertEquals(historicalQuestion.getId(), resultsList.getItems().getFirst().question().getId());
	}

	@Test
	public void revisionOutputApplicabilityCanExcludeAndRestoreOnePlacement(FxRobot robot) throws TimeoutException {
		QuestionRetrievalRepository multipleApplicabilityRepository = currentNodes -> {
			if (!currentNodes.contains(currentDescriptor) || !currentNodes.contains(noMatchDescriptor)) {
				return List.of();
			}

			// One historical Question is applicable at two distinct current Descriptors
			// so the test can prove that only the selected placement is changed.
			return List.of(new QuestionApplicabilityMatch(historicalQuestion, currentDescriptor),
					new QuestionApplicabilityMatch(historicalQuestion, noMatchDescriptor));
		};
		QuestionRetrievalService multipleApplicabilityService = new QuestionRetrievalService(
				multipleApplicabilityRepository, new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, multipleApplicabilityService);
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> outputList = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Button includeButton = robot.lookup("#question-search-output-include").queryButton();
		Button excludeButton = robot.lookup("#question-search-output-exclude").queryButton();
		Label outputStatus = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);

		// Initial Working Subject Search returns the Question without requiring a
		// Search-local Subject-selection action.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 2);
		QuestionOutputApplicabilityRow firstPlacement = outputList.getItems().stream()
				.filter(row -> row.currentNode().equals(currentDescriptor)).findFirst().orElseThrow();
		robot.interact(() -> outputList.getSelectionModel().select(firstPlacement));

		// An included placement can be excluded, but Include is meaningless until that
		// exclusion has actually been persisted.
		assertTrue(includeButton.isDisable());
		assertFalse(excludeButton.isDisable());

		// This is a semantic button action; pointer hit testing is not under test.
		robot.interact(excludeButton::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> outputList.getItems().stream().filter(row -> row.currentNode().equals(currentDescriptor))
						.anyMatch(QuestionOutputApplicabilityRow::excluded));
		assertEquals(Set.of(currentDescriptor.getId()),
				outputApplicabilityRepository.findExcludedCurrentNodeIds(historicalQuestion));

		// The second placement remains independently included.
		assertFalse(outputList.getItems().stream().filter(row -> row.currentNode().equals(noMatchDescriptor))
				.findFirst().orElseThrow().excluded());
		assertEquals("2 current placements: 1 included, 1 excluded.", outputStatus.getText());
		assertFalse(includeButton.isDisable());
		assertTrue(excludeButton.isDisable());

		// Restore the selected placement through the same semantic control path.
		robot.interact(includeButton::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> outputList.getItems().stream().filter(row -> row.currentNode().equals(currentDescriptor))
						.noneMatch(QuestionOutputApplicabilityRow::excluded));

		// Removing the exception restores normal curriculum-derived applicability.
		assertEquals(Set.of(), outputApplicabilityRepository.findExcludedCurrentNodeIds(historicalQuestion));
		assertEquals("2 current placements: 2 included, 0 excluded.", outputStatus.getText());
		assertTrue(includeButton.isDisable());
		assertFalse(excludeButton.isDisable());
	}

	@Test
	public void revisionOutputChangeRefreshesActiveFilteredSearch(FxRobot robot) throws TimeoutException {
		Question first = historicalQuestion;
		Question second = new Question(160, first.getBooklet(), "Q4", "", 2, List.of(), historicalDescriptor, false);
		List<Question> questions = List.of(first, second);
		QuestionRetrievalRepository repository = nodes -> {
			if (!nodes.contains(currentDescriptor)) {
				return List.of();
			}
			return questions.stream().map(question -> new QuestionApplicabilityMatch(question, currentDescriptor))
					.toList();
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));

		// First is initially excluded; second is included.
		outputApplicabilityRepository.setExcluded(first, currentDescriptor, true);
		replaceSearchPane(robot, curriculumRepository, service, () -> questions);
		TitledPane moreFilters = robot.lookup("#question-search-more-filters").queryAs(TitledPane.class);
		robot.interact(() -> moreFilters.setExpanded(true));
		ComboBox<QuestionSearchInclusionFilter> inclusion = robot.lookup("#question-search-inclusion-filter")
				.queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Button includeButton = robot.lookup("#question-search-output-include").queryButton();
		Button excludeButton = robot.lookup("#question-search-output-exclude").queryButton();
		Label status = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		robot.interact(() -> inclusion.setValue(QuestionSearchInclusionFilter.EXCLUDED));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == first.getId());
		robot.interact(() -> results.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> output.getItems().size() == 1
				&& output.getItems().getFirst().excluded() && !includeButton.isDisable());

		// Persisting Include removes the selected Question
		// from the active Excluded search.
		robot.interact(includeButton::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().isEmpty() && "No questions found.".equals(status.getText()));
		assertTrue(outputApplicabilityRepository.findExcludedCurrentNodeIds(first).isEmpty());

		// Both Questions now match Included.
		robot.interact(() -> inclusion.setValue(QuestionSearchInclusionFilter.INCLUDED));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);
		QuestionSearchResult secondResult = results.getItems().stream()
				.filter(result -> result.question().getId() == second.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(secondResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> output.getItems().size() == 1
				&& !output.getItems().getFirst().excluded() && !excludeButton.isDisable());

		// Persisting Exclude removes second from Included.
		robot.interact(excludeButton::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == first.getId());
		assertEquals(Set.of(currentDescriptor.getId()),
				outputApplicabilityRepository.findExcludedCurrentNodeIds(second));
	}

	@Test
	public void revisionOutputFilterTracksSelectedCurriculumScope(FxRobot robot) throws TimeoutException {
		Question mixed = historicalQuestion;
		Question excluded = new Question(150, mixed.getBooklet(), "Q2", "", 2, List.of(), historicalDescriptor, false);
		Question included = new Question(151, mixed.getBooklet(), "Q3", "", 2, List.of(), historicalDescriptor, false);
		List<Question> questions = List.of(mixed, excluded, included);
		QuestionRetrievalRepository repository = nodes -> {
			List<QuestionApplicabilityMatch> matches = new ArrayList<>();
			if (nodes.contains(currentDescriptor)) {
				for (Question question : questions) {
					matches.add(new QuestionApplicabilityMatch(question, currentDescriptor));
				}
			}
			if (nodes.contains(noMatchDescriptor)) {
				matches.add(new QuestionApplicabilityMatch(mixed, noMatchDescriptor));
			}
			return matches;
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));

		// Mixed is excluded only from the second Topic.
		// Excluded is excluded from the first Topic.
		// Included has no explicit exclusions.
		outputApplicabilityRepository.setExcluded(mixed, noMatchDescriptor, true);
		outputApplicabilityRepository.setExcluded(excluded, currentDescriptor, true);
		replaceSearchPane(robot, curriculumRepository, service, () -> questions);
		TitledPane moreFilters = robot.lookup("#question-search-more-filters").queryAs(TitledPane.class);
		robot.interact(() -> moreFilters.setExpanded(true));
		ComboBox<QuestionSearchInclusionFilter> inclusion = robot.lookup("#question-search-inclusion-filter")
				.queryComboBox();
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<CurriculumNode> unit = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topic = robot.lookup("#question-search-topic").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 3 && unit.getItems().contains(currentUnit));
		assertFalse(inclusion.isDisable());
		assertEquals(QuestionSearchInclusionFilter.ALL, inclusion.getValue());

		// Both mixed and excluded have at least one
		// excluded placement in the complete current syllabus.
		robot.interact(() -> inclusion.setValue(QuestionSearchInclusionFilter.EXCLUDED));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 2
						&& results.getItems().stream().map(result -> result.question().getId())
								.collect(Collectors.toSet()).equals(Set.of(mixed.getId(), excluded.getId())));

		// Mixed also has an included placement.
		// Included has no exclusions at all.
		robot.interact(() -> inclusion.setValue(QuestionSearchInclusionFilter.INCLUDED));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> results.getItems().size() == 2
						&& results.getItems().stream().map(result -> result.question().getId())
								.collect(Collectors.toSet()).equals(Set.of(mixed.getId(), included.getId())));
		robot.interact(() -> inclusion.setValue(QuestionSearchInclusionFilter.EXCLUDED));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);

		// Narrow to the first Topic.
		// Mixed's excluded placement is in a different Topic,
		// so it must no longer match Excluded.
		robot.interact(() -> unit.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> topic.getItems().contains(currentTopic));
		robot.interact(() -> topic.setValue(currentTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().question().getId() == excluded.getId());

		// All Questions must not retain the exclusion filter.
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 3
				&& results.getItems().stream().allMatch(result -> result.scope() == QuestionSearchScope.ALL_QUESTIONS));
		assertTrue(inclusion.isDisable());
		assertEquals(QuestionSearchInclusionFilter.ALL, inclusion.getValue());

		// Returning to Current syllabus begins unrestricted
		// inclusion filtering, not the old Excluded setting.
		robot.interact(() -> scope.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 3 && results.getItems()
				.stream().allMatch(result -> result.scope() == QuestionSearchScope.CURRENT_SYLLABUS));
		assertFalse(inclusion.isDisable());
		assertEquals(QuestionSearchInclusionFilter.ALL, inclusion.getValue());
	}

	@Test
	public void rightColumnGivesRemainingHeightToPreview(FxRobot robot) {
		VBox rightColumn = robot.lookup("#question-search-right-column").queryAs(VBox.class);
		Node selectedClassification = robot.lookup("#question-search-selected-classification").query();
		Node outputApplicability = robot.lookup("#question-search-output-section").query();
		Node preview = robot.lookup("#question-search-preview-section").query();

		// The right column is a simple task sequence. Classification and output
		// applicability keep their natural height while Preview receives spare space.
		assertEquals(List.of(selectedClassification, outputApplicability, preview), rightColumn.getChildren());
		assertFalse(Priority.ALWAYS.equals(VBox.getVgrow(outputApplicability)));
		assertEquals(Priority.ALWAYS, VBox.getVgrow(preview));
	}

	@Test
	public void searchSectionsUseTwoColumnTaskLayout(FxRobot robot) {
		SplitPane workspace = robot.lookup("#question-search-workspace").queryAs(SplitPane.class);
		Node leftColumn = robot.lookup("#question-search-left-column").query();
		Node rightColumn = robot.lookup("#question-search-right-column").query();
		VBox resultsSection = robot.lookup("#question-search-results-section").queryAs(VBox.class);
		VBox detailsSection = robot.lookup("#question-search-details-section").queryAs(VBox.class);
		assertEquals(Orientation.HORIZONTAL, workspace.getOrientation());

		// Search uses a narrower navigation/results column and gives the larger share
		// of horizontal space to classification, applicability and Question preview.
		// The requested divider is 0.35, but JavaFX may move it to
		// respect child minimum widths. Font metrics under Xvfb can
		// produce a different effective position from local Windows.
		double dividerPosition = workspace.getDividerPositions()[0];
		assertTrue(dividerPosition > 0.0 && dividerPosition < 0.5,
				"Search/results must remain narrower than Question information");

		// Verify the rendered columns as well as the divider position.
		robot.interact(() -> {
			workspace.applyCss();
			workspace.layout();
			double leftWidth = leftColumn.getBoundsInParent().getWidth();
			double rightWidth = rightColumn.getBoundsInParent().getWidth();
			assertTrue(leftWidth > 0.0);
			assertTrue(rightWidth > leftWidth, "Question information should receive more horizontal space");
		});

		// Search, results and basic Question details belong together on the left.
		assertTrue(isDescendantOf(robot.lookup("#question-search-filter-section").query(), leftColumn));
		assertTrue(isDescendantOf(robot.lookup("#question-search-results-section").query(), leftColumn));
		assertTrue(isDescendantOf(robot.lookup("#question-search-details-section").query(), leftColumn));

		// Selected-Question decisions and visual output belong together on the right.
		assertTrue(isDescendantOf(robot.lookup("#question-search-selected-classification").query(), rightColumn));
		assertTrue(isDescendantOf(robot.lookup("#question-search-output-section").query(), rightColumn));
		assertTrue(isDescendantOf(robot.lookup("#question-search-preview-section").query(), rightColumn));

		// The two vertically split Question sections use the same inset as the
		// surrounding Search workspace rather than pressing against their pane edges.
		assertEquals(new Insets(12), resultsSection.getPadding());
		assertEquals(new Insets(12), detailsSection.getPadding());
	}

	@Test
	public void selectedDescriptorQuestionShowsStoredClassificationPath(FxRobot robot) throws TimeoutException {
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ComboBox<CurriculumNode> selectedUnitBox = robot.lookup("#question-search-selected-unit").queryComboBox();
		ComboBox<CurriculumNode> selectedTopicBox = robot.lookup("#question-search-selected-topic").queryComboBox();
		ComboBox<CurriculumNode> selectedDescriptorBox = robot.lookup("#question-search-selected-descriptor")
				.queryComboBox();

		// Initial Working Subject Search supplies the stored Question automatically.
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
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> outputList = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Label outputStatus = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);

		// Initial Working Subject Search supplies the Question whose output
		// applicability contains the persisted exclusion.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> outputList.getItems().size() == 1);
		QuestionOutputApplicabilityRow row = outputList.getItems().getFirst();
		assertEquals(currentDescriptor, row.currentNode());
		assertTrue(row.excluded());

		// The Question remains curriculum-applicable while this one current placement
		// is explicitly excluded from Revision Output.
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
	public void singleApplicabilityPlacementIsSelectedWithoutPersistingChanges(FxRobot robot) throws TimeoutException {
		outputApplicabilityRepository.setExcluded(historicalQuestion, currentDescriptor, true);
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Button include = robot.lookup("#question-search-output-include").queryButton();
		Button exclude = robot.lookup("#question-search-output-exclude").queryButton();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		robot.interact(() -> results.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> output.getItems().size() == 1
				&& output.getSelectionModel().getSelectedItem() != null && !include.isDisable());
		QuestionOutputApplicabilityRow selected = output.getSelectionModel().getSelectedItem();
		assertEquals(currentDescriptor, selected.currentNode());
		assertTrue(selected.excluded());
		assertTrue(exclude.isDisable());

		// Automatic selection must not persist anything.
		assertEquals(Set.of(currentDescriptor.getId()),
				outputApplicabilityRepository.findExcludedCurrentNodeIds(historicalQuestion));
		robot.interact(include::fire);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> output.getSelectionModel().getSelectedItem() != null
				&& !output.getSelectionModel().getSelectedItem().excluded() && !exclude.isDisable());
		assertEquals(currentDescriptor, output.getSelectionModel().getSelectedItem().currentNode());
		assertTrue(include.isDisable());
		assertEquals(Set.of(), outputApplicabilityRepository.findExcludedCurrentNodeIds(historicalQuestion));
	}

	@Test
	public void staleAllQuestionsCompletionCannotOverwriteNewerCurrentSyllabusSearch(FxRobot robot)
			throws TimeoutException {
		DelayedAllQuestionsSupplier allQuestions = new DelayedAllQuestionsSupplier(List.of(historicalQuestion));
		replaceSearchPane(robot, curriculumRepository, retrievalService, allQuestions);
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();

		// Start an All Questions request that deliberately remains in flight.
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, allQuestions::hasDelayStarted);

		// Returning to Current Syllabus supersedes the outstanding All Questions
		// request and restores Search directly from the fixed Working Subject.
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.CURRENT_SYLLABUS));
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
	public void staleHierarchyLoadCannotRestoreCurrentSyllabusControlsAfterScopeChange(FxRobot robot)
			throws TimeoutException {
		ComboBox<QuestionSearchScope> scopeBox = robot.lookup("#question-search-scope").queryComboBox();
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();

		// Begin a delayed hierarchy request beneath the automatically loaded Working
		// Subject.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		curriculumRepository.delayChildrenFor(currentUnit);
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, curriculumRepository::hasDelayStarted);

		// Changing Search scope invalidates the in-flight current-syllabus hierarchy
		// request and disables its navigation controls.
		robot.interact(() -> scopeBox.setValue(QuestionSearchScope.ALL_QUESTIONS));
		assertTrue(unitBox.isDisable());
		assertTrue(topicBox.isDisable());
		assertTrue(topicBox.getItems().isEmpty());

		// Complete the obsolete hierarchy request after the scope transition. Its late
		// result must not restore Topic navigation into All Questions.
		curriculumRepository.releaseDelayedChildren();
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, curriculumRepository::hasDelayFinished);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, scopeBox.getValue());
		assertTrue(unitBox.isDisable());
		assertTrue(topicBox.isDisable());
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

			// Both Questions are applicable so selection can move while the first preview
			// extraction is deliberately held in flight.
			return List.of(new QuestionApplicabilityMatch(first, currentDescriptor),
					new QuestionApplicabilityMatch(second, currentDescriptor));
		};
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		DelayedQuestionExtractor extractor = new DelayedQuestionExtractor(first.getRegions().getFirst());
		QuestionPreviewService replacementPreviewService = new QuestionPreviewService(new PdfStore(pdfRoot), extractor);
		replaceSearchPane(robot, curriculumRepository, service, replacementPreviewService);
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);

		// Working Subject Search supplies both Questions automatically.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 2);
		QuestionSearchResult firstResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == first.getId()).findFirst().orElseThrow();
		QuestionSearchResult secondResult = resultsList.getItems().stream()
				.filter(result -> result.question().getId() == second.getId()).findFirst().orElseThrow();
		robot.interact(() -> resultsList.getSelectionModel().select(firstResult));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, extractor::hasDelayStarted);

		// Selecting the second Question supersedes the delayed preview belonging to the
		// first Question.
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
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);

		// Navigate from the automatically loaded Working Subject to the Descriptor
		// whose
		// Search request will be deliberately delayed.
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

		// Move to a different Topic and classification while the previous Descriptor
		// Search is still outstanding.
		robot.interact(() -> topicBox.setValue(noMatchTopic));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> classificationBox.getItems().contains(noMatchDescriptor));
		robot.interact(() -> classificationBox.setValue(noMatchDescriptor));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> "No questions found.".equals(statusLabel.getText()));
		assertTrue(resultsList.getItems().isEmpty());

		// The obsolete Descriptor Search may finish, but it must not replace the newer
		// no-match state.
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

		// Search receives the same Subject that the application workspace would
		// provide.
		QuestionSearchPane pane = new QuestionSearchPane(chemistry, curriculumRepository, retrievalService,
				() -> List.of(historicalQuestion), previewService, outputApplicabilityRepository);
		stage.setScene(new Scene(pane, 700, 600));
		stage.show();
	}

	@Test
	public void subtopicSelectionExposesDescriptorChildren(FxRobot robot) throws TimeoutException {
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ComboBox<CurriculumNode> topicBox = robot.lookup("#question-search-topic").queryComboBox();
		ComboBox<CurriculumNode> classificationBox = robot.lookup("#question-search-classification").queryComboBox();
		ComboBox<CurriculumNode> descriptorBox = robot.lookup("#question-search-descriptor").queryComboBox();

		// Working Subject navigation is loaded automatically before lower hierarchy
		// selections are made.
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
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();

		// Wait for Working Subject navigation before narrowing Search to the Unit.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().contains(currentUnit));
		robot.interact(() -> unitBox.setValue(currentUnit));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertEquals(historicalQuestion.getId(), resultsList.getItems().getFirst().question().getId());
	}

	@Test
	public void workingSubjectTriggersSearchAutomatically(FxRobot robot) throws TimeoutException {
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();

		// Search must begin from the workspace Working Subject without exposing or
		// requiring a second Subject selector.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		assertTrue(robot.lookup("#question-search-subject").tryQuery().isEmpty());
		assertEquals(historicalQuestion.getId(), resultsList.getItems().getFirst().question().getId());
	}

	@Test
	public void workingSubjectUsesOnlyCurrentSyllabus(FxRobot robot) throws TimeoutException {
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();

		// Search loads the current-syllabus hierarchy directly from the authoritative
		// workspace Working Subject.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> unitBox.getItems().size() == 1);
		assertEquals(currentUnit, unitBox.getItems().getFirst());
		assertFalse(unitBox.getItems().contains(historicalUnit));
		assertFalse(unitBox.isDisable());
	}

	@Test
	public void workingSubjectWithoutCurrentSyllabusLeavesNavigationDisabled(FxRobot robot) throws TimeoutException {
		Subject historyOnly = new Subject(30, "History Only");
		SyllabusVersion historical = new SyllabusVersion(31, historyOnly, "2020", false);
		Unit historicalUnitOnly = new Unit(32, historical, "1", "Historical Unit", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(historyOnly),
				List.of(historical), List.of(historicalUnitOnly));
		QuestionRetrievalRepository emptyRetrieval = _ -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(emptyRetrieval,
				new CurriculumSearchNodeExpansionService(repository));

		// Search must derive its curriculum navigation from this explicit Working
		// Subject rather than from a Search-local Subject selector.
		replaceSearchPane(robot, historyOnly, repository, service);
		ComboBox<CurriculumNode> unitBox = robot.lookup("#question-search-unit").queryComboBox();
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label statusLabel = robot.lookup("#question-search-status").queryAs(Label.class);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> "No current syllabus available.".equals(statusLabel.getText()));
		assertTrue(robot.lookup("#question-search-subject").tryQuery().isEmpty());
		assertTrue(unitBox.getItems().isEmpty());
		assertTrue(unitBox.isDisable());
		assertTrue(resultsList.getItems().isEmpty());
	}

	@Test
	public void zeroApplicabilityPlacementsLeaveActionsDisabled(FxRobot robot) throws TimeoutException {
		QuestionRetrievalRepository repository = _ -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		replaceSearchPane(robot, curriculumRepository, service, () -> List.of(historicalQuestion));
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		Label status = robot.lookup("#question-search-output-applicability-status").queryAs(Label.class);
		Button include = robot.lookup("#question-search-output-include").queryButton();
		Button exclude = robot.lookup("#question-search-output-exclude").queryButton();
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		robot.interact(() -> results.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> "No current revision applicability.".equals(status.getText()));
		assertTrue(output.getItems().isEmpty());
		assertEquals(null, output.getSelectionModel().getSelectedItem());
		assertTrue(include.isDisable());
		assertTrue(exclude.isDisable());
	}

	@Test
	public void zeroRegionLegacyQuestionReportsNoStoredImage(FxRobot robot) throws TimeoutException {
		ListView<QuestionSearchResult> resultsList = robot.lookup("#question-search-results").queryListView();
		Label previewStatus = robot.lookup("#question-search-preview-status").queryAs(Label.class);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);

		// The initial Working Subject search supplies the Question without requiring a
		// separate Subject-selection action.
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> resultsList.getItems().size() == 1);
		robot.interact(() -> resultsList.getSelectionModel().selectFirst());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
				() -> "No stored question image.".equals(previewStatus.getText()));
		assertEquals(null, preview.getImage());
	}

	private void assertOversizedPreviewLayout(FxRobot robot) throws TimeoutException {
		VBox rightColumn = robot.lookup("#question-search-right-column").queryAs(VBox.class);
		ScrollPane previewScroll = robot.lookup("#question-search-preview-scroll").queryAs(ScrollPane.class);
		ImageView preview = robot.lookup("#question-search-preview").queryAs(ImageView.class);
		ListView<QuestionOutputApplicabilityRow> output = robot.lookup("#question-search-output-applicability")
				.queryListView();
		HBox actions = robot.lookup("#question-search-output-actions").queryAs(HBox.class);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> preview.getImage() != null
				&& output.getItems().size() == 2 && previewScroll.getViewportBounds().getWidth() > 0);
		assertTrue(preview.isPreserveRatio());
		assertEquals(ScrollPane.ScrollBarPolicy.NEVER, previewScroll.getHbarPolicy());

		// The vertical scrollbar always has reserved space, so
		// its appearance cannot change the image fit width.
		assertEquals(ScrollPane.ScrollBarPolicy.ALWAYS, previewScroll.getVbarPolicy());
		double[][] sizes = { { 1100, 760 }, { 900, 650 }, { 1140, 790 } };
		for (double[] size : sizes) {
			robot.interact(() -> {
				stage.setWidth(size[0]);
				stage.setHeight(size[1]);
			});
			WaitForAsyncUtils.waitForFxEvents();
			robot.interact(() -> {
				rightColumn.applyCss();
				rightColumn.layout();
				previewScroll.layout();
				assertTrue(output.getHeight() >= 90.0, "Applicability list has collapsed");
				assertTrue(actions.getBoundsInParent().getMinY() >= output.getBoundsInParent().getMaxY(),
						"Include/Exclude overlaps the placement list");
				double viewportWidth = previewScroll.getViewportBounds().getWidth();
				double viewportHeight = previewScroll.getViewportBounds().getHeight();
				assertTrue(viewportWidth > 0);
				assertTrue(viewportHeight > 0);
				assertEquals(viewportWidth, preview.getFitWidth(), 1.0);
				double fittedWidth = preview.getBoundsInLocal().getWidth();
				double fittedHeight = preview.getBoundsInLocal().getHeight();
				double expectedHeight = fittedWidth * preview.getImage().getHeight() / preview.getImage().getWidth();
				assertEquals(expectedHeight, fittedHeight, 2.0, "Preview must preserve the source aspect ratio");
				assertTrue(fittedHeight > viewportHeight, "Tall preview must remain vertically scrollable");

				// Repeated layout must converge to the same geometry.
				for (int pulse = 0; pulse < 8; pulse++) {
					rightColumn.requestLayout();
					rightColumn.layout();
					previewScroll.layout();
					assertEquals(fittedWidth, preview.getBoundsInLocal().getWidth(), 1.0);
					assertEquals(fittedHeight, preview.getBoundsInLocal().getHeight(), 1.0);
				}
			});
		}
	}

	private Path createDensePdf(Path path) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.setLineWidth(0.4f);

				// Use real vector drawing operations to create a
				// more demanding PDFBox rendering workload than
				// the previously empty one-page PDF.
				for (int row = 0; row < 45; row++) {
					for (int column = 0; column < 26; column++) {
						float x = 24 + column * 20;
						float y = 24 + row * 16;
						content.addRect(x, y, 12, 8);
					}
				}
				content.stroke();
			}
			document.save(path.toFile());
		}
		return path;
	}

	private Path createOnePagePdf(Path path) throws IOException {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(path.toFile());
		}
		return path;
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

	private boolean isDescendantOf(Node node, Node ancestor) {
		Node current = node;
		while (current != null) {
			if (current == ancestor) {
				return true;
			}
			current = current.getParent();
		}
		return false;
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

	private void openOversizedPreviewFixture(FxRobot robot, Question question, QuestionPreviewService service)
			throws TimeoutException {
		QuestionRetrievalRepository repository = nodes -> {
			List<QuestionApplicabilityMatch> matches = new ArrayList<>();
			if (nodes.contains(currentDescriptor)) {
				matches.add(new QuestionApplicabilityMatch(question, currentDescriptor));
			}
			if (nodes.contains(noMatchDescriptor)) {
				matches.add(new QuestionApplicabilityMatch(question, noMatchDescriptor));
			}
			return matches;
		};
		QuestionRetrievalService retrieval = new QuestionRetrievalService(repository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		robot.interact(() -> {
			QuestionSearchPane previous = (QuestionSearchPane) stage.getScene().getRoot();
			previous.dispose();
			QuestionSearchPane pane = new QuestionSearchPane(chemistry, curriculumRepository, retrieval,
					() -> List.of(question), service, outputApplicabilityRepository);
			stage.setScene(new Scene(pane, 1100, 760));
		});
		ComboBox<QuestionSearchScope> scope = robot.lookup("#question-search-scope").queryComboBox();
		ListView<QuestionSearchResult> results = robot.lookup("#question-search-results").queryListView();
		robot.interact(() -> scope.setValue(QuestionSearchScope.ALL_QUESTIONS));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().scope() == QuestionSearchScope.ALL_QUESTIONS);
		robot.interact(() -> results.getSelectionModel().selectFirst());
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service) {

		// Most Search tests use Chemistry as the application-level Working Subject.
		return replaceSearchPane(robot, chemistry, repository, service);
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service, QuestionPreviewService replacementPreviewService) {
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();

			// Keep Working Subject, complete-bank retrieval and output-applicability state
			// stable while this overload varies only the preview service.
			pane[0] = new QuestionSearchPane(chemistry, repository, service, () -> List.of(historicalQuestion),
					replacementPreviewService, outputApplicabilityRepository);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		return pane[0];
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service, Supplier<List<Question>> allQuestionsSupplier) {
		return replaceSearchPane(robot, repository, service, allQuestionsSupplier,
				QuestionSearchNarrowing.unrestricted());
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, InMemoryCurriculumRepository repository,
			QuestionRetrievalService service, Supplier<List<Question>> allQuestionsSupplier,
			QuestionSearchNarrowing searchNarrowing) {
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();

			// Launch narrowing is independent of the ordinary Search scope and therefore
			// belongs to construction rather than to one particular retrieval action.
			pane[0] = new QuestionSearchPane(chemistry, repository, service, allQuestionsSupplier, previewService,
					outputApplicabilityRepository, searchNarrowing);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		return pane[0];
	}

	private QuestionSearchPane replaceSearchPane(FxRobot robot, Subject workingSubject,
			InMemoryCurriculumRepository repository, QuestionRetrievalService service) {
		QuestionSearchPane[] pane = new QuestionSearchPane[1];
		robot.interact(() -> {
			QuestionSearchPane existing = (QuestionSearchPane) stage.getScene().getRoot();
			existing.dispose();

			// Construct Search with the supplied application-level Working Subject so
			// tests can exercise Subjects other than the normal Chemistry fixture.
			pane[0] = new QuestionSearchPane(workingSubject, repository, service, () -> List.of(historicalQuestion),
					previewService, outputApplicabilityRepository);
			stage.setScene(new Scene(pane[0], 700, 600));
		});
		return pane[0];
	}

	private void selectMetadataFilter(FxRobot robot, ComboBox<?> box, String label) {
		int index = -1;
		for (int i = 0; i < box.getItems().size(); i++) {
			if (label.equals(box.getItems().get(i).toString())) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			throw new AssertionError("Filter choice unavailable: " + label);
		}
		int selectedIndex = index;
		robot.interact(() -> box.getSelectionModel().select(selectedIndex));
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

			// The test observes the replacement Subject list in the UI, so a separate
			// completion latch for this background lookup is unnecessary.
			return super.findAllSubjects();
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

		private void failChildrenFor(CurriculumNode parent) {
			failingParent = parent;
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

	private static final class DelayedQuestionExtractor extends QuestionExtractor {

		private final QuestionRegion delayedRegion;
		private final CountDownLatch delayStarted = new CountDownLatch(1);
		private final CountDownLatch delayRelease = new CountDownLatch(1);
		private final CountDownLatch delayFinished = new CountDownLatch(1);

		private DelayedQuestionExtractor(QuestionRegion delayedRegion) {
			this.delayedRegion = delayedRegion;
		}

		@Override
		public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
			if (sameRegion(region, delayedRegion)) {
				delayStarted.countDown();
				boolean released = false;
				while (!released) {
					try {
						delayRelease.await();
						released = true;
					} catch (InterruptedException exception) {

						// Ignore cancellation deliberately so the obsolete preview can finish
						// after a newer selection or disposal has already invalidated it.
					}
				}
				delayFinished.countDown();

				// The delayed preview is deliberately distinguishable from the newer one.
				return new BufferedImage(11, 11, BufferedImage.TYPE_INT_RGB);
			}

			// Non-delayed Question content completes immediately with a distinguishable
			// size so stale-preview tests can prove which result reached the ImageView.
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

		private boolean sameRegion(QuestionRegion first, QuestionRegion second) {
			return first.booklet().getId() == second.booklet().getId() && first.pageNumber() == second.pageNumber()
					&& Double.compare(first.x(), second.x()) == 0 && Double.compare(first.y(), second.y()) == 0
					&& Double.compare(first.width(), second.width()) == 0
					&& Double.compare(first.height(), second.height()) == 0;
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

	private static final class LatestPreviewDebouncer implements AutoCloseable {

		private final AtomicInteger active = new AtomicInteger();
		private final AtomicInteger completed = new AtomicInteger();
		private final AtomicLong currentQuestionId = new AtomicLong(-1);
		private final int delayMilliseconds;
		private final AtomicInteger peakConcurrent = new AtomicInteger();
		private final AtomicInteger skipped = new AtomicInteger();
		private final ScheduledExecutorService timer = Executors
				.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
		private ScheduledFuture<?> pending;

		private LatestPreviewDebouncer(int delayMilliseconds) {
			if (delayMilliseconds < 0) {
				throw new IllegalArgumentException("Delay must not be negative");
			}
			this.delayMilliseconds = delayMilliseconds;
		}

		@Override
		public void close() {
			timer.shutdownNow();
		}

		private int active() {
			return active.get();
		}

		private int completed() {
			return completed.get();
		}

		private boolean isCurrent(long questionId) {
			return currentQuestionId.get() == questionId;
		}

		private int peakConcurrent() {
			return peakConcurrent.get();
		}

		private synchronized void select(long questionId, Runnable rendering) {
			currentQuestionId.set(questionId);

			// Replace a pending request before it starts expensive
			// PDF work. Never interrupt an already-running renderer.
			if (pending != null && pending.cancel(false)) {
				skipped.incrementAndGet();
			}
			pending = timer.schedule(() -> {
				if (!isCurrent(questionId)) {
					return;
				}
				Thread.ofVirtual().name("coalesced-question-preview").start(() -> {
					if (!isCurrent(questionId)) {
						return;
					}
					int concurrent = active.incrementAndGet();
					peakConcurrent.accumulateAndGet(concurrent, Math::max);
					try {
						rendering.run();
					} finally {
						active.decrementAndGet();
						completed.incrementAndGet();
					}
				});
			}, delayMilliseconds, TimeUnit.MILLISECONDS);
		}

		private int skipped() {
			return skipped.get();
		}
	}

	private static final class PostWorkGate {

		private final Set<Long> heldQuestionIds;
		private final CountDownLatch reached;
		private final CountDownLatch releaseLatch = new CountDownLatch(1);

		private PostWorkGate(Set<Long> heldQuestionIds) {
			this.heldQuestionIds = Set.copyOf(heldQuestionIds);
			this.reached = new CountDownLatch(heldQuestionIds.size());
		}

		private void awaitAfterWork(long questionId) {
			if (!heldQuestionIds.contains(questionId)) {
				return;
			}

			// This signal occurs only after the actual PDF or
			// SQLite work has finished.
			reached.countDown();
			boolean released = false;
			while (!released) {
				try {
					releaseLatch.await();
					released = true;
				} catch (InterruptedException ignored) {

					// Preserve the already-executing stale-worker
					// scenario without interrupting PDFBox or SQLite.
				}
			}
		}

		private int heldCount() {
			return heldQuestionIds.size() - (int) reached.getCount();
		}

		private void release() {
			releaseLatch.countDown();
		}

		private long remaining() {
			return reached.getCount();
		}
	}

	private static final class RapidSelectionWorkProbe {

		private final AtomicInteger active = new AtomicInteger();
		private final AtomicInteger completed = new AtomicInteger();
		private final AtomicInteger discarded = new AtomicInteger();
		private final AtomicLong discardedNanos = new AtomicLong();
		private final long finalQuestionId;
		private final AtomicInteger peakConcurrent = new AtomicInteger();
		private final AtomicLong sampledHeapBytes = new AtomicLong();
		private final CountDownLatch release = new CountDownLatch(1);
		private volatile long selectedQuestionId = -1;
		private final AtomicInteger started = new AtomicInteger();

		private RapidSelectionWorkProbe(long finalQuestionId) {
			this.finalQuestionId = finalQuestionId;
		}

		private int active() {
			return active.get();
		}

		private int completed() {
			return completed.get();
		}

		private int discarded() {
			return discarded.get();
		}

		private double discardedMilliseconds() {
			return discardedNanos.get() / 1_000_000.0;
		}

		private <T> T measure(long questionId, Supplier<T> work) {
			long startNanos = System.nanoTime();
			started.incrementAndGet();
			int concurrent = active.incrementAndGet();
			peakConcurrent.accumulateAndGet(concurrent, Math::max);
			sampleHeap();
			try {
				if (questionId != finalQuestionId) {

					// Deliberately hold obsolete operations in flight.
					// This is a controlled contention experiment, not
					// a simulation of actual SQLite/PDF CPU cost.
					boolean released = false;
					while (!released) {
						try {
							release.await();
							released = true;
						} catch (InterruptedException ignored) {

							// A cancelled JavaFX Task can still have
							// an executing background operation.
						}
					}
				}
				return work.get();
			} finally {
				sampleHeap();
				completed.incrementAndGet();
				if (selectedQuestionId != questionId) {
					discarded.incrementAndGet();
					discardedNanos.addAndGet(System.nanoTime() - startNanos);
				}
				active.decrementAndGet();
			}
		}

		private int peakConcurrent() {
			return peakConcurrent.get();
		}

		private void release() {
			release.countDown();
		}

		private long sampledHeapBytes() {
			return sampledHeapBytes.get();
		}

		private void sampleHeap() {
			Runtime runtime = Runtime.getRuntime();
			long used = runtime.totalMemory() - runtime.freeMemory();
			sampledHeapBytes.accumulateAndGet(used, Math::max);
		}

		private void select(long questionId) {
			selectedQuestionId = questionId;
		}

		private int started() {
			return started.get();
		}
	}

	private static final class RealSelectionWorkProbe {

		private final AtomicInteger active = new AtomicInteger();
		private final AtomicInteger completed = new AtomicInteger();
		private final AtomicInteger peakConcurrent = new AtomicInteger();
		private final AtomicLong sampledHeapBytes = new AtomicLong();
		private final AtomicLong selectedQuestionId = new AtomicLong(-1);
		private final AtomicInteger started = new AtomicInteger();
		private final AtomicInteger superseded = new AtomicInteger();
		private final AtomicLong supersededNanos = new AtomicLong();
		private final AtomicLong totalNanos = new AtomicLong();

		private int active() {
			return active.get();
		}

		private int completed() {
			return completed.get();
		}

		private <T> T measure(long questionId, Callable<T> action) throws Exception {
			long startedAt = System.nanoTime();
			started.incrementAndGet();
			int concurrent = active.incrementAndGet();
			peakConcurrent.accumulateAndGet(concurrent, Math::max);
			sampleHeap();
			try {
				return action.call();
			} finally {
				long elapsed = System.nanoTime() - startedAt;
				totalNanos.addAndGet(elapsed);

				// Classify work by whether its Question was still the
				// latest requested selection when execution finished.
				if (selectedQuestionId.get() != questionId) {
					superseded.incrementAndGet();
					supersededNanos.addAndGet(elapsed);
				}
				sampleHeap();
				completed.incrementAndGet();
				active.decrementAndGet();
			}
		}

		private int peakConcurrent() {
			return peakConcurrent.get();
		}

		private long sampledHeapBytes() {
			return sampledHeapBytes.get();
		}

		private void sampleHeap() {
			Runtime runtime = Runtime.getRuntime();
			long used = runtime.totalMemory() - runtime.freeMemory();
			sampledHeapBytes.accumulateAndGet(used, Math::max);
		}

		private void select(long questionId) {
			selectedQuestionId.set(questionId);
		}

		private int started() {
			return started.get();
		}

		private int superseded() {
			return superseded.get();
		}

		private double supersededMilliseconds() {
			return supersededNanos.get() / 1_000_000.0;
		}

		private double totalMilliseconds() {
			return totalNanos.get() / 1_000_000.0;
		}
	}
}
