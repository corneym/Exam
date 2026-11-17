package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectPortableRecordReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Reads an installed contributor assignment and its complete current Exam graph
 * from one immutable SQLite snapshot.
 *
 * <p>
 * The original assignment baseline is recovered from the same database snapshot
 * as the current records. Comparison uses the original issued baseline even
 * when previous partial returns have been prepared or the contributor has
 * changed active assignments.
 *
 * <p>
 * This reader does not create database snapshots, read managed binary assets,
 * publish packages or change assignment state. The caller owns the snapshot's
 * creation and eventual cleanup.
 */
public final class ContributionSnapshotReader {

	private final Path stableSnapshotPath;

	/**
	 * Creates a reader for an existing consistent SQLite snapshot.
	 *
	 * @param stableSnapshotPath immutable SQLite snapshot file
	 * @throws NullPointerException if the path is null
	 */
	public ContributionSnapshotReader(Path stableSnapshotPath) {
		this.stableSnapshotPath = Objects.requireNonNull(stableSnapshotPath, "stableSnapshotPath");
	}

	/**
	 * Reads the original assignment baseline and the current Exam records, then
	 * calculates the contributor's changes.
	 *
	 * <p>
	 * Assignment lookup uses the supplied UUID rather than the currently selected
	 * assignment. This allows a contributor to inspect an earlier assignment after
	 * selecting another one.
	 *
	 * @param assignmentId installed assignment identity
	 * @return original and current records with their contribution plan
	 * @throws SQLException         if the assignment, Exam graph or portable
	 *                              identities cannot be read consistently
	 * @throws IOException          if stored baseline JSONL is invalid or
	 *                              contributor changes cannot be represented
	 * @throws NullPointerException if assignmentId is null
	 */
	public Snapshot read(UUID assignmentId) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		SqliteDatabase snapshotDatabase = SqliteDatabase.readOnly(stableSnapshotPath);
		SqliteContributorAssignmentSessionRepository sessions = new SqliteContributorAssignmentSessionRepository(
				snapshotDatabase);
		SqliteContributorAssignmentSessionRepository.Session session = sessions.find(assignmentId)
				.orElseThrow(() -> new SQLException("Unknown installed contributor assignment: " + assignmentId));
		SqliteContributorAssignmentSessionRepository.Baseline storedBaseline = sessions.readBaseline(assignmentId);
		List<ExchangeRecord> baseline;
		try (ByteArrayInputStream input = new ByteArrayInputStream(storedBaseline.records())) {
			baseline = new ExamWorkJsonlCodec().readRecords(input);
		}

		// The graph and all entity snapshots come from the same
		// immutable database copy as the assignment metadata.
		SqliteSubjectGraphReader.SubjectGraph graph = new SqliteExamAssignmentGraphReader(stableSnapshotPath)
				.read(session.subjectLocalId(), session.examLocalId());
		List<ExchangeRecord> current = new SqliteSubjectPortableRecordReader(stableSnapshotPath).read(graph);

		// Confirm that both record sets describe the assigned
		// Subject and Exam, not a different local installation.
		requireIdentity(baseline, "SUBJECT", session.subjectPortableId());
		requireIdentity(baseline, "EXAM", session.examPortableId());
		requireIdentity(current, "SUBJECT", session.subjectPortableId());
		requireIdentity(current, "EXAM", session.examPortableId());
		ContributionDeltaPlanner.Plan delta = new ContributionDeltaPlanner().plan(baseline, current);
		return new Snapshot(session, baseline, current, delta);
	}

	private void requireIdentity(List<ExchangeRecord> records, String type, UUID expectedId)
			throws PackageFormatException {
		long count = records.stream().filter(record -> record.recordType().equals(type)).count();
		boolean matches = records.stream()
				.anyMatch(record -> record.recordType().equals(type) && record.recordId().equals(expectedId));
		if (count != 1 || !matches) {
			throw new PackageFormatException(
					"Contribution snapshot does not contain exactly " + "the assigned " + type + ": " + expectedId);
		}
	}

	/**
	 * Read-only assignment snapshot with calculated changes.
	 *
	 * @param session         installed contributor assignment
	 * @param baselineRecords original immutable assignment records
	 * @param currentRecords  complete current assigned Exam graph
	 * @param delta           new and modified contribution candidates
	 */
	public record Snapshot(SqliteContributorAssignmentSessionRepository.Session session,
			List<ExchangeRecord> baselineRecords, List<ExchangeRecord> currentRecords,
			ContributionDeltaPlanner.Plan delta) {

		/**
		 * Validates the snapshot and defensively copies record lists.
		 *
		 * @throws NullPointerException if a component is null
		 */
		public Snapshot {
			session = Objects.requireNonNull(session, "session");
			baselineRecords = List.copyOf(Objects.requireNonNull(baselineRecords, "baselineRecords"));
			currentRecords = List.copyOf(Objects.requireNonNull(currentRecords, "currentRecords"));
			delta = Objects.requireNonNull(delta, "delta");
		}
	}
}
