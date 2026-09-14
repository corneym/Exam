package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;

class CurriculumDraftValidatorTest {

	private final CurriculumDraftValidator validator = new CurriculumDraftValidator();

	@Test
	void acceptsDescriptorDirectlyUnderTopic() {
		List<CurriculumDraftNode> nodes = List.of(node(1, CurriculumLevel.UNIT, "A", null),
				node(2, CurriculumLevel.TOPIC, "B", 1L), node(3, CurriculumLevel.DESCRIPTOR, "criterion-x", 2L));
		assertEquals(List.of(), validator.validate(nodes));
	}

	@Test
	void acceptsFourLevelHierarchy() {
		List<CurriculumDraftNode> nodes = List.of(node(1, CurriculumLevel.UNIT, "1", null),
				node(2, CurriculumLevel.TOPIC, "1.1", 1L), node(3, CurriculumLevel.SUBTOPIC, "1.1.1", 2L),
				node(4, CurriculumLevel.DESCRIPTOR, "1.1.1.a", 3L));
		assertEquals(List.of(), validator.validate(nodes));
	}

	@Test
	void rejectsMixingSubtopicsWithDescriptorsDirectlyUnderTopics() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "1", "Unit", null, null);
		CurriculumDraftNode firstTopic = draft.addNode(CurriculumLevel.TOPIC, "1.1", "First topic", unit.draftId(),
				null);
		CurriculumDraftNode secondTopic = draft.addNode(CurriculumLevel.TOPIC, "1.2", "Second topic", unit.draftId(),
				null);
		draft.addNode(CurriculumLevel.DESCRIPTOR, "1.1.1", "Direct descriptor", firstTopic.draftId(), null);
		draft.addNode(CurriculumLevel.SUBTOPIC, "1.2.1", "Subtopic", secondTopic.draftId(), null);
		assertTrue(draft.validationProblems()
				.contains("Curriculum draft must not mix Subtopics with Descriptors directly under Topics"));
	}

	@Test
	void rejectsNullDraftAndReportsEmptyDraft() {
		assertThrows(NullPointerException.class, () -> validator.validate(null));
		assertEquals(List.of("Curriculum draft must contain at least one node"), validator.validate(List.of()));
	}

	@Test
	void reportsDuplicateIdsAndCodes() {
		List<CurriculumDraftNode> nodes = List.of(node(1, CurriculumLevel.UNIT, "1", null),
				node(1, CurriculumLevel.UNIT, "2", null), node(2, CurriculumLevel.UNIT, " 1 ", null));
		assertEquals(List.of("Duplicate draft id: 1", "Duplicate curriculum code: 1"), validator.validate(nodes));
	}

	@Test
	void reportsMissingAndIllegalParents() {
		List<CurriculumDraftNode> nodes = List.of(node(1, CurriculumLevel.UNIT, "1", null),
				node(2, CurriculumLevel.TOPIC, "2", null), node(3, CurriculumLevel.SUBTOPIC, "3", 1L),
				node(4, CurriculumLevel.DESCRIPTOR, "4", 99L), node(5, CurriculumLevel.UNIT, "5", 1L),
				node(6, CurriculumLevel.DESCRIPTOR, "6", 1L));
		assertEquals(List.of("TOPIC 2 (draft 2) requires a parent",
				"SUBTOPIC 3 (draft 3) cannot have parent level UNIT",
				"DESCRIPTOR 4 (draft 4) references missing parent draft 99", "UNIT 5 (draft 5) must not have a parent",
				"DESCRIPTOR 6 (draft 6) cannot have parent level UNIT"), validator.validate(nodes));
	}

	@Test
	void reportsNullNodeWithoutStoppingOtherValidation() {
		List<CurriculumDraftNode> nodes = new ArrayList<>();
		nodes.add(node(1, CurriculumLevel.UNIT, "1", null));
		nodes.add(null);
		nodes.add(node(2, CurriculumLevel.TOPIC, "1.1", null));
		assertEquals(List.of("Draft node at index 1 is null", "TOPIC 1.1 (draft 2) requires a parent"),
				validator.validate(nodes));
	}

	private CurriculumDraftNode node(long id, CurriculumLevel level, String code, Long parentId) {
		return new CurriculumDraftNode(id, level, code, "Node " + code, parentId, 0, null);
	}
}