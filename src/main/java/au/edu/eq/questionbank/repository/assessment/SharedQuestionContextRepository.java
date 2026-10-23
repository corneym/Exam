package au.edu.eq.questionbank.repository.assessment;

import java.util.List;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedContextContentPart;
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
	 * Replaces the editable content of an existing shared context while preserving
	 * its persistent identity and therefore every Question relationship to it.
	 *
	 * @param context the existing shared context to replace
	 * @param label   the replacement non-blank display label
	 * @param regions one or more replacement regions in source order
	 * @return the updated shared context with its original persistent identity
	 * @throws NullPointerException     if {@code context}, {@code regions}, or a
	 *                                  region is {@code null}
	 * @throws IllegalArgumentException if the label is blank, no regions are
	 *                                  supplied, or the context no longer exists
	 * @throws IllegalStateException    if the replacement cannot be persisted
	 */
	SharedQuestionContext replace(SharedQuestionContext context, String label,
			List<SharedQuestionContextRegion> regions);

	/**
	 * Replaces all ordered PDF/image content while retaining context identity and
	 * existing Question links. The replacement is atomic.
	 *
	 * @param context      persisted context being corrected
	 * @param label        non-blank corrected label
	 * @param contentParts non-empty mixed content in final display order
	 * @return persisted replacement with unchanged context identifier
	 */
	SharedQuestionContext replaceContentParts(SharedQuestionContext context, String label,
			List<? extends SharedContextContentPart> contentParts);

	/**
	 * Persists a shared context and all of its ordered regions atomically.
	 *
	 * @param booklet the booklet containing the regions
	 * @param label   the non-blank display label
	 * @param regions one or more regions in source order
	 * @return the persisted shared context
	 */
	SharedQuestionContext save(ExamBooklet booklet, String label, List<SharedQuestionContextRegion> regions);

	/**
	 * Persists ordered PDF regions, PNG images or both within one transaction.
	 *
	 * @param booklet      owning booklet
	 * @param label        non-blank context label
	 * @param contentParts non-empty mixed content in final display order
	 * @return persisted context
	 */
	SharedQuestionContext saveContentParts(ExamBooklet booklet, String label,
			List<? extends SharedContextContentPart> contentParts);
}
