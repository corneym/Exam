package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
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
	private final SqliteDatabase database;

	/**
	 * Creates the coordinator-side issuance service.
	 *
	 * @param database authoritative master database
	 * @param layout   authoritative managed data layout
	 * @throws NullPointerException if either argument is null
	 */
	public WorkAssignmentIssuanceService(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		Objects.requireNonNull(layout, "layout");
		exporter = new WorkAssignmentExporter(database, layout);
		ledger = new WorkAssignmentLedger(database);
	}

	/**
	 * Exports, validates and registers a complete Exam assignment.
	 *
	 * <p>
	 * An Exam with an outstanding assignment cannot be issued again. The early
	 * check avoids unnecessary archive creation. The SQLite assignment claim
	 * remains authoritative and also rejects races between concurrent issuance
	 * attempts.
	 *
	 * <p>
	 * If registration fails after export, the new archive is removed.
	 *
	 * @param request whole-Exam assignment request
	 * @return durable ACTIVE assignment
	 * @throws SQLException         if identity or ledger persistence fails
	 * @throws IOException          if another assignment is active or package
	 *                              creation, verification or registration fails
	 * @throws NullPointerException if request is null
	 */
	public WorkAssignmentLedger.Assignment issue(WorkAssignmentExporter.Request request)
			throws SQLException, IOException {
		Objects.requireNonNull(request, "request");
		UUID examPortableId = new SqlitePortableIdentityRepository(database)
				.requireByLocalId(EntityType.EXAM, request.exam().getId()).portableId();
		if (ledger.findActiveForExam(examPortableId).isPresent()) {
			throw new IOException("The selected Exam already has an active work assignment. "
					+ "Close or reconcile that assignment before " + "creating another work package.");
		}

		// Export performs the existing prerequisite checks,
		// snapshot selection, asset staging and archive validation.
		exporter.export(request);
		try {

			// Registration claims the Exam in SQLite. This is the
			// authoritative second check against concurrent issuance.
			return ledger.register(request.destination());
		} catch (SQLException | IOException | RuntimeException failure) {
			try {
				Files.deleteIfExists(request.destination().toAbsolutePath().normalize());
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}
}
