package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalPlanner.Asset;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalPlanner.Entity;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalPlanner.Plan;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalPlanner.Reason;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalPlanner.RetiredEntity;

/**
 * Releases the exclusive database corpus of one contributor assignment after
 * durable submission or verified unchanged removal.
 *
 * <p>
 * Planning is committed before deletion. The database release is then one
 * transaction whose temporary {@code DATABASE_RELEASING} state grants exact
 * deletion permission only to the recorded entity inventory. Interruption or
 * failure rolls the release back to durable {@code PLANNED}. Database success
 * reaches {@code DATABASE_RELEASED}; exact unreferenced managed files are then
 * released recoverably before the operation reaches {@code VERIFIED}.
 */
public final class ContributorAssignmentCorpusDisposalService {

	private static final int POLICY_VERSION = 1;
	private final SqliteDatabase database;
	private final FaultInjector faults;
	private final ManagedDataLayout layout;

	/**
	 * Creates a contributor-assignment corpus disposal service.
	 *
	 * @param database contributor's initialised SQLite database
	 * @param layout   contributor's managed-data layout
	 * @throws NullPointerException if either argument is null
	 */
	public ContributorAssignmentCorpusDisposalService(SqliteDatabase database, ManagedDataLayout layout) {
		this(database, layout, FaultInjector.NONE);
	}

