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
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;

/**
 * Verifies embedded PNG dependencies of a received contribution.
 *
 * <p>
 * Checks issued Question images and Shared Context records against their
 * original master baseline. Also verifies the actual returned PNG bytes against
 * their declared asset identities.
 *
 * <p>
 * Archive integrity and portable dependency closure must already have been
 * established by the contribution inspector.
 *
 * <p>
 * This class is read-only. It does not authorise image creation, replace
 * embedded content or publish managed filesystem assets.
 */
final class MasterContributionEmbeddedImagePreflight {

	private static final long MAX_PNG_BYTES = 256L * 1024L * 1024L;
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private final SqliteDatabase database;

	MasterContributionEmbeddedImagePreflight(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	void verify(WorkAssignmentLedger.Baseline baseline, ContributionArchiveInspector.InspectedContribution inspected,
			Path archive) throws SQLException, IOException {
		Objects.requireNonNull(baseline, "baseline");
		Objects.requireNonNull(inspected, "inspected");
		Objects.requireNonNull(archive, "archive");
		if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Received image archive is missing or unsafe");
		}
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> issuedRecords = codec.readRecords(new ByteArrayInputStream(baseline.records()));
		ExamWorkAssetIndex issuedAssets = codec.readAssetIndex(new ByteArrayInputStream(baseline.assets()));
		verifyIssuedMaster(issuedRecords);
		String subjectName = inspected.manifest().subjectName();

		// Both the original asset paths and returned asset paths
		// must use the canonical package-only embedded namespace.
		for (Reference reference : issuedAssets.references()) {
			if (isEmbedded(reference)) {
				requireCanonicalReference(reference, subjectName);
			}
		}
		for (Reference reference : inspected.assets().references()) {
			if (isEmbedded(reference)) {
				requireCanonicalReference(reference, subjectName);
			}
		}
		verifyReturnedPngs(inspected.assets(), archive);

		// Detect a replacement of the archive after its original
		// receipt inspection, even if the embedded entries happened
		// to be copied unchanged.
		if (!digest(archive).equals(inspected.archiveSha256())) {
			throw new IOException("Contribution archive changed during " + "embedded asset verification");
		}
	}

	private String digest(Path file) throws IOException {
		MessageDigest sha256 = newDigest();
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				sha256.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(sha256.digest());
	}

