package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class PerformanceBenchmarkWorkloadsTest {

	@TempDir
	Path tempDirectory;

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
