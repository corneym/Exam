package au.edu.eq.questionbank;

/**
 * Indicates that required application configuration is missing or invalid.
 */
public class ConfigurationException extends RuntimeException {

	/**
	 * Creates a configuration exception with a user-facing explanation.
	 *
	 * @param message the configuration error message
	 */
	public ConfigurationException(String message) {
		super(message);
	}
}
