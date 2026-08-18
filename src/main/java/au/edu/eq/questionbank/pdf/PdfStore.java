package au.edu.eq.questionbank.pdf;

import java.nio.file.Path;

public class PdfStore {

	private static final Path PDF_ROOT = Path.of("D:/git/Exam/data/exams");

	public Path resolve(String relativePath) {
		return PDF_ROOT.resolve(relativePath);
	}
}
