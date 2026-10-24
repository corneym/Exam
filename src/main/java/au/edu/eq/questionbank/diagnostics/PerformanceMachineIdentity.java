package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Maintains a random installation identity independently of Question data.
 *
 * The identity is created only when diagnostics explicitly request it. Question
 * database backup and restore must not replace this file.
 */
public final class PerformanceMachineIdentity {

	private static final String ID_FILENAME = "machine.id";
	private static final int MAX_ID_BYTES = 64;

	private PerformanceMachineIdentity() {
	}

	/**
	 * Reads or creates a permanent random machine identifier.
	 *
	 * File locking protects concurrent application instances. A malformed existing
	 * identity is rejected rather than silently replaced.
	 *
	 * @param diagnosticsDirectory application diagnostics directory
	 * @return stable machine UUID
	 * @throws IOException if the identity cannot be read or persisted
	 */
	public static synchronized UUID loadOrCreate(Path diagnosticsDirectory) throws IOException {
		Objects.requireNonNull(diagnosticsDirectory, "diagnosticsDirectory");
		Files.createDirectories(diagnosticsDirectory);
		Path identityFile = diagnosticsDirectory.resolve(ID_FILENAME);
		try (FileChannel channel = FileChannel.open(identityFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
				StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
			if (!lock.isValid()) {
				throw new IOException("Could not acquire a valid performance identity lock");
			}
			if (channel.size() == 0) {
				UUID identity = UUID.randomUUID();
				byte[] bytes = (identity + "\n").getBytes(StandardCharsets.UTF_8);
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				channel.position(0);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
				return identity;
			}
			if (channel.size() > MAX_ID_BYTES) {
				throw new IOException("Invalid performance machine identity");
			}
			ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
			channel.position(0);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) {
					throw new IOException("Incomplete performance machine identity");
				}
			}
			String value = new String(buffer.array(), StandardCharsets.UTF_8).trim();
			try {
				UUID identity = UUID.fromString(value);
				if (!identity.toString().equals(value)) {
					throw new IllegalArgumentException("Noncanonical UUID");
				}
				return identity;
			} catch (IllegalArgumentException invalid) {
				throw new IOException("Invalid performance machine identity", invalid);
			}
		}
	}
}
