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

	public RevisionExportService(RevisionCorpusBuilder corpusBuilder,
			RevisionQuestionAssetRenderer questionAssetRenderer, RevisionAnswerAssetRenderer answerAssetRenderer,
			RevisionExportValidator validator) {
		if (corpusBuilder == null) {
			throw new NullPointerException("corpusBuilder");
		}
		if (questionAssetRenderer == null) {
			throw new NullPointerException("questionAssetRenderer");
		}
		if (answerAssetRenderer == null) {
			throw new NullPointerException("answerAssetRenderer");
		}
		if (validator == null) {
			throw new NullPointerException("validator");
		}
		this.corpusBuilder = corpusBuilder;
		this.questionAssetRenderer = questionAssetRenderer;
		this.answerAssetRenderer = answerAssetRenderer;
		this.validator = validator;
	}

	public RevisionExportResult export(RevisionExportRequest request) throws IOException {
		if (request == null) {
			throw new NullPointerException("request");
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
		RevisionCorpus corpus = corpusBuilder.build(request.getSubject());
		boolean promoted = false;
		try {
			Files.createDirectory(staging);
			List<RevisionQuestionAsset> questionAssets = questionAssetRenderer.render(corpus, staging);
			List<RevisionAnswerAsset> answerAssets = answerAssetRenderer.render(corpus, staging);
			RevisionHtmlRenderer htmlRenderer = new RevisionHtmlRenderer(questionAssets, answerAssets);
			List<Path> htmlFiles = htmlRenderer.render(corpus, staging);
			validator.validate(staging, corpus, htmlFiles, questionAssets, answerAssets);
			promote(staging, destination);
			promoted = true;
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
