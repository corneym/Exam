package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionCorpusScope;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlan;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

/**
 * Coordinates complete static revision export generation.
 * <p>
 * Output is generated and validated in a sibling staging directory and is only
 * promoted to the requested destination after validation succeeds.
 */
public final class RevisionExportService {

	// Corpus construction and revision rendering collaborators.
	private final RevisionAnswerAssetRenderer answerAssetRenderer;
	private final Clock clock;
	private final RevisionCorpusBuilder corpusBuilder;
	private final AtomicReference<Instant> lastGeneratedInstant = new AtomicReference<>();
	private final RevisionPresentationPlanner presentationPlanner;
	private final RevisionQuestionAssetRenderer questionAssetRenderer;
	private final RevisionSharedContextAssetRenderer sharedContextAssetRenderer;
	private final RevisionExportValidator validator;

	// Revision scope selection (initialisation order retained).
	private final RevisionCorpusScope corpusScope = new RevisionCorpusScope();

	/**
	 * Creates an exporter from corpus, rendering and validation services.
	 *
	 * @param presentationPlanner        multipart grouping and numbering planner
	 * @param sharedContextAssetRenderer the reusable shared context image renderer
	 * @param corpusBuilder              the subject corpus builder
	 * @param questionAssetRenderer      the question image renderer
	 * @param answerAssetRenderer        the answer image renderer
	 * @param validator                  the generated-site validator
	 * @throws NullPointerException if any dependency is null
	 */
	public RevisionExportService(RevisionCorpusBuilder corpusBuilder, RevisionPresentationPlanner presentationPlanner,
			RevisionQuestionAssetRenderer questionAssetRenderer,
			RevisionSharedContextAssetRenderer sharedContextAssetRenderer,
			RevisionAnswerAssetRenderer answerAssetRenderer, RevisionExportValidator validator) {
		this(corpusBuilder, presentationPlanner, questionAssetRenderer, sharedContextAssetRenderer, answerAssetRenderer,
				validator, Clock.systemDefaultZone());
	}

	/**
	 * Creates an exporter using an explicit clock for deterministic generation
	 * identities.
	 *
	 * @param corpusBuilder              the subject corpus builder
	 * @param presentationPlanner        multipart grouping and numbering planner
	 * @param questionAssetRenderer      the question image renderer
	 * @param sharedContextAssetRenderer the reusable shared context image renderer
	 * @param answerAssetRenderer        the answer image renderer
	 * @param validator                  the generated-site validator
	 * @param clock                      clock and display time zone for export
	 *                                   identities
	 * @throws NullPointerException if any dependency is null
	 */
	public RevisionExportService(RevisionCorpusBuilder corpusBuilder, RevisionPresentationPlanner presentationPlanner,
			RevisionQuestionAssetRenderer questionAssetRenderer,
			RevisionSharedContextAssetRenderer sharedContextAssetRenderer,
			RevisionAnswerAssetRenderer answerAssetRenderer, RevisionExportValidator validator, Clock clock) {
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
		if (clock == null) {
			throw new NullPointerException("clock");
		}
		this.corpusBuilder = corpusBuilder;
		this.presentationPlanner = presentationPlanner;
		this.questionAssetRenderer = questionAssetRenderer;
		this.sharedContextAssetRenderer = sharedContextAssetRenderer;
		this.answerAssetRenderer = answerAssetRenderer;
		this.validator = validator;
		this.clock = clock;
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

		// Capture one identity for the whole export. Page generation and SCORM
		// packaging must not obtain separate wall-clock values as a long-running
		// export progresses.
		RevisionExportIdentity exportIdentity = nextExportIdentity();
		Path destination = request.getDestination().toAbsolutePath().normalize();
		validateDestination(destination);
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("Revision export destination must have a parent directory: " + destination);
		}
		Files.createDirectories(parent);
		String destinationName = destination.getFileName().toString();

