package au.edu.eq.questionbank.repository.assessment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;

/**
 * Registers or reuses a managed replacement Answer PDF and safely reassigns one
 * Exam booklet to it.
 * <p>
 * Existing AnswerFiles are never overwritten in place because one AnswerFile
 * may legitimately serve several booklets. A newly selected document therefore
 * becomes a separate managed asset unless its SHA-256 identity already resolves
 * unambiguously to an AnswerFile belonging to the same Exam.
 */
public final class AnswerPdfReplacementService {

	private final Path pdfRoot;
	private final PdfStore pdfStore;
	private final SourceDocumentHashService hashService;
	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;
	private final AnswerFileReassignmentService reassignmentService;

	/**
	 * Creates an Answer-PDF replacement service.
	 *
	 * @param database question-bank database
	 * @param pdfRoot  managed Exam PDF root
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public AnswerPdfReplacementService(SqliteDatabase database, Path pdfRoot) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (pdfRoot == null) {
			throw new NullPointerException("pdfRoot");
		}
		this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
		this.pdfStore = new PdfStore(this.pdfRoot);
		this.hashService = new SourceDocumentHashService();
		this.examWriter = new SqliteExamWriter(database);
		this.answerWriter = new SqliteAnswerWriter(database, examWriter);
		this.reassignmentService = new AnswerFileReassignmentService(database);
	}

	/**
	 * Reports the source-dependent Answer capture affected by replacement.
	 *
	 * @param booklet persisted booklet being inspected
	 * @return replacement impact
	 * @throws SQLException if persistence cannot be read
	 */
	public AnswerFileReassignmentService.Impact assess(ExamBooklet booklet) throws SQLException {

		// Reuse the structural lifecycle and assignment checks from the authoritative
		// reassignment service.
		return reassignmentService.assess(booklet);
	}

	/**
	 * Registers or reuses the selected PDF and reassigns the booklet to it.
	 *
	 * @param booklet        booklet whose Answer PDF is being corrected
	 * @param replacementPdf selected replacement PDF
	 * @return committed replacement result
	 * @throws IOException              if managed document bytes cannot be read or
	 *                                  copied
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if duplicate managed content cannot be
	 *                                  resolved safely
	 * @throws IllegalStateException    if the current managed document identity is
	 *                                  inconsistent or the Exam cannot be changed
	 */
	public Result replace(ExamBooklet booklet, Path replacementPdf) throws IOException, SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (replacementPdf == null) {
			throw new NullPointerException("replacementPdf");
		}
		Path replacement = replacementPdf.toAbsolutePath().normalize();
		if (!Files.isRegularFile(replacement)) {
			throw new IOException("Replacement Answer PDF is not a regular file: " + replacement);
		}

