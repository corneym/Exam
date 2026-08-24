package au.edu.eq.questionbank.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Question;

public class InMemoryQuestionRepository implements QuestionRepository {

	private ArrayList<Question> questions = new ArrayList<Question>();

	public InMemoryQuestionRepository() {
	}

	@Override
	public List<Question> findAll() {
		return questions;
	}

	@Override
	public Optional<Question> findById(long id) {
		return questions.stream().filter(q -> q.getId() == id).findFirst();
	}

	@Override
	public void save(Question question) {
		questions.addQuestion(question);
	}
}
