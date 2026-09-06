package au.edu.eq.questionbank.service.backup;

/**
 * Reports a failure to create a valid question-bank backup.
 */
public class BackupException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a backup failure with an explanatory message.
	 *
	 * @param message failure description
	 */
	public BackupException(String message) {
		super(message);
	}

	/**
	 * Creates a backup failure caused by another exception.
	 *
	 * @param message failure description
	 * @param cause   underlying failure
	 */
	public BackupException(String message, Throwable cause) {
		super(message, cause);
	}
}