		// This also rejects COMPLETE Exams before any new managed asset is created.
		AnswerFileReassignmentService.Impact impact = reassignmentService.assess(booklet);
		AnswerFile current = answerWriter.findAnswerFile(booklet);
		if (current == null) {
			throw new IllegalStateException("Exam booklet has no assigned AnswerFile");
		}
		Path currentManagedPath = pdfStore.resolve(current.getSourceDocument().getRelativePath());
		if (!Files.isRegularFile(currentManagedPath)) {
			throw new IOException("Managed Answer PDF is missing or is not a regular file: " + currentManagedPath);
		}
		String currentManagedHash = hashService.sha256(currentManagedPath);
		String persistedCurrentHash = current.getSourceDocument().getContentSha256();
		if (persistedCurrentHash != null && !persistedCurrentHash.equals(currentManagedHash)) {

			// Never make structural decisions from a SourceDocument whose persisted
			// identity no longer describes its managed bytes.
			throw new IllegalStateException("Managed Answer PDF does not match its persisted SHA-256 identity");
		}
		if (persistedCurrentHash == null) {

			// Reading the current bytes gives enough evidence to safely back-fill an
			// older migrated SourceDocument before continuing.
			current = answerWriter.findOrCreateAnswerFile(booklet.getExam(), current.getName(),
					current.getSourceDocument().getRelativePath(), currentManagedHash);
		}
		String replacementHash = hashService.sha256(replacement);
		if (currentManagedHash.equals(replacementHash)) {

			// Selecting byte-identical content is recognition, not destructive
			// replacement. Existing Answer regions remain valid.
			return new Result(current, impact, false, currentManagedPath);
		}
		AnswerFile existingMatch = findExistingManagedAnswerFile(booklet.getExam(), replacementHash);
		if (existingMatch != null) {
			AnswerFileReassignmentService.Result reassigned = reassignmentService.reassign(booklet, existingMatch);
			Path managedPath = pdfStore.resolve(existingMatch.getSourceDocument().getRelativePath());
			return new Result(reassigned.answerFile(), reassigned.impact(), reassigned.changed(), managedPath);
		}
		ManagedCopy managedCopy = copyIntoManagedStore(booklet.getExam(), replacement, replacementHash);
		String finalManagedHash;
		try {
			finalManagedHash = hashService.sha256(managedCopy.path());
			if (!replacementHash.equals(finalManagedHash)) {
				throw new IOException("Replacement Answer PDF changed while it was being copied");
			}
		} catch (IOException failure) {
			cleanupUnregisteredCopy(managedCopy, failure);
			throw failure;
		}
		String relativePath = pdfRoot.relativize(managedCopy.path()).toString();
		AnswerFile replacementFile;
		try {

			// Use the actual managed filename as the asset label. Collision-safe copies
			// therefore also acquire a distinct AnswerFile natural key.
			replacementFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(),
					managedCopy.path().getFileName().toString(), relativePath, finalManagedHash);
		} catch (SQLException | RuntimeException failure) {
			cleanupUnregisteredCopy(managedCopy, failure);
			throw failure;
		}

		// From this point the managed PDF is a legitimate registered Exam asset even if
		// a later reassignment fails, so it must not be deleted as an orphan file.
		AnswerFileReassignmentService.Result reassigned = reassignmentService.reassign(booklet, replacementFile);
		return new Result(reassigned.answerFile(), reassigned.impact(), reassigned.changed(), managedCopy.path());
	}

	private void cleanupUnregisteredCopy(ManagedCopy managedCopy, Throwable failure) {
		if (!managedCopy.created()) {
			return;
		}
		try {
			Files.deleteIfExists(managedCopy.path());
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private ManagedCopy copyIntoManagedStore(Exam exam, Path source, String contentSha256) throws IOException {
		Path standardDestination = pdfStore.managedDestination(source, exam.getSubject().getName(),
				exam.getProvider().getName(), exam.getYear());
		Files.createDirectories(standardDestination.getParent());
		if (source.equals(standardDestination)) {
			return new ManagedCopy(standardDestination, false);
		}
		if (!Files.exists(standardDestination)) {
			Files.copy(source, standardDestination);
			return new ManagedCopy(standardDestination, true);
		}
		if (Files.isSameFile(source, standardDestination) || Files.mismatch(source, standardDestination) == -1) {
			return new ManagedCopy(standardDestination, false);
		}

		// A wrong and a correct marking guide frequently have the same filename.
		// Preserve the old managed asset and create a stable hash-qualified sibling.
		Path shortHashDestination = hashQualifiedDestination(standardDestination, contentSha256.substring(0, 12));
		ManagedCopy shortHashCopy = copyOrReuse(source, shortHashDestination);
		if (shortHashCopy != null) {
			return shortHashCopy;
		}

		// A 12-character prefix collision is unlikely but must not silently select the
		// wrong file. Fall back to the complete digest.
		Path fullHashDestination = hashQualifiedDestination(standardDestination, contentSha256);
		ManagedCopy fullHashCopy = copyOrReuse(source, fullHashDestination);
		if (fullHashCopy != null) {
			return fullHashCopy;
		}
		throw new IOException("Could not allocate a collision-safe managed Answer PDF filename");
	}

	private ManagedCopy copyOrReuse(Path source, Path destination) throws IOException {
		if (!Files.exists(destination)) {
			Files.copy(source, destination);
			return new ManagedCopy(destination, true);
		}
		if (Files.isSameFile(source, destination) || Files.mismatch(source, destination) == -1) {
			return new ManagedCopy(destination, false);
		}
		return null;
	}

	private AnswerFile findExistingManagedAnswerFile(Exam exam, String contentSha256) throws SQLException {
		List<SourceDocument> matchingDocuments = examWriter.findSourceDocumentsByHash(contentSha256);
		if (matchingDocuments.isEmpty()) {
			return null;
		}
		Set<Long> matchingDocumentIds = new HashSet<>();
		for (SourceDocument document : matchingDocuments) {
			matchingDocumentIds.add(document.getId());
		}
		List<AnswerFile> matchingAnswerFiles = answerWriter.findAnswerFiles(exam).stream()
				.filter(answerFile -> matchingDocumentIds.contains(answerFile.getSourceDocument().getId())).toList();
		if (matchingAnswerFiles.size() == 1) {

			// An explicit replacement may safely reuse one unambiguous AnswerFile already
			// belonging to this Exam.
			return matchingAnswerFiles.getFirst();
		}
		if (matchingAnswerFiles.size() > 1) {
			throw new IllegalArgumentException(
					"Replacement PDF matches more than one AnswerFile already managed for this Exam");
		}

		// The bytes are known, but not as an AnswerFile belonging to this Exam. Do not
		// silently reinterpret a Question PDF or another Exam's asset as this answer
		// source.
		throw new IllegalArgumentException(
				"Replacement PDF matches another managed document but not an AnswerFile for this Exam");
	}

	private Path hashQualifiedDestination(Path standardDestination, String hashText) {
		String filename = standardDestination.getFileName().toString();
		int extensionIndex = filename.lastIndexOf('.');
		String stem = extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
		String extension = extensionIndex > 0 ? filename.substring(extensionIndex) : "";
		return standardDestination.getParent().resolve(stem + "-" + hashText + extension);
	}

	/**
	 * Result of a managed Answer-PDF replacement.
	 *
	 * @param answerFile  AnswerFile ultimately assigned to the booklet
	 * @param impact      source-dependent Answer capture present before replacement
	 * @param changed     whether the booklet assignment changed
	 * @param managedPath authoritative managed PDF path
	 */
	public record Result(AnswerFile answerFile, AnswerFileReassignmentService.Impact impact, boolean changed,
			Path managedPath) {

		/**
		 * Validates replacement result state.
		 */
		public Result {
			if (answerFile == null) {
				throw new NullPointerException("answerFile");
			}
			if (impact == null) {
				throw new NullPointerException("impact");
			}
			if (managedPath == null) {
				throw new NullPointerException("managedPath");
			}
		}
	}

	private record ManagedCopy(Path path, boolean created) {
	}
}
