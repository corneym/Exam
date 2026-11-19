package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.Choice;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordedReview;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Prepares coordinator-approved embedded Question PNG payloads.
 *
 * <p>
 * Resolves the original received archive, durable intake evidence, explicit
 * review choices, portable Question ownership and content hashes before
 * returning any image bytes.
 *
 * <p>
 * Only images with QUEUE_APPLY decisions and approved parent Questions are
 * prepared. Rejected, skipped and held images produce no prepared payloads.
 *
 * <p>
 * This is a read-only preparation boundary. It never changes the master
 * database or managed assets. A later image writer must revalidate the review
 * and master revision evidence inside its own application transaction.
 */
final class MasterContributionApprovedImagePreparer {

	private static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;
	private static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private final SqliteDatabase database;

	MasterContributionApprovedImagePreparer(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	List<PreparedImage> prepare(RecordedReview review, Path archive) throws SQLException, IOException {
		Objects.requireNonNull(review, "review");
		Objects.requireNonNull(archive, "archive");
		if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Original contribution archive is unavailable");
		}
		RecordedReview recorded = new MasterContributionDecisionLedger(database).find(review.reviewId())
				.orElseThrow(() -> new SQLException("Contribution review is not recorded"));
		if (!recorded.equals(review)) {
			throw new SQLException("Supplied image review differs from " + "durable coordinator evidence");
		}
		MasterContributionIntake intake = new MasterContributionIntake(database);
		var received = intake.find(review.packageId())
				.orElseThrow(() -> new SQLException("Reviewed return has not been received"));
		if (received.status() != MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW) {
			throw new SQLException("Reviewed image return is not actionable");
		}
		var inspected = new ContributionArchiveInspector(database).inspect(archive);
		if (!review.packageId().equals(inspected.manifest().packageId())
				|| !received.assignmentId().equals(inspected.assignment().assignmentId())
				|| !received.archiveSha256().equals(inspected.archiveSha256())) {
			throw new IOException("Image archive differs from durable " + "contribution intake");
		}
		Map<RecordKey, ExchangeRecord> changes = indexRecords(intake.proposedChanges(review.packageId()));
		Map<RecordKey, ExchangeRecord> inspectedChanges = indexRecords(inspected.changes());
		if (!changes.equals(inspectedChanges)) {
			throw new IOException("Image proposals differ from received evidence");
		}
		Map<RecordKey, ExchangeRecord> proposedGraph = indexRecords(inspected.proposedRecords());
		Map<RecordKey, RecordDecision> decisions = new HashMap<>();
		for (RecordDecision decision : review.decisions()) {
			if (decisions.putIfAbsent(decision.key(), decision) != null) {
				throw new SQLException("Duplicate reviewed image decision");
			}
		}
		if (!decisions.keySet().equals(changes.keySet())) {
			throw new SQLException("Review does not cover all received proposals");
		}
		Map<UUID, Reference> imageAssets = indexQuestionImageAssets(inspected.assets());
		List<PreparedImage> prepared = new ArrayList<>();
		long totalBytes = 0;
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			for (RecordDecision decision : review.decisions()) {
				if (!"QUESTION_IMAGE".equals(decision.key().recordType()) || decision.choice() != Choice.QUEUE_APPLY) {
					continue;
				}
				if (decision.preliminaryStatus() != ReviewStatus.READY_NEW
						&& decision.preliminaryStatus() != ReviewStatus.READY_UPDATE) {
					throw new IOException("Approved image has unsupported " + "review status");
				}
				ExchangeRecord image = changes.get(decision.key());
				if (image == null || !image.revisionId().equals(decision.proposedRevisionId())) {
					throw new IOException("Approved image differs from review");
				}
				UUID questionId = requireUuid(image.values().get("question_id"), "image parent Question");
				requireApprovedParent(questionId, image, decisions, proposedGraph);
				Reference reference = imageAssets.get(image.recordId());
				if (reference == null) {
					throw new IOException("Approved image has no asset reference");
				}
				String expectedHash = requireHash(image.values().get("image_png"));
				if (!expectedHash.equals(reference.sha256())) {
					throw new IOException("Image content hash differs from " + "approved portable record");
				}
				if (reference.size() > MAX_IMAGE_BYTES || reference.size() > MAX_TOTAL_BYTES - totalBytes) {
					throw new IOException("Approved image payload limit exceeded");
				}
				byte[] bytes = readVerifiedImage(zip, reference);
				totalBytes += bytes.length;
				prepared.add(new PreparedImage(image.recordId(), questionId, image.revisionId(), expectedHash, bytes));
			}
		}

