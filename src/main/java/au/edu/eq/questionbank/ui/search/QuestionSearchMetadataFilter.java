package au.edu.eq.questionbank.ui.search;

import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Immutable metadata filters for Question Search.
 *
 * The Working Subject is always authoritative. Optional filters narrow results
 * but cannot extend a search outside that Subject.
 *
 * Null optional values mean no restriction. Filters combine using AND.
 * Revision-output inclusion is handled separately because it depends on
 * current-curriculum placements, not only stored Question metadata.
 *
 * @param workingSubject   authoritative application Subject
 * @param provider         optional Exam provider
 * @param fromYear         optional inclusive earliest Exam year
 * @param toYear           optional inclusive latest Exam year
 * @param exam             optional Exam
 * @param booklet          optional Exam booklet
 * @param responseType     optional Multiple Choice or Written Response type
 * @param originalSyllabus optional stored classification syllabus
 */
public record QuestionSearchMetadataFilter(Subject workingSubject, ExamProvider provider, Integer fromYear,
		Integer toYear, Exam exam, ExamBooklet booklet, QuestionResponseType responseType,
		SyllabusVersion originalSyllabus) {

	/**
	 * Validates an immutable Search filter snapshot.
	 */
	public QuestionSearchMetadataFilter {
		Objects.requireNonNull(workingSubject, "workingSubject");
		if (fromYear != null && fromYear < 1) {
			throw new IllegalArgumentException("fromYear must be positive");
		}
		if (toYear != null && toYear < 1) {
			throw new IllegalArgumentException("toYear must be positive");
		}
		if (fromYear != null && toYear != null && fromYear > toYear) {
			throw new IllegalArgumentException("fromYear must not exceed toYear");
		}

		// UNKNOWN remains visible under All, but must not become
		// an explicitly selectable retrieval filter.
		if (responseType == QuestionResponseType.UNKNOWN) {
			throw new IllegalArgumentException("UNKNOWN is not a selectable response-type filter");
		}
		if (originalSyllabus != null && !workingSubject.equals(originalSyllabus.getSubject())) {
			throw new IllegalArgumentException("Original syllabus belongs to another Subject");
		}
		if (exam != null && !workingSubject.equals(exam.getSubject())) {
			throw new IllegalArgumentException("Exam belongs to another Subject");
		}
		if (booklet != null && !workingSubject.equals(booklet.getExam().getSubject())) {
			throw new IllegalArgumentException("Booklet belongs to another Subject");
		}
	}

	/**
	 * Returns all Questions matching this immutable filter snapshot. The source
	 * collection's iteration order is preserved.
	 *
	 * @param questions source Questions
	 * @return immutable matching Questions in input order
	 */
	public List<Question> filter(List<Question> questions) {
		Objects.requireNonNull(questions, "questions");
		return questions.stream().filter(this::matches).toList();
	}

	/**
	 * Determines whether a Question satisfies every metadata filter.
	 *
	 * Persistent identities are compared by ID because repository reads may
	 * reconstruct Exam, Booklet, Provider and Syllabus objects.
	 *
	 * @param question Question being examined
	 * @return whether every active filter is satisfied
	 */
	public boolean matches(Question question) {
		Objects.requireNonNull(question, "question");
		Exam sourceExam = question.getExam();

		// No Search-local filter may broaden the Working Subject.
		if (!workingSubject.equals(sourceExam.getSubject())) {
			return false;
		}
		if (provider != null && sourceExam.getProvider().getId() != provider.getId()) {
			return false;
		}
		int year = sourceExam.getYear();
		if (fromYear != null && year < fromYear) {
			return false;
		}
		if (toYear != null && year > toYear) {
			return false;
		}
		if (exam != null && sourceExam.getId() != exam.getId()) {
			return false;
		}
		if (booklet != null && question.getBooklet().getId() != booklet.getId()) {
			return false;
		}
		if (responseType != null && question.getResponseType() != responseType) {
			return false;
		}
		if (originalSyllabus != null
				&& question.getClassification().getSyllabusVersion().getId() != originalSyllabus.getId()) {
			return false;
		}
		return true;
	}

	/**
	 * Creates an unrestricted metadata filter for one Working Subject.
	 *
	 * @param workingSubject authoritative application Subject
	 * @return unrestricted metadata filter
	 */
	public static QuestionSearchMetadataFilter unrestricted(Subject workingSubject) {
		return new QuestionSearchMetadataFilter(workingSubject, null, null, null, null, null, null, null);
	}
}
