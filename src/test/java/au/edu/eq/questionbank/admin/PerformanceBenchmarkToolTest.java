package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class PerformanceBenchmarkToolTest {

	@TempDir
	Path tempDirectory;

	@Test
	void benchmarksDashboardAuditAndExportsCsv() throws Exception {
		Fixture fixture = createFixture();
		Path csv = tempDirectory.resolve("dashboard-audit.csv");
		byte[] before = Files.readAllBytes(fixture.snapshot());
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		String operation = "dashboard.audit.subject:" + fixture.subjectId();
		int status = PerformanceBenchmarkTool.run(
				new String[] { "--snapshot", fixture.snapshot().toString(), "--operation", operation, "--warmups", "1",
						"--iterations", "2", "--label", "dashboard-audit-test", "--csv", csv.toString() },
				new PrintStream(outputBytes), new PrintStream(errorBytes));
		assertEquals(0, status);
		assertTrue(errorBytes.toString().isEmpty());
		String output = outputBytes.toString();
		assertTrue(output.contains("Operation: " + operation));
		assertTrue(output.contains("Measured iterations: 2"));

		// The fixture has one Exam containing one Question.
		// The audit workload counts Exams, not Questions.
		assertTrue(output.contains("Result count: 1"));
		assertTrue(Files.isRegularFile(csv));
		List<String> rows = Files.readAllLines(csv);
		assertEquals(2, rows.size());
		assertEquals(PerformanceBenchmarkCsvReport.HEADER, rows.getFirst());
		assertTrue(rows.get(1).contains("," + operation + ",1,2,1,"));
		assertArrayEquals(before, Files.readAllBytes(fixture.snapshot()));
		Path absentRoot = fixture.snapshot().resolveSibling(fixture.snapshot().getFileName() + ".benchmark-no-pdfs");
		assertFalse(Files.exists(absentRoot));
	}

	@Test
	void csvReportContainsRealSnapshotFingerprintAndMetadata() throws Exception {
		Fixture fixture = createFixture();
		Path csv = tempDirectory.resolve("results.csv");
		byte[] snapshotBefore = Files.readAllBytes(fixture.snapshot());
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int status = PerformanceBenchmarkTool.run(
				new String[] { "--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll",
						"--operation", "question.findById:" + fixture.questionId(), "--warmups", "2", "--iterations",
						"3", "--label", "pre94-abc123", "--csv", csv.toString() },
				new PrintStream(outputBytes), new PrintStream(errorBytes));
		assertEquals(0, status);
		assertTrue(Files.isRegularFile(csv));
		String expectedSha = PerformanceBenchmarkCsvReport.sha256(fixture.snapshot());
		List<String> rows = Files.readAllLines(csv);
		assertEquals(3, rows.size());
		assertEquals(PerformanceBenchmarkCsvReport.HEADER, rows.getFirst());
		assertTrue(rows.get(1).contains(expectedSha));
		assertTrue(rows.get(2).contains(expectedSha));
		assertTrue(rows.get(1).contains(",pre94-abc123,"));
		assertTrue(rows.get(1).contains(",question.findAll,2,3,1,"));
		assertTrue(rows.get(2).contains(",question.findById:" + fixture.questionId() + ",2,3,1,"));
		String output = outputBytes.toString();
		assertTrue(output.contains("Code label: pre94-abc123"));
		assertTrue(output.contains("Snapshot SHA-256: " + expectedSha));
		assertTrue(output.contains("CSV report: " + csv));
		assertTrue(errorBytes.toString().isEmpty());
		assertArrayEquals(snapshotBefore, Files.readAllBytes(fixture.snapshot()));
	}

	@Test
	void executesSelectedProductionWorkloadsAndReportsCorpusContext() throws Exception {
		Fixture fixture = createFixture();
		byte[] before = Files.readAllBytes(fixture.snapshot());
		List<String> arguments = new ArrayList<>(
				List.of("--snapshot", fixture.snapshot().toString(), "--warmups", "1", "--iterations", "2"));
		List<String> operations = List.of("question.findAll", "question.findById:" + fixture.questionId(),
				"search.current.subject:" + fixture.subjectId(), "search.current.unit:" + fixture.unitId(),
				"search.current.topic:" + fixture.topicId(), "search.current.descriptor:" + fixture.descriptorId());
		for (String operation : operations) {
			arguments.add("--operation");
			arguments.add(operation);
		}
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int status = PerformanceBenchmarkTool.run(arguments.toArray(String[]::new),
				new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
				new PrintStream(errorBytes, true, StandardCharsets.UTF_8));
		assertEquals(0, status);
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
		String output = outputBytes.toString(StandardCharsets.UTF_8);
		assertTrue(output.contains("Questions in bank: 1"));
		assertTrue(output.contains("Database bytes: " + Files.size(fixture.snapshot())));
		assertTrue(output.contains("Warm-ups: 1"));
		assertTrue(output.contains("Measured iterations: 2"));
		assertTrue(output.contains("Result count: 1"));
		assertTrue(output.contains("Median:"));
		assertTrue(output.contains("P90:"));
		for (String operation : operations) {
			assertTrue(output.contains("Operation: " + operation));
		}
		assertArrayEquals(before, Files.readAllBytes(fixture.snapshot()));
		assertFalse(Files.exists(fixture.snapshot().resolveSibling(fixture.snapshot().getFileName() + "-wal")));
		assertFalse(Files.exists(fixture.snapshot().resolveSibling(fixture.snapshot().getFileName() + "-shm")));
	}

	@Test
	void existingCsvIsNotOverwritten() throws Exception {
		Fixture fixture = createFixture();
		Path csv = tempDirectory.resolve("existing-results.csv");
		Files.writeString(csv, "protected benchmark baseline");
		assertEquals(1, runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll", "--csv",
				csv.toString()));
		assertEquals("protected benchmark baseline", Files.readString(csv));
	}

	@Test
	void failedBenchmarkDoesNotProducePartialCsv() throws Exception {
		Fixture fixture = createFixture();
		Path csv = tempDirectory.resolve("failed-results.csv");
		int status = runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll",
				"--operation", "question.findById:999999", "--warmups", "1", "--iterations", "1", "--csv",
				csv.toString());
		assertEquals(1, status);
		assertFalse(Files.exists(csv));
	}

	@Test
	void helpDoesNotRequireDatabaseAccess() {
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		int result = PerformanceBenchmarkTool.run(new String[] { "--help" }, new PrintStream(outputBytes),
				new PrintStream(new ByteArrayOutputStream()));
		assertEquals(0, result);
		assertTrue(outputBytes.toString().contains("Usage:"));
	}

	@Test
	void invalidCsvOptionsAreRejectedWithoutModifyingSnapshot() throws Exception {
		Fixture fixture = createFixture();
		byte[] snapshotBefore = Files.readAllBytes(fixture.snapshot());
		Path relative = Path.of("relative-results.csv");
		assertEquals(2, runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll", "--csv",
				relative.toString()));
		assertEquals(2, runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll", "--csv",
				fixture.snapshot().toString()));
		assertEquals(2, runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll",
				"--label", "invalid label"));
		assertEquals(2,
				runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll", "--label", ""));
		assertEquals(2,
				runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll", "--csv"));
		assertArrayEquals(snapshotBefore, Files.readAllBytes(fixture.snapshot()));
	}

	@Test
	void refusesMissingSnapshotWithoutCreatingIt() {
		Path missing = tempDirectory.resolve("missing.db");
		assertEquals(1, runTool("--snapshot", missing.toString(), "--operation", "question.findAll"));
		assertFalse(Files.exists(missing));
	}

	@Test
	void refusesSnapshotWithOutstandingSidecarFiles() throws Exception {
		Fixture fixture = createFixture();
		Path wal = fixture.snapshot().resolveSibling(fixture.snapshot().getFileName() + "-wal");
		Files.writeString(wal, "unexpected journal");
		assertEquals(1, runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findAll"));
		assertTrue(Files.exists(wal));
	}

	@Test
	void rejectsInvalidArgumentsBeforeOpeningDatabase() {
		assertEquals(2, runTool());
		assertEquals(2, runTool("--operation", "question.findAll"));
		assertEquals(2, runTool("--snapshot", "relative.db", "--operation", "question.findAll"));
		assertEquals(2, runTool("--snapshot", tempDirectory.resolve("x.db").toString()));
		assertEquals(2, runTool("--snapshot", tempDirectory.resolve("x.db").toString(), "--operation", "unknown"));
		assertEquals(2,
				runTool("--snapshot", tempDirectory.resolve("x.db").toString(), "--operation", "question.findById:0"));
		assertEquals(2, runTool("--snapshot", tempDirectory.resolve("x.db").toString(), "--operation",
				"question.findAll", "--warmups", "0"));
		assertEquals(2, runTool("--snapshot", tempDirectory.resolve("x.db").toString(), "--operation",
				"question.findAll", "--iterations", "-1"));
		assertEquals(2, runTool("--snapshot", tempDirectory.resolve("x.db").toString(), "--operation",
				"question.findAll", "--operation", "question.findAll"));
	}

	@Test
	void rejectsMissingDashboardAuditSubject() throws Exception {
		Fixture fixture = createFixture();
		assertEquals(2,
				runTool("--snapshot", fixture.snapshot().toString(), "--operation", "dashboard.audit.subject:0"));
		assertEquals(1, runTool("--snapshot", fixture.snapshot().toString(), "--operation",
				"dashboard.audit.subject:999999", "--warmups", "1", "--iterations", "1"));
	}

	@Test
	void unknownQuestionFailsRatherThanReportingZeroResults() throws Exception {
		Fixture fixture = createFixture();
		assertEquals(1, runTool("--snapshot", fixture.snapshot().toString(), "--operation", "question.findById:999999",
				"--warmups", "1", "--iterations", "1"));
	}

	private Fixture createFixture() throws Exception {
		Path databasePath = tempDirectory.resolve("source.db");
		Path snapshotPath = tempDirectory.resolve("benchmark.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Chemistry");
		SyllabusVersion version = writer.insertSyllabusVersion(subject, "2025", true);
		Unit unit = writer.insertUnit(version, "1", "Unit 1", 1);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = writer.insertDescriptor(topic, "1.1.1", "Descriptor 1", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(subject, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper.pdf");
		Question question = new SqliteQuestionRepository(database).save(booklet, "Q1", "", 2, List.of(), descriptor,
				false);
		database.createConsistentSnapshot(snapshotPath);
		return new Fixture(snapshotPath, subject.getId(), unit.getId(), topic.getId(), descriptor.getId(),
				question.getId());
	}

	private int runTool(String... arguments) {
		return PerformanceBenchmarkTool.run(arguments, new PrintStream(new ByteArrayOutputStream()),
				new PrintStream(new ByteArrayOutputStream()));
	}

	private record Fixture(Path snapshot, long subjectId, long unitId, long topicId, long descriptorId,
			long questionId) {
	}
}
