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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Prepares reviewed Shared Context image content from the original contributor
 * return.
 *
 * <p>
 * All content is checked against durable intake and review evidence, portable
 * owner identities, ordered image positions and the original archive bytes.
 *
 * <p>
 * This service is read-only. It never applies Shared Context changes, modifies
 * the authoritative master or releases an assignment lock. Its output must be
 * revalidated under the application transaction before any reviewed write is
 * authorised.
 */
final class MasterContributionApprovedContextPreparer {

	private static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;
	private static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private final SqliteDatabase database;

	MasterContributionApprovedContextPreparer(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	List<PreparedContext> prepare(RecordedReview review, Path archive) throws SQLException, IOException {
		Objects.requireNonNull(review, "review");
		Objects.requireNonNull(archive, "archive");
		if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Original contribution archive is unavailable");
		}
		RecordedReview recorded = new MasterContributionDecisionLedger(database).find(review.reviewId())
				.orElseThrow(() -> new SQLException("Contribution review is not recorded"));
		if (!recorded.equals(review)) {
			throw new SQLException("Shared Context review differs from durable coordinator evidence");
		}
		MasterContributionIntake intake = new MasterContributionIntake(database);
		var received = intake.find(review.packageId())
				.orElseThrow(() -> new SQLException("Reviewed contribution has not been received"));
		if (received.status() != MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW) {
			throw new SQLException("Shared Context return is not actionable");
		}
		var inspected = new ContributionArchiveInspector(database).inspect(archive);
		if (!review.packageId().equals(inspected.manifest().packageId())
				|| !received.assignmentId().equals(inspected.assignment().assignmentId())
				|| !received.archiveSha256().equals(inspected.archiveSha256())) {
			throw new IOException("Shared Context archive differs from durable intake");
		}
		Map<RecordKey, ExchangeRecord> changes = indexRecords(intake.proposedChanges(review.packageId()));
		Map<RecordKey, ExchangeRecord> inspectedChanges = indexRecords(inspected.changes());
		if (!changes.equals(inspectedChanges)) {
			throw new IOException("Shared Context proposals differ from received evidence");
		}
		Map<RecordKey, ExchangeRecord> proposedGraph = indexRecords(inspected.proposedRecords());
		Map<RecordKey, RecordDecision> decisions = new HashMap<>();
		for (RecordDecision decision : review.decisions()) {
			if (decisions.putIfAbsent(decision.key(), decision) != null) {
				throw new SQLException("Duplicate coordinator review decision");
			}
		}
		if (!decisions.keySet().equals(changes.keySet())) {
			throw new SQLException("Review does not cover the complete received proposal set");
		}
		Map<ContextImageKey, Reference> assets = indexContextAssets(inspected.assets(),
				inspected.manifest().subjectName());
		List<PreparedContext> prepared = new ArrayList<>();
		long totalBytes = 0;
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			for (RecordDecision decision : review.decisions()) {
				if (!"SHARED_QUESTION_CONTEXT".equals(decision.key().recordType())
						|| decision.choice() != Choice.QUEUE_APPLY) {
					continue;
				}
				if (decision.preliminaryStatus() != ReviewStatus.READY_NEW
						&& decision.preliminaryStatus() != ReviewStatus.READY_UPDATE) {
					throw new IOException("Approved Shared Context has unsupported review status");
				}
				ExchangeRecord context = changes.get(decision.key());
				ExchangeRecord graphContext = proposedGraph.get(decision.key());
				if (context == null || graphContext == null
						|| !context.revisionId().equals(decision.proposedRevisionId())
						|| !context.revisionId().equals(graphContext.revisionId())
						|| !context.values().equals(graphContext.values())) {
					throw new IOException("Approved Shared Context differs from proposed graph");
				}
				List<PreparedContextImage> images = readContextImages(zip, context, assets);
				for (PreparedContextImage image : images) {
					if (image.pngBytes().length > MAX_TOTAL_BYTES - totalBytes) {
						throw new IOException("Approved Shared Context image limit exceeded");
					}
					totalBytes += image.pngBytes().length;
				}
				prepared.add(new PreparedContext(context, images));
			}
		}

