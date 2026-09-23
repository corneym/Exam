package au.edu.eq.questionbank.service.revision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.QuestionSourceOrder;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * Converts a revision corpus into deterministic student-facing question
 * presentations.
 * <p>
 * Curriculum grouping is an output decision. The source corpus continues to
 * retain the actual current-curriculum applicability of every Question.
 * Multipart grouping happens only after the final output bucket has been
 * chosen.
 */
public final class RevisionPresentationPlanner {

	private static final Comparator<Question> MEMBER_ORDER = Comparator.comparing(Question::getQuestionCode)
			.thenComparingLong(Question::getId);

	// Revision output is chronological within one curriculum bucket. Once year is
	// equal, reuse the application's established deterministic source ordering.
	private static final Comparator<RevisionQuestionPlacement> REVISION_SOURCE_ORDER = Comparator
			.comparingInt((RevisionQuestionPlacement placement) -> placement.getQuestion().getExam().getYear())
			.thenComparing(RevisionQuestionPlacement::getQuestion, QuestionSourceOrder.comparator());

	/**
	 * Creates a planner for curriculum-grouped, multipart revision presentations.
	 */
	public RevisionPresentationPlanner() {
	}

	/**
	 * Returns whether Descriptor grouping can represent the complete renderable
	 * corpus without dropping broader Subtopic-level applicability.
	 * <p>
	 * The rule is intentionally all-or-nothing. One renderable Subtopic-level
	 * placement disables Descriptor grouping for the export.
	 *
	 * @param corpus source revision corpus
	 * @return true when all renderable placements are Descriptor-level
	 */
	public boolean isDescriptorGroupingAvailable(RevisionCorpus corpus) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			if (!hasCompleteDescriptorCoverage(root)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Plans the corpus using the finest safe grouping mode.
	 * <p>
	 * Descriptor grouping is used only when every renderable placement is at
	 * Descriptor level. Otherwise the corpus automatically falls back to Subtopic
	 * grouping.
	 *
	 * @param corpus source corpus organised by current curriculum
	 * @return presentation hierarchy using the finest safe grouping mode
	 */
	public RevisionPresentationPlan plan(RevisionCorpus corpus) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		RevisionGroupingMode groupingMode = isDescriptorGroupingAvailable(corpus) ? RevisionGroupingMode.DESCRIPTOR
				: RevisionGroupingMode.SUBTOPIC;
		return plan(corpus, groupingMode);
	}

	/**
	 * Plans the corpus using an explicit transient export grouping mode.
	 *
	 * @param corpus       source corpus organised by current curriculum
	 * @param groupingMode requested grouping depth
	 * @return student-facing presentation hierarchy
	 * @throws IllegalArgumentException if Descriptor grouping is requested while
	 *                                  any renderable placement exists only at
	 *                                  Subtopic level
	 */
	public RevisionPresentationPlan plan(RevisionCorpus corpus, RevisionGroupingMode groupingMode) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (groupingMode == null) {
			throw new NullPointerException("groupingMode");
		}
		if (groupingMode == RevisionGroupingMode.DESCRIPTOR && !isDescriptorGroupingAvailable(corpus)) {
			throw new IllegalArgumentException(
					"Descriptor grouping requires every renderable revision placement to be Descriptor-level");
		}
		RevisionNumberSequence revisionNumbers = new RevisionNumberSequence();
		List<RevisionPresentationNode> roots = new ArrayList<>();

