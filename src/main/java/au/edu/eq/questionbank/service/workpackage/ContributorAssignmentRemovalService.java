
package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository.State;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Removes unchanged assignments from the contributor's active interface.
 *
 * <p>
 * Removal never deletes imported domain records, assets, baseline evidence,
 * return history or portable identities. This makes safe reinstallation of the
 * exact original package possible.
 *
 * <p>
 * A non-empty contribution must instead be submitted to the coordinator.
 */
public final class ContributorAssignmentRemovalService {

	private final SqliteDatabase database;
	private final SqliteContributorAssignmentVisibilityRepository visibility;

	/**
	 * Creates a removal service for a contributor installation.
	 *
	 * @param database initialised contributor database
	 */
	public ContributorAssignmentRemovalService(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
		visibility = new SqliteContributorAssignmentVisibilityRepository(database);
	}

	/**
	 * Makes a previously removed, still-unchanged assignment available again.
	 *
	 * <p>
	 * The caller must first validate that the reimported original package matches
	 * the installed assignment's immutable identity and archive digest.
	 *
	 * @param assignmentId original installed assignment UUID
	 * @throws SQLException if persistence fails
	 * @throws IOException  if the installed work cannot be validated
	 */
	public void reactivate(UUID assignmentId) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");

		if (visibility.find(assignmentId).orElseThrow(() -> new IllegalArgumentException(
				"Unknown contributor assignment: " + assignmentId)) != State.REMOVED) {
			throw new IllegalStateException("Only a previously removed assignment can be reactivated.");
		}

		requireNoSavedChanges(assignmentId);

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						DELETE FROM contributor_assignment_hidden
						WHERE assignment_id = ?
						  AND NOT EXISTS (
						      SELECT 1
						      FROM contributor_return_packages packet
						      WHERE packet.assignment_id =
						            contributor_assignment_hidden.assignment_id
						  )
						""")) {
			statement.setString(1, assignmentId.toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("The removed assignment could not be reactivated.");
			}
		}
	}

	/**
	 * Removes an unchanged, not-yet-submitted assignment from the active list.
	 *
	 * <p>
	 * All domain data and immutable assignment evidence are retained. The caller
	 * must resolve any unsaved UI capture before invoking this method.
	 *
	 * @param assignmentId installed assignment UUID
	 * @throws SQLException          if persistence or assignment lookup fails
	 * @throws IOException           if the consistent snapshot cannot be inspected
	 * @throws IllegalStateException if work has been saved or submitted
	 */
	public void removeUnchanged(UUID assignmentId) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");

		if (visibility.find(assignmentId).orElseThrow(() -> new IllegalArgumentException(
				"Unknown contributor assignment: " + assignmentId)) != State.ACTIVE) {
			throw new IllegalStateException("Only an active, unsubmitted assignment may be removed.");
		}

		requireNoSavedChanges(assignmentId);

		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			try {
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO contributor_assignment_hidden (
						    assignment_id, removed_at
						)
						SELECT session.assignment_id, ?
						FROM contributor_assignment_sessions session
						WHERE session.assignment_id = ?
						  AND NOT EXISTS (
						      SELECT 1
						      FROM contributor_return_packages packet
						      WHERE packet.assignment_id = session.assignment_id
						  )
						  AND NOT EXISTS (
						      SELECT 1
						      FROM contributor_assignment_hidden hidden
						      WHERE hidden.assignment_id = session.assignment_id
						  )
						""")) {
					insert.setString(1, Instant.now().toString());
					insert.setString(2, assignmentId.toString());

					if (insert.executeUpdate() != 1) {
						throw new IllegalStateException("The assignment is no longer eligible for removal.");
					}
				}

				try (PreparedStatement clear = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = NULL,
						    updated_at = ?
						WHERE singleton_id = 1
						  AND assignment_id = ?
						""")) {
					clear.setString(1, Instant.now().toString());
					clear.setString(2, assignmentId.toString());
					clear.executeUpdate();
				}

				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	private void requireNoSavedChanges(UUID assignmentId) throws SQLException, IOException {
		Path directory = Files.createTempDirectory("examwork-assignment-removal-");
		Path snapshotPath = directory.resolve("contributor-snapshot.db");

		try {
			database.createConsistentSnapshot(snapshotPath);
			var snapshot = new ContributionSnapshotReader(snapshotPath).read(assignmentId);

			if (!snapshot.delta().candidates().isEmpty()) {
				throw new IllegalStateException("This assignment contains saved work and cannot be removed. "
						+ "Export the contribution and send it to the coordinator.");
			}
		} catch (PackageFormatException failure) {
			throw new IllegalStateException(
					"The assigned records differ from the original work package. "
							+ "The assignment cannot be removed until its work " + "has been resolved and submitted.",
					failure);
		} finally {
			try {
				Files.deleteIfExists(snapshotPath);
			} finally {
				Files.deleteIfExists(directory);
			}
		}
	}
}
