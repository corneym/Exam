package au.edu.eq.questionbank.repository.assessment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;

/**
 * In-memory implementation of Question-specific output exclusions.
 */
public final class InMemoryQuestionOutputApplicabilityRepository implements QuestionOutputApplicabilityRepository {

	private final Map<Long, Set<Long>> excludedNodeIdsByQuestionId = new HashMap<>();

	/**
	 * Creates an empty output-applicability repository.
	 */
	public InMemoryQuestionOutputApplicabilityRepository() {

		// An empty repository intentionally means that every derived applicability
		// remains included.
	}

	@Override
	public Set<Long> findExcludedCurrentNodeIds(Question question) {
		validateQuestion(question);
		Set<Long> excludedNodeIds = excludedNodeIdsByQuestionId.get(question.getId());
		if (excludedNodeIds == null) {
			return Set.of();
		}

		// Do not expose the mutable set that owns repository state.
		return Set.copyOf(excludedNodeIds);
	}

	@Override
	public Map<Long, Set<Long>> findExcludedCurrentNodeIdsForQuestions(List<Question> questions) {
		Objects.requireNonNull(questions, "questions");
		Map<Long, Set<Long>> matches = new HashMap<>();
		for (Question question : questions) {
			validateQuestion(question);
			Set<Long> excluded = excludedNodeIdsByQuestionId.get(question.getId());
			if (excluded != null && !excluded.isEmpty()) {
				matches.put(question.getId(), Set.copyOf(excluded));
			}
		}
		return Map.copyOf(matches);
	}

	@Override
	public void setExcluded(Question question, CurriculumNode currentNode, boolean excluded) {
		validateQuestion(question);
		validateCurrentNode(question, currentNode);
		if (excluded) {

			// Adding the same exclusion twice is intentionally idempotent.
			excludedNodeIdsByQuestionId.computeIfAbsent(question.getId(), _ -> new HashSet<>())
					.add(currentNode.getId());
			return;
		}
		Set<Long> excludedNodeIds = excludedNodeIdsByQuestionId.get(question.getId());
		if (excludedNodeIds == null) {
			return;
		}

		// Removing an absent exclusion is also idempotent.
		excludedNodeIds.remove(currentNode.getId());
		if (excludedNodeIds.isEmpty()) {

			// Do not retain empty per-Question buckets as hidden state.
			excludedNodeIdsByQuestionId.remove(question.getId());
		}
	}

	private void validateCurrentNode(Question question, CurriculumNode currentNode) {
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (currentNode.getId() < 1) {
			throw new IllegalArgumentException("currentNode must have a persistent identifier");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("Output exclusion requires a current curriculum node");
		}
		CurriculumLevel level = currentNode.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Output exclusion requires a Subtopic or Descriptor");
		}
		if (!question.getExam().getSubject().equals(currentNode.getSyllabusVersion().getSubject())) {
			throw new IllegalArgumentException("Question and current curriculum node must belong to the same Subject");
		}
	}

	private void validateQuestion(Question question) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (question.getId() < 1) {
			throw new IllegalArgumentException("question must have a persistent identifier");
		}
	}
}
