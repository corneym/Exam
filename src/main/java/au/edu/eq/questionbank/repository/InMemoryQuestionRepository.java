package au.edu.eq.questionbank.repository;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;

public class InMemoryQuestionRepository implements QuestionRepository {

	private final List<Question> questions;

	public InMemoryQuestionRepository() {
		SourceDocument document = new SourceDocument(1, "chemistry/QCAA/2024/snr_chemistry_24_ea_p1_mc_question.pdf");

		Exam exam = new Exam(1, "Chemistry", 2024, "Multiple Choice", document);

		questions = List.of(new Question(1, exam, "Q1", "Sample question one", 2),

				new Question(2, exam, "Q2", "Sample question two", 4));
	}

	@Override
	public List<Question> findAll() {
		return questions;
	}

	@Override
	public Optional<Question> findById(long id) {
		return questions.stream().filter(q -> q.getId() == id).findFirst();
	}

}
