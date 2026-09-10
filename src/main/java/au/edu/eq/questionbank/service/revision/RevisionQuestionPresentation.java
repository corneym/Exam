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
			marks = Math.addExact(marks, question.getMarks());
		}
		this.revisionNumber = revisionNumber;
		this.currentNode = currentNode;
		this.members = List.copyOf(members);
		this.totalMarks = marks;
		this.sourceQuestion = sourceQuestion;
		this.sharedContext = sharedContext;
		this.renderSharedContext = renderSharedContext;
	}

	public CurriculumNode getCurrentNode() {
		return currentNode;
	}

	public List<Question> getMembers() {
		return members;
	}

	public int getRevisionNumber() {
		return revisionNumber;
	}

	public SharedQuestionContext getSharedContext() {
		return sharedContext;
	}

	public SourceQuestion getSourceQuestion() {
		return sourceQuestion;
	}

	public int getTotalMarks() {
		return totalMarks;
	}

	public boolean hasSharedContext() {
		return sharedContext != null;
	}

	public boolean hasSourceQuestion() {
		return sourceQuestion != null;
	}

	public boolean isMultipart() {
		return members.size() > 1;
	}

	public boolean shouldRenderSharedContext() {
		return renderSharedContext;
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
