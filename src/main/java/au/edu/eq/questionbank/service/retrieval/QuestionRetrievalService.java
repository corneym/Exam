package au.edu.eq.questionbank.service.retrieval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
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
	private final PerformanceRecorder performanceRecorder;

	/**
	 * Creates a retrieval service with diagnostics disabled.
	 *
	 * @param retrievalRepository        curriculum-aware Question repository
	 * @param searchNodeExpansionService curriculum search-node expansion
	 */
	public QuestionRetrievalService(QuestionRetrievalRepository retrievalRepository,
			CurriculumSearchNodeExpansionService searchNodeExpansionService) {
		this(retrievalRepository, searchNodeExpansionService,
				new PerformanceRecorder(false, Path.of("performance.csv")));
	}

	/**
	 * Creates a retrieval service using the supplied performance recorder.
	 *
	 * @param retrievalRepository        curriculum-aware Question repository
	 * @param searchNodeExpansionService curriculum search-node expansion
	 * @param performanceRecorder        application-level performance recorder
	 */
	public QuestionRetrievalService(QuestionRetrievalRepository retrievalRepository,
			CurriculumSearchNodeExpansionService searchNodeExpansionService, PerformanceRecorder performanceRecorder) {
		if (retrievalRepository == null) {
			throw new NullPointerException("retrievalRepository");
		}
		if (searchNodeExpansionService == null) {
			throw new NullPointerException("searchNodeExpansionService");
		}
		if (performanceRecorder == null) {
			throw new NullPointerException("performanceRecorder");
		}
		this.performanceRecorder = performanceRecorder;
		this.retrievalRepository = retrievalRepository;
		this.searchNodeExpansionService = searchNodeExpansionService;
	}

	/**
	 * Finds Questions applicable to a current curriculum node.
	 *
	 * @param currentNode selected curriculum search scope
	 * @return matching Questions with current applicability
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(CurriculumNode currentNode) {
		return findQuestionsApplicableTo(currentNode, 0);
	}

	/**
	 * Finds applicable Questions with correlated diagnostic measurements.
	 *
	 * @param currentNode       selected curriculum search scope
	 * @param parentOperationId parent diagnostic operation ID
	 * @return matching Questions with current applicability
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(CurriculumNode currentNode, long parentOperationId) {
		List<CurriculumNode> currentNodes;
		try (PerformanceOperation operation = performanceRecorder.start("search.curriculum.expand",
				parentOperationId)) {
			try {
				currentNodes = searchNodeExpansionService.expandSearchNode(currentNode);
				operation.resultCount(currentNodes.size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
		if (currentNodes.isEmpty()) {
			return List.of();
		}
		return retrieveForCurrentNodes(currentNodes, parentOperationId);
	}

	/**
	 * Finds Questions applicable to the Subject's current syllabus.
	 *
	 * @param subject Subject to search
	 * @return matching Questions with current applicability
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(Subject subject) {
		return findQuestionsApplicableTo(subject, 0);
	}

	/**
	 * Finds Subject-applicable Questions with correlated diagnostics.
	 *
	 * @param subject           Subject to search
	 * @param parentOperationId parent diagnostic operation ID
	 * @return matching Questions with current applicability
	 */
	public List<QuestionRetrievalResult> findQuestionsApplicableTo(Subject subject, long parentOperationId) {
		List<CurriculumNode> currentNodes;
		try (PerformanceOperation operation = performanceRecorder.start("search.curriculum.expand",
				parentOperationId)) {
			try {
				currentNodes = searchNodeExpansionService.expandSearchSubject(subject);
				operation.resultCount(currentNodes.size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
		if (currentNodes.isEmpty()) {
			return List.of();
		}
		return retrieveForCurrentNodes(currentNodes, parentOperationId);
	}

	private List<QuestionRetrievalResult> retrieveForCurrentNodes(List<CurriculumNode> currentNodes,
			long parentOperationId) {
		List<QuestionApplicabilityMatch> matches;
		try (PerformanceOperation operation = performanceRecorder.start("search.repository.applicability",
				parentOperationId)) {
			try {
				matches = retrievalRepository.findApplicableToNodes(currentNodes, operation.id());
				if (matches == null) {
					throw new IllegalStateException("Question retrieval repository returned null");
				}
				operation.resultCount(matches.size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
		try (PerformanceOperation operation = performanceRecorder.start("search.matches.aggregate",
				parentOperationId)) {
			try {

				// Preserve persistent-ID ordering and distinct applicability.
				Map<Long, Question> questionsById = new TreeMap<Long, Question>();
				Map<Long, Map<Long, CurriculumNode>> applicabilityByQuestionId = new TreeMap<Long, Map<Long, CurriculumNode>>();
				Map<Long, CurriculumNode> requestedNodesById = new TreeMap<Long, CurriculumNode>();
				for (CurriculumNode currentNode : currentNodes) {
					requestedNodesById.put(currentNode.getId(), currentNode);
				}
				for (QuestionApplicabilityMatch match : matches) {
					if (match == null) {
						throw new IllegalStateException("Question retrieval repository returned a null match");
					}
					Question question = match.getQuestion();
					CurriculumNode currentNode = match.getCurrentNode();
					if (!requestedNodesById.containsKey(currentNode.getId())) {
						throw new IllegalStateException(
								"Question retrieval repository returned an " + "unrequested current node");
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
				List<QuestionRetrievalResult> uniqueResults = List.copyOf(results);
				operation.resultCount(uniqueResults.size());
				return uniqueResults;
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}
}
