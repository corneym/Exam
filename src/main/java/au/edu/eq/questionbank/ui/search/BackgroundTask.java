package au.edu.eq.questionbank.ui.search;

import java.util.concurrent.Callable;

import javafx.concurrent.Task;

/**
 * JavaFX background task backed by one callable operation.
 * <p>
 * Search workflow classes can supply their background work without repeatedly
 * declaring anonymous {@link Task} subclasses. Checked exceptions are retained
 * so ordinary JavaFX Task failure handling continues to receive them.
 *
 * @param <T> result type produced by the background operation
 */
final class BackgroundTask<T> extends Task<T> {

	private final Callable<T> operation;

	BackgroundTask(Callable<T> operation) {
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
