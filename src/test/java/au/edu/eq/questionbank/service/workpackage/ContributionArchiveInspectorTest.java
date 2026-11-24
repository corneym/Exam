package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
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
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.HistoricalSchemaFixture;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;

class ContributionArchiveInspectorTest {

	@TempDir
	Path directory;

	@Test
	void approvedImagePreparationRejectsAnotherReceivedArchive() throws Exception {
		Fixture fixture = fixture();
		Path original = publishNewContributorImage(fixture, "original-image-return.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(original).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve original returned image");
		var preparer = new MasterContributionApprovedImagePreparer(fixture.master());
		assertEquals(1, preparer.prepare(review, original).size());

		// Construct a substituted archive without attempting
		// a prohibited second contributor submission.
		List<ExchangeRecord> alteredRecords = new ArrayList<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			if ("QUESTION".equals(proposal.recordType())) {
				ObjectNode altered = ((ObjectNode) proposal.values()).deepCopy();
				altered.put("question_text", "Substituted Question content");
				alteredRecords.add(withValues(proposal, altered));
			} else {
				alteredRecords.add(proposal);
			}
		}
		Path substituted = repackage(original, "records/contribution-changes.jsonl", encode(alteredRecords));

		// Verify that the substituted archive is independently valid,
		// but is not the archive recorded by the original intake.
		assertArchiveWellFormed(substituted, fixture.master());
		new ContributionArchiveInspector(fixture.master()).inspect(substituted);
		IOException failure = assertThrows(IOException.class, () -> preparer.prepare(review, substituted));
		assertTrue(failure.getMessage().contains("Image archive differs from durable"), failure.getMessage());
		assertNull(questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void approvedImagePreparationRejectsUnapprovedParentQuestion() throws Exception {
		Fixture fixture = fixture();
		Path archive = publishNewContributorImage(fixture, "rejected-parent-image.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					"QUESTION_IMAGE".equals(proposal.recordType()) ? MasterContributionDecisionLedger.Choice.QUEUE_APPLY
							: MasterContributionDecisionLedger.Choice.REJECT);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Reject parent Question but queue image");
		IOException failure = assertThrows(IOException.class,
				() -> new MasterContributionApprovedImagePreparer(fixture.master()).prepare(review, archive));
		assertTrue(failure.getMessage().contains("approved parent Question"), failure.getMessage());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void approvedImagePreparationRetainsExactBytesAndPortableOwnership() throws Exception {
		Fixture fixture = fixture();
		Path archive = publishNewContributorImage(fixture, "approved-image-preparation.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve Question and its new embedded image");
		var prepared = new MasterContributionApprovedImagePreparer(fixture.master()).prepare(review, archive);
		assertEquals(1, prepared.size());
		var image = prepared.getFirst();
		ExchangeRecord sourceImage = intake.proposedChanges(packageId).stream()
				.filter(record -> "QUESTION_IMAGE".equals(record.recordType())).findFirst().orElseThrow();
		assertEquals(sourceImage.recordId(), image.imageId());
		assertEquals(sourceImage.revisionId(), image.proposedRevisionId());
		assertEquals(sourceImage.values().path("question_id").asText(), image.questionId().toString());
		assertEquals(sourceImage.values().path("image_png").asText(), image.sha256());
		byte[] expected = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
		assertArrayEquals(expected, image.pngBytes());

		// A caller must not be able to mutate the prepared image
		// through the array returned by its accessor.
		byte[] altered = image.pngBytes();
		altered[0] = 0;
		assertArrayEquals(expected, image.pngBytes());

		// Preparation must not import the image or unlock the Exam.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM question_images
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void approvedSharedContextPreparationPreservesPngEvidenceAndMasterLock() throws Exception {
		Fixture fixture = fixture();
		byte[] png = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
		try (Connection connection = fixture.contributor().openConnection()) {
			connection.setAutoCommit(false);
			try {
				long bookletId;
				try (PreparedStatement lookup = connection.prepareStatement("""
						SELECT id
						FROM exam_booklets
						WHERE booklet_name = 'Paper 1'
						"""); ResultSet rows = lookup.executeQuery()) {
					assertTrue(rows.next());
					bookletId = rows.getLong(1);
					assertFalse(rows.next());
				}
				long contextId;
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO shared_question_contexts (
						    booklet_id, context_label
						) VALUES (?, 'Imported image context')
						""")) {
					insert.setLong(1, bookletId);
					assertEquals(1, insert.executeUpdate());
				}
				try (Statement statement = connection.createStatement();
						ResultSet rows = statement.executeQuery("SELECT last_insert_rowid()")) {
					assertTrue(rows.next());
					contextId = rows.getLong(1);
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO shared_question_context_images (
						    shared_context_id, image_order, image_png
						) VALUES (?, 0, ?)
						""")) {
					insert.setLong(1, contextId);
					insert.setBytes(2, png);
					assertEquals(1, insert.executeUpdate());
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO shared_question_context_content_parts (
						    shared_context_id, content_order,
						    content_type, region_order, image_order
						) VALUES (?, 0, 'IMAGE', NULL, 0)
						""")) {
					insert.setLong(1, contextId);
					assertEquals(1, insert.executeUpdate());
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		Path archive = directory.resolve("shared-context-return.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve returned Shared Context image");
		var prepared = new MasterContributionApprovedContextPreparer(fixture.master()).prepare(review, archive);
		assertEquals(1, prepared.size());
		var context = prepared.getFirst();
		assertEquals("SHARED_QUESTION_CONTEXT", context.proposal().recordType());
		assertEquals(1, context.images().size());
		var image = context.images().getFirst();
		assertEquals(0, image.imageOrder());
		assertArrayEquals(png, image.pngBytes());
		byte[] altered = image.pngBytes();
		altered[0] = 0;
		assertArrayEquals(png, image.pngBytes());

		// Preparation must not apply any contributor content.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM shared_question_contexts
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void approvedSharedContextPreparationRejectsDifferentReturnArchive() throws Exception {
		Fixture fixture = fixture();
		Path original = publish(fixture, "Original contributor response", "original-context-lineage.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(original).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Original contribution review");

		// Construct a different but structurally valid archive for the same
		// package identity. No second contributor export is performed.
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		ObjectNode alteredValues = ((ObjectNode) proposed.values()).deepCopy();
		alteredValues.put("question_text", "Substituted unapproved contributor response");
		Path substituted = repackage(original, "records/contribution-changes.jsonl",
				encode(List.of(withValues(proposed, alteredValues))));
		assertArchiveWellFormed(substituted, fixture.master());
		assertThrows(IOException.class,
				() -> new MasterContributionApprovedContextPreparer(fixture.master()).prepare(review, substituted));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void completedAssignmentRejectsFurtherExportButAcceptsExactReplay() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Accepted contributor revision", "accepted-return.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Accept final submitted work");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertThrows(IllegalStateException.class,
				() -> new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
						.export(fixture.assignmentId(), directory.resolve("late-return.examwork")));

		// Retrying transfer of the original package does not create
		// a competing second return.
		var replay = intake.receive(archive);
		assertTrue(replay.duplicate());
		assertEquals(packageId, replay.received().packageId());
		// A completed assignment cannot be approved for another merge.
		assertThrows(PackageFormatException.class,
				() -> new MasterContributionDecisionLedger(fixture.master()).recordMerge(packageId));
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("Accepted contributor revision", questionTextForCode(fixture.master(), "2"));
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void contributorAcknowledgementHistoryIsDatabaseProtected() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Protected acknowledged revision",
				"immutable-acknowledgement.examwork");
		String trustedDigest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		new ContributorReceiptAcknowledgementLedger(fixture.contributor()).acknowledge(receipt, trustedDigest);
		assertThrows(SQLException.class, () -> execute(fixture.contributor(), """
				UPDATE contributor_receipt_acknowledgements
				SET archive_sha256 =
				    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
				"""));
		assertThrows(SQLException.class, () -> execute(fixture.contributor(), """
				DELETE FROM contributor_receipt_acknowledgements
				"""));
		fixture.contributor().verifySchema();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void contributorAcknowledgementIsDurableIdempotentAndNonDestructive() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Acknowledged contributor revision",
				"durable-acknowledgement.examwork");
		String trustedDigest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributor());
		byte[] originalBaseline = sessions.readBaseline(fixture.assignmentId()).records();

		// Preserve work performed after the returned package was published.
		execute(fixture.contributor(), """
				UPDATE questions
				SET question_text = 'Unreturned local correction'
				WHERE question_code = '1'
				""");
		var ledger = new ContributorReceiptAcknowledgementLedger(fixture.contributor());
		var acknowledged = ledger.acknowledge(receipt, trustedDigest);
		assertEquals(fixture.assignmentId(), acknowledged.assignmentId());
		assertEquals(trustedDigest, acknowledged.archiveSha256());
		assertEquals(1, acknowledged.outcomes().size());
		assertEquals(1, acknowledged.acceptedMasterRecords().size());
		assertEquals("Acknowledged contributor revision",
				acknowledged.acceptedMasterRecords().getFirst().values().path("question_text").asText());
		var replay = ledger.acknowledge(receipt, trustedDigest);
		assertEquals(acknowledged, replay);

		// A new service instance must retrieve the durable result
		// without relying on any in-memory assessment.
		var reopened = new ContributorReceiptAcknowledgementLedger(fixture.contributor())
				.find(acknowledged.receiptPackageId()).orElseThrow();
		assertEquals(acknowledged, reopened);
		assertEquals("Unreturned local correction", masterQuestionText(fixture.contributor()));
		assertArrayEquals(originalBaseline, sessions.readBaseline(fixture.assignmentId()).records());
		try (Connection connection = fixture.contributor().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM contributor_receipt_acknowledgements
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
		fixture.contributor().verifySchema();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void contributorAcknowledgementRejectsForgedArchiveWithoutWriting() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Authentic master value", "acknowledgement-authentic.examwork");
		String trustedDigest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		List<ExchangeRecord> accepted = new ExamWorkJsonlCodec()
				.readRecords(new ByteArrayInputStream(readEntry(receipt, "records/accepted-master-state.jsonl")));
		ExchangeRecord original = accepted.getFirst();
		ObjectNode altered = (ObjectNode) original.values();
		altered.put("question_text", "Unauthorised replacement");
		Path forged = repackage(receipt, "records/accepted-master-state.jsonl",
				encode(List.of(withValues(original, altered))));
		var ledger = new ContributorReceiptAcknowledgementLedger(fixture.contributor());
		assertThrows(PackageFormatException.class, () -> ledger.acknowledge(forged, trustedDigest));
		try (Connection connection = fixture.contributor().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM contributor_receipt_acknowledgements
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Authentic master value", questionTextForCode(fixture.contributor(), "2"));
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void contributorAcknowledgementRetainsRejectedWork() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Contributor proposal to reject",
				"acknowledgement-rejected-source.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT), "Explicitly reject proposal");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		Path receipt = directory.resolve("acknowledgement-rejected.examwork");
		new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), receipt);
		String trustedDigest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var acknowledged = new ContributorReceiptAcknowledgementLedger(fixture.contributor()).acknowledge(receipt,
				trustedDigest);
		assertEquals(1, acknowledged.outcomes().size());
		assertTrue(acknowledged.acceptedMasterRecords().isEmpty());
		assertEquals("REJECTED", acknowledged.outcomes().getFirst().values().path("outcome").asText());
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Contributor proposal to reject", questionTextForCode(fixture.contributor(), "2"));
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertNull(questionTextForCode(fixture.master(), "2"));
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void contributorCannotExportReplacementOfIssuedImage() throws Exception {
		Fixture fixture = fixture(true);
		byte[] originalImage = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
		byte[] replacement = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
				+ "AAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");
		var identities = new SqlitePortableIdentityRepository(fixture.master());
		var originalIdentity = identities.findByLocalId(EntityType.QUESTION_IMAGE, 151L).orElseThrow();

		// Simulate an out-of-band database modification, bypassing
		// ordinary contributor capture and correction services.
		try (Connection connection = fixture.contributor().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE question_images
						SET image_png = ?
						WHERE question_id = (
						    SELECT id
						    FROM questions
						    WHERE question_code = '1'
						)
						""")) {
			statement.setBytes(1, replacement);
			assertEquals(1, statement.executeUpdate());
		}
		Path archive = directory.resolve("prohibited-issued-image-replacement.examwork");
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
						.export(fixture.assignmentId(), archive));
		assertTrue(failure.getMessage().contains("Existing coordinator-owned records cannot be modified"),
				failure.getMessage());

		// Rejected content must not become a published contribution
		// or cause the contributor assignment to be submitted.
		assertFalse(Files.exists(archive));
		assertTrue(
				new SqliteContributionReturnLedger(fixture.contributor()).findLatest(fixture.assignmentId()).isEmpty());
		try (Connection connection = fixture.master().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT image_png
						FROM question_images
						WHERE id = 151
						""");
				ResultSet rows = statement.executeQuery()) {
			assertTrue(rows.next());
			assertArrayEquals(originalImage, rows.getBytes(1));
			assertFalse(rows.next());
		}
		var retainedIdentity = identities.findByLocalId(EntityType.QUESTION_IMAGE, 151L).orElseThrow();
		assertEquals(originalIdentity.portableId(), retainedIdentity.portableId());
		assertEquals(originalIdentity.revisionId(), retainedIdentity.revisionId());
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void contributorInstallationUsesPortableIdentityIndependentOfLocalPrimaryKey() throws Exception {
		Fixture fixture = fixture();
		var masterIdentities = new SqlitePortableIdentityRepository(fixture.master());
		var contributorIdentities = new SqlitePortableIdentityRepository(fixture.contributor());
		var masterQuestion = masterIdentities.findByLocalId(EntityType.QUESTION, 81L).orElseThrow();
		var contributorQuestion = contributorIdentities
				.findByPortableId(EntityType.QUESTION, masterQuestion.portableId()).orElseThrow();

		// Portable identity survives installation, but the SQLite
		// primary key belongs exclusively to its local database.
		assertEquals(masterQuestion.portableId(), contributorQuestion.portableId());

		// Deliberately preserve different fixture-local IDs so that
		// future cross-database ID assumptions cannot pass unnoticed.
		assertNotEquals(masterQuestion.localId(), contributorQuestion.localId());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("Biology question", masterQuestionText(fixture.contributor()));
	}

	@Test
	void contributorRebaseAssessmentPreservesRejectedContribution() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Contributor text rejected by master", "rebase-rejected-source.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT),
				"Reject contributor proposal for rebase test");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		Path receipt = directory.resolve("rebase-rejected-receipt.examwork");
		new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), receipt);
		var plan = new ContributorReceiptRebasePlanner(fixture.contributor()).plan(receipt);
		assertEquals(1, plan.assessments().size());
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionApplicationCoordinator.Outcome.REJECTED, assessment.outcome());
		assertEquals(ContributorReceiptRebasePlanner.Disposition.PRESERVE_REJECTED, assessment.disposition());
		assertNull(assessment.canonicalMasterId());
		assertNull(assessment.resultingMasterRevisionId());

		// The master rejection cannot delete contributor work.
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Contributor text rejected by master", questionTextForCode(fixture.contributor(), "2"));
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertNull(questionTextForCode(fixture.master(), "2"));
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void contributorReceiptInspectionPreservesOutstandingLocalEdits() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Accepted contributor work", "inspect-valid-receipt.examwork");

		// This work was performed locally after the returned package
		// was published. Inspecting the receipt must not overwrite it.
		execute(fixture.contributor(), """
				UPDATE questions
				SET question_text = 'Outstanding later edit'
				WHERE question_code = '1'
				""");
		ContributorReceiptInspector.Inspection inspected = new ContributorReceiptInspector(fixture.contributor())
				.inspect(receipt);
		assertEquals(ExamWorkPackageType.IMPORT_RECEIPT, inspected.manifest().packageType());
		assertEquals(fixture.assignmentId(), inspected.manifest().assignmentId());
		assertEquals(new SqliteContributionReturnLedger(fixture.contributor()).findLatest(fixture.assignmentId())
				.orElseThrow().packageId(), inspected.returnPackageId());
		assertEquals(1, inspected.outcomes().size());
		assertEquals("APPLIED", inspected.outcomes().getFirst().values().path("outcome").asText());
		try (Connection connection = fixture.contributor().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT question_text
						FROM questions
						WHERE question_code = '1'
						""")) {
			assertTrue(result.next());
			assertEquals("Outstanding later edit", result.getString(1));
			assertFalse(result.next());
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void contributorReceiptInspectionRejectsInconsistentOutcome() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Accepted source contribution",
				"inspect-invalid-outcome.examwork");
		List<ExchangeRecord> records = new ExamWorkJsonlCodec()
				.readRecords(new ByteArrayInputStream(readEntry(receipt, "receipts/import-results.jsonl")));
		List<ExchangeRecord> changed = new ArrayList<>();
		for (ExchangeRecord record : records) {
			if ("IMPORT_RECEIPT_QUESTION".equals(record.recordType())) {
				ObjectNode values = (ObjectNode) record.values();

				// This contradicts the accepted APPLIED outcome.
				values.put("decision", "REJECT");
				changed.add(withValues(record, values));
			} else {
				changed.add(record);
			}
		}
		Path altered = repackage(receipt, "receipts/import-results.jsonl", encode(changed));
		assertArchiveWellFormed(altered, fixture.contributor());
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> new ContributorReceiptInspector(fixture.contributor()).inspect(altered));
		assertTrue(failure.getMessage().contains("decision and terminal outcome disagree"), failure.getMessage());
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void contributorReceiptInspectionRejectsUnpublishedReturn() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Receipt source contribution", "inspect-foreign-return.examwork");
		List<ExchangeRecord> records = new ExamWorkJsonlCodec()
				.readRecords(new ByteArrayInputStream(readEntry(receipt, "receipts/import-results.jsonl")));
		List<ExchangeRecord> changed = new ArrayList<>();
		for (ExchangeRecord record : records) {
			if ("IMPORT_RECEIPT_CONTEXT".equals(record.recordType())) {
				ObjectNode values = (ObjectNode) record.values();
				values.put("contribution_package_id", UUID.randomUUID().toString());
				changed.add(withValues(record, values));
			} else {
				changed.add(record);
			}
		}
		Path foreign = repackage(receipt, "receipts/import-results.jsonl", encode(changed));

