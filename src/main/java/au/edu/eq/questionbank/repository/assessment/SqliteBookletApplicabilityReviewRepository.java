package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite implementation of explicit booklet applicability-review confirmations.
 */
public final class SqliteBookletApplicabilityReviewRepository implements BookletApplicabilityReviewRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a repository using an initialised SQLite database.
	 *
	 * @param database SQLite database
	 */
	public SqliteBookletApplicabilityReviewRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public Set<Long> findReviewedBookletIds(long examId) {
		requireId(examId, "examId");
		Set<Long> reviewedIds = new HashSet<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT r.booklet_id
						FROM booklet_applicability_reviews r
						JOIN exam_booklets b ON b.id = r.booklet_id
						WHERE b.exam_id = ?
						ORDER BY r.booklet_id
						""")) {
			statement.setLong(1, examId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					reviewedIds.add(result.getLong("booklet_id"));
				}
			}
			return Set.copyOf(reviewedIds);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not load booklet applicability reviews", exception);
		}
	}

	@Override
	public Set<Long> findReviewedBookletIdsForSubject(long subjectId) {
		requireId(subjectId, "subjectId");
		Set<Long> reviewedIds = new HashSet<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT r.booklet_id
						FROM booklet_applicability_reviews r
						JOIN exam_booklets b ON b.id = r.booklet_id
						JOIN exams e ON e.id = b.exam_id
						WHERE e.subject_id = ?
						ORDER BY r.booklet_id
						""")) {
			statement.setLong(1, subjectId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					reviewedIds.add(result.getLong("booklet_id"));
				}
			}
			return Set.copyOf(reviewedIds);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not load Subject booklet applicability reviews", exception);
		}
	}

	@Override
	public boolean isReviewed(long bookletId) {
		requireId(bookletId, "bookletId");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM booklet_applicability_reviews
						WHERE booklet_id = ?
						""")) {
			statement.setLong(1, bookletId);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not read booklet applicability review", exception);
		}
	}

	@Override
	public void setReviewed(long bookletId, boolean reviewed) {
		requireId(bookletId, "bookletId");
		try (Connection connection = database.openConnection()) {
			if (reviewed) {
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT OR IGNORE INTO booklet_applicability_reviews
						    (booklet_id)
						VALUES (?)
						""")) {
					statement.setLong(1, bookletId);
					statement.executeUpdate();
				}
			} else {
				try (PreparedStatement statement = connection.prepareStatement("""
						DELETE FROM booklet_applicability_reviews
						WHERE booklet_id = ?
						""")) {
					statement.setLong(1, bookletId);
					statement.executeUpdate();
				}
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not update booklet applicability review", exception);
		}
	}

	private void requireId(long id, String field) {
		if (id < 1) {
			throw new IllegalArgumentException(field + " must be a positive persistent identifier");
		}
	}
}
