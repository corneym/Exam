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

	public Path getDestination() {
		return destination;
	}

	public RevisionCorpusStatistics getStatistics() {
		return statistics;
	}
}
