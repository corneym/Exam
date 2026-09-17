package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;

class CurriculumDraftNodeTest {

	@Test
	void allowsMissingParentForLaterWholeDraftValidation() {
		CurriculumDraftNode node = new CurriculumDraftNode(2, CurriculumLevel.TOPIC, "2.3", "Psychological disorders",
				null, 0, null);
		assertNull(node.parentDraftId());
		assertNull(node.sourcePageNumber());
	}

	@Test
	void rejectsInvalidScalarValues() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(0, CurriculumLevel.UNIT, "1", "Unit", null, 0, null)),
				() -> assertThrows(NullPointerException.class,
						() -> new CurriculumDraftNode(1, null, "1", "Unit", null, 0, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(1, CurriculumLevel.UNIT, " ", "Unit", null, 0, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(1, CurriculumLevel.UNIT, "1", " ", null, 0, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(1, CurriculumLevel.TOPIC, "1.1", "Topic", 0L, 0, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(1, CurriculumLevel.TOPIC, "1.1", "Topic", 1L, 0, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(1, CurriculumLevel.UNIT, "1", "Unit", null, -1, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumDraftNode(1, CurriculumLevel.UNIT, "1", "Unit", null, 0, 0)));
	}

	@Test
	void retainsExplicitAuthoringValues() {
		CurriculumDraftNode node = new CurriculumDraftNode(3, CurriculumLevel.DESCRIPTOR, "2.3.5",
				"Describe approaches to diagnosis", 2L, 4, 17);
		assertAll(() -> assertEquals(3, node.draftId()), () -> assertEquals(CurriculumLevel.DESCRIPTOR, node.level()),
				() -> assertEquals("2.3.5", node.code()),
				() -> assertEquals("Describe approaches to diagnosis", node.name()),
				() -> assertEquals(2L, node.parentDraftId()), () -> assertEquals(4, node.displayOrder()),
				() -> assertEquals(17, node.sourcePageNumber()));
	}
}
