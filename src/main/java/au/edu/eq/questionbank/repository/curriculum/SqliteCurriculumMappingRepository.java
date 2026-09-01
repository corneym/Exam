package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;

/**
 * Reads directional curriculum mappings from SQLite in persistent mapping-id
 * order. Nodes are reconstructed through the shared curriculum repository using
 * stored identities and parent links, not assumptions about classification codes.
 * Missing or inconsistent persisted state and SQL failures are reported as
 * {@link IllegalStateException}.
 */
public final class SqliteCurriculumMappingRepository implements CurriculumMappingRepository {
	private final SqliteDatabase database;
	private final SqliteCurriculumRepository curriculumRepository;

	/**
	 * @param database the initialised question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteCurriculumMappingRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		curriculumRepository = new SqliteCurriculumRepository(database);
	}

	@Override
	public List<CurriculumMapping> findAll() {
		List<CurriculumMapping> mappings = new ArrayList<>();
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    mapping.id,
						    mapping.mapping_status,
						    mapping.source_node_id,
						    mapping.target_node_id
						FROM curriculum_mappings mapping
						ORDER BY mapping.id
						""")) {
			while (result.next()) {
				mappings.add(createMapping(connection, result));
			}
			return mappings;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read curriculum mappings from database", e);
		}
	}

	/**
	 * {@inheritDoc}
	 * @throws NullPointerException if {@code target} is {@code null}
	 */
	@Override
	public List<CurriculumMapping> findSources(CurriculumNode target) {
		if (target == null) {
			throw new NullPointerException("target");
		}
		return findByNode("target_node_id", target.getId());
	}

	/**
	 * {@inheritDoc}
	 * @throws NullPointerException if {@code source} is {@code null}
	 */
	@Override
	public List<CurriculumMapping> findTargets(CurriculumNode source) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		return findByNode("source_node_id", source.getId());
	}

	private CurriculumMapping createMapping(Connection connection, ResultSet result) throws SQLException {
		long mappingId = result.getLong("id");
		try {
			CurriculumNode source = findNode(connection, result.getLong("source_node_id"));
			CurriculumNode target = findNode(connection, result.getLong("target_node_id"));
			String storedStatus = result.getString("mapping_status");
			if (storedStatus == null) {
				throw new IllegalStateException("Missing mapping status");
			}
			MappingStatus status = MappingStatus.valueOf(storedStatus);
			return new CurriculumMapping(mappingId, source, target, status);
		} catch (IllegalArgumentException | IllegalStateException e) {
			throw new IllegalStateException("Invalid stored curriculum mapping " + mappingId + ": " + e.getMessage(), e);
		}
	}

	private List<CurriculumMapping> findByNode(String columnName, long nodeId) {
		List<CurriculumMapping> mappings = new ArrayList<>();
		String sql = """
				SELECT
				    mapping.id,
				    mapping.mapping_status,
				    mapping.source_node_id,
				    mapping.target_node_id
				FROM curriculum_mappings mapping
				WHERE mapping.%s = ?
				ORDER BY mapping.id
				""".formatted(columnName);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, nodeId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					mappings.add(createMapping(connection, result));
				}
			}
			return mappings;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read curriculum mappings from database", e);
		}
	}

	private CurriculumNode findNode(Connection connection, long nodeId) throws SQLException {
		return curriculumRepository.findNodeById(connection, nodeId)
				.orElseThrow(() -> new IllegalStateException("Missing curriculum node " + nodeId));
	}
}
