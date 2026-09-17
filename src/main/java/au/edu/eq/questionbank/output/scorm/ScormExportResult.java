package au.edu.eq.questionbank.output.scorm;

import java.nio.file.Path;

import au.edu.eq.questionbank.service.revision.RevisionCorpusStatistics;

/**
 * Result of one successfully generated SCORM revision package.
 */
public final class ScormExportResult {

	private final Path destination;
	private final RevisionCorpusStatistics statistics;

	/**
	 * Creates a result after successful package publication.
	 *
	 * @param destination the published SCORM ZIP
	 * @param statistics  statistics from the underlying revision corpus
	 * @throws NullPointerException if either argument is null
	 */
	ScormExportResult(Path destination, RevisionCorpusStatistics statistics) {
		if (destination == null) {
			throw new NullPointerException("destination");
		}
		if (statistics == null) {
			throw new NullPointerException("statistics");
		}
		this.destination = destination;
		this.statistics = statistics;
	}

	/**
	 * Returns the published SCORM ZIP path.
	 *
	 * @return the final ZIP path
	 */
	public Path getDestination() {
		return destination;
	}

	/**
	 * Returns statistics produced while generating the revision corpus.
	 *
	 * @return the revision corpus statistics
	 */
	public RevisionCorpusStatistics getStatistics() {
		return statistics;
	}
}
