package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;

class CurriculumDraftTest {

	@Test
	void allocatesStableIdsAndSiblingOrder() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "U", "Unit", null, 4);
		CurriculumDraftNode firstTopic = draft.addNode(CurriculumLevel.TOPIC, "T1", "First topic", unit.draftId(), 5);
		CurriculumDraftNode secondTopic = draft.addNode(CurriculumLevel.TOPIC, "T2", "Second topic", unit.draftId(), 6);
		CurriculumDraftNode descriptor = draft.addNode(CurriculumLevel.DESCRIPTOR, "D", "Descriptor",
				firstTopic.draftId(), 7);
		assertEquals(1, unit.draftId());
		assertEquals(2, firstTopic.draftId());
		assertEquals(3, secondTopic.draftId());
		assertEquals(4, descriptor.draftId());
		assertEquals(0, unit.displayOrder());
		assertEquals(0, firstTopic.displayOrder());
		assertEquals(1, secondTopic.displayOrder());
		assertEquals(0, descriptor.displayOrder());
	}

	@Test
	void doesNotReuseIdentifiersAfterDeletion() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode first = draft.addNode(CurriculumLevel.UNIT, "U1", "First", null, null);
		CurriculumDraftNode second = draft.addNode(CurriculumLevel.UNIT, "U2", "Second", null, null);
		draft.removeSubtree(second.draftId());
		CurriculumDraftNode third = draft.addNode(CurriculumLevel.UNIT, "U3", "Third", null, null);
		assertEquals(1, first.draftId());
		assertEquals(2, second.draftId());
		assertEquals(3, third.draftId());
	}

	@Test
	void editingCodeDoesNotBreakChildRelationship() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "old-code", "Unit", null, 3);
		CurriculumDraftNode topic = draft.addNode(CurriculumLevel.TOPIC, "topic", "Topic", unit.draftId(), 4);
		CurriculumDraftNode updatedUnit = draft.updateNode(unit.draftId(), CurriculumLevel.UNIT, "completely-new-code",
				"Renamed unit", null, 8);
		assertEquals(unit.draftId(), updatedUnit.draftId());
		assertEquals("completely-new-code", updatedUnit.code());
		assertEquals(unit.draftId(), draft.findNode(topic.draftId()).orElseThrow().parentDraftId());
	}

	@Test
	void exposesWholeDraftValidation() {
		CurriculumDraft draft = new CurriculumDraft();
		draft.addNode(CurriculumLevel.TOPIC, "topic-without-unit", "Topic", null, null);
		assertEquals(List.of("TOPIC topic-without-unit (draft 1) requires a parent"), draft.validationProblems());
	}

	@Test
	void movesNodesOnlyWithinTheirSiblingList() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "U", "Unit", null, null);
		CurriculumDraftNode first = draft.addNode(CurriculumLevel.TOPIC, "T1", "First", unit.draftId(), null);
		CurriculumDraftNode second = draft.addNode(CurriculumLevel.TOPIC, "T2", "Second", unit.draftId(), null);
		CurriculumDraftNode third = draft.addNode(CurriculumLevel.TOPIC, "T3", "Third", unit.draftId(), null);
		assertFalse(draft.moveUp(first.draftId()));
		assertTrue(draft.moveUp(third.draftId()));
		assertEquals(List.of(first.draftId(), third.draftId(), second.draftId()),
				draft.childrenOf(unit.draftId()).stream().map(CurriculumDraftNode::draftId).toList());
		assertTrue(draft.moveDown(first.draftId()));
		assertEquals(List.of(third.draftId(), first.draftId(), second.draftId()),
				draft.childrenOf(unit.draftId()).stream().map(CurriculumDraftNode::draftId).toList());
		assertFalse(draft.moveDown(second.draftId()));
	}

	@Test
	void rejectsUnknownNodeForEditingOrMovement() {
		CurriculumDraft draft = new CurriculumDraft();
		assertThrows(IllegalArgumentException.class,
				() -> draft.updateNode(99, CurriculumLevel.UNIT, "U", "Unit", null, null));
		assertThrows(IllegalArgumentException.class, () -> draft.moveUp(99));
		assertThrows(IllegalArgumentException.class, () -> draft.moveDown(99));
		assertThrows(IllegalArgumentException.class, () -> draft.removeSubtree(99));
	}

	@Test
	void removesCompleteSubtreeAndCompactsRemainingSiblings() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "U", "Unit", null, null);
		CurriculumDraftNode firstTopic = draft.addNode(CurriculumLevel.TOPIC, "T1", "First topic", unit.draftId(),
				null);
		CurriculumDraftNode secondTopic = draft.addNode(CurriculumLevel.TOPIC, "T2", "Second topic", unit.draftId(),
				null);
		CurriculumDraftNode thirdTopic = draft.addNode(CurriculumLevel.TOPIC, "T3", "Third topic", unit.draftId(),
				null);
		CurriculumDraftNode subtopic = draft.addNode(CurriculumLevel.SUBTOPIC, "S1", "Subtopic", secondTopic.draftId(),
				null);
		CurriculumDraftNode descriptor = draft.addNode(CurriculumLevel.DESCRIPTOR, "D1", "Descriptor",
				subtopic.draftId(), null);
		List<CurriculumDraftNode> removed = draft.removeSubtree(secondTopic.draftId());
		assertEquals(List.of(secondTopic.draftId(), subtopic.draftId(), descriptor.draftId()),
				removed.stream().map(CurriculumDraftNode::draftId).toList());
		assertEquals(List.of(firstTopic.draftId(), thirdTopic.draftId()),
				draft.childrenOf(unit.draftId()).stream().map(CurriculumDraftNode::draftId).toList());
		assertEquals(0, draft.childrenOf(unit.draftId()).get(0).displayOrder());
		assertEquals(1, draft.childrenOf(unit.draftId()).get(1).displayOrder());
		assertTrue(draft.findNode(secondTopic.draftId()).isEmpty());
		assertTrue(draft.findNode(subtopic.draftId()).isEmpty());
		assertTrue(draft.findNode(descriptor.draftId()).isEmpty());
	}

	@Test
	void reparentingMovesNodeToEndAndCompactsOldSiblingOrder() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "U", "Unit", null, null);
		CurriculumDraftNode firstTopic = draft.addNode(CurriculumLevel.TOPIC, "T1", "First", unit.draftId(), null);
		CurriculumDraftNode secondTopic = draft.addNode(CurriculumLevel.TOPIC, "T2", "Second", unit.draftId(), null);
		CurriculumDraftNode firstDescriptor = draft.addNode(CurriculumLevel.DESCRIPTOR, "D1", "First descriptor",
				firstTopic.draftId(), null);
		CurriculumDraftNode secondDescriptor = draft.addNode(CurriculumLevel.DESCRIPTOR, "D2", "Second descriptor",
				firstTopic.draftId(), null);
		CurriculumDraftNode moved = draft.updateNode(firstDescriptor.draftId(), CurriculumLevel.DESCRIPTOR, "D1",
				"First descriptor", secondTopic.draftId(), null);
		assertEquals(secondTopic.draftId(), moved.parentDraftId());
		assertEquals(0, moved.displayOrder());
		assertEquals(List.of(secondDescriptor.draftId()),
				draft.childrenOf(firstTopic.draftId()).stream().map(CurriculumDraftNode::draftId).toList());
		assertEquals(0, draft.childrenOf(firstTopic.draftId()).get(0).displayOrder());
	}
}
