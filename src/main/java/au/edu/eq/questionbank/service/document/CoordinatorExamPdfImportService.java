package au.edu.eq.questionbank.service.document;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;

/**
 * Imports coordinator-owned Exam PDFs into managed storage.
 *
 * <p>
 * Assignment-mode restrictions are checked before any managed directory or file
 * is created. Persisting the corresponding Exam, Booklet or AnswerFile metadata
 * remains the responsibility of the calling workflow.
 *
 * <p>
 * Database triggers independently protect structural persistence. This service
 * does not authorise bypassing those triggers.
 */
public final class CoordinatorExamPdfImportService {

	private final SqliteExamWriter examWriter;
	private final PdfStore store;

	/**
	 * Creates a guarded Exam PDF import service.
	 *
	 * @param store      managed Exam PDF storage
	 * @param examWriter persistent Exam editing boundary
	 * @throws NullPointerException if either argument is null
	 */
	public CoordinatorExamPdfImportService(PdfStore store, SqliteExamWriter examWriter) {
		this.store = Objects.requireNonNull(store, "store");
		this.examWriter = Objects.requireNonNull(examWriter, "examWriter");
	}

	/**
	 * Imports a source PDF into its canonical managed Exam directory.
	 *
	 * <p>
	 * The selected assignment is checked before attempting any file operation. An
	 * identical existing PDF may be reused in ordinary mode according to the
	 * existing PdfStore contract.
	 *
	 * @param sourcePath     external or already-managed source PDF
	 * @param subjectName    owning Subject name
	 * @param providerName   Exam provider name
	 * @param year           examination year
	 * @param assessmentName assessment name
	 * @return managed PDF path
	 * @throws IOException           if the source or managed copy is invalid
	 * @throws SQLException          if contributor selection cannot be read
	 * @throws IllegalStateException if a contributor assignment is active
	 */
	public Path importPdf(Path sourcePath, String subjectName, String providerName, int year, String assessmentName)
			throws IOException, SQLException {

		// File publication must not precede the contributor-mode check.
		examWriter.requireCoordinatorStructureEdit();
		return store.importExamPdf(sourcePath, subjectName, providerName, year, assessmentName);
	}
}
