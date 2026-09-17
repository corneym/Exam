package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Suggests subtopic mappings using confirmed descriptor mappings as evidence.
 * The score is the fraction of the source subtopic's direct descriptors that
 * support a target subtopic. Each source descriptor contributes at most one
 * vote to a target subtopic, regardless of how many confirmed target
 * descriptors it has there, but may support more than one target subtopic.
 * Target descriptors directly beneath a topic provide no subtopic evidence.
 * Ties are ordered by persistent target identifier, and suggestions are never
 * persisted automatically.
 */
public final class ConfirmedDescriptorSubtopicMappingSuggester implements CurriculumMappingSuggester {

	private final CurriculumRepository curriculumRepository;
	private final CurriculumMappingRepository mappingRepository;

	/**
	 * Creates a suggester backed by curriculum hierarchy and directional mapping
	 * lookups.
	 *
	 * @param curriculumRepository the hierarchy used to find direct source
	 *                             descriptors
	 * @param mappingRepository    confirmed descriptor mapping lookup
	 * @throws NullPointerException if either repository is {@code null}
	 */
	public ConfirmedDescriptorSubtopicMappingSuggester(CurriculumRepository curriculumRepository,
			CurriculumMappingRepository mappingRepository) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (mappingRepository == null) {
			throw new NullPointerException("mappingRepository");
		}
		this.curriculumRepository = curriculumRepository;
		this.mappingRepository = mappingRepository;
	}

	@Override
	public List<CurriculumMappingSuggestion> suggest(CurriculumNode source, SyllabusVersion targetVersion) {
		validateRequest(source, targetVersion);
		List<CurriculumNode> sourceDescriptors = findDirectDescriptors(source);
		if (sourceDescriptors.isEmpty()) {
			return List.of();
		}
		Map<CurriculumNode, Integer> evidenceCounts = collectEvidence(sourceDescriptors, targetVersion);
		List<CurriculumMappingSuggestion> suggestions = createSuggestions(source, sourceDescriptors.size(),
				evidenceCounts);
		sortSuggestions(suggestions);
		return List.copyOf(suggestions);
	}

	private Map<CurriculumNode, Integer> collectEvidence(List<CurriculumNode> sourceDescriptors,
			SyllabusVersion targetVersion) {
		Map<CurriculumNode, Integer> evidenceCounts = new HashMap<>();
		for (CurriculumNode sourceDescriptor : sourceDescriptors) {
			Set<Long> supportedTargetSubtopicIds = new HashSet<>();
			for (CurriculumMapping mapping : mappingRepository.findTargets(sourceDescriptor)) {
				if (mapping.getStatus() != MappingStatus.CONFIRMED) {
					continue;
				}
				CurriculumNode targetDescriptor = mapping.getTarget();
				if (!targetDescriptor.getSyllabusVersion().equals(targetVersion)) {
					continue;
				}
				CurriculumNode targetParent = targetDescriptor.getParent();
				if (targetParent == null || targetParent.getLevel() != CurriculumLevel.SUBTOPIC) {
					continue;
				}
				if (supportedTargetSubtopicIds.add(targetParent.getId())) {
					evidenceCounts.merge(targetParent, 1, Integer::sum);
				}
			}
		}
		return evidenceCounts;
	}

	private List<CurriculumMappingSuggestion> createSuggestions(CurriculumNode source, int sourceDescriptorCount,
			Map<CurriculumNode, Integer> evidenceCounts) {
		List<CurriculumMappingSuggestion> suggestions = new ArrayList<>();
		for (Map.Entry<CurriculumNode, Integer> entry : evidenceCounts.entrySet()) {
			double score = (double) entry.getValue() / sourceDescriptorCount;
			suggestions.add(new CurriculumMappingSuggestion(source, entry.getKey(), score));
		}
		return suggestions;
	}

	private void sortSuggestions(List<CurriculumMappingSuggestion> suggestions) {
		suggestions.sort((first, second) -> {
			int scoreComparison = Double.compare(second.getScore(), first.getScore());
			if (scoreComparison != 0) {
				return scoreComparison;
			}
			return Long.compare(first.getTarget().getId(), second.getTarget().getId());
		});
	}

	private List<CurriculumNode> findDirectDescriptors(CurriculumNode subtopic) {
		List<CurriculumNode> descriptors = new ArrayList<>();
		for (CurriculumNode child : curriculumRepository.findChildren(subtopic)) {
			if (child.getLevel() == CurriculumLevel.DESCRIPTOR) {
				descriptors.add(child);
			}
		}
		return descriptors;
	}

	private void validateRequest(CurriculumNode source, SyllabusVersion targetVersion) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (source.getLevel() != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("source must be a subtopic");
		}
		if (!source.getSyllabusVersion().getSubject().equals(targetVersion.getSubject())) {
			throw new IllegalArgumentException("source and target syllabus must belong to the same subject");
		}
		if (source.getSyllabusVersion().equals(targetVersion)) {
			throw new IllegalArgumentException("source and target syllabus versions must be different");
		}
		if (source.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("source syllabus version must not be current");
		}
		if (!targetVersion.isCurrent()) {
			throw new IllegalArgumentException("target syllabus version must be current");
		}
	}
}
