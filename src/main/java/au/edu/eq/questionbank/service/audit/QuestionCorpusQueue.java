package au.edu.eq.questionbank.service.audit;

import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.Question;

/**
 * Builds filtered corpus work queues and aggregate completeness summaries.
 */
public final class QuestionCorpusQueue {

	private QuestionCorpusQueue() {
	}

	/**
	 * Assesses questions and returns those matching the queue filter in input order.
	 *
	 * @param questions ordered corpus questions
	 * @param filter completion, problem and examination restrictions
	 * @return immutable matching work items
	 */
	public static List<QuestionCorpusWorkItem> build(List<Question> questions, QuestionCorpusFilter filter) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		if (filter == null) {
			throw new NullPointerException("filter");
		}
		List<QuestionCorpusWorkItem> result = new ArrayList<>();
		for (Question question : questions) {
			if (question == null) {
				throw new NullPointerException("questions contains null");
			}
			QuestionCorpusWorkItem item = new QuestionCorpusWorkItem(question, QuestionCorpusAudit.assess(question));
			if (filter.matches(item)) {
				result.add(item);
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Counts complete questions and each independent corpus problem.
	 *
	 * @param questions questions to assess
	 * @return aggregate counts; one question may contribute to several problem counts
	 */
	public static QuestionCorpusSummary summarise(List<Question> questions) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		int complete = 0;
		int incomplete = 0;
		int missingQuestionSource = 0;
		int missingAnswer = 0;
		int unresolvedSharedContext = 0;
		int unknownResponseType = 0;
		for (Question question : questions) {
			if (question == null) {
				throw new NullPointerException("questions contains null");
			}
			QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
			if (status.isComplete()) {
				complete++;
			} else {
				incomplete++;
			}
			if (status.hasProblem(QuestionCorpusProblem.MISSING_QUESTION_SOURCE)) {
				missingQuestionSource++;
			}
			if (status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER)) {
				missingAnswer++;
			}
			if (status.hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT)) {
				unresolvedSharedContext++;
			}
			if (status.hasProblem(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE)) {
				unknownResponseType++;
			}
		}
		return new QuestionCorpusSummary(questions.size(), complete, incomplete, missingQuestionSource, missingAnswer,
				unresolvedSharedContext, unknownResponseType);
	}
}
