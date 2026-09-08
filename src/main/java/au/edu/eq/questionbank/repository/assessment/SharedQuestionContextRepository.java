package au.edu.eq.questionbank.repository.assessment;

import java.util.List;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;

/**
 * Persistence boundary for reusable shared question context.
 */
public interface SharedQuestionContextRepository {

	/**
	 * Finds the reusable contexts owned by one examination booklet.
	 *
	 * @param booklet the owning booklet
	 * @return an immutable list whose region lists retain their stored order
	 */
	List<SharedQuestionContext> findByBooklet(ExamBooklet booklet);

	/**
	 * Persists a shared context and all of its ordered regions atomically.
	 *
	 * @param booklet the booklet containing the regions
	 * @param label   the non-blank display label
	 * @param regions one or more regions in source order
	 * @return the persisted shared context
	 */
	SharedQuestionContext save(ExamBooklet booklet, String label, List<SharedQuestionContextRegion> regions);
}
