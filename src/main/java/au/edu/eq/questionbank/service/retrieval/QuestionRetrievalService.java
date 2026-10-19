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
 * Application-facing service for retrieving Questions applicable to the current
 * curriculum.
 * <p>
 * Unit and Topic selections are search scopes, not applicability placements.
 * They expand to current SUBTOPIC and DESCRIPTOR nodes.
 * <p>
 * Repository matches are aggregated into one result per persistent Question ID.
 * Each result contains distinct current placements in ascending persistent-node
 * ID order. Question results are also ordered by ascending persistent Question
 * ID.
 * <p>
 * Duplicate repository matches are permitted and consolidated. Null repository
 * results, null matches, unrequested placements and incorrect selected-Question
 * identities are rejected.
 * <p>
 * Current applicability is derived independently of the Question's
 * authoritative original classification. Retrieval never modifies that
 * classification or persists derived placements.
 */
public final class QuestionRetrievalService {

	// Persistence and service collaborators.
	private final PerformanceRecorder performanceRecorder;
	private final QuestionRetrievalRepository retrievalRepository;
	private final CurriculumSearchNodeExpansionService searchNodeExpansionService;

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
	 * Finds every current-syllabus placement of one persisted Question.
	 *
	 * Expands the Question's complete Subject, independently of any Search filter
	 * or Dashboard launch narrowing. The repository constrains the Question ID
	 * before reconstructing persisted Questions.
	 *
	 * @param question          selected persisted Question
	 * @param parentOperationId enclosing diagnostic operation identifier
	 * @return distinct current placements in ascending persistent-node ID order
	 */
	public List<CurriculumNode> findCurrentApplicabilityForQuestion(Question question, long parentOperationId) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		List<CurriculumNode> currentNodes;
		try (PerformanceOperation operation = performanceRecorder.start("search.curriculum.expand",
				parentOperationId)) {
			try {

				// Scope is derived from the selected Question's Subject,
				// never from the current Search navigation or result subset.
				currentNodes = searchNodeExpansionService.expandSearchSubject(question.getExam().getSubject());
				operation.resultCount(currentNodes.size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
		if (currentNodes.isEmpty()) {
			return List.of();
		}
		List<QuestionRetrievalResult> results = retrieveForCurrentNodes(currentNodes, question.getId(),
				parentOperationId);
		if (results.isEmpty()) {
			return List.of();
		}

		// A selective repository must never return another Question or
		// multiple distinct Questions for this request.
		if (results.size() != 1 || results.getFirst().getQuestion().getId() != question.getId()) {
			throw new IllegalStateException("Selective applicability retrieval returned another Question");
		}
		return results.getFirst().getCurrentApplicability();
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
		return retrieveForCurrentNodes(currentNodes, null, parentOperationId);
	}

	private List<QuestionRetrievalResult> retrieveForCurrentNodes(List<CurriculumNode> currentNodes,
			Long selectedQuestionId, long parentOperationId) {
		List<QuestionApplicabilityMatch> matches;
		try (PerformanceOperation operation = performanceRecorder.start("search.repository.applicability",
				parentOperationId)) {
			try {

				// Search uses broad retrieval; selected-Question output
				// applicability uses the Question-constrained repository path.
				matches = selectedQuestionId == null
						? retrievalRepository.findApplicableToNodes(currentNodes, operation.id())
						: retrievalRepository.findApplicableToNodesForQuestion(currentNodes, selectedQuestionId,
								operation.id());
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

				// Preserve persistent-ID ordering and distinct applicability
				// in both broad and selected-Question retrieval.
				Map<Long, Question> questionsById = new TreeMap<>();
				Map<Long, Map<Long, CurriculumNode>> applicabilityByQuestionId = new TreeMap<>();
				Map<Long, CurriculumNode> requestedNodesById = new TreeMap<>();
				for (CurriculumNode currentNode : currentNodes) {
					requestedNodesById.put(currentNode.getId(), currentNode);
				}
				for (QuestionApplicabilityMatch match : matches) {
					if (match == null) {
						throw new IllegalStateException("Question retrieval repository returned a null match");
					}
					Question question = match.getQuestion();
					CurriculumNode currentNode = match.getCurrentNode();
					if (selectedQuestionId != null && question.getId() != selectedQuestionId) {
						throw new IllegalStateException("Selective applicability retrieval returned another Question");
					}
					if (!requestedNodesById.containsKey(currentNode.getId())) {
						throw new IllegalStateException(
								"Question retrieval repository returned an unrequested current node");
					}
					questionsById.putIfAbsent(question.getId(), question);
					Map<Long, CurriculumNode> applicability = applicabilityByQuestionId.get(question.getId());
					if (applicability == null) {
						applicability = new TreeMap<>();
						applicabilityByQuestionId.put(question.getId(), applicability);
					}
					applicability.putIfAbsent(currentNode.getId(), currentNode);
				}
				List<QuestionRetrievalResult> results = new ArrayList<>();
				for (Map.Entry<Long, Question> entry : questionsById.entrySet()) {
					Map<Long, CurriculumNode> applicability = applicabilityByQuestionId.get(entry.getKey());
					results.add(new QuestionRetrievalResult(entry.getValue(), new ArrayList<>(applicability.values())));
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
