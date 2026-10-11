package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteQuestionInvestigationToolTest {

	@TempDir
	Path tempDirectory;

	@Test
	void findAllCountsEveryReconstructedQuestion() throws Exception {
		Fixture fixture = createFixture();
		byte[] original = Files.readAllBytes(fixture.snapshot());
		String output = runSuccessfully(fixture.snapshot(), "question.findAll");
		assertTrue(output.contains("Result count: 2"));
		assertTrue(output.contains("Questions reconstructed: 2"));

		// This fixture must exercise the repeated-connection retrieval path.
		// Bulk Question and curriculum reconstruction must share one connection.
		assertEquals(1, count(output, "Connections opened:"));

		// Reconstruction still executes the necessary SQL statements.
		assertTrue(count(output, "SQL executions:") > 2);
		assertArrayEquals(original, Files.readAllBytes(fixture.snapshot()));
		assertNoSidecars(fixture.snapshot());
	}

	@Test
	void invalidOperationsAreRejected() throws Exception {
		Fixture fixture = createFixture();
		assertEquals(2, runTool(fixture.snapshot(), "invalid.operation"));
		assertEquals(2, runTool(fixture.snapshot(), "question.findById:0"));
		assertEquals(2, runTool(fixture.snapshot(), "question.findById:not-a-number"));
		assertEquals(1, runTool(fixture.snapshot(), "question.findById:999999"));
	}

	@Test
	void refusesSnapshotWithWalSidecar() throws Exception {
		Fixture fixture = createFixture();
		byte[] original = Files.readAllBytes(fixture.snapshot());
		Path wal = fixture.snapshot().resolveSibling(fixture.snapshot().getFileName() + "-wal");
		Files.writeString(wal, "not standalone");
		try {
			assertEquals(1, runTool(fixture.snapshot(), "question.findAll"));
		} finally {
			Files.deleteIfExists(wal);
		}
		assertArrayEquals(original, Files.readAllBytes(fixture.snapshot()));
	}

	@Test
	void singleQuestionRetrievalCountsOneReconstruction() throws Exception {
		Fixture fixture = createFixture();
		String output = runSuccessfully(fixture.snapshot(), "question.findById:" + fixture.questionId());
		assertTrue(output.contains("Result count: 1"));
		assertTrue(output.contains("Questions reconstructed: 1"));
		assertNoSidecars(fixture.snapshot());
	}

	@Test
	void subjectRetrievalCountsReconstructedQuestions() throws Exception {
		Fixture fixture = createFixture();
		String output = runSuccessfully(fixture.snapshot(), "search.current.subject:" + fixture.subjectId());
		assertTrue(output.contains("Result count: 2"));
		assertTrue(output.contains("Questions reconstructed: 2"));
		assertTrue(count(output, "SQL executions:") > 2);
		assertNoSidecars(fixture.snapshot());
	}

	private String[] arguments(Path snapshot, String operation) {
		return new String[] { "--snapshot", snapshot.toString(), "--operation", operation };
	}

	private void assertNoSidecars(Path snapshot) {
		assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-wal")));
		assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-shm")));
	}

	private long count(String output, String label) {
		return output.lines().filter(line -> line.startsWith(label)).map(line -> line.substring(label.length()).trim())
				.mapToLong(Long::parseLong).findFirst().orElseThrow();
	}

	private Fixture createFixture() throws Exception {
		Path databasePath = tempDirectory.resolve("source.db");
		Path snapshotPath = tempDirectory.resolve("snapshot.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		Subject subject = curriculum.insertSubject("Chemistry");
		SyllabusVersion version = curriculum.insertSyllabusVersion(subject, "2025", true);
		Unit unit = curriculum.insertUnit(version, "1", "Unit 1", 1);
		Topic topic = curriculum.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = curriculum.insertDescriptor(topic, "1.1.1", "Descriptor 1", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(subject, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		long firstId = questions.save(booklet, "Q1", "", 2, List.of(), descriptor, false).getId();
		questions.save(booklet, "Q2", "", 3, List.of(), descriptor, false);
		database.createConsistentSnapshot(snapshotPath);
		return new Fixture(snapshotPath, subject.getId(), firstId);
	}

	private String runSuccessfully(Path snapshot, String operation) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		int result = SqliteQuestionInvestigationTool.run(arguments(snapshot, operation), new PrintStream(output),
				new PrintStream(error));
		assertEquals(0, result, error.toString());
		return output.toString();
	}

	private int runTool(Path snapshot, String operation) {
		return SqliteQuestionInvestigationTool.run(arguments(snapshot, operation),
				new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
	}

	private record Fixture(Path snapshot, long subjectId, long questionId) {
	}
}
