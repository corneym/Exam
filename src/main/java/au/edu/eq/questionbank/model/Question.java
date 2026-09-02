package au.edu.eq.questionbank.model;

import java.util.List;

/**
 * An examination question belonging to one source booklet.
 * <p>
 * The original source regions are authoritative; question text is supplementary
 * metadata. A question imported from legacy metadata may exist before any
 * source regions have been captured. Questions created through normal PDF
 * capture must contain at least one region before they are saved.
 * <p>
 * Each question has one best-fit syllabus classification, which must be a
 * {@link CurriculumLevel#SUBTOPIC subtopic} or
 * {@link CurriculumLevel#DESCRIPTOR descriptor}. Every captured region must
 * belong to the question's {@link ExamBooklet}. The classification must belong
 * to the same subject as the booklet's exam.
 */
public class Question {

	private static ExamBooklet bookletFromRegions(Exam exam, List<QuestionRegion> regions) {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("Question must contain at least one region");
		}
		QuestionRegion firstRegion = regions.get(0);
		if (firstRegion == null) {
			throw new NullPointerException("regions contains null");
		}
		ExamBooklet booklet = firstRegion.booklet();
		if (booklet.getExam().getId() != exam.getId()) {
			throw new IllegalArgumentException("Question region booklet must belong to the question's exam");
		}
		return booklet;
	}

	private final long id;
	private final ExamBooklet booklet;
	private final String questionCode;
	private final String questionText;
	private final List<QuestionRegion> regions;
	private final CurriculumNode classification;
	private final int marks;
	private final boolean preambleCaptureRequired;

	private Answer answer;

	/**
	 * Creates a question through the normal capture workflow.
	 * <p>
	 * The booklet is derived from the first region, so this constructor requires at
	 * least one region. Questions created this way do not carry the legacy preamble
	 * capture hint.
	 *
	 * @param id             the persistent question identifier
	 * @param exam           the exam containing every supplied region
	 * @param questionCode   the non-blank question identifier
	 * @param questionText   supplementary text, which may be blank
	 * @param marks          the positive mark value
	 * @param regions        one or more source regions in assembly order
	 * @param classification the syllabus subtopic or descriptor
	 */
	public Question(long id, Exam exam, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification) {
		this(id, bookletFromRegions(exam, regions), questionCode, questionText, marks, regions, classification, false);
	}

	/**
	 * Creates a question belonging explicitly to a source booklet.
	 * <p>
	 * This form supports legacy-imported questions for which metadata is known but
	 * PDF regions have not yet been captured.
	 *
	 * @param id                      the persistent question identifier
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the question or part-question identifier
	 * @param questionText            supplementary searchable or transcribed text
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in assembly order
	 * @param classification          the syllabus subtopic or descriptor
	 * @param preambleCaptureRequired whether legacy metadata indicates that shared
	 *                                introductory material should be captured
	 */
	public Question(long id, ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (questionText == null) {
			throw new NullPointerException("questionText");
		}
		if (marks < 1) {
			throw new IllegalArgumentException("marks must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		CurriculumLevel classificationLevel = classification.getLevel();
		if (classificationLevel != CurriculumLevel.SUBTOPIC && classificationLevel != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC or DESCRIPTOR");
		}
		if (!classification.getSyllabusVersion().getSubject().equals(booklet.getExam().getSubject())) {
			throw new IllegalArgumentException("Question classification must belong to the exam's subject");
		}
		for (QuestionRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
			if (region.booklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("All question regions must belong to the question's exam booklet");
			}
		}
		this.id = id;
		this.booklet = booklet;
		this.questionCode = questionCode;
		this.questionText = questionText;
		this.regions = List.copyOf(regions);
		this.marks = marks;
		this.classification = classification;
		this.preambleCaptureRequired = preambleCaptureRequired;
	}

	/**
	 * Returns the associated answer, or {@code null} when none has been persisted.
	 *
	 * @return the answer, or {@code null}
	 */
	public Answer getAnswer() {
		return answer;
	}

	/**
	 * Returns the source booklet, including for metadata-only questions awaiting
	 * region capture.
	 *
	 * @return the source booklet
	 */
	public ExamBooklet getBooklet() {
		return booklet;
	}

	public CurriculumNode getClassification() {
		return classification;
	}

	public Exam getExam() {
		return booklet.getExam();
	}

	public long getId() {
		return id;
	}

	/**
	 * Returns the positive mark value assigned to the question.
	 *
	 * @return the mark value
	 */
	public int getMarks() {
		return marks;
	}

	public String getQuestionCode() {
		return questionCode;
	}

	public String getQuestionText() {
		return questionText;
	}

	public List<QuestionRegion> getRegions() {
		return regions;
	}

	public boolean hasAnswer() {
		return answer != null;
	}

	/**
	 * Returns whether legacy metadata says shared or introductory material should
	 * be included during subsequent region capture.
	 *
	 * @return {@code true} when the capture hint is present
	 */
	public boolean isPreambleCaptureRequired() {
		return preambleCaptureRequired;
	}

	/**
	 * Associates an answer with this question.
	 *
	 * @param answer the answer to associate with the question
	 * @throws NullPointerException     if {@code answer} is {@code null}
	 * @throws IllegalArgumentException if an answer region belongs to a different
	 *                                  exam
	 */
	public void setAnswer(Answer answer) {
		if (answer == null) {
			throw new NullPointerException("answer");
		}
		for (AnswerRegion region : answer.getRegions()) {
			if (region.answerFile().getExam().getId() != booklet.getExam().getId()) {
				throw new IllegalArgumentException("Answer region file must belong to the question's exam");
			}
		}
		this.answer = answer;
	}
}
