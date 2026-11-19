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
 * immutable issuance baseline and the locked master installation.
 *
 * <p>
 * Source Question/Answer PDFs and syllabus PDFs must retain their originally
 * issued paths and bytes. Existing embedded Question PNGs and Shared Context
 * content must retain their issued master values and revision evidence.
 *
 * <p>
 * Returned embedded PNG entries are checked against their asset references,
 * canonical ownership paths, expected size, SHA-256 and PNG file signature.
 *
 * <p>
 * This is a read-only verification boundary. The calling application
 * coordinator holds the managed-asset publication lock throughout verification
 * and subsequent SQLite reconciliation. This class does not publish assets or
 * authorise new image database writes.
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
	 * Verifies one received contribution and all issued binary dependencies.
	 *
	 * <p>
	 * The original archive must match its durable intake evidence. Issued master
	 * PDFs and embedded images must remain unchanged. Returned PNG entries must
	 * match their verified portable asset index.
	 *
	 * <p>
	 * New and replaced source PDFs are unsupported. New or modified embedded images
	 * may pass preflight but still require a separately authorised reviewed
	 * database write before they can be accepted.
	 *
	 * @param packageId durable received contribution package UUID
	 * @param archive   original durably received contribution archive
	 * @throws SQLException         if assignment, receipt, identity or master
	 *                              persistence evidence cannot be read
	 * @throws IOException          if archive contents, asset references or master
	 *                              binary dependencies are inconsistent
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
			throw new IOException("Contribution archive differs from " + "durable intake evidence");
		}
		WorkAssignmentLedger.Baseline baseline = new WorkAssignmentLedger(database)
				.readBaseline(received.assignmentId());
		ExamWorkAssetIndex issuedIndex = new ExamWorkJsonlCodec()
				.readAssetIndex(new ByteArrayInputStream(baseline.assets()));
		Map<String, Reference> issuedPdfs = pdfReferences(issuedIndex);
		Map<String, Reference> receivedPdfs = pdfReferences(inspected.assets());
		if (!issuedPdfs.keySet().equals(receivedPdfs.keySet())) {
			throw new IOException("Contribution PDF dependency set differs " + "from issuance");
		}
		for (Map.Entry<String, Reference> entry : issuedPdfs.entrySet()) {
			Reference original = entry.getValue();
			Reference returned = receivedPdfs.get(entry.getKey());
			if (!original.managedPath().equals(returned.managedPath()) || !original.sha256().equals(returned.sha256())
					|| original.size() != returned.size() || !original.mediaType().equals(returned.mediaType())) {
				throw new IOException(
						"Contribution source PDF differs " + "from issued bytes: " + original.managedPath());
			}
			requireMasterPdfMatches(original);
		}

		// The returned image assets have already passed ordinary
		// ZIP and graph-closure validation. Recheck actual embedded
		// bytes and current locked-master baseline here, under
		// the caller's managed-asset publication lock.
		new MasterContributionEmbeddedImagePreflight(database).verify(baseline, inspected, archive);
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
