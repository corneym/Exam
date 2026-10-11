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

class SqliteQuestionQueryPlanToolTest {

	@TempDir
	Path tempDirectory;

	@Test
	void rejectsInvalidOrMissingSubject() throws Exception {
		Fixture fixture = createFixture();
		assertEquals(2, run(fixture.snapshot(), "0"));
		assertEquals(2, run(fixture.snapshot(), "not-a-number"));
		assertEquals(2, run(fixture.snapshot(), "999999"));
	}

	@Test
	void rejectsSnapshotWithWalSidecar() throws Exception {
		Fixture fixture = createFixture();
		byte[] original = Files.readAllBytes(fixture.snapshot());
		Path wal = fixture.snapshot().resolveSibling(fixture.snapshot().getFileName() + "-wal");
		Files.writeString(wal, "sidecar");
		try {
			assertEquals(1, run(fixture.snapshot(), Long.toString(fixture.subjectId())));
		} finally {
			Files.deleteIfExists(wal);
		}
		assertArrayEquals(original, Files.readAllBytes(fixture.snapshot()));
	}

	@Test
	void reportsProductionPlansAndIndexInventory() throws Exception {
		Fixture fixture = createFixture();
		byte[] original = Files.readAllBytes(fixture.snapshot());
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		int status = SqliteQuestionQueryPlanTool.run(arguments(fixture), new PrintStream(output),
				new PrintStream(error));
		assertEquals(0, status, error.toString());
		String report = output.toString();
		assertTrue(report.contains("=== SQLite index inventory ==="));
		assertTrue(report.contains("Table: questions"));
		assertTrue(report.contains("Table: curriculum_nodes"));
		assertTrue(report.contains("Index: sqlite_autoindex_"));
		assertTrue(report.contains("Plan: question.findAll"));
		assertTrue(report.contains("Plan: question.findById"));
		assertTrue(report.contains("Plan: search.current.single-node"));
		assertTrue(report.contains("Plan: search.current.subject"));
		assertTrue(report.contains("Read-only investigation completed."));

		// Plan details vary by SQLite version, but a non-empty
		// plan must describe at least one access path.
		assertTrue(report.contains("SEARCH") || report.contains("SCAN"));
		assertArrayEquals(original, Files.readAllBytes(fixture.snapshot()));
		assertNoSidecars(fixture.snapshot());
	}

	private String[] arguments(Fixture fixture) {
		return new String[] { "--snapshot", fixture.snapshot().toString(), "--subject",
				Long.toString(fixture.subjectId()) };
	}

	private void assertNoSidecars(Path snapshot) {
		assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-wal")));
		assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-shm")));
	}

	private Fixture createFixture() throws Exception {
		Path source = tempDirectory.resolve("source.db");
		Path snapshot = tempDirectory.resolve("snapshot.db");
		SqliteDatabase database = new SqliteDatabase(source);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		Subject subject = curriculum.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(subject, "2025", true);
		Unit unit = curriculum.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculum.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = curriculum.insertDescriptor(topic, "1.1.1", "Descriptor 1", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(subject, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		questions.save(booklet, "Q1", "", 2, List.of(), descriptor, false);
		database.createConsistentSnapshot(snapshot);
		return new Fixture(snapshot, subject.getId());
	}

	private int run(Path snapshot, String subjectId) {
		return SqliteQuestionQueryPlanTool.run(
				new String[] { "--snapshot", snapshot.toString(), "--subject", subjectId },
				new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
	}

	private record Fixture(Path snapshot, long subjectId) {
	}
}
