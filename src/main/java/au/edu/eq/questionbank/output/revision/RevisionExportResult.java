package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.service.revision.RevisionCorpusStatistics;

/**
 * Result of one successfully validated and published revision export.
 */
public final class RevisionExportResult {

	private final Path destination;
	private final RevisionExportIdentity exportIdentity;
	private final RevisionCorpusStatistics statistics;

	RevisionExportResult(Path destination, RevisionCorpusStatistics statistics,
			RevisionExportIdentity exportIdentity) {
		if (destination == null) {
			throw new NullPointerException("destination");
		}
		if (statistics == null) {
			throw new NullPointerException("statistics");
		}
		if (exportIdentity == null) {
			throw new NullPointerException("exportIdentity");
		}
		this.destination = destination;
		this.exportIdentity = exportIdentity;
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
	 * Returns the identity shared by every artifact in this export.
	 *
	 * @return export identity
	 */
	public RevisionExportIdentity getExportIdentity() {
		return exportIdentity;
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
