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
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumMappingReviewWriterTest {
	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private SyllabusVersion targetVersion;
	private SyllabusVersion otherTargetVersion;
	private Descriptor source;
	private Descriptor targetOne;
	private Descriptor targetTwo;
	private Descriptor otherVersionTarget;

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
	void editingOneTargetVersionPreservesMappingsAndReviewForAnotherVersion() throws Exception {
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		writer.confirmMappings(source, targetVersion, List.of(targetOne));
		writer.confirmMappings(source, otherTargetVersion, List.of(otherVersionTarget));

		writer.replaceMappings(source, targetVersion, List.of(targetTwo));

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT target_node_id
					FROM curriculum_mappings
					WHERE source_node_id = 12
					ORDER BY target_node_id
					""")) {
				assertTrue(result.next());
				assertEquals(23, result.getLong("target_node_id"));
				assertTrue(result.next());
				assertEquals(32, result.getLong("target_node_id"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT target_syllabus_version_id, review_outcome
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					ORDER BY target_syllabus_version_id
					""")) {
				assertTrue(result.next());
				assertEquals(2, result.getLong("target_syllabus_version_id"));
				assertEquals("MATCHED", result.getString("review_outcome"));
				assertTrue(result.next());
				assertEquals(3, result.getLong("target_syllabus_version_id"));
				assertEquals("MATCHED", result.getString("review_outcome"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void promotesExistingSuggestedMappingWhenReviewIsConfirmed() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO curriculum_mappings
					    (source_node_id, target_node_id, mapping_status)
					VALUES (12, 22, 'SUGGESTED')
					""");
		}
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
	void refusesNoMatchWhenConfirmedMappingExistsWithoutReview() throws Exception {
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		mappingWriter.insertMapping(source, targetOne, MappingStatus.CONFIRMED);
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);

		assertThrows(IllegalStateException.class, () -> reviewWriter.confirmNoMatch(source, targetVersion));

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*) AS review_count
						FROM curriculum_mapping_reviews
						WHERE source_node_id = 12
						  AND target_syllabus_version_id = 2
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt("review_count"));
		}
	}

	@Test
	void rejectsTargetThatMisrepresentsItsPersistedSyllabusAndRollsBackReview() throws Exception {
		Topic claimedParent = (Topic) targetOne.getParent();
		Descriptor fabricatedTarget = new Descriptor(32, targetVersion, claimedParent, "1.1.9", "Fabricated target", 9);
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);

		assertThrows(IllegalArgumentException.class,
				() -> writer.confirmMappings(source, targetVersion, List.of(targetOne, fabricatedTarget)));

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM curriculum_mappings
					WHERE source_node_id = 12
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM curriculum_mapping_reviews
					WHERE source_node_id = 12
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
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
	void rollsBackDeletedMappingsWhenEditedReplacementCannotBeInserted() throws Exception {
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		writer.confirmMappings(source, targetVersion, List.of(targetOne));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_second_review_mapping
					BEFORE INSERT ON curriculum_mappings
					WHEN NEW.target_node_id = 23
					BEGIN
					    SELECT RAISE(ABORT, 'replacement rejected');
					END
					""");
		}

		assertThrows(SQLException.class,
				() -> writer.replaceMappings(source, targetVersion, List.of(targetOne, targetTwo)));

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
	void rollsBackSuggestedPromotionWhenLaterMappingCannotBeConfirmed() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO curriculum_mappings
					    (source_node_id, target_node_id, mapping_status)
					VALUES (12, 22, 'SUGGESTED')
					""");
			statement.execute("""
					CREATE TRIGGER reject_second_mapping
					BEFORE INSERT ON curriculum_mappings
					WHEN NEW.target_node_id = 23
					BEGIN
					    SELECT RAISE(ABORT, 'forced mapping failure');
					END
					""");
		}
		SqliteCurriculumMappingReviewWriter writer = new SqliteCurriculumMappingReviewWriter(database);
		assertThrows(SQLException.class,
				() -> writer.confirmMappings(source, targetVersion, List.of(targetOne, targetTwo)));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT target_node_id, mapping_status
					FROM curriculum_mappings
					WHERE source_node_id = 12
					""")) {
				assertTrue(result.next());
				assertEquals(22, result.getLong("target_node_id"));
				assertEquals("SUGGESTED", result.getString("mapping_status"));
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
					    (2, 1, 'New syllabus', 1),
					    (3, 1, 'Other target syllabus', 0)
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
					    (23, 2, 21, '1.1.2', 'Second new descriptor', 'DESCRIPTOR', 1),
					    (30, 3, NULL, '1', 'Other target unit', 'UNIT', 0),
					    (31, 3, 30, '1.1', 'Other target topic', 'TOPIC', 0),
					    (32, 3, 31, '1.1.1', 'Other target descriptor', 'DESCRIPTOR', 0)
					""");
		}
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, subject, "Old syllabus", false);
		targetVersion = new SyllabusVersion(2, subject, "New syllabus", true);
		otherTargetVersion = new SyllabusVersion(3, subject, "Other target syllabus", false);
		Unit sourceUnit = new Unit(10, sourceVersion, "1", "Old unit", 0);
		Topic sourceTopic = new Topic(11, sourceVersion, sourceUnit, "1.1", "Old topic", 0);
		source = new Descriptor(12, sourceVersion, sourceTopic, "1.1.1", "Old descriptor", 0);
		Unit targetUnit = new Unit(20, targetVersion, "1", "New unit", 0);
		Topic targetTopic = new Topic(21, targetVersion, targetUnit, "1.1", "New topic", 0);
		targetOne = new Descriptor(22, targetVersion, targetTopic, "1.1.1", "First new descriptor", 0);
		targetTwo = new Descriptor(23, targetVersion, targetTopic, "1.1.2", "Second new descriptor", 1);
		Unit otherTargetUnit = new Unit(30, otherTargetVersion, "1", "Other target unit", 0);
		Topic otherTargetTopic = new Topic(31, otherTargetVersion, otherTargetUnit, "1.1", "Other target topic", 0);
		otherVersionTarget = new Descriptor(32, otherTargetVersion, otherTargetTopic, "1.1.1",
				"Other target descriptor", 0);
	}
}
