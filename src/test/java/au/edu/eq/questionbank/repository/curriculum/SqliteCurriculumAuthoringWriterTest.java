package au.edu.eq.questionbank.repository.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftLoader;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNumberingService;

class SqliteCurriculumAuthoringWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void refusesToDeleteMappedCurriculumNodeAndLeavesPersistedCurriculumUnchanged() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("referenced-node-deletion.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject engineering = curriculumWriter.insertSubject("Engineering");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(engineering, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Forces", 0);
		var protectedDescriptor = curriculumWriter.insertDescriptor(topic, "1.1.1", "Protected descriptor", 0);
		var otherDescriptor = curriculumWriter.insertDescriptor(topic, "1.1.2", "Other descriptor", 1);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO curriculum_mappings
						    (source_node_id,
						     target_node_id,
						     mapping_status)
						VALUES (?, ?, 'CONFIRMED')
						""")) {
			statement.setLong(1, protectedDescriptor.getId());
			statement.setLong(2, otherDescriptor.getId());
			assertEquals(1, statement.executeUpdate());
		}
		CurriculumDraftLoader loader = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database));
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraft draft = session.draft();
		CurriculumDraftNode draftUnit = draft.childrenOf(null).get(0);
		CurriculumDraftNode draftTopic = draft.childrenOf(draftUnit.draftId()).get(0);
		CurriculumDraftNode draftProtected = draft.childrenOf(draftTopic.draftId()).stream()
				.filter(node -> node.name().equals("Protected descriptor")).findFirst().orElseThrow();
		CurriculumDraftNode draftOther = draft.childrenOf(draftTopic.draftId()).stream()
				.filter(node -> node.name().equals("Other descriptor")).findFirst().orElseThrow();
		CurriculumDraftNumberingService numbering = new CurriculumDraftNumberingService();
		numbering.updateText(draft, draftOther.draftId(), "Edited other descriptor");
		numbering.removeSubtree(draft, draftProtected.draftId());
		assertTrue(session.deletedPersistentIds().contains(protectedDescriptor.getId()));
		SqliteCurriculumAuthoringWriter writer = new SqliteCurriculumAuthoringWriter(database);
		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> writer.save(session));
		assertTrue(exception.getMessage().contains("referenced by a curriculum mapping"));
		/*
		 * A failed save must not alter the persistence bindings. The deletion remains
		 * pending in the editing session.
		 */
		assertTrue(session.deletedPersistentIds().contains(protectedDescriptor.getId()));
		assertEquals(otherDescriptor.getId(), session.persistentIdForDraftId(draftOther.draftId()).orElseThrow());
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT curriculum_name,
					       curriculum_code
					FROM curriculum_nodes
					WHERE id = ?
					""")) {
				statement.setLong(1, protectedDescriptor.getId());
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					assertEquals("Protected descriptor", result.getString("curriculum_name"));
					assertEquals("1.1.1", result.getString("curriculum_code"));
				}
				statement.setLong(1, otherDescriptor.getId());
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					/*
					 * The edit exists only in the draft because the complete save operation was
					 * rejected.
					 */
					assertEquals("Other descriptor", result.getString("curriculum_name"));
					assertEquals("1.1.2", result.getString("curriculum_code"));
				}
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT COUNT(*)
					FROM curriculum_mappings
					WHERE source_node_id = ?
					  AND target_node_id = ?
					""")) {
				statement.setLong(1, protectedDescriptor.getId());
				statement.setLong(2, otherDescriptor.getId());
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					assertEquals(1, result.getInt(1));
				}
			}
		}
	}

	@Test
	void savesUpdatesInsertionsDeletionsAndReorderingWithoutChangingExistingIds() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("authoring-save.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject engineering = curriculumWriter.insertSubject("Engineering");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(engineering, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Forces", 0);
		var firstDescriptor = curriculumWriter.insertDescriptor(topic, "1.1.1", "First descriptor", 0);
		var secondDescriptor = curriculumWriter.insertDescriptor(topic, "1.1.2", "Second descriptor", 1);
		curriculumWriter.insertDescriptor(topic, "1.1.3", "Descriptor to remove", 2);
		CurriculumDraftLoader loader = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database));
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraft draft = session.draft();
		CurriculumDraftNode draftUnit = draft.childrenOf(null).get(0);
		CurriculumDraftNode draftTopic = draft.childrenOf(draftUnit.draftId()).get(0);
		CurriculumDraftNode draftFirst = draft.childrenOf(draftTopic.draftId()).get(0);
		CurriculumDraftNode draftSecond = draft.childrenOf(draftTopic.draftId()).get(1);
		CurriculumDraftNode draftDeleted = draft.childrenOf(draftTopic.draftId()).get(2);
		assertEquals(firstDescriptor.getId(), session.persistentIdForDraftId(draftFirst.draftId()).orElseThrow());
		assertEquals(secondDescriptor.getId(), session.persistentIdForDraftId(draftSecond.draftId()).orElseThrow());
		CurriculumDraftNumberingService numbering = new CurriculumDraftNumberingService();
		numbering.removeSubtree(draft, draftDeleted.draftId());
		assertTrue(numbering.moveDown(draft, draftFirst.draftId()));
		String editedText = """
				Resolve forces using \\(F_h = F\\cos\\theta\\).

				Then calculate \\(F_T = \\sqrt{F_h^2 + F_v^2}\\).
				""";
		numbering.updateText(draft, draftFirst.draftId(), editedText);
		CurriculumDraftNode newDescriptor = numbering.addNode(draft,
				au.edu.eq.questionbank.model.CurriculumLevel.DESCRIPTOR, "Calculate resultant force",
				draftTopic.draftId(), 13);
		assertTrue(session.persistentIdForDraftId(newDescriptor.draftId()).isEmpty());
		SqliteCurriculumAuthoringWriter writer = new SqliteCurriculumAuthoringWriter(database);
		writer.save(session);
		assertFalse(session.persistentIdForDraftId(newDescriptor.draftId()).isEmpty());
		assertTrue(session.deletedPersistentIds().isEmpty());
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT
					    curriculum_code,
					    curriculum_name
					FROM curriculum_nodes
					WHERE id = ?
					""")) {
				statement.setLong(1, firstDescriptor.getId());
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					/*
					 * Moving the first descriptor down changes display order only. Its curriculum
					 * code and permanent database identity remain unchanged.
					 */
					assertEquals("1.1.1", result.getString("curriculum_code"));
					assertEquals(editedText, result.getString("curriculum_name"));
				}
				statement.setLong(1, secondDescriptor.getId());
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					assertEquals("1.1.2", result.getString("curriculum_code"));
				}
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT COUNT(*)
					FROM curriculum_nodes
					WHERE syllabus_version_id = ?
					  AND curriculum_name = ?
					""")) {
				statement.setLong(1, syllabus.getId());
				statement.setString(2, "Descriptor to remove");
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					assertEquals(0, result.getInt(1));
				}
			}
			long newPersistentId = session.persistentIdForDraftId(newDescriptor.draftId()).orElseThrow();
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT
					    curriculum_code,
					    curriculum_name,
					    source_page_number
					FROM curriculum_nodes
					WHERE id = ?
					""")) {
				statement.setLong(1, newPersistentId);
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					assertEquals("1.1.3", result.getString("curriculum_code"));
					assertEquals("Calculate resultant force", result.getString("curriculum_name"));
					assertEquals(13, result.getInt("source_page_number"));
				}
			}
		}
	}
}
