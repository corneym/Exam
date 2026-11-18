package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.HistoricalSchemaFixture;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;

class ContributionArchiveInspectorTest {

	@TempDir
	Path directory;

	@Test
	void acceptsCumulativeReturnsWithoutCoordinatorAcknowledgement() throws Exception {
		Fixture fixture = fixture();
		Path firstArchive = publish(fixture, "First contributor edit", "first.examwork");
		ContributionArchiveInspector inspector = new ContributionArchiveInspector(fixture.master());
		var first = inspector.inspect(firstArchive);
		assertEquals(1, first.returnNumber());
		assertNull(first.previousPackageId());
		assertEquals(1, first.changes().size());
		assertTrue(first.proposedRecords().size() > first.changes().size());
		assertEquals(fixture.assignmentId(), first.assignment().assignmentId());
		assertTrue(first.archiveSha256().matches("[0-9a-f]{64}"));

		// The coordinator has neither accepted nor acknowledged return 1.
		Path secondArchive = publish(fixture, "Second contributor edit", "second.examwork");
		var second = inspector.inspect(secondArchive);
		assertEquals(2, second.returnNumber());
		assertEquals(first.manifest().packageId(), second.previousPackageId());
		assertEquals(1, second.changes().size());
		assertEquals(first.assignment(), second.assignment());
		assertArrayEquals(new WorkAssignmentLedger(fixture.master()).readBaseline(fixture.assignmentId()).records(),
				readEntry(secondArchive, "baseline/initial-records.jsonl"));
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationCommitsQuestionTextAndRevision() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Applied contributor text", "apply-question.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Accept reviewed Question text");
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		var applied = coordinator.execute(review.reviewId());
		assertFalse(applied.duplicate());
		assertEquals(packageId, applied.packageId());
		assertEquals(1, applied.outcomes().size());
		var outcome = applied.outcomes().getFirst();
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, outcome.outcome());
		assertNotNull(outcome.resultingRevisionId());
		assertFalse(outcome.resultingRevisionId().equals(review.decisions().getFirst().masterRevisionId()));
		assertEquals("Applied contributor text", masterQuestionText(fixture.master()));
		assertEquals(applied, coordinator.find(review.reviewId()).orElseThrow());

