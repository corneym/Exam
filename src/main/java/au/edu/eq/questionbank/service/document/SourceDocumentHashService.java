package au.edu.eq.questionbank.service.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Calculates stable SHA-256 identities for managed source-document bytes.
 * <p>
 * Hashing is deliberately independent of filenames and persisted paths so the
 * same document can be recognised after a rename, move or duplicate selection.
 */
public final class SourceDocumentHashService {

	/**
	 * Calculates the SHA-256 digest of an existing regular file.
	 *
	 * @param sourcePath file whose bytes will be hashed
	 * @return exactly 64 lower-case hexadecimal SHA-256 characters
	 * @throws IOException          if the source is not a regular file or its bytes
	 *                              cannot be read
	 * @throws NullPointerException if {@code sourcePath} is {@code null}
	 */
	public String sha256(Path sourcePath) throws IOException {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		Path source = sourcePath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Source document is not a regular file: " + source);
		}
		MessageDigest digest = newSha256Digest();

		// Stream the file rather than loading an entire examination PDF into memory.
		byte[] buffer = new byte[8192];
		try (InputStream input = Files.newInputStream(source)) {
			int bytesRead;
			while ((bytesRead = input.read(buffer)) != -1) {
				digest.update(buffer, 0, bytesRead);
			}
		}

		// HexFormat emits the canonical lower-case representation expected by the
		// domain object and schema-v16 constraint.
		return HexFormat.of().formatHex(digest.digest());
	}

	private MessageDigest newSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {

			// Every supported Java runtime is required to provide SHA-256. Treat its
			// absence as an unusable runtime rather than a recoverable document error.
			throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", exception);
		}
	}
}
