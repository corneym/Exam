package au.edu.eq.questionbank.service.audit;

import java.util.Set;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamBooklet;

/**
 * Calculated audit state for one Exam booklet.
 *
 * @param booklet                          audited booklet
 * @param questionPdfAvailable             whether the booklet's Question PDF is
 *                                         currently available
 * @param assignedAnswerFile               booklet-assigned AnswerFile, or
 *                                         {@code null} when no assignment
 *                                         exists
 * @param encounteredTopLevelQuestionCount encountered top-level/source
 *                                         Questions
 * @param questionPartCount                stored Question records belonging to
 *                                         the booklet
 * @param questionSummary                  aggregate Question-level audit state
 * @param mcqExplanationCoverage           optional MCQ explanation coverage
 * @param findings                         advisory booklet-level findings
 */
public record BookletCorpusStatus(ExamBooklet booklet, boolean questionPdfAvailable, AnswerFile assignedAnswerFile,
		int encounteredTopLevelQuestionCount, int questionPartCount, QuestionCorpusSummary questionSummary,
		McqExplanationCoverage mcqExplanationCoverage, Set<BookletCorpusFinding> findings) {

	/**
	 * Creates an immutable booklet audit snapshot.
	 */
	public BookletCorpusStatus {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (encounteredTopLevelQuestionCount < 0) {
			throw new IllegalArgumentException("encounteredTopLevelQuestionCount must not be negative");
		}
		if (questionPartCount < 0) {
			throw new IllegalArgumentException("questionPartCount must not be negative");
		}
		if (encounteredTopLevelQuestionCount > questionPartCount) {
			throw new IllegalArgumentException("Top-level Question count cannot exceed Question-part count");
		}
		if (questionSummary == null) {
			throw new NullPointerException("questionSummary");
		}
		if (mcqExplanationCoverage == null) {
			throw new NullPointerException("mcqExplanationCoverage");
		}
		if (findings == null) {
			throw new NullPointerException("findings");
		}
		if (questionSummary.totalQuestions() != questionPartCount) {
			throw new IllegalArgumentException("Question summary total must equal questionPartCount");
		}
		if (assignedAnswerFile != null && assignedAnswerFile.getExam().getId() != booklet.getExam().getId()) {
			throw new IllegalArgumentException("Assigned AnswerFile must belong to the booklet's Exam");
		}
		if (mcqExplanationCoverage
				.explanationCapable() != (assignedAnswerFile != null && assignedAnswerFile.hasAnswerExplanations())) {
			throw new IllegalArgumentException("MCQ explanation capability must match the assigned AnswerFile");
		}

		// Snapshot caller-owned finding collections so Dashboard state cannot change
		// after the audit result has been constructed.
		findings = Set.copyOf(findings);
	}

	/**
	 * Returns the user-declared expected top-level Question count.
	 *
	 * @return expected count, or {@code null} when none has been recorded
	 */
	public Integer expectedTopLevelQuestionCount() {

		// Expected count remains authoritative booklet metadata rather than duplicated
		// calculated state inside the audit record.
		return booklet.getExpectedQuestionCount();
	}

	/**
	 * Tests whether the booklet has a particular calculated finding.
	 *
	 * @param finding finding to inspect
	 * @return whether the finding is present
	 */
	public boolean hasFinding(BookletCorpusFinding finding) {
		if (finding == null) {
			throw new NullPointerException("finding");
		}

		// Structural/advisory findings are kept distinct from Question-level corpus
		// problems even though both may require Dashboard attention.
		return findings.contains(finding);
	}

	/**
	 * Returns whether either booklet findings or incomplete Questions require
	 * attention.
	 *
	 * @return whether the booklet has audit work to review
	 */
	public boolean requiresAttention() {

		// Explanation coverage is deliberately excluded. An MCQ with a valid A-D
		// Answer remains complete when no explanation region has been captured.
		return !findings.isEmpty() || questionSummary.incompleteQuestions() > 0;
	}
}
