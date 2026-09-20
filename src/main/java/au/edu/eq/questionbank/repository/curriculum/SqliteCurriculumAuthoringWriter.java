package au.edu.eq.questionbank.repository.curriculum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;

/**
 * Transactionally saves a resumable curriculum-authoring session.
 * <p>
 * Existing nodes are updated in place, new nodes receive generated persistent
 * identifiers, and nodes removed from the draft are deleted when they are not
 * referenced by other application data.
 */
public final class SqliteCurriculumAuthoringWriter implements CurriculumAuthoringWriter {

	private final SqliteDatabase database;

	/**
	 * Creates a transactional writer for resumable authoring sessions.
	 *
	 * @param database initialised question-bank database
	 */
	public SqliteCurriculumAuthoringWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Persists the complete current state of an authoring session.
	 *
	 * @param session authoring session to save
	 * @throws IllegalArgumentException if the draft is structurally invalid
	 * @throws IllegalStateException    if the syllabus is final, persisted nodes
	 *                                  differ from the session's snapshot, a
	 *                                  deleted node is referenced, or persistence
	 *                                  fails
	 */
	@Override
	public void save(CurriculumAuthoringSession session) {
		if (session == null) {
			throw new NullPointerException("session");
		}
		if (session.syllabusVersion().getCurriculumStatus() == CurriculumStatus.FINAL) {
			throw new IllegalStateException("Final curriculum must be reopened before editing");
		}
		List<String> validationProblems = session.draft().validationProblems();
		if (!validationProblems.isEmpty()) {
			throw new IllegalArgumentException(
					"Cannot save invalid curriculum draft: " + String.join("; ", validationProblems));
		}
		SaveChanges changes;
		try {
			changes = saveTransactionally(session);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save curriculum authoring session", e);
		}

		// Only mutate the session's persistence bindings after the database transaction
		// has committed successfully.
		// Forget successfully deleted rows before binding newly inserted rows. SQLite
		// may legitimately reuse an INTEGER PRIMARY KEY value from a deleted row.
		for (long persistentId : changes.deletedPersistentIds()) {
			session.forgetPersistentId(persistentId);
		}
		for (Map.Entry<Long, Long> entry : changes.newBindings().entrySet()) {
			session.bindPersistentId(entry.getKey(), entry.getValue());
		}
		session.recordPersistedSnapshot(changes.persistedNodes());
	}

