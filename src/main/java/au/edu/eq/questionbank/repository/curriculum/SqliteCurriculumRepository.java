package au.edu.eq.questionbank.repository.curriculum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Reads subjects, syllabus versions, and curriculum hierarchies from SQLite.
 * SQL failures are exposed as {@link IllegalStateException} because the
 * {@link CurriculumRepository} interface represents lookup operations rather
 * than JDBC operations.
 */
public final class SqliteCurriculumRepository implements CurriculumRepository {

	private static final int MAX_HIERARCHY_DEPTH = 4;
	private final SqliteDatabase database;

	/**
	 * Creates a curriculum repository backed by the supplied database.
	 *
	 * @param database the initialized question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteCurriculumRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public List<Subject> findAllSubjects() {
		List<Subject> subjects = new ArrayList<>();
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id, subject_name
						FROM subjects
						ORDER BY subject_name
						""")) {
			while (result.next()) {
				Subject subject = new Subject(result.getLong("id"), result.getString("subject_name"));
				subjects.add(subject);
			}
			return subjects;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read subjects from database", e);
		}
	}

	@Override
	public Optional<CurriculumNode> findByCode(SyllabusVersion syllabusVersion, String code) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		String[] codeParts = code.split("\\.");
		List<CurriculumNode> candidates = findRootNodes(syllabusVersion);
		CurriculumNode foundNode = null;
		String currentCode = "";
		for (int index = 0; index < codeParts.length; index++) {
			if (currentCode.isEmpty()) {
				currentCode = codeParts[index];
			} else {
				currentCode = currentCode + "." + codeParts[index];
			}
			foundNode = null;
			for (CurriculumNode candidate : candidates) {
				if (candidate.getCode().equals(currentCode)) {
					foundNode = candidate;
					break;
				}
			}
			if (foundNode == null) {
				return Optional.empty();
			}
			if (index < codeParts.length - 1) {
				candidates = findChildren(foundNode);
			}
		}
		return Optional.of(foundNode);
	}

	@Override
	public List<CurriculumNode> findChildren(CurriculumNode parent) {
		if (parent == null) {
			throw new NullPointerException("parent");
		}
		List<CurriculumNode> children = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    id,
						    curriculum_code,
						    curriculum_name,
						    curriculum_level,
						    display_order
						FROM curriculum_nodes
						WHERE parent_id = ?
						ORDER BY display_order, curriculum_code
						""")) {
			statement.setLong(1, parent.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					CurriculumNode child = createChild(result, parent);
					children.add(child);
				}
			}
			return children;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read curriculum children from database", e);
		}
	}

	@Override
	public List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		List<CurriculumNode> nodes = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    id,
						    curriculum_code,
						    curriculum_name,
						    display_order
						FROM curriculum_nodes
						WHERE syllabus_version_id = ?
						  AND parent_id IS NULL
						  AND curriculum_level = 'UNIT'
						ORDER BY display_order, curriculum_code
						""")) {
			statement.setLong(1, syllabusVersion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					Unit unit = new Unit(result.getLong("id"), syllabusVersion, result.getString("curriculum_code"),
							result.getString("curriculum_name"), result.getInt("display_order"));
					nodes.add(unit);
				}
			}
			return nodes;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read curriculum root nodes from database", e);
		}
	}

	@Override
	public Optional<Subject> findSubjectById(long id) {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id, subject_name
						FROM subjects
						WHERE id = ?
						""")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Subject subject = new Subject(result.getLong("id"), result.getString("subject_name"));
				return Optional.of(subject);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read subject from database", e);
		}
	}

	@Override
	public Optional<SyllabusVersion> findVersionById(long id) {
		try (Connection connection = database.openConnection()) {
			return findVersionById(connection, id);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read syllabus version from database", e);
		}
	}

	@Override
	public List<SyllabusVersion> findVersionsForSubject(Subject subject) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		List<SyllabusVersion> versions = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    id AS version_id,
						    syllabus_name,
						    is_current,
						    curriculum_status,
						    curriculum_finalised_at,
						    source_pdf_path
						FROM syllabus_versions
						WHERE subject_id = ?
						ORDER BY syllabus_name
						""")) {
			statement.setLong(1, subject.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					versions.add(createSyllabusVersion(result, subject));
				}
			}
			return versions;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read syllabus versions from database", e);
		}
	}

	/**
	 * Reconstructs a node using its stored parent links, without interpreting its
	 * code. Mapping reads share their caller-owned connection so mapping and node
	 * identities are read from the same SQLite snapshot.
	 *
	 * @param connection the caller-owned connection with an active read
	 * @param id         the persistent node identifier
	 * @return the reconstructed node, or empty if the identifier is absent
	 * @throws SQLException          if a lookup fails
	 * @throws IllegalStateException if a parent/version is missing or the stored
	 *                               hierarchy is inconsistent
	 */
	Optional<CurriculumNode> findNodeById(Connection connection, long id) throws SQLException {
		return findNodeById(connection, id, MAX_HIERARCHY_DEPTH);
	}

