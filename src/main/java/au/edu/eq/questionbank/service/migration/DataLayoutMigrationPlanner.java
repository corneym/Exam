package au.edu.eq.questionbank.service.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.AssetKind;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.Blocker;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.Move;

/**
 * Builds a non-mutating migration plan for pre-Sprint-14 managed data.
 */
public final class DataLayoutMigrationPlanner {

	private final ApplicationConfig config;
	private final SqliteDatabase database;
	private final ManagedDataLayout managedDataLayout;
	private final List<CurriculumWorkbookMigrationAssignment> workbookAssignments;

	/**
	 * Creates a migration planner without explicit curriculum-workbook ownership.
	 *
	 * @param config   application paths
	 * @param database current database
	 */
	public DataLayoutMigrationPlanner(ApplicationConfig config, SqliteDatabase database) {
		this(config, database, List.of());
	}

	/**
	 * Creates a migration planner with explicit ownership for flat legacy
	 * curriculum workbooks.
	 *
	 * @param config              application paths
	 * @param database            current database
	 * @param workbookAssignments explicit old-workbook ownership
	 */
	public DataLayoutMigrationPlanner(ApplicationConfig config, SqliteDatabase database,
			List<CurriculumWorkbookMigrationAssignment> workbookAssignments) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (workbookAssignments == null) {
			throw new NullPointerException("workbookAssignments");
		}
		this.config = config;
		this.database = database;
		this.workbookAssignments = List.copyOf(workbookAssignments);
		managedDataLayout = new ManagedDataLayout(config.dataRoot());
	}

	/**
	 * Calculates the complete migration without modifying files or persistence.
	 *
	 * @return safe moves, recovery-archive files and blockers
	 * @throws SQLException if persistence cannot be inspected
	 * @throws IOException  if managed data cannot be inspected
	 */
	public DataLayoutMigrationPlan plan() throws SQLException, IOException {
		database.verifySchema();
		List<Move> moves = new ArrayList<>();
		List<Path> archiveOnlyFiles = new ArrayList<>();
		List<Blocker> blockers = new ArrayList<>();
		Set<Path> knownLegacyFiles = new HashSet<>();
		Map<Path, Move> destinationOwners = new LinkedHashMap<>();
		planWorkbookAssignments(moves, blockers, knownLegacyFiles, destinationOwners);
		planExamSources(moves, blockers, knownLegacyFiles, destinationOwners);
		planCurriculumSources(moves, blockers, knownLegacyFiles, destinationOwners);
		scanLegacyRoot(config.pdfDataRoot(), knownLegacyFiles, false, archiveOnlyFiles, blockers);
		scanLegacyRoot(config.curriculumDataRoot(), knownLegacyFiles, true, archiveOnlyFiles, blockers);
		return new DataLayoutMigrationPlan(moves, archiveOnlyFiles, blockers);
	}

	private void addMove(AssetKind assetKind, long persistentId, Path source, Path destination, String oldRelativePath,
			String newRelativePath, List<Move> moves, List<Blocker> blockers, Map<Path, Move> destinationOwners)
			throws IOException {
		if (!Files.isRegularFile(source)) {
			blockers.add(new Blocker("MISSING_SOURCE",
					"Persisted managed source is missing or is not a regular file: " + oldRelativePath, source));
			return;
		}
		boolean destinationAlreadyVerified = false;
		if (Files.exists(destination)) {
			if (!Files.isRegularFile(destination)) {
				blockers.add(new Blocker("DESTINATION_COLLISION",
						"Migration destination already exists and is not a regular file.", destination));
				return;
			}
			if (Files.mismatch(source, destination) != -1) {
				blockers.add(new Blocker("DESTINATION_COLLISION",
						"Migration destination already contains different bytes.", destination));
				return;
			}
			destinationAlreadyVerified = true;
		}
		Move move = new Move(assetKind, persistentId, source, destination, oldRelativePath, newRelativePath,
				destinationAlreadyVerified);
		Move existing = destinationOwners.putIfAbsent(destination, move);
		if (existing != null && (!existing.source().equals(source) || existing.assetKind() != assetKind
				|| existing.persistentId() != persistentId)) {
			blockers.add(new Blocker("MULTIPLE_SOURCES_ONE_DESTINATION",
					"More than one managed asset would migrate to the same destination.", destination));
			return;
		}
		moves.add(move);
	}

	private boolean containsTraversalComponent(String portablePath) {
		Path path = Path.of(portablePath);
		for (Path component : path) {
			String value = component.toString();
			if (".".equals(value) || "..".equals(value)) {
				return true;
			}
		}
		return false;
	}

	private boolean isSubjectFirstPath(String relativePath) {
		return relativePath.replace('\\', '/').startsWith("subjects/");
	}

	private boolean isWindowsAbsolutePath(String path) {
		return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
	}

	private void planCurrentReference(String relativePath, String description, List<Blocker> blockers) {
		try {
			String portablePath = relativePath.replace('\\', '/');
			if (containsTraversalComponent(portablePath)) {
				throw new IllegalArgumentException("Subject-first path contains traversal components");
			}
			Path resolved = managedDataLayout.resolve(portablePath);
			if (!resolved.startsWith(managedDataLayout.subjectsRoot())) {
				throw new IllegalArgumentException("Subject-first path resolves outside subjects/");
			}
			if (!Files.isRegularFile(resolved)) {
				blockers.add(new Blocker("MISSING_CURRENT_FILE",
						description + " uses Subject-first persistence but its file is missing.", resolved));
			}
		} catch (IllegalArgumentException exception) {
			blockers.add(new Blocker("UNSAFE_CURRENT_PATH",
					description + " has an invalid Subject-first path: " + exception.getMessage(), null));
		}
	}

	private void planCurriculumSources(List<Move> moves, List<Blocker> blockers, Set<Path> knownLegacyFiles,
			Map<Path, Move> destinationOwners) throws SQLException, IOException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    version.id AS syllabus_id,
						    version.syllabus_name,
						    version.source_pdf_path,
						    subject.subject_name
						FROM syllabus_versions version
						JOIN subjects subject
						    ON subject.id = version.subject_id
						WHERE version.source_pdf_path IS NOT NULL
						ORDER BY version.id
						""")) {
			while (result.next()) {
				long syllabusId = result.getLong("syllabus_id");
				String subjectName = result.getString("subject_name");
				String syllabusName = result.getString("syllabus_name");
				String relativePath = result.getString("source_pdf_path");
				if (isSubjectFirstPath(relativePath)) {
					planCurrentReference(relativePath, "Curriculum source PDF for syllabus " + syllabusId, blockers);
					continue;
				}
				Path source;
				try {
					source = resolveLegacyPath(config.curriculumDataRoot(), relativePath);
				} catch (IllegalArgumentException exception) {
					blockers.add(new Blocker("UNSAFE_LEGACY_PATH", "Curriculum source PDF " + syllabusId
							+ " has an unsafe legacy path: " + exception.getMessage(), null));
					continue;
				}
				knownLegacyFiles.add(source);
				Path fileName = source.getFileName();
				if (fileName == null) {
					blockers.add(new Blocker("INVALID_SOURCE_FILENAME",
							"Curriculum source PDF has no filename: " + relativePath, source));
					continue;
				}
				try {
					Path destination = managedDataLayout.curriculumSourceDirectory(subjectName, syllabusName)
							.resolve(fileName).normalize();
					addMove(AssetKind.CURRICULUM_SOURCE_PDF, syllabusId, source, destination, relativePath,
							managedDataLayout.relativePath(destination), moves, blockers, destinationOwners);
				} catch (IllegalArgumentException exception) {
					blockers.add(new Blocker(
							"INVALID_MANAGED_IDENTITY", "Curriculum source PDF " + syllabusId
									+ " cannot be assigned a Subject-first destination: " + exception.getMessage(),
							source));
				}
			}
		}
	}

	private void planExamSources(List<Move> moves, List<Blocker> blockers, Set<Path> knownLegacyFiles,
			Map<Path, Move> destinationOwners) throws SQLException, IOException {
		Map<Long, List<ExamSourceRow>> rowsBySource = new LinkedHashMap<>();
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    sd.id AS source_document_id,
						    sd.relative_path,
						    ownership.exam_id,
						    subject.subject_name,
						    provider.provider_name,
						    exam.exam_year,
						    exam.exam_name
						FROM source_documents sd
						LEFT JOIN (
						    SELECT source_document_id, exam_id
						    FROM exam_booklets
						    UNION
						    SELECT source_document_id, exam_id
						    FROM answer_files
						) ownership
						    ON ownership.source_document_id = sd.id
						LEFT JOIN exams exam
						    ON exam.id = ownership.exam_id
						LEFT JOIN subjects subject
						    ON subject.id = exam.subject_id
						LEFT JOIN exam_providers provider
						    ON provider.id = exam.provider_id
						ORDER BY sd.id, ownership.exam_id
						""")) {
			while (result.next()) {
				long examIdValue = result.getLong("exam_id");
				Long examId = result.wasNull() ? null : Long.valueOf(examIdValue);
				ExamSourceRow row = new ExamSourceRow(result.getLong("source_document_id"),
						result.getString("relative_path"), examId, result.getString("subject_name"),
						result.getString("provider_name"), result.getInt("exam_year"), result.getString("exam_name"));
				rowsBySource.computeIfAbsent(row.sourceDocumentId(), _ -> new ArrayList<>()).add(row);
			}
		}
		for (Map.Entry<Long, List<ExamSourceRow>> entry : rowsBySource.entrySet()) {
			long sourceDocumentId = entry.getKey();
			List<ExamSourceRow> rows = entry.getValue();
			ExamSourceRow first = rows.getFirst();
			if (isSubjectFirstPath(first.relativePath())) {
				planCurrentReference(first.relativePath(), "SourceDocument " + sourceDocumentId, blockers);
				continue;
			}
			Set<Long> examIds = new LinkedHashSet<>();
			for (ExamSourceRow row : rows) {
				if (row.examId() != null) {
					examIds.add(row.examId());
				}
			}
			if (examIds.isEmpty()) {
				blockers.add(new Blocker("UNOWNED_SOURCE_DOCUMENT",
						"Legacy SourceDocument " + sourceDocumentId + " is not owned by an Exam.", null));
				continue;
			}
			if (examIds.size() != 1) {
				blockers.add(new Blocker("SHARED_EXAM_SOURCE",
						"Legacy SourceDocument " + sourceDocumentId + " is shared by more than one Exam.", null));
				continue;
			}
			Path source;
			try {
				source = resolveLegacyPath(config.pdfDataRoot(), first.relativePath());
			} catch (IllegalArgumentException exception) {
				blockers.add(new Blocker("UNSAFE_LEGACY_PATH",
						"SourceDocument " + sourceDocumentId + " has an unsafe legacy path: " + exception.getMessage(),
						null));
				continue;
			}
			knownLegacyFiles.add(source);
			Path fileName = source.getFileName();
			if (fileName == null) {
				blockers.add(new Blocker("INVALID_SOURCE_FILENAME",
						"SourceDocument has no filename: " + first.relativePath(), source));
				continue;
			}
			try {
				Path destination = managedDataLayout
						.examDirectory(first.subjectName(), first.providerName(), first.year(), first.examName())
						.resolve(fileName).normalize();
				addMove(AssetKind.EXAM_PDF, sourceDocumentId, source, destination, first.relativePath(),
						managedDataLayout.relativePath(destination), moves, blockers, destinationOwners);
			} catch (IllegalArgumentException exception) {
				blockers.add(new Blocker(
						"INVALID_MANAGED_IDENTITY", "SourceDocument " + sourceDocumentId
								+ " cannot be assigned a Subject-first destination: " + exception.getMessage(),
						source));
			}
		}
	}

	private void planWorkbookAssignments(List<Move> moves, List<Blocker> blockers, Set<Path> knownLegacyFiles,
			Map<Path, Move> destinationOwners) throws SQLException, IOException {

		// After successful finalisation the legacy curriculum root no longer exists.
		// Re-running the same admin command with its historical workbook assignments
		// must therefore be a clean no-op rather than treating already-migrated
		// provenance as newly missing input.
		if (!Files.exists(config.curriculumDataRoot())) {
			return;
		}
		Set<Path> assignedSources = new HashSet<>();
		for (CurriculumWorkbookMigrationAssignment assignment : workbookAssignments) {
			Path source;
			try {
				source = resolveLegacyPath(config.curriculumDataRoot(), assignment.legacyRelativePath());
			} catch (IllegalArgumentException exception) {
				blockers.add(new Blocker("UNSAFE_WORKBOOK_ASSIGNMENT",
						"Legacy curriculum workbook assignment has an unsafe path: " + exception.getMessage(), null));
				continue;
			}
			if (!assignedSources.add(source)) {
				blockers.add(new Blocker("DUPLICATE_WORKBOOK_ASSIGNMENT",
						"Legacy curriculum workbook was assigned more than once.", source));
				continue;
			}
			knownLegacyFiles.add(source);
			if (!Files.isRegularFile(source)) {
				blockers.add(new Blocker("MISSING_WORKBOOK_SOURCE", "Assigned legacy curriculum workbook is missing.",
						source));
				continue;
			}
			Path fileName = source.getFileName();
			if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
				blockers.add(new Blocker("INVALID_WORKBOOK_SOURCE",
						"Assigned legacy curriculum source is not an .xlsx workbook.", source));
				continue;
			}
			if (!syllabusVersionExists(assignment.subjectName(), assignment.syllabusVersion())) {
				blockers.add(new Blocker("UNKNOWN_WORKBOOK_OWNER",
						"Assigned curriculum workbook owner does not identify a persisted Subject/version: "
								+ assignment.subjectName() + " / " + assignment.syllabusVersion(),
						source));
				continue;
			}
			try {
				Path destination = managedDataLayout
						.curriculumWorkbookDirectory(assignment.subjectName(), assignment.syllabusVersion())
						.resolve(fileName).normalize();
				addMove(AssetKind.CURRICULUM_WORKBOOK, 0, source, destination,
						assignment.legacyRelativePath().replace('\\', '/'), managedDataLayout.relativePath(destination),
						moves, blockers, destinationOwners);
			} catch (IllegalArgumentException exception) {
				blockers.add(new Blocker("INVALID_MANAGED_IDENTITY",
						"Assigned curriculum workbook cannot be placed in Subject-first storage: "
								+ exception.getMessage(),
						source));
			}
		}
	}

	private Path resolveLegacyPath(Path legacyRoot, String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("legacy persisted path must not be blank");
		}
		String portablePath = relativePath.replace('\\', '/');
		if (portablePath.startsWith("/") || isWindowsAbsolutePath(portablePath)) {
			throw new IllegalArgumentException("legacy persisted path must be relative");
		}
		if (containsTraversalComponent(portablePath)) {
			throw new IllegalArgumentException("legacy persisted path contains traversal components");
		}
		Path stored = Path.of(portablePath);
		if (stored.isAbsolute() || stored.getRoot() != null) {
			throw new IllegalArgumentException("legacy persisted path must be relative");
		}
		Path root = legacyRoot.toAbsolutePath().normalize();
		Path resolved = root.resolve(stored).normalize();
		if (resolved.equals(root) || !resolved.startsWith(root)) {
			throw new IllegalArgumentException("legacy persisted path escapes its managed root");
		}
		return resolved;
	}

	private void scanLegacyRoot(Path legacyRoot, Set<Path> knownLegacyFiles, boolean curriculumRoot,
			List<Path> archiveOnlyFiles, List<Blocker> blockers) throws IOException {
		Path root = legacyRoot.toAbsolutePath().normalize();
		if (!Files.exists(root)) {
			return;
		}
		if (Files.isSymbolicLink(root)) {
			blockers.add(new Blocker("SYMLINK_LEGACY_ROOT", "Legacy managed root is a symbolic link.", root));
			return;
		}
		try (var paths = Files.walk(root)) {
			paths.forEach(path -> {
				Path normalised = path.toAbsolutePath().normalize();
				if (normalised.equals(root)) {
					return;
				}
				if (Files.isSymbolicLink(path)) {
					blockers.add(new Blocker("SYMLINK_IN_LEGACY_ROOT", "Legacy managed data contains a symbolic link.",
							path));
					return;
				}
				if (!Files.isRegularFile(path)) {
					return;
				}
				if (knownLegacyFiles.contains(normalised)) {
					return;
				}
				if (curriculumRoot && path.getFileName() != null
						&& path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
					blockers.add(new Blocker("UNASSIGNED_CURRICULUM_WORKBOOK",
							"Legacy curriculum workbook requires an explicit Subject/version assignment.", path));
					return;
				}

				// Unknown legacy-root material is never discarded. Once all
				// authoritative moves verify, finalisation preserves it in the
				// pre-Subject-first recovery archive.
				archiveOnlyFiles.add(normalised);
			});
		}
	}

	private boolean syllabusVersionExists(String subjectName, String syllabusVersion) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM syllabus_versions version
						JOIN subjects subject
						    ON subject.id = version.subject_id
						WHERE subject.subject_name = ?
						  AND version.syllabus_name = ?
						LIMIT 1
						""")) {
			statement.setString(1, subjectName);
			statement.setString(2, syllabusVersion);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private record ExamSourceRow(long sourceDocumentId, String relativePath, Long examId, String subjectName,
			String providerName, int year, String examName) {
	}
}
