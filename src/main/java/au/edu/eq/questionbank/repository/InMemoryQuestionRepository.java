package au.edu.eq.questionbank.repository;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;

public class InMemoryQuestionRepository implements QuestionRepository {

	private final List<Question> questions;

	public InMemoryQuestionRepository() {
		SourceDocument document = new SourceDocument(1, "chemistry/QCAA/2024/snr_chemistry_24_ea_p1_mc_question.pdf");

		Exam exam = new Exam(1, "Chemistry", 2024, "Multiple Choice", document);
		Question question1 = new Question(1, exam, "Q1", "Sample question one",
				List.of(new QuestionRegion(3, 0.08, 0.25, 0.84, 0.35)));
		Question question2 = new Question(2, exam, "Q2", "Sample question two",
				List.of(new QuestionRegion(4, 0.10, 0.20, 0.80, 0.30), new QuestionRegion(5, 0.20, 0.25, 0.60, 0.25)));
		Question question3 = new Question(3, exam, "Q3", "Sample question three",
				List.of(new QuestionRegion(5, 0.10, 0.60, 0.85, 0.30)));

		questions = List.of(question1, question2, question3);
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
