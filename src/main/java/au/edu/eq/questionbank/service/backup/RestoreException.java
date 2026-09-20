package au.edu.eq.questionbank.service.backup;

/**
 * Reports a failure to validate, stage, or restore a question-bank backup.
 */
public class RestoreException extends Exception {

	private static final long serialVersionUID = 1L;
	/**
	 * Whether restore closed or altered resources so the current session must
	 * terminate.
	 */
	private final boolean applicationMustExit;

	/**
	 * Creates a restore failure that leaves the current application session usable.
	 *
	 * @param message failure description
	 */
	public RestoreException(String message) {
		this(message, null, false);
	}

	/**
	 * Creates a restore failure that leaves the current application session usable.
	 *
	 * @param message failure description
	 * @param cause   underlying failure
	 */
	public RestoreException(String message, Throwable cause) {
		this(message, cause, false);
	}

	/**
	 * Creates a restore failure.
	 *
	 * @param message             failure description
	 * @param cause               underlying failure
	 * @param applicationMustExit whether active resources have already been closed
	 *                            or altered such that the current session must
	 *                            terminate
	 */
	public RestoreException(String message, Throwable cause, boolean applicationMustExit) {
		super(message, cause);
		this.applicationMustExit = applicationMustExit;
	}

	/**
	 * Returns whether the current application session must terminate after this
	 * failure.
	 *
	 * @return {@code true} when continuing the current session is unsafe
	 */
	public boolean applicationMustExit() {
		return applicationMustExit;
	}
}
