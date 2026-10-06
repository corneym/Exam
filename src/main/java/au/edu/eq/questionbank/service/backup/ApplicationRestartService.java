package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Relaunches Exam Question Bank from its installed jpackage launcher.
 * <p>
 * Development launches through Java or an IDE are deliberately not treated as
 * restartable because reproducing an IDE command line is outside application
 * ownership.
 */
public final class ApplicationRestartService {

	private static final String WINDOWS_PACKAGED_LAUNCHER = "Exam Question Bank.exe";
	private final Supplier<Optional<String>> commandSupplier;
	private final ProcessLauncher processLauncher;

	/**
	 * Creates a restart service for the currently running application process.
	 */
	public ApplicationRestartService() {
		this(() -> ProcessHandle.current().info().command(),
				executable -> new ProcessBuilder(executable.toString()).start());
	}

	ApplicationRestartService(Supplier<Optional<String>> commandSupplier, ProcessLauncher processLauncher) {
		if (commandSupplier == null) {
			throw new NullPointerException("commandSupplier");
		}
		if (processLauncher == null) {
			throw new NullPointerException("processLauncher");
		}
		this.commandSupplier = commandSupplier;
		this.processLauncher = processLauncher;
	}

	/**
	 * Returns whether the current process appears to be the installed Exam Question
	 * Bank launcher.
	 *
	 * @return {@code true} when automatic restart is supported
	 */
	public boolean isRestartSupported() {
		return resolvePackagedLauncher().isPresent();
	}

	/**
	 * Starts a replacement Exam Question Bank process.
	 * <p>
	 * The caller remains responsible for first completing all backup and resource
	 * shutdown work and for terminating the current process after this method
	 * succeeds or fails.
	 *
	 * @throws IOException if the current process is not the supported installed
	 *                     launcher or the replacement process cannot be started
	 */
	public void restart() throws IOException {
		Path launcher = resolvePackagedLauncher().orElseThrow(() -> new IOException(
				"Automatic restart is available only from the installed Exam Question Bank application."));
		processLauncher.launch(launcher);
	}

	private Optional<Path> resolvePackagedLauncher() {
		Optional<String> command;
		try {
			command = commandSupplier.get();
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
		if (command == null || command.isEmpty() || command.get().isBlank()) {
			return Optional.empty();
		}
		Path executable;
		try {
			executable = Path.of(command.get()).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
		Path fileName = executable.getFileName();
		if (fileName == null || !WINDOWS_PACKAGED_LAUNCHER.equalsIgnoreCase(fileName.toString())) {
			return Optional.empty();
		}
		if (!Files.isRegularFile(executable)) {
			return Optional.empty();
		}
		return Optional.of(executable);
	}

	@FunctionalInterface
	interface ProcessLauncher {

		void launch(Path executable) throws IOException;
	}
}
