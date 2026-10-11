package au.edu.eq.questionbank.admin;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.audit.ExamCorpusAuditService;

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

	private final Path absentAssetRoot;
	private final SqliteDatabase database;
	private final SqliteQuestionRepository questions;

	public PerformanceBenchmarkWorkloads(Path snapshotPath) throws SQLException {
		Objects.requireNonNull(snapshotPath, "snapshotPath");
		database = SqliteDatabase.readOnly(snapshotPath);
		int schemaVersion = database.schemaVersion();
		if (schemaVersion != SqliteDatabase.latestSchemaVersion()) {
			throw new SQLException("Benchmark requires schema version " + SqliteDatabase.latestSchemaVersion()
					+ "; snapshot has version " + schemaVersion);
		}

		// Validate the immutable snapshot before any measurements.
		// Never initialise or migrate the benchmark database.
		database.verifySchema();
		questions = new SqliteQuestionRepository(database);

		// The audit service checks managed PDF availability. Use a
		// deliberately nonexistent root so timings never depend on
		// mutable development or production PDF assets.
		Path absoluteSnapshot = snapshotPath.toAbsolutePath().normalize();
		absentAssetRoot = absoluteSnapshot.resolveSibling(absoluteSnapshot.getFileName() + ".benchmark-no-pdfs");
	}

	/**
	 * Creates a production Dashboard corpus-audit benchmark for one Subject.
	 *
	 * Subject resolution and repository/service construction occur outside measured
	 * iterations. Every iteration executes the real
	 * ExamCorpusAuditService.assessSubject operation.
	 *
	 * Physical Question PDFs are deliberately treated as unavailable, because this
	 * workload requires only an immutable SQLite snapshot. Consequently, it
	 * measures audit preparation but cannot reproduce the PDF-availability findings
	 * of a complete installed data root.
	 *
	 * @param subjectId positive persisted Subject identifier
	 * @return workload reporting the number of audited Exams
	 * @throws IllegalArgumentException if the Subject ID is invalid or absent
	 * @throws IllegalStateException    if the reserved absent-asset root exists
	 */
	public PerformanceBenchmarkRunner.Workload dashboardAuditSubject(long subjectId) {
		if (subjectId <= 0) {
			throw new IllegalArgumentException("Subject ID must be positive");
		}
		Subject subject = new SqliteCurriculumRepository(database).findSubjectById(subjectId)
				.orElseThrow(() -> new IllegalArgumentException("Benchmark Subject does not exist: " + subjectId));
		requireAbsentAssetRoot();
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		ExamCorpusAuditService auditService = new ExamCorpusAuditService(examWriter, answerWriter, questions,
				new PdfStore(absentAssetRoot));
		return () -> {

			// Detect an accidentally populated asset location rather
			// than silently changing the benchmark's PDF assumptions.
			requireAbsentAssetRoot();
			return auditService.assessSubject(subject).size();
		};
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

	private void requireAbsentAssetRoot() {
		if (Files.exists(absentAssetRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("Reserved benchmark asset root must not exist: " + absentAssetRoot);
		}
	}
}
