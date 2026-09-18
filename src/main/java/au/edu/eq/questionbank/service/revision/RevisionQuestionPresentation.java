package au.edu.eq.questionbank.service.revision;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * One student-facing revision question presentation.
 * <p>
 * A presentation contains either one independently presented question or the
 * renderable members of one source question that belong to the same final
 * current-curriculum bucket.
 */
public final class RevisionQuestionPresentation {

	private final int revisionNumber;
	private final CurriculumNode currentNode;
	private final List<Question> members;
	private final int totalMarks;
	private final SourceQuestion sourceQuestion;
	private final SharedQuestionContext sharedContext;
	private final boolean renderSharedContext;

	RevisionQuestionPresentation(int revisionNumber, CurriculumNode currentNode, List<Question> members,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, boolean renderSharedContext) {
		if (revisionNumber < 1) {
			throw new IllegalArgumentException("revisionNumber must be positive");
		}
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (members == null) {
			throw new NullPointerException("members");
		}
		if (members.isEmpty()) {
			throw new IllegalArgumentException("Presentation must contain at least one question");
		}
		if (members.size() > 1 && sourceQuestion == null) {
			throw new IllegalArgumentException("Multipart presentation requires a source question");
		}
		if (renderSharedContext && sharedContext == null) {
			throw new IllegalArgumentException("Cannot render an absent shared context");
		}
		int marks = validateMembersAndSumMarks(members, sourceQuestion, sharedContext);
		this.revisionNumber = revisionNumber;
		this.currentNode = currentNode;
		this.members = List.copyOf(members);
		this.totalMarks = marks;
		this.sourceQuestion = sourceQuestion;
		this.sharedContext = sharedContext;
		this.renderSharedContext = renderSharedContext;
	}

	/**
	 * Returns the current curriculum bucket containing this presentation.
	 *
	 * @return current Subtopic or Descriptor
	 */
	public CurriculumNode getCurrentNode() {
		return currentNode;
	}

	/**
	 * Returns the captured questions presented together as one revision question.
	 *
	 * @return immutable members in presentation order
	 */
	public List<Question> getMembers() {
		return members;
	}

	/**
	 * Returns the student-facing number assigned by the presentation planner.
	 *
	 * @return positive revision question number
	 */
	public int getRevisionNumber() {
		return revisionNumber;
	}

	/**
	 * Returns the reusable preamble shared by the presentation members.
	 *
	 * @return shared context, or null if none is linked
	 */
	public SharedQuestionContext getSharedContext() {
		return sharedContext;
	}

	/**
	 * Returns the common source-question identity of this presentation.
	 *
	 * @return source identity, or null for an ungrouped question
	 */
	public SourceQuestion getSourceQuestion() {
		return sourceQuestion;
	}

	/**
	 * Returns the sum of marks for all presented members.
	 *
	 * @return total presentation mark value
	 */
	public int getTotalMarks() {
		return totalMarks;
	}

	/**
	 * Indicates whether this presentation has linked reusable preamble material.
	 *
	 * @return true when shared context is available
	 */
	public boolean hasSharedContext() {
		return sharedContext != null;
	}

	/**
	 * Indicates whether the members have a common persisted source identity.
	 *
	 * @return true when a source question is linked
	 */
	public boolean hasSourceQuestion() {
		return sourceQuestion != null;
	}

	/**
	 * Indicates whether several captured parts are presented together.
	 *
	 * @return true when the presentation has more than one member
	 */
	public boolean isMultipart() {
		return members.size() > 1;
	}

	/**
	 * Indicates whether the planner requests preamble output for this presentation.
	 *
	 * @return true when the shared context should be rendered here
	 */
	public boolean shouldRenderSharedContext() {
		return renderSharedContext;
	}

	private int validateMembersAndSumMarks(List<Question> members, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) {
		int marks = 0;
		for (Question question : members) {
			if (question == null) {
				throw new NullPointerException("members contains null");
			}
			if (question.getRegions().isEmpty()) {
				throw new IllegalArgumentException("Presentation members must be renderable");
			}
			validateSourceQuestion(question, sourceQuestion);
			validateSharedContext(question, sharedContext);

			// Fail on overflow rather than publishing a wrapped total to the student.
			marks = Math.addExact(marks, question.getMarks());
		}
		return marks;
	}

	private void validateSharedContext(Question question, SharedQuestionContext expectedContext) {
		if (expectedContext == null) {
			if (question.hasSharedContext()) {
				throw new IllegalArgumentException("Presentation member has an unexpected shared context");
			}
			return;
		}
		if (!question.hasSharedContext()) {
			throw new IllegalArgumentException("Presentation member is missing the shared context");
		}
		SharedQuestionContext actual = question.getSharedContext();
		if (actual.getId() != expectedContext.getId()
				|| actual.getBooklet().getId() != expectedContext.getBooklet().getId()) {
			throw new IllegalArgumentException("Presentation member has a different shared context");
		}
	}

	private void validateSourceQuestion(Question question, SourceQuestion expectedSource) {
		if (expectedSource == null) {
			if (question.hasSourceQuestion()) {
				throw new IllegalArgumentException("Presentation member has an unexpected source question");
			}
			return;
		}
		if (!question.hasSourceQuestion()) {
			throw new IllegalArgumentException("Presentation member is missing the source question");
		}
		SourceQuestion actual = question.getSourceQuestion();
		if (actual.getId() != expectedSource.getId()
				|| actual.getBooklet().getId() != expectedSource.getBooklet().getId()) {
			throw new IllegalArgumentException("Presentation member belongs to another source question");
		}
	}
}
