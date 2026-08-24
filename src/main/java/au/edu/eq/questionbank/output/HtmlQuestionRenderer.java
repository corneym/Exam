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

	/**
	 * Renders question images in list order using paths relative to the output
	 * document's directory.
	 *
	 * @param questionImages images to include, in document order
	 * @param outputFile     destination HTML file
	 * @throws IOException if the output document cannot be written
	 */
	public void render(List<Path> questionImages, Path outputFile) throws IOException {

		StringBuilder html = new StringBuilder();

		html.append("""
				<!DOCTYPE html>
				<html>
				<head>
				    <meta charset="UTF-8">
				    <title>Exam Questions</title>
				    <style>
				        body {
				            font-family: Arial, sans-serif;
				            max-width: 900px;
				            margin: 40px auto;
				        }

				        .question {
				            margin-bottom: 30px;
				            page-break-inside: avoid;
				        }

				        .question img {
				            width: 100%;
				            height: auto;
				        }
				    </style>
				</head>
				<body>
				""");

		for (Path image : questionImages) {

			Path relativeImage = outputFile.getParent().relativize(image);

			html.append("""
					<div class="question">
					    <img src="%s">
					</div>
					""".formatted(relativeImage.toString().replace('\\', '/')));
		}

		html.append("""
				</body>
				</html>
				""");

		Files.writeString(outputFile, html.toString());
	}
}
