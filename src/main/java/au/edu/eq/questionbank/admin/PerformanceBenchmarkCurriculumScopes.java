package au.edu.eq.questionbank.admin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Resolves benchmark curriculum scopes against a read-only SQLite snapshot.
 *
 * Scope expansion takes place before timed benchmark iterations. The resulting
 * nodes can be supplied directly to the production applicability retrieval
 * workload.
 *
 * Both current Subtopics and Descriptors are retained because Questions may be
 * classified directly at either level.
 */
public final class PerformanceBenchmarkCurriculumScopes {

	private final SqliteCurriculumRepository curriculum;

	/**
	 * Creates a curriculum scope resolver for an existing database snapshot.
	 *
	 * @param snapshotPath immutable benchmark database snapshot
	 * @throws SQLException if the database cannot be read or its schema is invalid
	 */
	public PerformanceBenchmarkCurriculumScopes(Path snapshotPath) throws SQLException {
		Objects.requireNonNull(snapshotPath, "snapshotPath");
		SqliteDatabase database = SqliteDatabase.readOnly(snapshotPath);
		if (database.schemaVersion() != SqliteDatabase.latestSchemaVersion()) {
			throw new SQLException("Benchmark requires the current database schema");
		}
		database.verifySchema();
		this.curriculum = new SqliteCurriculumRepository(database);
	}

	/**
	 * Resolves one current Descriptor for applicability benchmarking.
	 *
	 * @param descriptorId persistent Descriptor identifier
	 * @return singleton retrieval-node list
	 */
	public List<CurriculumNode> descriptor(long descriptorId) {
		return resolveNode(descriptorId, CurriculumLevel.DESCRIPTOR);
	}

	/**
	 * Resolves the current curriculum within a Subject.
	 *
	 * @param subjectId persistent Subject identifier
	 * @return Subtopics and Descriptors in curriculum traversal order
	 */
	public List<CurriculumNode> subject(long subjectId) {
		requirePositiveId(subjectId);
		Subject subject = curriculum.findSubjectById(subjectId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown Subject ID: " + subjectId));
		List<SyllabusVersion> currentVersions = curriculum.findVersionsForSubject(subject).stream()
				.filter(SyllabusVersion::isCurrent).toList();
		if (currentVersions.size() != 1) {
			throw new IllegalArgumentException("Subject must have exactly one current syllabus: " + subjectId);
		}
		List<CurriculumNode> selected = new ArrayList<>();
		for (CurriculumNode unit : curriculum.findRootNodes(currentVersions.getFirst())) {
			collectRetrievalNodes(unit, selected);
		}
		return requireRetrievalNodes(selected);
	}

	/**
	 * Resolves a current Topic and all its retrievable descendants.
	 *
	 * @param topicId persistent Topic identifier
	 * @return current Subtopics and Descriptors within the Topic
	 */
	public List<CurriculumNode> topic(long topicId) {
		return resolveNode(topicId, CurriculumLevel.TOPIC);
	}

	/**
	 * Resolves a current Unit and all its retrievable descendants.
	 *
	 * @param unitId persistent Unit identifier
	 * @return current Subtopics and Descriptors within the Unit
	 */
	public List<CurriculumNode> unit(long unitId) {
		return resolveNode(unitId, CurriculumLevel.UNIT);
	}

	private void collectRetrievalNodes(CurriculumNode node, List<CurriculumNode> selected) {
		CurriculumLevel level = node.getLevel();

		// Directly classified Subtopic Questions must remain searchable
		// even when that Subtopic also contains Descriptors.
		if (level == CurriculumLevel.SUBTOPIC || level == CurriculumLevel.DESCRIPTOR) {
			selected.add(node);
		}
		if (level == CurriculumLevel.DESCRIPTOR) {
			return;
		}

		// Follow persisted parent relationships rather than constructing
		// curriculum codes or assuming a fixed number of hierarchy levels.
		for (CurriculumNode child : curriculum.findChildren(node)) {
			collectRetrievalNodes(child, selected);
		}
	}

	private CurriculumNode findNode(CurriculumNode root, long nodeId) {
		if (root.getId() == nodeId) {
			return root;
		}
		if (root.getLevel() == CurriculumLevel.DESCRIPTOR) {
			return null;
		}
		for (CurriculumNode child : curriculum.findChildren(root)) {
			CurriculumNode found = findNode(child, nodeId);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private void requirePositiveId(long id) {
		if (id <= 0) {
			throw new IllegalArgumentException("Curriculum identifier must be positive");
		}
	}

	private List<CurriculumNode> requireRetrievalNodes(List<CurriculumNode> selected) {
		if (selected.isEmpty()) {
			throw new IllegalArgumentException(
					"Selected current curriculum scope has no " + "Subtopic or Descriptor nodes");
		}
		return List.copyOf(selected);
	}

	private List<CurriculumNode> resolveNode(long nodeId, CurriculumLevel expectedLevel) {
		requirePositiveId(nodeId);

		// Only current syllabus versions are searched. Historical nodes
		// cannot accidentally become benchmark retrieval targets.
		for (Subject subject : curriculum.findAllSubjects()) {
			for (SyllabusVersion version : curriculum.findVersionsForSubject(subject)) {
				if (!version.isCurrent()) {
					continue;
				}
				for (CurriculumNode unit : curriculum.findRootNodes(version)) {
					CurriculumNode selectedNode = findNode(unit, nodeId);
					if (selectedNode == null) {
						continue;
					}
					if (selectedNode.getLevel() != expectedLevel) {
						throw new IllegalArgumentException("Selected node is not a " + expectedLevel + ": " + nodeId);
					}
					List<CurriculumNode> selected = new ArrayList<>();
					collectRetrievalNodes(selectedNode, selected);
					return requireRetrievalNodes(selected);
				}
			}
		}
		throw new IllegalArgumentException("Current curriculum node not found: " + nodeId);
	}
}
