package au.edu.eq.questionbank.repository.assessment;

import java.util.List;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;

/**
 * Persistence boundary for reusable shared question context.
 */
public interface SharedQuestionContextRepository {

	List<SharedQuestionContext> findByBooklet(ExamBooklet booklet);
	SharedQuestionContext save(ExamBooklet booklet, String label, List<SharedQuestionContextRegion> regions);
}
