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

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.pdf.PdfStore;

/**
 * Corrects Exam metadata together with the managed filesystem locations derived
 * from provider and year.
 * <p>
 * Files are moved first, then Exam metadata and SourceDocument paths are
 * updated in one SQLite transaction. If persistence fails, completed file moves
 * are reversed.
 */
public final class ExamMetadataCorrectionService {

	private final Path pdfDataRoot;
	private final PdfStore pdfStore;
	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;

	/**
	 * Creates the correction service.
	 *
	 * @param pdfDataRoot  managed PDF data root
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
		this.pdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		this.pdfStore = new PdfStore(this.pdfDataRoot);
		this.examWriter = examWriter;
		this.answerWriter = answerWriter;
	}

	/**
	 * Corrects one Exam and relocates all managed booklet and answer PDFs to the
	 * authoritative subject/provider/year directory.
	 *
	 * @param exam           persisted Exam
	 * @param providerName   corrected provider
	 * @param year           corrected year
	 * @param assessmentName corrected assessment name
	 * @return corrected metadata plus replacement SourceDocument paths
	 * @throws SQLException if database persistence fails
	 * @throws IOException  if a managed source cannot be relocated
	 */
	public Result correct(Exam exam, String providerName, int year, String assessmentName)
			throws SQLException, IOException {
		validateCorrection(exam, providerName, year, assessmentName);
		List<Relocation> relocations = createRelocationPlan(exam, providerName, year);
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
			return new Result(corrected, replacementPaths);
		} catch (SQLException | IOException | RuntimeException failure) {

			// Filesystem and SQLite cannot share one transaction. Reverse every
			// completed move if the later operation fails.
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

	private List<Relocation> createRelocationPlan(Exam exam, String providerName, int year)
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
			Path destination = pdfStore.managedDestination(source, exam.getSubject().getName(), providerName, year);
			Long existingOwner = destinationOwners.putIfAbsent(destination, sourceDocument.getId());
			if (existingOwner != null && existingOwner.longValue() != sourceDocument.getId()) {
				throw new IllegalStateException("More than one source document would be relocated to " + destination);
			}
			if (!source.equals(destination) && Files.exists(destination)) {
				throw new FileAlreadyExistsException(destination.toString(), source.toString(),
						"A managed PDF already exists at the corrected exam location");
			}
			String relativeDestination = pdfDataRoot.relativize(destination).toString();
			relocations.add(new Relocation(sourceDocument.getId(), source, destination, relativeDestination));
		}
		return List.copyOf(relocations);
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

	private record Relocation(long sourceDocumentId, Path source, Path destination, String relativeDestination) {

		private boolean requiresMove() {
			return !source.equals(destination);
		}
	}
}
