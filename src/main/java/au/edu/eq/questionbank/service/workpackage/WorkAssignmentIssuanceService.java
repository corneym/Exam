package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedAssetPublicationLock;
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
	private final ManagedDataLayout layout;

	/**
	 * Creates the coordinator-side issuance service.
	 *
	 * @param database authoritative master database
	 * @param layout   authoritative managed data layout
	 * @throws NullPointerException if either argument is null
	 */
	public WorkAssignmentIssuanceService(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
		exporter = new WorkAssignmentExporter(database, layout);
		ledger = new WorkAssignmentLedger(database);
	}

	/**
	 * Exports, validates and registers a whole-Exam offline assignment.
	 *
	 * <p>
	 * The shared managed-asset publication lock prevents cooperating PDF
	 * replacement operations from interleaving with the assignment's source-file
	 * snapshot and registration. The database ledger remains the authority for
	 * rejecting a second active assignment.
	 *
	 * @param request validated whole-Exam assignment request
	 * @return newly registered ACTIVE assignment
	 * @throws SQLException         if persistence or portable identity lookup fails
	 * @throws IOException          if a publication is pending, another assignment
	 *                              is active, or export/registration fails
	 * @throws NullPointerException if request is null
	 */
	public WorkAssignmentLedger.Assignment issue(WorkAssignmentExporter.Request request)
			throws SQLException, IOException {
		Objects.requireNonNull(request, "request");
		try (var publicationLock = ManagedAssetPublicationLock.acquire(layout.dataRoot())) {
			publicationLock.requireNoPendingPublications();
			UUID examPortableId = new SqlitePortableIdentityRepository(database)
					.requireByLocalId(EntityType.EXAM, request.exam().getId()).portableId();
			if (ledger.findActiveForExam(examPortableId).isPresent()) {
				throw new IOException("The selected Exam already has an active work assignment. "
						+ "Close or reconcile that assignment before " + "creating another work package.");
			}
			exporter.export(request);
			try {
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
}
