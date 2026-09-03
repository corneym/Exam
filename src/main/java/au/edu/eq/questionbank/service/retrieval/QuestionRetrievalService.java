package au.edu.eq.questionbank.service.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;

/**
 * Application-facing service for finding questions applicable to current
 * curriculum.
 * <p>
 * Retrieval does not change a question's original classification. The service
 * also guarantees unique question results and deterministic ordering.
 */
public final class QuestionRetrievalService {

	private final QuestionRetrievalRepository retrievalRepository;

	/**
	 * @param retrievalRepository curriculum-aware question persistence boundary
	 * @throws NullPointerException if the repository is {@code null}
	 */
	public QuestionRetrievalService(QuestionRetrievalRepository retrievalRepository) {
		if (retrievalRepository == null) {
			throw new NullPointerException("retrievalRepository");
		}

		this.retrievalRepository = retrievalRepository;
	}

	/**
	 * Finds unique stored questions applicable to a current descriptor or subtopic.
	 * <p>
	 * Results are ordered by persistent question identifier. Historical
	 * classifications remain unchanged and are exposed separately from the current
	 * applicability that caused each result to match.
	 *
	 * @param currentNode the current descriptor or subtopic being searched
	 * @return unique matching questions in deterministic order
	 * @throws NullPointerException     if {@code currentNode} is {@code null}
	 * @throws IllegalArgumentException if the node is not current or is not yet a
	 *                                  supported search level
	 * @throws IllegalStateException    if persistence cannot be read or returns an
	 *                                  invalid match
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(CurriculumNode currentNode) {
		validateSearchNode(currentNode);

		return retrieveForCurrentNodes(List.of(currentNode));
	}

	private List<QuestionRetrievalResult> retrieveForCurrentNodes(List<CurriculumNode> currentNodes) {
		Map<Long, Question> questionsById = new TreeMap<Long, Question>();
		Map<Long, Map<Long, CurriculumNode>> applicabilityByQuestionId = new TreeMap<Long, Map<Long, CurriculumNode>>();

		Map<Long, CurriculumNode> requestedNodesById = new TreeMap<Long, CurriculumNode>();

		for (CurriculumNode currentNode : currentNodes) {
			requestedNodesById.put(currentNode.getId(), currentNode);
		}

		List<QuestionApplicabilityMatch> matches = retrievalRepository.findApplicableToNodes(currentNodes);

		if (matches == null) {
			throw new IllegalStateException("Question retrieval repository returned null");
		}

		for (QuestionApplicabilityMatch match : matches) {
			if (match == null) {
				throw new IllegalStateException("Question retrieval repository returned a null match");
			}

			Question question = match.getQuestion();
			CurriculumNode currentNode = match.getCurrentNode();

			if (!requestedNodesById.containsKey(currentNode.getId())) {
				throw new IllegalStateException("Question retrieval repository returned an unrequested current node");
			}

			questionsById.putIfAbsent(question.getId(), question);

			Map<Long, CurriculumNode> applicability = applicabilityByQuestionId.get(question.getId());

			if (applicability == null) {
				applicability = new TreeMap<Long, CurriculumNode>();
				applicabilityByQuestionId.put(question.getId(), applicability);
			}

			applicability.putIfAbsent(currentNode.getId(), currentNode);
		}

		List<QuestionRetrievalResult> results = new ArrayList<QuestionRetrievalResult>();

		for (Map.Entry<Long, Question> entry : questionsById.entrySet()) {
			Map<Long, CurriculumNode> applicability = applicabilityByQuestionId.get(entry.getKey());

			results.add(new QuestionRetrievalResult(entry.getValue(),
					new ArrayList<CurriculumNode>(applicability.values())));
		}

		return List.copyOf(results);
	}

	private void validateSearchNode(CurriculumNode currentNode) {
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("Search node must belong to a current syllabus");
		}

		CurriculumLevel level = currentNode.getLevel();

		if (level != CurriculumLevel.DESCRIPTOR && level != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("Search node must currently be a DESCRIPTOR or SUBTOPIC");
		}
	}
}
