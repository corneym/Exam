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
	void helpDoesNotRequireDatabaseAccess() {
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();

		int result = PerformanceBenchmarkTool.run(new String[] { "--help" }, new PrintStream(outputBytes),
				new PrintStream(new ByteArrayOutputStream()));

		assertEquals(0, result);
		assertTrue(outputBytes.toString().contains("Usage:"));
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
