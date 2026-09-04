package au.edu.eq.questionbank.service.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;

/**
 * Application-facing service for finding questions applicable to current
 * curriculum.
 * <p>
 * Retrieval does not change a question's original classification. Unit and
 * topic nodes are search scopes only. They are expanded to the subtopic and
 * descriptor nodes that can actually classify questions.
 */
public final class QuestionRetrievalService {

	private final QuestionRetrievalRepository retrievalRepository;
	private final CurriculumSearchNodeExpansionService searchNodeExpansionService;

	/**
	 * @param retrievalRepository        curriculum-aware question persistence
	 *                                   boundary
	 * @param searchNodeExpansionService current curriculum hierarchy expansion
	 *                                   service
	 * @throws NullPointerException if either dependency is {@code null}
	 */
	public QuestionRetrievalService(QuestionRetrievalRepository retrievalRepository,
			CurriculumSearchNodeExpansionService searchNodeExpansionService) {
		if (retrievalRepository == null) {
			throw new NullPointerException("retrievalRepository");
		}
		if (searchNodeExpansionService == null) {
			throw new NullPointerException("searchNodeExpansionService");
		}
		this.retrievalRepository = retrievalRepository;
		this.searchNodeExpansionService = searchNodeExpansionService;
	}

	/**
	 * Finds unique stored questions applicable within a current curriculum scope.
	 * <p>
	 * Descriptor searches are exact. Subtopic, topic and unit searches expand
	 * downwards according to the current curriculum hierarchy.
	 * <p>
	 * Results are ordered by persistent question identifier. Historical
	 * classifications remain unchanged and are exposed separately from the current
	 * applicability that caused each result to match.
	 *
	 * @param currentNode the current unit, topic, subtopic or descriptor being
	 *                    searched
	 * @return unique matching questions in deterministic order
	 * @throws NullPointerException     if {@code currentNode} is {@code null}
	 * @throws IllegalArgumentException if the node is not a supported current
	 *                                  curriculum search scope
	 * @throws IllegalStateException    if the curriculum hierarchy or persistence
	 *                                  result is invalid
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(CurriculumNode currentNode) {
		List<CurriculumNode> currentNodes = searchNodeExpansionService.expandSearchNode(currentNode);
		if (currentNodes.isEmpty()) {
			return List.of();
		}
		return retrieveForCurrentNodes(currentNodes);
	}

	/**
	 * Finds unique stored questions applicable anywhere within the subject's
	 * current syllabus.
	 * <p>
	 * Historical classifications remain unchanged and may contribute through
	 * confirmed mappings to current curriculum nodes.
	 *
	 * @param subject the subject whose current curriculum is searched
	 * @return unique matching questions in deterministic order
	 * @throws NullPointerException  if {@code subject} is {@code null}
	 * @throws IllegalStateException if the current curriculum hierarchy or
	 *                               persistence result is invalid
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(Subject subject) {
		List<CurriculumNode> currentNodes = searchNodeExpansionService.expandSearchSubject(subject);
		if (currentNodes.isEmpty()) {
			return List.of();
		}
		return retrieveForCurrentNodes(currentNodes);
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
}
