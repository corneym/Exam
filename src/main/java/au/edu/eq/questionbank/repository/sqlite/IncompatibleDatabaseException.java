package au.edu.eq.questionbank.repository.sqlite;

import java.sql.SQLException;

public class IncompatibleDatabaseException extends SQLException {

	private static final long serialVersionUID = 1L;

	public IncompatibleDatabaseException(String message) {
		super(message);
	}
}