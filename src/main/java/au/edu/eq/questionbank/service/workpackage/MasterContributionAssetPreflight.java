package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;

/**
 * Verifies the binary dependencies of a received contribution against its
 * immutable issuance baseline and the current master data root.
 *
 * <p>
 * The original archive must still match its durable intake receipt. Existing
 * Question/Answer source PDFs and syllabus PDFs must have the exact bytes
 * issued to the contributor. Embedded binary assets remain inside the validated
 * archive and are not published here.
 *
 * <p>
 * This is a read-only verification boundary. The calling application
 * coordinator must retain the managed-asset publication lock across
 * verification and any subsequent application.
 */
public final class MasterContributionAssetPreflight {

	private final SqliteDatabase database;
	private final ManagedDataLayout layout;

	/**
	 * Creates a verifier for an authoritative master installation.
	 *
	 * @param database master SQLite database
	 * @param layout   master managed-data layout
	 * @throws NullPointerException if either argument is null
	 */
	public MasterContributionAssetPreflight(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
	}

	/**
	 * Verifies one received return's archive and existing PDF dependencies.
	 *
	 * <p>
	 * Source files are checked against the original issued asset index, the
	 * received asset index, their master SourceDocument or syllabus metadata, and
	 * the current physical file bytes.
	 *
	 * <p>
	 * New or replaced source PDFs are not accepted through this method. Their
	 * conflict and publication handling requires a separate approved asset-import
	 * transaction.
	 *
	 * @param packageId durable received contribution package ID
	 * @param archive   original contribution archive
	 * @throws SQLException         if assignment, receipt or master identity
	 *                              evidence cannot be read
	 * @throws IOException          if archive contents or source assets differ from
	 *                              the original approved dependency evidence
	 * @throws NullPointerException if either argument is null
	 */
	public void verify(UUID packageId, Path archive) throws SQLException, IOException {
		Objects.requireNonNull(packageId, "packageId");
		Objects.requireNonNull(archive, "archive");
		if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Received contribution archive is missing or unsafe");
		}
		MasterContributionIntake.ReceivedPackage received = new MasterContributionIntake(database).find(packageId)
				.orElseThrow(() -> new SQLException("Return has not been received: " + packageId));
		var inspected = new ContributionArchiveInspector(database).inspect(archive);
		if (!packageId.equals(inspected.manifest().packageId())
				|| !received.assignmentId().equals(inspected.assignment().assignmentId())
				|| !received.archiveSha256().equals(inspected.archiveSha256())) {
			throw new IOException("Contribution archive differs from durable intake evidence");
		}
		byte[] issuedIndexBytes = new WorkAssignmentLedger(database).readBaseline(received.assignmentId()).assets();
		ExamWorkAssetIndex issuedIndex = new ExamWorkJsonlCodec()
				.readAssetIndex(new ByteArrayInputStream(issuedIndexBytes));
		Map<String, Reference> issuedPdfs = pdfReferences(issuedIndex);
		Map<String, Reference> receivedPdfs = pdfReferences(inspected.assets());

		// A return cannot silently remove an issued PDF dependency
		// or introduce a different source PDF.
		if (!issuedPdfs.keySet().equals(receivedPdfs.keySet())) {
			throw new IOException("Contribution PDF dependency set differs from issuance");
		}
		for (Map.Entry<String, Reference> entry : issuedPdfs.entrySet()) {
			Reference original = entry.getValue();
			Reference returned = receivedPdfs.get(entry.getKey());
			if (!original.managedPath().equals(returned.managedPath()) || !original.sha256().equals(returned.sha256())
					|| original.size() != returned.size() || !original.mediaType().equals(returned.mediaType())) {
				throw new IOException("Contribution source PDF differs from issued bytes: " + original.managedPath());
			}
			requireMasterPdfMatches(original);
		}
	}

	private String digest(Path file) throws IOException {
		MessageDigest sha256;
		try {
			sha256 = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				sha256.update(buffer, 0, count);
			}
		}
		return java.util.HexFormat.of().formatHex(sha256.digest());
	}

	private Map<String, Reference> pdfReferences(ExamWorkAssetIndex index) throws IOException {
		Map<String, Reference> references = new HashMap<>();
		for (Reference reference : index.references()) {
			if (!"SOURCE_PDF".equals(reference.usage()) && !"SYLLABUS_PDF".equals(reference.usage())) {
				continue;
			}
			String expectedOwnerType = "SOURCE_PDF".equals(reference.usage()) ? "SOURCE_DOCUMENT" : "SYLLABUS_VERSION";
			if (!expectedOwnerType.equals(reference.ownerType()) || reference.ownerId() == null
					|| !"application/pdf".equals(reference.mediaType())) {
				throw new IOException("PDF asset has an invalid owner or media type");
			}
			String key = reference.usage() + ":" + reference.ownerId();
			if (references.putIfAbsent(key, reference) != null) {
				throw new IOException("Duplicate source PDF dependency: " + key);
			}
		}
		return Map.copyOf(references);
	}

	private void requireMasterPdfMatches(Reference reference) throws SQLException, IOException {
		boolean source = "SOURCE_PDF".equals(reference.usage());
		EntityType type = source ? EntityType.SOURCE_DOCUMENT : EntityType.SYLLABUS_VERSION;
		long localId = new SqlitePortableIdentityRepository(database).findByPortableId(type, reference.ownerId())
				.orElseThrow(() -> new SQLException("Master PDF owner identity is missing")).localId();
		String sql = source ? """
				SELECT relative_path AS stored_path,
				       content_sha256 AS expected_hash
				FROM source_documents
				WHERE id = ?
				""" : """
				SELECT source_pdf_path AS stored_path,
				       NULL AS expected_hash
				FROM syllabus_versions
				WHERE id = ?
				""";
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Master PDF owner is missing");
				}
				String storedPath = result.getString("stored_path");
				String expectedHash = result.getString("expected_hash");
				if (!reference.managedPath().equals(storedPath)) {
					throw new IOException("Master PDF path differs from issued dependency");
				}
				if (expectedHash != null && !expectedHash.equals(reference.sha256())) {
					throw new IOException("Persisted master PDF digest differs " + "from issued dependency");
				}
				if (result.next()) {
					throw new SQLException("Duplicate master PDF owner");
				}
			}
		}
		Path managedFile;
		try {
			managedFile = layout.resolve(reference.managedPath());
		} catch (IllegalArgumentException failure) {
			throw new IOException("Unsafe master PDF path", failure);
		}
		requireSafeMasterFile(managedFile);
		if (Files.size(managedFile) != reference.size() || !digest(managedFile).equals(reference.sha256())) {
			throw new IOException("Master PDF bytes differ from issued dependency: " + reference.managedPath());
		}
	}

	private void requireSafeMasterFile(Path file) throws IOException {
		Path root = layout.dataRoot();
		if (!file.startsWith(root) || Files.isSymbolicLink(root)) {
			throw new IOException("Unsafe master managed-data root");
		}
		Path current = root;

		// Reject symbolic links in every path component, not just
		// in the final PDF, to avoid escaping the managed root.
		for (Path component : root.relativize(file)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Symbolic link in master PDF path: " + current);
			}
		}
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Master source PDF is missing or unsafe: " + file);
		}
	}
}
