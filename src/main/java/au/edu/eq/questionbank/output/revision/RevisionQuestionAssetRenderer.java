package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;

/**
 * Renders unique question images required by a revision corpus.
 */
public final class RevisionQuestionAssetRenderer {

	private final PdfStore pdfStore;
	private final QuestionExtractor questionExtractor;

	public RevisionQuestionAssetRenderer(PdfStore pdfStore, QuestionExtractor questionExtractor) {
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
	 * Renders one PNG per unique renderable question in persistent question-id
	 * order.
	 *
	 * @param corpus     revision corpus containing question placements
	 * @param outputRoot root directory of the static export
	 * @return rendered question assets in deterministic question-id order
	 * @throws IOException if a source PDF cannot be read or an asset cannot be
	 *                     written
	 */
	public List<RevisionQuestionAsset> render(RevisionCorpus corpus, Path outputRoot) throws IOException {
		return render(corpus, outputRoot, (completed, total) -> {
		});
	}

	List<RevisionQuestionAsset> render(RevisionCorpus corpus, Path outputRoot, BiConsumer<Integer, Integer> progress)
			throws IOException {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (outputRoot == null) {
			throw new NullPointerException("outputRoot");
		}
		if (progress == null) {
			throw new NullPointerException("progress");
		}
		Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
		Map<Long, Question> questionsById = new TreeMap<Long, Question>();
		for (RevisionCorpusNode rootNode : corpus.getRootNodes()) {
			collectRenderableQuestions(rootNode, questionsById);
		}
		int total = questionsById.size();
		progress.accept(Integer.valueOf(0), Integer.valueOf(total));
		List<RevisionQuestionAsset> assets = new ArrayList<RevisionQuestionAsset>();
		for (Question question : questionsById.values()) {
			Path relativePath = questionAssetPath(question);
			Path outputFile = normalizedOutputRoot.resolve(relativePath).normalize();
			if (!outputFile.startsWith(normalizedOutputRoot)) {
				throw new IOException("Question asset path escapes the export root: " + relativePath);
			}
			Files.createDirectories(outputFile.getParent());
			String sourceRelativePath = question.getBooklet().getSourceDocument().getRelativePath();
			Path sourcePdf = pdfStore.resolve(sourceRelativePath);
			if (!Files.isRegularFile(sourcePdf)) {
				throw new IOException(
						"Question source PDF is not available for question " + question.getId() + ": " + sourcePdf);
			}
			try {
				questionExtractor.extractQuestion(sourcePdf, question, outputFile.toFile());
			} catch (Exception e) {
				throw new IOException("Could not render question " + question.getId() + " from " + sourcePdf, e);
			}
			if (!Files.isRegularFile(outputFile) || Files.size(outputFile) == 0) {
				throw new IOException("Question image was not written for question " + question.getId());
			}
			assets.add(new RevisionQuestionAsset(question, relativePath));
			progress.accept(Integer.valueOf(assets.size()), Integer.valueOf(total));
		}
		return List.copyOf(assets);
	}

	private void collectRenderableQuestions(RevisionCorpusNode node, Map<Long, Question> questionsById) {
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			if (placement.isRenderable()) {
				Question question = placement.getQuestion();
				questionsById.putIfAbsent(question.getId(), question);
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			collectRenderableQuestions(child, questionsById);
		}
	}

	private Path questionAssetPath(Question question) {
		return Path.of("assets", "questions", "question-" + question.getId() + ".png");
	}
}