		// A file replacement during preparation must not allow
		// previously read PNG bytes to be treated as evidence
		// from the originally received return.
		if (!digest(archive).equals(inspected.archiveSha256())) {
			throw new IOException("Contribution archive changed during " + "image preparation");
		}
		return List.copyOf(prepared);
	}

	private String digest(Path path) throws IOException {
		MessageDigest sha256 = newDigest();
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				sha256.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(sha256.digest());
	}

	private Map<UUID, Reference> indexQuestionImageAssets(ExamWorkAssetIndex assets) throws IOException {
		Map<UUID, Reference> indexed = new HashMap<>();
		for (Reference reference : assets.references()) {
			if (!"EMBEDDED_QUESTION_PNG".equals(reference.usage())) {
				continue;
			}
			if (!"QUESTION_IMAGE".equals(reference.ownerType()) || reference.ownerId() == null
					|| !"image/png".equals(reference.mediaType())) {
				throw new IOException("Invalid Question image asset ownership");
			}
			if (indexed.putIfAbsent(reference.ownerId(), reference) != null) {
				throw new IOException("Multiple assets claim one Question image");
			}
		}
		return Map.copyOf(indexed);
	}

	private Map<RecordKey, ExchangeRecord> indexRecords(List<ExchangeRecord> records) throws IOException {
		Map<RecordKey, ExchangeRecord> indexed = new HashMap<>();
		for (ExchangeRecord record : records) {
			RecordKey key = new RecordKey(record.recordType(), record.recordId());
			if (indexed.putIfAbsent(key, record) != null) {
				throw new IOException("Duplicate portable record identity: " + key);
			}
		}
		return Map.copyOf(indexed);
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private byte[] readVerifiedImage(ZipFile archive, Reference reference) throws IOException {
		long size = reference.size();
		if (size < PNG_SIGNATURE.length || size > MAX_IMAGE_BYTES) {
			throw new IOException("Approved PNG has invalid size");
		}
		ZipArchiveEntry entry = archive.getEntry(reference.archivePath());
		if (entry == null || entry.isDirectory() || entry.getSize() != size) {
			throw new IOException("Approved image archive entry is missing " + "or has an invalid size");
		}
		byte[] bytes;
		try (InputStream input = archive.getInputStream(entry)) {

			// The bounded extra byte detects unexpectedly longer
			// data without trusting the ZIP header alone.
			bytes = input.readNBytes((int) size + 1);
		}
		if (bytes.length != size) {
			throw new IOException("Approved PNG length differs from asset index");
		}
		if (!Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
			throw new IOException("Approved image lacks PNG signature");
		}
		String actualHash = HexFormat.of().formatHex(newDigest().digest(bytes));
		if (!actualHash.equals(reference.sha256())) {
			throw new IOException("Approved PNG bytes differ from declared hash");
		}
		return bytes;
	}

	private void requireApprovedParent(UUID questionId, ExchangeRecord image, Map<RecordKey, RecordDecision> decisions,
			Map<RecordKey, ExchangeRecord> graph) throws IOException {
		RecordKey parentKey = new RecordKey("QUESTION", questionId);
		RecordDecision parentDecision = decisions.get(parentKey);
		if (parentDecision == null || parentDecision.choice() != Choice.QUEUE_APPLY
				|| (parentDecision.preliminaryStatus() != ReviewStatus.READY_NEW
						&& parentDecision.preliminaryStatus() != ReviewStatus.READY_UPDATE)) {
			throw new IOException("Approved image requires an approved " + "parent Question proposal");
		}
		ExchangeRecord parent = graph.get(parentKey);
		if (parent == null) {
			throw new IOException("Approved image parent is missing " + "from proposed Exam graph");
		}
		JsonNode images = parent.values().get("question_images");
		if (images == null || !images.isArray()) {
			throw new IOException("Approved parent has no valid image collection");
		}
		String expectedHash = requireHash(image.values().get("image_png"));
		boolean found = false;
		for (JsonNode child : images) {
			if (expectedHash.equals(child.path("image_png").asText())) {
				found = true;
				break;
			}
		}
		if (!found) {
			throw new IOException("Approved image is not represented " + "by the parent Question");
		}
	}

	private String requireHash(JsonNode value) throws IOException {
		if (value == null || !value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) {
			throw new IOException("Approved image has invalid content hash");
		}
		return value.asText();
	}

	private UUID requireUuid(JsonNode node, String description) throws IOException {
		if (node == null || !node.isTextual()) {
			throw new IOException("Missing " + description + " UUID");
		}
		try {
			UUID id = UUID.fromString(node.asText());
			if (!id.toString().equals(node.asText())) {
				throw new IllegalArgumentException("Noncanonical portable UUID");
			}
			return id;
		} catch (IllegalArgumentException failure) {
			throw new IOException("Invalid " + description + " UUID", failure);
		}
	}

	/**
	 * Immutable image import input with defensively copied PNG bytes.
	 *
	 * @param imageId            approved contributor image portable UUID
	 * @param questionId         approved parent Question portable UUID
	 * @param proposedRevisionId reviewed contributor image revision
	 * @param sha256             SHA-256 of the original PNG bytes
	 * @param pngBytes           original verified PNG bytes
	 */
	record PreparedImage(UUID imageId, UUID questionId, UUID proposedRevisionId, String sha256, byte[] pngBytes) {

		PreparedImage {
			Objects.requireNonNull(imageId, "imageId");
			Objects.requireNonNull(questionId, "questionId");
			Objects.requireNonNull(proposedRevisionId, "proposedRevisionId");
			Objects.requireNonNull(sha256, "sha256");
			pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
			if (!sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid prepared PNG hash");
			}
		}

		/**
		 * Returns a defensive copy of the prepared PNG.
		 *
		 * @return independent copy of the verified image bytes
		 */
		@Override
		public byte[] pngBytes() {
			return pngBytes.clone();
		}
	}
}
