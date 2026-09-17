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
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;

/**
 * Renders unique shared-context images required by renderable questions in a
 * revision corpus.
 */
public final class RevisionSharedContextAssetRenderer {

	private final PdfStore pdfStore;
	private final QuestionExtractor questionExtractor;

	/**
	 * Creates a renderer for shared-context source regions in managed PDFs.
	 *
	 * @param pdfStore          resolver for source examination PDFs
	 * @param questionExtractor renderer for ordered shared-context regions
	 */
	public RevisionSharedContextAssetRenderer(PdfStore pdfStore, QuestionExtractor questionExtractor) {
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
	 * Renders one image per shared context used by renderable corpus questions.
	 *
	 * @param corpus     corpus whose reusable preambles are required
	 * @param outputRoot destination root for generated assets
	 * @return immutable rendered assets ordered by shared-context identity
	 * @throws IOException if source material is unavailable or rendering fails
	 */
	public List<RevisionSharedContextAsset> render(RevisionCorpus corpus, Path outputRoot) throws IOException {
		return render(corpus, outputRoot, (_, _) -> {
		});
	}

	List<RevisionSharedContextAsset> render(RevisionCorpus corpus, Path outputRoot,
			BiConsumer<Integer, Integer> progress) throws IOException {
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
		// Deduplicate by context identity across questions and curriculum placements, with stable output order.
		Map<Long, SharedQuestionContext> contextsById = new TreeMap<>();
		for (RevisionCorpusNode rootNode : corpus.getRootNodes()) {
			collectRenderableContexts(rootNode, contextsById);
		}
		int total = contextsById.size();
		progress.accept(Integer.valueOf(0), Integer.valueOf(total));
		List<RevisionSharedContextAsset> assets = new ArrayList<>();
		for (SharedQuestionContext context : contextsById.values()) {
			Path relativePath = sharedContextAssetPath(context);
			Path outputFile = normalizedOutputRoot.resolve(relativePath).normalize();
			if (!outputFile.startsWith(normalizedOutputRoot)) {
				throw new IOException("Shared-context asset path escapes the export root: " + relativePath);
			}
			Files.createDirectories(outputFile.getParent());
			String sourceRelativePath = context.getBooklet().getSourceDocument().getRelativePath();
			Path sourcePdf = pdfStore.resolve(sourceRelativePath);
			if (!Files.isRegularFile(sourcePdf)) {
				throw new IOException(
						"Shared-context source PDF is not available for context " + context.getId() + ": " + sourcePdf);
			}
			try {
				questionExtractor.extractSharedContext(sourcePdf, context, outputFile.toFile());
			} catch (Exception e) {
				throw new IOException("Could not render shared context " + context.getId() + " from " + sourcePdf, e);
			}
			if (!Files.isRegularFile(outputFile) || Files.size(outputFile) == 0) {
				throw new IOException("Shared-context image was not written for context " + context.getId());
			}
			assets.add(new RevisionSharedContextAsset(context, relativePath));
			progress.accept(Integer.valueOf(assets.size()), Integer.valueOf(total));
		}
		return List.copyOf(assets);
	}

	private void collectRenderableContexts(RevisionCorpusNode node, Map<Long, SharedQuestionContext> contextsById) {
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			if (!placement.isRenderable()) {
				continue;
			}
			Question question = placement.getQuestion();
			if (!question.hasSharedContext()) {
				continue;
			}
			SharedQuestionContext context = question.getSharedContext();
			SharedQuestionContext existing = contextsById.putIfAbsent(context.getId(), context);
			// A reused identity must still identify material in the same source booklet.
			if (existing != null && existing.getBooklet().getId() != context.getBooklet().getId()) {
				throw new IllegalStateException(
						"Shared-context id " + context.getId() + " occurs in more than one booklet");
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			collectRenderableContexts(child, contextsById);
		}
	}

	private Path sharedContextAssetPath(SharedQuestionContext context) {
		return Path.of("assets", "contexts", "context-" + context.getId() + ".png");
	}
}
