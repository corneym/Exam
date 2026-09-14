package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumDraftLoaderTest {

	@TempDir
	Path tempDir;

	@Test
	void loadsPersistedCurriculumIntoEditableDraftWhilePreservingIdentity() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("curriculum-authoring.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);
		Unit unit = writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		Topic topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		var descriptor = writer.insertDescriptor(topic, "1.1.1", "Resolve forces into components", 0);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE curriculum_nodes
						SET source_page_number = ?
						WHERE id = ?
						""")) {
			statement.setInt(1, 12);
			statement.setLong(2, descriptor.getId());
			assertEquals(1, statement.executeUpdate());
		}
		CurriculumDraftLoader loader = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database));
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraft draft = session.draft();
		assertEquals(3, draft.nodes().size());
		CurriculumDraftNode draftUnit = draft.childrenOf(null).get(0);
		assertEquals(CurriculumLevel.UNIT, draftUnit.level());
		assertEquals("1", draftUnit.code());
		assertEquals(unit.getId(), session.persistentIdForDraftId(draftUnit.draftId()).orElseThrow());
		CurriculumDraftNode draftTopic = draft.childrenOf(draftUnit.draftId()).get(0);
		assertEquals(topic.getId(), session.persistentIdForDraftId(draftTopic.draftId()).orElseThrow());
		CurriculumDraftNode draftDescriptor = draft.childrenOf(draftTopic.draftId()).get(0);
		assertEquals("Resolve forces into components", draftDescriptor.name());
		assertEquals(Integer.valueOf(12), draftDescriptor.sourcePageNumber());
		assertEquals(descriptor.getId(), session.persistentIdForDraftId(draftDescriptor.draftId()).orElseThrow());
		CurriculumDraftNode newDescriptor = draft.addNode(CurriculumLevel.DESCRIPTOR, "1.1.2", "New descriptor",
				draftTopic.draftId(), 13);
		assertTrue(session.persistentIdForDraftId(newDescriptor.draftId()).isEmpty());
		draft.removeSubtree(draftDescriptor.draftId());
		List<CurriculumDraftNode> remainingDescriptors = draft.childrenOf(draftTopic.draftId());
		assertEquals(1, remainingDescriptors.size());
		CurriculumDraftNode remainingDescriptor = remainingDescriptors.get(0);
		assertEquals(newDescriptor.draftId(), remainingDescriptor.draftId());
		assertEquals("1.1.2", remainingDescriptor.code());
		assertEquals("New descriptor", remainingDescriptor.name());
		assertEquals(0, remainingDescriptor.displayOrder());
		assertEquals(Integer.valueOf(13), remainingDescriptor.sourcePageNumber());
		assertTrue(session.deletedPersistentIds().contains(descriptor.getId()));
		assertFalse(session.deletedPersistentIds().contains(unit.getId()));
	}
}
