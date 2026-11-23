package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusAuditService;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentExporter;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentIssuanceService;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentLedger;
import au.edu.eq.questionbank.ui.audit.CorpusDashboardPane;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CorpusDashboardSnapshotReuseTest extends QuestionBankApplicationUiTestBase {

	@Test
	@SuppressWarnings("unchecked")
	void dashboardRefreshUpdatesAssignmentLocksWithoutReplacingPane(FxRobot robot) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		Subject chemistry = curriculum.findAllSubjects().stream()
				.filter(subject -> "Chemistry".equals(subject.getName())).findFirst().orElseThrow();
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		Exam exam = examWriter.createExam(chemistry, "QCAA", 2026, "Assignment refresh test");

		// Use a valid PDF in canonical managed Subject storage.
		ManagedDataLayout layout = new ManagedDataLayout(applicationConfig.dataRoot());
		String relativePath = "subjects/Chemistry/exams/assignment-refresh.pdf";
		Path pdf = layout.resolve(relativePath);
		Files.createDirectories(pdf.getParent());
		Files.copy(examPdf, pdf);
		ExamBooklet booklet = examWriter.insertExamBooklet(exam, examWriter.insertSourceDocument(relativePath),
				"Paper 1", ExamBookletQuestionFormat.MIXED);
		examWriter.updateExamAssetExpectations(exam, 1, 0);
		SyllabusVersion syllabus = curriculum.findVersionsForSubject(chemistry).stream()
				.filter(SyllabusVersion::isCurrent).findFirst().orElseThrow();
		Descriptor descriptor = (Descriptor) curriculum.findReviewNodes(syllabus).stream()
				.filter(node -> node.getLevel() == CurriculumLevel.DESCRIPTOR).findFirst().orElseThrow();
		Question question = new SqliteQuestionRepository(database).save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), descriptor, false);
		ComboBox<Subject> subjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);
		robot.interact(() -> subjectBox.getSelectionModel().select(chemistry));
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
			Subject current = field(application, "workingSubject", Subject.class);
			return current != null && current.getId() == chemistry.getId();
		});
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshAndShowCorpusDashboardHome", new Class<?>[0]);
			return null;
		}).get();

		// Wait for the entire Dashboard to be published and laid out,
		// not merely for the Exam table to contain its persisted row.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(() -> {
			CorpusDashboardPane current = field(application, "corpusDashboardPane", CorpusDashboardPane.class);
			if (current == null || current.getScene() == null) {
				return false;
			}
			current.applyCss();
			current.layout();
			TableView<?> examTable = field(current, "examTable", TableView.class);
			Button issueButton = field(current, "createContributorAssignmentButton", Button.class);
			Button closeButton = field(current, "closeContributorAssignmentButton", Button.class);
			Button manageButton = field(current, "manageExamAssetsButton", Button.class);

			// The Exam table may be populated before the
			// ScrollPane has attached its action controls.
			boolean examPublished = examTable.getItems().stream()
					.anyMatch(item -> item instanceof ExamCorpusStatus status && status.exam().getId() == exam.getId());
			return examPublished && issueButton.getScene() == current.getScene()
					&& closeButton.getScene() == current.getScene() && manageButton.getScene() == current.getScene();
		}).get());
		CorpusDashboardPane dashboard = field(application, "corpusDashboardPane", CorpusDashboardPane.class);

		// Resolve the actual Dashboard-owned controls rather than
		// depending on ScrollPane viewport lookup timing.
		TableView<ExamCorpusStatus> exams = field(dashboard, "examTable", TableView.class);
		TableView<BookletCorpusStatus> booklets = field(dashboard, "bookletTable", TableView.class);
		TableView<QuestionCorpusWorkItem> questionWork = field(dashboard, "questionTable", TableView.class);
		Button issue = field(dashboard, "createContributorAssignmentButton", Button.class);
		Button close = field(dashboard, "closeContributorAssignmentButton", Button.class);
		Button manage = field(dashboard, "manageExamAssetsButton", Button.class);
		robot.interact(() -> {
			exams.getSelectionModel().selectFirst();
			booklets.getSelectionModel().selectFirst();
			questionWork.getSelectionModel().select(questionWork.getItems().stream()
					.filter(item -> item.question().getId() == question.getId()).findFirst().orElseThrow());
		});
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> evaluateOnFx(robot, () -> !issue.isDisabled()));
		assertTrue(close.isDisabled());
		UUID assignmentId = UUID.randomUUID();
		Path archive = databasePath.getParent().resolve("assignment-refresh.examwork");
		var request = new WorkAssignmentExporter.Request(exam, assignmentId, UUID.randomUUID(),
				"Refresh Test Contributor", false, archive);
		new WorkAssignmentIssuanceService(database, layout).issue(request);

		// Exercise the same ordinary in-place refresh used after
		// successful coordinator assignment issuance.
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshCorpusDashboardHome", new Class<?>[] { long.class }, -1L);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> issue.isDisabled() && !close.isDisabled() && manage.isDisabled()));
		assertSame(dashboard, field(application, "corpusDashboardPane", CorpusDashboardPane.class));
		PseudoClass assigned = PseudoClass.getPseudoClass("exam-assigned");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot,
						() -> exams.lookupAll(".table-row-cell").stream().filter(TableRow.class::isInstance)
								.map(TableRow.class::cast)
								.anyMatch(row -> row.getItem() instanceof ExamCorpusStatus status
										&& status.exam().getId() == exam.getId()
										&& row.getPseudoClassStates().contains(assigned))));
		assertTrue(evaluateOnFx(robot,
				() -> exams.getSelectionModel().getSelectedItem().exam().getId() == exam.getId()
						&& booklets.getSelectionModel().getSelectedItem().booklet().getId() == booklet.getId()
						&& questionWork.getSelectionModel().getSelectedItem().question().getId() == question.getId()));

		// Cancel the assignment through its authoritative ledger,
		// then refresh without changing the Working Subject.
		new WorkAssignmentLedger(database).closeWithoutReturn(assignmentId, "Refresh regression test");
		WaitForAsyncUtils.asyncFx(() -> {
			invoke(application, "refreshCorpusDashboardHome", new Class<?>[] { long.class }, -1L);
			return null;
		}).get();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> evaluateOnFx(robot, () -> !issue.isDisabled() && close.isDisabled() && !manage.isDisabled()));
		assertSame(dashboard, field(application, "corpusDashboardPane", CorpusDashboardPane.class));
		assertTrue(evaluateOnFx(robot,
				() -> exams.lookupAll(".table-row-cell").stream().filter(TableRow.class::isInstance)
						.map(TableRow.class::cast)
						.noneMatch(row -> row.getItem() instanceof ExamCorpusStatus status
								&& status.exam().getId() == exam.getId()
								&& row.getPseudoClassStates().contains(assigned))));
		assertTrue(evaluateOnFx(robot,
				() -> exams.getSelectionModel().getSelectedItem().exam().getId() == exam.getId()
						&& booklets.getSelectionModel().getSelectedItem().booklet().getId() == booklet.getId()
						&& questionWork.getSelectionModel().getSelectedItem().question().getId() == question.getId()));
	}

	@Test
	void dashboardSnapshotReadsOnceAndReflectsPersistenceChanges(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		Subject chemistry = field(application, "workingSubject", Subject.class);
		assertNotNull(chemistry);
		assertEquals("Chemistry", chemistry.getName());
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteQuestionRepository persistedQuestions = new SqliteQuestionRepository(database);
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);

		// The existing Exam comes from the ordinary UI
		// setup workflow.
		Exam populatedExam = examWriter.findExamsForSubject(chemistry).getFirst();
		ExamBooklet booklet = examWriter.findExamBooklets(populatedExam).getFirst();

		// An Exam without expectations, booklets or
		// Questions must remain visible in the audit.
		Exam emptyExam = examWriter.createExam(chemistry, "QCAA", 2026, "Not yet captured");

		// A different Subject is present in the same
		// database and must not enter Chemistry audit.
		Subject physics = curriculum.findAllSubjects().stream().filter(subject -> "Physics".equals(subject.getName()))
				.findFirst().orElseThrow();
		Exam physicsExam = examWriter.createExam(physics, "QCAA", 2025, "Physics assessment");
		ExamBooklet physicsBooklet = examWriter.insertExamBooklet(physicsExam,
				examWriter.insertSourceDocument("Physics/2025/paper1.pdf"), "Paper 1", ExamBookletQuestionFormat.MIXED);
		SyllabusVersion chemistrySyllabus = curriculum.findVersionsForSubject(chemistry).stream()
				.filter(SyllabusVersion::isCurrent).findFirst().orElseThrow();
		Descriptor chemistryDescriptor = (Descriptor) curriculum.findReviewNodes(chemistrySyllabus).stream()
				.filter(node -> node.getLevel() == CurriculumLevel.DESCRIPTOR).findFirst().orElseThrow();
		SyllabusVersion physicsSyllabus = curriculum.findVersionsForSubject(physics).stream()
				.filter(SyllabusVersion::isCurrent).findFirst().orElseThrow();
		Subtopic physicsSubtopic = (Subtopic) curriculum.findReviewNodes(physicsSyllabus).stream()
				.filter(node -> node.getLevel() == CurriculumLevel.SUBTOPIC).findFirst().orElseThrow();

		// Spy on the same QuestionRepository boundary
		// consumed by the Dashboard and audit service.
		QuestionRepository delegate = field(application, "questionRepository", QuestionRepository.class);
		AtomicInteger findAllCalls = new AtomicInteger();
		QuestionRepository countingRepository = countingRepository(delegate, findAllCalls);
		setField(application, "questionRepository", countingRepository);
		ExamCorpusAuditService auditService = new ExamCorpusAuditService(examWriter, answerWriter, countingRepository,
				new PdfStore(pdfDataRoot));

		// Snapshot 1: no captured Questions.
		Object initial = loadDashboardSnapshot(auditService, chemistry);
		assertEquals(1, findAllCalls.get());
		List<ExamCorpusStatus> initialStatuses = auditStatuses(initial);
		assertEquals(2, initialStatuses.size());
		assertTrue(displayedQuestions(initial).isEmpty());
		assertEquals(Set.of(populatedExam.getId(), emptyExam.getId()), examIds(initialStatuses));
		assertTrue(initialStatuses.stream().allMatch(status -> status.questionSummary().totalQuestions() == 0));
		assertTrue(initialStatuses.stream().filter(status -> status.exam().getId() == emptyExam.getId()).findFirst()
				.orElseThrow().bookletStatuses().isEmpty());

		// Persist one Question in each Subject.
		Question chemistryQuestion = persistedQuestions.save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), chemistryDescriptor, false);
		Question physicsQuestion = persistedQuestions.save(physicsBooklet, "Q1", "", 2,
				List.of(new QuestionRegion(physicsBooklet, 1, 0.10, 0.10, 0.50, 0.20)), physicsSubtopic, false);

		// Snapshot 2 must reconstruct the bank again,
		// but only once, including the new Questions.
		Object afterCapture = loadDashboardSnapshot(auditService, chemistry);
		assertEquals(2, findAllCalls.get());
		List<Question> displayed = displayedQuestions(afterCapture);

		// The application-level snapshot retains the
		// complete bank. CorpusDashboardPane applies
		// its existing Working Subject display filtering.
		assertEquals(Set.of(chemistryQuestion.getId(), physicsQuestion.getId()),
				displayed.stream().map(Question::getId).collect(java.util.stream.Collectors.toSet()));
		List<ExamCorpusStatus> capturedStatuses = auditStatuses(afterCapture);
		assertEquals(2, capturedStatuses.size());
		ExamCorpusStatus populated = capturedStatuses.stream()
				.filter(status -> status.exam().getId() == populatedExam.getId()).findFirst().orElseThrow();
		assertEquals(1, populated.questionSummary().totalQuestions());
		assertEquals(1, populated.bookletStatuses().getFirst().questionPartCount());
		assertEquals(0, populated.bookletStatuses().getFirst().questionsWithoutDescriptorCount());
		ExamCorpusStatus empty = capturedStatuses.stream().filter(status -> status.exam().getId() == emptyExam.getId())
				.findFirst().orElseThrow();
		assertEquals(0, empty.questionSummary().totalQuestions());
		assertTrue(empty.bookletStatuses().isEmpty());

		// Change the persisted classification without
		// touching the previously loaded snapshot.
		Topic currentTopic = (Topic) chemistryDescriptor.getParent();
		Subtopic unclassified = new SqliteCurriculumWriter(database).insertSubtopic(currentTopic, "1.1.2",
				"Needs descriptor", 2);
		persistedQuestions.updateClassification(chemistryQuestion.getId(), unclassified);

		// Snapshot 3 must reflect the newly persisted
		// classification and revised descriptor count.
		Object afterClassification = loadDashboardSnapshot(auditService, chemistry);
		assertEquals(3, findAllCalls.get());
		Question refreshedQuestion = displayedQuestions(afterClassification).stream()
				.filter(question -> question.getId() == chemistryQuestion.getId()).findFirst().orElseThrow();
		assertEquals(unclassified.getId(), refreshedQuestion.getClassification().getId());
		ExamCorpusStatus refreshedStatus = auditStatuses(afterClassification).stream()
				.filter(status -> status.exam().getId() == populatedExam.getId()).findFirst().orElseThrow();
		assertEquals(1, refreshedStatus.bookletStatuses().getFirst().questionsWithoutDescriptorCount());

		// The independent legacy audit entry point
		// must remain equivalent. It deliberately
		// acquires its own fresh repository snapshot.
		List<ExamCorpusStatus> legacyStatuses = auditService.assessSubject(chemistry);
		assertEquals(4, findAllCalls.get());
		assertAuditEquivalent(legacyStatuses, auditStatuses(afterClassification));
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	private void assertAuditEquivalent(List<ExamCorpusStatus> expected, List<ExamCorpusStatus> actual) {
		assertEquals(expected.size(), actual.size());
		for (int index = 0; index < expected.size(); index++) {
			ExamCorpusStatus before = expected.get(index);
			ExamCorpusStatus after = actual.get(index);
			assertEquals(before.exam().getId(), after.exam().getId());
			assertEquals(before.declaredCaptureState(), after.declaredCaptureState());
			assertEquals(before.assetExpectations(), after.assetExpectations());
			assertEquals(before.questionSummary(), after.questionSummary());
			assertEquals(before.mcqExplanationSummary(), after.mcqExplanationSummary());
			assertEquals(before.findings(), after.findings());
			assertEquals(before.requiresAttention(), after.requiresAttention());
			assertEquals(before.isReadyForCompletion(), after.isReadyForCompletion());
			assertEquals(before.bookletStatuses().size(), after.bookletStatuses().size());
			for (int bookletIndex = 0; bookletIndex < before.bookletStatuses().size(); bookletIndex++) {
				BookletCorpusStatus oldBooklet = before.bookletStatuses().get(bookletIndex);
				BookletCorpusStatus newBooklet = after.bookletStatuses().get(bookletIndex);
				assertEquals(oldBooklet.booklet().getId(), newBooklet.booklet().getId());
				assertEquals(oldBooklet.expectedTopLevelQuestionCount(), newBooklet.expectedTopLevelQuestionCount());
				assertEquals(oldBooklet.questionPartCount(), newBooklet.questionPartCount());
				assertEquals(oldBooklet.encounteredTopLevelQuestionCount(),
						newBooklet.encounteredTopLevelQuestionCount());
				assertEquals(oldBooklet.questionsWithoutDescriptorCount(),
						newBooklet.questionsWithoutDescriptorCount());
				assertEquals(oldBooklet.questionPdfAvailable(), newBooklet.questionPdfAvailable());
				assertEquals(oldBooklet.questionSummary(), newBooklet.questionSummary());
				assertEquals(oldBooklet.mcqExplanationCoverage(), newBooklet.mcqExplanationCoverage());
				assertEquals(oldBooklet.findings(), newBooklet.findings());
			}
		}
	}

	private List<ExamCorpusStatus> auditStatuses(Object snapshot) throws Exception {
		return snapshotItems(snapshot, "examStatuses").stream().map(ExamCorpusStatus.class::cast).toList();
	}

	private QuestionRepository countingRepository(QuestionRepository delegate, AtomicInteger findAllCalls) {
		return (QuestionRepository) Proxy.newProxyInstance(QuestionRepository.class.getClassLoader(),
				new Class<?>[] { QuestionRepository.class }, (_, method, arguments) -> {
					if ("findAll".equals(method.getName())) {
						findAllCalls.incrementAndGet();
					}
					try {
						return method.invoke(delegate, arguments);
					} catch (InvocationTargetException failure) {
						throw failure.getCause();
					}
				});
	}

	private List<Question> displayedQuestions(Object snapshot) throws Exception {
		return snapshotItems(snapshot, "questions").stream().map(Question.class::cast).toList();
	}

	private Set<Long> examIds(List<ExamCorpusStatus> statuses) {
		return statuses.stream().map(status -> status.exam().getId()).collect(java.util.stream.Collectors.toSet());
	}

	private Object loadDashboardSnapshot(ExamCorpusAuditService auditService, Subject subject) throws Exception {

		// Invoke the actual production snapshot builder.
		// It performs persistence work and does not need
		// to access JavaFX controls.
		return invoke(application, "loadCorpusDashboardSnapshot",
				new Class<?>[] { ApplicationConfig.class, ExamCorpusAuditService.class, Subject.class, long.class },
				applicationConfig, auditService, subject, 0L);
	}

	private List<?> snapshotItems(Object snapshot, String component) throws Exception {
		Method accessor = snapshot.getClass().getDeclaredMethod(component);
		accessor.setAccessible(true);
		return (List<?>) accessor.invoke(snapshot);
	}
}
