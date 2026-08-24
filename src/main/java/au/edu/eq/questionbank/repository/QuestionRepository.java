package au.edu.eq.questionbank.repository;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Question;

public interface QuestionRepository {

	List<Question> findAll();

	Optional<Question> findById(long id);

	void save(Question question);
}