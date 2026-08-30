package au.edu.eq.questionbank;

import au.edu.eq.questionbank.ui.QuestionBankApplication;

/**
 * Non-JavaFX launcher entry point used to start the desktop application from a
 * packaged application.
 */
public class Launcher {

	/**
	 * Creates the launcher used by the packaged application.
	 */
	public Launcher() {
	}

	/**
	 * Starts the JavaFX application.
	 *
	 * @param args command-line arguments passed to JavaFX
	 */
	public static void main(String[] args) {
		QuestionBankApplication.main(args);
	}
}
