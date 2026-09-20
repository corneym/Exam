package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TfIdfTextSimilarityScorerTest {

	@Test
	void identicalTextHasMaximumSimilarity() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(
				List.of("chemical equilibrium and equilibrium position", "energy transfer during combustion",
						"electrolysis of aqueous solutions"));
		double score = scorer.score("chemical equilibrium and equilibrium position",
				"chemical equilibrium and equilibrium position");
		assertEquals(1.0, score, 0.000001);
	}

	@Test
	void unrelatedTextHasZeroSimilarityWhenNoTermsAreShared() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(
				List.of("chemical equilibrium", "energy transfer", "electrolysis aqueous solutions"));
		double score = scorer.score("chemical equilibrium", "energy transfer");
		assertEquals(0.0, score, 0.000001);
	}

	@Test
	void relatedDescriptorScoresHigherThanUnrelatedDescriptor() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(
				List.of("explain the effect of concentration on chemical equilibrium",
						"analyse concentration changes in equilibrium systems",
						"describe energy transfer during combustion", "calculate quantities in electrolysis"));
		double related = scorer.score("explain the effect of concentration on chemical equilibrium",
				"analyse concentration changes in equilibrium systems");
		double unrelated = scorer.score("explain the effect of concentration on chemical equilibrium",
				"describe energy transfer during combustion");
		assertTrue(related > unrelated);
	}

	@Test
	void comparisonIgnoresCaseAndPunctuation() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(
				List.of("chemical equilibrium", "reaction rates"));
		double score = scorer.score("Chemical equilibrium!", "chemical, EQUILIBRIUM.");
		assertEquals(1.0, score, 0.000001);
	}

	@Test
	void blankTextProducesZeroSimilarity() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(List.of("chemical equilibrium"));
		assertEquals(0.0, scorer.score("", "chemical equilibrium"));
	}

	@Test
	void rejectsEmptyCorpus() {
		assertThrows(IllegalArgumentException.class, () -> new TfIdfTextSimilarityScorer(List.of()));
	}

	@Test
	void cognitiveVerbContributesLessThanSubjectContent() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(List.of("explain equilibrium concentration",
				"analyse equilibrium concentration", "explain combustion energy", "describe electrolysis products"));
		double related = scorer.score("explain equilibrium concentration", "analyse equilibrium concentration");
		double sharedVerbOnly = scorer.score("explain equilibrium concentration", "explain combustion energy");
		assertTrue(related > sharedVerbOnly);
	}

	@Test
	void scoreAlwaysRemainsWithinNormalizedRange() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(
				List.of("equilibrium concentration pressure temperature", "reaction rate collision energy",
						"oxidation reduction electrochemical cells"));
		double score = scorer.score("equilibrium concentration pressure temperature",
				"equilibrium concentration pressure temperature");
		assertTrue(score >= 0.0);
		assertTrue(score <= 1.0);
	}

	@Test
	void rejectsNullSourceText() {
		TfIdfTextSimilarityScorer scorer = new TfIdfTextSimilarityScorer(List.of("chemical equilibrium"));
		assertThrows(NullPointerException.class, () -> scorer.score(null, "chemical equilibrium"));
	}
}
