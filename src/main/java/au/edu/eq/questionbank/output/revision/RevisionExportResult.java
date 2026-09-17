package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.service.revision.RevisionCorpusStatistics;

/**
 * Result of one successfully validated and published revision export.
 */
public final class RevisionExportResult {

	private final Path destination;
	private final RevisionCorpusStatistics statistics;

	RevisionExportResult(Path destination, RevisionCorpusStatistics statistics) {
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
	 * Returns the completed revision site directory.
	 *
	 * @return the published export directory
	 */
	public Path getDestination() {
		return destination;
	}

	/**
	 * Returns the corpus statistics reported for this export.
	 *
	 * @return corpus counts for the completed export
	 */
	public RevisionCorpusStatistics getStatistics() {
		return statistics;
	}
}
