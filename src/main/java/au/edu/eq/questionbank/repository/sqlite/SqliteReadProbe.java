package au.edu.eq.questionbank.repository.sqlite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Collects JDBC activity for one synchronous diagnostic workload.
 *
 * A probe is explicitly enabled for the calling thread. Connections opened
 * outside an active probe are not wrapped or counted.
 *
 * The probe counts successful connection openings, JDBC execution invocations,
 * rows returned by ResultSet.next(), and complete Question reconstructions.
 *
 * JDBC execution time excludes subsequent ResultSet traversal. Connection
 * opening includes the configuration performed by SqliteDatabase.
 *
 * Measurements are diagnostic evidence, not an estimate of pure SQLite engine
 * execution time.
 */
public final class SqliteReadProbe implements AutoCloseable {

	private static final ThreadLocal<SqliteReadProbe> ACTIVE = new ThreadLocal<>();
	// Connection and execution measurements.
	private long connectionOpenNanos;
	private long connectionsOpened;
	private long executionNanos;
	private long executions;

	// Reconstruction and row measurements.
	private long questionsReconstructed;
	private long rowNextNanos;
	private long rowsRead;

	// Measurement lifecycle.
	private boolean closed;
	private long finishedNanos;

	// Measurement start time (initialisation order retained).
	private final long startedNanos = System.nanoTime();

	private SqliteReadProbe() {
	}

	/**
	 * Starts a diagnostic scope on the calling thread.
	 *
	 * Nested probes are rejected to avoid counting operations twice.
	 *
	 * @return the active diagnostic scope
	 * @throws IllegalStateException if a scope is already active
	 */
	public static SqliteReadProbe begin() {
		if (ACTIVE.get() != null) {
			throw new IllegalStateException("A SQLite diagnostic probe is already active");
		}
		SqliteReadProbe probe = new SqliteReadProbe();
		ACTIVE.set(probe);
		return probe;
	}

	/**
	 * Records one successfully reconstructed Question.
	 *
	 * Has no effect when a diagnostic scope is not active.
	 */
	public static void recordQuestionReconstruction() {
		SqliteReadProbe probe = ACTIVE.get();
		if (probe != null) {
			probe.questionsReconstructed++;
		}
	}

	static SqliteReadProbe active() {
		return ACTIVE.get();
	}

	/**
	 * Completes the scope and restores ordinary JDBC behaviour.
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		if (ACTIVE.get() != this) {
			throw new IllegalStateException("SQLite diagnostic scope closed on another thread");
		}
		finishedNanos = System.nanoTime();
		ACTIVE.remove();
		closed = true;
	}

	/**
	 * Returns an immutable snapshot of the collected measurements.
	 *
	 * @return counters and elapsed duration
	 */
	public Snapshot snapshot() {
		long endNanos = closed ? finishedNanos : System.nanoTime();
		return new Snapshot(connectionsOpened, connectionOpenNanos, executions, executionNanos, rowsRead, rowNextNanos,
				questionsReconstructed, endNanos - startedNanos);
	}

	Connection wrap(Connection connection, long openingNanos) {
		connectionsOpened++;
		connectionOpenNanos += openingNanos;
		return Connection.class.cast(Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (_, method, arguments) -> {
					Object result = invoke(connection, method, arguments);
					if (result instanceof Statement statement && isStatementFactory(method.getName())) {
						return wrapStatement(statement);
					}
					return result;
				}));
	}

	private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException failure) {

			// Preserve the actual JDBC exception rather than exposing
			// a reflection wrapper to repository callers.
			throw failure.getCause();
		}
	}

	private boolean isExecution(String methodName) {
		return switch (methodName) {
		case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch" ->
			true;
		default -> false;
		};
	}

	private boolean isStatementFactory(String methodName) {
		return "createStatement".equals(methodName) || "prepareStatement".equals(methodName)
				|| "prepareCall".equals(methodName);
	}

	private ResultSet wrapResultSet(ResultSet resultSet) {
		return ResultSet.class.cast(Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
				new Class<?>[] { ResultSet.class }, (_, method, arguments) -> {
					if (!"next".equals(method.getName())) {
						return invoke(resultSet, method, arguments);
					}
					long started = System.nanoTime();
					try {
						boolean hasRow = (boolean) invoke(resultSet, method, arguments);
						if (hasRow) {
							rowsRead++;
						}
						return hasRow;
					} finally {
						rowNextNanos += System.nanoTime() - started;
					}
				}));
	}

	private Statement wrapStatement(Statement statement) {
		Class<?> contract;
		if (statement instanceof CallableStatement) {
			contract = CallableStatement.class;
		} else if (statement instanceof PreparedStatement) {
			contract = PreparedStatement.class;
		} else {
			contract = Statement.class;
		}
		return (Statement) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract },
				(_, method, arguments) -> {
					String methodName = method.getName();
					if (isExecution(methodName)) {
						long started = System.nanoTime();
						try {
							Object result = invoke(statement, method, arguments);
							if (result instanceof ResultSet resultSet) {
								return wrapResultSet(resultSet);
							}
							return result;
						} finally {

							// Count attempted executions, including
							// executions that report an SQL error.
							executions++;
							executionNanos += System.nanoTime() - started;
						}
					}
					Object result = invoke(statement, method, arguments);
					if (("getResultSet".equals(methodName) || "getGeneratedKeys".equals(methodName))
							&& result instanceof ResultSet resultSet) {
						return wrapResultSet(resultSet);
					}
					return result;
				});
	}

	/**
	 * Immutable diagnostic measurement.
	 *
	 * Executions are JDBC execute invocations, not necessarily individual
	 * statements within a batch. Rows are successful ResultSet.next() observations.
	 * Timing fields are nanoseconds.
	 *
	 * @param connectionsOpened      successful connections returned
	 * @param connectionOpenNanos    time spent opening and configuring connections
	 * @param executions             JDBC execution invocations
	 * @param executionNanos         cumulative JDBC execution duration
	 * @param rowsRead               rows observed during ResultSet traversal
	 * @param rowNextNanos           cumulative time spent in ResultSet.next()
	 * @param questionsReconstructed complete Questions reconstructed
	 * @param elapsedNanos           total diagnostic scope duration
	 */
	public record Snapshot(long connectionsOpened, long connectionOpenNanos, long executions, long executionNanos,
			long rowsRead, long rowNextNanos, long questionsReconstructed, long elapsedNanos) {
	}
}
