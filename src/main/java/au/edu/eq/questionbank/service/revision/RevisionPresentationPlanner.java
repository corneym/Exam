package au.edu.eq.questionbank.service.revision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * Converts a revision corpus into deterministic student-facing question
 * presentations.
 * <p>
 * Multipart grouping happens only within one final current-curriculum bucket
 * and depends on persisted SourceQuestion identity. Shared-context identity
 * alone never creates a multipart group.
 */
public final class RevisionPresentationPlanner {

	/**
	 * Creates a planner for curriculum-grouped, multipart revision presentations.
	 */
	public RevisionPresentationPlanner() {
	}

	private static final Comparator<Question> MEMBER_ORDER = Comparator.comparing(Question::getQuestionCode)
			.thenComparingLong(Question::getId);

	/**
	 * Groups renderable corpus placements into numbered student-facing presentations.
	 *
	 * @param corpus source corpus organised by current curriculum
	 * @return presentation hierarchy with source-question parts grouped within each bucket
	 */
	public RevisionPresentationPlan plan(RevisionCorpus corpus) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		RevisionNumberSequence revisionNumbers = new RevisionNumberSequence();
		List<RevisionPresentationNode> roots = new ArrayList<>();
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			roots.add(planNode(root, revisionNumbers));
		}
		return new RevisionPresentationPlan(corpus, roots);
	}

	private boolean contextsMatch(SharedQuestionContext first, SharedQuestionContext second) {
		if (first == null || second == null) {
			return first == second;
		}
		return first.getId() == second.getId() && first.getBooklet().getId() == second.getBooklet().getId();
	}

	private List<PresentationDraft> createDrafts(List<RevisionQuestionPlacement> placements) {
		List<RevisionQuestionPlacement> renderablePlacements = new ArrayList<>();
		for (RevisionQuestionPlacement placement : placements) {
			if (placement.isRenderable()) {
				renderablePlacements.add(placement);
			}
		}
		List<PresentationDraft> drafts = new ArrayList<>();
		Set<SourceKey> emittedSources = new HashSet<>();
		for (RevisionQuestionPlacement placement : renderablePlacements) {
			Question question = placement.getQuestion();
			if (!question.hasSourceQuestion()) {
				drafts.add(createIndependentDraft(question));
				continue;
			}
			SourceKey sourceKey = SourceKey.from(question);
			if (!emittedSources.add(sourceKey)) {
				continue;
			}
			drafts.add(createSourceQuestionDraft(renderablePlacements, sourceKey));
		}
		return drafts;
	}

	private PresentationDraft createIndependentDraft(Question question) {
		return new PresentationDraft(List.of(question), null, question.getSharedContext());
	}

	private PresentationDraft createSourceQuestionDraft(List<RevisionQuestionPlacement> renderablePlacements,
			SourceKey sourceKey) {
		List<Question> members = new ArrayList<>();
		SourceQuestion sourceQuestion = null;
		for (RevisionQuestionPlacement placement : renderablePlacements) {
			Question question = placement.getQuestion();
			if (!question.hasSourceQuestion()) {
				continue;
			}
			if (!sourceKey.equals(SourceKey.from(question))) {
				continue;
			}
			members.add(question);
			if (sourceQuestion == null) {
				sourceQuestion = question.getSourceQuestion();
			}
		}
		members.sort(MEMBER_ORDER);
		SharedQuestionContext sharedContext = resolveConsistentSharedContext(members, sourceQuestion);
		return new PresentationDraft(members, sourceQuestion, sharedContext);
	}

	private RevisionPresentationNode planNode(RevisionCorpusNode corpusNode, RevisionNumberSequence revisionNumbers) {
		List<RevisionQuestionPresentation> presentations = planPresentations(corpusNode, revisionNumbers);
		List<RevisionPresentationNode> children = new ArrayList<>();
		for (RevisionCorpusNode child : corpusNode.getChildren()) {
			children.add(planNode(child, revisionNumbers));
		}
		return new RevisionPresentationNode(corpusNode.getCurriculumNode(), children, presentations);
	}

	private List<RevisionQuestionPresentation> planPresentations(RevisionCorpusNode corpusNode,
			RevisionNumberSequence revisionNumbers) {
		List<PresentationDraft> drafts = createDrafts(corpusNode.getQuestionPlacements());
		List<RevisionQuestionPresentation> presentations = new ArrayList<>();
		ContextKey previousContext = null;
		for (PresentationDraft draft : drafts) {
			ContextKey currentContext = ContextKey.from(draft.sharedContext());
			boolean renderSharedContext = currentContext != null && !currentContext.equals(previousContext);
			presentations.add(new RevisionQuestionPresentation(revisionNumbers.next(), corpusNode.getCurriculumNode(),
					draft.members(), draft.sourceQuestion(), draft.sharedContext(), renderSharedContext));
			previousContext = currentContext;
		}
		return List.copyOf(presentations);
	}

	private SharedQuestionContext resolveConsistentSharedContext(List<Question> members,
			SourceQuestion sourceQuestion) {
		if (members.isEmpty()) {
			throw new IllegalStateException("Source question has no renderable members");
		}
		SharedQuestionContext expected = members.getFirst().getSharedContext();
		for (Question member : members) {
			if (!contextsMatch(expected, member.getSharedContext())) {
				throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
						+ " has inconsistent shared-context links");
			}
		}
		return expected;
	}

	private record ContextKey(long bookletId, long contextId) {

		private static ContextKey from(SharedQuestionContext context) {
			if (context == null) {
				return null;
			}
			return new ContextKey(context.getBooklet().getId(), context.getId());
		}
	}

	private record PresentationDraft(List<Question> members, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) {

		private PresentationDraft {
			members = List.copyOf(members);
		}
	}

	private static final class RevisionNumberSequence {

		private int nextNumber = 1;

		private int next() {
			return nextNumber++;
		}
	}

	private record SourceKey(long bookletId, long sourceQuestionId) {

		private static SourceKey from(Question question) {
			SourceQuestion sourceQuestion = question.getSourceQuestion();
			return new SourceKey(sourceQuestion.getBooklet().getId(), sourceQuestion.getId());
		}
	}
}
