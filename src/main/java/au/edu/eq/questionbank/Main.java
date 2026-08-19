package au.edu.eq.questionbank;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.output.HtmlQuestionRenderer;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.QuestionRepository;

public class Main {

	public static void main(String[] args) throws Exception {

		ApplicationConfig config = ApplicationConfig.load(Path.of("questionbank.properties"));
		QuestionRepository repository = new InMemoryQuestionRepository();

		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		QuestionExtractor extractor = new QuestionExtractor();

		Path outputDir = Path.of("target", "extracted");
		Files.createDirectories(outputDir);
		List<Path> questionImages = new ArrayList<>();

		for (Question question : repository.findAll()) {
			SourceDocument document = question.getExam().getSourceDocument();
			Path pdfPath = pdfStore.resolve(document.getRelativePath());
			System.out.println(question.getExam().getSubject());
			System.out.println(question.getExam().getYear());
			System.out.println(question.getQuestionCode());
			System.out.println(pdfPath);

			if (Files.isRegularFile(pdfPath)) {
				System.out.println("PDF found");
				Path outputFile = outputDir.resolve("question" + question.getId() + ".png");
				extractor.extractQuestion(pdfPath, question, outputFile.toFile());
				questionImages.add(outputFile);
				System.out.println("Extracted to: " + outputFile);
			} else {
				System.out.println("PDF NOT FOUND");
			}
			System.out.println();
		}

		HtmlQuestionRenderer htmlRenderer = new HtmlQuestionRenderer();
		Path htmlFile = outputDir.resolve("questions.html");
		htmlRenderer.render(questionImages, htmlFile);
		System.out.println("HTML written to: " + htmlFile.toAbsolutePath());
	}
}
