package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlan;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

/**
 * Coordinates complete static revision export generation.
 * <p>
 * Output is generated and validated in a sibling staging directory and is only
 * promoted to the requested destination after validation succeeds.
 */
public final class RevisionExportService {

	private final RevisionCorpusBuilder corpusBuilder;
	private final RevisionQuestionAssetRenderer questionAssetRenderer;
	private final RevisionAnswerAssetRenderer answerAssetRenderer;
	private final RevisionExportValidator validator;
	private final RevisionPresentationPlanner presentationPlanner;
	private final RevisionSharedContextAssetRenderer sharedContextAssetRenderer;

	/**
	 * Creates an exporter from corpus, rendering and validation services.
	 *
	 * @param corpusBuilder         the subject corpus builder
	 * @param questionAssetRenderer the question image renderer
	 * @param answerAssetRenderer   the answer image renderer
	 * @param validator             the generated-site validator
	 * @throws NullPointerException if any dependency is null
	 */
	public RevisionExportService(RevisionCorpusBuilder corpusBuilder, RevisionPresentationPlanner presentationPlanner,
			RevisionQuestionAssetRenderer questionAssetRenderer,
			RevisionSharedContextAssetRenderer sharedContextAssetRenderer,
			RevisionAnswerAssetRenderer answerAssetRenderer, RevisionExportValidator validator) {
		if (corpusBuilder == null) {
			throw new NullPointerException("corpusBuilder");
		}
		if (presentationPlanner == null) {
			throw new NullPointerException("presentationPlanner");
		}
		if (questionAssetRenderer == null) {
			throw new NullPointerException("questionAssetRenderer");
		}
		if (sharedContextAssetRenderer == null) {
			throw new NullPointerException("sharedContextAssetRenderer");
		}
		if (answerAssetRenderer == null) {
			throw new NullPointerException("answerAssetRenderer");
		}
		if (validator == null) {
			throw new NullPointerException("validator");
		}
		this.corpusBuilder = corpusBuilder;
		this.presentationPlanner = presentationPlanner;
		this.questionAssetRenderer = questionAssetRenderer;
		this.sharedContextAssetRenderer = sharedContextAssetRenderer;
		this.answerAssetRenderer = answerAssetRenderer;
		this.validator = validator;
	}

	/**
	 * Exports a validated revision site without progress notifications.
	 *
	 * @param request the subject and new destination directory
	 * @return the published location and corpus statistics
	 * @throws IOException if generation, validation or publication fails
	 */
	public RevisionExportResult export(RevisionExportRequest request) throws IOException {
		return export(request, (_, _, _) -> {
		});
	}

	/**
	 * Builds and validates a revision site in a sibling staging directory, then
	 * moves it to a destination that must not already exist. Failed staging output
	 * is removed; publication uses an atomic move when supported by the filesystem.
	 *
	 * @param request  the subject and new destination directory
	 * @param progress synchronous progress callback on the exporting thread
	 * @return the published location and corpus statistics
	 * @throws IOException          if the destination exists or export cannot
	 *                              complete
	 * @throws NullPointerException if either argument is null
	 */
	public RevisionExportResult export(RevisionExportRequest request, RevisionExportProgressListener progress)
			throws IOException {
		if (request == null) {
			throw new NullPointerException("request");
		}
		if (progress == null) {
			throw new NullPointerException("progress");
		}
		Path destination = request.getDestination().toAbsolutePath().normalize();
		validateDestination(destination);
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("Revision export destination must have a parent directory: " + destination);
		}
		Files.createDirectories(parent);
		String destinationName = destination.getFileName().toString();
		Path staging = parent.resolve(destinationName + ".staging-" + UUID.randomUUID()).normalize();
		if (!staging.getParent().equals(parent)) {
			throw new IOException("Revision export staging directory escaped destination parent");
		}
		progress.update("Building revision corpus...", 0, 0);
		RevisionCorpus corpus = corpusBuilder.build(request.getSubject());
		progress.update("Planning revision presentation...", 0, 0);
		RevisionPresentationPlan presentationPlan = presentationPlanner.plan(corpus);
		boolean promoted = false;
		try {
			Files.createDirectory(staging);
			List<RevisionQuestionAsset> questionAssets = questionAssetRenderer.render(corpus, staging,
					(completed, total) -> progress.update("Rendering questions: " + completed + " / " + total,
							completed.intValue(), total.intValue()));
			List<RevisionSharedContextAsset> sharedContextAssets = sharedContextAssetRenderer.render(corpus, staging,
					(completed, total) -> progress.update("Rendering shared context: " + completed + " / " + total,
							completed.intValue(), total.intValue()));
			List<RevisionAnswerAsset> answerAssets = answerAssetRenderer.render(corpus, staging,
					(completed, total) -> progress.update("Rendering answers: " + completed + " / " + total,
							completed.intValue(), total.intValue()));
			progress.update("Writing HTML...", 0, 0);
			RevisionHtmlRenderer htmlRenderer = new RevisionHtmlRenderer(presentationPlan, questionAssets, answerAssets,
					sharedContextAssets);
			List<Path> htmlFiles = htmlRenderer.render(corpus, staging);
			progress.update("Validating export...", 0, 0);
			validator.validate(staging, corpus, htmlFiles, questionAssets, answerAssets, sharedContextAssets);
			progress.update("Publishing export...", 0, 0);
			promote(staging, destination);
			promoted = true;
			progress.update("Export complete.", 1, 1);
			return new RevisionExportResult(destination, corpus.getStatistics());
		} finally {
			if (!promoted && Files.exists(staging)) {
				deleteRecursively(staging);
			}
		}
	}

	private void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private void promote(Path staging, Path destination) throws IOException {
		try {
			Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(staging, destination);
		}
	}

	private void validateDestination(Path destination) throws IOException {
		if (Files.exists(destination)) {
			throw new IOException("Revision export destination already exists: " + destination);
		}
		Path fileName = destination.getFileName();
		if (fileName == null || fileName.toString().isBlank()) {
			throw new IOException("Revision export destination must name a directory");
		}
	}
}
