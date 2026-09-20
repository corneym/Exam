package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnswerTest {

	private AnswerRegion region;

	@Test
	void acceptsRegionOnlyAnswer() {
		Answer answer = new Answer(1, null, List.of(region));
		assertEquals(1, answer.getId());
		assertEquals(null, answer.getAnswerText());
		assertEquals(1, answer.getRegions().size());
		assertSame(region, answer.getRegions().get(0));
	}

	@Test
	void acceptsTextAndRegions() {
		Answer answer = new Answer(1, "42 kJ mol⁻¹", List.of(region));
		assertEquals("42 kJ mol⁻¹", answer.getAnswerText());
		assertEquals(List.of(region), answer.getRegions());
	}

	@Test
	void acceptsTextOnlyAnswer() {
		Answer answer = new Answer(1, "B", List.of());
		assertEquals(1, answer.getId());
		assertEquals("B", answer.getAnswerText());
		assertEquals(List.of(), answer.getRegions());
	}

	@Test
	void protectsRegionListFromModification() {
		Answer answer = new Answer(1, null, List.of(region));
		assertThrows(UnsupportedOperationException.class, () -> answer.getRegions().clear());
	}

	@Test
	void rejectsEmptyAnswer() {
		assertThrows(IllegalArgumentException.class, () -> new Answer(1, null, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new Answer(1, "", List.of()));
		assertThrows(IllegalArgumentException.class, () -> new Answer(1, "   ", List.of()));
	}

	@Test
	void rejectsNonPositiveId() {
		assertThrows(IllegalArgumentException.class, () -> new Answer(0, "B", List.of()));
	}

	@Test
	void rejectsNullRegionElement() {
		assertThrows(NullPointerException.class, () -> new Answer(1, null, List.of(region, null)));
	}

	@Test
	void rejectsNullRegions() {
		assertThrows(NullPointerException.class, () -> new Answer(1, "B", null));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2025, "External Assessment");
		SourceDocument document = new SourceDocument(1, "answers/marking-guide.pdf");
		AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", document);
		region = new AnswerRegion(answerFile, 2, 0.1, 0.2, 0.3, 0.4);
	}
}
