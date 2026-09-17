package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;

/**
 * Derives a question's applicability to the current syllabus without changing
 * its original stored classification. Historical classifications apply through
 * confirmed directional mappings only; suggested or non-current targets are
 * ignored.
 */
public final class CurrentCurriculumApplicabilityService {

	private final CurriculumMappingRepository mappingRepository;

	/**
	 * Creates an applicability service using directional curriculum mappings.
	 *
	 * @param mappingRepository directional mapping lookup
	 * @throws NullPointerException if the repository is {@code null}
	 */
	public CurrentCurriculumApplicabilityService(CurriculumMappingRepository mappingRepository) {
		if (mappingRepository == null) {
			throw new NullPointerException("mappingRepository");
		}
		this.mappingRepository = mappingRepository;
	}

	/**
	 * Finds the current curriculum nodes to which a classification applies. A
	 * classification already in a current syllabus applies to itself; a historical
	 * classification applies to each distinct current target of a confirmed
	 * mapping.
	 *
	 * @param classification the question's original classification
	 * @return current applicable nodes in repository order
	 * @throws NullPointerException  if the classification is {@code null}
	 * @throws IllegalStateException if mapping persistence cannot be read
	 */
	public List<CurriculumNode> findCurrentNodes(CurriculumNode classification) {
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (classification.getSyllabusVersion().isCurrent()) {
			return List.of(classification);
		}
		List<CurriculumNode> currentNodes = new ArrayList<>();
		Set<Long> currentNodeIds = new HashSet<>();
		for (CurriculumMapping mapping : mappingRepository.findTargets(classification)) {
			if (mapping.getStatus() != MappingStatus.CONFIRMED) {
				continue;
			}
			CurriculumNode target = mapping.getTarget();
			if (!target.getSyllabusVersion().isCurrent()) {
				continue;
			}
			if (currentNodeIds.add(target.getId())) {
				currentNodes.add(target);
			}
		}
		return List.copyOf(currentNodes);
	}

	/**
	 * Finds current applicability from a question's original classification.
	 *
	 * @param question the classified question
	 * @return current applicable nodes in repository order
	 * @throws NullPointerException  if the question is {@code null}
	 * @throws IllegalStateException if mapping persistence cannot be read
	 */
	public List<CurriculumNode> findCurrentNodes(Question question) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		return findCurrentNodes(question.getClassification());
	}
}
