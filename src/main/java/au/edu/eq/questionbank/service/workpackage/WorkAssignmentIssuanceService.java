package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Objects;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Coordinates the creation and registration of coordinator-issued whole-Exam
 * offline work assignments.
 *
 * <p>
 * An assignment is considered issued only after its validated archive and
 * immutable baseline have been registered in the authoritative coordinator
 * database.
 *
 * <p>
 * If registration fails, the service attempts to remove the newly exported
 * archive without modifying existing assignments.
 */
public class WorkAssignmentIssuanceService {

	private final WorkAssignmentExporter exporter;
	private final WorkAssignmentLedger ledger;

	/**
	 * Creates the coordinator-side issuance service.
	 *
	 * @param database authoritative master database
	 * @param layout   authoritative managed data layout
	 */
	public WorkAssignmentIssuanceService(SqliteDatabase database, ManagedDataLayout layout) {
		Objects.requireNonNull(database, "database");
		Objects.requireNonNull(layout, "layout");
		exporter = new WorkAssignmentExporter(database, layout);
		ledger = new WorkAssignmentLedger(database);
	}

	/**
	 * Exports, validates and registers a complete Exam assignment.
	 *
	 * <p>
	 * Registration is the issuance checkpoint. If registration fails after archive
	 * export, the newly published archive is removed and the original failure is
	 * propagated.
	 *
	 * @param request whole-Exam assignment request
	 * @return durable ACTIVE assignment
	 * @throws SQLException if ledger registration fails
	 * @throws IOException  if package creation or verification fails
	 */
	public WorkAssignmentLedger.Assignment issue(WorkAssignmentExporter.Request request)
			throws SQLException, IOException {
		Objects.requireNonNull(request, "request");

		// Export performs preflight, SQLite snapshot selection,
		// binary staging and complete archive validation.
		exporter.export(request);
		try {

			// Do not report issuance until the authoritative master
			// has retained its immutable baseline and lifecycle.
			return ledger.register(request.destination());
		} catch (SQLException | IOException | RuntimeException failure) {

			// The archive is our newly created output. A failed
			// registration must not leave an apparently issued copy.
			try {
				Files.deleteIfExists(request.destination().toAbsolutePath().normalize());
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}
}
