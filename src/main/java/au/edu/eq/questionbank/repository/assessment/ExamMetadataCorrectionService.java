package au.edu.eq.questionbank.repository.assessment;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.pdf.PdfStore;

/**
 * Corrects Exam metadata together with every managed Question and Answer source
 * location derived from that Exam identity.
 * <p>
 * Subject-first corrections relocate managed sources beneath the corrected
 * Subject/Provider/Year/Assessment directory. Legacy-only callers retain their
 * historical PDF-root-relative behaviour during migration.
 * <p>
 * Files are moved first, then Exam metadata and all affected SourceDocument
 * paths are updated atomically in SQLite. If persistence fails, completed file
 * moves are reversed.
 */
public final class ExamMetadataCorrectionService {

	private final SqliteAnswerWriter answerWriter;
	private final SqliteExamWriter examWriter;
	private final Path legacyPdfDataRoot;
	private final ManagedDataLayout managedDataLayout;
	private final PdfStore pdfStore;

	/**
	 * Creates the correction service using the Subject-first managed-data layout
	 * while retaining read access to legacy persisted paths.
	 *
	 * @param managedDataLayout canonical application managed-data layout
	 * @param legacyPdfDataRoot former dedicated PDF data root
	 * @param examWriter        Exam and SourceDocument persistence
	 * @param answerWriter      AnswerFile lookup
	 */
	public ExamMetadataCorrectionService(ManagedDataLayout managedDataLayout, Path legacyPdfDataRoot,
			SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		if (legacyPdfDataRoot == null) {
			throw new NullPointerException("legacyPdfDataRoot");
		}
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		this.answerWriter = answerWriter;
		this.examWriter = examWriter;
		this.legacyPdfDataRoot = legacyPdfDataRoot.toAbsolutePath().normalize();
		this.managedDataLayout = managedDataLayout;
		pdfStore = new PdfStore(managedDataLayout, this.legacyPdfDataRoot);
	}

	/**
	 * Creates the correction service using the legacy PDF-root-relative layout.
	 *
	 * @param pdfDataRoot  legacy managed PDF data root
	 * @param examWriter   Exam and SourceDocument persistence
	 * @param answerWriter AnswerFile lookup
	 */
	public ExamMetadataCorrectionService(Path pdfDataRoot, SqliteExamWriter examWriter,
			SqliteAnswerWriter answerWriter) {
		if (pdfDataRoot == null) {
			throw new NullPointerException("pdfDataRoot");
		}
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		this.answerWriter = answerWriter;
		this.examWriter = examWriter;
		legacyPdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		managedDataLayout = null;
		pdfStore = new PdfStore(legacyPdfDataRoot);
	}

	/**
	 * Corrects one Exam and relocates all managed booklet and Answer PDFs to the
	 * authoritative corrected Exam directory.
	 * <p>
	 * The corrected Provider, Year and Assessment identify the destination for
	 * every managed source owned exclusively by the Exam. Filesystem moves are
	 * completed before Exam metadata and SourceDocument paths are committed
	 * together in SQLite. If persistence fails, completed file moves are reversed.
	 *
	 * @param exam           persisted Exam whose metadata and managed sources are
	 *                       being corrected
	 * @param providerName   corrected provider name
	 * @param year           corrected examination year
	 * @param assessmentName corrected assessment name
	 * @return corrected Exam metadata and the replacement SourceDocument paths,
	 *         keyed by SourceDocument id
	 * @throws SQLException             if persistence cannot be read or updated
	 * @throws IOException              if a managed source cannot be validated,
	 *                                  relocated or restored after failure
	 * @throws NullPointerException     if {@code exam} is {@code null}
	 * @throws IllegalArgumentException if corrected metadata is invalid or a
	 *                                  relocation destination conflicts with an
	 *                                  existing managed file
	 * @throws IllegalStateException    if a SourceDocument is shared with another
	 *                                  Exam or multiple sources would collide at
	 *                                  the corrected destination
	 */
	public Result correct(Exam exam, String providerName, int year, String assessmentName)
			throws SQLException, IOException {
		validateCorrection(exam, providerName, year, assessmentName);
		List<Relocation> relocations = createRelocationPlan(exam, providerName, year, assessmentName);
		List<Relocation> completedMoves = new ArrayList<>();
		try {
			for (Relocation relocation : relocations) {
				if (!relocation.requiresMove()) {
					continue;
				}

				// Move without REPLACE_EXISTING. A concurrently-created destination must
				// stop the correction rather than overwrite another managed source.
				Files.createDirectories(relocation.destination().getParent());
				Files.move(relocation.source(), relocation.destination());
				completedMoves.add(relocation);
			}
			Map<Long, String> replacementPaths = new LinkedHashMap<>();
			for (Relocation relocation : relocations) {
				replacementPaths.put(relocation.sourceDocumentId(), relocation.relativeDestination());
			}
			Exam corrected = examWriter.correctExamMetadataAndSourceDocumentPaths(exam, providerName, year,
					assessmentName, replacementPaths);

			// Files become authoritative only after the metadata/path transaction commits.
			pruneEmptySourceDirectories(completedMoves);
			return new Result(corrected, replacementPaths);
		} catch (SQLException | IOException | RuntimeException failure) {

			// Filesystem and SQLite cannot share one transaction. Reverse every completed
			// move when persistence or a later relocation fails.
			rollbackMoves(completedMoves, failure);
			throw failure;
		}
	}

