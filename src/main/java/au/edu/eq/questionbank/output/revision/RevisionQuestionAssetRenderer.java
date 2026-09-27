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
import au.edu.eq.questionbank.service.render.QuestionContentRenderer;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;

/**
 * Renders unique Question-body image assets required by a revision corpus.
 * <p>
 * Question bodies may contain ordered PDF regions, stored PNG images, or a
 * mixture of both. Linked shared context is deliberately excluded because
 * revision output renders it separately through
 * {@link RevisionSharedContextAssetRenderer}.
 */
public final class RevisionQuestionAssetRenderer {

	private final QuestionContentRenderer contentRenderer;

	/**
	 * Creates a revision Question-asset renderer.
	 * <p>
	 * PDF-backed content is resolved through the supplied {@link PdfStore} and
	 * cropped through the supplied {@link QuestionExtractor}. Stored image content
	 * is rendered directly from the Question's persisted mixed-content sequence.
	 *
	 * @param pdfStore          resolver for managed source PDFs
	 * @param questionExtractor renderer for PDF-backed Question regions
	 * @throws NullPointerException if either dependency is {@code null}
	 */
	public RevisionQuestionAssetRenderer(PdfStore pdfStore, QuestionExtractor questionExtractor) {
		if (pdfStore == null) {
			throw new NullPointerException("pdfStore");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}

		// Preserve the existing construction API while delegating body rendering to the
		// mixed-content boundary.
		this.contentRenderer = new QuestionContentRenderer(pdfStore, questionExtractor);
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
		return render(corpus, outputRoot, (_, _) -> {
		});
	}

	/**
	 * Renders one PNG per unique renderable Question in persistent Question-id
	 * order.
	 * <p>
	 * Each generated asset contains the Question's own ordered body content, which
	 * may consist of PDF regions, stored images, or both. Shared context is
	 * rendered separately.
	 *
	 * @param corpus     revision corpus containing Question placements
	 * @param outputRoot root directory of the static export
	 * @return rendered Question assets in deterministic Question-id order
	 * @throws NullPointerException if {@code corpus} or {@code outputRoot} is
	 *                              {@code null}
	 * @throws IOException          if required source content cannot be read or an
	 *                              asset cannot be written
	 */
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

		// One question may appear under several curriculum nodes; render its body only
		// once.
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
			try {

				// Render only the Question body here. Shared context remains a separate
				// revision
				// asset so multipart presentation can display it once per group.
				contentRenderer.writeQuestionBody(question, outputFile);
			} catch (IOException e) {

				// Preserve specific source and image decoding diagnostics supplied by the
				// mixed-content renderer.
				throw e;
			} catch (RuntimeException e) {

				// Unexpected domain or rendering failures still identify the affected Question.
				throw new IOException("Could not render question " + question.getId(), e);
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
