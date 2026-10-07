package au.edu.eq.questionbank.repository.assessment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;

/**
 * Replaces the managed PDF backing one Exam booklet while invalidating only
 * capture data whose coordinates depend on the old PDF.
 * <p>
 * Question identity, metadata, classification, marks, response type, answers
 * and stored image content are preserved. PDF-backed Question regions and
 * Shared Context regions are invalidated because their coordinates refer to the
 * replaced document.
 */
public final class QuestionBookletPdfReplacementService {

	private final SqliteDatabase database;
	private final PdfStore pdfStore;
	private final SourceDocumentHashService hashService;

	/**
	 * Creates a Question-booklet PDF replacement service supporting both
	 * Subject-first and legacy persisted paths during migration.
	 *
	 * @param database          question-bank database
	 * @param managedDataLayout canonical application managed-data layout
	 * @param legacyPdfDataRoot former dedicated Exam PDF root
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public QuestionBookletPdfReplacementService(SqliteDatabase database, ManagedDataLayout managedDataLayout,
			Path legacyPdfDataRoot) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		if (legacyPdfDataRoot == null) {
			throw new NullPointerException("legacyPdfDataRoot");
		}
		this.database = database;
		hashService = new SourceDocumentHashService();
		pdfStore = new PdfStore(managedDataLayout, legacyPdfDataRoot);
	}

	/**
	 * Creates a Question-booklet PDF replacement service using the legacy
	 * PDF-root-relative storage contract.
	 *
	 * @param database    question-bank database
	 * @param pdfDataRoot legacy managed Exam PDF root
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public QuestionBookletPdfReplacementService(SqliteDatabase database, Path pdfDataRoot) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (pdfDataRoot == null) {
			throw new NullPointerException("pdfDataRoot");
		}
		this.database = database;
		hashService = new SourceDocumentHashService();
		pdfStore = new PdfStore(pdfDataRoot);
	}

	/**
	 * Reports the persisted capture that would be affected by replacing a booklet
	 * PDF.
	 *
	 * @param booklet persisted booklet being inspected
	 * @return replacement impact
	 * @throws SQLException         if persistence cannot be inspected
	 * @throws NullPointerException if {@code booklet} is {@code null}
	 */
	public Impact assess(ExamBooklet booklet) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		try (Connection connection = database.openConnection()) {
			verifyPersistedBooklet(connection, booklet);
			return readImpact(connection, booklet.getId());
		}
	}

	/**
	 * Replaces the PDF backing one active Exam booklet.
	 * <p>
	 * The managed path and SourceDocument identity remain stable. This operation
	 * changes the bytes occupying that managed asset slot and updates its SHA-256
	 * identity.
	 *
	 * @param booklet        persisted booklet whose PDF is being corrected
	 * @param replacementPdf existing replacement PDF
	 * @return replacement result and invalidation impact
	 * @throws IOException              if managed or replacement bytes cannot be
	 *                                  read, copied or restored
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the replacement is invalid, duplicates a
	 *                                  different managed document, or the
	 *                                  SourceDocument is shared
	 * @throws IllegalStateException    if the Exam is complete or the current
	 *                                  managed bytes conflict with their persisted
	 *                                  hash
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
			throw new IOException("Replacement Question PDF is not a regular file: " + replacement);
		}
		SourceDocument currentSourceDocument = booklet.getSourceDocument();
		Path managedPdf = pdfStore.resolve(currentSourceDocument.getRelativePath());
		if (!Files.isRegularFile(managedPdf)) {
			throw new IOException("Managed Question PDF is missing or is not a regular file: " + managedPdf);
		}
		String currentManagedHash = hashService.sha256(managedPdf);
		String replacementHash = hashService.sha256(replacement);

		// A persisted hash describes the authoritative managed bytes. Refuse to
		// overwrite unexpected filesystem changes without an explicit repair path.
		if (currentSourceDocument.getContentSha256() != null
				&& !currentSourceDocument.getContentSha256().equals(currentManagedHash)) {
			throw new IllegalStateException("Managed Question PDF does not match its persisted SHA-256 identity");
		}
		Impact impact;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				verifyPersistedBooklet(connection, booklet);
				impact = readImpact(connection, booklet.getId());

				// Selecting the same bytes is not a destructive replacement. It may
				// legitimately back-fill a migrated SourceDocument whose hash was NULL.
				if (currentManagedHash.equals(replacementHash)) {
					updateSourceDocumentHash(connection, currentSourceDocument.getId(), replacementHash);
					connection.commit();
					ExamBooklet unchanged = rebuildBooklet(booklet, replacementHash);
					return new Result(unchanged, impact, false);
				}
				verifyExamActive(connection, booklet);
				verifySourceDocumentExclusiveToBooklet(connection, booklet);
				verifyReplacementHashIsNotAnotherManagedDocument(connection, currentSourceDocument.getId(),
						replacementHash);
				connection.rollback();
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		}
		Path backup = null;
		boolean managedBytesChanged = false;
		try {
			if (!Files.isSameFile(replacement, managedPdf)) {

				// Keep a private copy of the old authoritative bytes so a later SQLite
				// failure can restore the filesystem state.
				backup = Files.createTempFile(managedPdf.getParent(), ".question-booklet-backup-", ".pdf");
				Files.copy(managedPdf, backup, StandardCopyOption.REPLACE_EXISTING);

				// The booklet keeps the same managed path. Only the authoritative bytes
				// occupying that asset slot are replaced.
				Files.copy(replacement, managedPdf, StandardCopyOption.REPLACE_EXISTING);
				managedBytesChanged = true;

				// Detect a source file that changed concurrently while it was being copied.
				if (!replacementHash.equals(hashService.sha256(managedPdf))) {
					throw new IOException("Replacement Question PDF changed while it was being copied");
				}
			}
			try (Connection connection = database.openConnection()) {
				connection.setAutoCommit(false);
				try {
					verifyPersistedBooklet(connection, booklet);
					verifyExamActive(connection, booklet);
					verifySourceDocumentExclusiveToBooklet(connection, booklet);
					verifyReplacementHashIsNotAnotherManagedDocument(connection, currentSourceDocument.getId(),
							replacementHash);
					Impact committedImpact = readImpact(connection, booklet.getId());

					// Only Questions that actually owned old booklet PDF regions require
					// Question-source recapture. Preserved image-only content is not enough
					// to resolve that requirement.
					markQuestionSourceCaptureRequired(connection, booklet.getId());

					// Any Question previously linked to Shared Context still requires that
					// material after its obsolete coordinates are discarded.
					markSharedContextQuestionsForRecapture(connection, booklet.getId());

					// Pending continuation points at old PDF coordinates and cannot survive
					// replacement of the underlying booklet.
					clearPendingSharedContext(connection, booklet.getId());

					// Unlink Questions before deleting their now-invalid Shared Context.
					clearQuestionSharedContexts(connection, booklet.getId());
					deleteSharedContexts(connection, booklet.getId());

					// Deleting Question regions cascades only their PDF composition rows.
					// Stored image rows and IMAGE content parts remain intact.
					deleteQuestionPdfRegions(connection, booklet.getId());
					updateSourceDocumentHash(connection, currentSourceDocument.getId(), replacementHash);
					connection.commit();
					ExamBooklet replaced = rebuildBooklet(booklet, replacementHash);
					cleanupBackup(backup);
					return new Result(replaced, committedImpact, true);
				} catch (SQLException | RuntimeException exception) {
					try {
						connection.rollback();
					} catch (SQLException rollbackFailure) {
						exception.addSuppressed(rollbackFailure);
					}
					throw exception;
				}
			}
		} catch (SQLException | IOException | RuntimeException failure) {
			if (managedBytesChanged && backup != null) {
				try {

					// SQLite did not commit, so restore the old authoritative managed
					// document before exposing the failure to the caller.
					Files.copy(backup, managedPdf, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
			}
			cleanupBackupAfterFailure(backup, failure);
			throw failure;
		}
	}

	private void cleanupBackup(Path backup) {
		if (backup == null) {
			return;
		}
		try {
			Files.deleteIfExists(backup);
		} catch (IOException exception) {

			// Replacement is already committed. A leftover private backup is a cleanup
			// problem rather than grounds for reporting the replacement as failed.
			backup.toFile().deleteOnExit();
		}
	}

	private void cleanupBackupAfterFailure(Path backup, Throwable failure) {
		if (backup == null) {
			return;
		}
		try {
			Files.deleteIfExists(backup);
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
			backup.toFile().deleteOnExit();
		}
	}

	private void clearPendingSharedContext(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE exam_booklets
				SET pending_mcq_shared_context_id = NULL
				WHERE id = ?
				""")) {
			statement.setLong(1, bookletId);
			if (statement.executeUpdate() != 1) {
				throw new IllegalArgumentException("Exam booklet does not exist: " + bookletId);
			}
		}
	}

	private void clearQuestionSharedContexts(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET shared_context_id = NULL
				WHERE booklet_id = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.executeUpdate();
		}
	}

	private void deleteQuestionPdfRegions(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM question_regions
				WHERE booklet_id = ?
				""")) {
			statement.setLong(1, bookletId);

			// Schema-v14 foreign keys cascade each deleted region to only its
			// PDF_REGION composition row. IMAGE composition and image BLOBs survive.
			statement.executeUpdate();
		}
	}

	private void deleteSharedContexts(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement regions = connection.prepareStatement("""
				DELETE FROM shared_question_context_regions
				WHERE shared_context_id IN (
				    SELECT id
				    FROM shared_question_contexts
				    WHERE booklet_id = ?
				)
				"""); PreparedStatement contexts = connection.prepareStatement("""
				DELETE FROM shared_question_contexts
				WHERE booklet_id = ?
				""")) {
			regions.setLong(1, bookletId);
			regions.executeUpdate();

			// Context rows cannot survive without valid source coordinates.
			contexts.setLong(1, bookletId);
			contexts.executeUpdate();
		}
	}

	private void markQuestionSourceCaptureRequired(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET source_capture_required = 1
				WHERE booklet_id = ?
				  AND EXISTS (
				      SELECT 1
				      FROM question_regions qr
				      WHERE qr.question_id = questions.id
				  )
				""")) {
			statement.setLong(1, bookletId);

			// Record the recapture obligation before deleting the regions that prove why
			// the Question became incomplete.
			statement.executeUpdate();
		}
	}

	private void markSharedContextQuestionsForRecapture(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET shared_context_capture_required = 1
				WHERE booklet_id = ?
				  AND shared_context_id IS NOT NULL
				""")) {
			statement.setLong(1, bookletId);

			// Retain the requirement for shared material while discarding only its old
			// PDF coordinate capture.
			statement.executeUpdate();
		}
	}

	private Impact readImpact(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    (
				        SELECT COUNT(DISTINCT q.id)
				        FROM questions q
				        LEFT JOIN question_regions qr
				            ON qr.question_id = q.id
				        WHERE q.booklet_id = ?
				          AND (
				              qr.question_id IS NOT NULL
				              OR q.shared_context_id IS NOT NULL
				          )
				    ) AS affected_question_count,
				    (
				        SELECT COUNT(*)
				        FROM question_regions
				        WHERE booklet_id = ?
				    ) AS pdf_region_count,
				    (
				        SELECT COUNT(*)
				        FROM shared_question_contexts
				        WHERE booklet_id = ?
				    ) AS shared_context_count,
				    (
				        SELECT COUNT(*)
				        FROM question_images qi
				        JOIN questions q
				            ON q.id = qi.question_id
				        WHERE q.booklet_id = ?
				    ) AS preserved_image_count
				""")) {
			statement.setLong(1, bookletId);
			statement.setLong(2, bookletId);
			statement.setLong(3, bookletId);
			statement.setLong(4, bookletId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Could not calculate Question booklet replacement impact");
				}
				return new Impact(result.getInt("affected_question_count"), result.getInt("pdf_region_count"),
						result.getInt("shared_context_count"), result.getInt("preserved_image_count"));
			}
		}
	}

	private ExamBooklet rebuildBooklet(ExamBooklet booklet, String contentSha256) {
		SourceDocument sourceDocument = new SourceDocument(booklet.getSourceDocument().getId(),
				booklet.getSourceDocument().getRelativePath(), contentSha256);

		// Replacement changes only the managed source bytes. Booklet identity and
		// structural planning metadata remain unchanged.
		return new ExamBooklet(booklet.getId(), booklet.getExam(), booklet.getName(), sourceDocument,
				booklet.getQuestionFormat(), booklet.getExpectedQuestionCount());
	}

	private void updateSourceDocumentHash(Connection connection, long sourceDocumentId, String contentSha256)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE source_documents
				SET content_sha256 = ?
				WHERE id = ?
				""")) {
			statement.setString(1, contentSha256);
			statement.setLong(2, sourceDocumentId);
			if (statement.executeUpdate() != 1) {
				throw new IllegalArgumentException("Source document does not exist: " + sourceDocumentId);
			}
		}
	}

	private void verifyExamActive(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT e.capture_state
				FROM exam_booklets eb
				JOIN exams e
				    ON e.id = eb.exam_id
				WHERE eb.id = ?
				  AND eb.exam_id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, booklet.getExam().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam booklet does not exist: " + booklet.getId());
				}
				ExamCaptureState state = ExamCaptureState.valueOf(result.getString("capture_state"));
				if (state == ExamCaptureState.COMPLETE) {
					throw new IllegalStateException(
							"Exam is complete; reactivate it before replacing a Question booklet PDF");
				}
			}
		}
	}

	private void verifyPersistedBooklet(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT source_document_id
				FROM exam_booklets
				WHERE id = ?
				  AND exam_id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, booklet.getExam().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam booklet does not exist: " + booklet.getId());
				}
				long sourceDocumentId = result.getLong("source_document_id");
				if (sourceDocumentId != booklet.getSourceDocument().getId()) {
					throw new IllegalArgumentException("Exam booklet no longer refers to the supplied SourceDocument");
				}
			}
		}
	}

	private void verifyReplacementHashIsNotAnotherManagedDocument(Connection connection, long sourceDocumentId,
			String replacementHash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT relative_path
				FROM source_documents
				WHERE content_sha256 = ?
				  AND id <> ?
				ORDER BY id
				LIMIT 1
				""")) {
			statement.setString(1, replacementHash);
			statement.setLong(2, sourceDocumentId);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {

					// Selecting bytes already owned by another managed document is a
					// duplicate/conflicting replacement, not a new authoritative asset.
					throw new IllegalArgumentException(
							"Replacement PDF is already managed as " + result.getString("relative_path"));
				}
			}
		}
	}

	private void verifySourceDocumentExclusiveToBooklet(Connection connection, ExamBooklet booklet)
			throws SQLException {
		long sourceDocumentId = booklet.getSourceDocument().getId();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    (
				        SELECT COUNT(*)
				        FROM exam_booklets
				        WHERE source_document_id = ?
				          AND id <> ?
				    )
				    +
				    (
				        SELECT COUNT(*)
				        FROM answer_files
				        WHERE source_document_id = ?
				    ) AS other_reference_count
				""")) {
			statement.setLong(1, sourceDocumentId);
			statement.setLong(2, booklet.getId());
			statement.setLong(3, sourceDocumentId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Could not verify SourceDocument ownership");
				}
				if (result.getInt("other_reference_count") != 0) {

					// Replacing bytes in-place is safe only when no other persisted asset
					// relies on those same managed bytes.
					throw new IllegalArgumentException(
							"Question booklet SourceDocument is shared by another managed asset");
				}
			}
		}
	}

	/**
	 * Persisted capture affected by a Question-booklet PDF replacement.
	 *
	 * @param affectedQuestionCount number of Questions losing PDF-derived content
	 *                              or Shared Context
	 * @param pdfRegionCount        Question regions tied to the old PDF
	 * @param sharedContextCount    Shared Context objects tied to the old PDF
	 * @param preservedImageCount   independent stored image fragments retained
	 */
	public record Impact(int affectedQuestionCount, int pdfRegionCount, int sharedContextCount,
			int preservedImageCount) {

		/**
		 * Indicates whether replacing the booklet PDF would invalidate persisted
		 * PDF-derived capture.
		 *
		 * @return whether replacement invalidates any PDF-derived capture
		 */
		public boolean hasSourceDependentCapture() {
			return affectedQuestionCount > 0 || pdfRegionCount > 0 || sharedContextCount > 0;
		}
	}

	/**
	 * Result of a completed replacement.
	 *
	 * @param booklet        refreshed booklet carrying the authoritative source
	 *                       hash
	 * @param impact         capture that existed before replacement
	 * @param contentChanged whether authoritative PDF bytes actually changed
	 */
	public record Result(ExamBooklet booklet, Impact impact, boolean contentChanged) {

		/** Validates replacement result state. */
		public Result {
			if (booklet == null) {
				throw new NullPointerException("booklet");
			}
			if (impact == null) {
				throw new NullPointerException("impact");
			}
		}
	}
}