		// A repeated execution must return existing evidence without writing.
		var replay = coordinator.execute(review.reviewId());
		assertTrue(replay.duplicate());
		assertEquals(applied.outcomes(), replay.outcomes());
		assertEquals(applied.completedAt(), replay.completedAt());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationHistoryIsImmutable() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Rejected content", "apply-immutable.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT), "Immutable application history");
		var coordinator = new MasterContributionApplicationCoordinator(fixture.master());
		var result = coordinator.execute(review.reviewId());
		String reviewId = result.reviewId().toString();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE contribution_application_results
				SET outcome = 'APPLIED'
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM contribution_application_results
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE contribution_application_batches
				SET completed_at = 'changed'
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM contribution_application_batches
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertEquals(result, coordinator.find(review.reviewId()).orElseThrow());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationPreservesIdenticalSkipRevision() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Already accepted content", "apply-identical.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Already accepted content'
				WHERE id = 81
				""");
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.SKIP_IDENTICAL),
				"Content already present on master");
		UUID retainedRevision = review.decisions().getFirst().masterRevisionId();
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		var result = coordinator.execute(review.reviewId());
		var outcome = result.outcomes().getFirst();
		assertEquals(MasterContributionApplicationCoordinator.Outcome.SKIPPED, outcome.outcome());
		assertEquals(retainedRevision, outcome.resultingRevisionId());
		assertEquals("Already accepted content", masterQuestionText(fixture.master()));
		assertEquals(result, coordinator.find(review.reviewId()).orElseThrow());
	}

	@Test
	void masterApplicationRecordsRejectionWithoutMasterMutation() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Unwanted contributor content", "apply-reject.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT), "Content is not required");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		var result = coordinator.execute(review.reviewId());
		var outcome = result.outcomes().getFirst();
		assertEquals(MasterContributionApplicationCoordinator.Outcome.REJECTED, outcome.outcome());
		assertNull(outcome.resultingRevisionId());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals(1, intake.proposedChanges(packageId).size());
		assertEquals(result, coordinator.find(review.reviewId()).orElseThrow());
	}

	@Test
	void masterApplicationRejectsStaleReviewedRevision() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor proposal", "apply-stale.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Review before coordinator edit");

		// Independent master modification after the review.
		execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Later coordinator correction'
				WHERE id = 81
				""");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId()));

		// Both application evidence and proposed mutation must roll back.
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		assertEquals("Later coordinator correction", masterQuestionText(fixture.master()));
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationRejectsUnsupportedQueuedAnswer() throws Exception {
		Fixture fixture = fixture();

		// An Answer edit is valid contributor work, but this first
		// application slice supports only existing Question text changes.
		try (Connection connection = fixture.contributor().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					UPDATE answers
					SET answer_text = 'Contributor answer revision'
					"""));
		}
		Path archive = directory.resolve("apply-answer.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		assertEquals("ANSWER", proposal.recordType());
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Answer proposal queued for future support");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId()));
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT answer_text FROM answers WHERE id = 91
						""")) {
			assertTrue(result.next());
			assertEquals("Biology answer", result.getString(1));
		}
	}

	@Test
	void masterDecisionHistoryIsImmutable() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Immutable proposal", "decision-immutable.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		var recorded = ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.REJECT),
				"Evidence protection regression");
		String reviewId = recorded.reviewId().toString();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE contribution_review_decisions
				SET choice = 'HOLD'
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM contribution_review_decisions
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE contribution_review_batches
				SET review_note = 'Altered'
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM contribution_review_batches
				WHERE review_id = '%s'
				""".formatted(reviewId)));
		assertEquals(recorded, ledger.find(recorded.reviewId()).orElseThrow());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterDecisionsQueueSafeUpdateWithoutApplyingIt() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "decision-ready.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		var recorded = ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Reviewed against issued baseline");
		assertEquals(packageId, recorded.packageId());
		assertEquals(1, recorded.decisions().size());
		var decision = recorded.decisions().getFirst();
		assertEquals(MasterContributionDecisionLedger.Choice.QUEUE_APPLY, decision.choice());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_UPDATE, decision.preliminaryStatus());
		assertEquals(decision.baselineRevisionId(), decision.masterRevisionId());
		assertNull(decision.resultRevisionId());

		// The entire decision and its observed revision survive rereading.
		assertEquals(recorded, ledger.find(recorded.reviewId()).orElseThrow());

		// QUEUE_APPLY is not a write operation.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterDecisionsRecordIdenticalSkipWithResultRevision() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Already present", "decision-identical.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var proposal = intake.proposedChanges(packageId).getFirst();

		// Simulate another master operation producing the same content.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					UPDATE questions
					SET question_text = 'Already present'
					WHERE id = 81
					"""));
		}
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		var recorded = ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.SKIP_IDENTICAL),
				"Master already contains identical content");
		var decision = recorded.decisions().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.SKIP_IDENTICAL, decision.preliminaryStatus());
		assertEquals(MasterContributionDecisionLedger.Choice.SKIP_IDENTICAL, decision.choice());
		assertNotNull(decision.resultRevisionId());
		assertEquals(decision.masterRevisionId(), decision.resultRevisionId());
		assertEquals(recorded, ledger.find(recorded.reviewId()).orElseThrow());
		assertEquals("Already present", masterQuestionText(fixture.master()));
	}

	@Test
	void masterDecisionsRejectHeldLineageForApplication() throws Exception {
		Fixture fixture = fixture();
		publish(fixture, "First revision", "held-first.examwork");
		Path secondArchive = publish(fixture, "Second revision", "held-second.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());

		// Return 2 is received before return 1.
		UUID packageId = intake.receive(secondArchive).received().packageId();
		var proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		assertThrows(IllegalArgumentException.class,
				() -> ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
						"Attempt to queue an unresolved return"));

		// A held return can be retained without falsely accepting it.
		var recorded = ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.HOLD),
				"Awaiting previous return");
		assertEquals(MasterContributionDecisionLedger.Choice.HOLD, recorded.decisions().getFirst().choice());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				recorded.decisions().getFirst().preliminaryStatus());
		assertNull(recorded.decisions().getFirst().resultRevisionId());
		assertEquals(recorded, ledger.find(recorded.reviewId()).orElseThrow());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterDecisionsRequireCompleteChoicesAndAllowRejection() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Unwanted contributor edit", "decision-rejected.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		assertThrows(IllegalArgumentException.class, () -> ledger.record(packageId, Map.of(), "Incomplete review"));
		assertThrows(IllegalArgumentException.class, () -> ledger.record(packageId, Map.of(), "  "));
		var proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var recorded = ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.REJECT),
				"Contributor edit not required");
		assertEquals(MasterContributionDecisionLedger.Choice.REJECT, recorded.decisions().getFirst().choice());
		assertNull(recorded.decisions().getFirst().resultRevisionId());
		assertEquals(recorded, ledger.find(recorded.reviewId()).orElseThrow());

		// Rejection does not remove the proposed intake evidence.
		assertEquals(1, intake.proposedChanges(packageId).size());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterIntakeEvidenceCannotBeUpdatedOrDeleted() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Immutable contribution", "immutable-return.examwork");
		var receipt = new MasterContributionIntake(fixture.master()).receive(archive);
		String packageId = receipt.received().packageId().toString();
		try (Connection connection = fixture.master().openConnection();
				PreparedStatement update = connection.prepareStatement("""
						UPDATE contribution_intake_packages
						SET intake_status = 'HELD_LINEAGE'
						WHERE package_id = ?
						""");
				PreparedStatement remove = connection.prepareStatement("""
						DELETE FROM contribution_intake_proposals WHERE package_id = ?
						""")) {
			update.setString(1, packageId);
			remove.setString(1, packageId);
			assertThrows(SQLException.class, update::executeUpdate);
			assertThrows(SQLException.class, remove::executeUpdate);
		}
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterIntakeHoldsOutOfOrderReturnsWithoutLaterAutoPromotion() throws Exception {
		Fixture fixture = fixture();
		Path firstArchive = publish(fixture, "Contributor first return", "first-return.examwork");
		Path secondArchive = publish(fixture, "Contributor second return", "second-return.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var second = intake.receive(secondArchive);
		assertFalse(second.duplicate());
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, second.received().status());
		var first = intake.receive(firstArchive);
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, first.received().status());
		assertEquals(first.received().packageId(), second.received().previousPackageId());

		// Replaying a previously held package must not silently change its state.
		var repeated = intake.receive(secondArchive);
		assertTrue(repeated.duplicate());
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, repeated.received().status());
		Path thirdArchive = publish(fixture, "Contributor third return", "third-return.examwork");
		var third = intake.receive(thirdArchive);
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, third.received().status());
		assertEquals(second.received().packageId(), third.received().previousPackageId());
	}

	@Test
	void masterIntakeRejectsPackageIdReusedForDifferentValidArchiveBytes() throws Exception {
		Fixture fixture = fixture();
		Path original = publish(fixture, "First submitted text", "submitted.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var recorded = intake.receive(original);
		List<ExchangeRecord> changes = new ExamWorkJsonlCodec()
				.readRecords(new ByteArrayInputStream(readEntry(original, "records/contribution-changes.jsonl")));
		ExchangeRecord question = changes.getFirst();
		ObjectNode values = (ObjectNode) question.values();
		values.put("question_text", "Different submitted text");
		Path reused = repackage(original, "records/contribution-changes.jsonl",
				encode(List.of(withValues(question, values))));
		assertArchiveWellFormed(reused, fixture.master());
		assertThrows(PackageFormatException.class, () -> intake.receive(reused));
		assertEquals(recorded.received(), intake.find(recorded.received().packageId()).orElseThrow());
		assertEquals(1, intake.proposedChanges(recorded.received().packageId()).size());
	}

	@Test
	void masterIntakeRetainsProposalsAndDoesNotOverwriteCoordinatorEdits() throws Exception {
		Fixture fixture = fixture();
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		Path firstArchive = publish(fixture, "Contributor version one", "return-1.examwork");
		var first = intake.receive(firstArchive);
		assertFalse(first.duplicate());
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, first.received().status());
		assertEquals(1, first.received().returnNumber());
		assertEquals(1, intake.proposedChanges(first.received().packageId()).size());

		// An intervening master correction must never be overwritten by intake.
		execute(fixture.master(), "UPDATE questions SET question_text = 'Coordinator correction' WHERE id = 81");
		Path secondArchive = publish(fixture, "Contributor version two", "return-2.examwork");
		var second = intake.receive(secondArchive);
		assertFalse(second.duplicate());
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, second.received().status());
		assertEquals(first.received().packageId(), second.received().previousPackageId());
		assertEquals(2, second.received().returnNumber());
		assertEquals(1, intake.proposedChanges(second.received().packageId()).size());
		assertEquals(second.received(), intake.find(second.received().packageId()).orElseThrow());
		var replay = intake.receive(firstArchive);
		assertTrue(replay.duplicate());
		assertEquals(first.received(), replay.received());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT question_text FROM questions WHERE id = 81")) {
			assertTrue(result.next());
			assertEquals("Coordinator correction", result.getString(1));
		}
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReviewHoldsDeletedBaselineQuestion() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "review-missing-master.examwork");
		var intake = new MasterContributionIntake(fixture.master());
		var receipt = intake.receive(archive);

		// Remove the master Answer before its parent Question to preserve
		// foreign-key integrity. Its original baseline remains in the ledger.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, "DELETE FROM answers WHERE question_id = 81"));
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, "DELETE FROM questions WHERE id = 81"));
		}
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(receipt.received().packageId());
		assertEquals(1, plan.assessments().size());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_MISSING_MASTER,
				plan.assessments().getFirst().status());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReviewHoldsStaleMasterRevision() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "review-stale.examwork");
		var intake = new MasterContributionIntake(fixture.master());
		var receipt = intake.receive(archive);

		// The master changes independently after issuing the baseline.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					UPDATE questions
					SET question_text = 'Master correction'
					WHERE id = 81
					"""));
		}
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(receipt.received().packageId());
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_STALE_MASTER, assessment.status());
		assertNotNull(assessment.masterRevisionId());
		assertNotNull(assessment.masterRecordSha256());
		assertFalse(assessment.masterRevisionId().equals(assessment.baselineRevisionId()));
		assertEquals("Master correction", masterQuestionText(fixture.master()));
	}

	@Test
	void masterReviewKeepsOutOfOrderReturnHeld() throws Exception {
		Fixture fixture = fixture();
		Path firstArchive = publish(fixture, "First revision", "review-first.examwork");
		Path secondArchive = publish(fixture, "Second revision", "review-second.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var second = intake.receive(secondArchive);
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, second.received().status());
		MasterContributionReviewPlanner planner = new MasterContributionReviewPlanner(fixture.master());
		var before = planner.plan(second.received().packageId());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				before.assessments().getFirst().status());

		// The delayed first return cannot automatically release return 2.
		intake.receive(firstArchive);
		var after = planner.plan(second.received().packageId());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				after.assessments().getFirst().status());
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE,
				intake.find(second.received().packageId()).orElseThrow().status());
	}

	@Test
	void masterReviewRecognisesNewQuestionWithoutInsertingIt() throws Exception {
		Fixture fixture = fixture();
		var session = new SqliteContributorAssignmentSessionRepository(fixture.contributor())
				.find(fixture.assignmentId()).orElseThrow();
		try (Connection connection = fixture.contributor().openConnection();
				PreparedStatement source = connection.prepareStatement("""
						SELECT question.booklet_id,
						       question.classification_node_id
						FROM questions question
						JOIN exam_booklets booklet
						    ON booklet.id = question.booklet_id
						WHERE booklet.exam_id = ?
						  AND question.question_code = '1'
						""");
				PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO questions (
						    booklet_id,
						    classification_node_id,
						    question_code,
						    question_text,
						    marks,
						    shared_context_capture_required
						)
						VALUES (?, ?, '2', 'New contributor question', 3, 0)
						""")) {
			source.setLong(1, session.examLocalId());
			long bookletId;
			long classificationId;
			try (ResultSet result = source.executeQuery()) {
				assertTrue(result.next());
				bookletId = result.getLong("booklet_id");
				classificationId = result.getLong("classification_node_id");
				assertFalse(result.next());
			}

			// Reuse valid contributor-local Booklet and classification
			// identities from the installed assignment.
			insert.setLong(1, bookletId);
			insert.setLong(2, classificationId);
			assertEquals(1, SqliteDirectChanges.executeUpdate(insert));
		}
		Path archive = directory.resolve("review-new-question.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var receipt = intake.receive(archive);
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(receipt.received().packageId());
		assertEquals(1, plan.assessments().size());
		var assessment = plan.assessments().getFirst();
		assertEquals("QUESTION", assessment.recordType());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, assessment.status());
		assertNull(assessment.baselineRevisionId());
		assertNull(assessment.masterRevisionId());

		// Inspection and review must not insert the contributor's Question
		// into the authoritative coordinator database.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM questions
						WHERE question_code = '2'
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void masterReviewRecognisesSafeBaselineUpdate() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "review-ready.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var receipt = intake.receive(archive);
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(receipt.received().packageId());
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, plan.intakeStatus());
		assertEquals(fixture.assignmentId(), plan.assignmentId());
		assertEquals(1, plan.assessments().size());
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_UPDATE, assessment.status());
		assertEquals(assessment.baselineRevisionId(), assessment.masterRevisionId());
		assertEquals(intake.proposedChanges(receipt.received().packageId()).getFirst().revisionId(),
				assessment.proposedRevisionId());
		assertNotNull(assessment.masterRecordSha256());

		// A preliminary review must not modify the coordinator's record.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterReviewSkipsAlreadyPresentContent() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Matching text", "review-identical.examwork");
		var intake = new MasterContributionIntake(fixture.master());
		var receipt = intake.receive(archive);
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					UPDATE questions
					SET question_text = 'Matching text'
					WHERE id = 81
					"""));
		}
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(receipt.received().packageId());
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.SKIP_IDENTICAL, assessment.status());
		assertNotNull(assessment.masterRevisionId());
		assertFalse(assessment.masterRevisionId().equals(assessment.baselineRevisionId()));
		assertEquals("Matching text", masterQuestionText(fixture.master()));
	}

	@Test
	void migrationToVersion35InstallsProtectedIntakeTables() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v35-migration.db"));
		database.initialiseSchema();
		assertEquals(37, database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {

				// Reconstruct schema v34 from a disposable v37 database.
				// This also removes v36 and v37 dependent structures.
				HistoricalSchemaFixture.removeVersion35Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 34
						WHERE version = 37
						"""));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(34, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationToVersion36InstallsProtectedReviewTables() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v36-migration.db"));
		database.initialiseSchema();
		assertEquals(37, database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {

				// Reconstruct v35 while retaining its intake structures.
				HistoricalSchemaFixture.removeVersion36Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 35
						WHERE version = 37
						"""));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(35, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationToVersion37InstallsProtectedApplicationTables() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v37-migration.db"));
		database.initialiseSchema();
		assertEquals(37, database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion37Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 36
						WHERE version = 37
						"""));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(36, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(37, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void rejectsBaselineChangedInsideOtherwiseValidArchive() throws Exception {
		Fixture fixture = fixture();
		Path issued = publish(fixture, "Modified question", "valid.examwork");
		var codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> records = codec
				.readRecords(new ByteArrayInputStream(readEntry(issued, "baseline/initial-records.jsonl")));
		List<ExchangeRecord> forged = new ArrayList<>();
		for (ExchangeRecord record : records) {
			if ("QUESTION".equals(record.recordType())) {
				ObjectNode values = (ObjectNode) record.values();
				values.put("question_text", "Altered issued baseline");
				forged.add(withValues(record, values));
			} else {
				forged.add(record);
			}
		}
		Path changed = repackage(issued, "baseline/initial-records.jsonl", encode(forged));
		assertArchiveWellFormed(changed, fixture.master());
		assertThrows(PackageFormatException.class,
				() -> new ContributionArchiveInspector(fixture.master()).inspect(changed));
	}

	@Test
	void rejectsForeignBookletReferenceInAValidContributionArchive() throws Exception {
		Fixture fixture = fixture();
		Path issued = publish(fixture, "Modified question", "valid.examwork");
		var codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> records = codec
				.readRecords(new ByteArrayInputStream(readEntry(issued, "records/contribution-changes.jsonl")));
		ExchangeRecord original = records.getFirst();
		assertEquals("QUESTION", original.recordType());
		ObjectNode values = (ObjectNode) original.values();
		values.put("booklet_id", UUID.randomUUID().toString());
		Path changed = repackage(issued, "records/contribution-changes.jsonl",
				encode(List.of(withValues(original, values))));
		assertArchiveWellFormed(changed, fixture.master());
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> new ContributionArchiveInspector(fixture.master()).inspect(changed));
		assertTrue(failure.getMessage().contains("out-of-scope"), failure.getMessage());
	}

	@Test
	void rejectsInconsistentContributorIdentityAndReturnLineage() throws Exception {
		Fixture fixture = fixture();
		Path issued = publish(fixture, "Modified question", "valid.examwork");
		var codec = new ExamWorkJsonlCodec();
		ExchangeRecord context = codec
				.readRecords(new ByteArrayInputStream(readEntry(issued, "records/contribution-context.jsonl")))
				.getFirst();
		ObjectNode wrongContributor = (ObjectNode) context.values();
		wrongContributor.put("contributor_id", UUID.randomUUID().toString());
		Path foreign = repackage(issued, "records/contribution-context.jsonl",
				encode(List.of(withValues(context, wrongContributor))));
		assertArchiveWellFormed(foreign, fixture.master());
		assertThrows(PackageFormatException.class,
				() -> new ContributionArchiveInspector(fixture.master()).inspect(foreign));
		ObjectNode wrongOrder = (ObjectNode) context.values();
		wrongOrder.put("return_number", 2);
		wrongOrder.putNull("previous_return_package_id");
		Path missingParent = repackage(issued, "records/contribution-context.jsonl",
				encode(List.of(withValues(context, wrongOrder))));
		assertArchiveWellFormed(missingParent, fixture.master());
		assertThrows(PackageFormatException.class,
				() -> new ContributionArchiveInspector(fixture.master()).inspect(missingParent));
	}

	@Test
	void rejectsOutOfScopeAssetOwner() throws Exception {
		Fixture fixture = fixture();
		Path issued = publish(fixture, "Modified question", "valid.examwork");
		var codec = new ExamWorkJsonlCodec();
		ExamWorkAssetIndex index = codec
				.readAssetIndex(new ByteArrayInputStream(readEntry(issued, ExamWorkAssetIndex.INDEX_PATH)));
		List<Reference> altered = new ArrayList<>();
		boolean changedOwner = false;
		for (Reference reference : index.references()) {
			if (!changedOwner && "SOURCE_PDF".equals(reference.usage())) {
				altered.add(new Reference(reference.archivePath(), reference.sha256(), reference.size(),
						reference.mediaType(), reference.managedPath(), reference.ownerType(), UUID.randomUUID(),
						reference.usage()));
				changedOwner = true;
			} else {
				altered.add(reference);
			}
		}
		assertTrue(changedOwner);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		codec.writeAssetIndex(new ExamWorkAssetIndex(altered), bytes);
		Path changed = repackage(issued, ExamWorkAssetIndex.INDEX_PATH, bytes.toByteArray());
		assertArchiveWellFormed(changed, fixture.master());
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> new ContributionArchiveInspector(fixture.master()).inspect(changed));
		assertTrue(failure.getMessage().contains("out-of-scope"), failure.getMessage());
	}

	@Test
	void rejectsSemanticallyInvalidContributionAfterMerge() throws Exception {
		Fixture fixture = fixture();
		Path original = publish(fixture, "Modified question", "semantic-valid.examwork");
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> changes = codec
				.readRecords(new ByteArrayInputStream(readEntry(original, "records/contribution-changes.jsonl")));
		assertEquals(1, changes.size());
		ExchangeRecord question = changes.getFirst();
		assertEquals("QUESTION", question.recordType());
		ObjectNode invalidValues = (ObjectNode) question.values();

		// A multiple-choice Question cannot carry two marks.
		// This package remains checksum-valid and structurally in scope.
		invalidValues.put("response_type", "MULTIPLE_CHOICE");
		invalidValues.put("marks", 2);
		Path invalid = repackage(original, "records/contribution-changes.jsonl",
				encode(List.of(withValues(question, invalidValues))));
		assertArchiveWellFormed(invalid, fixture.master());
		assertThrows(PackageFormatException.class,
				() -> new ContributionArchiveInspector(fixture.master()).inspect(invalid));
	}

	@Test
	void rejectsUnknownMasterAssignmentWithoutWritingMasterData() throws Exception {
		Fixture fixture = fixture();
		Path issued = publish(fixture, "Modified question", "valid.examwork");
		SqliteDatabase otherMaster = new SqliteDatabase(directory.resolve("unrelated.db"));
		otherMaster.initialiseSchema();
		assertThrows(PackageFormatException.class, () -> new ContributionArchiveInspector(otherMaster).inspect(issued));
		otherMaster.verifyIntegrity();
	}

	private void assertArchiveWellFormed(Path archive, SqliteDatabase database) throws Exception {
		assertNotNull(new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				database.schemaVersion()));
	}

	private byte[] digest(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private byte[] encode(List<ExchangeRecord> records) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(records, output);
		return output.toByteArray();
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Fixture fixture() throws Exception {
		ApplicationConfig coordinator = ApplicationConfig.fromDataRoot(directory.resolve("coordinator"));
		ApplicationConfig contributor = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		Files.createDirectories(coordinator.dataRoot());
		Files.createDirectories(contributor.dataRoot());
		SqliteDatabase master = new SqliteDatabase(coordinator.databasePath());
		SqliteDatabase local = new SqliteDatabase(contributor.databasePath());
		master.initialiseSchema();
		local.initialiseSchema();
		Path questionPdf = coordinator.dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answerPdf = coordinator.dataRoot().resolve("subjects/Biology/exams/answers.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.write(questionPdf, TestPdf.bytes("Question PDF content"));
		Files.write(answerPdf, TestPdf.bytes("Answer PDF content"));
		execute(master, """
				INSERT INTO subjects (id, subject_name) VALUES (1, 'Biology')
				""");
		execute(master, """
				INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current)
				VALUES (11, 1, 'Biology 2025', 1)
				""");
		execute(master, """
				INSERT INTO curriculum_nodes (id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name, curriculum_level, display_order)
				VALUES
				    (101, 11, NULL, 'B25U1', 'Biology Unit', 'UNIT', 0),
				    (102, 11, 101, 'B25U1T1', 'Biology Topic', 'TOPIC', 0),
				    (103, 11, 102, 'B25U1T1D1', 'Biology Descriptor', 'DESCRIPTOR', 0)
				""");
		execute(master, """
				INSERT INTO exam_providers (id, provider_name) VALUES (21, 'QCAA')
				""");
		execute(master, """
				INSERT INTO source_documents (id, relative_path) VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""");
		execute(master, """
				INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name,
				    expected_question_booklet_count, expected_answer_file_count)
				VALUES (31, 1, 21, 2025, 'External Assessment', 2, 1)
				""");
		execute(master, """
				INSERT INTO answer_files (id, exam_id, source_document_id, answer_file_name)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(master, """
				INSERT INTO exam_booklets (id, exam_id, source_document_id, answer_file_id, booklet_name)
				VALUES (61, 31, 51, 71, 'Paper 1'), (62, 31, 51, 71, 'Paper 2')
				""");
		execute(master, """
				INSERT INTO questions (id, booklet_id, classification_node_id,
				    question_code, question_text, marks, shared_context_capture_required)
				VALUES (81, 61, 103, '1', 'Biology question', 2, 0)
				""");
		execute(master, """
				INSERT INTO answers (id, question_id, answer_text)
				VALUES (91, 81, 'Biology answer')
				""");
		UUID assignmentId = UUID.randomUUID();
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		Path assignment = directory.resolve("issued.examwork");
		new WorkAssignmentExporter(master, new ManagedDataLayout(coordinator.dataRoot()))
				.export(new WorkAssignmentExporter.Request(exam, assignmentId, UUID.randomUUID(),
						"Integration Contributor", false, assignment));
		new WorkAssignmentLedger(master).register(assignment);
		new WorkAssignmentInstallationCoordinator(contributor).install(assignment);
		return new Fixture(master, local, contributor.dataRoot(), assignmentId);
	}

	private String masterQuestionText(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT question_text
						FROM questions
						WHERE id = 81
						""")) {
			assertTrue(result.next());
			String text = result.getString(1);
			assertFalse(result.next());
			return text;
		}
	}

	private Path publish(Fixture fixture, String text, String name) throws Exception {
		try (Connection connection = fixture.contributor().openConnection();
				Statement statement = connection.createStatement()) {

			// Count only directly updated Questions, excluding changes
			// made by the portable revision triggers.
			int affected = SqliteDirectChanges.executeUpdate(statement, """
					UPDATE questions
					SET question_text = '%s'
					WHERE question_code = '1'
					""".formatted(text));
			assertEquals(1, affected);
		}
		Path target = directory.resolve(name);
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), target);
		return target;
	}

	private byte[] readEntry(Path archive, String name) throws IOException {
		try (ZipFile zip = ZipFile.builder().setPath(archive).get();
				InputStream input = zip.getInputStream(zip.getEntry(name))) {
			return input.readAllBytes();
		}
	}

	private Path repackage(Path original, String modifiedPath, byte[] replacement) throws Exception {
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(original, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		Map<String, byte[]> contents = new HashMap<>();
		try (ZipFile zip = ZipFile.builder().setPath(original).get()) {
			for (Entry entry : manifest.entries()) {
				try (InputStream input = zip.getInputStream(zip.getEntry(entry.path()))) {
					contents.put(entry.path(), input.readAllBytes());
				}
			}
		}
		assertNotNull(contents.put(modifiedPath, replacement));
		List<Entry> entries = manifest.entries().stream().map(entry -> new Entry(entry.path(), entry.role(),
				contents.get(entry.path()).length, HexFormat.of().formatHex(digest(contents.get(entry.path())))))
				.toList();
		ExamWorkManifest altered = new ExamWorkManifest(manifest.formatVersion(), manifest.packageId(),
				manifest.packageType(), manifest.createdAt(), manifest.originInstallationId(),
				manifest.intendedMasterInstallationId(), manifest.subjectId(), manifest.subjectName(),
				manifest.assignmentId(), manifest.baselineSnapshotId(), manifest.producerApplicationVersion(),
				manifest.minimumReaderApplicationVersion(), manifest.producerSchemaVersion(),
				manifest.minimumReaderSchemaVersion(), manifest.scope(), entries);
		Map<String, EntrySource> sources = new HashMap<>();
		for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
			byte[] bytes = entry.getValue();
			sources.put(entry.getKey(), () -> new ByteArrayInputStream(bytes));
		}
		Path destination = directory.resolve("forged-" + UUID.randomUUID() + ".examwork");
		try (OutputStream output = Files.newOutputStream(destination)) {
			new ExamWorkArchiveWriter().write(altered, sources, output);
		}
		return destination;
	}

	private ExchangeRecord withValues(ExchangeRecord original, ObjectNode values) {
		return new ExchangeRecord(original.recordVersion(), original.recordType(), original.recordId(),
				original.revisionId(), original.baselineRevisionId(), values);
	}

	private record Fixture(SqliteDatabase master, SqliteDatabase contributor, Path contributorRoot, UUID assignmentId) {
	}
}
