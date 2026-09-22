package au.edu.eq.questionbank.repository.assessment;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * Persistence boundary for original source-question identities.
 */
public interface SourceQuestionRepository {

	/**
	 * Finds source-question identities owned by one booklet.
	 *
	 * @param booklet the owning booklet
	 * @return an immutable list in repository order
	 */
	List<SourceQuestion> findByBooklet(ExamBooklet booklet);

	/**
	 * Finds a source-question identity by its booklet-scoped natural key.
	 *
	 * @param booklet            the owning booklet
	 * @param sourceQuestionCode the source-question code
	 * @return the stored identity, or an empty optional
	 */
	Optional<SourceQuestion> findByBookletAndCode(ExamBooklet booklet, String sourceQuestionCode);

	/**
	 * Persists a new source-question identity with an unresolved preamble state.
	 *
	 * @param booklet            the owning booklet
	 * @param sourceQuestionCode the booklet-scoped source-question code
	 * @return the persisted source question
	 * @throws IllegalArgumentException if the code is blank or already exists for
	 *                                  the booklet
	 */
	SourceQuestion save(ExamBooklet booklet, String sourceQuestionCode);

	/**
	 * Replaces the persisted preamble state of an existing source question.
	 *
	 * @param sourceQuestion the persisted source-question identity
	 * @param preambleStatus the replacement state
	 * @return the source question with the persisted replacement state
	 * @throws IllegalStateException if the identified row cannot be updated
	 */
	SourceQuestion updatePreambleStatus(SourceQuestion sourceQuestion, SharedContextStatus preambleStatus);
}
