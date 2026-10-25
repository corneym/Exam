package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Publishes and verifies immutable file digests for one permanent historical
 * performance session. Never touches raw session files.
 */
public final class PerformanceHistoryIntegrity {

	/** Name of the independently verifiable history manifest. */
	public static final String MANIFEST_NAME = "session.integrity";
	private static final Pattern SUMMARY_NAME = Pattern.compile("checkpoint-[0-9]{6}\\.csv");

	/** Creates a historical-summary integrity verifier. */
	public PerformanceHistoryIntegrity() {
	}

	private static String expectedManifest(Path directory) throws IOException {
		Objects.requireNonNull(directory, "historySessionDirectory");
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe historical session directory");
		}
		UUID sessionId;
		UUID machineId;
		try {
			sessionId = UUID.fromString(directory.getFileName().toString());
			machineId = UUID.fromString(directory.getParent().getFileName().toString());
		} catch (IllegalArgumentException | NullPointerException invalid) {
			throw new IOException("Invalid historical machine/session identity", invalid);
		}
		if (!sessionId.toString().equals(directory.getFileName().toString())
				|| !machineId.toString().equals(directory.getParent().getFileName().toString())) {
			throw new IOException("Noncanonical historical identity");
		}
		List<Path> files = new ArrayList<>();
		int summaries = 0;
		try (Stream<Path> contents = Files.list(directory)) {
			for (Path file : contents.toList()) {
				String filename = file.getFileName().toString();
				if (MANIFEST_NAME.equals(filename)) {
					if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
						throw new IOException("Unsafe history integrity manifest");
					}
					continue;
				}
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Unsafe historical file: " + filename);
				}
				if (SUMMARY_NAME.matcher(filename).matches()) {
					summaries++;
				} else if (!"corpus-checkpoints.csv".equals(filename) && !"session.summary-complete".equals(filename)) {
					throw new IOException("Unexpected historical file: " + filename);
				}
				files.add(file);
			}
		}
		Path receipt = directory.resolve("session.summary-complete");
		Path checkpoints = directory.resolve("corpus-checkpoints.csv");
		if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(checkpoints, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing permanent history receipt or checkpoints");
		}
		Properties completion = new Properties();
		try (InputStream input = Files.newInputStream(receipt)) {
			completion.load(input);
		}
		int expectedSummaries;
		try {
			expectedSummaries = Integer.parseInt(completion.getProperty("summaries", "-1"));
		} catch (NumberFormatException invalid) {
			throw new IOException("Invalid receipt summary count", invalid);
		}
		if (expectedSummaries < 0 || expectedSummaries != summaries
				|| !sessionId.toString().equals(completion.getProperty("sessionId"))
				|| !machineId.toString().equals(completion.getProperty("machineId"))) {
			throw new IOException("Historical receipt and files do not agree");
		}
		files.sort(Comparator.comparing(path -> path.getFileName().toString()));
		StringBuilder manifest = new StringBuilder();
		manifest.append("version=1\n").append("machineId=").append(machineId).append('\n').append("sessionId=")
				.append(sessionId).append('\n').append("fileCount=").append(files.size()).append('\n');
		for (Path file : files) {
			manifest.append("sha256.").append(file.getFileName()).append('=').append(sha256(file)).append('\n');
		}
		return manifest.toString();
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
				input.transferTo(java.io.OutputStream.nullOutputStream());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 unavailable", unavailable);
		}
	}

	/**
	 * Creates a new immutable integrity manifest, or checks an existing one. The
	 * publisher must call this only after it successfully verified all regenerated
	 * historical summaries and published its completion receipt.
	 *
	 * @param historySessionDirectory machine/session history directory
	 * @throws IOException if the history is incomplete or conflicts
	 */
	public void publish(Path historySessionDirectory) throws IOException {
		String expected = expectedManifest(historySessionDirectory);
		Path destination = historySessionDirectory.resolve(MANIFEST_NAME);
		if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {

			// Atomic destination creation: never replace a prior manifest.
			try {
				Files.writeString(destination, expected, java.nio.charset.StandardCharsets.UTF_8,
						java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			} catch (FileAlreadyExistsException concurrent) {

				// Another publisher may have created the immutable manifest.
				// Verification below determines whether it agrees.
			}
		}
		verify(historySessionDirectory);
	}

	/**
	 * Verifies every permanent history file and its immutable manifest.
	 *
	 * @param historySessionDirectory machine/session history directory
	 * @throws IOException if the manifest is missing, corrupt or inconsistent
	 */
	public void verify(Path historySessionDirectory) throws IOException {
		String expected = expectedManifest(historySessionDirectory);
		Path marker = historySessionDirectory.resolve(MANIFEST_NAME);
		if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !expected.equals(Files.readString(marker))) {
			throw new IOException("Historical integrity manifest is missing or conflicting");
		}
	}

	/**
	 * Locates the one machine/session history directory and verifies it. The method
	 * rejects ambiguous UUIDs across imported machine histories.
	 *
	 * @param diagnosticsRoot diagnostics directory
	 * @param sessionId       recorded raw-session UUID
	 * @throws IOException if no unique valid permanent history exists
	 */
	public void verifySession(Path diagnosticsRoot, UUID sessionId) throws IOException {
		Objects.requireNonNull(diagnosticsRoot, "diagnosticsRoot");
		Objects.requireNonNull(sessionId, "sessionId");
		Path root = diagnosticsRoot.resolve("history");
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
			throw new IOException("Missing or unsafe historical root");
		}
		List<Path> matching = new ArrayList<>();
		try (Stream<Path> machines = Files.list(root)) {
			for (Path machine : machines.toList()) {
				if (Files.isSymbolicLink(machine) || !Files.isDirectory(machine, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				Path session = machine.resolve(sessionId.toString());
				if (Files.exists(session, LinkOption.NOFOLLOW_LINKS)) {
					matching.add(session);
				}
			}
		}
		if (matching.size() != 1) {
			throw new IOException("Expected exactly one historical session for " + sessionId);
		}
		verify(matching.getFirst());
	}
}
