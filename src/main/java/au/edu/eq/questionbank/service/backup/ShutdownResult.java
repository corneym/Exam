package au.edu.eq.questionbank.service.backup;

/**
 * Reports the outcome of preparing the application for shutdown.
 *
 * @param status  shutdown status
 * @param failure associated failure or warning, if any
 */
public record ShutdownResult(ShutdownStatus status, Throwable failure) {

	/**
	 * Validates the shutdown result.
	 */
	public ShutdownResult {
		if (status == null) {
			throw new NullPointerException("status");
		}
	}

	/**
	 * Returns whether the application may now terminate safely.
	 *
	 * @return {@code true} when shutdown preparation is complete
	 */
	public boolean exitAllowed() {
		return status == ShutdownStatus.READY_TO_EXIT || status == ShutdownStatus.READY_TO_EXIT_WITH_RETENTION_WARNING
				|| status == ShutdownStatus.ALREADY_READY;
	}
}
