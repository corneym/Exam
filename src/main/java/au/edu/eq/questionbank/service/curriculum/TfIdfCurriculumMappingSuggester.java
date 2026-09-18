package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Produces up to five ranked descriptor mapping suggestions using TF-IDF cosine
 * similarity. Descriptor text contributes 85 percent of the score and ancestor
 * hierarchy text contributes 15 percent. Ties are ordered by persistent target
 * identifier; ranking never confirms a mapping.
 */
public final class TfIdfCurriculumMappingSuggester implements CurriculumMappingSuggester {

	private record SimilarityCorpora(List<String> descriptorTexts, List<String> contextTexts) {
	}

	private final CurriculumRepository repository;
	private static final double DESCRIPTOR_WEIGHT = 0.85;
	private static final double CONTEXT_WEIGHT = 0.15;
	private static final int MAX_SUGGESTIONS = 5;

	/**
	 * Creates a descriptor suggester using syllabus text and ancestor context.
	 *
	 * @param repository the curriculum hierarchy used to build the source and
	 *                   target corpora
	 * @throws NullPointerException if {@code repository} is {@code null}
	 */
	public TfIdfCurriculumMappingSuggester(CurriculumRepository repository) {
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		this.repository = repository;
	}

	@Override
	public List<CurriculumMappingSuggestion> suggest(CurriculumNode source, SyllabusVersion targetVersion) {
		validateRequest(source, targetVersion);
		List<CurriculumNode> sourceDescriptors = findDescriptors(source.getSyllabusVersion());
		List<CurriculumNode> targetDescriptors = findDescriptors(targetVersion);
		if (targetDescriptors.isEmpty()) {
			return List.of();
		}
		// Weight terms against both syllabuses so source and target vectors share one scale.
		SimilarityCorpora corpora = buildCorpora(sourceDescriptors, targetDescriptors);
		TextSimilarityScorer descriptorScorer = new TfIdfTextSimilarityScorer(corpora.descriptorTexts());
		TextSimilarityScorer contextScorer = new TfIdfTextSimilarityScorer(corpora.contextTexts());
		List<CurriculumMappingSuggestion> suggestions = scoreTargets(source, targetDescriptors, descriptorScorer,
				contextScorer);
		sortSuggestions(suggestions);
		int resultCount = Math.min(MAX_SUGGESTIONS, suggestions.size());
		return List.copyOf(suggestions.subList(0, resultCount));
	}

	private void validateRequest(CurriculumNode source, SyllabusVersion targetVersion) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (source.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("source must be a descriptor");
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

	private SimilarityCorpora buildCorpora(List<CurriculumNode> sourceDescriptors,
			List<CurriculumNode> targetDescriptors) {
		List<String> descriptorCorpus = new ArrayList<>();
		List<String> contextCorpus = new ArrayList<>();
		for (CurriculumNode descriptor : sourceDescriptors) {
			descriptorCorpus.add(descriptor.getName());
			contextCorpus.add(contextText(descriptor));
		}
		for (CurriculumNode descriptor : targetDescriptors) {
			descriptorCorpus.add(descriptor.getName());
			contextCorpus.add(contextText(descriptor));
		}
		return new SimilarityCorpora(descriptorCorpus, contextCorpus);
	}

	private List<CurriculumMappingSuggestion> scoreTargets(CurriculumNode source,
			List<CurriculumNode> targetDescriptors, TextSimilarityScorer descriptorScorer,
			TextSimilarityScorer contextScorer) {
		List<CurriculumMappingSuggestion> suggestions = new ArrayList<>();
		for (CurriculumNode target : targetDescriptors) {
			double descriptorScore = descriptorScorer.score(source.getName(), target.getName());
			double contextScore = contextScorer.score(contextText(source), contextText(target));
			double score = DESCRIPTOR_WEIGHT * descriptorScore + CONTEXT_WEIGHT * contextScore;
			score = Math.max(0.0, Math.min(1.0, score));
			suggestions.add(new CurriculumMappingSuggestion(source, target, score));
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

	private String contextText(CurriculumNode descriptor) {
		// Score ancestor wording separately so it supplements, rather than repeats, descriptor text.
		StringBuilder context = new StringBuilder();
		CurriculumNode parent = descriptor.getParent();
		while (parent != null) {
			if (!context.isEmpty()) {
				context.append(' ');
			}
			context.append(parent.getName());
			parent = parent.getParent();
		}
		return context.toString();
	}

	private void collectDescriptors(CurriculumNode node, List<CurriculumNode> descriptors) {
		if (node.getLevel() == CurriculumLevel.DESCRIPTOR) {
			descriptors.add(node);
			return;
		}
		for (CurriculumNode child : repository.findChildren(node)) {
			collectDescriptors(child, descriptors);
		}
	}

	private List<CurriculumNode> findDescriptors(SyllabusVersion version) {
		List<CurriculumNode> descriptors = new ArrayList<>();
		for (CurriculumNode root : repository.findRootNodes(version)) {
			collectDescriptors(root, descriptors);
		}
		return descriptors;
	}
}
