package au.edu.eq.questionbank.diagnostics;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;

/**
 * Measures JDBC activity within one synchronous workflow.
 *
 * The operation and its SQL measurements share a parent relationship. Existing
 * thread-local probes are not nested or counted twice.
 */
public final class PerformanceSqlScope {

	private PerformanceSqlScope() {
	}

	/**
	 * Executes a workflow while collecting aggregate JDBC metrics.
	 *
	 * Disabled recording and already-active SQL scopes execute the action directly,
	 * preserving existing application behaviour.
	 *
	 * @param <T>             result type
	 * @param recorder        application performance recorder
	 * @param operationPrefix stable diagnostic operation prefix
	 * @param parentId        enclosing performance operation ID, or zero
	 * @param action          database-related workflow
	 * @return original workflow result
	 * @throws Exception original workflow exception
	 */
	public static <T> T measure(PerformanceRecorder recorder, String operationPrefix, long parentId, Callable<T> action)
			throws Exception {
		return measure(recorder, operationPrefix, parentId, action, _ -> {
		});
	}

	/**
	 * Executes a workflow, publishes SQL counters, and optionally observes the
	 * completed aggregate JDBC snapshot.
	 *
	 * The observer runs after the thread-local probe has been closed. Its
	 * exceptions cannot replace the original workflow outcome. Disabled or nested
	 * diagnostic scopes do not invoke the observer.
	 *
	 * @param <T>             result type
	 * @param recorder        application performance recorder
	 * @param operationPrefix stable diagnostic operation prefix
	 * @param parentId        enclosing performance operation ID, or zero
	 * @param action          database-related workflow
	 * @param observer        best-effort observer of the completed SQL snapshot
	 * @return original workflow result
	 * @throws Exception original workflow exception
	 */
	public static <T> T measure(PerformanceRecorder recorder, String operationPrefix, long parentId, Callable<T> action,
			Consumer<SqliteReadProbe.Snapshot> observer) throws Exception {
		Objects.requireNonNull(recorder, "recorder");
		Objects.requireNonNull(operationPrefix, "operationPrefix");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(observer, "observer");
		if (!recorder.isEnabled() || SqliteReadProbe.isActive()) {
			return action.call();
		}
		PerformanceOperation operation = recorder.start(operationPrefix + ".sql.scope", parentId);
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			try {
				return action.call();
			} catch (Exception | Error failure) {
				operation.failed();
				throw failure;
			} finally {

				// Remove the thread-local probe before publishing measurements.
				probe.close();
				operation.close();
				SqliteReadProbe.Snapshot snapshot = probe.snapshot();
				try {
					publish(recorder, operationPrefix, operation.id(), snapshot);
				} catch (RuntimeException | Error diagnosticFailure) {

					// Diagnostic publication must not replace the workflow result.
				}
				try {
					observer.accept(snapshot);
				} catch (RuntimeException | Error diagnosticFailure) {

					// Optional console reporting is best effort only.
				}
			}
		} finally {
			operation.close();
		}
	}

	/**
	 * Measures a workflow whose callback cannot throw checked exceptions.
	 *
	 * Preserves the original RuntimeException or Error, including when a nested
	 * probe is active or recording is disabled.
	 *
	 * @param <T>             workflow result type
	 * @param recorder        application performance recorder
	 * @param operationPrefix stable diagnostic operation prefix
	 * @param parentId        enclosing operation ID, or zero
	 * @param action          original workflow
	 * @return original workflow result
	 */
	public static <T> T measureUnchecked(PerformanceRecorder recorder, String operationPrefix, long parentId,
			Supplier<T> action) {
		return measureUnchecked(recorder, operationPrefix, parentId, action, _ -> {
		});
	}

	/**
	 * Measures a workflow without checked exceptions and observes the completed
	 * aggregate JDBC snapshot when a new probe is active.
	 *
	 * @param <T>             workflow result type
	 * @param recorder        application performance recorder
	 * @param operationPrefix stable diagnostic operation prefix
	 * @param parentId        enclosing operation ID, or zero
	 * @param action          original workflow
	 * @param observer        best-effort observer of the completed SQL snapshot
	 * @return original workflow result
	 */
	public static <T> T measureUnchecked(PerformanceRecorder recorder, String operationPrefix, long parentId,
			Supplier<T> action, Consumer<SqliteReadProbe.Snapshot> observer) {
		Objects.requireNonNull(action, "action");
		try {
			return measure(recorder, operationPrefix, parentId, action::get, observer);
		} catch (RuntimeException | Error failure) {
			throw failure;
		} catch (Exception unexpectedChecked) {

			// Supplier.get cannot declare checked exceptions.
			throw new IllegalStateException("Unexpected checked diagnostic workflow failure", unexpectedChecked);
		}
	}

	private static void metric(PerformanceRecorder recorder, String name, long parentId, long value) {
		try (PerformanceOperation operation = recorder.start(name, parentId)) {
			operation.resultCount(value);
		}
	}

	private static void publish(PerformanceRecorder recorder, String prefix, long parentId,
			SqliteReadProbe.Snapshot snapshot) {
		metric(recorder, prefix + ".sql.connections", parentId, snapshot.connectionsOpened());
		metric(recorder, prefix + ".sql.execute-invocations", parentId, snapshot.executions());
		metric(recorder, prefix + ".sql.rows-read", parentId, snapshot.rowsRead());
		metric(recorder, prefix + ".sql.questions-reconstructed", parentId, snapshot.questionsReconstructed());
		metric(recorder, prefix + ".sql.connection-open-us", parentId, snapshot.connectionOpenNanos() / 1_000);
		metric(recorder, prefix + ".sql.execution-us", parentId, snapshot.executionNanos() / 1_000);
		metric(recorder, prefix + ".sql.row-next-us", parentId, snapshot.rowNextNanos() / 1_000);
	}
}
