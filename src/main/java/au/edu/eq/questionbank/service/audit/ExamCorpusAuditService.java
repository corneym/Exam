package au.edu.eq.questionbank.service.audit;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;

/**
 * Loads persisted Exam corpus state and delegates calculated completeness rules
 * to the corpus-audit model.
 */
public final class ExamCorpusAuditService {

	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;
	private final QuestionRepository questionRepository;
	private final PdfStore pdfStore;

	/**
	 * Creates the persisted Exam corpus audit service.
	 *
	 * @param examWriter         Exam and booklet persistence reader
	 * @param answerWriter       AnswerFile persistence reader
	 * @param questionRepository Question persistence reader
	 * @param pdfStore           managed Exam PDF store
	 */
	public ExamCorpusAuditService(SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter,
			QuestionRepository questionRepository, PdfStore pdfStore) {
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (questionRepository == null) {
			throw new NullPointerException("questionRepository");
		}
		if (pdfStore == null) {
			throw new NullPointerException("pdfStore");
		}

		// Keep persistence and filesystem access at this boundary; pure audit
		// calculators remain independent of SQLite and managed-file locations.
		this.examWriter = examWriter;
		this.answerWriter = answerWriter;
		this.questionRepository = questionRepository;
		this.pdfStore = pdfStore;
	}

	/**
	 * Loads calculated audit state for every persisted Exam belonging to the
	 * supplied Subject.
	 *
	 * @param subject authoritative Working Subject
	 * @return immutable Exam audit snapshots
	 * @throws SQLException if persistence cannot be read
	 */
	public List<ExamCorpusStatus> assessSubject(Subject subject) throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
		}

		// Load the Subject's Question corpus once and reuse it for
		// all of its Exams.
		List<Question> subjectQuestions = questionRepository.findAll().stream()
				.filter(question -> question.getExam().getSubject().getId() == subject.getId()).toList();
		List<ExamCorpusStatus> statuses = new ArrayList<>();
		for (Exam exam : examWriter.findExamsForSubject(subject)) {
			statuses.add(assessExam(exam, subjectQuestions));
		}
		return List.copyOf(statuses);
	}

	/**
	 * Calculates explicit booklet applicability-review coverage for an already
	 * calculated Exam corpus snapshot.
	 *
	 * @param status             current Exam audit snapshot
	 * @param reviewedBookletIds persisted explicit confirmation identifiers
	 * @return true only if every expected booklet is validly reviewed
	 */
	public boolean isApplicabilityReviewed(ExamCorpusStatus status, Set<Long> reviewedBookletIds) {
		if (status == null) {
			throw new NullPointerException("status");
		}
		return ExamApplicabilityReview.allBookletsReviewed(status.assetExpectations(), status.bookletStatuses(),
				reviewedBookletIds);
	}

	private ExamCorpusStatus assessExam(Exam exam, List<Question> subjectQuestions) throws SQLException {
		List<BookletCorpusStatus> bookletStatuses = new ArrayList<>();
		for (ExamBooklet booklet : examWriter.findExamBooklets(exam)) {
			AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(booklet);
			boolean questionPdfAvailable = isQuestionPdfAvailable(booklet);

			// BookletCorpusAudit owns counting and Question-completeness rules; this
			// service supplies only authoritative persisted and filesystem facts.
			bookletStatuses.add(
					BookletCorpusAudit.assess(booklet, subjectQuestions, assignedAnswerFile, questionPdfAvailable));
		}
		ExamAssetExpectations assetExpectations = examWriter.findExamAssetExpectations(exam);

		// ExamCorpusAudit keeps user-declared lifecycle state separate from the
		// calculated findings assembled above.
		return ExamCorpusAudit.assess(exam, assetExpectations, bookletStatuses);
	}

	private boolean isQuestionPdfAvailable(ExamBooklet booklet) {
		try {
			return Files.isRegularFile(pdfStore.resolve(booklet.getSourceDocument().getRelativePath()));
		} catch (IllegalArgumentException exception) {

			// A malformed or escaping persisted path is not an available managed PDF.
			// Report it through the normal missing-PDF audit finding instead of losing
			// the rest of the Subject Dashboard.
			return false;
		}
	}
}