		// Number grouped presentations afresh. Corpus placement numbers are diagnostic
		// source state and may collapse when final output buckets are rolled up.
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			roots.add(planNode(root, groupingMode, revisionNumbers));
		}
		return new RevisionPresentationPlan(corpus, roots, groupingMode);
	}

	private void addOrderedPlacements(List<RevisionQuestionPlacement> source, List<RevisionQuestionPlacement> target,
			Set<Long> emittedQuestionIds) {
		for (RevisionQuestionPlacement placement : orderedPlacements(source)) {

			// A Question may map to several Descriptors beneath one final Subtopic. The
			// rolled-up Subtopic presentation must show that Question once, at the first
			// Descriptor encountered in curriculum order.
			if (emittedQuestionIds.add(placement.getQuestion().getId())) {
				target.add(placement);
			}
		}
	}

	private boolean contextsMatch(SharedQuestionContext first, SharedQuestionContext second) {
		if (first == null || second == null) {
			return first == second;
		}
		return first.getId() == second.getId() && first.getBooklet().getId() == second.getBooklet().getId();
	}

	private List<PresentationDraft> createDrafts(List<RevisionQuestionPlacement> placements) {

		// Uncaptured Questions remain in corpus statistics but cannot join rendered
		// student presentations.
		List<RevisionQuestionPlacement> renderablePlacements = new ArrayList<>();
		for (RevisionQuestionPlacement placement : placements) {
			if (placement.isRenderable()) {
				renderablePlacements.add(placement);
			}
		}
		List<PresentationDraft> drafts = new ArrayList<>();
		Set<SourceKey> emittedSources = new HashSet<>();

		// Form multipart Questions before response-type ordering so a multipart
		// Question remains one student-facing presentation.
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

		// List.sort is stable. This moves MCQ before written response before unknown
		// while preserving Descriptor/year/source order inside each response type.
		drafts.sort(Comparator.comparingInt(this::responseRank));
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

	private boolean hasCompleteDescriptorCoverage(RevisionCorpusNode node) {

		// Metadata-only placements do not affect an export choice because they cannot
		// appear in the generated student resource.
		if (node.getCurriculumNode().getLevel() == CurriculumLevel.SUBTOPIC) {
			for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
				if (placement.isRenderable()) {
					return false;
				}
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			if (!hasCompleteDescriptorCoverage(child)) {
				return false;
			}
		}
		return true;
	}

	private List<RevisionQuestionPlacement> orderedPlacements(List<RevisionQuestionPlacement> placements) {
		List<RevisionQuestionPlacement> ordered = new ArrayList<>(placements);
		ordered.sort(REVISION_SOURCE_ORDER);
		return ordered;
	}

	private RevisionPresentationNode planNode(RevisionCorpusNode corpusNode, RevisionGroupingMode groupingMode,
			RevisionNumberSequence revisionNumbers) {
		List<RevisionQuestionPresentation> presentations = presentationsForNode(corpusNode, groupingMode,
				revisionNumbers);
		List<RevisionPresentationNode> children = new ArrayList<>();
		for (RevisionCorpusNode child : corpusNode.getChildren()) {
			children.add(planNode(child, groupingMode, revisionNumbers));
		}
		return new RevisionPresentationNode(corpusNode.getCurriculumNode(), children, presentations);
	}

	private List<RevisionQuestionPresentation> planPresentations(CurriculumNode outputBucket,
			List<RevisionQuestionPlacement> placements, RevisionNumberSequence revisionNumbers) {
		List<PresentationDraft> drafts = createDrafts(placements);
		List<RevisionQuestionPresentation> presentations = new ArrayList<>();
		ContextKey previousContext = null;
		for (PresentationDraft draft : drafts) {
			ContextKey currentContext = ContextKey.from(draft.sharedContext());

			// Shared context is emitted at the start of an adjacent sequence using that
			// context. A different context, including no context, starts a new sequence.
			boolean renderSharedContext = currentContext != null && !currentContext.equals(previousContext);
			presentations.add(new RevisionQuestionPresentation(revisionNumbers.next(), outputBucket, draft.members(),
					draft.sourceQuestion(), draft.sharedContext(), renderSharedContext));
			previousContext = currentContext;
		}
		return List.copyOf(presentations);
	}

	private List<RevisionQuestionPresentation> presentationsForNode(RevisionCorpusNode corpusNode,
			RevisionGroupingMode groupingMode, RevisionNumberSequence revisionNumbers) {
		CurriculumLevel level = corpusNode.getCurriculumNode().getLevel();
		if (groupingMode == RevisionGroupingMode.DESCRIPTOR) {

			// Descriptor mode is all-or-nothing, so only Descriptor nodes are final
			// presentation buckets.
			if (level != CurriculumLevel.DESCRIPTOR) {
				return List.of();
			}
			return planPresentations(corpusNode.getCurriculumNode(),
					orderedPlacements(corpusNode.getQuestionPlacements()), revisionNumbers);
		}
		if (level == CurriculumLevel.SUBTOPIC) {
			return planPresentations(corpusNode.getCurriculumNode(), rollUpSubtopicPlacements(corpusNode),
					revisionNumbers);
		}
		if (level == CurriculumLevel.TOPIC && topicUsesDirectDescriptors(corpusNode)) {

			// A three-level curriculum has no Subtopic node. In Subtopic mode the Topic
			// therefore becomes the practical roll-up bucket rather than inventing a
			// synthetic curriculum node.
			return planPresentations(corpusNode.getCurriculumNode(), rollUpDirectDescriptorTopicPlacements(corpusNode),
					revisionNumbers);
		}
		return List.of();
	}

	private SharedQuestionContext resolveConsistentSharedContext(List<Question> members,
			SourceQuestion sourceQuestion) {
		if (members.isEmpty()) {
			throw new IllegalStateException("Source question has no renderable members");
		}
		SharedQuestionContext expected = members.getFirst().getSharedContext();

		// Missing versus linked context is also a conflict; choosing one would hide
		// inconsistent persisted source data.
		for (Question member : members) {
			if (!contextsMatch(expected, member.getSharedContext())) {
				throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
						+ " has inconsistent shared-context links");
			}
		}
		return expected;
	}

	private int responseRank(PresentationDraft draft) {
		boolean allMultipleChoice = true;
		boolean hasWrittenResponse = false;
		for (Question question : draft.members()) {
			QuestionResponseType responseType = question.getResponseType();
			if (responseType != QuestionResponseType.MULTIPLE_CHOICE) {
				allMultipleChoice = false;
			}
			if (responseType == QuestionResponseType.WRITTEN_RESPONSE) {
				hasWrittenResponse = true;
			}
		}
		if (allMultipleChoice) {
			return 0;
		}
		if (hasWrittenResponse) {
			return 1;
		}
		return 2;
	}

	private List<RevisionQuestionPlacement> rollUpDirectDescriptorTopicPlacements(RevisionCorpusNode topicNode) {
		List<RevisionQuestionPlacement> rolledUp = new ArrayList<>();
		Set<Long> emittedQuestionIds = new HashSet<>();

		// Descriptor child order is curriculum order. Within each Descriptor, Questions
		// are oldest-to-newest and ties use deterministic source order.
		for (RevisionCorpusNode descriptorNode : topicNode.getChildren()) {
			if (descriptorNode.getCurriculumNode().getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalStateException("Three-level Topic roll-up requires only Descriptor children");
			}
			addOrderedPlacements(descriptorNode.getQuestionPlacements(), rolledUp, emittedQuestionIds);
		}
		return List.copyOf(rolledUp);
	}

	private List<RevisionQuestionPlacement> rollUpSubtopicPlacements(RevisionCorpusNode subtopicNode) {
		List<RevisionQuestionPlacement> rolledUp = new ArrayList<>();
		Set<Long> emittedQuestionIds = new HashSet<>();

		// Questions classified directly to the broader Subtopic come first. These are
		// followed by Descriptor groups in curriculum order.
		addOrderedPlacements(subtopicNode.getQuestionPlacements(), rolledUp, emittedQuestionIds);
		for (RevisionCorpusNode descriptorNode : subtopicNode.getChildren()) {
			if (descriptorNode.getCurriculumNode().getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalStateException("Subtopic corpus children must be Descriptor nodes");
			}
			addOrderedPlacements(descriptorNode.getQuestionPlacements(), rolledUp, emittedQuestionIds);
		}
		return List.copyOf(rolledUp);
	}

	private boolean topicUsesDirectDescriptors(RevisionCorpusNode topicNode) {
		List<RevisionCorpusNode> children = topicNode.getChildren();
		if (children.isEmpty()) {
			return false;
		}
		CurriculumLevel childLevel = children.getFirst().getCurriculumNode().getLevel();
		for (RevisionCorpusNode child : children) {
			if (child.getCurriculumNode().getLevel() != childLevel) {
				throw new IllegalStateException(
						"Topic presentation children must not mix Subtopic and Descriptor nodes");
			}
		}
		if (childLevel == CurriculumLevel.DESCRIPTOR) {
			return true;
		}
		if (childLevel == CurriculumLevel.SUBTOPIC) {
			return false;
		}
		throw new IllegalStateException("Topic presentation children must be Subtopic or Descriptor nodes");
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
