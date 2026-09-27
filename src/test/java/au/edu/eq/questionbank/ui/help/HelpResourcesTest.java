package au.edu.eq.questionbank.ui.help;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HelpResourcesTest {

	private static final String HELP_ROOT = "/au/edu/eq/questionbank/help/";
	private static final String[] REQUIRED_RESOURCES = { "index.html", "getting-started.html", "question-capture.html",
			"question-search.html", "curriculum.html", "revision-output.html", "data-safety.html", "help.css" };

	@Test
	void allHelpResourcesArePackaged() {
		for (String resourceName : REQUIRED_RESOURCES) {

			// Every page linked from the Help index must be available from the
			// packaged application classpath.
			assertNotNull(HelpResourcesTest.class.getResource(HELP_ROOT + resourceName), resourceName);
		}
	}

	@Test
	void helpIndexLinksToEveryTopicAndSharedStylesheet() throws IOException {
		URL index = HelpResourcesTest.class.getResource(HELP_ROOT + "index.html");
		assertNotNull(index);
		String html;
		try (var input = index.openStream()) {
			html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		// Keep the maintained Help index aligned with the packaged topic set.
		assertTrue(html.contains("href=\"help.css\""));
		assertTrue(html.contains("href=\"getting-started.html\""));
		assertTrue(html.contains("href=\"question-capture.html\""));
		assertTrue(html.contains("href=\"question-search.html\""));
		assertTrue(html.contains("href=\"curriculum.html\""));
		assertTrue(html.contains("href=\"revision-output.html\""));
		assertTrue(html.contains("href=\"data-safety.html\""));
	}
}