		// Recheck the original file after reading its ZIP entries.
		if (!hashFile(archive).equals(received.archiveSha256())) {
			throw new IOException("Contribution archive changed during Shared Context preparation");
		}
		return List.copyOf(prepared);
	}

	private String hashFile(Path archive) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(archive)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private Map<ContextImageKey, Reference> indexContextAssets(ExamWorkAssetIndex assets, String subjectName)
			throws IOException {
		Map<ContextImageKey, Reference> indexed = new HashMap<>();
		for (Reference reference : assets.references()) {
			if (!"EMBEDDED_CONTEXT_PNG".equals(reference.usage())) {
				continue;
			}
			if (!"SHARED_QUESTION_CONTEXT".equals(reference.ownerType()) || reference.ownerId() == null
					|| !"image/png".equals(reference.mediaType()) || !reference.sha256().matches("[0-9a-f]{64}")) {
				throw new IOException("Invalid Shared Context image asset ownership");
			}
			String prefix = "subjects/" + subjectName + "/exams/_examwork_embedded/" + "SHARED_QUESTION_CONTEXT/"
					+ reference.ownerId() + "-";
			if (!reference.managedPath().startsWith(prefix)) {
				throw new IOException("Invalid Shared Context image asset path");
			}
			String suffix = reference.managedPath().substring(prefix.length());
			if (!suffix.matches("(0|[1-9][0-9]*)\\.png")) {
				throw new IOException("Invalid Shared Context image order");
			}
			int order;
			try {
				order = Integer.parseInt(suffix.substring(0, suffix.length() - 4));
			} catch (NumberFormatException invalid) {
				throw new IOException("Shared Context image order exceeds supported range", invalid);
			}
			ContextImageKey key = new ContextImageKey(reference.ownerId(), order);
			if (indexed.putIfAbsent(key, reference) != null) {
				throw new IOException("Duplicate Shared Context image asset ownership");
			}
		}
		return Map.copyOf(indexed);
	}

	private Map<RecordKey, ExchangeRecord> indexRecords(List<ExchangeRecord> records) throws IOException {
		Map<RecordKey, ExchangeRecord> indexed = new HashMap<>();
		for (ExchangeRecord record : records) {
			RecordKey key = new RecordKey(record.recordType(), record.recordId());
			if (indexed.putIfAbsent(key, record) != null) {
				throw new IOException("Duplicate portable contribution identity: " + key);
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

	private List<PreparedContextImage> readContextImages(ZipFile zip, ExchangeRecord context,
			Map<ContextImageKey, Reference> assets) throws IOException {
		JsonNode rows = context.values().get("shared_question_context_images");
		if (rows == null || !rows.isArray()) {
			throw new IOException("Approved Shared Context has no valid image collection");
		}
		List<PreparedContextImage> prepared = new ArrayList<>();
		Set<ContextImageKey> used = new HashSet<>();
		for (JsonNode row : rows) {
			JsonNode orderNode = row.path("image_order");
			JsonNode hashNode = row.path("image_png");
			if (!orderNode.isIntegralNumber() || !orderNode.canConvertToInt() || orderNode.intValue() < 0
					|| !hashNode.isTextual() || !hashNode.asText().matches("[0-9a-f]{64}")) {
				throw new IOException("Invalid approved Shared Context image record");
			}
			int order = orderNode.intValue();
			ContextImageKey key = new ContextImageKey(context.recordId(), order);
			if (!used.add(key)) {
				throw new IOException("Duplicate approved Shared Context image order");
			}
			Reference reference = assets.get(key);
			if (reference == null || !hashNode.asText().equals(reference.sha256())) {
				throw new IOException("Shared Context image differs from asset evidence");
			}
			byte[] bytes = readPng(zip, reference);
			prepared.add(new PreparedContextImage(order, reference.sha256(), bytes));
		}
		for (ContextImageKey key : assets.keySet()) {
			if (key.contextId().equals(context.recordId()) && !used.contains(key)) {
				throw new IOException("Unreferenced Shared Context image asset");
			}
		}
		prepared.sort(Comparator.comparingInt(PreparedContextImage::imageOrder));
		return List.copyOf(prepared);
	}

	private byte[] readPng(ZipFile zip, Reference reference) throws IOException {
		long size = reference.size();
		if (size < PNG_SIGNATURE.length || size > MAX_IMAGE_BYTES) {
			throw new IOException("Approved PNG has invalid size");
		}
		ZipArchiveEntry entry = zip.getEntry(reference.archivePath());
		if (entry == null || entry.isDirectory() || entry.getSize() != size) {
			throw new IOException("Approved PNG archive entry is missing or invalid");
		}
		byte[] bytes;
		try (InputStream input = zip.getInputStream(entry)) {
			bytes = input.readNBytes((int) size + 1);
		}
		if (bytes.length != size || !Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
			throw new IOException("Approved PNG length or signature differs from evidence");
		}
		String actualHash = HexFormat.of().formatHex(newDigest().digest(bytes));
		if (!actualHash.equals(reference.sha256())) {
			throw new IOException("Approved PNG differs from declared SHA-256");
		}
		return bytes;
	}

	record PreparedContext(ExchangeRecord proposal, List<PreparedContextImage> images) {

		PreparedContext {
			Objects.requireNonNull(proposal, "proposal");
			images = List.copyOf(Objects.requireNonNull(images, "images"));
		}
	}

	record PreparedContextImage(int imageOrder, String sha256, byte[] pngBytes) {

		PreparedContextImage {
			if (imageOrder < 0) {
				throw new IllegalArgumentException("Image order must not be negative");
			}
			Objects.requireNonNull(sha256, "sha256");
			if (!sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid prepared image SHA-256");
			}
			pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
		}

		/**
		 * Returns an independent copy of the prepared PNG bytes.
		 *
		 * @return defensive copy of the verified image bytes
		 */
		@Override
		public byte[] pngBytes() {
			return pngBytes.clone();
		}
	}

	private record ContextImageKey(UUID contextId, int imageOrder) {
	}
}
