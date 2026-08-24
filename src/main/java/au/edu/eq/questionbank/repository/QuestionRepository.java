package au.edu.eq.questionbank.repository;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Question;

/**
 * Persistence boundary for examination questions.
 */
public interface QuestionRepository {

	/**
	 * Returns all stored questions in repository-defined order.
	 *
	 * @return the stored questions
	 */
	List<Question> findAll();

	/**
	 * Finds a question by its persistent identifier.
	 *
	 * @param id the question identifier
	 * @return the matching question, or an empty optional when it is absent
	 */
	Optional<Question> findById(long id);

	/**
	 * Stores a question.
	 *
	 * @param question the question to store
	 */
	void save(Question question);
}
