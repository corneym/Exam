package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite persistence for reusable shared question context and its ordered
 * source regions.
 */
public final class SqliteSharedQuestionContextRepository implements SharedQuestionContextRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a shared-context repository using the supplied SQLite database.
	 *
	 * @param database the database that owns the shared-context rows
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteSharedQuestionContextRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws NullPointerException  if {@code booklet} is {@code null}
	 * @throws IllegalStateException if reconstruction fails
	 */
	@Override
	public List<SharedQuestionContext> findByBooklet(ExamBooklet booklet) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		List<ContextRow> rows = new ArrayList<>();
		List<SharedQuestionContext> contexts = new ArrayList<>();
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT id, context_label
					FROM shared_question_contexts
					WHERE booklet_id = ?
					ORDER BY id
					""")) {
				statement.setLong(1, booklet.getId());
				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						rows.add(new ContextRow(result.getLong("id"), result.getString("context_label")));
					}
				}
			}
			for (ContextRow row : rows) {
				List<SharedQuestionContextRegion> regions = findRegions(connection, row.id());
				contexts.add(new SharedQuestionContext(row.id(), booklet, row.label(), regions));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read shared question contexts", e);
		}
		return List.copyOf(contexts);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws NullPointerException     if the booklet, region list or a region is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if the label is blank or no regions are
	 *                                  supplied
	 * @throws IllegalStateException    if the context and regions cannot be saved
	 *                                  atomically
	 */
	@Override
	public SharedQuestionContext save(ExamBooklet booklet, String label, List<SharedQuestionContextRegion> regions) {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SharedQuestionContext context = save(connection, booklet, label, regions);
				connection.commit();
				return context;
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save shared question context", e);
		}
	}

	// Within the caller transaction, retain referenced contexts or remove their
	// regions and row together.
	boolean deleteIfUnreferenced(Connection connection, long contextId, long bookletId) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM questions
				WHERE shared_context_id = ?
				LIMIT 1
				""")) {
			statement.setLong(1, contextId);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					return false;
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM shared_question_context_regions
				WHERE shared_context_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.executeUpdate();
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM shared_question_contexts
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.setLong(2, bookletId);
			return statement.executeUpdate() == 1;
		}
	}

	Optional<SharedQuestionContext> findById(Connection connection, ExamBooklet booklet, long contextId)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT context_label
				FROM shared_question_contexts
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.setLong(2, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(new SharedQuestionContext(contextId, booklet, result.getString("context_label"),
						findRegions(connection, contextId)));
			}
		}
	}

	SharedQuestionContext save(Connection connection, ExamBooklet booklet, String label,
			List<SharedQuestionContextRegion> regions) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		for (SharedQuestionContextRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
		}
		long contextId = insertContext(connection, booklet, label);
		insertRegions(connection, contextId, regions);
		return new SharedQuestionContext(contextId, booklet, label, regions);
	}

	private List<SharedQuestionContextRegion> findRegions(Connection connection, long contextId) throws SQLException {
		List<SharedQuestionContextRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    page_number,
				    x,
				    y,
				    width,
				    height
				FROM shared_question_context_regions
				WHERE shared_context_id = ?
				ORDER BY region_order
				""")) {
			statement.setLong(1, contextId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					regions.add(new SharedQuestionContextRegion(result.getInt("page_number"), result.getDouble("x"),
							result.getDouble("y"), result.getDouble("width"), result.getDouble("height")));
				}
			}
		}
		return regions;
	}

	private long insertContext(Connection connection, ExamBooklet booklet, String label) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO shared_question_contexts
				    (booklet_id, context_label)
				VALUES (?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setString(2, label);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Shared question context insert did not return an id");
				}
				return result.getLong("id");
			}
		}
	}

	private void insertRegions(Connection connection, long contextId, List<SharedQuestionContextRegion> regions)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO shared_question_context_regions
				    (shared_context_id,
				     region_order,
				     page_number,
				     x,
				     y,
				     width,
				     height)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (int i = 0; i < regions.size(); i++) {
				SharedQuestionContextRegion region = regions.get(i);
				statement.setLong(1, contextId);
				statement.setInt(2, i);
				statement.setInt(3, region.pageNumber());
				statement.setDouble(4, region.x());
				statement.setDouble(5, region.y());
				statement.setDouble(6, region.width());
				statement.setDouble(7, region.height());
				statement.executeUpdate();
			}
		}
	}

	private record ContextRow(long id, String label) {
	}
}