	ContributorAssignmentCorpusDisposalService(SqliteDatabase database, ManagedDataLayout layout,
			FaultInjector faults) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
		this.faults = Objects.requireNonNull(faults, "faults");
	}

	/**
	 * Plans and atomically releases one submitted assignment's exclusive database
	 * graph.
	 *
	 * <p>
	 * Repeating this method after a planned interruption retries the exact plan.
	 * Repeating it after database release resumes managed-file cleanup. Repeating
	 * it after verification is an idempotent read. An unresolved publication,
	 * unsubmitted assignment, shared Exam, changed plan or unsafe managed asset
	 * fails closed without unverified deletion.
	 *
	 * @param assignmentId submitted assignment identity
	 * @return durable operation identity and current release state
	 * @throws SQLException          if database planning, verification or release
	 *                               fails
	 * @throws IOException           if publication recovery, managed path
	 *                               inspection or asset hashing fails
	 * @throws IllegalStateException if the assignment is not safely disposable
	 * @throws NullPointerException  if assignmentId is null
	 */
	public ReleaseResult releaseSubmitted(UUID assignmentId) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (ManagedAssetPublicationLock lock = ManagedAssetPublicationLock.acquire(layout.dataRoot())) {
			return releaseSubmitted(assignmentId, lock);
		}
	}

	ReleaseResult releaseSubmitted(UUID assignmentId, ManagedAssetPublicationLock lock)
			throws SQLException, IOException {
		return release(assignmentId, Reason.SUBMITTED, lock);
	}

	/**
	 * Releases a coordinator-cancelled or superseded assignment only when it was
	 * already removed locally and still exactly matches its immutable baseline.
	 *
	 * <p>
	 * This path records no terminal Contribution. It is intended for safe
	 * cancellation/reissue handling; saved or otherwise changed work fails closed.
	 *
	 * @param assignmentId removed assignment identity
	 * @return durable operation identity and current release state
	 * @throws SQLException          if database planning, verification or release
	 *                               fails
	 * @throws IOException           if managed publication recovery, snapshot
	 *                               inspection or asset hashing fails
	 * @throws IllegalStateException if the assignment is not removed and unchanged
	 * @throws NullPointerException  if assignmentId is null
	 */
	public ReleaseResult releaseRemovedUnchanged(UUID assignmentId) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (ManagedAssetPublicationLock lock = ManagedAssetPublicationLock.acquire(layout.dataRoot())) {
			return releaseRemovedUnchanged(assignmentId, lock);
		}
	}

	ReleaseResult releaseRemovedUnchanged(UUID assignmentId, ManagedAssetPublicationLock lock)
			throws SQLException, IOException {
		return release(assignmentId, Reason.REMOVED_UNCHANGED, lock);
	}

	private ReleaseResult release(UUID assignmentId, Reason reason, ManagedAssetPublicationLock lock)
			throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(lock, "lock");
		ApplicationConfig config = ApplicationConfig.fromDataRoot(layout.dataRoot());
		ManagedAssetPublicationRecovery.recoverPendingForDisposal(config, lock, assignmentId);
		lock.requireNoPendingPublications();
		Operation operation = ensurePlan(assignmentId, reason);
		if (!"PLANNED".equals(operation.state())) {
			return result(operation);
		}
		faults.reach(FaultPoint.AFTER_PLAN_COMMIT);
		release(operation);
		faults.reach(FaultPoint.AFTER_DATABASE_RELEASE_COMMIT);
		new ContributorAssignmentCorpusDisposalRecovery(config).recoverPending(lock);
		return result(ensurePlan(assignmentId, reason));
	}

	private Operation ensurePlan(UUID assignmentId, Reason reason) throws SQLException, IOException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				reserveWriter(connection);
				Operation existing = findOperation(connection, assignmentId);
				if (existing != null) {
					if (existing.reason() != reason) {
						throw new IllegalStateException("Assignment disposal reason does not match its durable plan");
					}
					if ("DATABASE_RELEASING".equals(existing.state())) {
						throw new SQLException("Corpus deletion authority escaped its transaction");
					}
					if ("PLANNED".equals(existing.state())) {
						Plan actual = new ContributorAssignmentCorpusDisposalPlanner(database, layout).plan(connection,
								assignmentId, reason);
						requireStoredPlan(connection, existing, actual);
					}
					connection.commit();
					return existing;
				}
				Plan plan = new ContributorAssignmentCorpusDisposalPlanner(database, layout).plan(connection,
						assignmentId, reason);
				Operation created = persistPlan(connection, plan);
				connection.commit();
				return created;
			} catch (SQLException | IOException | RuntimeException failure) {
				rollback(connection, failure);
				throw failure;
			}
		}
	}

	private ReleaseResult release(Operation operation) throws SQLException, IOException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				reserveWriter(connection);
				Operation current = findOperation(connection, operation.scopeId());
				if (current == null) {
					throw new SQLException("Persisted corpus disposal operation disappeared");
				}
				if (!"PLANNED".equals(current.state())) {
					connection.commit();
					return result(current);
				}
				Plan plan = new ContributorAssignmentCorpusDisposalPlanner(database, layout).plan(connection,
						operation.scopeId(), operation.reason());
				requireStoredPlan(connection, current, plan);
				try (Statement statement = connection.createStatement()) {
					statement.execute("PRAGMA defer_foreign_keys = ON");
				}
				setReleasing(connection, current.operationId());
				faults.reach(FaultPoint.AFTER_RELEASE_PERMISSION);
				UUID preservedSelection = clearSelection(connection, current.scopeId());
				deleteSessionEvidence(connection, current.scopeId());
				convertRetirements(connection, current.operationId());
				deleteGraph(connection, current.operationId());
				faults.reach(FaultPoint.AFTER_GRAPH_DELETE);
				restoreSelection(connection, preservedSelection);
				requireExactEvictions(connection, current.operationId());
				markDatabaseReleased(connection, current.operationId());
				requireForeignKeyIntegrity(connection);
				faults.reach(FaultPoint.BEFORE_RELEASE_COMMIT);
				connection.commit();
				return new ReleaseResult(current.operationId(), current.scopeId(), State.DATABASE_RELEASED,
						plan.entities().size() + plan.retiredEntities().size(), plan.assets().size());
			} catch (SQLException | IOException | RuntimeException failure) {
				rollback(connection, failure);
				throw failure;
			}
		}
	}

	private Operation persistPlan(Connection connection, Plan plan) throws SQLException {
		UUID operationId = UUID.randomUUID();
		String now = Instant.now().toString();
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO corpus_disposal_operations (
				    operation_id, policy, policy_version, scope_id, reason,
				    terminal_return_package_id, assignment_package_id,
				    assignment_archive_sha256, plan_sha256,
				    state, planned_at, updated_at
				)
				VALUES (?, 'CONTRIBUTOR_ASSIGNMENT', ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?)
				""")) {
			statement.setString(1, operationId.toString());
			statement.setInt(2, POLICY_VERSION);
			statement.setString(3, plan.assignmentId().toString());
			statement.setString(4, plan.reason().name());
			statement.setString(5, plan.returnPackageId() == null ? null : plan.returnPackageId().toString());
			statement.setString(6, plan.assignmentPackageId().toString());
			statement.setString(7, plan.assignmentArchiveSha256());
			statement.setString(8, plan.sha256());
			statement.setString(9, now);
			statement.setString(10, now);
			requireOne(statement, "Corpus disposal operation was not created");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO corpus_disposal_entities (
				    operation_id, entity_type, local_id, portable_id,
				    revision_id, authority_installation_id, reattachable
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (Entity entity : plan.entities()) {
				statement.setString(1, operationId.toString());
				statement.setString(2, entity.type().name());
				statement.setLong(3, entity.localId());
				statement.setString(4, entity.portableId().toString());
				statement.setString(5, entity.revisionId().toString());
				statement.setString(6, entity.authorityInstallationId().toString());
				statement.setInt(7, entity.reattachable() ? 1 : 0);
				requireOne(statement, "Corpus disposal entity was not inventoried");
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO corpus_disposal_assets (
				    operation_id, relative_path, content_sha256, asset_kind
				)
				VALUES (?, ?, ?, ?)
				""")) {
			for (Asset asset : plan.assets()) {
				statement.setString(1, operationId.toString());
				statement.setString(2, asset.relativePath());
				statement.setString(3, asset.sha256());
				statement.setString(4, asset.kind());
				requireOne(statement, "Corpus disposal asset was not inventoried");
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO corpus_disposal_retired_entities (
				    operation_id, portable_id, entity_type, former_local_id,
				    latest_revision_id, authority_installation_id
				)
				VALUES (?, ?, ?, ?, ?, ?)
				""")) {
			for (RetiredEntity entity : plan.retiredEntities()) {
				statement.setString(1, operationId.toString());
				statement.setString(2, entity.portableId().toString());
				statement.setString(3, entity.type().name());
				statement.setLong(4, entity.formerLocalId());
				statement.setString(5, entity.latestRevisionId().toString());
				statement.setString(6, entity.authorityInstallationId().toString());
				requireOne(statement, "Corpus disposal retired entity was not inventoried");
			}
		}
		return new Operation(operationId, plan.assignmentId(), plan.reason(), plan.returnPackageId(),
				plan.assignmentPackageId(), plan.assignmentArchiveSha256(), plan.sha256(), "PLANNED",
				plan.entities().size() + plan.retiredEntities().size(), plan.assets().size());
	}

	private void requireStoredPlan(Connection connection, Operation operation, Plan actual) throws SQLException {
		if (operation.reason() != actual.reason()
				|| !Objects.equals(operation.returnPackageId(), actual.returnPackageId())
				|| !operation.assignmentPackageId().equals(actual.assignmentPackageId())
				|| !operation.assignmentArchiveSha256().equals(actual.assignmentArchiveSha256())
				|| !operation.planSha256().equals(actual.sha256())
				|| operation.entityCount() != actual.entities().size() + actual.retiredEntities().size()
				|| operation.assetCount() != actual.assets().size()) {
			throw new IllegalStateException("Corpus disposal plan is stale; no deletion was performed");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT entity_type, local_id, portable_id, revision_id,
				       authority_installation_id, reattachable
				FROM corpus_disposal_entities
				WHERE operation_id = ?
				ORDER BY entity_type, local_id
				""")) {
			statement.setString(1, operation.operationId().toString());
			try (ResultSet rows = statement.executeQuery()) {
				for (Entity entity : actual.entities()) {
					if (!rows.next() || !entity.type().name().equals(rows.getString("entity_type"))
							|| entity.localId() != rows.getLong("local_id")
							|| !entity.portableId().toString().equals(rows.getString("portable_id"))
							|| !entity.revisionId().toString().equals(rows.getString("revision_id"))
							|| !entity.authorityInstallationId().toString()
									.equals(rows.getString("authority_installation_id"))
							|| (entity.reattachable() ? 1 : 0) != rows.getInt("reattachable")) {
						throw new IllegalStateException("Corpus disposal entity inventory changed");
					}
				}
				if (rows.next()) {
					throw new IllegalStateException("Corpus disposal entity inventory has extra rows");
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT entity_type, former_local_id, portable_id,
				       latest_revision_id, authority_installation_id
				FROM corpus_disposal_retired_entities
				WHERE operation_id = ?
				ORDER BY entity_type, former_local_id
				""")) {
			statement.setString(1, operation.operationId().toString());
			try (ResultSet rows = statement.executeQuery()) {
				for (RetiredEntity entity : actual.retiredEntities()) {
					if (!rows.next() || !entity.type().name().equals(rows.getString("entity_type"))
							|| entity.formerLocalId() != rows.getLong("former_local_id")
							|| !entity.portableId().toString().equals(rows.getString("portable_id"))
							|| !entity.latestRevisionId().toString().equals(rows.getString("latest_revision_id"))
							|| !entity.authorityInstallationId().toString()
									.equals(rows.getString("authority_installation_id"))) {
						throw new IllegalStateException("Corpus disposal retired-entity inventory changed");
					}
				}
				if (rows.next()) {
					throw new IllegalStateException("Corpus disposal retired-entity inventory has extra rows");
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT relative_path, content_sha256, asset_kind
				FROM corpus_disposal_assets
				WHERE operation_id = ?
				ORDER BY relative_path
				""")) {
			statement.setString(1, operation.operationId().toString());
			try (ResultSet rows = statement.executeQuery()) {
				for (Asset asset : actual.assets()) {
					if (!rows.next() || !asset.relativePath().equals(rows.getString("relative_path"))
							|| !asset.sha256().equals(rows.getString("content_sha256"))
							|| !asset.kind().equals(rows.getString("asset_kind"))) {
						throw new IllegalStateException("Corpus disposal asset inventory changed");
					}
				}
				if (rows.next()) {
					throw new IllegalStateException("Corpus disposal asset inventory has extra rows");
				}
			}
		}
	}

	private Operation findOperation(Connection connection, UUID assignmentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT operation.operation_id,
				       operation.reason,
				       operation.terminal_return_package_id,
				       operation.assignment_package_id,
				       operation.assignment_archive_sha256,
				       operation.plan_sha256,
				       operation.state,
				       (SELECT COUNT(*) FROM corpus_disposal_entities entity
				        WHERE entity.operation_id = operation.operation_id)
				       + (SELECT COUNT(*) FROM corpus_disposal_retired_entities retired
				          WHERE retired.operation_id = operation.operation_id) AS entity_count,
				       (SELECT COUNT(*) FROM corpus_disposal_assets asset
				        WHERE asset.operation_id = operation.operation_id) AS asset_count
				FROM corpus_disposal_operations operation
				WHERE operation.policy = 'CONTRIBUTOR_ASSIGNMENT'
				  AND operation.policy_version = ?
				  AND operation.scope_id = ?
				""")) {
			statement.setInt(1, POLICY_VERSION);
			statement.setString(2, assignmentId.toString());
			try (ResultSet row = statement.executeQuery()) {
				if (!row.next()) {
					return null;
				}
				String terminalReturnPackageId = row.getString("terminal_return_package_id");
				Operation operation = new Operation(UUID.fromString(row.getString("operation_id")), assignmentId,
						Reason.valueOf(row.getString("reason")),
						terminalReturnPackageId == null ? null : UUID.fromString(terminalReturnPackageId),
						UUID.fromString(row.getString("assignment_package_id")),
						row.getString("assignment_archive_sha256"), row.getString("plan_sha256"),
						row.getString("state"), row.getInt("entity_count"), row.getInt("asset_count"));
				if (row.next()) {
					throw new SQLException("Duplicate corpus disposal operation");
				}
				return operation;
			}
		}
	}

	private void convertRetirements(Connection connection, UUID operationId) throws SQLException {
		int expected;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT COUNT(*)
				FROM corpus_disposal_retired_entities
				WHERE operation_id = ?
				""")) {
			statement.setString(1, operationId.toString());
			try (ResultSet row = statement.executeQuery()) {
				if (!row.next()) {
					throw new SQLException("Retired-entity inventory count is missing");
				}
				expected = row.getInt(1);
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO portable_entity_cache_evictions (
				    operation_id, portable_id, entity_type, former_local_id,
				    latest_revision_id, authority_installation_id,
				    reattachable, evicted_at
				)
				SELECT inventory.operation_id,
				       inventory.portable_id,
				       inventory.entity_type,
				       inventory.former_local_id,
				       inventory.latest_revision_id,
				       inventory.authority_installation_id,
				       1,
				       ?
				FROM corpus_disposal_retired_entities inventory
				JOIN portable_entity_retirements retired
				  ON retired.entity_type = inventory.entity_type
				 AND retired.local_id = inventory.former_local_id
				 AND retired.portable_id = inventory.portable_id
				 AND retired.revision_id = inventory.latest_revision_id
				WHERE inventory.operation_id = ?
				""")) {
			statement.setString(1, Instant.now().toString());
			statement.setString(2, operationId.toString());
			if (SqliteDirectChanges.executeUpdate(statement) != expected) {
				throw new SQLException("Corpus disposal did not convert the exact retired-entity inventory");
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM portable_entity_retirements
				WHERE EXISTS (
				    SELECT 1
				    FROM corpus_disposal_retired_entities inventory
				    WHERE inventory.operation_id = ?
				      AND inventory.entity_type = portable_entity_retirements.entity_type
				      AND inventory.former_local_id = portable_entity_retirements.local_id
				      AND inventory.portable_id = portable_entity_retirements.portable_id
				      AND inventory.latest_revision_id = portable_entity_retirements.revision_id
				)
				""")) {
			statement.setString(1, operationId.toString());
			if (SqliteDirectChanges.executeUpdate(statement) != expected) {
				throw new SQLException("Corpus disposal did not release the exact retired-entity evidence");
			}
		}
	}

	private void setReleasing(Connection connection, UUID operationId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE corpus_disposal_operations
				SET state = 'DATABASE_RELEASING', updated_at = ?
				WHERE operation_id = ? AND state = 'PLANNED'
				""")) {
			statement.setString(1, Instant.now().toString());
			statement.setString(2, operationId.toString());
			requireOne(statement, "Corpus disposal release permission was not established");
		}
	}

	private UUID clearSelection(Connection connection, UUID assignmentId) throws SQLException {
		UUID previous = null;
		try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("""
				SELECT assignment_id
				FROM contributor_assignment_selection
				WHERE singleton_id = 1
				""")) {
			if (!row.next()) {
				throw new SQLException("Contributor assignment selection singleton is missing");
			}
			String value = row.getString(1);
			if (value != null && !value.equals(assignmentId.toString())) {
				previous = UUID.fromString(value);
			}
			if (row.next()) {
				throw new SQLException("Duplicate contributor assignment selection singleton");
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE contributor_assignment_selection
				SET assignment_id = NULL, updated_at = ?
				WHERE singleton_id = 1
				""")) {
			statement.setString(1, Instant.now().toString());
			requireOne(statement, "Contributor assignment selection was not cleared");
		}
		return previous;
	}

	private void restoreSelection(Connection connection, UUID assignmentId) throws SQLException {
		if (assignmentId == null) {
			return;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE contributor_assignment_selection
				SET assignment_id = ?, updated_at = ?
				WHERE singleton_id = 1
				  AND EXISTS (
				      SELECT 1 FROM contributor_assignment_sessions
				      WHERE assignment_id = ?
				  )
				""")) {
			statement.setString(1, assignmentId.toString());
			statement.setString(2, Instant.now().toString());
			statement.setString(3, assignmentId.toString());
			requireOne(statement, "Previously selected assignment could not be restored");
		}
	}

	private void deleteSessionEvidence(Connection connection, UUID assignmentId) throws SQLException {
		execute(connection, "DELETE FROM contributor_assignment_hidden WHERE assignment_id = ?", assignmentId);
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM contributor_assignment_sessions
				WHERE assignment_id = ?
				""")) {
			statement.setString(1, assignmentId.toString());
			requireOne(statement, "Submitted assignment session was not released");
		}
	}

	private void deleteGraph(Connection connection, UUID operationId) throws SQLException {
		deleteChildren(connection, operationId, "booklet_applicability_reviews", "booklet_id", EntityType.EXAM_BOOKLET);
		deleteChildren(connection, operationId, "answer_regions", "answer_id", EntityType.ANSWER);
		deleteChildren(connection, operationId, "question_output_exclusions", "question_id", EntityType.QUESTION);
		deleteChildren(connection, operationId, "question_content_parts", "question_id", EntityType.QUESTION);
		deleteChildren(connection, operationId, "question_regions", "question_id", EntityType.QUESTION);
		deleteChildren(connection, operationId, "shared_question_context_content_parts", "shared_context_id",
				EntityType.SHARED_QUESTION_CONTEXT);
		deleteChildren(connection, operationId, "shared_question_context_images", "shared_context_id",
				EntityType.SHARED_QUESTION_CONTEXT);
		deleteChildren(connection, operationId, "shared_question_context_regions", "shared_context_id",
				EntityType.SHARED_QUESTION_CONTEXT);
		deleteEntities(connection, operationId, EntityType.QUESTION_IMAGE, "question_images");
		deleteEntities(connection, operationId, EntityType.ANSWER, "answers");
		deleteEntities(connection, operationId, EntityType.QUESTION, "questions");
		deleteEntities(connection, operationId, EntityType.SHARED_QUESTION_CONTEXT, "shared_question_contexts");
		deleteEntities(connection, operationId, EntityType.SOURCE_QUESTION, "source_questions");
		deleteEntities(connection, operationId, EntityType.EXAM_BOOKLET, "exam_booklets");
		deleteEntities(connection, operationId, EntityType.ANSWER_FILE, "answer_files");
		deleteEntities(connection, operationId, EntityType.EXAM, "exams");
		deleteEntities(connection, operationId, EntityType.SOURCE_DOCUMENT, "source_documents");
		deleteCurriculumReviews(connection, operationId);
		deleteEntities(connection, operationId, EntityType.CURRICULUM_MAPPING, "curriculum_mappings");
		deleteEntities(connection, operationId, EntityType.CURRICULUM_NODE, "curriculum_nodes");
		deleteEntities(connection, operationId, EntityType.SYLLABUS_VERSION, "syllabus_versions");
	}

	private void deleteCurriculumReviews(Connection connection, UUID operationId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM curriculum_mapping_reviews
				WHERE source_node_id IN (
				    SELECT local_id FROM corpus_disposal_entities
				    WHERE operation_id = ? AND entity_type = 'CURRICULUM_NODE'
				)
				OR target_syllabus_version_id IN (
				    SELECT local_id FROM corpus_disposal_entities
				    WHERE operation_id = ? AND entity_type = 'SYLLABUS_VERSION'
				)
				""")) {
			statement.setString(1, operationId.toString());
			statement.setString(2, operationId.toString());
			SqliteDirectChanges.executeUpdate(statement);
		}
	}

	private void deleteChildren(Connection connection, UUID operationId, String table, String ownerColumn,
			EntityType ownerType) throws SQLException {
		String sql = "DELETE FROM " + table + " WHERE " + ownerColumn + " IN ("
				+ "SELECT local_id FROM corpus_disposal_entities WHERE operation_id = ? AND entity_type = ?)";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, operationId.toString());
			statement.setString(2, ownerType.name());
			SqliteDirectChanges.executeUpdate(statement);
		}
	}

	private void deleteEntities(Connection connection, UUID operationId, EntityType type, String table)
			throws SQLException {
		String sql = "DELETE FROM " + table + " WHERE id IN ("
				+ "SELECT local_id FROM corpus_disposal_entities WHERE operation_id = ? AND entity_type = ?)";
		int expected;
		try (PreparedStatement count = connection.prepareStatement("""
				SELECT COUNT(*)
				FROM corpus_disposal_entities
				WHERE operation_id = ? AND entity_type = ?
				""")) {
			count.setString(1, operationId.toString());
			count.setString(2, type.name());
			try (ResultSet row = count.executeQuery()) {
				row.next();
				expected = row.getInt(1);
			}
		}
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, operationId.toString());
			statement.setString(2, type.name());
			int actual = SqliteDirectChanges.executeUpdate(statement);
			if (actual != expected) {
				throw new SQLException("Corpus disposal did not delete exact " + type + " inventory");
			}
		}
	}

	private void requireExactEvictions(Connection connection, UUID operationId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				WITH inventory AS (
				    SELECT operation_id, entity_type, local_id AS former_local_id,
				           portable_id, revision_id AS latest_revision_id,
				           authority_installation_id, reattachable
				    FROM corpus_disposal_entities
				    UNION ALL
				    SELECT operation_id, entity_type, former_local_id,
				           portable_id, latest_revision_id,
				           authority_installation_id, 1 AS reattachable
				    FROM corpus_disposal_retired_entities
				)
				SELECT entity.entity_type, entity.former_local_id, entity.portable_id,
				       entity.latest_revision_id, entity.authority_installation_id, entity.reattachable,
				       eviction.entity_type, eviction.former_local_id,
				       eviction.latest_revision_id, eviction.authority_installation_id,
				       eviction.reattachable, history.revision_id
				FROM inventory entity
				LEFT JOIN portable_entity_cache_evictions eviction
				  ON eviction.operation_id = entity.operation_id
				 AND eviction.portable_id = entity.portable_id
				LEFT JOIN portable_revision_history history
				  ON history.revision_id = eviction.latest_revision_id
				 AND history.portable_id = entity.portable_id
				 AND history.entity_type = entity.entity_type
				WHERE entity.operation_id = ?
				""")) {
			statement.setString(1, operationId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					if (rows.getString(7) == null || !rows.getString(1).equals(rows.getString(7))
							|| rows.getLong(2) != rows.getLong(8) || !rows.getString(5).equals(rows.getString(10))
							|| rows.getInt(6) != rows.getInt(11) || rows.getString(12) == null) {
						throw new SQLException("Corpus disposal cache-eviction evidence is incomplete");
					}
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				WITH inventory AS (
				    SELECT operation_id, portable_id
				    FROM corpus_disposal_entities
				    UNION ALL
				    SELECT operation_id, portable_id
				    FROM corpus_disposal_retired_entities
				)
				SELECT 1
				FROM portable_entity_cache_evictions eviction
				LEFT JOIN inventory entity
				  ON entity.operation_id = eviction.operation_id
				 AND entity.portable_id = eviction.portable_id
				WHERE eviction.operation_id = ?
				  AND entity.portable_id IS NULL
				LIMIT 1
				""")) {
			statement.setString(1, operationId.toString());
			try (ResultSet row = statement.executeQuery()) {
				if (row.next()) {
					throw new SQLException("Corpus disposal recorded an uninventoried cache eviction");
				}
			}
		}
	}

	private void requireForeignKeyIntegrity(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (rows.next()) {
				throw new SQLException(
						"Corpus disposal would leave a broken foreign-key reference: " + rows.getString(1));
			}
		}
	}

	private void markDatabaseReleased(Connection connection, UUID operationId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE corpus_disposal_operations
				SET state = 'DATABASE_RELEASED',
				    updated_at = ?,
				    database_released_at = ?
				WHERE operation_id = ? AND state = 'DATABASE_RELEASING'
				""")) {
			String now = Instant.now().toString();
			statement.setString(1, now);
			statement.setString(2, now);
			statement.setString(3, operationId.toString());
			requireOne(statement, "Corpus disposal did not reach database-released state");
		}
	}

	private void reserveWriter(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			if (statement.executeUpdate("UPDATE installation_identity SET installation_id = installation_id "
					+ "WHERE singleton_id = 1") != 1) {
				throw new SQLException("Cannot reserve authoritative corpus state");
			}
		}
	}

	private void execute(Connection connection, String sql, UUID value) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, value.toString());
			SqliteDirectChanges.executeUpdate(statement);
		}
	}

	private void requireOne(PreparedStatement statement, String message) throws SQLException {
		if (SqliteDirectChanges.executeUpdate(statement) != 1) {
			throw new SQLException(message);
		}
	}

	private ReleaseResult result(Operation operation) {
		State state;
		try {
			state = State.valueOf(operation.state());
		} catch (IllegalArgumentException failure) {
			throw new IllegalStateException("Unknown durable corpus disposal state", failure);
		}
		return new ReleaseResult(operation.operationId(), operation.scopeId(), state, operation.entityCount(),
				operation.assetCount());
	}

	private void rollback(Connection connection, Exception failure) {
		try {
			connection.rollback();
		} catch (SQLException rollbackFailure) {
			failure.addSuppressed(rollbackFailure);
		}
	}

	/** Durable corpus-disposal states visible outside the release transaction. */
	public enum State {
		/** Exact entity and asset inventory is durable; no corpus was deleted. */
		PLANNED,
		/** Exclusive database corpus is absent; managed-asset release remains. */
		DATABASE_RELEASED,
		/** Database and managed-asset release were both verified. */
		VERIFIED
	}

	/**
	 * Durable result of one assignment release request.
	 *
	 * @param operationId  exact disposal operation identity
	 * @param assignmentId released assignment identity
	 * @param state        durable operation state
	 * @param entityCount  inventoried database entity count
	 * @param assetCount   inventoried managed-asset candidate count
	 */
	public record ReleaseResult(UUID operationId, UUID assignmentId, State state, int entityCount, int assetCount) {

		/** Validates durable release result evidence. */
		public ReleaseResult {
			Objects.requireNonNull(operationId, "operationId");
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(state, "state");
			if (entityCount < 0 || assetCount < 0) {
				throw new IllegalArgumentException("Release inventory counts cannot be negative");
			}
		}
	}

	enum FaultPoint {
		AFTER_DATABASE_RELEASE_COMMIT, AFTER_PLAN_COMMIT, AFTER_RELEASE_PERMISSION, AFTER_GRAPH_DELETE,
		BEFORE_RELEASE_COMMIT
	}

	@FunctionalInterface
	interface FaultInjector {

		FaultInjector NONE = point -> {
		};

		void reach(FaultPoint point) throws IOException;
	}

	private record Operation(UUID operationId, UUID scopeId, Reason reason, UUID returnPackageId,
			UUID assignmentPackageId, String assignmentArchiveSha256, String planSha256, String state, int entityCount,
			int assetCount) {
	}
}