		// Stage beside the destination so publication can use a same-filesystem move.
		Path staging = parent.resolve(destinationName + ".staging-" + UUID.randomUUID()).normalize();
		if (!staging.getParent().equals(parent)) {
			throw new IOException("Revision export staging directory escaped destination parent");
		}
		progress.update("Building revision corpus...", 0, 0);
		RevisionCorpus fullCorpus = corpusBuilder.build(request.getSubject());
		RevisionCorpus corpus;
		if (request.hasUnitSelection()) {
			corpus = corpusScope.selectUnits(fullCorpus, request.getSelectedUnitIds());
		} else {
			corpus = fullCorpus;
		}
		progress.update("Planning revision presentation...", 0, 0);

		// Legacy/internal callers may still use automatic safe grouping. New UI exports
		// carry the user's explicit choice.
		RevisionPresentationPlan presentationPlan;
		if (request.hasGroupingMode()) {
			presentationPlan = presentationPlanner.plan(corpus, request.getGroupingMode());
		} else {
			presentationPlan = presentationPlanner.plan(corpus);
		}
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
					sharedContextAssets, exportIdentity);
			List<Path> htmlFiles = htmlRenderer.render(corpus, staging);
			progress.update("Validating export...", 0, 0);

			// Check the complete site before exposing it at the requested destination.
			validator.validate(staging, corpus, htmlFiles, questionAssets, answerAssets, sharedContextAssets);
			progress.update("Publishing export...", 0, 0);
			promote(staging, destination);
			promoted = true;
			progress.update("Export complete.", 1, 1);
			return new RevisionExportResult(destination, corpus.getStatistics(), exportIdentity);
		} finally {

			// Only unfinished staging belongs to cleanup; successful publication retains
			// the destination.
			if (!promoted && Files.exists(staging)) {
				deleteRecursively(staging);
			}
		}
	}

	/**
	 * Returns the current Units containing revision questions that can be rendered.
	 *
	 * @param subject subject whose corpus will be inspected
	 * @return immutable Units in curriculum order
	 */
	public List<Unit> findExportableUnits(Subject subject) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		RevisionCorpus corpus = corpusBuilder.build(subject);
		return corpusScope.findNonEmptyUnits(corpus);
	}

	/**
	 * Returns whether the Subject's current renderable revision corpus has complete
	 * Descriptor-level coverage.
	 * <p>
	 * This is an export-time capability check. It does not alter classification or
	 * mapping data.
	 *
	 * @param subject subject being considered for export
	 * @return true when Descriptor grouping may safely be offered
	 */
	public boolean isDescriptorGroupingAvailable(Subject subject) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		RevisionCorpus corpus = corpusBuilder.build(subject);
		return presentationPlanner.isDescriptorGroupingAvailable(corpus);
	}

	/**
	 * Returns whether Descriptor grouping is safe within a selected Unit scope.
	 *
	 * @param subject         subject being considered for export
	 * @param selectedUnitIds identifiers of Units included in the export
	 * @return {@code true} when every renderable scoped placement is at Descriptor
	 *         level
	 */
	public boolean isDescriptorGroupingAvailable(Subject subject, Set<Long> selectedUnitIds) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (selectedUnitIds == null) {
			throw new NullPointerException("selectedUnitIds");
		}
		RevisionCorpus fullCorpus = corpusBuilder.build(subject);
		RevisionCorpus scopedCorpus = corpusScope.selectUnits(fullCorpus, selectedUnitIds);
		return presentationPlanner.isDescriptorGroupingAvailable(scopedCorpus);
	}

	private void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> paths = Files.walk(root)) {

			// Remove descendants before their directories.
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private RevisionExportIdentity nextExportIdentity() {
		Instant observed = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		Instant generatedAt = lastGeneratedInstant.updateAndGet(
				previous -> previous == null || observed.isAfter(previous) ? observed : previous.plusMillis(1));
		return new RevisionExportIdentity(generatedAt.atZone(clock.getZone()));
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
