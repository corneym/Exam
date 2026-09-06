package au.edu.eq.questionbank.output.revision;

/**
 * Receives progress updates while a revision export is being generated.
 *
 * A total of zero indicates a phase whose progress cannot be quantified.
 */
@FunctionalInterface
public interface RevisionExportProgressListener {

	void update(String message, int completed, int total);
}
