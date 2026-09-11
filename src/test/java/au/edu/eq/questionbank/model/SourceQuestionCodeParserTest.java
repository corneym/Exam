package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SourceQuestionCodeParserTest {
	@Test
	void alphabeticCodeHasNoSourceCode() {
		assertNull(SourceQuestionCodeParser.derive("ABC"));
	}

	@Test
	void derivesPrefixedMultipartCode() {
		assertEquals("Q24", SourceQuestionCodeParser.derive("Q24a"));
	}

	@Test
	void derivesSimpleMultipartCode() {
		assertEquals("21", SourceQuestionCodeParser.derive("21a"));
	}

	@Test
	void multipleTrailingLettersAreNotMultipart() {
		assertNull(SourceQuestionCodeParser.derive("21ab"));
	}

	@Test
	void nullHasNoSourceCode() {
		assertNull(SourceQuestionCodeParser.derive(null));
	}

	@Test
	void ordinaryQuestionHasNoSourceCode() {
		assertNull(SourceQuestionCodeParser.derive("21"));
	}

	@Test
	void trimsQuestionCode() {
		assertEquals("21", SourceQuestionCodeParser.derive(" 21a "));
	}
}