	private CurriculumNode createChild(ResultSet result, CurriculumNode parent) throws SQLException {
		long id = result.getLong("id");
		String code = result.getString("curriculum_code");
		String name = result.getString("curriculum_name");
		String level = result.getString("curriculum_level");
		int displayOrder = result.getInt("display_order");
		SyllabusVersion syllabusVersion = parent.getSyllabusVersion();
		switch (level) {
		case "TOPIC":
			if (!(parent instanceof Unit)) {
				throw new IllegalStateException("TOPIC has invalid parent");
			}
			return new Topic(id, syllabusVersion, (Unit) parent, code, name, displayOrder);
		case "SUBTOPIC":
			if (!(parent instanceof Topic)) {
				throw new IllegalStateException("SUBTOPIC has invalid parent");
			}
			return new Subtopic(id, syllabusVersion, (Topic) parent, code, name, displayOrder);
		case "DESCRIPTOR":
			if (parent instanceof Topic) {
				return new Descriptor(id, syllabusVersion, (Topic) parent, code, name, displayOrder);
			}
			if (parent instanceof Subtopic) {
				return new Descriptor(id, syllabusVersion, (Subtopic) parent, code, name, displayOrder);
			}
			throw new IllegalStateException("DESCRIPTOR has invalid parent");
		default:
			throw new IllegalStateException("Unexpected child curriculum level: " + level);
		}
	}

	private SyllabusVersion createSyllabusVersion(ResultSet result, Subject subject) throws SQLException {
		String statusText = result.getString("curriculum_status");
		String finalisedText = result.getString("curriculum_finalised_at");
		try {
			CurriculumStatus status = CurriculumStatus.valueOf(statusText);
			Instant finalisedAt = finalisedText == null ? null : Instant.parse(finalisedText);
			return new SyllabusVersion(result.getLong("version_id"), subject, result.getString("syllabus_name"),
					result.getInt("is_current") == 1, status, finalisedAt, result.getString("source_pdf_path"));
		} catch (IllegalArgumentException e) {
			throw new SQLException(
					"Invalid curriculum authoring metadata for syllabus version " + result.getLong("version_id"), e);
		}
	}

	private Optional<CurriculumNode> findNodeById(Connection connection, long id, int remainingLevels)
			throws SQLException {
		if (remainingLevels == 0) {
			throw new IllegalStateException("Curriculum hierarchy is cyclic or exceeds four levels at node " + id);
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, syllabus_version_id, parent_id, curriculum_code, curriculum_name,
				       curriculum_level, display_order
				FROM curriculum_nodes
				WHERE id = ?
				""")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				long versionId = result.getLong("syllabus_version_id");
				long parentId = result.getLong("parent_id");
				if (result.wasNull()) {
					if (!"UNIT".equals(result.getString("curriculum_level"))) {
						throw new IllegalStateException("Non-unit curriculum node has no parent: " + id);
					}
					SyllabusVersion version = findVersionById(connection, versionId)
							.orElseThrow(() -> new IllegalStateException("Missing syllabus version " + versionId));
					return Optional.of(new Unit(id, version, result.getString("curriculum_code"),
							result.getString("curriculum_name"), result.getInt("display_order")));
				}
				CurriculumNode parent = findNodeById(connection, parentId, remainingLevels - 1)
						.orElseThrow(() -> new IllegalStateException("Missing curriculum parent " + parentId));
				if (parent.getSyllabusVersion().getId() != versionId) {
					throw new IllegalStateException(
							"Curriculum node " + id + " has a parent in another syllabus version");
				}
				return Optional.of(createChild(result, parent));
			}
		}
	}

	private Optional<SyllabusVersion> findVersionById(Connection connection, long id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    version.id AS version_id,
				    version.syllabus_name,
				    version.is_current,
				    version.curriculum_status,
				    version.curriculum_finalised_at,
				    version.source_pdf_path,
				    subject.id AS subject_id,
				    subject.subject_name
				FROM syllabus_versions version
				JOIN subjects subject
				    ON subject.id = version.subject_id
				WHERE version.id = ?
				""")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Subject subject = new Subject(result.getLong("subject_id"), result.getString("subject_name"));
				return Optional.of(createSyllabusVersion(result, subject));
			}
		}
	}
}
