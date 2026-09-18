package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores text similarity using TF-IDF weighted cosine similarity.
 */
public final class TfIdfTextSimilarityScorer implements TextSimilarityScorer {

	private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
			"in", "is", "it", "of", "on", "or", "that", "the", "their", "to", "was", "were", "which", "with");
	private final int documentCount;
	private final Map<String, Integer> documentFrequencies = new HashMap<>();
	private static final double COGNITIVE_VERB_WEIGHT = 0.20;
	private static final Set<String> COGNITIVE_VERBS = Set.of("analyse", "apply", "assess", "calculate", "compare",
			"construct", "describe", "determine", "evaluate", "explain", "identify", "interpret", "investigate",
			"justify", "predict");

	/**
	 * Builds document frequencies from the supplied curriculum text. Stop words are
	 * omitted and common cognitive verbs receive reduced weight when scoring.
	 *
	 * @param corpus the non-empty collection of documents used to calculate term
	 *               weights
	 * @throws NullPointerException     if the corpus or one of its documents is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if the corpus is empty
	 */
	public TfIdfTextSimilarityScorer(List<String> corpus) {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (corpus.isEmpty()) {
			throw new IllegalArgumentException("corpus must not be empty");
		}
		documentCount = corpus.size();
		for (String document : corpus) {
			if (document == null) {
				throw new NullPointerException("corpus document");
			}
			// Document frequency counts presence, not repetitions within a descriptor.
			Set<String> uniqueTerms = new HashSet<>(tokenise(document));
			for (String term : uniqueTerms) {
				Integer frequency = documentFrequencies.get(term);
				if (frequency == null) {
					documentFrequencies.put(term, 1);
				} else {
					documentFrequencies.put(term, frequency + 1);
				}
			}
		}
	}

	@Override
	public double score(String sourceText, String targetText) {
		if (sourceText == null) {
			throw new NullPointerException("sourceText");
		}
		if (targetText == null) {
			throw new NullPointerException("targetText");
		}
		Map<String, Double> sourceVector = createVector(sourceText);
		Map<String, Double> targetVector = createVector(targetText);
		if (sourceVector.isEmpty() || targetVector.isEmpty()) {
			return 0.0;
		}
		double dotProduct = 0.0;
		double sourceMagnitude = 0.0;
		double targetMagnitude = 0.0;
		for (Map.Entry<String, Double> entry : sourceVector.entrySet()) {
			double sourceWeight = entry.getValue();
			sourceMagnitude += sourceWeight * sourceWeight;
			Double targetWeight = targetVector.get(entry.getKey());
			if (targetWeight != null) {
				dotProduct += sourceWeight * targetWeight;
			}
		}
		for (double targetWeight : targetVector.values()) {
			targetMagnitude += targetWeight * targetWeight;
		}
		if (sourceMagnitude == 0.0 || targetMagnitude == 0.0) {
			return 0.0;
		}
		// Normalise for text length, then contain floating-point drift within the score contract.
		double similarity = dotProduct / (Math.sqrt(sourceMagnitude) * Math.sqrt(targetMagnitude));
		return Math.max(0.0, Math.min(1.0, similarity));
	}

	private Map<String, Double> createVector(String text) {
		List<String> terms = tokenise(text);
		Map<String, Integer> termFrequencies = new HashMap<>();
		for (String term : terms) {
			Integer frequency = termFrequencies.get(term);
			if (frequency == null) {
				termFrequencies.put(term, 1);
			} else {
				termFrequencies.put(term, frequency + 1);
			}
		}
		Map<String, Double> vector = new HashMap<>();
		for (Map.Entry<String, Integer> entry : termFrequencies.entrySet()) {
			double termFrequency = entry.getValue();
			double inverseDocumentFrequency = inverseDocumentFrequency(entry.getKey());
			double weight = termFrequency * inverseDocumentFrequency;
			// Shared task verbs are weaker evidence of subject content than the remaining terms.
			if (COGNITIVE_VERBS.contains(entry.getKey())) {
				weight *= COGNITIVE_VERB_WEIGHT;
			}
			vector.put(entry.getKey(), weight);
		}
		return vector;
	}

	private double inverseDocumentFrequency(String term) {
		Integer frequency = documentFrequencies.get(term);
		int documentFrequency = frequency == null ? 0 : frequency;
		// Add-one smoothing supports unseen terms and keeps ubiquitous terms at nonzero weight.
		return Math.log((documentCount + 1.0) / (documentFrequency + 1.0)) + 1.0;
	}

	private List<String> tokenise(String text) {
		List<String> terms = new ArrayList<>();
		String normalized = text.toLowerCase(Locale.ROOT);
		String[] tokens = normalized.split("[^\\p{L}\\p{N}]+");
		for (String token : tokens) {
			if (!token.isBlank() && !STOP_WORDS.contains(token)) {
				terms.add(token);
			}
		}
		return terms;
	}
}