		// The forged archive is checksum-valid, but its return
		// identity has no corresponding local publication evidence.
		assertArchiveWellFormed(foreign, fixture.contributor());
		SQLException failure = assertThrows(SQLException.class,
				() -> new ContributorReceiptInspector(fixture.contributor()).inspect(foreign));
		assertTrue(failure.getMessage().contains("unpublished local return"), failure.getMessage());
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void coordinatorAcceptanceDoesNotRewriteContributedWork() throws Exception {
		Fixture fixture = fixture();
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributor());
		byte[] originalBaseline = sessions.readBaseline(fixture.assignmentId()).records();
		Path receipt = exportAppliedReceipt(fixture, "Accepted contributor text", "accepted-contributor-work.examwork");
		assertTrue(Files.exists(receipt));

		// The accepted contribution is Question 2.
		// Question 1 belongs to the immutable issued baseline.
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Accepted contributor text", questionTextForCode(fixture.master(), "2"));
		assertEquals("Accepted contributor text", questionTextForCode(fixture.contributor(), "2"));

		// Coordinator reconciliation must not rewrite the contributor's
		// immutable assignment baseline.
		assertArrayEquals(originalBaseline, sessions.readBaseline(fixture.assignmentId()).records());
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void knownMasterViewRejectsUnacknowledgedReceiptIdentity() throws Exception {
		Fixture fixture = fixture();
		var service = new ContributorKnownMasterRebaseService(fixture.contributor());
		SQLException failure = assertThrows(SQLException.class, () -> service.view(UUID.randomUUID()));
		assertTrue(failure.getMessage().contains("Receipt has not been acknowledged"));
		assertEquals("Biology question", masterQuestionText(fixture.contributor()));
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void knownMasterViewRetainsAcknowledgementWithoutArchive() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Accepted authoritative revision", "known-master-view.examwork");
		String trustedDigest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributor());
		byte[] baseline = sessions.readBaseline(fixture.assignmentId()).records();
		var service = new ContributorKnownMasterRebaseService(fixture.contributor());
		var initial = service.acknowledgeAndView(receipt, trustedDigest);
		assertEquals(1, initial.entries().size());
		assertFalse(initial.supersededByNewerAssignment());
		assertEquals(ContributorKnownMasterRebaseService.Status.KNOWN_MASTER_RECORDED,
				initial.entries().getFirst().status());

		// Historical acknowledgement evidence must remain available
		// after the original receipt archive is no longer present.
		Files.delete(receipt);
		assertFalse(Files.exists(receipt));
		var reopened = new ContributorKnownMasterRebaseService(fixture.contributor());
		var retained = reopened.view(initial.acknowledgement().receiptPackageId());
		assertEquals(initial.acknowledgement(), retained.acknowledgement());
		assertEquals(1, retained.entries().size());
		assertFalse(retained.supersededByNewerAssignment());
		assertEquals(ContributorKnownMasterRebaseService.Status.KNOWN_MASTER_RECORDED,
				retained.entries().getFirst().status());
		assertEquals("Accepted authoritative revision",
				retained.entries().getFirst().masterRecord().values().path("question_text").asText());

