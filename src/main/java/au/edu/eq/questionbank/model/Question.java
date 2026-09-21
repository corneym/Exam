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

	private final long id;
	private final ExamBooklet booklet;
	private final String questionCode;
	private final String questionText;
	private final List<QuestionRegion> regions;
	private final CurriculumNode classification;
	private final int marks;
	private final boolean preambleCaptureRequired;
	private final SourceQuestion sourceQuestion;
	private final SharedQuestionContext sharedContext;
	private Answer answer;
	private final QuestionResponseType responseType;

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
		this(id, bookletFromRegions(exam, regions), questionCode, questionText, marks, regions, classification, false,
				null, null);
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
		this(id, booklet, questionCode, questionText, marks, regions, classification, preambleCaptureRequired, null,
				null);
	}

	/**
	 * Creates a question with its optional source-question and shared-context
	 * relationships.
	 * <p>
	 * This compatibility constructor represents response type as
	 * {@link QuestionResponseType#UNKNOWN}. Callers that know the authoritative
	 * response type should use the overload accepting {@link QuestionResponseType}.
	 *
	 * @param id                      the persistent question identifier
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the question or part-question identifier
	 * @param questionText            supplementary searchable or transcribed text
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in assembly order
	 * @param classification          the syllabus subtopic or descriptor
	 * @param preambleCaptureRequired historical legacy preamble-capture evidence
	 * @param sourceQuestion          common source-question identity, or null
	 * @param sharedContext           reusable shared question context, or null
	 */
	public Question(long id, ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		this(id, booklet, questionCode, questionText, marks, regions, classification, preambleCaptureRequired,
				sourceQuestion, sharedContext, QuestionResponseType.UNKNOWN);
	}

	/**
	 * Creates a question with its persisted response type and optional capture
	 * relationships.
	 *
	 * @param id                      the persistent question identifier
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the question or part-question identifier
	 * @param questionText            supplementary searchable or transcribed text
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in assembly order
	 * @param classification          the syllabus subtopic or descriptor
	 * @param preambleCaptureRequired historical legacy preamble-capture evidence
	 * @param sourceQuestion          common source-question identity, or null
	 * @param sharedContext           reusable shared question context, or null
	 * @param responseType            authoritative Question response type
	 */
	public Question(long id, ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, QuestionResponseType responseType) {
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
		if (responseType == null) {
			throw new NullPointerException("responseType");
		}
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE && marks != 1) {

			// Multiple-choice Questions are always worth exactly one mark. Enforce the
			// invariant in the domain model rather than relying only on the capture UI.
			throw new IllegalArgumentException("Multiple-choice questions must be worth exactly 1 mark");
		}
		// Check classification and source ownership before retaining the supplied
		// relationships.
		validateClassification(booklet, classification);
		validateSourceOwnership(booklet, regions, sourceQuestion, sharedContext);
		this.id = id;
		this.booklet = booklet;
		this.questionCode = questionCode;
		this.questionText = questionText;

		// Preserve assembly order independently of later changes to the caller's list.
		this.regions = List.copyOf(regions);
		this.marks = marks;
		this.classification = classification;
		this.preambleCaptureRequired = preambleCaptureRequired;
		this.sourceQuestion = sourceQuestion;
		this.sharedContext = sharedContext;
		this.responseType = responseType;
	}

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

		// The capture constructor derives ownership here; the common constructor checks
		// remaining regions.
		ExamBooklet booklet = firstRegion.booklet();
		if (booklet.getExam().getId() != exam.getId()) {
			throw new IllegalArgumentException("Question region booklet must belong to the question's exam");
		}
		return booklet;
	}

	private static void validateClassification(ExamBooklet booklet, CurriculumNode classification) {

		// Accept either supported classification level, including descriptors directly
		// beneath topics.
		CurriculumLevel classificationLevel = classification.getLevel();
		if (classificationLevel != CurriculumLevel.SUBTOPIC && classificationLevel != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC or DESCRIPTOR");
		}
		if (!classification.getSyllabusVersion().getSubject().equals(booklet.getExam().getSubject())) {
			throw new IllegalArgumentException("Question classification must belong to the exam's subject");
		}
	}

	private static void validateSourceOwnership(ExamBooklet booklet, List<QuestionRegion> regions,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {

		// An empty list is valid for imported metadata; every supplied region must use
		// this booklet.
		for (QuestionRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
			if (region.booklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("All question regions must belong to the question's exam booklet");
			}
		}
		if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Source question must belong to the question's exam booklet");
		}
		if (sharedContext != null && sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the question's exam booklet");
		}
	}

	/**
	 * Returns the answer associated in memory, or {@code null} when none is set.
	 * Association alone does not persist an answer.
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

	/**
	 * Returns the original syllabus classification selected for this question.
	 *
	 * @return the best-fit subtopic or descriptor in the exam subject
	 */
	public CurriculumNode getClassification() {
		return classification;
	}

	/**
	 * Returns the examination from which this question was captured.
	 *
	 * @return the exam owning the source booklet
	 */
	public Exam getExam() {
		return booklet.getExam();
	}

	/**
	 * Returns the persistent identity of this question.
	 *
	 * @return the positive persistent identifier
	 */
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

	/**
	 * Returns the original examination question or part code.
	 *
	 * @return the non-blank question or part code, preserved as supplied
	 */
	public String getQuestionCode() {
		return questionCode;
	}

	/**
	 * Returns supplementary wording stored alongside the authoritative source
	 * regions.
	 *
	 * @return supplementary text, which may be blank but is never null
	 */
	public String getQuestionText() {
		return questionText;
	}

	/**
	 * Returns the source regions used to assemble this question.
	 *
	 * @return an immutable list in assembly order; empty for metadata-only
	 *         questions
	 */
	public List<QuestionRegion> getRegions() {
		return regions;
	}

	/**
	 * Returns the persisted response type that determines the required Answer
	 * representation.
	 *
	 * @return the Question response type
	 */
	public QuestionResponseType getResponseType() {
		return responseType;
	}

	/**
	 * Returns the shared preamble linked to this question.
	 *
	 * @return the reusable preamble context, or {@code null} when unlinked
	 */
	public SharedQuestionContext getSharedContext() {
		return sharedContext;
	}

	/**
	 * Returns the source-question identity used to group related parts.
	 *
	 * @return the common source identity, or {@code null} when unlinked
	 */
	public SourceQuestion getSourceQuestion() {
		return sourceQuestion;
	}

	/**
	 * Indicates whether an answer is attached to this question instance.
	 *
	 * @return whether an answer is associated in memory
	 */
	public boolean hasAnswer() {
		return answer != null;
	}

	/**
	 * Indicates whether reusable preamble material is linked.
	 *
	 * @return whether reusable source context is linked
	 */
	public boolean hasSharedContext() {
		return sharedContext != null;
	}

	/**
	 * Indicates whether a persisted source-question identity is linked.
	 *
	 * @return whether the question belongs to a common source identity
	 */
	public boolean hasSourceQuestion() {
		return sourceQuestion != null;
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
	 * Returns whether the historical legacy hint requires shared context and none
	 * is linked. This does not consult the source question's preamble status.
	 *
	 * @return {@code true} when shared context still requires resolution
	 */
	public boolean isSharedContextUnresolved() {
		return preambleCaptureRequired && sharedContext == null;
	}

	/**
	 * Associates or replaces this question's in-memory answer without persisting
	 * it.
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

		// Answer files belong to the exam, so they need not share the question
		// booklet's source document.
		for (AnswerRegion region : answer.getRegions()) {
			if (region.answerFile().getExam().getId() != booklet.getExam().getId()) {
				throw new IllegalArgumentException("Answer region file must belong to the question's exam");
			}
		}

		// Replace the association only after every region passes, preserving the old
		// answer on failure.
		this.answer = answer;
	}
}