	private void deletePersistedNodes(Connection connection, long syllabusVersionId, Set<Long> persistentIds)
			throws SQLException {
		if (persistentIds.isEmpty()) {
			return;
		}
		List<Long> orderedIds = persistentIds.stream().sorted().toList();
		String placeholders = String.join(", ", Collections.nCopies(orderedIds.size(), "?"));
		String sql = """
				DELETE FROM curriculum_nodes
				WHERE syllabus_version_id = ?
				  AND id IN (%s)
				""".formatted(placeholders);
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, syllabusVersionId);
			for (int index = 0; index < orderedIds.size(); index++) {
				statement.setLong(index + 2, orderedIds.get(index));
			}
			int deleted = statement.executeUpdate();
			if (deleted != orderedIds.size()) {
				throw new IllegalStateException(
						"Expected to delete " + orderedIds.size() + " curriculum nodes but deleted " + deleted);
			}
		}
	}

	private boolean hasReference(Connection connection, String table, String column, long persistentId)
			throws SQLException {
		String sql = "SELECT 1 FROM " + table + " WHERE " + column + " = ? LIMIT 1";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, persistentId);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private long insertNode(Connection connection, long syllabusVersionId, Long parentPersistentId,
			CurriculumDraftNode node) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO curriculum_nodes
				    (syllabus_version_id,
				     parent_id,
				     curriculum_code,
				     curriculum_name,
				     curriculum_level,
				     display_order,
				     source_page_number)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, syllabusVersionId);
			setNullableLong(statement, 2, parentPersistentId);
			statement.setString(3, node.code());
			statement.setString(4, node.name());
			statement.setString(5, node.level().name());
			statement.setInt(6, node.displayOrder());
			setNullableInteger(statement, 7, node.sourcePageNumber());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Curriculum node insert did not return an id");
				}
				return result.getLong("id");
			}
		}
	}

	private void persistNode(Connection connection, long syllabusVersionId, CurriculumDraft draft,
			CurriculumDraftNode node, Long parentPersistentId, Map<Long, Long> existingBindings,
			Map<Long, Long> newBindings) throws SQLException {

		// Persist each parent before its children, reusing the IDs of existing nodes.
		Long persistentId = existingBindings.get(node.draftId());
		if (persistentId == null) {
			persistentId = insertNode(connection, syllabusVersionId, parentPersistentId, node);
			newBindings.put(node.draftId(), persistentId);
		} else {
			updateNode(connection, syllabusVersionId, persistentId, parentPersistentId, node);
		}
		for (CurriculumDraftNode child : draft.childrenOf(node.draftId())) {
			persistNode(connection, syllabusVersionId, draft, child, persistentId, existingBindings, newBindings);
		}
	}

	private void requireDeletionsUnreferenced(Connection connection, Set<Long> deletedPersistentIds)
			throws SQLException {
		for (long persistentId : deletedPersistentIds) {
			if (hasReference(connection, "questions", "classification_node_id", persistentId)) {
				throw new IllegalStateException(
						"Cannot delete curriculum node " + persistentId + " because it is referenced by a question");
			}
			if (hasReference(connection, "curriculum_mappings", "source_node_id", persistentId)
					|| hasReference(connection, "curriculum_mappings", "target_node_id", persistentId)) {
				throw new IllegalStateException("Cannot delete curriculum node " + persistentId
						+ " because it is referenced by a curriculum mapping");
			}
			if (hasReference(connection, "curriculum_mapping_reviews", "source_node_id", persistentId)) {
				throw new IllegalStateException("Cannot delete curriculum node " + persistentId
						+ " because it is referenced by a curriculum mapping review");
			}
		}
	}

	private void requireSessionMatchesDatabase(Connection connection, long syllabusVersionId,
			CurriculumAuthoringSession session) throws SQLException {
		Set<PersistedCurriculumNode> actual = Set
				.copyOf(SqliteCurriculumAuthoringRepository.findNodes(connection, syllabusVersionId));
		Set<Long> actualIds = new HashSet<>();
		for (PersistedCurriculumNode node : actual) {
			actualIds.add(node.persistentId());
		}

		// Compare the full saved content as well as IDs to reject stale text, code and
		// order edits.
		if (!actual.equals(session.persistedSnapshot())
				|| !actualIds.equals(new HashSet<>(session.persistentBindings().values()))) {
			throw new IllegalStateException(
					"Curriculum changed since this session was loaded. Close and reopen it before saving.");
		}
	}

	private void requireSyllabusEditable(Connection connection, long syllabusVersionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT curriculum_status
				FROM syllabus_versions
				WHERE id = ?
				""")) {
			statement.setLong(1, syllabusVersionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Unknown syllabus version: " + syllabusVersionId);
				}
				String status = result.getString("curriculum_status");
				if (!CurriculumStatus.IN_PROGRESS.name().equals(status)) {
					throw new IllegalStateException("Final curriculum must be reopened before editing");
				}
			}
		}
	}

	private SaveChanges saveTransactionally(CurriculumAuthoringSession session) throws SQLException {
		long syllabusVersionId = session.syllabusVersion().getId();
		Set<Long> deletedPersistentIds = session.deletedPersistentIds();
		Map<Long, Long> existingBindings = session.persistentBindings();
		Map<Long, Long> newBindings = new HashMap<>();
		List<PersistedCurriculumNode> persistedNodes;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Check lifecycle, snapshot and deletion references before the first mutation.
				requireSyllabusEditable(connection, syllabusVersionId);
				requireSessionMatchesDatabase(connection, syllabusVersionId, session);
				requireDeletionsUnreferenced(connection, deletedPersistentIds);
				deletePersistedNodes(connection, syllabusVersionId, deletedPersistentIds);
				temporarilyRecodeExistingNodes(connection, syllabusVersionId, session.draft(), existingBindings);
				for (CurriculumDraftNode root : session.draft().childrenOf(null)) {
					persistNode(connection, syllabusVersionId, session.draft(), root, null, existingBindings,
							newBindings);
				}
				persistedNodes = SqliteCurriculumAuthoringRepository.findNodes(connection, syllabusVersionId);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
		return new SaveChanges(Map.copyOf(newBindings), Set.copyOf(deletedPersistentIds), persistedNodes);
	}

	private void setNullableInteger(PreparedStatement statement, int parameterIndex, Integer value)
			throws SQLException {
		if (value == null) {
			statement.setNull(parameterIndex, Types.INTEGER);
		} else {
			statement.setInt(parameterIndex, value);
		}
	}

	private void setNullableLong(PreparedStatement statement, int parameterIndex, Long value) throws SQLException {
		if (value == null) {
			statement.setNull(parameterIndex, Types.INTEGER);
		} else {
			statement.setLong(parameterIndex, value);
		}
	}

	private void temporarilyRecodeExistingNodes(Connection connection, long syllabusVersionId, CurriculumDraft draft,
			Map<Long, Long> existingBindings) throws SQLException {

		// Free existing codes temporarily so code swaps cannot collide with the unique
		// key.
		String saveToken = UUID.randomUUID().toString();
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE curriculum_nodes
				SET curriculum_code = ?
				WHERE id = ?
				  AND syllabus_version_id = ?
				""")) {
			for (CurriculumDraftNode node : draft.nodes()) {
				Long persistentId = existingBindings.get(node.draftId());
				if (persistentId == null) {
					continue;
				}
				statement.setString(1, "__curriculum_save_" + saveToken + "_" + persistentId);
				statement.setLong(2, persistentId);
				statement.setLong(3, syllabusVersionId);
				if (statement.executeUpdate() != 1) {
					throw new IllegalStateException(
							"Could not prepare persisted curriculum node " + persistentId + " for update");
				}
			}
		}
	}

	private void updateNode(Connection connection, long syllabusVersionId, long persistentId, Long parentPersistentId,
			CurriculumDraftNode node) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE curriculum_nodes
				SET parent_id = ?,
				    curriculum_code = ?,
				    curriculum_name = ?,
				    curriculum_level = ?,
				    display_order = ?,
				    source_page_number = ?
				WHERE id = ?
				  AND syllabus_version_id = ?
				""")) {
			setNullableLong(statement, 1, parentPersistentId);
			statement.setString(2, node.code());

			// setString preserves Markdown/LaTeX text, backslashes and line breaks without
			// interpretation.
			statement.setString(3, node.name());
			statement.setString(4, node.level().name());
			statement.setInt(5, node.displayOrder());
			setNullableInteger(statement, 6, node.sourcePageNumber());
			statement.setLong(7, persistentId);
			statement.setLong(8, syllabusVersionId);
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Could not update persisted curriculum node " + persistentId);
			}
		}
	}

	private record SaveChanges(Map<Long, Long> newBindings, Set<Long> deletedPersistentIds,
			List<PersistedCurriculumNode> persistedNodes) {
	}
}
