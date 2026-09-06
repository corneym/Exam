package au.edu.eq.questionbank.output.scorm;

import java.nio.file.Path;

import au.edu.eq.questionbank.service.revision.RevisionCorpusStatistics;

/**
 * Result of one successfully generated SCORM revision package.
 */
public final class ScormExportResult {

	private final Path destination;
	private final RevisionCorpusStatistics statistics;

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

	public Path getDestination() {
		return destination;
	}

	public RevisionCorpusStatistics getStatistics() {
		return statistics;
	}
}
