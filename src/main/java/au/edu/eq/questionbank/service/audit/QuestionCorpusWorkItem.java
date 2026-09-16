package au.edu.eq.questionbank.service.audit;

import au.edu.eq.questionbank.model.Question;

/**
 * One Question together with its current corpus-completeness assessment.
 *
 * @param question persisted Question
 * @param status   current completeness status
 */
public record QuestionCorpusWorkItem(Question question, QuestionCorpusStatus status) {

	public QuestionCorpusWorkItem {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (status == null) {
			throw new NullPointerException("status");
		}
	}
}
