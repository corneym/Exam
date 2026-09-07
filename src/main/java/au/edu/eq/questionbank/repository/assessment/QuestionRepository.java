package au.edu.eq.questionbank.repository.assessment;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * Persistence boundary for examination questions.
 */
public interface QuestionRepository {

	/**
	 * Attaches captured source regions to an existing question that currently has
	 * no regions.
	 *
	 * @param questionId the persistent question identifier
	 * @param regions    one or more source regions in extraction order
	 * @return the updated question
	 * @throws NullPointerException     if {@code regions} or an element is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if the question is absent, already has
	 *                                  regions, or a region belongs to another
	 *                                  booklet
	 * @throws IllegalStateException    if the repository cannot persist the update
	 */
	Question attachRegions(long questionId, List<QuestionRegion> regions);
	/**
	 * Attaches captured source regions and optional source-question/shared-context
	 * relationships to an existing question that currently has no regions.
	 *
	 * @param questionId     the persistent question identifier
	 * @param regions        one or more source regions in extraction order
	 * @param sourceQuestion source-question identity, or {@code null}
	 * @param sharedContext  reusable shared context, or {@code null}
	 * @return the updated question
	 */
	Question attachRegions(long questionId, List<QuestionRegion> regions, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext);
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
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the non-blank question label
	 * @param questionText            supplementary question text, which may be
	 *                                blank
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in extraction
	 *                                order
	 * @param classification          the question's syllabus subtopic or descriptor
	 * @param preambleCaptureRequired whether shared or introductory material must
	 *                                be included during later capture
	 * @return the stored question with its persistent identifier
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 *                                  are invalid
	 * @throws IllegalStateException    if the repository cannot persist the
	 *                                  question
	 */
	Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired);
	/**
	 * Stores a classified question with optional source-question and shared-context
	 * relationships.
	 *
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the non-blank question label
	 * @param questionText            supplementary question text, which may be
	 *                                blank
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in extraction
	 *                                order
	 * @param classification          the question's syllabus subtopic or descriptor
	 * @param preambleCaptureRequired historical legacy preamble-capture evidence
	 * @param sourceQuestion          source-question identity, or {@code null}
	 * @param sharedContext           reusable shared context, or {@code null}
	 * @return the stored question
	 */
	Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext);
}
