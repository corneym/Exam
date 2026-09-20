package au.edu.eq.questionbank.ui.search;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionSourceOrder;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;

/**
 * UI-facing Search Questions result.
 * <p>
 * Current-syllabus results carry the current applicability that caused the
 * Question to match. All-bank results deliberately carry no inferred current
 * applicability because that scope does not evaluate curriculum mappings.
 */
final class QuestionSearchResult {

	private static final Comparator<QuestionSearchResult> SOURCE_ORDER = Comparator
			.comparing(QuestionSearchResult::question, QuestionSourceOrder.comparator());
	private final Question question;
	private final QuestionSearchScope scope;
	private final List<CurriculumNode> currentApplicability;

	private QuestionSearchResult(Question question, QuestionSearchScope scope,
			List<CurriculumNode> currentApplicability) {
		this.question = Objects.requireNonNull(question, "question");
		this.scope = Objects.requireNonNull(scope, "scope");
		this.currentApplicability = List.copyOf(Objects.requireNonNull(currentApplicability, "currentApplicability"));

		// Current-syllabus results must retain the applicability that justified the
		// match. All Questions deliberately has none because mappings were not
		// evaluated in that mode.
		if (scope == QuestionSearchScope.CURRENT_SYLLABUS && this.currentApplicability.isEmpty()) {
			throw new IllegalArgumentException("Current-syllabus result requires current applicability");
		}
		if (scope == QuestionSearchScope.ALL_QUESTIONS && !this.currentApplicability.isEmpty()) {
			throw new IllegalArgumentException("All-questions result must not claim current applicability");
		}
	}

	static List<QuestionSearchResult> allQuestionResults(List<Question> questions) {
		Objects.requireNonNull(questions, "questions");

		// The repository's iteration order is persistence-defined, so All Questions
		// must explicitly use provider/year/booklet/natural Question order.
		return questions.stream().map(QuestionSearchResult::allQuestions).sorted(SOURCE_ORDER).toList();
	}

	static QuestionSearchResult allQuestions(Question question) {

		// An all-bank result represents stored identity only. Empty applicability means
		// "not evaluated in this scope", not "not applicable".
		return new QuestionSearchResult(question, QuestionSearchScope.ALL_QUESTIONS, List.of());
	}

	static QuestionSearchResult currentSyllabus(QuestionRetrievalResult result) {
		Objects.requireNonNull(result, "result");

		// Preserve the retrieval service's authoritative applicability rather than
		// recalculating curriculum mappings in the UI layer.
		return new QuestionSearchResult(result.getQuestion(), QuestionSearchScope.CURRENT_SYLLABUS,
				result.getCurrentApplicability());
	}

	static List<QuestionSearchResult> currentSyllabusResults(List<QuestionRetrievalResult> results) {
		Objects.requireNonNull(results, "results");

		// Convert the retrieval-service results and impose the shared source ordering
		// used by other teacher-facing Question queues.
		return results.stream().map(QuestionSearchResult::currentSyllabus).sorted(SOURCE_ORDER).toList();
	}

	List<CurriculumNode> currentApplicability() {
		return currentApplicability;
	}

	Question question() {
		return question;
	}

	QuestionSearchScope scope() {
		return scope;
	}
}
