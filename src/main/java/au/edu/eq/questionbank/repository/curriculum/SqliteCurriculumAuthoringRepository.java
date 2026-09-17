package au.edu.eq.questionbank.repository.curriculum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite-backed authoring reads for persisted curricula.
 */
public final class SqliteCurriculumAuthoringRepository implements CurriculumAuthoringRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a reader for persisted authoring hierarchies.
	 *
	 * @param database initialised question-bank database
	 */
	public SqliteCurriculumAuthoringRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public List<PersistedCurriculumNode> findNodesForVersion(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		try (Connection connection = database.openConnection()) {
			return findNodes(connection, syllabusVersion.getId());
		} catch (SQLException e) {
			throw new IllegalStateException(
					"Could not read curriculum authoring data for syllabus version " + syllabusVersion.getId(), e);
		}
	}

	/**
	 * Reads on the caller's transaction so snapshot checks and writes are atomic.
	 */
	static List<PersistedCurriculumNode> findNodes(Connection connection, long syllabusVersionId) throws SQLException {
		List<PersistedCurriculumNode> nodes = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    id,
				    parent_id,
				    curriculum_code,
				    curriculum_name,
				    curriculum_level,
				    display_order,
				    source_page_number
				FROM curriculum_nodes
				WHERE syllabus_version_id = ?
				ORDER BY id
				""")) {
			statement.setLong(1, syllabusVersionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					nodes.add(createNode(result));
				}
			}
			return List.copyOf(nodes);
		}
	}

	private static PersistedCurriculumNode createNode(ResultSet result) throws SQLException {
		long parentId = result.getLong("parent_id");
		Long parentPersistentId = result.wasNull() ? null : parentId;
		int sourcePage = result.getInt("source_page_number");
		Integer sourcePageNumber = result.wasNull() ? null : sourcePage;
		CurriculumLevel level;
		try {
			level = CurriculumLevel.valueOf(result.getString("curriculum_level"));
		} catch (IllegalArgumentException e) {
			throw new SQLException("Invalid persisted curriculum level for node " + result.getLong("id"), e);
		}
		return new PersistedCurriculumNode(result.getLong("id"), level, result.getString("curriculum_code"),
				result.getString("curriculum_name"), parentPersistentId, result.getInt("display_order"),
				sourcePageNumber);
	}
}
