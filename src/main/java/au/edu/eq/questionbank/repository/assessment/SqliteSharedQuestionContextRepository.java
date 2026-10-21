package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.SharedContextContentPart;
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
				contexts.add(SharedQuestionContext.fromContentParts(row.id(), booklet, row.label(),
						findContentParts(connection, row.id())));
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not read shared question contexts", exception);
		}
		return List.copyOf(contexts);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The context row is updated in place and its ordered region set is replaced in
	 * the same transaction. Preserving the context identifier means existing
	 * Question foreign-key relationships remain unchanged.
	 * </p>
	 */
	@Override
	public SharedQuestionContext replace(SharedQuestionContext context, String label,
			List<SharedQuestionContextRegion> regions) {
		if (context == null) {
			throw new NullPointerException("context");
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
		ExamBooklet booklet = context.getBooklet();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Retain context identity so existing Question relationships
				// continue referencing this context after correction.
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE shared_question_contexts
						SET context_label = ?
						WHERE id = ?
						  AND booklet_id = ?
						""")) {
					statement.setString(1, label);
					statement.setLong(2, context.getId());
					statement.setLong(3, booklet.getId());
					if (statement.executeUpdate() != 1) {
						throw new IllegalArgumentException("Shared context does not exist for booklet");
					}
				}

				// Remove references before deleting their source records.
				// All changes are rolled back together if an insert fails.
				deleteContentParts(connection, context.getId());
				try (PreparedStatement statement = connection.prepareStatement("""
						DELETE FROM shared_question_context_images
						WHERE shared_context_id = ?
						""")) {
					statement.setLong(1, context.getId());
					statement.executeUpdate();
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						DELETE FROM shared_question_context_regions
						WHERE shared_context_id = ?
						""")) {
					statement.setLong(1, context.getId());
					statement.executeUpdate();
				}
				insertRegions(connection, context.getId(), regions);
				insertPdfContentOrder(connection, context.getId(), regions.size());
				SharedQuestionContext replaced = new SharedQuestionContext(context.getId(), booklet, label, regions);
				connection.commit();
				return replaced;
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not replace shared question context", exception);
		}
	}

	@Override
	public SharedQuestionContext replaceContentParts(SharedQuestionContext context, String label,
			List<? extends SharedContextContentPart> contentParts) {
		if (context == null) {
			throw new NullPointerException("context");
		}
		validateContentParts(label, contentParts);
		ExamBooklet booklet = context.getBooklet();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE shared_question_contexts
						SET context_label = ?
						WHERE id = ? AND booklet_id = ?
						""")) {
					statement.setString(1, label);
					statement.setLong(2, context.getId());
					statement.setLong(3, booklet.getId());
					if (statement.executeUpdate() != 1) {
						throw new IllegalArgumentException("Shared context does not exist for booklet");
					}
				}

				// References must go first; the v24 composite foreign keys prevent
				// deletion of PDF/image data still referenced by ordering records.
				deleteContentParts(connection, context.getId());
				deleteImages(connection, context.getId());
				deleteRegions(connection, context.getId());
				insertMixedContent(connection, context.getId(), contentParts);
				SharedQuestionContext replacement = SharedQuestionContext.fromContentParts(context.getId(), booklet,
						label, contentParts);
				connection.commit();
				return replacement;
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not replace shared question context", exception);
		}
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

	@Override
	public SharedQuestionContext saveContentParts(ExamBooklet booklet, String label,
			List<? extends SharedContextContentPart> contentParts) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		validateContentParts(label, contentParts);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SharedQuestionContext saved = saveContentParts(connection, booklet, label, contentParts);
				connection.commit();
				return saved;
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not save shared question context", exception);
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

		// Release mixed-content references before deleting the records
		// to which they refer. The caller owns the transaction.
		deleteContentParts(connection, contextId);
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM shared_question_context_images
				WHERE shared_context_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.executeUpdate();
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
				WHERE id = ? AND booklet_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.setLong(2, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(SharedQuestionContext.fromContentParts(contextId, booklet,
						result.getString("context_label"), findContentParts(connection, contextId)));
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

		// Both the source regions and their presentation-order references
		// belong to the caller's transaction.
		insertRegions(connection, contextId, regions);
		insertPdfContentOrder(connection, contextId, regions.size());
		return new SharedQuestionContext(contextId, booklet, label, regions);
	}

	SharedQuestionContext saveContentParts(Connection connection, ExamBooklet booklet, String label,
			List<? extends SharedContextContentPart> contentParts) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		validateContentParts(label, contentParts);
		long id = insertContext(connection, booklet, label);
		insertMixedContent(connection, id, contentParts);
		return SharedQuestionContext.fromContentParts(id, booklet, label, contentParts);
	}

	private void deleteContentParts(Connection connection, long contextId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM shared_question_context_content_parts
				WHERE shared_context_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.executeUpdate();
		}
	}

	private void deleteImages(Connection connection, long contextId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM shared_question_context_images
				WHERE shared_context_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.executeUpdate();
		}
	}

	private void deleteRegions(Connection connection, long contextId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM shared_question_context_regions
				WHERE shared_context_id = ?
				""")) {
			statement.setLong(1, contextId);
			statement.executeUpdate();
		}
	}

	private List<SharedContextContentPart> findContentParts(Connection connection, long contextId) throws SQLException {
		List<SharedContextContentPart> parts = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT p.content_type,
				       r.page_number, r.x, r.y, r.width, r.height,
				       i.image_png
				FROM shared_question_context_content_parts p
				LEFT JOIN shared_question_context_regions r
				  ON p.shared_context_id = r.shared_context_id
				 AND p.region_order = r.region_order
				LEFT JOIN shared_question_context_images i
				  ON p.shared_context_id = i.shared_context_id
				 AND p.image_order = i.image_order
				WHERE p.shared_context_id = ?
				ORDER BY p.content_order
				""")) {
			statement.setLong(1, contextId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					String type = result.getString("content_type");
					if ("PDF_REGION".equals(type)) {
						int page = result.getInt("page_number");
						if (result.wasNull()) {
							throw new SQLException("Shared Context references missing PDF region");
						}
						parts.add(new SharedQuestionContextRegion(page, result.getDouble("x"), result.getDouble("y"),
								result.getDouble("width"), result.getDouble("height")));
					} else if ("IMAGE".equals(type)) {
						byte[] png = result.getBytes("image_png");
						if (png == null || png.length == 0) {
							throw new SQLException("Shared Context references missing PNG image");
						}
						parts.add(new ImageSharedContextContentPart(png));
					} else {
						throw new SQLException("Unexpected Shared Context content type: " + type);
					}
				}
			}
		}
		if (parts.isEmpty()) {
			throw new SQLException("Shared Context has no persisted content parts: " + contextId);
		}
		return List.copyOf(parts);
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

	private void insertMixedContent(Connection connection, long contextId,
			List<? extends SharedContextContentPart> contentParts) throws SQLException {
		try (PreparedStatement insertRegion = connection.prepareStatement("""
				INSERT INTO shared_question_context_regions
				    (shared_context_id, region_order, page_number, x, y, width, height)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				"""); PreparedStatement insertImage = connection.prepareStatement("""
				INSERT INTO shared_question_context_images
				    (shared_context_id, image_order, image_png)
				VALUES (?, ?, ?)
				"""); PreparedStatement insertOrder = connection.prepareStatement("""
				INSERT INTO shared_question_context_content_parts
				    (shared_context_id, content_order, content_type, region_order, image_order)
				VALUES (?, ?, ?, ?, ?)
				""")) {
			int regionOrder = 0;
			int imageOrder = 0;
			for (int contentOrder = 0; contentOrder < contentParts.size(); contentOrder++) {
				SharedContextContentPart part = contentParts.get(contentOrder);
				insertOrder.setLong(1, contextId);
				insertOrder.setInt(2, contentOrder);
				if (part instanceof SharedQuestionContextRegion region) {
					insertRegion.setLong(1, contextId);
					insertRegion.setInt(2, regionOrder);
					insertRegion.setInt(3, region.pageNumber());
					insertRegion.setDouble(4, region.x());
					insertRegion.setDouble(5, region.y());
					insertRegion.setDouble(6, region.width());
					insertRegion.setDouble(7, region.height());
					insertRegion.executeUpdate();
					insertOrder.setString(3, "PDF_REGION");
					insertOrder.setInt(4, regionOrder);
					insertOrder.setNull(5, Types.INTEGER);
					regionOrder++;
				} else if (part instanceof ImageSharedContextContentPart image) {
					insertImage.setLong(1, contextId);
					insertImage.setInt(2, imageOrder);
					insertImage.setBytes(3, image.pngBytes());
					insertImage.executeUpdate();
					insertOrder.setString(3, "IMAGE");
					insertOrder.setNull(4, Types.INTEGER);
					insertOrder.setInt(5, imageOrder);
					imageOrder++;
				} else {
					throw new IllegalArgumentException("Unsupported Shared Context content part: " + part);
				}

				// Insert ordering only once its target source record exists.
				insertOrder.executeUpdate();
			}
		}
	}

	private void insertPdfContentOrder(Connection connection, long contextId, int regionCount) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO shared_question_context_content_parts
				    (shared_context_id,
				     content_order,
				     content_type,
				     region_order,
				     image_order)
				VALUES (?, ?, 'PDF_REGION', ?, NULL)
				""")) {

			// The PDF-only compatibility path uses identical source and
			// presentation positions, matching pre-v24 semantics.
			for (int index = 0; index < regionCount; index++) {
				statement.setLong(1, contextId);
				statement.setInt(2, index);
				statement.setInt(3, index);
				statement.executeUpdate();
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

	private void validateContentParts(String label, List<? extends SharedContextContentPart> contentParts) {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		if (contentParts == null) {
			throw new NullPointerException("contentParts");
		}
		if (contentParts.isEmpty()) {
			throw new IllegalArgumentException("contentParts must not be empty");
		}
		for (SharedContextContentPart part : contentParts) {
			if (part == null) {
				throw new NullPointerException("contentParts contains null");
			}
		}
	}

	private record ContextRow(long id, String label) {
	}
}
