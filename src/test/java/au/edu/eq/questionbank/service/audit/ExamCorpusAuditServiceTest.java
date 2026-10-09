package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class ExamCorpusAuditServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void invalidStoredQuestionPdfPathBecomesFindingWithoutAbortingAudit() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("invalid-pdf-path.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		Exam exam = examWriter.createExam(chemistry, "QCAA", 2025, "External Assessment");

		// Persistence historically requires only a non-blank SourceDocument path. An
		// unsafe legacy value must therefore be reportable rather than crashing audit.
		SourceDocument invalidDocument = examWriter.insertSourceDocument("../outside-managed-root.pdf");
		ExamBooklet booklet = examWriter.insertExamBooklet(exam, invalidDocument, "Paper 1",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		ExamCorpusAuditService service = new ExamCorpusAuditService(examWriter, answerWriter, questionRepository,
				new PdfStore(tempDirectory.resolve("pdf-invalid-path")));
		List<ExamCorpusStatus> statuses = service.assessSubject(chemistry);

		// The invalid source path becomes a local booklet finding; it does not make the
		// complete Subject audit unavailable.
		assertEquals(1, statuses.size());
		ExamCorpusStatus status = statuses.getFirst();
		assertEquals(1, status.bookletStatuses().size());
		BookletCorpusStatus bookletStatus = status.bookletStatuses().getFirst();
		assertEquals(booklet.getId(), bookletStatus.booklet().getId());
		assertFalse(bookletStatus.questionPdfAvailable());
		assertTrue(bookletStatus.hasFinding(BookletCorpusFinding.MISSING_QUESTION_PDF));

		// No expected Question count was recorded, so the missing file must not be
		// confused with an expected-versus-encountered count mismatch.
		assertNull(bookletStatus.expectedTopLevelQuestionCount());
		assertFalse(bookletStatus.hasFinding(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));
		assertTrue(status.requiresAttention());
	}

	@Test
	void loadsSubjectAuditFromPersistedStructureQuestionsAssignmentsAndFiles() throws Exception {
		Path databasePath = tempDirectory.resolve("corpus-audit.db");
		Path pdfRoot = tempDirectory.resolve("pdf");
		Files.createDirectories(pdfRoot);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = curriculumWriter.insertDescriptor(topic, "1.1.1", "Descriptor", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		Exam exam = examWriter.createExam(chemistry, "QCAA", 2025, "External Assessment");
		SourceDocument paper1Document = examWriter.insertSourceDocument("Chemistry/QCAA/2025/paper1.pdf");
		ExamBooklet paper1 = examWriter.insertExamBooklet(exam, paper1Document, "Paper 1",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, 1);
		SourceDocument paper2Document = examWriter.insertSourceDocument("Chemistry/QCAA/2025/paper2.pdf");
		ExamBooklet paper2 = examWriter.insertExamBooklet(exam, paper2Document, "Paper 2",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, 1);

		// The persisted Exam planning metadata exactly matches the structural assets
		// created for this fixture.
		examWriter.updateExamAssetExpectations(exam, 2, 1);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(exam, "Marking guide",
				"Chemistry/QCAA/2025/answers.pdf");
		answerWriter.assignAnswerFile(paper1, answerFile);
		PdfStore pdfStore = new PdfStore(pdfRoot);
		Path paper1Pdf = pdfStore.resolve(paper1Document.getRelativePath());
		Files.createDirectories(paper1Pdf.getParent());
		Files.writeString(paper1Pdf, "test PDF bytes");

		// Deliberately do not create Paper 2 on disk. Its persisted SourceDocument
		// remains valid metadata while the audit must report the physical PDF missing.
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		questionRepository.save(paper1, "1", "", 2, List.of(new QuestionRegion(paper1, 1, 0.10, 0.10, 0.70, 0.20)),
				descriptor, false, null, null, QuestionResponseType.WRITTEN_RESPONSE);

		// Complete the Exam only after all intended structural setup has been
		// persisted.
		// The missing Answer and missing Paper 2 PDF must not reverse this declaration.
		examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE);
		Subject physics = curriculumWriter.insertSubject("Physics");

		// A different Subject Exam proves the service honours the authoritative
		// Subject boundary rather than returning every Exam in the database.
		examWriter.createExam(physics, "QCAA", 2025, "External Assessment");
		ExamCorpusAuditService service = new ExamCorpusAuditService(examWriter, answerWriter, questionRepository,
				pdfStore);
		List<ExamCorpusStatus> statuses = service.assessSubject(chemistry);
		assertEquals(1, statuses.size());
		ExamCorpusStatus status = statuses.getFirst();
		assertEquals(exam.getId(), status.exam().getId());
		assertEquals(ExamCaptureState.COMPLETE, status.declaredCaptureState());

		// Asset expectations are loaded directly from persisted Exam planning and
		// currently registered structural assets.
		assertEquals(Integer.valueOf(2), status.assetExpectations().expectedQuestionBookletCount());
		assertEquals(2, status.assetExpectations().availableQuestionBookletCount());
		assertEquals(Integer.valueOf(1), status.assetExpectations().expectedAnswerFileCount());
		assertEquals(1, status.assetExpectations().availableAnswerFileCount());
		assertTrue(status.findings().isEmpty());
		assertEquals(2, status.bookletStatuses().size());
		BookletCorpusStatus paper1Status = status.bookletStatuses().stream()
				.filter(bookletStatus -> bookletStatus.booklet().getId() == paper1.getId()).findFirst().orElseThrow();
		BookletCorpusStatus paper2Status = status.bookletStatuses().stream()
				.filter(bookletStatus -> bookletStatus.booklet().getId() == paper2.getId()).findFirst().orElseThrow();

		// Physical availability and AnswerFile assignment come from their respective
		// authoritative boundaries rather than being inferred from Question content.
		assertTrue(paper1Status.questionPdfAvailable());
		assertEquals(answerFile.getId(), paper1Status.assignedAnswerFile().getId());
		assertFalse(paper2Status.questionPdfAvailable());
		assertTrue(paper2Status.hasFinding(BookletCorpusFinding.MISSING_QUESTION_PDF));
		assertNull(paper2Status.assignedAnswerFile());

		// Paper 1 has one captured part/top-level Question. Paper 2 expects one but has
		// encountered none, so only Paper 2 receives the advisory count mismatch.
		assertEquals(1, paper1Status.questionPartCount());
		assertEquals(1, paper1Status.encounteredTopLevelQuestionCount());
		assertFalse(paper1Status.hasFinding(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));
		assertEquals(0, paper2Status.questionPartCount());
		assertEquals(0, paper2Status.encounteredTopLevelQuestionCount());
		assertTrue(paper2Status.hasFinding(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));

		// The single persisted written-response Question has no Answer, and that
		// Question-level finding rolls through booklet and Exam aggregation.
		assertEquals(1, status.questionSummary().totalQuestions());
		assertEquals(1, status.questionSummary().incompleteQuestions());
		assertEquals(1, status.questionSummary().missingAnswer());
		assertTrue(status.requiresAttention());

		// Audit attention never mutates the user's persisted structural declaration.
		assertEquals(ExamCaptureState.COMPLETE, status.exam().getCaptureState());
	}

	@Test
	void newlyCreatedExamRemainsInAuditThroughInitialSetup() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("new-exam-visibility.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		Subject physics = curriculumWriter.insertSubject("Physics");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		ExamCorpusAuditService service = new ExamCorpusAuditService(examWriter, answerWriter, questionRepository,
				new PdfStore(tempDirectory.resolve("new-exam-pdf")));

		// Stage 1: Persist an Exam without expectations,
		// booklets or Questions.
		Exam exam = examWriter.createExam(chemistry, "QCAA", 2026, "External Assessment");
		ExamCorpusStatus initial = service.assessSubject(chemistry).stream()
				.filter(status -> status.exam().getId() == exam.getId()).findFirst().orElseThrow();
		assertNull(initial.assetExpectations().expectedQuestionBookletCount());
		assertNull(initial.assetExpectations().expectedAnswerFileCount());
		assertEquals(0, initial.assetExpectations().availableQuestionBookletCount());
		assertEquals(0, initial.assetExpectations().availableAnswerFileCount());
		assertTrue(initial.bookletStatuses().isEmpty());
		assertEquals(0, initial.questionSummary().totalQuestions());
		assertFalse(initial.isReadyForCompletion());

		// Stage 2: Record expectations without importing assets.
		examWriter.updateExamAssetExpectations(exam, 1, 0);
		ExamCorpusStatus planned = service.assessSubject(chemistry).stream()
				.filter(status -> status.exam().getId() == exam.getId()).findFirst().orElseThrow();
		assertEquals(exam.getId(), planned.exam().getId());
		assertEquals(Integer.valueOf(1), planned.assetExpectations().expectedQuestionBookletCount());
		assertEquals(0, planned.assetExpectations().availableQuestionBookletCount());
		assertTrue(planned.bookletStatuses().isEmpty());
		assertFalse(planned.isReadyForCompletion());

		// Stage 3: Register a source booklet without Questions.
		SourceDocument document = examWriter.insertSourceDocument("Chemistry/QCAA/2026/paper1.pdf");
		ExamBooklet booklet = examWriter.insertExamBooklet(exam, document, "Paper 1",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, 1);
		ExamCorpusStatus withBooklet = service.assessSubject(chemistry).stream()
				.filter(status -> status.exam().getId() == exam.getId()).findFirst().orElseThrow();
		assertEquals(exam.getId(), withBooklet.exam().getId());
		assertEquals(1, withBooklet.bookletStatuses().size());
		assertEquals(booklet.getId(), withBooklet.bookletStatuses().getFirst().booklet().getId());
		assertEquals(0, withBooklet.questionSummary().totalQuestions());
		assertEquals(0, withBooklet.bookletStatuses().getFirst().encounteredTopLevelQuestionCount());
		assertFalse(withBooklet.isReadyForCompletion());

		// Stage 4: Capture the first Question.
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2026", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = curriculumWriter.insertDescriptor(topic, "1.1.1", "Descriptor", 1);
		questionRepository.save(booklet, "1", "", 2, List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20)),
				descriptor, false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
		ExamCorpusStatus withQuestion = service.assessSubject(chemistry).stream()
				.filter(status -> status.exam().getId() == exam.getId()).findFirst().orElseThrow();
		assertEquals(exam.getId(), withQuestion.exam().getId());
		assertEquals(1, withQuestion.questionSummary().totalQuestions());
		assertEquals(1, withQuestion.bookletStatuses().size());
		assertEquals(1, withQuestion.bookletStatuses().getFirst().encounteredTopLevelQuestionCount());

		// A different Subject must never receive this Exam.
		assertTrue(service.assessSubject(physics).isEmpty());

		// There must still be exactly one Exam row.
		assertEquals(1, service.assessSubject(chemistry).size());
	}

	@Test
	void plannedExamWithoutBookletsRemainsVisibleAndReportsAssetMismatch() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("planned-exam.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		Exam exam = examWriter.createExam(chemistry, "QCAA", 2025, "External Assessment");

		// Planning metadata may legitimately precede source-asset registration.
		examWriter.updateExamAssetExpectations(exam, 1, 0);
		Exam completed = examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		ExamCorpusAuditService service = new ExamCorpusAuditService(examWriter, answerWriter, questionRepository,
				new PdfStore(tempDirectory.resolve("pdf-planned-exam")));
		List<ExamCorpusStatus> statuses = service.assessSubject(chemistry);
		assertEquals(1, statuses.size());
		ExamCorpusStatus status = statuses.getFirst();

		// Exams with no booklet rows must remain visible because the absence of the
		// expected asset is itself operational Dashboard information.
		assertEquals(completed.getId(), status.exam().getId());
		assertTrue(status.bookletStatuses().isEmpty());
		assertEquals(Integer.valueOf(1), status.assetExpectations().expectedQuestionBookletCount());
		assertEquals(0, status.assetExpectations().availableQuestionBookletCount());
		assertEquals(Integer.valueOf(0), status.assetExpectations().expectedAnswerFileCount());
		assertEquals(0, status.assetExpectations().availableAnswerFileCount());
		assertTrue(status.hasFinding(ExamCorpusFinding.EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH));
		assertFalse(status.hasFinding(ExamCorpusFinding.EXPECTED_ANSWER_FILE_COUNT_MISMATCH));
		assertEquals(0, status.questionSummary().totalQuestions());
		assertTrue(status.requiresAttention());

		// Calculated audit findings must never silently reverse the user's explicit
		// lifecycle declaration.
		assertEquals(ExamCaptureState.COMPLETE, status.declaredCaptureState());
	}

	@Test
	void subjectWithNoExamsReturnsEmptyAuditSnapshot() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("empty-subject.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		ExamCorpusAuditService service = new ExamCorpusAuditService(examWriter, answerWriter, questionRepository,
				new PdfStore(tempDirectory.resolve("pdf-empty-subject")));

		// A valid Working Subject does not require an Exam to exist yet. This empty
		// result becomes the Dashboard's onboarding state rather than an audit error.
		assertTrue(service.assessSubject(chemistry).isEmpty());
	}
}
