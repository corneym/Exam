package au.edu.eq.questionbank.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

/**
 * Transient question repository that retains saved questions in insertion
 * order. Intended for proof-of-concept workflows and tests rather than durable
 * storage.
 */
public class InMemoryQuestionRepository implements QuestionRepository {

	private final List<Question> questions;
	private long nextId = 1;

	/**
	 * Creates an empty repository.
	 */
	public InMemoryQuestionRepository() {
		questions = new ArrayList<Question>();
	}

	@Override
	public List<Question> findAll() {
		return List.copyOf(questions);
	}

	@Override
	public Optional<Question> findById(long id) {
		for (Question question : questions) {
			if (question.getId() == id) {
				return Optional.of(question);
			}
		}
		return Optional.empty();
	}

	@Override
	public Question save(Exam exam, String questionCode, String questionText, List<QuestionRegion> regions,
			CurriculumNode classification) {
		Question question = new Question(nextId++, exam, questionCode, questionText, regions, classification);
		questions.add(question);
		return question;
	}
}
