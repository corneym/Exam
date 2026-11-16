package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/** Authoritative file references held stable by a SQLite writer reservation. */
final class ManagedAssetRecoveryReferences implements AutoCloseable {

	private final Connection connection;
	private final Set<Path> references = new HashSet<>();

	ManagedAssetRecoveryReferences(ApplicationConfig config, ManagedAssetPublicationLock lock)
			throws IOException, SQLException {
		lock.requireHeld(config.dataRoot());
		if (!Files.isRegularFile(config.databasePath(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Recovery requires the existing authoritative database");
		}
		connection = new SqliteDatabase(config.databasePath()).openConnection();
		try {
			connection.setAutoCommit(false);
			try (var statement = connection.createStatement()) {

				// Reserve before reading: no other operation can commit a new reference
				// between this inventory and the final filesystem deletion.
				if (statement.executeUpdate("UPDATE installation_identity SET installation_id = installation_id "
						+ "WHERE singleton_id = 1") != 1) {
					throw new SQLException("Cannot establish authoritative recovery reference state");
				}
			}
			readReferences(config);
		} catch (IOException | SQLException | RuntimeException failure) {
			try {
				connection.close();
			} catch (SQLException closeFailure) {
				failure.addSuppressed(closeFailure);
			}
			throw failure;
		}
	}

	@Override
	public void close() throws SQLException {
		try {
			connection.rollback();
		} finally {
			connection.close();
		}
	}

	boolean contains(Path destination) {
		return references.contains(destination.toAbsolutePath().normalize());
	}

	private void readReferences(ApplicationConfig config) throws IOException, SQLException {
		ManagedDataLayout layout = new ManagedDataLayout(config.dataRoot());
		try (var statement = connection.createStatement()) {
			try (var rows = statement.executeQuery("SELECT relative_path FROM source_documents")) {
				while (rows.next()) {
					references.add(layout.resolve(rows.getString(1)));
				}
			}
			try (var rows = statement
					.executeQuery("SELECT source_pdf_path FROM syllabus_versions WHERE source_pdf_path IS NOT NULL")) {
				while (rows.next()) {
					references.add(layout.resolve(rows.getString(1)));
				}
			}
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new IOException("Cannot establish ownership: unsafe persisted managed-file reference", failure);
		}
		for (Path reference : references) {
			requireUnambiguousPath(config.dataRoot(), reference);
		}
	}

	private void requireUnambiguousPath(Path root, Path reference) throws IOException {
		Path current = root.toAbsolutePath().normalize();
		for (Path component : current.relativize(reference)) {
			current = current.resolve(component);
			try {
				var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (attributes.isSymbolicLink() || attributes.isOther()) {
					throw new IOException("Cannot establish ownership through an indirect managed path: " + current);
				}
			} catch (NoSuchFileException absent) {

				// A missing ordinary component cannot alias another managed destination.
				return;
			}
		}
	}
}
