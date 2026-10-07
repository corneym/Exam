package au.edu.eq.questionbank.ui;

import java.util.concurrent.Callable;

import javafx.concurrent.Task;

/**
 * JavaFX background task backed by one application-level callable operation.
 * <p>
 * Application workflow code can supply background work without repeatedly
 * declaring anonymous {@link Task} subclasses. Checked exceptions are retained
 * so normal JavaFX Task failure handling continues to receive them.
 *
 * @param <T> result type produced by the background operation
 */
final class ApplicationBackgroundTask<T> extends Task<T> {

	private final Callable<T> operation;

	ApplicationBackgroundTask(Callable<T> operation) {
		if (operation == null) {
			throw new NullPointerException("operation");
		}
		this.operation = operation;
	}

	@Override
	protected T call() throws Exception {

		// Task owns the JavaFX lifecycle; the supplied operation owns only the
		// background work that produces its result.
		return operation.call();
	}
}
