package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;

/**
 * Produces verified fingerprints for complete Subject snapshot archives.
 *
 * <p>
 * The semantic content fingerprint covers the Subject identity and all verified
 * manifest entries, including portable record revisions, logical asset
 * references and physical asset checksums.
 *
 * <p>
 * Package identity and creation time are deliberately excluded from the
 * semantic fingerprint. An independent full-archive SHA-256 binds approval to
 * the exact incoming ZIP bytes.
 *
 * <p>
 * Every inspection first invokes the common archive validator.
 */
public final class SubjectImportSnapshotFingerprint {

	private final ExamWorkArchiveValidator validator = new ExamWorkArchiveValidator();

	/**
	 * Creates a Subject snapshot fingerprint service using the standard ExamWork
	 * archive validator.
	 */
	public SubjectImportSnapshotFingerprint() {
	}

	/**
	 * Inspects one immutable, private copy of a Subject archive.
	 *
	 * <p>
	 * Both validation and fingerprint calculation use the same staged archive
	 * bytes. Replacement of the external source during or after staging cannot
	 * change the verified content.
	 *
	 * @param archive existing Subject snapshot archive
	 * @return verified package and content fingerprints
	 * @throws IOException if copying, validation or reading fails
	 */
	public Fingerprint inspect(Path archive) throws IOException {
		Objects.requireNonNull(archive, "archive");
		if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new PackageFormatException("Subject archive must be a regular file");
		}
		if (Files.size(archive) > 512L * 1024 * 1024) {
			throw new PackageFormatException("Subject archive exceeds maximum compressed size");
		}
		Path directory = Files.createTempDirectory("examwork-fingerprint-");
		Path staged = directory.resolve("verified.examwork");
		try {

			// Do not follow source symbolic links. Validation and digest
			// calculation both operate on this private staged file.
			Files.copy(archive, staged, LinkOption.NOFOLLOW_LINKS);
			ExamWorkManifest manifest = validator.validate(staged, ApplicationVersion.current(),
					SqliteDatabase.latestSchemaVersion());
			if (manifest.packageType() != ExamWorkPackageType.SUBJECT_SNAPSHOT
					|| manifest.scope().level() != ExamWorkManifest.ScopeLevel.SUBJECT) {
				throw new PackageFormatException("Subject import requires a complete Subject snapshot");
			}
			MessageDigest digest = newDigest();
			update(digest, "subject-import-content-v1");
			update(digest, Integer.toString(manifest.formatVersion()));
			update(digest, manifest.originInstallationId().toString());
			update(digest, manifest.subjectId().toString());
			update(digest, manifest.subjectName());

			// All manifest entries have already been checked against the
			// actual bytes of this staged archive.
			for (Entry entry : manifest.entries().stream().sorted(Comparator.comparing(Entry::path)).toList()) {
				update(digest, entry.path());
				update(digest, entry.role().name());
				update(digest, Long.toString(entry.size()));
				update(digest, entry.sha256());
			}
			return new Fingerprint(manifest.packageId(), manifest.originInstallationId(), manifest.subjectId(),
					manifest.subjectName(), HexFormat.of().formatHex(digest.digest()), archiveSha256(staged));
		} finally {
			try {
				Files.deleteIfExists(staged);
			} finally {
				Files.deleteIfExists(directory);
			}
		}
	}

	/**
	 * Requires a newly exported master snapshot to contain exactly the same
	 * relevant Subject state as the snapshot originally reviewed.
	 *
	 * <p>
	 * This comparison ignores regenerated package UUIDs and ZIP metadata. It does
	 * not replace the final transactional alias and dependency checks required
	 * during import publication.
	 *
	 * @param expected reviewed master fingerprint
	 * @param archive  fresh master Subject snapshot
	 * @throws IOException           if archive validation fails
	 * @throws IllegalStateException if the Subject state has changed
	 */
	public void requireSameContent(Fingerprint expected, Path archive) throws IOException {
		Objects.requireNonNull(expected, "expected");
		Fingerprint actual = inspect(archive);
		if (!expected.originInstallationId().equals(actual.originInstallationId())
				|| !expected.subjectId().equals(actual.subjectId())
				|| !expected.subjectName().equals(actual.subjectName())
				|| !expected.contentSha256().equals(actual.contentSha256())) {
			throw new IllegalStateException("Master Subject has changed since coordinator review");
		}
	}

	/**
	 * Requires the exact approved incoming archive to remain unchanged.
	 *
	 * @param expected originally inspected incoming archive
	 * @param archive  incoming archive being revalidated
	 * @throws IOException           if archive validation fails
	 * @throws IllegalStateException if the package was replaced or changed
	 */
	public void requireSamePackage(Fingerprint expected, Path archive) throws IOException {
		Objects.requireNonNull(expected, "expected");
		Fingerprint actual = inspect(archive);
		if (!expected.equals(actual)) {
			throw new IllegalStateException("Incoming Subject package differs from the reviewed archive");
		}
	}

	/**
	 * Calculates the SHA-256 digest of the complete archive bytes.
	 */
	private String archiveSha256(Path archive) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(archive)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Creates a fresh SHA-256 digest implementation.
	 */
	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * Adds one length-delimited UTF-8 field to a semantic digest.
	 *
	 * <p>
	 * Length prefixes avoid ambiguous concatenations of adjacent values.
	 */
	private void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	/**
	 * Immutable identities of a validated Subject snapshot.
	 *
	 * @param packageId            archive package UUID
	 * @param originInstallationId originating installation UUID
	 * @param subjectId            portable Subject UUID
	 * @param subjectName          Subject name
	 * @param contentSha256        canonical Subject-content fingerprint
	 * @param archiveSha256        exact archive-byte fingerprint
	 */
	public record Fingerprint(UUID packageId, UUID originInstallationId, UUID subjectId, String subjectName,
			String contentSha256, String archiveSha256) {

		/**
		 * Validates a complete fingerprint.
		 */
		public Fingerprint {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(originInstallationId, "originInstallationId");
			Objects.requireNonNull(subjectId, "subjectId");
			if (subjectName == null || subjectName.isBlank()) {
				throw new IllegalArgumentException("Subject name must not be blank");
			}
			if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}") || archiveSha256 == null
					|| !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Snapshot fingerprints must be SHA-256 digests");
			}
		}
	}
}
