package au.edu.eq.questionbank.service.audit;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;

/**
 * Calculates booklet-level corpus audit state from authoritative persisted
 * domain data.
 */
public final class BookletCorpusAudit {

	private BookletCorpusAudit() {
	}

	/**
	 * Audits one booklet.
	 * <p>
	 * Questions belonging to other booklets are ignored, allowing callers to supply
	 * an Exam-wide or Subject-wide Question snapshot.
	 *
	 * @param booklet              booklet to assess
	 * @param questions            current Question snapshot
	 * @param assignedAnswerFile   AnswerFile assigned to this booklet, or
	 *                             {@code null}
	 * @param questionPdfAvailable whether the recorded Question PDF is physically
	 *                             available
	 * @return calculated booklet audit status
	 */
	public static BookletCorpusStatus assess(ExamBooklet booklet, List<Question> questions,
			AnswerFile assignedAnswerFile, boolean questionPdfAvailable) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		for (Question question : questions) {
			if (question == null) {
				throw new NullPointerException("questions contains null");
			}
		}

		// Restrict the supplied corpus snapshot by persistent booklet identity so
		// reconstructed domain objects are handled correctly.
		List<Question> bookletQuestions = questions.stream()
				.filter(question -> question.getBooklet().getId() == booklet.getId()).toList();
		int encounteredTopLevelQuestions = countEncounteredTopLevelQuestions(bookletQuestions);
		QuestionCorpusSummary questionSummary = QuestionCorpusQueue.summarise(bookletQuestions);
		McqExplanationCoverage explanationCoverage = assessMcqExplanationCoverage(bookletQuestions, assignedAnswerFile);
		EnumSet<BookletCorpusFinding> findings = EnumSet.noneOf(BookletCorpusFinding.class);
		if (!questionPdfAvailable) {

			// Missing physical source material is an advisory audit finding. It does not
			// rewrite the user's Exam lifecycle declaration.
			findings.add(BookletCorpusFinding.MISSING_QUESTION_PDF);
		}
		Integer expectedQuestionCount = booklet.getExpectedQuestionCount();
		if (expectedQuestionCount != null && expectedQuestionCount.intValue() != encounteredTopLevelQuestions) {

			// Report the discrepancy without guessing whether the expected count or the
			// captured source structure is wrong.
			findings.add(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH);
		}

		// AnswerFile assignment and explanation coverage are descriptive state. Missing
		// explanations never manufacture an ordinary completeness finding.
		return new BookletCorpusStatus(booklet, questionPdfAvailable, assignedAnswerFile, encounteredTopLevelQuestions,
				bookletQuestions.size(), questionSummary, explanationCoverage, findings);
	}

	private static McqExplanationCoverage assessMcqExplanationCoverage(List<Question> questions,
			AnswerFile assignedAnswerFile) {
		if (assignedAnswerFile == null || !assignedAnswerFile.hasAnswerExplanations()) {

			// The retrofit workflow is unavailable unless the booklet's authoritative
			// AnswerFile explicitly declares explanation material.
			return new McqExplanationCoverage(false, 0, 0);
		}
		int eligibleQuestions = 0;
		int capturedExplanations = 0;
		for (Question question : questions) {
			if (question.getResponseType() != QuestionResponseType.MULTIPLE_CHOICE) {
				continue;
			}

			// Reuse ordinary Question audit truth for the A-D Answer check. This matches
			// the retrofit rule without importing UI-specific validation into audit code.
			if (!QuestionCorpusAudit.assess(question).answerComplete()) {
				continue;
			}
			eligibleQuestions++;
			if (!question.getAnswer().getRegions().isEmpty()) {

				// For an MCQ, persisted Answer regions are supplementary explanation
				// material; the A-D answer remains authoritative ordinary completeness.
				capturedExplanations++;
			}
		}
		return new McqExplanationCoverage(true, eligibleQuestions, capturedExplanations);
	}

	private static int countEncounteredTopLevelQuestions(List<Question> questions) {
		Set<TopLevelQuestionKey> encountered = new HashSet<>();
		for (Question question : questions) {
			if (question.hasSourceQuestion()) {

				// Multipart records sharing one persisted SourceQuestion represent one
				// top-level Question regardless of the number of stored parts.
				encountered.add(TopLevelQuestionKey.sourceQuestion(question.getSourceQuestion().getId()));
				continue;
			}

			// Ordinary Questions without source grouping are independently encountered
			// top-level Questions.
			encountered.add(TopLevelQuestionKey.question(question.getId()));
		}
		return encountered.size();
	}

	private record TopLevelQuestionKey(boolean sourceQuestionIdentity, long id) {

		private static TopLevelQuestionKey question(long id) {

			// Keep Question and SourceQuestion identifier namespaces distinct because
			// their separate database tables may legitimately contain the same number.
			return new TopLevelQuestionKey(false, id);
		}

		private static TopLevelQuestionKey sourceQuestion(long id) {

			// SourceQuestion identity collapses every persisted multipart member into one
			// encountered top-level Question.
			return new TopLevelQuestionKey(true, id);
		}
	}
}
