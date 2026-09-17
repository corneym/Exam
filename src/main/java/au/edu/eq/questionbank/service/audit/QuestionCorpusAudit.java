package au.edu.eq.questionbank.service.audit;

import java.util.EnumSet;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;

/**
 * Evaluates persisted Question data against corpus-completeness rules.
 */
public final class QuestionCorpusAudit {

	private QuestionCorpusAudit() {
	}

	/**
	 * Evaluates one Question.
	 *
	 * @param question Question to evaluate
	 * @return its current corpus-completeness status
	 */
	public static QuestionCorpusStatus assess(Question question) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		EnumSet<QuestionCorpusProblem> problems = EnumSet.noneOf(QuestionCorpusProblem.class);
		boolean questionSourceCaptured = !question.getRegions().isEmpty();
		if (!questionSourceCaptured) {
			problems.add(QuestionCorpusProblem.MISSING_QUESTION_SOURCE);
		}
		boolean sharedContextResolved = !question.isSharedContextUnresolved();
		if (!sharedContextResolved) {
			problems.add(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT);
		}
		boolean responseTypeResolved = question.getResponseType() != QuestionResponseType.UNKNOWN;
		boolean answerComplete = false;
		if (!responseTypeResolved) {
			problems.add(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE);
			/*
			 * UNKNOWN response type is itself the actionable problem. Do not also report
			 * MISSING_ANSWER because the required Answer representation is not yet known.
			 */
		} else {
			answerComplete = switch (question.getResponseType()) {
			case MULTIPLE_CHOICE -> hasCompleteMultipleChoiceAnswer(question);
			case WRITTEN_RESPONSE -> hasCompleteWrittenAnswer(question);
			case UNKNOWN -> false;
			};
			if (!answerComplete) {
				problems.add(QuestionCorpusProblem.MISSING_ANSWER);
			}
		}
		return new QuestionCorpusStatus(questionSourceCaptured, responseTypeResolved, answerComplete,
				sharedContextResolved, problems);
	}

	private static boolean hasCompleteMultipleChoiceAnswer(Question question) {
		if (!question.hasAnswer()) {
			return false;
		}
		String answerText = question.getAnswer().getAnswerText();
		if (answerText == null) {
			return false;
		}
		String answer = answerText.trim();
		return answer.equalsIgnoreCase("A") || answer.equalsIgnoreCase("B") || answer.equalsIgnoreCase("C")
				|| answer.equalsIgnoreCase("D");
	}

	private static boolean hasCompleteWrittenAnswer(Question question) {
		return question.hasAnswer() && !question.getAnswer().getRegions().isEmpty();
	}
}
