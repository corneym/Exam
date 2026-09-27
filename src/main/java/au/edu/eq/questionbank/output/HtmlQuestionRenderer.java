package au.edu.eq.questionbank.output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a simple printable HTML document containing pre-rendered question
 * images.
 */
public class HtmlQuestionRenderer {

	private static final String STYLESHEET_RESOURCE = "/au/edu/eq/questionbank/output/question/question.css";

	/**
	 * Creates a renderer for question images in HTML documents.
	 */
	public HtmlQuestionRenderer() {
	}

	/**
	 * Renders question images in list order using paths relative to the output
	 * document's directory.
	 *
	 * @param questionImages images to include, in document order
	 * @param outputFile     destination HTML file with a parent directory against
	 *                       which image paths can be relativized
	 * @throws IOException              if the output document cannot be written
	 * @throws NullPointerException     if either argument, an image path, or the
	 *                                  output parent directory is {@code null}
	 * @throws IllegalArgumentException if an image cannot be relativized against
	 *                                  the output directory
	 */
	public void render(List<Path> questionImages, Path outputFile) throws IOException {
		String stylesheet = loadStylesheet();
		StringBuilder html = new StringBuilder();
		html.append("""
				<!DOCTYPE html>
				<html>
				<head>
				    <meta charset="UTF-8">
				    <title>Exam Questions</title>
				    <style>
				""");

		// CSS remains embedded in the generated document so this simple HTML export
		// stays self-contained, while its maintained source lives in resources.
		html.append(stylesheet);
		if (!stylesheet.endsWith(System.lineSeparator()) && !stylesheet.endsWith("\n")) {
			html.append(System.lineSeparator());
		}
		html.append("""
				    </style>
				</head>
				<body>
				""");
		for (Path image : questionImages) {

			// Keep links portable with the HTML directory and escape filenames before
			// inserting attributes.
			Path relativeImage = outputFile.getParent().relativize(image);
			String source = escapeAttribute(relativeImage.toString().replace('\\', '/'));
			html.append("""
					<div class="question">
					    <img src="%s">
					</div>
					""".formatted(source));
		}
		html.append("""
				</body>
				</html>
				""");
		Files.writeString(outputFile, html.toString());
	}

	private String escapeAttribute(String value) {
		return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private String loadStylesheet() throws IOException {
		try (java.io.InputStream input = HtmlQuestionRenderer.class.getResourceAsStream(STYLESHEET_RESOURCE)) {
			if (input == null) {

				// The stylesheet is a required packaged application resource. Missing
				// CSS therefore indicates an invalid application build or package.
				throw new IllegalStateException("Packaged question stylesheet not found: " + STYLESHEET_RESOURCE);
			}
			return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}
}
