package au.edu.eq.questionbank;

/**
 * Indicates that required application configuration is missing or invalid.
 */
public class ConfigurationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a configuration exception with a user-facing explanation.
	 *
	 * @param message the configuration error message
	 */
	public ConfigurationException(String message) {
		super(message);
	}
}