	private List<SourceDocument> collectSourceDocuments(Exam exam) throws SQLException {
		Map<Long, SourceDocument> documents = new LinkedHashMap<>();
		for (ExamBooklet booklet : examWriter.findAllExamBooklets()) {
			if (booklet.getExam().getId() != exam.getId()) {
				continue;
			}
			documents.putIfAbsent(booklet.getSourceDocument().getId(), booklet.getSourceDocument());
		}
		for (AnswerFile answerFile : answerWriter.findAnswerFiles(exam)) {
			documents.putIfAbsent(answerFile.getSourceDocument().getId(), answerFile.getSourceDocument());
		}
		return List.copyOf(documents.values());
	}

	private boolean containsManagedPathSymbolicLink(Path directory, Path managedRoot) {
		Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
		Path normalizedDirectory = directory.toAbsolutePath().normalize();
		if (!normalizedDirectory.startsWith(normalizedRoot)) {
			return true;
		}
		Path relativeDirectory = normalizedRoot.relativize(normalizedDirectory);
		Path current = normalizedRoot;
		for (Path segment : relativeDirectory) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) {

				// Refuse to prune through a link because its real target is not proven to
				// remain within the owning managed tree.
				return true;
			}
		}
		return false;
	}

	private List<Relocation> createRelocationPlan(Exam exam, String providerName, int year, String assessmentName)
			throws SQLException, IOException {
		List<Relocation> relocations = new ArrayList<>();
		Map<Path, Long> destinationOwners = new LinkedHashMap<>();
		for (SourceDocument sourceDocument : collectSourceDocuments(exam)) {
			if (examWriter.sourceDocumentReferencedOutsideExam(sourceDocument.getId(), exam.getId())) {
				throw new IllegalStateException(
						"Source document is also used by another exam: " + sourceDocument.getRelativePath());
			}
			Path source = pdfStore.resolve(sourceDocument.getRelativePath());
			if (!Files.isRegularFile(source)) {
				throw new IOException("Managed PDF is missing or is not a regular file: " + source);
			}
			Path destination;
			String relativeDestination;
			if (managedDataLayout == null) {

				// Preserve the old contract for legacy callers and legacy-focused tests.
				destination = pdfStore.managedDestination(source, exam.getSubject().getName(), providerName, year);
				relativeDestination = legacyPdfDataRoot.relativize(destination).toString();
			} else {

				// Upgraded corrections always publish the complete corrected Exam identity,
				// including Assessment, through the Subject-first layout.
				destination = pdfStore.managedDestination(source, exam.getSubject().getName(), providerName, year,
						assessmentName);
				relativeDestination = pdfStore.relativePath(destination);
			}
			Long existingOwner = destinationOwners.putIfAbsent(destination, sourceDocument.getId());
			if (existingOwner != null && existingOwner.longValue() != sourceDocument.getId()) {
				throw new IllegalStateException("More than one source document would be relocated to " + destination);
			}
			if (!source.equals(destination) && Files.exists(destination)) {
				throw new FileAlreadyExistsException(destination.toString(), source.toString(),
						"A managed PDF already exists at the corrected exam location");
			}
			Path sourceRoot = sourceManagedRoot(sourceDocument.getRelativePath());
			relocations
					.add(new Relocation(sourceDocument.getId(), source, sourceRoot, destination, relativeDestination));
		}
		return List.copyOf(relocations);
	}

	private void pruneEmptyManagedDirectoryTree(Path startDirectory, Path managedRoot) {
		Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
		Path current = startDirectory.toAbsolutePath().normalize();
		while (current != null && current.startsWith(normalizedRoot) && !current.equals(normalizedRoot)) {

			// Never traverse a symbolic-link component inside the managed tree.
			if (containsManagedPathSymbolicLink(current, normalizedRoot)) {
				return;
			}
			if (!Files.exists(current)) {

				// Another relocation may already have removed the same empty directory.
				current = current.getParent();
				continue;
			}
			if (!Files.isDirectory(current)) {
				return;
			}
			try (var entries = Files.list(current)) {
				if (entries.findAny().isPresent()) {

					// The first non-empty ancestor is the pruning boundary.
					return;
				}
			} catch (IOException cleanupFailure) {

				// Directory pruning is non-authoritative housekeeping after commit.
				return;
			}
			try {
				Files.delete(current);
			} catch (IOException cleanupFailure) {

				// A cleanup race leaves only an obsolete directory.
				return;
			}
			current = current.getParent();
		}
	}

	private void pruneEmptySourceDirectories(List<Relocation> completedMoves) {

		// Each source may belong either to the old PDF root or the new data-root
		// hierarchy, so pruning must stop at that source's actual managed boundary.
		for (Relocation relocation : completedMoves) {
			Path sourceDirectory = relocation.source().getParent();
			if (sourceDirectory != null) {
				pruneEmptyManagedDirectoryTree(sourceDirectory, relocation.sourceRoot());
			}
		}
	}

	private void rollbackMoves(List<Relocation> completedMoves, Throwable originalFailure) {
		for (int index = completedMoves.size() - 1; index >= 0; index--) {
			Relocation relocation = completedMoves.get(index);
			try {
				Files.createDirectories(relocation.source().getParent());
				Files.move(relocation.destination(), relocation.source());
			} catch (IOException rollbackFailure) {

				// Preserve the original failure while retaining any rollback failure for
				// diagnosis. The caller must be told that filesystem recovery was incomplete.
				originalFailure.addSuppressed(rollbackFailure);
			}
		}
	}

	private Path sourceManagedRoot(String relativePath) {
		String portablePath = relativePath.replace('\\', '/');

		// Subject-first persisted paths are relative to dataRoot. All older paths are
		// still relative to the legacy PDF root until migration.
		if (managedDataLayout != null && (portablePath.equals("subjects") || portablePath.startsWith("subjects/"))) {
			return managedDataLayout.dataRoot();
		}
		return legacyPdfDataRoot;
	}

	private void validateCorrection(Exam exam, String providerName, int year, String assessmentName) {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (assessmentName == null || assessmentName.isBlank()) {
			throw new IllegalArgumentException("assessmentName must not be blank");
		}
	}

	/**
	 * Result of a completed correction.
	 *
	 * @param exam                corrected Exam
	 * @param sourceDocumentPaths new relative paths keyed by persistent
	 *                            SourceDocument id
	 */
	public record Result(Exam exam, Map<Long, String> sourceDocumentPaths) {

		/** Validates the corrected Exam and freezes the path mapping. */
		public Result {
			if (exam == null) {
				throw new NullPointerException("exam");
			}
			if (sourceDocumentPaths == null) {
				throw new NullPointerException("sourceDocumentPaths");
			}
			sourceDocumentPaths = Map.copyOf(sourceDocumentPaths);
		}
	}

	private record Relocation(long sourceDocumentId, Path source, Path sourceRoot, Path destination,
			String relativeDestination) {

		private boolean requiresMove() {
			return !source.equals(destination);
		}
	}
}
