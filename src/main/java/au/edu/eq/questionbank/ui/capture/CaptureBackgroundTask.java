package au.edu.eq.questionbank.ui.capture;

import java.util.concurrent.Callable;

import javafx.concurrent.Task;

/**
 * Runs one capture operation away from the JavaFX application thread.
 *
 * @param <T> result produced by the background operation
 */
final class CaptureBackgroundTask<T> extends Task<T> {

	private final Callable<T> operation;

	CaptureBackgroundTask(Callable<T> operation) {
		if (operation == null) {
			throw new NullPointerException("operation");
		}
		this.operation = operation;
	}

	@Override
	protected T call() throws Exception {

		// Delegate persistence work to the supplied operation while retaining normal
		// JavaFX Task success/failure notification.
		return operation.call();
	}
}
