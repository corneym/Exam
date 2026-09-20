package au.edu.eq.questionbank.service.revision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;

/**
 * Builds a transient revision corpus from the current curriculum and the
 * existing curriculum-aware question retrieval service.
 */
public final class RevisionCorpusBuilder {

	private final CurriculumRepository curriculumRepository;
	private final QuestionRetrievalService questionRetrievalService;

	/**
	 * Creates a builder combining current curriculum structure with applicable
	 * questions.
	 *
	 * @param curriculumRepository     subject and curriculum hierarchy lookup
	 * @param questionRetrievalService current-curriculum question retrieval
	 */
	public RevisionCorpusBuilder(CurriculumRepository curriculumRepository,
			QuestionRetrievalService questionRetrievalService) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (questionRetrievalService == null) {
			throw new NullPointerException("questionRetrievalService");
		}
		this.curriculumRepository = curriculumRepository;
		this.questionRetrievalService = questionRetrievalService;
	}

	/**
	 * Builds an ordered revision corpus beneath the subject's current syllabus.
	 *
	 * @param subject subject to assemble for revision
	 * @return curriculum hierarchy with question placements and completeness
	 *         statistics
	 */
	public RevisionCorpus build(Subject subject) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		SyllabusVersion currentVersion = findCurrentVersion(subject);
		Map<Long, MutableCorpusNode> nodesById = new LinkedHashMap<Long, MutableCorpusNode>();
		Set<Long> visitedNodeIds = new HashSet<Long>();
		List<CurriculumNode> roots = orderedNodes(requireNodes(curriculumRepository.findRootNodes(currentVersion),
				"Curriculum repository returned null root nodes"));
		List<MutableCorpusNode> mutableRoots = new ArrayList<MutableCorpusNode>();
		for (CurriculumNode root : roots) {
			if (root.getLevel() != CurriculumLevel.UNIT) {
				throw new IllegalStateException("Current syllabus root nodes must be UNIT nodes");
			}
			if (root.getParent() != null) {
				throw new IllegalStateException("Current syllabus root UNIT must not have a parent");
			}
			mutableRoots.add(buildHierarchy(root, currentVersion, nodesById, visitedNodeIds));
		}

		// Build the complete tree before resolving applicability to its final
		// classification nodes.
		placeQuestions(subject, currentVersion, nodesById);
		List<RevisionCorpusNode> rootNodes = new ArrayList<RevisionCorpusNode>();

		// Share one sequence across Units so placement numbering follows the full
		// traversal order.
		RevisionNumberSequence revisionNumbers = new RevisionNumberSequence();
		for (MutableCorpusNode mutableRoot : mutableRoots) {
			rootNodes.add(freeze(mutableRoot, revisionNumbers));
		}
		RevisionCorpusStatistics statistics = buildStatistics(mutableRoots);
		return new RevisionCorpus(subject, currentVersion, rootNodes, statistics);
	}

	private void accumulateStatistics(MutableCorpusNode node, StatisticsAccumulator accumulator) {

		// Count every placement, but count capture and answer state once per stored
		// question.
		accumulator.applicablePlacements += node.questionsById.size();
		for (Question question : node.questionsById.values()) {
			long questionId = question.getId();
			if (!accumulator.uniqueQuestionIds.add(questionId)) {
				continue;
			}
			if (question.getRegions().isEmpty()) {
				accumulator.missingRegionQuestionIds.add(questionId);
			} else {
				accumulator.renderableQuestionIds.add(questionId);
			}

			// These statistics record answer presence, not response-type completeness.
			if (question.hasAnswer()) {
				accumulator.questionIdsWithAnswers.add(questionId);
			} else {
				accumulator.questionIdsWithoutAnswers.add(questionId);
			}

			// Preserve the legacy review signal even when shared context has since been
			// captured.
			if (question.isPreambleCaptureRequired()) {
				accumulator.preambleReviewQuestionIds.add(questionId);
			}
		}
		for (MutableCorpusNode child : node.children) {
			accumulateStatistics(child, accumulator);
		}
	}

	private MutableCorpusNode buildHierarchy(CurriculumNode node, SyllabusVersion currentVersion,
			Map<Long, MutableCorpusNode> nodesById, Set<Long> visitedNodeIds) {
		validateCurrentNode(node, currentVersion);
		if (!visitedNodeIds.add(node.getId())) {
			throw new IllegalStateException("Curriculum hierarchy contains a duplicate or cycle");
		}
		MutableCorpusNode mutableNode = new MutableCorpusNode(node);
		nodesById.put(node.getId(), mutableNode);
		List<CurriculumNode> children = orderedNodes(
				requireNodes(curriculumRepository.findChildren(node), "Curriculum repository returned null children"));
		validateChildren(node, children, currentVersion);
		for (CurriculumNode child : children) {
			mutableNode.children.add(buildHierarchy(child, currentVersion, nodesById, visitedNodeIds));
		}
		return mutableNode;
	}

	private RevisionCorpusStatistics buildStatistics(List<MutableCorpusNode> rootNodes) {
		StatisticsAccumulator accumulator = new StatisticsAccumulator();
		for (MutableCorpusNode rootNode : rootNodes) {
			accumulateStatistics(rootNode, accumulator);
		}
		return new RevisionCorpusStatistics(accumulator.applicablePlacements, accumulator.uniqueQuestionIds.size(),
				accumulator.renderableQuestionIds.size(), accumulator.missingRegionQuestionIds.size(),
				accumulator.questionIdsWithAnswers.size(), accumulator.questionIdsWithoutAnswers.size(),
				accumulator.preambleReviewQuestionIds.size());
	}

	private SyllabusVersion findCurrentVersion(Subject subject) {
		List<SyllabusVersion> versions = curriculumRepository.findVersionsForSubject(subject);
		if (versions == null) {
			throw new IllegalStateException("Curriculum repository returned null syllabus versions");
		}

		// Do not choose arbitrarily if persisted data identifies more than one current
		// syllabus.
		SyllabusVersion currentVersion = null;
		for (SyllabusVersion version : versions) {
			if (version == null) {
				throw new IllegalStateException("Curriculum repository returned a null syllabus version");
			}
			if (!subject.equals(version.getSubject())) {
				throw new IllegalStateException("Syllabus version belongs to another subject");
			}
			if (!version.isCurrent()) {
				continue;
			}
			if (currentVersion != null) {
				throw new IllegalStateException("Subject has multiple current syllabus versions");
			}
			currentVersion = version;
		}
		if (currentVersion == null) {
			throw new IllegalStateException("Subject has no current syllabus version");
		}
		return currentVersion;
	}

	private RevisionCorpusNode freeze(MutableCorpusNode mutableNode, RevisionNumberSequence revisionNumbers) {
		List<RevisionQuestionPlacement> placements = new ArrayList<RevisionQuestionPlacement>();
		for (Question question : mutableNode.questionsById.values()) {

			// Retain uncaptured questions for diagnostics without consuming a revision
			// number.
			int revisionNumber = 0;
			if (!question.getRegions().isEmpty()) {
				revisionNumber = revisionNumbers.next();
			}
			placements.add(new RevisionQuestionPlacement(question, mutableNode.curriculumNode, revisionNumber));
		}
		List<RevisionCorpusNode> children = new ArrayList<RevisionCorpusNode>();
		for (MutableCorpusNode child : mutableNode.children) {
			children.add(freeze(child, revisionNumbers));
		}
		return new RevisionCorpusNode(mutableNode.curriculumNode, children, placements);
	}

	private List<CurriculumNode> orderedNodes(List<CurriculumNode> nodes) {
		List<CurriculumNode> ordered = new ArrayList<CurriculumNode>(nodes);

		// Author-defined order takes precedence; codes and IDs provide deterministic
		// tie-breaks.
		ordered.sort(Comparator.comparingInt(CurriculumNode::getDisplayOrder).thenComparing(CurriculumNode::getCode)
				.thenComparingLong(CurriculumNode::getId));
		return ordered;
	}

	private void placeQuestions(Subject subject, SyllabusVersion currentVersion,
			Map<Long, MutableCorpusNode> nodesById) {
		List<QuestionRetrievalResult> results = questionRetrievalService.findQuestionsApplicableTo(subject);
		if (results == null) {
			throw new IllegalStateException("Question retrieval service returned null");
		}
		for (QuestionRetrievalResult result : results) {
			if (result == null) {
				throw new IllegalStateException("Question retrieval service returned a null result");
			}
			Question question = result.getQuestion();
			if (!subject.equals(question.getExam().getSubject())) {
				throw new IllegalStateException("Retrieved question belongs to another subject");
			}
			for (CurriculumNode currentNode : result.getCurrentApplicability()) {
				MutableCorpusNode target = requirePlacementTarget(currentNode, currentVersion, nodesById);

				// Deduplicate within a bucket while allowing the same question in other
				// applicable buckets.
				target.questionsById.putIfAbsent(question.getId(), question);
			}
		}
	}

	private MutableCorpusNode requirePlacementTarget(CurriculumNode currentNode, SyllabusVersion currentVersion,
			Map<Long, MutableCorpusNode> nodesById) {
		if (!currentVersion.equals(currentNode.getSyllabusVersion())) {
			throw new IllegalStateException("Retrieved applicability belongs to another current syllabus");
		}
		MutableCorpusNode target = nodesById.get(currentNode.getId());
		if (target == null) {
			throw new IllegalStateException(
					"Retrieved applicability node is not present in the current curriculum tree");
		}
		CurriculumLevel level = target.curriculumNode.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalStateException("Questions may be placed only at Subtopic or Descriptor level");
		}
		return target;
	}

	private List<CurriculumNode> requireNodes(List<CurriculumNode> nodes, String message) {
		if (nodes == null) {
			throw new IllegalStateException(message);
		}
		for (CurriculumNode node : nodes) {
			if (node == null) {
				throw new IllegalStateException("Curriculum repository returned a null curriculum node");
			}
		}
		return nodes;
	}

	private void validateChildren(CurriculumNode parent, List<CurriculumNode> children,
			SyllabusVersion currentVersion) {

		// A Topic may omit Subtopics, but its children must consistently use one
		// supported level.
		CurriculumLevel topicChildMode = null;
		for (CurriculumNode child : children) {
			validateCurrentNode(child, currentVersion);
			if (!parent.equals(child.getParent())) {
				throw new IllegalStateException("Curriculum repository returned a child of another node");
			}
			switch (parent.getLevel()) {
			case UNIT:
				if (child.getLevel() != CurriculumLevel.TOPIC) {
					throw new IllegalStateException("Unit children must be TOPIC nodes");
				}
				break;
			case TOPIC:
				CurriculumLevel childLevel = child.getLevel();
				if (childLevel != CurriculumLevel.SUBTOPIC && childLevel != CurriculumLevel.DESCRIPTOR) {
					throw new IllegalStateException("Topic children must be SUBTOPIC or DESCRIPTOR nodes");
				}
				if (topicChildMode == null) {
					topicChildMode = childLevel;
				} else if (topicChildMode != childLevel) {
					throw new IllegalStateException(
							"Topic must contain either SUBTOPIC children or DESCRIPTOR children, not both");
				}
				break;
			case SUBTOPIC:
				if (child.getLevel() != CurriculumLevel.DESCRIPTOR) {
					throw new IllegalStateException("Subtopic children must be DESCRIPTOR nodes");
				}
				break;
			case DESCRIPTOR:
				throw new IllegalStateException("Descriptor nodes must not contain curriculum children");
			}
		}
	}

	private void validateCurrentNode(CurriculumNode node, SyllabusVersion currentVersion) {
		if (!currentVersion.equals(node.getSyllabusVersion())) {
			throw new IllegalStateException("Curriculum node belongs to another syllabus version");
		}
		if (!node.getSyllabusVersion().isCurrent()) {
			throw new IllegalStateException("Corpus curriculum nodes must belong to a current syllabus");
		}
	}

	private static final class MutableCorpusNode {

		private final CurriculumNode curriculumNode;
		private final List<MutableCorpusNode> children;
		private final Map<Long, Question> questionsById;

		private MutableCorpusNode(CurriculumNode curriculumNode) {
			this.curriculumNode = curriculumNode;
			this.children = new ArrayList<MutableCorpusNode>();
			this.questionsById = new TreeMap<Long, Question>();
		}
	}

	private static final class RevisionNumberSequence {

		private int nextNumber = 1;

		private int next() {
			int result = nextNumber;
			nextNumber++;
			return result;
		}
	}

	private static final class StatisticsAccumulator {

		private int applicablePlacements;
		private final Set<Long> uniqueQuestionIds = new HashSet<Long>();
		private final Set<Long> renderableQuestionIds = new HashSet<Long>();
		private final Set<Long> missingRegionQuestionIds = new HashSet<Long>();
		private final Set<Long> questionIdsWithAnswers = new HashSet<Long>();
		private final Set<Long> questionIdsWithoutAnswers = new HashSet<Long>();
		private final Set<Long> preambleReviewQuestionIds = new HashSet<Long>();
	}
}
