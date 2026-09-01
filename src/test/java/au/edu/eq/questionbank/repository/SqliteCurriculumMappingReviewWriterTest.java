package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumMappingReviewWriterTest {
	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private SyllabusVersion targetVersion;
	private Descriptor source;
	private Descriptor targetOne;
	private Descriptor targetTwo;

	@Test
	void confirmsMultipleMappingsAndMarksReviewMatched() throws Exception {
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		writer.confirmMappings(source, targetVersion, List.of(targetOne, targetTwo));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT target_node_id, mapping_status
					FROM curriculum_mappings
					WHERE source_node_id = 12
					ORDER BY target_node_id
					""")) {
				assertTrue(result.next());
				assertEquals(22, result.getLong("target_node_id"));
				assertEquals("CONFIRMED", result.getString("mapping_status"));
				assertTrue(result.next());
				assertEquals(23, result.getLong("target_node_id"));
				assertEquals("CONFIRMED", result.getString("mapping_status"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT review_outcome
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					  AND target_syllabus_version_id = 2
					""")) {
				assertTrue(result.next());
				assertEquals("MATCHED", result.getString("review_outcome"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void confirmsNoMatchWithoutCreatingMapping() throws Exception {
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		writer.confirmNoMatch(source, targetVersion);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*) AS mapping_count
					FROM curriculum_mappings
					WHERE source_node_id = 12
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt("mapping_count"));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT review_outcome
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					  AND target_syllabus_version_id = 2
					""")) {
				assertTrue(result.next());
				assertEquals("NO_MATCH", result.getString("review_outcome"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void replacesMatchedReviewWithNoMatch() throws Exception {
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		writer.confirmMappings(source, targetVersion, List.of(targetOne, targetTwo));
		writer.replaceWithNoMatch(source, targetVersion);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*) AS mapping_count
					FROM curriculum_mappings
					WHERE source_node_id = 12
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt("mapping_count"));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT review_outcome
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					  AND target_syllabus_version_id = 2
					""")) {
				assertTrue(result.next());
				assertEquals("NO_MATCH", result.getString("review_outcome"));
			}
		}
	}

	@Test
	void replacesNoMatchReviewWithMultipleMappings() throws Exception {
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		writer.confirmNoMatch(source, targetVersion);
		writer.replaceMappings(source, targetVersion, List.of(targetOne, targetTwo));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT target_node_id
					FROM curriculum_mappings
					WHERE source_node_id = 12
					ORDER BY target_node_id
					""")) {
				assertTrue(result.next());
				assertEquals(22, result.getLong("target_node_id"));
				assertTrue(result.next());
				assertEquals(23, result.getLong("target_node_id"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT review_outcome
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					  AND target_syllabus_version_id = 2
					""")) {
				assertTrue(result.next());
				assertEquals("MATCHED", result.getString("review_outcome"));
			}
		}
	}

	@Test
	void rollsBackWholeReviewWhenOneMappingCannotBeInserted() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO curriculum_mappings
					    (source_node_id, target_node_id, mapping_status)
					VALUES (12, 23, 'CONFIRMED')
					""");
		}
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		assertThrows(SQLException.class,
				() -> writer.confirmMappings(source, targetVersion, List.of(targetOne, targetTwo)));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT target_node_id
					FROM curriculum_mappings
					WHERE source_node_id = 12
					ORDER BY target_node_id
					""")) {
				assertTrue(result.next());
				assertEquals(23, result.getLong("target_node_id"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*) AS review_count
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					  AND target_syllabus_version_id = 2
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt("review_count"));
			}
		}
	}

	@BeforeEach
	void setUp() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, 'Old syllabus', 0),
					    (2, 1, 'New syllabus', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id, curriculum_code,
					     curriculum_name, curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1', 'Old unit', 'UNIT', 0),
					    (11, 1, 10, '1.1', 'Old topic', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1', 'Old descriptor', 'DESCRIPTOR', 0),
					    (20, 2, NULL, '1', 'New unit', 'UNIT', 0),
					    (21, 2, 20, '1.1', 'New topic', 'TOPIC', 0),
					    (22, 2, 21, '1.1.1', 'First new descriptor', 'DESCRIPTOR', 0),
					    (23, 2, 21, '1.1.2', 'Second new descriptor', 'DESCRIPTOR', 1)
					""");
		}
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, subject, "Old syllabus", false);
		targetVersion = new SyllabusVersion(2, subject, "New syllabus", true);
		Unit sourceUnit = new Unit(10, sourceVersion, "1", "Old unit", 0);
		Topic sourceTopic = new Topic(11, sourceVersion, sourceUnit, "1.1", "Old topic", 0);
		source = new Descriptor(12, sourceVersion, sourceTopic, "1.1.1", "Old descriptor", 0);
		Unit targetUnit = new Unit(20, targetVersion, "1", "New unit", 0);
		Topic targetTopic = new Topic(21, targetVersion, targetUnit, "1.1", "New topic", 0);
		targetOne = new Descriptor(22, targetVersion, targetTopic, "1.1.1", "First new descriptor", 0);
		targetTwo = new Descriptor(23, targetVersion, targetTopic, "1.1.2", "Second new descriptor", 1);
	}
}