package au.edu.eq.questionbank.repository;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

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
	 * Stores a classified question and its ordered source regions.
	 *
	 * @param exam           the exam containing the question
	 * @param questionCode   the non-blank question label
	 * @param questionText   supplementary question text, which may be blank
	 * @param regions        source regions in extraction order
	 * @param classification the question's syllabus subtopic or descriptor
	 * @return the stored question with its persistent identifier
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 *                                  are invalid
	 * @throws IllegalStateException    if the repository cannot persist the
	 *                                  question
	 */
	Question save(Exam exam, String questionCode, String questionText, List<QuestionRegion> regions,
			CurriculumNode classification);
}