		// Reading retained evidence must not change either
		// the issued baseline or the submitted contributor work.
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Accepted authoritative revision", questionTextForCode(fixture.contributor(), "2"));
		assertArrayEquals(baseline, sessions.readBaseline(fixture.assignmentId()).records());
		fixture.contributor().verifySchema();
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void knownMasterViewRetainsRejectedContributorProposal() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Contributor text not accepted by master",
				"known-master-rejected-source.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT), "Reject and retain contributor work");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		Path receipt = directory.resolve("known-master-rejected.examwork");
		new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), receipt);
		String trustedDigest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var view = new ContributorKnownMasterRebaseService(fixture.contributor()).acknowledgeAndView(receipt,
				trustedDigest);
		assertEquals(1, view.entries().size());
		assertEquals(ContributorKnownMasterRebaseService.Status.PRESERVE_REJECTED, view.entries().getFirst().status());
		assertNull(view.entries().getFirst().masterRecord());
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Contributor text not accepted by master", questionTextForCode(fixture.contributor(), "2"));
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertNull(questionTextForCode(fixture.master(), "2"));
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationAllowsFreshAssignmentAfterReconciliation() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Accepted partial work", "partial-return.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Accept partial work");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.master());
		assertEquals(WorkAssignmentLedger.State.COMPLETE, ledger.find(fixture.assignmentId()).orElseThrow().state());
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		UUID newAssignmentId = UUID.randomUUID();
		Path newArchive = directory.resolve("fresh-assignment.examwork");
		var request = new WorkAssignmentExporter.Request(exam, newAssignmentId, UUID.randomUUID(), "Next Contributor",
				false, newArchive);
		var newAssignment = new WorkAssignmentIssuanceService(fixture.master(),
				new ManagedDataLayout(directory.resolve("coordinator"))).issue(request);
		assertEquals(newAssignmentId, newAssignment.assignmentId());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, newAssignment.state());
		assertFalse(newAssignment.assignmentId().equals(fixture.assignmentId()));

		// The new baseline contains the accepted master content.
		String baselineText = new String(ledger.readBaseline(newAssignmentId).records(), StandardCharsets.UTF_8);
		assertTrue(baselineText.contains("Accepted partial work"));
		assertEquals(newAssignmentId,
				ledger.findActiveForExam(newAssignment.examPortableId()).orElseThrow().assignmentId());
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
	void masterApplicationImportsNewImageWithCanonicalMapping() throws Exception {
		Fixture fixture = fixture();
		Path archive = publishNewContributorImage(fixture, "approved-new-image-application.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		ExchangeRecord contributorImage = null;
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
			choices.put(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
			if ("QUESTION_IMAGE".equals(proposal.recordType())) {
				contributorImage = proposal;
			}
		}
		assertNotNull(contributorImage);
		assertEquals(2, choices.size());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Apply approved Question and new PNG");
		var applied = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertEquals(2, applied.outcomes().size());
		for (var outcome : applied.outcomes()) {
			assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, outcome.outcome());
			assertNotNull(outcome.resultingRevisionId());
		}
		long masterImageLocalId;
		UUID canonicalImageId;
		try (Connection connection = fixture.master().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT canonical_record_id, local_id
						FROM contribution_application_entity_mappings
						WHERE review_id = ?
						  AND record_type = 'QUESTION_IMAGE'
						  AND record_id = ?
						""")) {
			statement.setString(1, review.reviewId().toString());
			statement.setString(2, contributorImage.recordId().toString());
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				canonicalImageId = UUID.fromString(result.getString("canonical_record_id"));
				masterImageLocalId = result.getLong("local_id");
				assertTrue(masterImageLocalId > 0);
				assertFalse(result.next());
			}
		}
		var canonicalIdentity = new SqlitePortableIdentityRepository(fixture.master())
				.findByLocalId(EntityType.QUESTION_IMAGE, masterImageLocalId).orElseThrow();
		assertEquals(canonicalImageId, canonicalIdentity.portableId());
		assertEquals(contributorImage.recordId(), new SqlitePortableIdentityRepository(fixture.contributor())
				.findByPortableId(EntityType.QUESTION_IMAGE, contributorImage.recordId()).orElseThrow().portableId());
		try (Connection connection = fixture.master().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT image.image_png,
						       part.image_id
						FROM question_images image
						JOIN question_content_parts part
						  ON part.image_id = image.id
						JOIN questions question
						  ON question.id = image.question_id
						WHERE question.question_code = '2'
						""");
				ResultSet result = statement.executeQuery()) {
			assertTrue(result.next());
			byte[] expected = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
					+ "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
			assertArrayEquals(expected, result.getBytes("image_png"));
			assertEquals(masterImageLocalId, result.getLong("image_id"));
			assertFalse(result.next());
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());

		// Replaying a committed image review must not insert a
		// second image and must not require the source archive.
		var replay = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertTrue(replay.duplicate());
		assertEquals(applied.outcomes(), replay.outcomes());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterApplicationImportsNewQuestionAndAnswer() throws Exception {
		Fixture fixture = fixture();
		addNewContributorQuestionAndAnswer(fixture);
		Path archive = directory.resolve("new-question-answer.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		List<ExchangeRecord> proposed = intake.proposedChanges(packageId);
		assertEquals(2, proposed.size());
		var review = new MasterContributionDecisionLedger(fixture.master()).recordMerge(packageId);

		assertEquals(2, review.decisions().size());

		assertTrue(review.decisions().stream()
				.allMatch(decision -> decision.choice() == MasterContributionDecisionLedger.Choice.QUEUE_APPLY));
		for (var decision : review.decisions()) {
			assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, decision.preliminaryStatus());
		}
		var coordinator = new MasterContributionApplicationCoordinator(fixture.master());
		var result = coordinator.execute(review.reviewId());
		assertFalse(result.duplicate());
		assertEquals(2, result.outcomes().size());
		for (var outcome : result.outcomes()) {
			assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, outcome.outcome());
			assertNotNull(outcome.resultingRevisionId());
		}
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT question.question_text,
						       answer.answer_text
						FROM questions question
						JOIN answers answer
						  ON answer.question_id = question.id
						WHERE question.question_code = '2'
						""")) {
			assertTrue(rows.next());
			assertEquals("New contributor question", rows.getString("question_text"));
			assertEquals("New contributor answer", rows.getString("answer_text"));
			assertFalse(rows.next());
		}
		try (Connection connection = fixture.master().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT COUNT(*)
						FROM contribution_application_entity_mappings
						WHERE review_id = ?
						""")) {
			statement.setString(1, review.reviewId().toString());
			try (ResultSet rows = statement.executeQuery()) {
				assertTrue(rows.next());
				assertEquals(2, rows.getInt(1));
			}
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals(result.outcomes(), coordinator.execute(review.reviewId()).outcomes());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationImportsOrderedMixedQuestionContent() throws Exception {
		Fixture fixture = fixture(true);

		// The coordinator's Question 1 already has an image.
		// Build a separate new Question 2 with its own PDF region
		// and a new image containing the same valid PNG bytes.
		execute(fixture.contributor(), """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required,
				    response_type, source_capture_required
				)
				SELECT booklet_id, classification_node_id,
				       '2', 'Accepted mixed Question content', marks,
				       shared_context_capture_required,
				       response_type, source_capture_required
				FROM questions
				WHERE question_code = '1'
				""");
		execute(fixture.contributor(), """
				INSERT INTO question_regions (
				    question_id, region_order, booklet_id,
				    page_number, x, y, width, height
				)
				SELECT id, 0, booklet_id, 1,
				       0.1, 0.1, 0.3, 0.3
				FROM questions
				WHERE question_code = '2'
				""");
		execute(fixture.contributor(), """
				INSERT INTO question_images (question_id, image_png)
				SELECT target.id, source_image.image_png
				FROM questions target
				CROSS JOIN question_images source_image
				JOIN questions source_question
				  ON source_question.id = source_image.question_id
				WHERE target.question_code = '2'
				  AND source_question.question_code = '1'
				""");
		execute(fixture.contributor(), """
				INSERT INTO question_content_parts (
				    question_id, content_order,
				    content_type, region_order, image_id
				)
				SELECT id, 0, 'PDF_REGION', 0, NULL
				FROM questions
				WHERE question_code = '2'
				""");
		execute(fixture.contributor(), """
				INSERT INTO question_content_parts (
				    question_id, content_order,
				    content_type, region_order, image_id
				)
				SELECT question.id, 1, 'IMAGE', NULL, image.id
				FROM questions question
				JOIN question_images image
				  ON image.question_id = question.id
				WHERE question.question_code = '2'
				""");
		Path archive = directory.resolve("mixed-content-return.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord record : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(record.recordType(), record.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size(), "The new Question and new Image are separate proposals");
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Accept ordered new Question content");
		var applied = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertEquals(2, applied.outcomes().size());
		assertTrue(applied.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("Accepted mixed Question content", questionTextForCode(fixture.master(), "2"));
		try (Connection connection = fixture.master().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT part.content_type,
						       part.content_order,
						       part.region_order,
						       part.image_id
						FROM question_content_parts part
						JOIN questions question
						  ON question.id = part.question_id
						WHERE question.question_code = '2'
						ORDER BY part.content_order
						""");
				ResultSet rows = statement.executeQuery()) {
			assertTrue(rows.next());
			assertEquals("PDF_REGION", rows.getString("content_type"));
			assertEquals(0, rows.getInt("content_order"));
			assertEquals(0, rows.getInt("region_order"));
			assertNull(rows.getObject("image_id"));
			assertTrue(rows.next());
			assertEquals("IMAGE", rows.getString("content_type"));
			assertEquals(1, rows.getInt("content_order"));
			assertNull(rows.getObject("region_order"));
			assertNotNull(rows.getObject("image_id"));
			assertFalse(rows.next());
		}

		// The issued image identity remains unchanged on both installations.
		var masterIdentities = new SqlitePortableIdentityRepository(fixture.master());
		var contributorIdentities = new SqlitePortableIdentityRepository(fixture.contributor());
		var originalImage = masterIdentities.findByLocalId(EntityType.QUESTION_IMAGE, 151L).orElseThrow();
		var installedOriginalImage = contributorIdentities
				.findByPortableId(EntityType.QUESTION_IMAGE, originalImage.portableId()).orElseThrow();
		assertEquals(originalImage.portableId(), installedOriginalImage.portableId());
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterApplicationImportsVerifiedAnswerRegion() throws Exception {
		Fixture fixture = fixture();

		// Capture a new contributor-owned Question and Answer.
		addNewContributorQuestionAndAnswer(fixture);
		execute(fixture.contributor(), """
				INSERT INTO answer_regions (
				    answer_id,
				    region_order,
				    answer_file_id,
				    page_number,
				    x, y, width, height
				)
				SELECT
				    answer.id,
				    0,
				    booklet.answer_file_id,
				    1,
				    0.15, 0.25, 0.35, 0.45
				FROM answers answer
				JOIN questions question
				  ON question.id = answer.question_id
				JOIN exam_booklets booklet
				  ON booklet.id = question.booklet_id
				WHERE question.question_code = '2'
				""");
		Path archive = directory.resolve("answer-region-return.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposed : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size());
		assertTrue(choices.keySet().stream().anyMatch(key -> "QUESTION".equals(key.recordType())));
		assertTrue(choices.keySet().stream().anyMatch(key -> "ANSWER".equals(key.recordType())));
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Accept verified new Answer PDF region");
		var coordinator = new MasterContributionApplicationCoordinator(fixture.master());

		// The archive-free path cannot apply the PDF region.
		assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId()));
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		assertNull(questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		var result = coordinator.execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertFalse(result.duplicate());
		assertEquals(2, result.outcomes().size());
		assertTrue(result.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));

		// Resolve the imported Answer through its Question rather
		// than assuming contributor and coordinator local IDs agree.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    question.question_code,
						    answer.answer_text,
						    region.region_order,
						    region.answer_file_id,
						    region.page_number,
						    region.x,
						    region.y,
						    region.width,
						    region.height
						FROM answer_regions region
						JOIN answers answer
						  ON answer.id = region.answer_id
						JOIN questions question
						  ON question.id = answer.question_id
						""")) {
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			assertEquals("New contributor answer", rows.getString("answer_text"));
			assertEquals(0, rows.getInt("region_order"));
			assertEquals(71, rows.getLong("answer_file_id"));
			assertEquals(1, rows.getInt("page_number"));
			assertEquals(0.15, rows.getDouble("x"), 0.000001);
			assertEquals(0.25, rows.getDouble("y"), 0.000001);
			assertEquals(0.35, rows.getDouble("width"), 0.000001);
			assertEquals(0.45, rows.getDouble("height"), 0.000001);

			// No region was added to the original issued Answer.
			assertFalse(rows.next());
		}
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("New contributor question", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterApplicationImportsVerifiedQuestionRegion() throws Exception {
		Fixture fixture = fixture();

		// Create a new Question without changing the issued Question.
		execute(fixture.contributor(), """
				INSERT INTO questions (
				    booklet_id,
				    classification_node_id,
				    question_code,
				    question_text,
				    marks,
				    shared_context_capture_required,
				    response_type,
				    source_capture_required
				)
				SELECT
				    booklet_id,
				    classification_node_id,
				    '2',
				    'New Question with PDF region',
				    marks,
				    shared_context_capture_required,
				    response_type,
				    source_capture_required
				FROM questions
				WHERE question_code = '1'
				""");
		execute(fixture.contributor(), """
				INSERT INTO question_regions (
				    question_id,
				    region_order,
				    booklet_id,
				    page_number,
				    x, y, width, height
				)
				SELECT
				    id,
				    0,
				    booklet_id,
				    1,
				    0.1, 0.2, 0.3, 0.4
				FROM questions
				WHERE question_code = '2'
				""");

		// Include explicit ordered content referencing the new region.
		execute(fixture.contributor(), """
				INSERT INTO question_content_parts (
				    question_id,
				    content_order,
				    content_type,
				    region_order,
				    image_id
				)
				SELECT
				    id,
				    0,
				    'PDF_REGION',
				    0,
				    NULL
				FROM questions
				WHERE question_code = '2'
				""");
		Path archive = directory.resolve("question-region-return.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		List<ExchangeRecord> proposals = intake.proposedChanges(packageId);
		assertEquals(1, proposals.size());
		ExchangeRecord proposed = proposals.getFirst();
		assertEquals("QUESTION", proposed.recordType());
		assertNull(proposed.baselineRevisionId());
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Accept verified new Question PDF region");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());

		// Archive verification is mandatory before PDF regions
		// and their content parts may be imported.
		assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId()));
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		assertNull(questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		var applied = coordinator.execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertFalse(applied.duplicate());
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, applied.outcomes().getFirst().outcome());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    question.question_code,
						    region.region_order,
						    region.booklet_id,
						    region.page_number,
						    region.x,
						    region.y,
						    region.width,
						    region.height
						FROM question_regions region
						JOIN questions question
						  ON question.id = region.question_id
						""")) {
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			assertEquals(0, rows.getInt("region_order"));
			assertEquals(61, rows.getLong("booklet_id"));
			assertEquals(1, rows.getInt("page_number"));
			assertEquals(0.1, rows.getDouble("x"), 0.000001);
			assertEquals(0.2, rows.getDouble("y"), 0.000001);
			assertEquals(0.3, rows.getDouble("width"), 0.000001);
			assertEquals(0.4, rows.getDouble("height"), 0.000001);
			assertFalse(rows.next());
		}
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    question.question_code,
						    part.content_order,
						    part.content_type,
						    part.region_order,
						    part.image_id
						FROM question_content_parts part
						JOIN questions question
						  ON question.id = part.question_id
						""")) {
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			assertEquals(0, rows.getInt("content_order"));
			assertEquals("PDF_REGION", rows.getString("content_type"));
			assertEquals(0, rows.getInt("region_order"));
			assertNull(rows.getObject("image_id"));
			assertFalse(rows.next());
		}
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("New Question with PDF region", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		var replay = coordinator.execute(review.reviewId());
		assertTrue(replay.duplicate());
		assertEquals(applied.outcomes(), replay.outcomes());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterApplicationInsertsNewContributorQuestionAndClosesAssignment() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Applied contributor text", "apply-new-question.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		assertEquals("QUESTION", proposal.recordType());
		assertNull(proposal.baselineRevisionId());
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Accept new contributor Question");
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

		// Coordinator-owned Question 1 is unchanged. New Question 2 is inserted.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("Applied contributor text", questionTextForCode(fixture.master(), "2"));
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.master());
		var assignment = ledger.find(fixture.assignmentId()).orElseThrow();
		assertEquals(WorkAssignmentLedger.State.COMPLETE, assignment.state());
		assertTrue(ledger.findActiveForExam(assignment.examPortableId()).isEmpty());
		assertEquals(applied, coordinator.find(review.reviewId()).orElseThrow());

		// Replaying an approved application must be idempotent.
		var replay = coordinator.execute(review.reviewId());
		assertTrue(replay.duplicate());
		assertEquals(applied.outcomes(), replay.outcomes());
		assertEquals(applied.completedAt(), replay.completedAt());
		assertEquals("Applied contributor text", questionTextForCode(fixture.master(), "2"));
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationInsertsReviewedAnswerForNewQuestion() throws Exception {
		Fixture fixture = fixture();
		addNewContributorQuestionAndAnswer(fixture);

		// Correct the contributor's own new Answer before final submission.
		execute(fixture.contributor(), """
				UPDATE answers
				SET answer_text = 'Reviewed contributor answer'
				WHERE question_id IN (
				    SELECT id
				    FROM questions
				    WHERE question_code = '2'
				)
				""");
		Path archive = directory.resolve("reviewed-new-answer.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		List<ExchangeRecord> proposed = intake.proposedChanges(packageId);
		assertEquals(2, proposed.size());
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord record : proposed) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(record.recordType(), record.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve new Question and Answer");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertEquals(2, result.outcomes().size());
		assertTrue(result.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT question.question_code, answer.answer_text
						FROM questions question
						JOIN answers answer ON answer.question_id = question.id
						ORDER BY question.question_code
						""")) {
			assertTrue(rows.next());
			assertEquals("1", rows.getString("question_code"));
			assertEquals("Biology answer", rows.getString("answer_text"));
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			assertEquals("Reviewed contributor answer", rows.getString("answer_text"));
			assertFalse(rows.next());
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationPreservesIssuedClassificationAndImportsNewClassification() throws Exception {
		Fixture fixture = fixture();

		// Create the worker's own Question with the classification selected
		// before submitting it. The issued Question remains unchanged.
		execute(fixture.contributor(), """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required,
				    response_type, source_capture_required
				)
				SELECT booklet_id,
				       (SELECT id
				        FROM curriculum_nodes
				        WHERE curriculum_code = 'B25U1T1D2'),
				       '2', 'Newly classified Question', marks,
				       shared_context_capture_required,
				       response_type, source_capture_required
				FROM questions
				WHERE question_code = '1'
				""");
		Path archive = directory.resolve("new-classification.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		assertEquals("QUESTION", proposed.recordType());
		assertNull(proposed.baselineRevisionId());
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Accept new classified Question");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, result.outcomes().getFirst().outcome());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT question_code, classification_node_id
						FROM questions
						ORDER BY question_code
						""")) {
			assertTrue(rows.next());
			assertEquals("1", rows.getString("question_code"));
			assertEquals(103, rows.getLong("classification_node_id"));
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			assertEquals(104, rows.getLong("classification_node_id"));
			assertFalse(rows.next());
		}
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationPreventsCoordinatorEditBetweenReviewAndApply() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor proposal", "apply-stale.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Review before attempted coordinator edit");

		// A later ordinary coordinator edit cannot invalidate the
		// outstanding baseline or override the approved contribution.
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Later coordinator correction'
				WHERE id = 81
				"""));
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		var result = coordinator.execute(review.reviewId());
		assertFalse(result.duplicate());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("Contributor proposal", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
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
	void masterApplicationRefusesSecondContributorSubmission() throws Exception {
		Fixture fixture = fixture();
		Path firstArchive = publish(fixture, "One final contributor submission", "competing-first.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(firstArchive).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Accept the only valid contribution");

		// An additional contribution cannot be published for this assignment.
		assertThrows(IllegalStateException.class,
				() -> new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
						.export(fixture.assignmentId(), directory.resolve("competing-second.examwork")));
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW,
				intake.find(packageId).orElseThrow().status());
		var applied = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, applied.outcomes().getFirst().outcome());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("One final contributor submission", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationRejectsUnjustifiedIdenticalSkip() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Already accepted content", "apply-identical.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Already accepted content'
				WHERE id = 81
				"""));
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());

		// A skip is valid only when the current master content
		// genuinely matches the proposal. The locked baseline does not.
		assertThrows(IllegalArgumentException.class,
				() -> new MasterContributionDecisionLedger(fixture.master()).record(packageId,
						Map.of(key, MasterContributionDecisionLedger.Choice.SKIP_IDENTICAL),
						"Claiming content was already accepted"));
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterApplicationRequiresVerifiedArchiveForContentParts() throws Exception {
		Fixture fixture = fixture();
		Path archive = publishNewContributorImage(fixture, "verified-new-content-parts.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve new Question with ordered image content");
		var coordinator = new MasterContributionApplicationCoordinator(fixture.master());

		// The scalar-only path cannot apply image content without
		// verified binary evidence from the received archive.
		SQLException failure = assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId()));
		assertTrue(failure.getMessage().contains("Prepared Question image set differs from review"),
				failure.getMessage());
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		assertNull(questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());

		// The same review can succeed with its verified archive.
		var result = coordinator.execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertFalse(result.duplicate());
		assertEquals(2, result.outcomes().size());
		assertTrue(result.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    question.question_code,
						    part.content_order,
						    part.content_type,
						    part.region_order,
						    part.image_id
						FROM question_content_parts part
						JOIN questions question
						  ON question.id = part.question_id
						""")) {
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			assertEquals(0, rows.getInt("content_order"));
			assertEquals("IMAGE", rows.getString("content_type"));
			assertNull(rows.getObject("region_order"));
			assertNotNull(rows.getObject("image_id"));
			assertFalse(rows.next());
		}
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("Contributor Question with image", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationRollsBackDependentInsertFailure() throws Exception {
		Fixture fixture = fixture();
		addNewContributorQuestionAndAnswer(fixture);
		Path archive = directory.resolve("rollback-question-answer.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var review = new MasterContributionDecisionLedger(fixture.master()).recordMerge(packageId);

		// Force failure only when the newly approved Answer reaches
		// the actual master INSERT. This tests transactional rollback
		// rather than archive-preflight rejection.
		execute(fixture.master(), """
				CREATE TRIGGER trg_test_reject_new_answer
				BEFORE INSERT ON answers
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Injected Answer insert failure'
				    );
				END;
				""");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId()));
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT COUNT(*)
						FROM questions
						WHERE question_code = '2'
						""")) {
			assertTrue(rows.next());
			assertEquals(0, rows.getInt(1));
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT COUNT(*)
						FROM master_contribution_write_permits
						""")) {
			assertTrue(rows.next());
			assertEquals(0, rows.getInt(1));
		}
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationRollsBackMixedContentInsertionFailure() throws Exception {
		Fixture fixture = fixture();

		// Create a new contributor-owned Question 2.
		// The issued Question 1 must remain unchanged.
		execute(fixture.contributor(), """
				INSERT INTO questions (
				    booklet_id,
				    classification_node_id,
				    question_code,
				    question_text,
				    marks,
				    shared_context_capture_required,
				    response_type,
				    source_capture_required
				)
				SELECT
				    booklet_id,
				    classification_node_id,
				    '2',
				    'Content that must roll back',
				    marks,
				    shared_context_capture_required,
				    response_type,
				    source_capture_required
				FROM questions
				WHERE question_code = '1'
				""");

		// Add the PDF region to Question 2, never Question 1.
		execute(fixture.contributor(), """
				INSERT INTO question_regions (
				    question_id,
				    region_order,
				    booklet_id,
				    page_number,
				    x, y, width, height
				)
				SELECT
				    id, 0, booklet_id, 1,
				    0.1, 0.1, 0.4, 0.4
				FROM questions
				WHERE question_code = '2'
				""");
		execute(fixture.contributor(), """
				INSERT INTO question_content_parts (
				    question_id,
				    content_order,
				    content_type,
				    region_order,
				    image_id
				)
				SELECT
				    id, 0, 'PDF_REGION', 0, NULL
				FROM questions
				WHERE question_code = '2'
				""");
		assertEquals("Biology question", masterQuestionText(fixture.contributor()));
		Path archive = directory.resolve("mixed-content-rollback.examwork");

		// Export directly. The old publish() helper must not be used.
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		List<ExchangeRecord> proposed = intake.proposedChanges(packageId);
		assertEquals(1, proposed.size());
		assertEquals("QUESTION", proposed.getFirst().recordType());
		assertNull(proposed.getFirst().baselineRevisionId());
		ExchangeRecord proposal = proposed.getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Test atomic rollback of new Question content");

		// Inject a failure during the new Question's content insertion.
		execute(fixture.master(), """
				CREATE TRIGGER test_reject_contribution_content_part
				BEFORE INSERT ON question_content_parts
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Injected mixed-content insertion failure'
				    );
				END
				""");
		assertThrows(SQLException.class, () -> new MasterContributionApplicationCoordinator(fixture.master())
				.execute(review.reviewId(), archive, new ManagedDataLayout(directory.resolve("coordinator"))));

		// The original coordinator-owned Question is unchanged.
		assertEquals("Biology question", masterQuestionText(fixture.master()));

		// The new Question and its content must not be partially committed.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    (SELECT COUNT(*)
						     FROM questions
						     WHERE question_code = '2'),
						    (SELECT COUNT(*)
						     FROM question_regions),
						    (SELECT COUNT(*)
						     FROM question_content_parts),
						    (SELECT COUNT(*)
						     FROM contribution_application_results)
						""")) {
			assertTrue(rows.next());
			assertEquals(0, rows.getInt(1));
			assertEquals(0, rows.getInt(2));
			assertEquals(0, rows.getInt(3));
			assertEquals(0, rows.getInt(4));
		}
		assertTrue(new MasterContributionApplicationCoordinator(fixture.master()).find(review.reviewId()).isEmpty());

		// Failed application must not close or unlock the assignment.
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationRollsBackNewImageAndQuestionOnContentFailure() throws Exception {
		Fixture fixture = fixture();
		Path archive = publishNewContributorImage(fixture, "new-image-rollback.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Rollback an approved PNG import on failure");

		// The image has already been inserted by the time the
		// Question content part is written. This forces an error
		// after the binary database mutation has begun.
		execute(fixture.master(), """
				CREATE TRIGGER test_reject_new_image_content_part
				BEFORE INSERT ON question_content_parts
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Injected image content-part failure'
				    );
				END
				""");
		SQLException failure = assertThrows(SQLException.class,
				() -> new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), archive,
						new ManagedDataLayout(directory.resolve("coordinator"))));
		assertTrue(failure.getMessage().contains("Injected image content-part failure"), failure.getMessage());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    (SELECT COUNT(*) FROM question_images),
						    (SELECT COUNT(*) FROM question_content_parts),
						    (SELECT COUNT(*)
						     FROM contribution_application_results),
						    (SELECT COUNT(*)
						     FROM contribution_application_entity_mappings)
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
			assertEquals(0, result.getInt(2));
			assertEquals(0, result.getInt(3));
			assertEquals(0, result.getInt(4));
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertTrue(new MasterContributionApplicationCoordinator(fixture.master()).find(review.reviewId()).isEmpty());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationWithChangedPdfRollsBackAndStaysLocked() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor PDF-dependent work", "pdf-conflict.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY), "Reviewed before PDF conflict");
		Files.writeString(directory.resolve("coordinator").resolve("subjects/Biology/exams/answers.pdf"),
				"Externally changed Answer source");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		assertThrows(IOException.class, () -> coordinator.execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator"))));
		assertTrue(coordinator.find(review.reviewId()).isEmpty());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterApplicationWithVerifiedArchiveCommitsAndUnlocks() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Verified contributor change", "verified-application.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var review = new MasterContributionDecisionLedger(fixture.master()).recordMerge(packageId);
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		var result = coordinator.execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertFalse(result.duplicate());
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, result.outcomes().getFirst().outcome());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals("Verified contributor change", questionTextForCode(fixture.master(), "2"));
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.master());
		var assignment = ledger.find(fixture.assignmentId()).orElseThrow();
		assertEquals(WorkAssignmentLedger.State.COMPLETE, assignment.state());
		assertTrue(ledger.findActiveForExam(assignment.examPortableId()).isEmpty());

		// Replay does not require the received archive once a
		// complete application and closure have been committed.
		Files.delete(archive);
		var replay = coordinator.execute(review.reviewId());
		assertTrue(replay.duplicate());
		assertEquals(result.outcomes(), replay.outcomes());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterAppliesReviewedSharedContextAndQuestionReference() throws Exception {
		Fixture fixture = fixture();
		Path archive = publishNewContributorContext(fixture, "applied-context-return.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve Shared Context and linked Question");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertFalse(result.duplicate());
		assertEquals(2, result.outcomes().size());
		assertTrue(result.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT context.context_label,
						       question.shared_context_id,
						       image.image_png
						FROM questions question
						JOIN shared_question_contexts context
						  ON context.id = question.shared_context_id
						JOIN shared_question_context_images image
						  ON image.shared_context_id = context.id
						 AND image.image_order = 0
						WHERE question.question_code = '2'
						""")) {
			assertTrue(rows.next());
			assertEquals("Imported shared image", rows.getString("context_label"));
			assertTrue(rows.getLong("shared_context_id") > 0);
			assertNotNull(rows.getBytes("image_png"));
			assertFalse(rows.next());
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		var replay = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertTrue(replay.duplicate());
		assertEquals(result.outcomes(), replay.outcomes());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterAssetPreflightAcceptsUnchangedSources() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "verified-assets.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ManagedDataLayout layout = new ManagedDataLayout(directory.resolve("coordinator"));
		MasterContributionAssetPreflight preflight = new MasterContributionAssetPreflight(fixture.master(), layout);
		preflight.verify(packageId, archive);

		// Asset verification is read-only and cannot unlock the Exam.
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterAssetPreflightRejectsChangedMasterPdf() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "changed-master-pdf.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Path masterPdf = directory.resolve("coordinator").resolve("subjects/Biology/exams/questions.pdf");

		// Simulate external filesystem tampering. Ordinary managed-PDF
		// replacement services are already blocked by Batch 02.
		Files.writeString(masterPdf, "Unexpected replacement of master source PDF");
		MasterContributionAssetPreflight preflight = new MasterContributionAssetPreflight(fixture.master(),
				new ManagedDataLayout(directory.resolve("coordinator")));
		IOException failure = assertThrows(IOException.class, () -> preflight.verify(packageId, archive));
		assertTrue(failure.getMessage().contains("Master PDF bytes differ from issued dependency"),
				failure.getMessage());
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterAssetPreflightRejectsDifferentReturnArchive() throws Exception {
		Fixture fixture = fixture();
		Path original = publish(fixture, "First contributor revision", "verified-first.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(original).received().packageId();

		// A different archive is constructed without publishing a prohibited
		// second contribution from the contributor installation.
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		ObjectNode altered = ((ObjectNode) proposed.values()).deepCopy();
		altered.put("question_text", "Substituted return content");
		Path substituted = repackage(original, "records/contribution-changes.jsonl",
				encode(List.of(withValues(proposed, altered))));
		MasterContributionAssetPreflight preflight = new MasterContributionAssetPreflight(fixture.master(),
				new ManagedDataLayout(directory.resolve("coordinator")));

		// It must not accept substituted archive bytes as the recorded return.
		assertThrows(IOException.class, () -> preflight.verify(packageId, substituted));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterAssignmentLockRejectsUnreviewedImageInsertion() throws Exception {
		Fixture fixture = fixture();
		SQLException failure = assertThrows(SQLException.class, () -> execute(fixture.master(), """
				INSERT INTO question_images (
				    question_id,
				    image_png
				)
				VALUES (
				    81,
				    X'89504E470D0A1A0A'
				)
				"""));
		assertTrue(failure.getMessage().contains("Assigned Exam image insertion"), failure.getMessage());
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM question_images
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
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
	void masterDecisionsQueueNewQuestionWithoutApplyingIt() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "decision-new-question.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		var recorded = ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Approve a new contributor Question");
		assertEquals(packageId, recorded.packageId());
		assertEquals(1, recorded.decisions().size());
		var decision = recorded.decisions().getFirst();
		assertEquals(MasterContributionDecisionLedger.Choice.QUEUE_APPLY, decision.choice());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, decision.preliminaryStatus());
		assertNull(decision.baselineRevisionId());
		assertNull(decision.masterRevisionId());
		assertNull(decision.resultRevisionId());
		assertEquals(recorded, ledger.find(recorded.reviewId()).orElseThrow());

		// Recording the approval does not insert the proposed Question.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertNull(questionTextForCode(fixture.master(), "2"));
	}

	@Test
	void masterDecisionsRejectCancelledAssignmentReturn() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor Question returned after cancellation",
				"cancelled-return.examwork");
		WorkAssignmentLedger assignments = new WorkAssignmentLedger(fixture.master());
		assignments.closeWithoutReturn(fixture.assignmentId(),
				"Coordinator cancelled assignment before receiving work");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var received = intake.receive(archive);
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, received.received().status());
		UUID packageId = received.received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger decisions = new MasterContributionDecisionLedger(fixture.master());
		// The simplified merge workflow must not approve a late
		// return from an already cancelled assignment.
		assertThrows(PackageFormatException.class, () -> decisions.recordMerge(packageId));

		// Cancelled assignments cannot be reopened by choosing QUEUE_APPLY.
		assertThrows(IllegalArgumentException.class,
				() -> decisions.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
						"Invalid attempt to apply cancelled assignment"));

		// HOLD records the evidence without authorising changes.
		var held = decisions.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.HOLD),
				"Preserve late contribution as rejected lineage");
		assertEquals(MasterContributionDecisionLedger.Choice.HOLD, held.decisions().getFirst().choice());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				held.decisions().getFirst().preliminaryStatus());
		assertNull(held.decisions().getFirst().resultRevisionId());
		assertEquals(held, decisions.find(held.reviewId()).orElseThrow());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertNull(questionTextForCode(fixture.master(), "2"));
	}

	@Test
	void masterDecisionsRejectIdenticalSkipWithoutMatchingMasterContent() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Already present", "decision-identical.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		ExchangeRecord proposal = intake.proposedChanges(packageId).getFirst();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Already present'
				WHERE id = 81
				"""));
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		MasterContributionDecisionLedger ledger = new MasterContributionDecisionLedger(fixture.master());
		assertThrows(IllegalArgumentException.class,
				() -> ledger.record(packageId, Map.of(key, MasterContributionDecisionLedger.Choice.SKIP_IDENTICAL),
						"Master already contains identical content"));
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
	void masterEmbeddedPreflightAcceptsVerifiedIssuedImage() throws Exception {
		Fixture fixture = fixture(true);
		Path archive = publish(fixture, "Question with unchanged issued image", "preflight-image-valid.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var preflight = new MasterContributionAssetPreflight(fixture.master(),
				new ManagedDataLayout(directory.resolve("coordinator")));
		preflight.verify(packageId, archive);

		// Preflight is read-only. No accepted content should have
		// appeared on the master before reviewed application.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Accept Question with verified issued image");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, result.outcomes().getFirst().outcome());
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("Question with unchanged issued image", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterEmbeddedPreflightRejectsChangedLockedMasterImage() throws Exception {
		Fixture fixture = fixture(true);
		Path archive = publish(fixture, "Contributor proposal with original image",
				"preflight-master-image-drift.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();

		// Deliberately simulate an unexpected direct database
		// modification that bypasses the normal master capture
		// protection. This represents damaged authoritative data,
		// not a permitted application operation.
		execute(fixture.master(), """
				DROP TRIGGER trg_master_capture_question_images_update
				""");
		execute(fixture.master(), """
				UPDATE question_images
				SET image_png = X'0102030405060708090A'
				WHERE question_id = (
				    SELECT id
				    FROM questions
				    WHERE question_code = '1'
				)
				""");
		var preflight = new MasterContributionAssetPreflight(fixture.master(),
				new ManagedDataLayout(directory.resolve("coordinator")));
		IOException failure = assertThrows(IOException.class, () -> preflight.verify(packageId, archive));
		assertTrue(failure.getMessage().contains("Master embedded content differs")
				|| failure.getMessage().contains("Master Question images differ"), failure.getMessage());
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertTrue(new MasterContributionApplicationCoordinator(fixture.master()).find(UUID.randomUUID()).isEmpty());

		// This test deliberately removed a trigger, so schema
		// verification is not expected to pass for the damaged
		// fixture. Referential and SQLite integrity should hold.
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterEmbeddedPreflightRejectsInvalidContributorPngBytes() throws Exception {
		Fixture fixture = fixture(true);

		// Create a new contributor-owned Question and image.
		// The image is deliberately invalid, but its archive hashes
		// will accurately represent the supplied bytes.
		byte[] invalidPng = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
		Path archive = publishNewContributorImage(fixture, "preflight-invalid-png.examwork", invalidPng);

		// The archive is structurally valid. Binary preflight must
		// independently reject the invalid PNG signature.
		assertArchiveWellFormed(archive, fixture.master());
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		var preflight = new MasterContributionAssetPreflight(fixture.master(),
				new ManagedDataLayout(directory.resolve("coordinator")));
		IOException failure = assertThrows(IOException.class, () -> preflight.verify(packageId, archive));
		assertTrue(failure.getMessage().contains("invalid PNG signature"), failure.getMessage());
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertNull(questionTextForCode(fixture.master(), "2"));

		// Only the original coordinator-owned image may exist.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT id
						FROM question_images
						""")) {
			assertTrue(rows.next());
			assertEquals(151L, rows.getLong("id"));
			assertFalse(rows.next());
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterImportsNewImageWithoutChangingIssuedImageIdentity() throws Exception {
		Fixture fixture = fixture(true);
		byte[] originalPng = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
		byte[] newPng = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1Pe" + "AAAADElEQVR4nGOQ67kEAAJIAX1YW9a6AAAAAElFTkSuQmCC");
		var identities = new SqlitePortableIdentityRepository(fixture.master());
		var originalIdentity = identities.findByLocalId(EntityType.QUESTION_IMAGE, 151L).orElseThrow();
		Path archive = publishNewContributorImage(fixture, "new-image-with-issued-image-preserved.examwork", newPng);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			assertNull(proposal.baselineRevisionId());
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Approve new image without modifying issued image");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator")));
		assertFalse(result.duplicate());
		assertEquals(2, result.outcomes().size());
		assertTrue(result.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		long newImageId;
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    question.question_code,
						    image.id,
						    image.image_png
						FROM question_images image
						JOIN questions question
						  ON question.id = image.question_id
						ORDER BY question.question_code
						""")) {

			// Question 1 retains its original image.
			assertTrue(rows.next());
			assertEquals("1", rows.getString("question_code"));
			assertEquals(151L, rows.getLong("id"));
			assertArrayEquals(originalPng, rows.getBytes("image_png"));

			// Question 2 receives its own newly captured image.
			assertTrue(rows.next());
			assertEquals("2", rows.getString("question_code"));
			newImageId = rows.getLong("id");
			assertNotEquals(151L, newImageId);
			assertArrayEquals(newPng, rows.getBytes("image_png"));
			assertFalse(rows.next());
		}
		var retainedIdentity = identities.findByLocalId(EntityType.QUESTION_IMAGE, 151L).orElseThrow();
		var newIdentity = identities.findByLocalId(EntityType.QUESTION_IMAGE, newImageId).orElseThrow();

		// The issued image must retain both its identity and revision.
		assertEquals(originalIdentity.portableId(), retainedIdentity.portableId());
		assertEquals(originalIdentity.revisionId(), retainedIdentity.revisionId());
		assertNotEquals(originalIdentity.portableId(), newIdentity.portableId());
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("Contributor Question with image", questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void masterIntakeAcceptsOneReturnAndRecognisesExactReplay() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Only contributor submission", "one-return.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var first = intake.receive(archive);
		assertFalse(first.duplicate());
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, first.received().status());
		assertEquals(1, first.received().returnNumber());
		assertNull(first.received().previousPackageId());
		assertEquals(1, intake.proposedChanges(first.received().packageId()).size());

		// A duplicate transfer of the same archive is idempotent.
		var replay = intake.receive(archive);
		assertTrue(replay.duplicate());
		assertEquals(first.received(), replay.received());

		// A new return from the same assignment is prohibited locally.
		assertThrows(IllegalStateException.class,
				() -> new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
						.export(fixture.assignmentId(), directory.resolve("second-return.examwork")));
		assertEquals(first.received(), intake.find(first.received().packageId()).orElseThrow());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertNull(questionTextForCode(fixture.master(), "2"));
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
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
	void masterIntakeKeepsCancelledReturnHeldAcrossReplays() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor return after cancellation", "late-first-return.examwork");
		new WorkAssignmentLedger(fixture.master()).closeWithoutReturn(fixture.assignmentId(),
				"Assignment cancelled before package arrival");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var first = intake.receive(archive);
		assertFalse(first.duplicate());
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, first.received().status());
		var replay = intake.receive(archive);
		assertTrue(replay.duplicate());
		assertEquals(first.received(), replay.received());
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE,
				intake.find(first.received().packageId()).orElseThrow().status());
		MasterContributionReviewPlanner planner = new MasterContributionReviewPlanner(fixture.master());
		var review = planner.plan(first.received().packageId());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				review.assessments().getFirst().status());

		// Historical assessment remains available, but a late return
		// for a cancelled assignment must not open actionable review.
		assertTrue(planner.planPending(first.received().packageId()).isEmpty());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertNull(questionTextForCode(fixture.master(), "2"));
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
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
	void masterPendingReviewCanResumeAfterCancellationUntilApplication() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor work awaiting reconciliation", "pending-review.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		MasterContributionReviewPlanner planner = new MasterContributionReviewPlanner(fixture.master());
		var firstReceipt = intake.receive(archive);
		assertFalse(firstReceipt.duplicate());
		UUID packageId = firstReceipt.received().packageId();
		var firstPlan = planner.planPending(packageId).orElseThrow();
		assertEquals(1, firstPlan.assessments().size());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW,
				firstPlan.assessments().getFirst().status());

		// Simulate cancelling the first review without recording
		// decisions or applying the proposed records.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertNull(questionTextForCode(fixture.master(), "2"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());

		// Reimport the identical archive. Receipt duplication must
		// not prevent the pending contribution from being reviewed.
		var secondReceipt = intake.receive(archive);
		assertTrue(secondReceipt.duplicate());
		assertEquals(firstReceipt.received(), secondReceipt.received());
		var secondPlan = planner.planPending(packageId).orElseThrow();
		assertEquals(firstPlan.assessments(), secondPlan.assessments());
		assertNull(questionTextForCode(fixture.master(), "2"));

		// Reopen the database through new service instances,
		// representing a later application session after cancellation.
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("coordinator"));
		SqliteDatabase reopened = new SqliteDatabase(config.databasePath());
		MasterContributionIntake reopenedIntake = new MasterContributionIntake(reopened);
		MasterContributionReviewPlanner reopenedPlanner = new MasterContributionReviewPlanner(reopened);
		var thirdReceipt = reopenedIntake.receive(archive);
		assertTrue(thirdReceipt.duplicate());
		assertEquals(firstReceipt.received(), thirdReceipt.received());
		var thirdPlan = reopenedPlanner.planPending(packageId).orElseThrow();
		assertEquals(firstPlan.assessments(), thirdPlan.assessments());
		assertNull(questionTextForCode(reopened, "2"));

		// Apply the contribution exactly once.
		ExchangeRecord proposal = reopenedIntake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
		var review = new MasterContributionDecisionLedger(reopened).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Accept previously cancelled contribution review");
		var applied = new MasterContributionApplicationCoordinator(reopened).execute(review.reviewId());
		assertFalse(applied.duplicate());
		assertEquals(MasterContributionApplicationCoordinator.Outcome.APPLIED, applied.outcomes().getFirst().outcome());
		assertEquals("Biology question", masterQuestionText(reopened));
		assertEquals("Contributor work awaiting reconciliation", questionTextForCode(reopened, "2"));
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(reopened).find(fixture.assignmentId()).orElseThrow().state());

		// Once application and closure have committed, an identical
		// archive must no longer reopen the review.
		var completedReplay = reopenedIntake.receive(archive);
		assertTrue(completedReplay.duplicate());
		assertEquals(firstReceipt.received(), completedReplay.received());
		assertTrue(reopenedPlanner.planPending(packageId).isEmpty());
		assertEquals("Contributor work awaiting reconciliation", questionTextForCode(reopened, "2"));
		reopened.verifySchema();
		reopened.verifyIntegrity();
	}

	@Test
	void masterReceiptExportsAcceptedCanonicalMapping() throws Exception {
		Fixture fixture = fixture();

		// Only newly captured Question and Answer records can be submitted.
		// The original issued Question and Answer must remain unchanged.
		addNewContributorQuestionAndAnswer(fixture);
		Path contribution = directory.resolve("accepted-for-receipt.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), contribution);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		List<ExchangeRecord> proposed = intake.proposedChanges(packageId);
		assertEquals(2, proposed.size());
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord record : proposed) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(record.recordType(), record.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Accept newly captured Question and Answer");
		var application = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertEquals(2, application.outcomes().size());
		assertTrue(application.outcomes().stream()
				.allMatch(outcome -> outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		Path destination = directory.resolve("accepted-receipt.examwork");
		ExamWorkManifest receipt = new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(),
				destination);
		assertEquals(ExamWorkPackageType.IMPORT_RECEIPT, receipt.packageType());
		assertEquals(fixture.assignmentId(), receipt.assignmentId());
		assertEquals(application.completedAt(), receipt.createdAt());
		assertArchiveWellFormed(destination, fixture.master());
		List<ExchangeRecord> records = new ExamWorkJsonlCodec()
				.readRecords(new ByteArrayInputStream(readEntry(destination, "receipts/import-results.jsonl")));

		// One receipt context and one outcome per submitted record.
		assertEquals(3, records.size());
		ExchangeRecord context = records.stream().filter(record -> record.recordType().equals("IMPORT_RECEIPT_CONTEXT"))
				.findFirst().orElseThrow();
		assertEquals(review.reviewId().toString(), context.values().path("review_id").asText());
		assertEquals(packageId.toString(), context.values().path("contribution_package_id").asText());
		assertEquals(receipt.packageId().toString(), context.values().path("receipt_package_id").asText());
		assertEquals(2, context.values().path("record_count").asInt());
		for (ExchangeRecord proposedRecord : proposed) {
			ExchangeRecord outcome = records.stream()
					.filter(record -> record.recordType().equals("IMPORT_RECEIPT_" + proposedRecord.recordType()))
					.filter(record -> record.recordId().equals(proposedRecord.recordId())).findFirst().orElseThrow();
			assertEquals("APPLIED", outcome.values().path("outcome").asText());
			assertEquals("QUEUE_APPLY", outcome.values().path("decision").asText());
			assertFalse(outcome.values().path("canonical_record_id").asText().isBlank());
			assertFalse(outcome.values().path("resulting_revision_id").asText().isBlank());
		}

		// The original coordinator-owned Question is not updated.
		assertEquals("Biology question", masterQuestionText(fixture.master()));

		// The returned Question and Answer have been added.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT question.question_text,
						       answer.answer_text
						FROM questions question
						JOIN answers answer
						  ON answer.question_id = question.id
						WHERE question.question_code = '2'
						""")) {
			assertTrue(result.next());
			assertEquals("New contributor question", result.getString("question_text"));
			assertEquals("New contributor answer", result.getString("answer_text"));
			assertFalse(result.next());
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReceiptPreservesRejectedReviewOutcome() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Rejected contributor text", "rejected-for-receipt.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT),
				"Preserve master; reject contributor proposal");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertEquals(MasterContributionApplicationCoordinator.Outcome.REJECTED, result.outcomes().getFirst().outcome());
		Path receiptPath = directory.resolve("rejection-receipt.examwork");
		new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), receiptPath);
		List<ExchangeRecord> records = new ExamWorkJsonlCodec()
				.readRecords(new ByteArrayInputStream(readEntry(receiptPath, "receipts/import-results.jsonl")));
		ExchangeRecord rejected = records.stream()
				.filter(record -> record.recordType().equals("IMPORT_RECEIPT_QUESTION")).findFirst().orElseThrow();
		assertEquals("REJECTED", rejected.values().path("outcome").asText());
		assertEquals("REJECT", rejected.values().path("decision").asText());
		assertTrue(rejected.values().path("canonical_record_id").isNull());
		assertTrue(rejected.values().path("resulting_revision_id").isNull());

		// Rejected work must not overwrite the master Question.
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertArchiveWellFormed(receiptPath, fixture.master());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReceiptRefusesToMisattributeLaterMasterRevision() throws Exception {
		Fixture fixture = fixture();
		Path firstReceipt = exportAppliedReceipt(fixture, "Originally accepted master revision",
				"original-master-revision.examwork");
		UUID reviewId = new ContributorReceiptInspector(fixture.contributor()).inspect(firstReceipt).reviewId();

		// The assignment has closed. This is now a separate,
		// subsequent master edit, not part of the accepted return.
		execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Later independent master correction'
				WHERE question_code = '2'
				""");
		Path secondReceipt = directory.resolve("misattributed-master-revision.examwork");
		SQLException failure = assertThrows(SQLException.class,
				() -> new MasterContributionReceiptExporter(fixture.master()).export(reviewId, secondReceipt));
		assertTrue(failure.getMessage().contains("Master record changed after contribution application"),
				failure.getMessage());
		assertFalse(Files.exists(secondReceipt));
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals("Later independent master correction", questionTextForCode(fixture.master(), "2"));
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReceiptRejectsIncompleteApplication() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Not yet reconciled", "pending-for-receipt.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Review recorded but application pending");
		Path destination = directory.resolve("premature-receipt.examwork");
		SQLException failure = assertThrows(SQLException.class,
				() -> new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), destination));
		assertTrue(failure.getMessage().contains("Receipt requires a completed application"));
		assertFalse(Files.exists(destination));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReceiptRepeatedExportIsDeterministic() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Receipt replay contribution", "replay-for-receipt.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Accept once; export same receipt repeatedly");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		coordinator.execute(review.reviewId());
		MasterContributionReceiptExporter exporter = new MasterContributionReceiptExporter(fixture.master());
		Path first = directory.resolve("receipt-first.examwork");
		Path second = directory.resolve("receipt-second.examwork");
		ExamWorkManifest firstManifest = exporter.export(review.reviewId(), first);
		ExamWorkManifest secondManifest = exporter.export(review.reviewId(), second);
		assertEquals(firstManifest.packageId(), secondManifest.packageId());
		assertEquals(firstManifest, secondManifest);
		assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));

		// Existing archives are never intentionally overwritten.
		byte[] originalBytes = Files.readAllBytes(first);
		assertThrows(IOException.class, () -> exporter.export(review.reviewId(), first));
		assertArrayEquals(originalBytes, Files.readAllBytes(first));
		assertTrue(coordinator.execute(review.reviewId()).duplicate());
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterRegionImportRollsBackOnInsertionFailure() throws Exception {
		Fixture fixture = fixture();

		// Create contributor-owned Question 2 and its Answer.
		// Issued Question 1 and its Answer must remain immutable.
		addNewContributorQuestionAndAnswer(fixture);
		execute(fixture.contributor(), """
				INSERT INTO answer_regions (
				    answer_id,
				    region_order,
				    answer_file_id,
				    page_number,
				    x, y, width, height
				)
				SELECT
				    answer.id,
				    0,
				    booklet.answer_file_id,
				    1,
				    0.1, 0.1, 0.4, 0.4
				FROM answers answer
				JOIN questions question
				  ON question.id = answer.question_id
				JOIN exam_booklets booklet
				  ON booklet.id = question.booklet_id
				WHERE question.question_code = '2'
				""");
		Path archive = directory.resolve("rollback-region-return.examwork");
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposed : intake.proposedChanges(packageId)) {
			choices.put(new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId()),
					MasterContributionDecisionLedger.Choice.QUEUE_APPLY);
		}
		assertEquals(2, choices.size());
		assertTrue(choices.keySet().stream().anyMatch(key -> "QUESTION".equals(key.recordType())));
		assertTrue(choices.keySet().stream().anyMatch(key -> "ANSWER".equals(key.recordType())));
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Test atomic new Answer PDF-region rollback");

		// Fail during insertion of the approved new Answer region.
		// The earlier Question and Answer inserts must roll back.
		execute(fixture.master(), """
				CREATE TRIGGER trg_test_fail_answer_region
				BEFORE INSERT ON answer_regions
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Injected PDF-region insertion failure'
				    );
				END;
				""");
		MasterContributionApplicationCoordinator coordinator = new MasterContributionApplicationCoordinator(
				fixture.master());
		SQLException failure = assertThrows(SQLException.class, () -> coordinator.execute(review.reviewId(), archive,
				new ManagedDataLayout(directory.resolve("coordinator"))));
		assertTrue(failure.getMessage().contains("Injected PDF-region insertion failure"), failure.getMessage());
		assertTrue(coordinator.find(review.reviewId()).isEmpty());

		// No part of Question 2 or its Answer may survive.
		assertNull(questionTextForCode(fixture.master(), "2"));
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT COUNT(*)
						FROM answers
						""")) {
			assertTrue(rows.next());
			assertEquals(1, rows.getInt(1));
		}
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT COUNT(*)
						FROM answer_regions
						""")) {
			assertTrue(rows.next());
			assertEquals(0, rows.getInt(1));
		}

		// Transaction-scoped mutation permits must also roll back.
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT
						    (SELECT COUNT(*)
						     FROM master_contribution_write_permits)
						  + (SELECT COUNT(*)
						     FROM master_contribution_region_permits)
						""")) {
			assertTrue(rows.next());
			assertEquals(0, rows.getInt(1));
		}
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReviewCannotClaimUnapprovedConcurrentContent() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Matching text", "review-identical.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Matching text'
				WHERE id = 81
				"""));
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(packageId);
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, assessment.status());
		assertNull(assessment.baselineRevisionId());
		assertNull(assessment.masterRevisionId());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterReviewKeepsCancelledAssignmentReturnHeld() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Late contributor Question", "review-late-return.examwork");
		new WorkAssignmentLedger(fixture.master()).closeWithoutReturn(fixture.assignmentId(),
				"Cancelled before contribution was received");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var received = intake.receive(archive);
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE, received.received().status());
		MasterContributionReviewPlanner planner = new MasterContributionReviewPlanner(fixture.master());
		var firstReview = planner.plan(received.received().packageId());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				firstReview.assessments().getFirst().status());

		// Repeated inspection must not promote a cancelled return.
		var repeated = intake.receive(archive);
		assertTrue(repeated.duplicate());
		var secondReview = planner.plan(received.received().packageId());
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.HELD_LINEAGE,
				secondReview.assessments().getFirst().status());
		assertEquals(MasterContributionIntake.IntakeStatus.HELD_LINEAGE,
				intake.find(received.received().packageId()).orElseThrow().status());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterReviewPreservesBaselineAgainstCoordinatorCorrection() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "review-stale.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Master correction'
				WHERE id = 81
				"""));
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(packageId);
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, assessment.status());
		assertNull(assessment.baselineRevisionId());
		assertNull(assessment.masterRevisionId());
		assertNull(assessment.masterRecordSha256());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
	}

	@Test
	void masterReviewProtectsBaselineQuestionAgainstDeletion() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "review-missing-master.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(archive).received().packageId();

		// The coordinator's issued Question and Answer remain protected
		// for the entire active assignment.
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM answers
				WHERE question_id = 81
				"""));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM questions
				WHERE id = 81
				"""));
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(packageId);
		assertEquals(1, plan.assessments().size());
		var assessment = plan.assessments().getFirst();

		// The contribution contains a new Question 2.
		// It does not propose an update to baseline Question 1.
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, assessment.status());
		assertEquals("QUESTION", assessment.recordType());
		assertNull(assessment.baselineRevisionId());
		assertNull(assessment.masterRevisionId());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		fixture.master().verifyIntegrity();
	}

	@Test
	void masterReviewRecognisesNewContributorQuestion() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Contributor revision", "review-new-question.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		var receipt = intake.receive(archive);
		var plan = new MasterContributionReviewPlanner(fixture.master()).plan(receipt.received().packageId());
		assertEquals(MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, plan.intakeStatus());
		assertEquals(fixture.assignmentId(), plan.assignmentId());
		assertEquals(1, plan.assessments().size());
		var assessment = plan.assessments().getFirst();
		assertEquals(MasterContributionReviewPlanner.ReviewStatus.READY_NEW, assessment.status());
		assertNull(assessment.baselineRevisionId());
		assertNull(assessment.masterRevisionId());
		assertNull(assessment.masterRecordSha256());
		assertEquals(intake.proposedChanges(receipt.received().packageId()).getFirst().revisionId(),
				assessment.proposedRevisionId());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertNull(questionTextForCode(fixture.master(), "2"));
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
	void migrationToVersion35InstallsProtectedIntakeTables() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v35-migration.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion35Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 34
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion())));
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
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion36Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 35
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion())));
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
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion37Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 36
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion())));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(36, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationToVersion43InstallsReceiptAcknowledgementLedger() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v43-migration.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion43Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 42
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion())));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(42, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationToVersion44InstallsQuestionContentPartPermissions() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v44-migration.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion44Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 43
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion())));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(43, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationToVersion45InstallsApprovedImageInsertionGuard() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v45-migration.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());

		// Reconstruct v44 from the latest schema.
		// This removes v45, v46 and v47 additions in dependency order.
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion45Objects(statement);
				assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
						UPDATE schema_version
						SET version = 44
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion())));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(44, database.schemaVersion());
		database.verifySchema();

		// Initialisation applies v45 and verifies its image-insertion
		// guard before proceeding through v46 and v47.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void ordinaryMasterCaptureWritesAreBlockedWhileAssigned() throws Exception {
		Fixture fixture = fixture();
		SQLException questionFailure = assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE questions
				SET question_text = 'Unauthorised master correction'
				WHERE id = 81
				"""));
		assertTrue(questionFailure.getMessage().contains("Assigned Exam capture is read-only on the master"));
		SQLException answerFailure = assertThrows(SQLException.class, () -> execute(fixture.master(), """
				UPDATE answers
				SET answer_text = 'Unauthorised master answer'
				WHERE id = 91
				"""));
		assertTrue(answerFailure.getMessage().contains("Assigned Exam capture is read-only on the master"));
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM answers
				WHERE id = 91
				"""));
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
	}

	@Test
	void ordinaryMasterPdfRegionWriteRemainsBlocked() throws Exception {
		Fixture fixture = fixture();
		SQLException failure = assertThrows(SQLException.class, () -> execute(fixture.master(), """
				INSERT INTO question_regions (
				    question_id,
				    region_order,
				    booklet_id,
				    page_number,
				    x, y, width, height
				)
				VALUES (
				    81, 0, 61, 1,
				    0.1, 0.1, 0.4, 0.4
				)
				"""));
		assertTrue(failure.getMessage().contains("Assigned Exam capture is read-only on the master"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifyIntegrity();
	}

	@Test
	void ordinaryMasterSharedContextInsertionRemainsBlocked() throws Exception {
		Fixture fixture = fixture();
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				INSERT INTO shared_question_contexts (
				    booklet_id, context_label
				) VALUES (61, 'Unauthorised master context')
				"""));
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT COUNT(*)
						FROM shared_question_contexts
						""")) {
			assertTrue(rows.next());
			assertEquals(0, rows.getInt(1));
		}
		assertEquals(WorkAssignmentLedger.State.ACTIVE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void publishesOneFinalContributionWithoutCoordinatorAcknowledgement() throws Exception {
		Fixture fixture = fixture();
		Path archive = publish(fixture, "Final contributor Question", "single-final-return.examwork");
		ContributionArchiveInspector inspector = new ContributionArchiveInspector(fixture.master());
		var inspected = inspector.inspect(archive);
		assertEquals(1, inspected.returnNumber());
		assertNull(inspected.previousPackageId());
		assertEquals(1, inspected.changes().size());
		assertEquals("QUESTION", inspected.changes().getFirst().recordType());
		assertTrue(inspected.proposedRecords().size() > inspected.changes().size());
		assertEquals(fixture.assignmentId(), inspected.assignment().assignmentId());
		assertTrue(inspected.archiveSha256().matches("[0-9a-f]{64}"));
		assertArrayEquals(new WorkAssignmentLedger(fixture.master()).readBaseline(fixture.assignmentId()).records(),
				readEntry(archive, "baseline/initial-records.jsonl"));

		// No coordinator acknowledgement is necessary to finish the local
		// submission. A second export from the same assignment is forbidden.
		assertThrows(IllegalStateException.class,
				() -> new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
						.export(fixture.assignmentId(), directory.resolve("second-return.examwork")));
		assertEquals(1, new SqliteContributionReturnLedger(fixture.contributor()).findLatest(fixture.assignmentId())
				.orElseThrow().returnNumber());
		assertEquals("Biology question", masterQuestionText(fixture.master()));
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
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

	@Test
	void reviewedQuestionChangeInvalidatesOnlyAffectedBookletReview() throws Exception {
		Fixture fixture = fixture(false, true);

		// Neither reviewed Booklet may be changed by ordinary master SQL.
		assertThrows(SQLException.class, () -> execute(fixture.master(), """
				DELETE FROM booklet_applicability_reviews
				WHERE booklet_id = 62
				"""));
		Path contribution = publish(fixture, "Changed Question requiring a fresh applicability review",
				"booklet-review-invalidation.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		Map<MasterContributionDecisionLedger.RecordKey, MasterContributionDecisionLedger.Choice> choices = new HashMap<>();
		for (ExchangeRecord proposal : intake.proposedChanges(packageId)) {
			var key = new MasterContributionDecisionLedger.RecordKey(proposal.recordType(), proposal.recordId());
			choices.put(key,
					"QUESTION".equals(proposal.recordType()) ? MasterContributionDecisionLedger.Choice.QUEUE_APPLY
							: MasterContributionDecisionLedger.Choice.REJECT);
		}
		assertTrue(choices.keySet().stream().anyMatch(key -> "QUESTION".equals(key.recordType())));
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId, choices,
				"Apply Question and invalidate stale Booklet review");
		var result = new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		assertFalse(result.duplicate());
		assertTrue(result.outcomes().stream().anyMatch(outcome -> "QUESTION".equals(outcome.key().recordType())
				&& outcome.outcome() == MasterContributionApplicationCoordinator.Outcome.APPLIED));
		try (Connection connection = fixture.master().openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT booklet_id
						FROM booklet_applicability_reviews
						ORDER BY booklet_id
						""")) {

			// Paper 1 was materially changed. Its earlier review is stale.
			// Paper 2 remains reviewed because its content was unchanged.
			assertTrue(rows.next());
			assertEquals(62, rows.getLong(1));
			assertFalse(rows.next());
		}
		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				new WorkAssignmentLedger(fixture.master()).find(fixture.assignmentId()).orElseThrow().state());
		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void submittedContributionCannotBeExportedAgain() throws Exception {
		Fixture fixture = fixture();
		Path original = publish(fixture, "First submitted revision", "original-final-return.examwork");
		byte[] originalBytes = Files.readAllBytes(original);
		Path second = directory.resolve("second-final-return.examwork");
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
						.export(fixture.assignmentId(), second));
		assertTrue(failure.getMessage().contains("already been submitted"), failure.getMessage());

		// A submitted assignment cannot publish another contribution.
		// Its original archive and captured records must remain intact.
		assertFalse(Files.exists(second));
		assertArrayEquals(originalBytes, Files.readAllBytes(original));
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("First submitted revision", questionTextForCode(fixture.contributor(), "2"));
		assertTrue(new SqliteContributionReturnLedger(fixture.contributor()).findLatest(fixture.assignmentId())
				.isPresent());
		fixture.contributor().verifySchema();
		fixture.contributor().verifyIntegrity();
	}

	@Test
	void subsequentAssignmentInstallsIntoPreviousContributorAfterMerge() throws Exception {

		Fixture fixture = fixture();

		var masterLedger = new WorkAssignmentLedger(fixture.master());

		var originalAssignment = masterLedger.find(fixture.assignmentId()).orElseThrow();

		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributor());

		var originalBaseline = sessions.readBaseline(fixture.assignmentId());

		// Capture new work in the first contributor assignment.
		addNewContributorQuestionAndAnswer(fixture);

		Path contribution = directory.resolve("first-contribution.examwork");

		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), contribution);

		// Merge the contribution into the authoritative Exam.
		var intake = new MasterContributionIntake(fixture.master());

		UUID packageId = intake.receive(contribution).received().packageId();

		var review = new MasterContributionDecisionLedger(fixture.master()).recordMerge(packageId);

		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId(), contribution,
				new ManagedDataLayout(directory.resolve("coordinator")));

		assertEquals(WorkAssignmentLedger.State.COMPLETE,
				masterLedger.find(fixture.assignmentId()).orElseThrow().state());

		assertEquals("New contributor question", questionTextForCode(fixture.master(), "2"));

		// Issue the same Exam to the same contributor again.
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");

		UUID newAssignmentId = UUID.randomUUID();

		Path newAssignment = directory.resolve("subsequent-assignment.examwork");

		new WorkAssignmentIssuanceService(fixture.master(), new ManagedDataLayout(directory.resolve("coordinator")))
				.issue(new WorkAssignmentExporter.Request(exam, newAssignmentId, originalAssignment.contributorId(),
						originalAssignment.contributorName(), false, newAssignment));

		// Install into the existing contributor data root,
		// not a newly created contributor database.
		var installed = new WorkAssignmentInstallationCoordinator(
				ApplicationConfig.fromDataRoot(fixture.contributorRoot())).install(newAssignment);

		assertEquals(newAssignmentId, installed.assignmentId());
		assertFalse(installed.replay());

		assertEquals(newAssignmentId, sessions.active().orElseThrow().assignmentId());

		// The previous assignment remains submitted.
		var visibility = new SqliteContributorAssignmentVisibilityRepository(fixture.contributor());

		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.SUBMITTED,
				visibility.find(fixture.assignmentId()).orElseThrow());

		// Historical assignment evidence must remain immutable.
		assertArrayEquals(originalBaseline.records(), sessions.readBaseline(fixture.assignmentId()).records());

		assertArrayEquals(originalBaseline.assets(), sessions.readBaseline(fixture.assignmentId()).assets());

		// The newly installed graph must match its fresh
		// authoritative baseline and contain no contributor delta.
		Path snapshotPath = directory.resolve("subsequent-installed.db");

		fixture.contributor().createConsistentSnapshot(snapshotPath);

		var snapshot = new ContributionSnapshotReader(snapshotPath).read(newAssignmentId);

		assertEquals(snapshot.baselineRecords(), snapshot.currentRecords());

		assertTrue(snapshot.delta().candidates().isEmpty());

		assertEquals("New contributor question", questionTextForCode(fixture.contributor(), "2"));

		fixture.contributor().verifySchema();
		fixture.contributor().verifyIntegrity();

		fixture.master().verifySchema();
		fixture.master().verifyIntegrity();
	}

	@Test
	void trustedReceiptCarriesAcceptedMasterState() throws Exception {
		Fixture fixture = fixture();
		Path receipt = exportAppliedReceipt(fixture, "Trusted accepted contributor text",
				"trusted-master-state.examwork");
		String digest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var inspection = new ContributorReceiptInspector(fixture.contributor()).inspectTrusted(receipt, digest);
		assertEquals(1, inspection.outcomes().size());
		assertEquals(1, inspection.acceptedMasterRecords().size());
		ExchangeRecord accepted = inspection.acceptedMasterRecords().getFirst();
		assertEquals("QUESTION", accepted.recordType());
		assertEquals("Trusted accepted contributor text", accepted.values().path("question_text").asText());
		var outcome = inspection.outcomes().getFirst().values();
		assertEquals(outcome.path("canonical_record_id").asText(), accepted.recordId().toString());
		assertEquals(outcome.path("resulting_revision_id").asText(), accepted.revisionId().toString());
		var trusted = new ContributorReceiptRebasePlanner(fixture.contributor()).planTrusted(receipt, digest);
		assertEquals(ContributorReceiptRebasePlanner.Disposition.READY_FOR_KNOWN_MASTER_ACKNOWLEDGEMENT,
				trusted.plan().assessments().getFirst().disposition());
		assertEquals(inspection.acceptedMasterRecords(), trusted.acceptedMasterRecords());
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void trustedReceiptInspectionDoesNotRebaseContributorWork() throws Exception {
		Fixture fixture = fixture();
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributor());
		byte[] originalBaseline = sessions.readBaseline(fixture.assignmentId()).records();
		Path receipt = exportAppliedReceipt(fixture, "Previously submitted contributor text",
				"trusted-submitted-work.examwork");
		String digest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var inspected = new ContributorReceiptInspector(fixture.contributor()).inspectTrusted(receipt, digest);
		assertEquals(1, inspected.outcomes().size());
		assertEquals(1, inspected.acceptedMasterRecords().size());
		assertEquals("Previously submitted contributor text",
				inspected.acceptedMasterRecords().getFirst().values().path("question_text").asText());

		// Trusted receipt inspection is read-only. It must not
		// rewrite contributor records or advance the issued baseline.
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Previously submitted contributor text", questionTextForCode(fixture.contributor(), "2"));
		assertArrayEquals(originalBaseline, sessions.readBaseline(fixture.assignmentId()).records());
		fixture.contributor().verifySchema();
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	@Test
	void trustedReceiptSupportsRejectedWithoutMasterRecord() throws Exception {
		Fixture fixture = fixture();
		Path contribution = publish(fixture, "Rejected but retained contributor work",
				"trusted-rejected-source.examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.REJECT),
				"Reject without replacing contributor work");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		Path receipt = directory.resolve("trusted-rejected-receipt.examwork");
		new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), receipt);
		String digest = HexFormat.of().formatHex(digest(Files.readAllBytes(receipt)));
		var trusted = new ContributorReceiptRebasePlanner(fixture.contributor()).planTrusted(receipt, digest);
		assertTrue(trusted.acceptedMasterRecords().isEmpty());
		assertEquals(ContributorReceiptRebasePlanner.Disposition.PRESERVE_REJECTED,
				trusted.plan().assessments().getFirst().disposition());
		assertEquals("Biology question", questionTextForCode(fixture.contributor(), "1"));
		assertEquals("Rejected but retained contributor work", questionTextForCode(fixture.contributor(), "2"));
		assertEquals("Biology question", questionTextForCode(fixture.master(), "1"));
		assertNull(questionTextForCode(fixture.master(), "2"));
		fixture.contributor().verifyIntegrity();
		fixture.master().verifyIntegrity();
	}

	private void addNewContributorQuestionAndAnswer(Fixture fixture) throws SQLException {
		try (Connection connection = fixture.contributor().openConnection();
				Statement statement = connection.createStatement()) {

			// Copy the existing Question's valid classification and
			// capture metadata, but create a distinct Question identity.
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					INSERT INTO questions (
					    booklet_id,
					    classification_node_id,
					    question_code,
					    question_text,
					    marks,
					    shared_context_capture_required,
					    response_type,
					    source_capture_required
					)
					SELECT
					    booklet_id,
					    classification_node_id,
					    '2',
					    'New contributor question',
					    marks,
					    shared_context_capture_required,
					    response_type,
					    source_capture_required
					FROM questions
					WHERE question_code = '1'
					"""));
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					INSERT INTO answers (
					    question_id,
					    answer_text
					)
					SELECT
					    id,
					    'New contributor answer'
					FROM questions
					WHERE question_code = '2'
					"""));
		}
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

	private Path exportAppliedReceipt(Fixture fixture, String questionText, String filename) throws Exception {
		Path contribution = publish(fixture, questionText, "receipt-source-" + UUID.randomUUID() + ".examwork");
		MasterContributionIntake intake = new MasterContributionIntake(fixture.master());
		UUID packageId = intake.receive(contribution).received().packageId();
		ExchangeRecord proposed = intake.proposedChanges(packageId).getFirst();
		var key = new MasterContributionDecisionLedger.RecordKey(proposed.recordType(), proposed.recordId());
		var review = new MasterContributionDecisionLedger(fixture.master()).record(packageId,
				Map.of(key, MasterContributionDecisionLedger.Choice.QUEUE_APPLY),
				"Accept return for contributor receipt inspection");
		new MasterContributionApplicationCoordinator(fixture.master()).execute(review.reviewId());
		Path destination = directory.resolve(filename);
		new MasterContributionReceiptExporter(fixture.master()).export(review.reviewId(), destination);
		return destination;
	}

	private Fixture fixture() throws Exception {
		return fixture(false);
	}

	private Fixture fixture(boolean includeOriginalImage) throws Exception {
		return fixture(includeOriginalImage, false);
	}

	private Fixture fixture(boolean includeOriginalImage, boolean includeReviewedBooklets) throws Exception {
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
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		execute(master, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES (11, 1, 'Biology 2025', 1)
				""");
		execute(master, """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order
				)
				VALUES
				    (101, 11, NULL, 'B25U1',
				     'Biology Unit', 'UNIT', 0),
				    (102, 11, 101, 'B25U1T1',
				     'Biology Topic', 'TOPIC', 0),
				    (103, 11, 102, 'B25U1T1D1',
				     'Biology Descriptor', 'DESCRIPTOR', 0),
				    (104, 11, 102, 'B25U1T1D2',
				     'Second Biology Descriptor', 'DESCRIPTOR', 1)
				""");
		execute(master, """
				INSERT INTO exam_providers (
				    id, provider_name
				)
				VALUES (21, 'QCAA')
				""");
		execute(master, """
				INSERT INTO source_documents (
				    id, relative_path
				)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""");
		execute(master, """
				INSERT INTO exams (
				    id, subject_id, provider_id,
				    exam_year, exam_name,
				    expected_question_booklet_count,
				    expected_answer_file_count
				)
				VALUES (
				    31, 1, 21, 2025, 'External Assessment', 2, 1
				)
				""");
		execute(master, """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id,
				    answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(master, """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id,
				    answer_file_id, booklet_name
				)
				VALUES
				    (61, 31, 51, 71, 'Paper 1'),
				    (62, 31, 51, 71, 'Paper 2')
				""");
		execute(master, """
				INSERT INTO questions (
				    id, booklet_id, classification_node_id,
				    question_code, question_text,
				    marks, shared_context_capture_required
				)
				VALUES (
				    81, 61, 103, '1', 'Biology question', 2, 0
				)
				""");
		execute(master, """
				INSERT INTO answers (
				    id, question_id, answer_text
				)
				VALUES (91, 81, 'Biology answer')
				""");
		if (includeOriginalImage) {

			// Create a valid small PNG before issuance so that
			// master and contributor retain the same portable image
			// identity despite independently assigned SQLite IDs.
			byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
					+ "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
			try (Connection connection = master.openConnection();
					PreparedStatement statement = connection.prepareStatement("""
							INSERT INTO question_images (
							    id, question_id, image_png
							)
							VALUES (151, 81, ?)
							""")) {
				statement.setBytes(1, png);
				assertEquals(1, statement.executeUpdate());
			}
			execute(master, """
					INSERT INTO question_content_parts (
					    question_id, content_order, content_type,
					    region_order, image_id
					)
					VALUES (81, 0, 'IMAGE', NULL, 151)
					""");
		}
		if (includeReviewedBooklets) {

			// Reviews must exist before issuance; master capture is locked
			// after the assignment has been registered.
			execute(master, """
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (61), (62)
					""");
		}
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
						SELECT q.question_text
						FROM questions q
						JOIN exam_booklets b
						    ON b.id = q.booklet_id
						JOIN exams e
						    ON e.id = b.exam_id
						WHERE e.exam_name = 'External Assessment'
						  AND b.booklet_name = 'Paper 1'
						  AND q.question_code = '1'
						""")) {

			// This fixture lookup uses stable business attributes.
			// It must not assume master and contributor local IDs agree.
			assertTrue(result.next());
			String text = result.getString(1);
			assertFalse(result.next());
			return text;
		}
	}

	private Path publish(Fixture fixture, String text, String name) throws Exception {

		// The contributor may create a new Question but must never alter
		// Question 1, which was included in the immutable issued baseline.
		try (Connection connection = fixture.contributor().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO questions (
						    booklet_id,
						    classification_node_id,
						    question_code,
						    question_text,
						    marks,
						    shared_context_capture_required,
						    response_type,
						    source_capture_required
						)
						SELECT
						    booklet_id,
						    classification_node_id,
						    '2',
						    ?,
						    marks,
						    shared_context_capture_required,
						    response_type,
						    source_capture_required
						FROM questions
						WHERE question_code = '1'
						""")) {
			statement.setString(1, text);
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement));
		}
		Path target = directory.resolve(name);
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), target);
		return target;
	}

	private Path publishNewContributorContext(Fixture fixture, String filename) throws Exception {
		byte[] png = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
		try (Connection connection = fixture.contributor().openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Create a contributor-owned Question. Never modify issued Q1.
				try (Statement statement = connection.createStatement()) {
					assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
							INSERT INTO questions (
							    booklet_id, classification_node_id,
							    question_code, question_text, marks,
							    shared_context_capture_required,
							    response_type, source_capture_required
							)
							SELECT booklet_id, classification_node_id,
							       '2', 'Question with shared image', marks,
							       shared_context_capture_required,
							       response_type, source_capture_required
							FROM questions
							WHERE question_code = '1'
							"""));
				}
				long questionId;
				long bookletId;
				try (PreparedStatement lookup = connection.prepareStatement("""
						SELECT id, booklet_id
						FROM questions
						WHERE question_code = '2'
						"""); ResultSet rows = lookup.executeQuery()) {
					assertTrue(rows.next());
					questionId = rows.getLong("id");
					bookletId = rows.getLong("booklet_id");
					assertFalse(rows.next());
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO shared_question_contexts (
						    booklet_id, context_label
						)
						VALUES (?, 'Imported shared image')
						""")) {
					insert.setLong(1, bookletId);
					assertEquals(1, insert.executeUpdate());
				}
				long contextId;
				try (Statement statement = connection.createStatement();
						ResultSet rows = statement.executeQuery("SELECT last_insert_rowid()")) {
					assertTrue(rows.next());
					contextId = rows.getLong(1);
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO shared_question_context_images (
						    shared_context_id, image_order, image_png
						)
						VALUES (?, 0, ?)
						""")) {
					insert.setLong(1, contextId);
					insert.setBytes(2, png);
					assertEquals(1, insert.executeUpdate());
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO shared_question_context_content_parts (
						    shared_context_id, content_order,
						    content_type, region_order, image_order
						)
						VALUES (?, 0, 'IMAGE', NULL, 0)
						""")) {
					insert.setLong(1, contextId);
					assertEquals(1, insert.executeUpdate());
				}
				try (PreparedStatement update = connection.prepareStatement("""
						UPDATE questions
						SET shared_context_id = ?
						WHERE id = ?
						""")) {
					update.setLong(1, contextId);
					update.setLong(2, questionId);
					assertEquals(1, update.executeUpdate());
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		Path archive = directory.resolve(filename);
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), archive);
		return archive;
	}

	private Path publishNewContributorImage(Fixture fixture, String filename) throws Exception {
		byte[] png = Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO3Ry8UAAAAASUVORK5CYII=");
		return publishNewContributorImage(fixture, filename, png);
	}

	private Path publishNewContributorImage(Fixture fixture, String filename, byte[] png) throws Exception {
		try (Connection connection = fixture.contributor().openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Create a contributor-owned Question without modifying
				// the coordinator's immutable issued Question.
				try (Statement statement = connection.createStatement()) {
					assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
							INSERT INTO questions (
							    booklet_id, classification_node_id,
							    question_code, question_text, marks,
							    shared_context_capture_required,
							    response_type, source_capture_required
							)
							SELECT
							    booklet_id,
							    classification_node_id,
							    '2',
							    'Contributor Question with image',
							    marks,
							    shared_context_capture_required,
							    response_type,
							    source_capture_required
							FROM questions
							WHERE question_code = '1'
							"""));
				}
				long questionId;
				try (PreparedStatement lookup = connection.prepareStatement("""
						SELECT id
						FROM questions
						WHERE question_code = '2'
						"""); ResultSet result = lookup.executeQuery()) {
					assertTrue(result.next());
					questionId = result.getLong(1);
					assertFalse(result.next());
				}

				// Preserve the supplied bytes exactly, including deliberately
				// invalid bytes used by binary-validation regression tests.
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO question_images (
						    question_id, image_png
						)
						VALUES (?, ?)
						""")) {
					insert.setLong(1, questionId);
					insert.setBytes(2, png);
					assertEquals(1, insert.executeUpdate());
				}
				long imageId;
				try (PreparedStatement lookup = connection.prepareStatement("""
						SELECT id
						FROM question_images
						WHERE question_id = ?
						""")) {
					lookup.setLong(1, questionId);
					try (ResultSet result = lookup.executeQuery()) {
						assertTrue(result.next());
						imageId = result.getLong(1);
						assertFalse(result.next());
					}
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO question_content_parts (
						    question_id, content_order, content_type,
						    region_order, image_id
						)
						VALUES (?, 0, 'IMAGE', NULL, ?)
						""")) {
					insert.setLong(1, questionId);
					insert.setLong(2, imageId);
					assertEquals(1, insert.executeUpdate());
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		Path destination = directory.resolve(filename);
		new ContributionExporter(fixture.contributor(), new ManagedDataLayout(fixture.contributorRoot()))
				.export(fixture.assignmentId(), destination);
		return destination;
	}

	private String questionTextForCode(SqliteDatabase database, String questionCode) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT question.question_text
						FROM questions question
						JOIN exam_booklets booklet
						  ON booklet.id = question.booklet_id
						JOIN exams exam
						  ON exam.id = booklet.exam_id
						WHERE exam.exam_name = 'External Assessment'
						  AND booklet.booklet_name = 'Paper 1'
						  AND question.question_code = ?
						""")) {
			statement.setString(1, questionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				String value = result.getString(1);
				assertFalse(result.next(), "Question code must be unique within the test Booklet");
				return value;
			}
		}
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
