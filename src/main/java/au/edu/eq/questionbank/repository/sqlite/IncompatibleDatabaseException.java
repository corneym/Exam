package au.edu.eq.questionbank.repository.sqlite;

import java.sql.SQLException;

/**
 * Indicates that a recognised older database contains data for which the
 * application deliberately has no lossless automatic migration.
 */
public class IncompatibleDatabaseException extends SQLException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an incompatibility with a user-facing explanation.
	 *
	 * @param message the reason automatic migration was refused
	 */
	public IncompatibleDatabaseException(String message) {
		super(message);
	}
}
