package au.edu.eq.questionbank.service.migration;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.AssetKind;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.Move;

/**
 * Applies one blocker-free Subject-first migration plan.
 */
public final class DataLayoutMigrationExecutor {

	private final SqliteDatabase database;
	private final ManagedDataLayout managedDataLayout;

	/**
	 * Creates a migration executor.
	 *
	 * @param config   application paths
	 * @param database current database
	 */
	public DataLayoutMigrationExecutor(ApplicationConfig config, SqliteDatabase database) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		managedDataLayout = new ManagedDataLayout(config.dataRoot());
	}

	/**
	 * Copies/verifies all planned files and publishes all persisted path rewrites
	 * in one SQLite transaction.
	 *
	 * @param plan blocker-free migration plan
	 * @return migration publication result
	 * @throws IOException  if file copying or verification fails
	 * @throws SQLException if database publication fails
	 */
	public DataLayoutMigrationResult apply(DataLayoutMigrationPlan plan) throws IOException, SQLException {
		if (plan == null) {
			throw new NullPointerException("plan");
		}
		if (!plan.canApply()) {
			throw new IllegalStateException(
					"Migration plan contains " + plan.blockers().size() + " unresolved blocker(s)");
		}
		int copiedFiles = 0;
		int reusedFiles = 0;
		for (Move move : plan.moves()) {
			if (copyAndVerify(move)) {
				reusedFiles++;
			} else {
				copiedFiles++;
			}
		}
		int updatedExamPaths = 0;
		int updatedCurriculumPaths = 0;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validateDatabaseDestinationCollisions(connection, plan);
				for (Move move : plan.moves()) {
					switch (move.assetKind()) {
					case EXAM_PDF -> {
						updateExamSourcePath(connection, move);
						updatedExamPaths++;
					}
					case CURRICULUM_SOURCE_PDF -> {
						updateCurriculumSourcePath(connection, move);
						updatedCurriculumPaths++;
					}
					case CURRICULUM_WORKBOOK -> {

						// Curriculum workbook provenance had no database path in
						// the legacy model. Its verified managed copy is enough.
					}
					}
				}
				verifyRewrittenReferences(connection, plan);
				connection.commit();
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		}
		return new DataLayoutMigrationResult(copiedFiles, reusedFiles, updatedExamPaths, updatedCurriculumPaths);
	}

	private boolean copyAndVerify(Move move) throws IOException {
		Path source = move.source().toAbsolutePath().normalize();
		Path destination = move.destination().toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Migration source is missing or is not a regular file: " + source);
		}
		String relativeDestination = managedDataLayout.relativePath(destination);
		if (!relativeDestination.equals(move.newRelativePath())) {
			throw new IllegalArgumentException(
					"Migration destination no longer matches planned persisted path: " + destination);
		}
		if (Files.exists(destination)) {
			verifyExistingDestination(source, destination);
			return true;
		}
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("Migration destination has no parent: " + destination);
		}
		Files.createDirectories(parent);
		Path temporary = parent.resolve(".migration-" + UUID.randomUUID() + ".tmp");
		try {
			Files.copy(source, temporary);
			if (Files.mismatch(source, temporary) != -1) {
				throw new IOException("Copied migration file failed byte verification: " + destination);
			}
			try {
				Files.move(temporary, destination);
			} catch (FileAlreadyExistsException race) {
				Files.deleteIfExists(temporary);
				verifyExistingDestination(source, destination);
				return true;
			}
			if (Files.mismatch(source, destination) != -1) {
				throw new IOException("Published migration destination failed byte verification: " + destination);
			}
			return false;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private String persistedCurriculumPath(Connection connection, long syllabusId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT source_pdf_path
				FROM syllabus_versions
				WHERE id = ?
				""")) {
			statement.setLong(1, syllabusId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Migration syllabus no longer exists: " + syllabusId);
				}
				return result.getString("source_pdf_path");
			}
		}
	}

	private String persistedExamPath(Connection connection, long sourceDocumentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT relative_path
				FROM source_documents
				WHERE id = ?
				""")) {
			statement.setLong(1, sourceDocumentId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Migration SourceDocument no longer exists: " + sourceDocumentId);
				}
				return result.getString("relative_path");
			}
		}
	}

	private void updateCurriculumSourcePath(Connection connection, Move move) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE syllabus_versions
				SET source_pdf_path = ?
				WHERE id = ?
				  AND source_pdf_path = ?
				""")) {
			statement.setString(1, move.newRelativePath());
			statement.setLong(2, move.persistentId());
			statement.setString(3, move.oldRelativePath());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Migration plan is stale for syllabus " + move.persistentId());
			}
		}
	}

	private void updateExamSourcePath(Connection connection, Move move) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE source_documents
				SET relative_path = ?
				WHERE id = ?
				  AND relative_path = ?
				""")) {
			statement.setString(1, move.newRelativePath());
			statement.setLong(2, move.persistentId());
			statement.setString(3, move.oldRelativePath());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Migration plan is stale for SourceDocument " + move.persistentId());
			}
		}
	}

	private void validateDatabaseDestinationCollisions(Connection connection, DataLayoutMigrationPlan plan)
			throws SQLException {
		for (Move move : plan.moves()) {
			if (move.assetKind() != AssetKind.EXAM_PDF) {
				continue;
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT id
					FROM source_documents
					WHERE relative_path = ?
					  AND id <> ?
					LIMIT 1
					""")) {
				statement.setString(1, move.newRelativePath());
				statement.setLong(2, move.persistentId());
				try (ResultSet result = statement.executeQuery()) {
					if (result.next()) {
						throw new IllegalStateException("Migration destination path is already owned by SourceDocument "
								+ result.getLong("id") + ": " + move.newRelativePath());
					}
				}
			}
		}
	}

	private void verifyExistingDestination(Path source, Path destination) throws IOException {
		if (!Files.isRegularFile(destination)) {
			throw new IOException("Migration destination exists but is not a regular file: " + destination);
		}
		if (Files.mismatch(source, destination) != -1) {
			throw new IOException("Migration destination contains different bytes: " + destination);
		}
	}

	private void verifyManagedBytes(Move move) {
		Path resolved = managedDataLayout.resolve(move.newRelativePath());
		if (!resolved.equals(move.destination())) {
			throw new IllegalStateException(
					"Migration path resolves to unexpected destination: " + move.newRelativePath());
		}
		if (!Files.isRegularFile(resolved)) {
			throw new IllegalStateException("Migrated managed file is missing: " + resolved);
		}
		try {
			if (Files.mismatch(move.source(), resolved) != -1) {
				throw new IllegalStateException("Migrated managed file does not match its legacy source: " + resolved);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Could not verify migrated managed bytes: " + resolved, exception);
		}
	}

	private void verifyRewrittenReferences(Connection connection, DataLayoutMigrationPlan plan) throws SQLException {
		for (Move move : plan.moves()) {
			if (move.assetKind() == AssetKind.CURRICULUM_WORKBOOK) {
				verifyManagedBytes(move);
				continue;
			}
			String persistedPath;
			switch (move.assetKind()) {
			case EXAM_PDF -> persistedPath = persistedExamPath(connection, move.persistentId());
			case CURRICULUM_SOURCE_PDF -> persistedPath = persistedCurriculumPath(connection, move.persistentId());
			case CURRICULUM_WORKBOOK -> throw new IllegalStateException("Unexpected workbook persistence verification");
			default -> throw new IllegalStateException("Unsupported migration asset kind");
			}
			if (!move.newRelativePath().equals(persistedPath)) {
				throw new IllegalStateException("Migration persistence verification failed for " + move.assetKind()
						+ " " + move.persistentId());
			}
			verifyManagedBytes(move);
		}
	}
}
