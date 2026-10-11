package au.edu.eq.questionbank.admin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Defines benchmark workloads backed by production Question repositories.
 *
 * The supplied database must be an existing, immutable SQLite snapshot.
 * Benchmark execution uses read-only connections and never initialises or
 * migrates the supplied database.
 *
 * Workload preparation is separate from measured execution.
 */
public final class PerformanceBenchmarkWorkloads {

	private final SqliteQuestionRepository questions;

	/**
	 * Creates workloads using an existing read-only database snapshot.
	 *
	 * @param snapshotPath explicit path to an immutable SQLite snapshot
	 * @throws SQLException         if the snapshot is unavailable or its schema is
	 *                              not the current application schema
	 * @throws NullPointerException if snapshotPath is null
	 */
	public PerformanceBenchmarkWorkloads(Path snapshotPath) throws SQLException {
		Objects.requireNonNull(snapshotPath, "snapshotPath");
		SqliteDatabase database = SqliteDatabase.readOnly(snapshotPath);
		int schemaVersion = database.schemaVersion();
		if (schemaVersion != SqliteDatabase.latestSchemaVersion()) {
			throw new SQLException("Benchmark requires schema version " + SqliteDatabase.latestSchemaVersion()
					+ "; snapshot has version " + schemaVersion);
		}

		// Validate once, outside measured benchmark iterations.
		// Never invoke schema initialisation on a benchmark snapshot.
		database.verifySchema();
		this.questions = new SqliteQuestionRepository(database);
	}

	/**
	 * Creates a workload that reconstructs every persisted Question.
	 *
	 * @return production findAll workload
	 */
	public PerformanceBenchmarkRunner.Workload findAll() {
		return () -> questions.findAll().size();
	}

	/**
	 * Creates a workload for current-curriculum applicability retrieval.
	 *
	 * The supplied nodes must be current Subtopics or Descriptors. Subject, Unit
	 * and Topic scopes are expanded to these leaf retrieval nodes by the benchmark
	 * selection layer.
	 *
	 * A Question matching multiple supplied nodes is counted once. The production
	 * repository still performs its complete retrieval and reconstruction work.
	 *
	 * @param currentNodes current Subtopic or Descriptor retrieval nodes
	 * @return curriculum-aware retrieval workload
	 */
	public PerformanceBenchmarkRunner.Workload findApplicableToNodes(List<CurriculumNode> currentNodes) {
		List<CurriculumNode> nodes = List.copyOf(currentNodes);
		if (nodes.isEmpty()) {
			throw new IllegalArgumentException("At least one current curriculum node is required");
		}
		for (CurriculumNode node : nodes) {
			if (!node.getSyllabusVersion().isCurrent()) {
				throw new IllegalArgumentException("Benchmark retrieval requires current curriculum nodes");
			}
			CurriculumLevel level = node.getLevel();
			if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalArgumentException("Retrieval nodes must be Subtopics or Descriptors");
			}
		}

		// Keep the exact production applicability and Question
		// reconstruction boundary inside every measured iteration.
		return () -> questions.findApplicableToNodes(nodes).stream().mapToLong(match -> match.getQuestion().getId())
				.distinct().count();
	}

	/**
	 * Creates a workload that reconstructs one known persisted Question.
	 *
	 * An absent Question is an invalid benchmark target rather than a successful
	 * zero-result measurement.
	 *
	 * @param questionId positive persisted Question identifier
	 * @return production findById workload
	 */
	public PerformanceBenchmarkRunner.Workload findById(long questionId) {
		if (questionId <= 0) {
			throw new IllegalArgumentException("Question ID must be positive");
		}
		return () -> {
			if (questions.findById(questionId).isEmpty()) {
				throw new IllegalStateException("Benchmark Question no longer exists: " + questionId);
			}
			return 1L;
		};
	}
}
