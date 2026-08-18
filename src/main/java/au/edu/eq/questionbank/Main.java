package au.edu.eq.questionbank;

import java.nio.file.Files;
import java.nio.file.Path;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.QuestionRepository;

public class Main {

	public static void main(String[] args) {

		QuestionRepository repository = new InMemoryQuestionRepository();

		PdfStore pdfStore = new PdfStore();

		for (Question question : repository.findAll()) {
			SourceDocument document = question.getExam().getSourceDocument();
			Path pdfPath = pdfStore.resolve(document.getRelativePath());
			System.out.println(question.getExam().getSubject());
			System.out.println(question.getExam().getYear());
			System.out.println(question.getQuestionCode());
			System.out.println("Page: " + question.getPageNumber());
			System.out.println(pdfPath);
			if (Files.isRegularFile(pdfPath)) {
				System.out.println("PDF found");
			} else {
				System.out.println("PDF NOT FOUND");
			}

			System.out.println();
		}
	}
}
