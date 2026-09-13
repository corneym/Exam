package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;

class CurriculumDraftNumberingServiceTest {

	private final CurriculumDraftNumberingService numbering = new CurriculumDraftNumberingService();

	@Test
	void deletionCompactsNumericCodes() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = numbering.addNode(draft, CurriculumLevel.UNIT, "Unit", null, 1);
		CurriculumDraftNode first = numbering.addNode(draft, CurriculumLevel.TOPIC, "First", unit.draftId(), 2);
		CurriculumDraftNode second = numbering.addNode(draft, CurriculumLevel.TOPIC, "Second", unit.draftId(), 3);
		CurriculumDraftNode third = numbering.addNode(draft, CurriculumLevel.TOPIC, "Third", unit.draftId(), 4);
		numbering.removeSubtree(draft, second.draftId());
		assertEquals("1.1", current(draft, first).code());
		assertEquals("1.2", current(draft, third).code());
	}

	@Test
	void derivesCodesFromExplicitHierarchy() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode firstUnit = numbering.addNode(draft, CurriculumLevel.UNIT, "First unit", null, 4);
		CurriculumDraftNode secondUnit = numbering.addNode(draft, CurriculumLevel.UNIT, "Second unit", null, 10);
		CurriculumDraftNode firstTopic = numbering.addNode(draft, CurriculumLevel.TOPIC, "First topic",
				firstUnit.draftId(), 5);
		CurriculumDraftNode secondTopic = numbering.addNode(draft, CurriculumLevel.TOPIC, "Second topic",
				firstUnit.draftId(), 6);
		CurriculumDraftNode descriptor = numbering.addNode(draft, CurriculumLevel.DESCRIPTOR, "Direct descriptor",
				secondTopic.draftId(), 7);
		assertEquals("1", current(draft, firstUnit).code());
		assertEquals("2", current(draft, secondUnit).code());
		assertEquals("1.1", current(draft, firstTopic).code());
		assertEquals("1.2", current(draft, secondTopic).code());
		assertEquals("1.2.1", current(draft, descriptor).code());
	}

	@Test
	void reorderingRenumbersNodeAndDescendantsWithoutChangingIdentity() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = numbering.addNode(draft, CurriculumLevel.UNIT, "Unit", null, 1);
		CurriculumDraftNode firstTopic = numbering.addNode(draft, CurriculumLevel.TOPIC, "First", unit.draftId(), 2);
		CurriculumDraftNode secondTopic = numbering.addNode(draft, CurriculumLevel.TOPIC, "Second", unit.draftId(), 3);
		CurriculumDraftNode descriptor = numbering.addNode(draft, CurriculumLevel.DESCRIPTOR, "Descriptor",
				secondTopic.draftId(), 4);
		assertTrue(numbering.moveUp(draft, secondTopic.draftId()));
		assertEquals("1.1", current(draft, secondTopic).code());
		assertEquals("1.1.1", current(draft, descriptor).code());
		assertEquals("1.2", current(draft, firstTopic).code());
		assertEquals(secondTopic.draftId(), current(draft, secondTopic).draftId());
		assertEquals(descriptor.draftId(), current(draft, descriptor).draftId());
	}

	@Test
	void supportsOptionalSubtopicDepth() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = numbering.addNode(draft, CurriculumLevel.UNIT, "Unit", null, 1);
		CurriculumDraftNode topic = numbering.addNode(draft, CurriculumLevel.TOPIC, "Topic", unit.draftId(), 2);
		CurriculumDraftNode firstSubtopic = numbering.addNode(draft, CurriculumLevel.SUBTOPIC, "First subtopic",
				topic.draftId(), 3);
		CurriculumDraftNode secondSubtopic = numbering.addNode(draft, CurriculumLevel.SUBTOPIC, "Second subtopic",
				topic.draftId(), 4);
		CurriculumDraftNode descriptor = numbering.addNode(draft, CurriculumLevel.DESCRIPTOR, "Descriptor",
				secondSubtopic.draftId(), 5);
		assertEquals("1.1.1", current(draft, firstSubtopic).code());
		assertEquals("1.1.2", current(draft, secondSubtopic).code());
		assertEquals("1.1.2.1", current(draft, descriptor).code());
	}

	@Test
	void textEditingDoesNotAlterCodeOrIdentity() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = numbering.addNode(draft, CurriculumLevel.UNIT, "Original text", null, 8);
		CurriculumDraftNode updated = numbering.updateText(draft, unit.draftId(), "Corrected text");
		assertEquals(unit.draftId(), updated.draftId());
		assertEquals("1", updated.code());
		assertEquals("Corrected text", updated.name());
		assertEquals(8, updated.sourcePageNumber());
	}

	private CurriculumDraftNode current(CurriculumDraft draft, CurriculumDraftNode node) {
		return draft.findNode(node.draftId()).orElseThrow();
	}
}
