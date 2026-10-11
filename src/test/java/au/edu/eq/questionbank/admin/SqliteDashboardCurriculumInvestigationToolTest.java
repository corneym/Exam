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
import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteDashboardCurriculumInvestigationToolTest {

	@TempDir
	Path tempDirectory;

	@Test
	void measuresProductionCoverageWithoutModifyingSnapshot() throws Exception {

		Path snapshot = createFixture();
		byte[] before = Files.readAllBytes(snapshot);

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();

		int status = SqliteDashboardCurriculumInvestigationTool.run(
				new String[] { "--snapshot", snapshot.toString(), "--subject", "1" },
				new PrintStream(output, true, StandardCharsets.UTF_8),
				new PrintStream(errors, true, StandardCharsets.UTF_8));

		assertEquals(0, status, errors.toString());

		String report = output.toString(StandardCharsets.UTF_8);

		assertTrue(report.contains("Historical syllabus 1 -> current 2"));
		assertTrue(report.contains("Historical syllabus pairs: 1"));
		assertTrue(report.contains("Source descriptors: 1"));
		assertTrue(report.contains("Source subtopics: 1"));

		// Coverage traverses each hierarchy separately for both
		// Descriptor and Subtopic calculations.
		assertTrue(report.contains("curriculum.findRootNodes: calls=4"));

		assertTrue(report.contains("curriculum.findChildren: calls=16"));

		// One mapping collection and one reviewed source decision.
		assertTrue(report.contains("mappings.findAll: calls=1"));

		assertTrue(report.contains("reviews.findReviewedSourceIds: calls=1"));

		assertTrue(report.contains("reviews.findOutcome: calls=1"));

		assertTrue(report.contains("Connections opened:"));
		assertTrue(report.contains("SQL executions:"));
		assertTrue(report.contains("Snapshot unchanged: true"));

		assertArrayEquals(before, Files.readAllBytes(snapshot));

		assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-wal")));

		assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-shm")));
	}

	@Test
	void refusesSnapshotWithWalSidecar() throws Exception {
		Path snapshot = createFixture();
		Path wal = snapshot.resolveSibling(snapshot.getFileName() + "-wal");

		Files.writeString(wal, "not a valid WAL");

		ByteArrayOutputStream errors = new ByteArrayOutputStream();

		int status = SqliteDashboardCurriculumInvestigationTool.run(
				new String[] { "--snapshot", snapshot.toString(), "--subject", "1" },
				new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));

		assertEquals(2, status);
		assertTrue(errors.toString().contains("sidecar"));
	}

	@Test
	void rejectsMissingSubject() throws Exception {
		Path snapshot = createFixture();

		ByteArrayOutputStream errors = new ByteArrayOutputStream();

		int status = SqliteDashboardCurriculumInvestigationTool.run(
				new String[] { "--snapshot", snapshot.toString(), "--subject", "999999" },
				new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));

		assertEquals(2, status);
		assertTrue(errors.toString().contains("Subject does not exist"));
	}

	private Path createFixture() throws Exception {
		Path source = tempDirectory.resolve("source.db");
		Path snapshot = tempDirectory.resolve("snapshot.db");

		SqliteDatabase database = new SqliteDatabase(source);
		database.initialiseSchema();

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");

			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, '2019', 0),
					    (2, 1, '2025', 1)
					""");

			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1',
					     'Historical unit', 'UNIT', 0),
					    (11, 1, 10, '1.1',
					     'Historical topic', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1',
					     'Historical subtopic', 'SUBTOPIC', 0),
					    (13, 1, 12, '1.1.1.1',
					     'Historical descriptor', 'DESCRIPTOR', 0),

					    (20, 2, NULL, '1',
					     'Current unit', 'UNIT', 0),
					    (21, 2, 20, '1.1',
					     'Current topic', 'TOPIC', 0),
					    (22, 2, 21, '1.1.1',
					     'Current subtopic', 'SUBTOPIC', 0),
					    (23, 2, 22, '1.1.1.1',
					     'Current descriptor', 'DESCRIPTOR', 0)
					""");

			// A completed no-match review ensures the diagnostic
			// observes an individual review-outcome lookup.
			statement.execute("""
					INSERT INTO curriculum_mapping_reviews
					    (source_node_id, target_syllabus_version_id,
					     review_outcome)
					VALUES (13, 2, 'NO_MATCH')
					""");
		}

		database.createConsistentSnapshot(snapshot);
		return snapshot;
	}
}
