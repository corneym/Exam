package au.edu.eq.questionbank.output.revision;

/**
 * Receives progress updates while a revision export is being generated.
 *
 * A total of zero indicates a phase whose progress cannot be quantified.
 */
@FunctionalInterface
public interface RevisionExportProgressListener {

	/**
	 * Reports progress synchronously on the exporting thread. UI consumers must
	 * marshal control changes to the JavaFX application thread.
	 *
	 * @param message the current operation description
	 * @param completed completed items in the current phase
	 * @param total total items in the current phase; zero means indeterminate
	 */
	void update(String message, int completed, int total);
}
