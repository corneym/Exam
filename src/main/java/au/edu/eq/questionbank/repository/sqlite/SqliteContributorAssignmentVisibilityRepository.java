package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Determines which installed contributor assignments remain available for work.
 *
 * <p>
 * Published return evidence takes precedence over local visibility. An
 * assignment with a published contribution is SUBMITTED. An unchanged
 * assignment deliberately hidden by its contributor is REMOVED. Otherwise, an
 * installed assignment is ACTIVE.
 *
 * <p>
 * This repository reads the lifecycle only. Removal and reactivation must be
 * performed by the later service that verifies the assignment's actual
 * saved-work delta against its immutable baseline.
 */
public final class SqliteContributorAssignmentVisibilityRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a visibility repository.
	 *
	 * @param database initialised contributor SQLite database
	 */
	public SqliteContributorAssignmentVisibilityRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Returns the current local state of an installed assignment.
	 *
	 * @param assignmentId immutable assignment UUID
	 * @return local state, or empty when the assignment is not installed
	 * @throws SQLException if the state cannot be read
	 */
	public Optional<State> find(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT CASE
						    WHEN EXISTS (
						        SELECT 1
						        FROM contributor_return_packages packet
						        WHERE packet.assignment_id = session.assignment_id
						    ) THEN 'SUBMITTED'
						    WHEN EXISTS (
						        SELECT 1
						        FROM contributor_assignment_hidden hidden
						        WHERE hidden.assignment_id = session.assignment_id
						    ) THEN 'REMOVED'
						    ELSE 'ACTIVE'
						END AS local_state
						FROM contributor_assignment_sessions session
						WHERE session.assignment_id = ?
						""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				State state = State.valueOf(result.getString("local_state"));
				if (result.next()) {
					throw new SQLException("Duplicate contributor assignment identity");
				}
				return Optional.of(state);
			}
		}
	}

	/**
	 * Finds installed assignments available for further capture.
	 *
	 * <p>
	 * Submitted or removed assignments remain in the database but are not returned
	 * by this query.
	 *
	 * @return immutable list of active assignment UUIDs
	 * @throws SQLException if installed assignments cannot be read
	 */
	public List<UUID> findActiveAssignmentIds() throws SQLException {
		List<UUID> identifiers = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT session.assignment_id
						FROM contributor_assignment_sessions session
						WHERE NOT EXISTS (
						    SELECT 1
						    FROM contributor_return_packages packet
						    WHERE packet.assignment_id = session.assignment_id
						)
						AND NOT EXISTS (
						    SELECT 1
						    FROM contributor_assignment_hidden hidden
						    WHERE hidden.assignment_id = session.assignment_id
						)
						ORDER BY session.installed_at, session.assignment_id
						""");
				ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				identifiers.add(UUID.fromString(result.getString("assignment_id")));
			}
		}
		return List.copyOf(identifiers);
	}

	/**
	 * Local contributor assignment disposition.
	 */
	public enum State {
		/** Installed and available for new capture. */
		ACTIVE,
		/** An unchanged assignment was removed from the active interface. */
		REMOVED,
		/** At least one contribution has been durably published. */
		SUBMITTED
	}
}
