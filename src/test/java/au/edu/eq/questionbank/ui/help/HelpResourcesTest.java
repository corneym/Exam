package au.edu.eq.questionbank.ui.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HelpResourcesTest {

	private static final String HELP_ROOT = "/au/edu/eq/questionbank/help/";
	private static final String[] REQUIRED_RESOURCES = { "index.html", "getting-started.html", "corpus-dashboard.html",
			"exam-assets.html", "question-capture.html", "question-search.html", "curriculum.html",
			"revision-output.html", "data-safety.html", "help.css" };

	@Test
	void allHelpResourcesArePackaged() {
		for (String resourceName : REQUIRED_RESOURCES) {

			// Every page linked from maintained Help must be available from the
			// packaged application classpath.
			assertNotNull(HelpResourcesTest.class.getResource(HELP_ROOT + resourceName), resourceName);
		}
	}

	@Test
	void dashboardInspectionHelpMatchesSearchNarrowing() throws IOException {
		String dashboardHtml = readResource("corpus-dashboard.html").replaceAll("\\s+", " ");
		String searchHtml = readResource("question-search.html").replaceAll("\\s+", " ");

		// Dashboard Help must describe both structural inspection scopes without
		// presenting inspection as another capture or Exam/Assets operation.
		assertTrue(dashboardHtml.contains("Inspect Questions"));
		assertTrue(dashboardHtml.contains("Question Search narrowed to that Exam"));
		assertTrue(dashboardHtml.contains("narrow Search to that booklet"));

		// Search Help must explain that Dashboard narrowing survives the ordinary
		// Current Syllabus versus All Questions choice.
		assertTrue(searchHtml.contains("Narrowed to"));
		assertTrue(searchHtml.contains("Current syllabus"));
		assertTrue(searchHtml.contains("All Questions"));
		assertTrue(searchHtml.contains("constraint remains active"));
		assertTrue(searchHtml.contains("within that Exam or booklet"));
	}

	@Test
	void helpIndexLinksToEveryTopicAndSharedStylesheet() throws IOException {
		String html = readResource("index.html");

		// Keep the maintained Help index aligned with the complete packaged topic set.
		assertTrue(html.contains("href=\"help.css\""));
		assertTrue(html.contains("href=\"getting-started.html\""));
		assertTrue(html.contains("href=\"corpus-dashboard.html\""));
		assertTrue(html.contains("href=\"exam-assets.html\""));
		assertTrue(html.contains("href=\"question-capture.html\""));
		assertTrue(html.contains("href=\"question-search.html\""));
		assertTrue(html.contains("href=\"curriculum.html\""));
		assertTrue(html.contains("href=\"revision-output.html\""));
		assertTrue(html.contains("href=\"data-safety.html\""));
	}

	@Test
	void mcqExplanationHelpMatchesCompletionSemantics() throws IOException {
		String captureHtml = readResource("question-capture.html");
		String assetsHtml = readResource("exam-assets.html");

		// HTML formatting may split prose across source lines, so collapse whitespace
		// before asserting the user-visible explanation semantics.
		String captureText = captureHtml.replaceAll("\\s+", " ");
		String assetsText = assetsHtml.replaceAll("\\s+", " ");

		// Explanation metadata now creates required completion work rather than
		// describing an optional enhancement to an otherwise complete MCQ.
		assertTrue(captureText.contains("Missing MCQ explanations"));
		assertTrue(captureText.contains("explanation regions are required"));
		assertTrue(captureText.contains("final required explanation"));
		assertTrue(assetsText.contains("Contains answer explanations"));
		assertTrue(assetsText.contains("completion requirement"));
	}

	@Test
	void sprint12HelpUsesDashboardOwnedWorkflow() throws IOException {
		String gettingStartedHtml = readResource("getting-started.html");
		String dashboardHtml = readResource("corpus-dashboard.html");
		String searchHtml = readResource("question-search.html");

		// Sprint 12 made Dashboard the application home and moved lifecycle and
		// operational work onto the selected persisted Exam rather than menu state.
		assertTrue(gettingStartedHtml.contains("opens on the Corpus Dashboard"));
		assertTrue(dashboardHtml.contains("Mark Complete"));
		assertTrue(dashboardHtml.contains("Mark Active"));
		assertTrue(dashboardHtml.contains("Manage Exam / Assets"));
		assertTrue(dashboardHtml.contains("Import Legacy"));

		// The retired Questions-menu Dashboard entry must not reappear in maintained
		// user documentation.
		assertFalse(searchHtml.contains("Questions → Corpus Dashboard"));
	}

	private String readResource(String resourceName) throws IOException {
		URL resource = HelpResourcesTest.class.getResource(HELP_ROOT + resourceName);
		assertNotNull(resource, resourceName);

		// Read the packaged resource exactly as the Help viewer receives it so tests
		// validate deployed content rather than source-tree assumptions.
		try (var input = resource.openStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
