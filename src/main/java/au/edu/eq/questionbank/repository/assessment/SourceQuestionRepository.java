package au.edu.eq.questionbank.repository.assessment;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * Persistence boundary for original source-question identities.
 */
public interface SourceQuestionRepository {

	List<SourceQuestion> findByBooklet(ExamBooklet booklet);
	Optional<SourceQuestion> findByBookletAndCode(ExamBooklet booklet, String sourceQuestionCode);
	SourceQuestion save(ExamBooklet booklet, String sourceQuestionCode);
}
