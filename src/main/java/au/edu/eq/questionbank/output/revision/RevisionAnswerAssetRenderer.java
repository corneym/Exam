package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;

/**
 * Renders answer-region images required by a revision corpus.
 */
public final class RevisionAnswerAssetRenderer {

	private final PdfStore pdfStore;
	private final QuestionExtractor questionExtractor;

	public RevisionAnswerAssetRenderer(PdfStore pdfStore, QuestionExtractor questionExtractor) {
		if (pdfStore == null) {
			throw new NullPointerException("pdfStore");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		this.pdfStore = pdfStore;
		this.questionExtractor = questionExtractor;
	}

	/**
	 * Renders persisted answer regions for each unique applicable question.
	 * Text-only answers require no image asset.
	 *
	 * @param corpus     revision corpus
	 * @param outputRoot root directory of the static export
	 * @return answer assets in question-id then persisted region order
	 * @throws IOException if a source PDF cannot be read or an asset cannot be
	 *                     written
	 */
	public List<RevisionAnswerAsset> render(RevisionCorpus corpus, Path outputRoot) throws IOException {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (outputRoot == null) {
			throw new NullPointerException("outputRoot");
		}
		Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
		Map<Long, Question> questionsById = new TreeMap<Long, Question>();
		for (RevisionCorpusNode rootNode : corpus.getRootNodes()) {
			collectQuestionsWithAnswers(rootNode, questionsById);
		}
		List<RevisionAnswerAsset> assets = new ArrayList<RevisionAnswerAsset>();
		for (Question question : questionsById.values()) {
			Answer answer = question.getAnswer();
			List<AnswerRegion> regions = answer.getRegions();
			for (int index = 0; index < regions.size(); index++) {
				AnswerRegion region = regions.get(index);
				int regionNumber = index + 1;
				Path relativePath = answerAssetPath(question, regionNumber);
				Path outputFile = normalizedOutputRoot.resolve(relativePath).normalize();
				if (!outputFile.startsWith(normalizedOutputRoot)) {
					throw new IOException("Answer asset path escapes the export root: " + relativePath);
				}
				Files.createDirectories(outputFile.getParent());
				String sourceRelativePath = region.answerFile().getSourceDocument().getRelativePath();
				Path sourcePdf = pdfStore.resolve(sourceRelativePath);
				if (!Files.isRegularFile(sourcePdf)) {
					throw new IOException("Answer source PDF is not available for question " + question.getId()
							+ ", answer region " + regionNumber + ": " + sourcePdf);
				}
				try {
					questionExtractor.extractRegion(sourcePdf, region, outputFile.toFile());
				} catch (Exception e) {
					throw new IOException("Could not render answer region " + regionNumber + " for question "
							+ question.getId() + " from " + sourcePdf, e);
				}
				if (!Files.isRegularFile(outputFile) || Files.size(outputFile) == 0) {
					throw new IOException("Answer image was not written for question " + question.getId()
							+ ", answer region " + regionNumber);
				}
				assets.add(new RevisionAnswerAsset(question, region, regionNumber, relativePath));
			}
		}
		return List.copyOf(assets);
	}

	private Path answerAssetPath(Question question, int regionNumber) {
		return Path.of("assets", "answers",
				String.format("question-%d-answer-%02d.png", question.getId(), regionNumber));
	}

	private void collectQuestionsWithAnswers(RevisionCorpusNode node, Map<Long, Question> questionsById) {
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			Question question = placement.getQuestion();
			if (question.hasAnswer()) {
				questionsById.putIfAbsent(question.getId(), question);
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			collectQuestionsWithAnswers(child, questionsById);
		}
	}
}
