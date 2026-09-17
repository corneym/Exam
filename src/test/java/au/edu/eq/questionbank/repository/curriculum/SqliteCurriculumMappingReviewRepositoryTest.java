package au.edu.eq.questionbank.repository.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumMappingReviewRepositoryTest {

	@TempDir
	Path tempDir;

	@Test
	void findsReviewedSourcesForSelectedSourceAndTargetVersions() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, 'Old syllabus', 0),
					    (2, 1, 'Current syllabus', 1),
					    (3, 1, 'Other syllabus', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id, curriculum_code,
					     curriculum_name, curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1', 'Unit', 'UNIT', 0),
					    (11, 1, 10, '1.1', 'Topic', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1', 'Matched descriptor', 'DESCRIPTOR', 0),
					    (13, 1, 11, '1.1.2', 'No-match descriptor', 'DESCRIPTOR', 1),
					    (14, 1, 11, '1.1.3', 'Unreviewed descriptor', 'DESCRIPTOR', 2)
					""");
			statement.execute("""
					INSERT INTO curriculum_mapping_reviews
					    (source_node_id, target_syllabus_version_id, review_outcome)
					VALUES
					    (12, 2, 'MATCHED'),
					    (13, 2, 'NO_MATCH'),
					    (14, 3, 'NO_MATCH')
					""");
		}
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, subject, "Old syllabus", false);
		SyllabusVersion targetVersion = new SyllabusVersion(2, subject, "Current syllabus", true);
		CurriculumMappingReviewRepository repository = new SqliteCurriculumMappingReviewRepository(database);
		Set<Long> reviewedSourceIds = repository.findReviewedSourceIds(sourceVersion, targetVersion);
		assertEquals(Set.of(12L, 13L), reviewedSourceIds);
	}

	@Test
	void findsReviewOutcomeForSelectedSourceAndTargetVersion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("outcome.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, 'Old syllabus', 0),
					    (2, 1, 'Current syllabus', 1),
					    (3, 1, 'Other target syllabus', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id, curriculum_code,
					     curriculum_name, curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1', 'Unit', 'UNIT', 0),
					    (11, 1, 10, '1.1', 'Topic', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1', 'Descriptor', 'DESCRIPTOR', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_mapping_reviews
					    (source_node_id, target_syllabus_version_id, review_outcome)
					VALUES (12, 2, 'NO_MATCH')
					""");
		}
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, subject, "Old syllabus", false);
		SyllabusVersion targetVersion = new SyllabusVersion(2, subject, "Current syllabus", true);
		Unit unit = new Unit(10, sourceVersion, "1", "Unit", 0);
		Topic topic = new Topic(11, sourceVersion, unit, "1.1", "Topic", 0);
		Descriptor descriptor = new Descriptor(12, sourceVersion, topic, "1.1.1", "Descriptor", 0);
		CurriculumMappingReviewRepository repository = new SqliteCurriculumMappingReviewRepository(database);
		Optional<CurriculumMappingReviewOutcome> outcome = repository.findOutcome(descriptor, targetVersion);
		assertTrue(outcome.isPresent());
		assertEquals(CurriculumMappingReviewOutcome.NO_MATCH, outcome.get());
		SyllabusVersion otherTargetVersion = new SyllabusVersion(3, subject, "Other target syllabus", false);
		assertTrue(repository.findOutcome(descriptor, otherTargetVersion).isEmpty(),
				"absence of a target-specific review row must mean unreviewed");
	}
}
