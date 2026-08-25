package au.edu.eq.questionbank.model;

import java.util.List;

/**
 * An examination question identified by ordered regions of one source booklet.
 * <p>
 * The original source regions are authoritative; question text is supplementary
 * metadata. Each question has one best-fit syllabus classification, which must
 * be a {@link CurriculumLevel#SUBTOPIC subtopic}. Questions that occupy several
 * page areas retain those regions in extraction and assembly order. Every
 * region must belong to the same {@link ExamBooklet}, and that booklet must
 * belong to the question's exam.
 */
public class Question {

	private final long id;
	private final Exam exam;
	private final String questionCode;
	private final String questionText;
	private final List<QuestionRegion> regions;
	private final CurriculumNode classification;
	private Answer answer;

	/**
	 * Creates a classified question from one or more source regions.
	 *
	 * @param id             the persistent question identifier
	 * @param exam           the assessment containing the question
	 * @param questionCode   the question label used by the assessment, such as
	 *                       {@code Q6}
	 * @param questionText   supplementary searchable or transcribed question text
	 * @param regions        source regions from one booklet, in extraction and
	 *                       assembly order
	 * @param classification the single best-fit syllabus subtopic
	 * @throws NullPointerException     if {@code regions}, an element of
	 *                                  {@code regions}, or {@code classification}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if {@code regions} is empty,
	 *                                  {@code classification} is not a subtopic, a
	 *                                  region belongs to a different exam, or the
	 *                                  regions do not all belong to the same
	 *                                  booklet
	 */
	public Question(long id, Exam exam, String questionCode, String questionText, List<QuestionRegion> regions,
			CurriculumNode classification) {

		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("Question must contain at least one region");
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC");
		}
		ExamBooklet firstBooklet = regions.get(0).booklet();
		for (QuestionRegion region : regions) {
			if (region.booklet().getExam().getId() != exam.getId()) {
				throw new IllegalArgumentException("Question region booklet must belong to the question's exam");
			}
			if (region.booklet().getId() != firstBooklet.getId()) {
				throw new IllegalArgumentException("All question regions must belong to the same exam booklet");
			}
		}
		this.id = id;
		this.exam = exam;
		this.questionCode = questionCode;
		this.questionText = questionText;
		this.regions = List.copyOf(regions);
		this.classification = classification;
	}

	public Answer getAnswer() {
		return answer;
	}

	public CurriculumNode getClassification() {
		return classification;
	}

	public Exam getExam() {
		return exam;
	}

	public long getId() {
		return id;
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
			if (region.answerFile().getExam().getId() != exam.getId()) {
				throw new IllegalArgumentException("Answer region file must belong to the question's exam");
			}
		}

		this.answer = answer;
	}
}