	private boolean isEmbedded(Reference reference) {
		return "EMBEDDED_QUESTION_PNG".equals(reference.usage()) || "EMBEDDED_CONTEXT_PNG".equals(reference.usage());
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private void requireCanonicalReference(Reference reference, String subjectName) throws IOException {
		if (!"image/png".equals(reference.mediaType()) || reference.ownerId() == null) {
			throw new IOException("Embedded asset has invalid PNG ownership");
		}
		String prefix = "subjects/" + subjectName + "/exams/_examwork_embedded/";
		if ("EMBEDDED_QUESTION_PNG".equals(reference.usage())) {
			String expected = prefix + "QUESTION_IMAGE/" + reference.ownerId() + ".png";
			if (!"QUESTION_IMAGE".equals(reference.ownerType()) || !expected.equals(reference.managedPath())) {
				throw new IOException("Question image has invalid portable " + "ownership or logical path");
			}
			return;
		}
		if (!"EMBEDDED_CONTEXT_PNG".equals(reference.usage())) {
			throw new IOException("Unsupported embedded image usage");
		}
		String expectedPrefix = prefix + "SHARED_QUESTION_CONTEXT/" + reference.ownerId() + "-";
		if (!"SHARED_QUESTION_CONTEXT".equals(reference.ownerType())
				|| !reference.managedPath().startsWith(expectedPrefix)) {
			throw new IOException("Shared Context image has invalid ownership " + "or logical path");
		}
		String suffix = reference.managedPath().substring(expectedPrefix.length());
		if (!suffix.matches("(0|[1-9][0-9]*)\\.png")) {
			throw new IOException("Shared Context image order is invalid");
		}
		try {
			String order = suffix.substring(0, suffix.length() - 4);
			long parsed = Long.parseLong(order);
			if (parsed > Integer.MAX_VALUE) {
				throw new NumberFormatException("Image order exceeds integer range");
			}
		} catch (NumberFormatException failure) {
			throw new IOException("Shared Context image order is invalid", failure);
		}
	}

	private void verifyIssuedMaster(List<ExchangeRecord> baseline) throws SQLException, IOException {
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				for (ExchangeRecord original : baseline) {
					EntityType type;
					switch (original.recordType()) {
					case "QUESTION":
						type = EntityType.QUESTION;
						break;
					case "QUESTION_IMAGE":
						type = EntityType.QUESTION_IMAGE;
						break;
					case "SHARED_QUESTION_CONTEXT":
						type = EntityType.SHARED_QUESTION_CONTEXT;
						break;
					default:
						continue;
					}
					long localId = identities.findByPortableId(connection, type, original.recordId())
							.orElseThrow(() -> new SQLException("Issued master image " + "dependency is missing"))
							.localId();
					ExchangeRecord current = content.snapshot(connection, type, localId).record();

					// For Questions, protect the embedded-image
					// collection independently from ordinary
					// Question scalar review.
					if (type == EntityType.QUESTION) {
						if (!current.values().path("question_images")
								.equals(original.values().path("question_images"))) {
							throw new IOException(
									"Master Question images differ " + "from issued baseline: " + original.recordId());
						}
						continue;
					}

					// Standalone Question images and Shared Context
					// records must retain the original master
					// revision and complete content, including
					// SHA-256 representations of embedded BLOBs.
					if (!current.recordId().equals(original.recordId())
							|| !current.revisionId().equals(original.revisionId())
							|| !current.values().equals(original.values())) {
						throw new IOException("Master embedded content differs " + "from issued baseline: " + type + ":"
								+ original.recordId());
					}
				}
				connection.commit();
			} catch (SQLException | IOException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	private void verifyPngBytes(InputStream input, Reference reference) throws IOException {
		MessageDigest sha256 = newDigest();
		byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
		if (!Arrays.equals(signature, PNG_SIGNATURE)) {
			throw new IOException("Embedded image has an invalid PNG signature: " + reference.managedPath());
		}
		sha256.update(signature);
		long length = signature.length;
		byte[] buffer = new byte[8192];
		for (int count; (count = input.read(buffer)) != -1;) {
			length += count;
			if (length > MAX_PNG_BYTES || length > reference.size()) {
				throw new IOException("Embedded PNG exceeds declared size: " + reference.managedPath());
			}
			sha256.update(buffer, 0, count);
		}
		String actualHash = HexFormat.of().formatHex(sha256.digest());
		if (length != reference.size() || !actualHash.equals(reference.sha256())) {
			throw new IOException(
					"Embedded PNG bytes differ from declared " + "asset evidence: " + reference.managedPath());
		}
	}

	private void verifyReturnedPngs(ExamWorkAssetIndex assets, Path archive) throws IOException {
		Map<String, String> verified = new HashMap<>();
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			for (Reference reference : assets.references()) {
				if (!isEmbedded(reference)) {
					continue;
				}

				// Content-addressed entries may be shared by
				// several logical image owners. Read each physical
				// archive entry once, but never assume that a shared
				// digest establishes shared ownership.
				String previous = verified.get(reference.archivePath());
				if (previous != null) {
					if (!previous.equals(reference.sha256())) {
						throw new IOException("Conflicting embedded asset hashes");
					}
					continue;
				}
				ZipArchiveEntry entry = zip.getEntry(reference.archivePath());
				if (entry == null || entry.isDirectory()) {
					throw new IOException("Embedded PNG archive entry is missing: " + reference.archivePath());
				}
				if (entry.getSize() != reference.size()) {
					throw new IOException("Embedded PNG archive entry size differs " + "from the asset index");
				}
				try (InputStream input = zip.getInputStream(entry)) {
					verifyPngBytes(input, reference);
				}
				verified.put(reference.archivePath(), reference.sha256());
			}
		}
	}
}
