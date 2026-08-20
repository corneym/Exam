package au.edu.eq.questionbank.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlQuestionRendererTest {

	@TempDir
	Path tempDir;

	private final HtmlQuestionRenderer renderer = new HtmlQuestionRenderer();

	@Test
	void rendersImagesUsingRelativeWebPathsInOrder() throws Exception {
		Path output = tempDir.resolve("questions.html");
		Path first = tempDir.resolve("images/question1.png");
		Path second = tempDir.resolve("question2.png");

		renderer.render(List.of(first, second), output);

		String html = Files.readString(output);
		assertTrue(html.contains("<meta charset=\"UTF-8\">"));
		assertTrue(html.contains("<img src=\"images/question1.png\">"));
		assertTrue(html.contains("<img src=\"question2.png\">"));
		assertTrue(html.indexOf("images/question1.png") < html.indexOf("question2.png"));
	}

	@Test
	void rendersAValidDocumentWhenThereAreNoQuestions() throws Exception {
		Path output = tempDir.resolve("empty.html");

		renderer.render(List.of(), output);

		String html = Files.readString(output);
		assertTrue(html.contains("<!DOCTYPE html>"));
		assertTrue(html.contains("</html>"));
		assertFalse(html.contains("<img"));
	}
}
