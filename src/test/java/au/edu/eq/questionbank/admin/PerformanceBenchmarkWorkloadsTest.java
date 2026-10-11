package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;
import au.edu.eq.questionbank.service.audit.ExamCorpusAuditService;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;

class PerformanceBenchmarkWorkloadsTest {

	@TempDir
	Path tempDirectory;

	@Test
	void applicabilityReconstructionUsesTwoConnections() throws Exception {
		Fixture fixture = createFixture();
		SqliteDatabase database = SqliteDatabase.readOnly(fixture.snapshotPath());
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		SyllabusVersion version = curriculum.findVersionById(fixture.syllabusId()).orElseThrow();
		CurriculumNode descriptor = curriculum.findByCode(version, "1.1.1.1").orElseThrow();
		CurriculumNode subtopic = curriculum.findByCode(version, "1.1.1").orElseThrow();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		List<QuestionApplicabilityMatch> matches;
		SqliteReadProbe.Snapshot measurements;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			matches = repository.findApplicableToNodes(List.of(subtopic, descriptor));
			measurements = probe.snapshot();
		}
		assertEquals(2, matches.size());
		assertEquals(2, measurements.questionsReconstructed());

		// The first connection selects applicability matches.
		// The second reconstructs both Questions and their ancestors.
		assertEquals(2, measurements.connectionsOpened());
		assertEquals(2, matches.stream().map(match -> match.getQuestion().getId()).distinct().count());
	}

	@Test
	void benchmarkExecutionDoesNotModifyDatabaseSnapshot() throws Exception {
		Fixture fixture = createFixture();
		byte[] before = Files.readAllBytes(fixture.snapshotPath());
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();
		runner.run("question.findAll", 1, 2, workloads.findAll());
		runner.run("question.findById", 1, 2, workloads.findById(fixture.questionId()));
		byte[] after = Files.readAllBytes(fixture.snapshotPath());
		assertArrayEquals(before, after);
		Path wal = fixture.snapshotPath().resolveSibling(fixture.snapshotPath().getFileName() + "-wal");
		Path shm = fixture.snapshotPath().resolveSibling(fixture.snapshotPath().getFileName() + "-shm");
		assertFalse(Files.exists(wal));
		assertFalse(Files.exists(shm));
	}

	@Test
	void benchmarkFindAllUsesProductionRepository() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		PerformanceBenchmarkResult result = new PerformanceBenchmarkRunner().run("question.findAll", 1, 2,
				workloads.findAll());
		assertEquals(2, result.resultCount());
		assertEquals(1, result.warmupIterations());
		assertEquals(2, result.measuredIterations());
		assertEquals(2, result.elapsedNanos().size());
	}

	@Test
	void benchmarkFindByIdReconstructsKnownQuestion() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		PerformanceBenchmarkResult result = new PerformanceBenchmarkRunner().run("question.findById", 1, 3,
				workloads.findById(fixture.questionId()));
		assertEquals(1, result.resultCount());
		assertEquals(3, result.measuredIterations());
		assertThrows(IllegalArgumentException.class, () -> workloads.findById(0));
		assertThrows(IllegalStateException.class, () -> workloads.findById(999999L).execute());
	}

	@Test
	void benchmarkUsesRealCurriculumApplicabilityRetrieval() throws Exception {
		Fixture fixture = createFixture();
		SqliteDatabase database = SqliteDatabase.readOnly(fixture.snapshotPath());
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		SyllabusVersion version = curriculum.findVersionById(fixture.syllabusId()).orElseThrow();
		CurriculumNode descriptor = curriculum.findByCode(version, "1.1.1.1").orElseThrow();
		CurriculumNode subtopic = curriculum.findByCode(version, "1.1.1").orElseThrow();
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();
		PerformanceBenchmarkResult descriptorResult = runner.run("search.current.descriptor", 1, 2,
				workloads.findApplicableToNodes(List.of(descriptor)));
		PerformanceBenchmarkResult subtopicResult = runner.run("search.current.subtopic", 1, 2,
				workloads.findApplicableToNodes(List.of(subtopic)));
		PerformanceBenchmarkResult combinedResult = runner.run("search.current.applicability", 1, 2,
				workloads.findApplicableToNodes(List.of(subtopic, descriptor)));
		assertEquals(1, descriptorResult.resultCount());
		assertEquals(1, subtopicResult.resultCount());
		assertEquals(2, combinedResult.resultCount());

		// Duplicate requested nodes must not inflate Question counts.
		assertEquals(1, workloads.findApplicableToNodes(List.of(descriptor, descriptor)).execute());
	}

	@Test
	void classificationReconstructionUsesQuestionConnection() throws Exception {
		Fixture fixture = createFixture();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(
				SqliteDatabase.readOnly(fixture.snapshotPath()));
		Question question;
		SqliteReadProbe.Snapshot measurements;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			question = repository.findById(fixture.questionId()).orElseThrow();
			measurements = probe.snapshot();
		}
		assertEquals(1, measurements.connectionsOpened());
		assertEquals(1, measurements.questionsReconstructed());
		assertEquals("1.1.1.1", question.getClassification().getCode());
		assertEquals(fixture.syllabusId(), question.getClassification().getSyllabusVersion().getId());
		assertInstanceOf(Descriptor.class, question.getClassification());
		Descriptor classification = (Descriptor) question.getClassification();
		assertInstanceOf(Subtopic.class, classification.getParent());
		assertInstanceOf(Topic.class, classification.getParent().getParent());
	}

	@Test
	void dashboardAuditRejectsMissingAndInvalidSubjects() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		assertThrows(IllegalArgumentException.class, () -> workloads.dashboardAuditSubject(0));
		assertThrows(IllegalArgumentException.class, () -> workloads.dashboardAuditSubject(-1));
		assertThrows(IllegalArgumentException.class, () -> workloads.dashboardAuditSubject(999999));
	}

	@Test
	void dashboardAuditRejectsUnexpectedAssetRoot() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		Subject subject = new SqliteCurriculumRepository(SqliteDatabase.readOnly(fixture.snapshotPath()))
				.findAllSubjects().getFirst();
		Path absentRoot = fixture.snapshotPath()
				.resolveSibling(fixture.snapshotPath().getFileName() + ".benchmark-no-pdfs");

		// A directory at this location would make the benchmark's
		// PDF-availability assumptions unreliable.
		Files.createDirectories(absentRoot);
		assertThrows(IllegalStateException.class, () -> workloads.dashboardAuditSubject(subject.getId()));
	}

	@Test
	void dashboardAuditUsesProductionServiceAndPreservesSnapshot() throws Exception {
		Fixture fixture = createFixture();
		byte[] before = Files.readAllBytes(fixture.snapshotPath());
		SqliteDatabase database = SqliteDatabase.readOnly(fixture.snapshotPath());
		Subject subject = new SqliteCurriculumRepository(database).findAllSubjects().getFirst();

		// Calculate the expected audit result independently using
		// the actual production corpus-audit service.
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamCorpusAuditService service = new ExamCorpusAuditService(examWriter,
				new SqliteAnswerWriter(database, examWriter), new SqliteQuestionRepository(database),
				new PdfStore(tempDirectory.resolve("control-empty-pdfs")));
		List<ExamCorpusStatus> expected = service.assessSubject(subject);
		assertEquals(1, expected.size());
		assertEquals(2, expected.getFirst().questionSummary().totalQuestions());
		assertFalse(expected.getFirst().bookletStatuses().getFirst().questionPdfAvailable());
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		PerformanceBenchmarkResult result = new PerformanceBenchmarkRunner().run("dashboard.audit.subject", 1, 2,
				workloads.dashboardAuditSubject(subject.getId()));
		assertEquals(expected.size(), result.resultCount());
		assertEquals(1, result.warmupIterations());
		assertEquals(2, result.measuredIterations());
		Path absentRoot = fixture.snapshotPath()
				.resolveSibling(fixture.snapshotPath().getFileName() + ".benchmark-no-pdfs");
		assertFalse(Files.exists(absentRoot));
		assertArrayEquals(before, Files.readAllBytes(fixture.snapshotPath()));
		assertFalse(Files.exists(fixture.snapshotPath().resolveSibling(fixture.snapshotPath().getFileName() + "-wal")));
		assertFalse(Files.exists(fixture.snapshotPath().resolveSibling(fixture.snapshotPath().getFileName() + "-shm")));
	}

	@Test
	void findAllReusesOneConnectionForQuestionLookups() throws Exception {
		Fixture fixture = createFixture();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(
				SqliteDatabase.readOnly(fixture.snapshotPath()));
		List<Question> questions;
		SqliteReadProbe.Snapshot bulk;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			questions = repository.findAll();
			bulk = probe.snapshot();
		}
		SqliteReadProbe.Snapshot individual;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			for (Question question : questions) {
				Question reloaded = repository.findById(question.getId()).orElseThrow();
				assertEquals(question.getId(), reloaded.getId());
			}
			individual = probe.snapshot();
		}
		assertEquals(2, questions.size());
		assertEquals(2, bulk.questionsReconstructed());

		// Individual lookups open one top-level connection per
		// Question. Bulk retrieval uses one shared connection.
		assertEquals(individual.connectionsOpened() - questions.size() + 1, bulk.connectionsOpened());

		// No SQL statements are removed by this optimisation.
		// Bulk retrieval still executes one additional ID-list query.
		assertEquals(individual.executions() + 1, bulk.executions());
	}

	@Test
	void rejectsInvalidCurriculumRetrievalScope() throws Exception {
		Fixture fixture = createFixture();
		SqliteDatabase database = SqliteDatabase.readOnly(fixture.snapshotPath());
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		SyllabusVersion version = curriculum.findVersionById(fixture.syllabusId()).orElseThrow();
		CurriculumNode unit = curriculum.findByCode(version, "1").orElseThrow();
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		assertThrows(NullPointerException.class, () -> workloads.findApplicableToNodes(null));
		assertThrows(IllegalArgumentException.class, () -> workloads.findApplicableToNodes(List.of()));
		assertThrows(IllegalArgumentException.class, () -> workloads.findApplicableToNodes(List.of(unit)));
	}

	@Test
	void rejectsMissingOrInvalidDatabaseSnapshot() throws Exception {
		Path missing = tempDirectory.resolve("missing.db");
		assertThrows(SQLException.class, () -> new PerformanceBenchmarkWorkloads(missing));
		assertFalse(Files.exists(missing));
		Path invalid = tempDirectory.resolve("invalid.db");
		Files.writeString(invalid, "not a SQLite database");
		assertThrows(SQLException.class, () -> new PerformanceBenchmarkWorkloads(invalid));
	}

	@Test
	void searchReusesOneConnectionForMatchedQuestionLookups() throws Exception {
		Fixture fixture = createFixture();
		SqliteDatabase database = SqliteDatabase.readOnly(fixture.snapshotPath());
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		SyllabusVersion version = curriculum.findVersionById(fixture.syllabusId()).orElseThrow();
		CurriculumNode descriptor = curriculum.findByCode(version, "1.1.1.1").orElseThrow();
		CurriculumNode subtopic = curriculum.findByCode(version, "1.1.1").orElseThrow();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		List<QuestionApplicabilityMatch> matches;
		SqliteReadProbe.Snapshot bulk;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			matches = repository.findApplicableToNodes(List.of(subtopic, descriptor));
			bulk = probe.snapshot();
		}
		List<Long> distinctIds = matches.stream().map(match -> match.getQuestion().getId()).distinct().toList();
		assertEquals(2, distinctIds.size());
		assertEquals(2, bulk.questionsReconstructed());
		SqliteReadProbe.Snapshot individual;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			for (Long questionId : distinctIds) {
				assertTrue(repository.findById(questionId).isPresent());
			}
			individual = probe.snapshot();
		}

		// One connection selects the applicability rows, and a
		// second is reused for all matching Question reconstructions.
		assertEquals(individual.connectionsOpened() - distinctIds.size() + 2, bulk.connectionsOpened());
		assertEquals(individual.executions() + 1, bulk.executions());
	}

	@Test
	void wholeBankReconstructionUsesOneConnection() throws Exception {
		Fixture fixture = createFixture();
		SqliteQuestionRepository repository = new SqliteQuestionRepository(
				SqliteDatabase.readOnly(fixture.snapshotPath()));
		List<Question> questions;
		SqliteReadProbe.Snapshot measurements;
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			questions = repository.findAll();
			measurements = probe.snapshot();
		}
		assertEquals(2, questions.size());
		assertEquals(2, measurements.questionsReconstructed());

		// Both Questions and their curriculum ancestor chains must
		// now be reconstructed through the same connection.
		assertEquals(1, measurements.connectionsOpened());
		assertEquals("1.1.1.1", questions.get(0).getClassification().getCode());
		assertEquals("1.1.1", questions.get(1).getClassification().getCode());
	}

	private Fixture createFixture() throws Exception {
		Path sourcePath = tempDirectory.resolve("source.db");
		Path snapshotPath = tempDirectory.resolve("benchmark.db");
		SqliteDatabase database = new SqliteDatabase(sourcePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);
		Unit unit = writer.insertUnit(version, "1", "Unit 1", 1);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = writer.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		Descriptor descriptor = writer.insertDescriptor(subtopic, "1.1.1.1", "Descriptor 1", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(subject, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper-1.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		Question first = questions.save(booklet, "Q1", "", 2, List.of(), descriptor, false);
		questions.save(booklet, "Q2", "", 3, List.of(), subtopic, false);

		// Benchmark execution must use a consistent, separate
		// snapshot rather than the writable fixture database.
		database.createConsistentSnapshot(snapshotPath);
		return new Fixture(snapshotPath, first.getId(), version.getId());
	}

	private record Fixture(Path snapshotPath, long questionId, long syllabusId) {
	}
}
