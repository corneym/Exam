package au.edu.eq.questionbank.service.backup;

/**
 * Reports invalid or unsupported backup-format metadata.
 */
public class BackupFormatException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a backup-format failure.
	 *
	 * @param message description of the invalid format
	 */
	public BackupFormatException(String message) {
		super(message);
	}

	/**
	 * Creates a backup-format failure caused by another exception.
	 *
	 * @param message description of the invalid format
	 * @param cause   underlying failure
	 */
	public BackupFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
