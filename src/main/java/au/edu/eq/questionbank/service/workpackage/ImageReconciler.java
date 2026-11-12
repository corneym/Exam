package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;

/**
 * Compares embedded Question and Shared Context image provenance in two
 * independently exported Subject snapshots without accepting record aliases.
 *
 * <p>
 * Every embedded image digest must agree between its portable database record,
 * its containing capture and its validated asset index. Ordered mixed-content
 * parts are compared with image UUIDs replaced by their content digests. Image
 * equality is evidence only: it never upgrades a conflicting Question, Shared
 * Context, Booklet or PDF-region reconciliation decision.
 *
 * <p>
 * Callers must first validate both .examwork archives, including their binary
 * entry hashes, and calculate the capture reconciliation preview. This class
 * neither opens archives nor modifies databases, files or prior proposals.
 */
public final class ImageReconciler {

	/**
	 * Creates a stateless read-only image evidence reconciler.
	 */
	public ImageReconciler() {
	}

	/**
	 * Compares image ownership, original byte hashes and mixed-content order.
	 *
	 * @param master         coordinator's portable Subject records
	 * @param masterAssets   verified coordinator asset-reference index
	 * @param incoming       contributor's portable Subject records
	 * @param incomingAssets verified contributor asset-reference index
	 * @param captures       previous unapproved capture reconciliation preview
	 * @return evidence for image-bearing capture owners, without accepted aliases
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if portable image ownership or inventory is
	 *                                  inconsistent
	 */
	public ImagePreview preview(List<ExchangeRecord> master, ExamWorkAssetIndex masterAssets,
			List<ExchangeRecord> incoming, ExamWorkAssetIndex incomingAssets, CurriculumReconciler.Preview captures) {
		Objects.requireNonNull(captures, "captures");
		Graph left = new Graph(master, masterAssets);
		Graph right = new Graph(incoming, incomingAssets);
		Map<String, Map<UUID, Proposal>> mappings = new HashMap<>();
		for (Proposal proposal : captures.proposals()) {
			if (!proposal.recordType().equals("QUESTION") && !proposal.recordType().equals("SHARED_QUESTION_CONTEXT")) {
				continue;
			}
			Proposal duplicate = mappings.computeIfAbsent(proposal.recordType(), _ -> new HashMap<>())
					.putIfAbsent(proposal.incomingId(), proposal);
			if (duplicate != null) {
				throw new IllegalArgumentException("Duplicate capture proposal: " + proposal.incomingId());
			}
		}
		List<Evidence> comparisons = new ArrayList<>();
		for (String type : List.of("QUESTION", "SHARED_QUESTION_CONTEXT")) {
			for (ExchangeRecord record : right.records(type)) {
				Proposal parent = mappings.getOrDefault(type, Map.of()).get(record.recordId());
				if (parent == null) {
					throw new IllegalArgumentException(
							"Missing capture proposal for " + type + ":" + record.recordId());
				}
				OwnerContent current = right.content(type, record.recordId());
				OwnerContent previous = parent.masterId() == null ? OwnerContent.EMPTY
						: left.content(type, parent.masterId());
				if (current.isEmpty() && previous.isEmpty()) {
					continue;
				}
				Classification status;
				String reason;
				if (parent.masterId() == null) {
					status = parent.classification() == Classification.CONFLICT ? Classification.CONFLICT
							: parent.classification();
					reason = status == Classification.CONFLICT
							? "Capture parent is ambiguous; image bytes cannot establish its identity."
							: "Images belong to a proposed independent capture; no master image is overwritten.";
				} else if (!previous.equals(current)) {
					status = Classification.CONFLICT;
					reason = "Embedded image bytes, multiplicity or mixed-content order differ.";
				} else {
					status = Classification.IDENTICAL;
					reason = "Embedded PNG hashes, owners and composition order agree; "
							+ "the capture parent still requires its own reconciliation decision.";
				}
				comparisons.add(new Evidence(type, record.recordId(), parent.masterId(), status, reason));
			}
		}
		comparisons.sort(
				Comparator.comparing(Evidence::ownerType).thenComparing(item -> item.incomingOwnerId().toString()));
		return new ImagePreview(comparisons);
	}

	/**
	 * One image-evidence comparison for an incoming capture owner.
	 *
	 * <p>
	 * An IDENTICAL classification establishes only embedded-image equivalence; it
	 * is not a capture-record alias or an approval to import.
	 *
	 * @param ownerType       QUESTION or SHARED_QUESTION_CONTEXT
	 * @param incomingOwnerId portable incoming capture identity
	 * @param masterOwnerId   proposed master capture identity, if any
	 * @param classification  image-only comparison result
	 * @param reason          explanation of image evidence or a blocking difference
	 */
	public record Evidence(String ownerType, UUID incomingOwnerId, UUID masterOwnerId, Classification classification,
			String reason) {

		/**
		 * Validates an immutable image-evidence result.
		 */
		public Evidence {
			if (!"QUESTION".equals(ownerType) && !"SHARED_QUESTION_CONTEXT".equals(ownerType)) {
				throw new IllegalArgumentException("Unsupported image owner type: " + ownerType);
			}
			Objects.requireNonNull(incomingOwnerId, "incomingOwnerId");
			Objects.requireNonNull(classification, "classification");
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Image evidence requires a reason");
			}
			if (classification == Classification.IDENTICAL && masterOwnerId == null) {
				throw new IllegalArgumentException("Matching image evidence requires a master owner");
			}
		}
	}

	/**
	 * Immutable image-evidence results for the incoming Subject.
	 *
	 * @param evidence comparisons ordered by owner type and portable UUID
	 */
	public record ImagePreview(List<Evidence> evidence) {

		/**
		 * Copies the evidence so it cannot be modified by the caller.
		 */
		public ImagePreview {
			evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
		}
	}

	/** Validates portable image records against their logical asset usages. */
	private static final class Graph {

		private final Map<String, Map<UUID, ExchangeRecord>> records = new HashMap<>();
		private final Map<UUID, List<Reference>> questionAssets = new HashMap<>();
		private final Map<UUID, List<Reference>> contextAssets = new HashMap<>();
		private final Map<UUID, List<ExchangeRecord>> imagesByQuestion = new HashMap<>();

		/** Builds a private, read-only graph and checks all image-bearing owners. */
		private Graph(List<ExchangeRecord> source, ExamWorkAssetIndex assets) {
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(assets, "assets");
			for (ExchangeRecord record : source) {
				ExchangeRecord duplicate = records.computeIfAbsent(record.recordType(), _ -> new HashMap<>())
						.putIfAbsent(record.recordId(), record);
				if (duplicate != null) {
					throw new IllegalArgumentException("Duplicate portable identity: " + record.recordId());
				}
			}
			for (Reference reference : assets.references()) {
				if ("EMBEDDED_QUESTION_PNG".equals(reference.usage())) {
					requireImageAsset(reference, "QUESTION_IMAGE");
					questionAssets.computeIfAbsent(reference.ownerId(), _ -> new ArrayList<>()).add(reference);
				} else if ("EMBEDDED_CONTEXT_PNG".equals(reference.usage())) {
					requireImageAsset(reference, "SHARED_QUESTION_CONTEXT");
					contextAssets.computeIfAbsent(reference.ownerId(), _ -> new ArrayList<>()).add(reference);
				} else if ("QUESTION_IMAGE".equals(reference.ownerType())
						|| "SHARED_QUESTION_CONTEXT".equals(reference.ownerType())
								&& reference.usage().startsWith("EMBEDDED_")) {
					throw new IllegalArgumentException("Unexpected image asset usage: " + reference.usage());
				}
			}
			for (ExchangeRecord image : records("QUESTION_IMAGE")) {
				UUID owner = uuid(image.values(), "question_id");
				require("QUESTION", owner);
				imagesByQuestion.computeIfAbsent(owner, _ -> new ArrayList<>()).add(image);
				List<Reference> references = questionAssets.getOrDefault(image.recordId(), List.of());
				if (references.size() != 1
						|| !references.getFirst().sha256().equals(hash(image.values(), "image_png"))) {
					throw new IllegalArgumentException(
							"QuestionImage has missing or mismatched PNG asset: " + image.recordId());
				}
			}
			for (UUID owner : questionAssets.keySet()) {
				require("QUESTION_IMAGE", owner);
			}
			for (UUID owner : contextAssets.keySet()) {
				require("SHARED_QUESTION_CONTEXT", owner);
			}
			for (ExchangeRecord owner : records("QUESTION")) {
				content("QUESTION", owner.recordId());
			}
			for (ExchangeRecord owner : records("SHARED_QUESTION_CONTEXT")) {
				content("SHARED_QUESTION_CONTEXT", owner.recordId());
			}
		}

		/** Reads a missing or explicit-null optional child collection as empty. */
		private List<JsonNode> array(JsonNode node, String field) {
			JsonNode children = node.path(field);
			if (children.isMissingNode() || children.isNull()) {
				return List.of();
			}
			if (!children.isArray()) {
				throw new IllegalArgumentException("Expected array: " + field);
			}
			List<JsonNode> copy = new ArrayList<>();
			children.forEach(copy::add);
			return copy;
		}

		/** Computes image byte multiplicity and immutable composition order. */
		private OwnerContent content(String type, UUID id) {
			ExchangeRecord owner = require(type, id);
			JsonNode values = owner.values();
			if ("QUESTION".equals(type)) {
				Map<UUID, String> imageHashes = new HashMap<>();

				// Images are indexed by owning Question once. Avoid scanning
				// the entire Subject's image corpus for each Question.
				for (ExchangeRecord image : imagesByQuestion.getOrDefault(id, List.of())) {
					imageHashes.put(image.recordId(), hash(image.values(), "image_png"));
				}
				List<String> persistedHashes = new ArrayList<>();
				for (JsonNode child : array(values, "question_images")) {
					persistedHashes.add(hash(child, "image_png"));
				}
				if (!counts(persistedHashes).equals(counts(new ArrayList<>(imageHashes.values())))) {
					throw new IllegalArgumentException(
							"Question image records disagree with embedded child rows: " + id);
				}
				List<String> parts = new ArrayList<>();
				for (JsonNode part : array(values, "question_content_parts")) {
					if ("IMAGE".equals(part.path("content_type").asText())) {
						UUID imageId = uuid(part, "image_id");
						String digest = imageHashes.get(imageId);
						if (digest == null) {
							throw new IllegalArgumentException(
									"Question content references a foreign image: " + imageId);
						}
						parts.add("IMAGE:" + digest);
					} else {
						parts.add("OTHER:" + part.toString());
					}
				}
				persistedHashes.sort(String::compareTo);
				return new OwnerContent(persistedHashes, parts);
			}
			if (!"SHARED_QUESTION_CONTEXT".equals(type)) {
				throw new IllegalArgumentException("Unsupported image owner: " + type);
			}
			Map<Integer, String> byOrder = new HashMap<>();
			List<String> imageHashes = new ArrayList<>();
			for (JsonNode image : array(values, "shared_question_context_images")) {
				JsonNode order = image.path("image_order");
				if (!order.isIntegralNumber() || order.asInt() < 0
						|| byOrder.putIfAbsent(order.asInt(), hash(image, "image_png")) != null) {
					throw new IllegalArgumentException("Invalid or duplicate Shared Context image order: " + id);
				}
				imageHashes.add(hash(image, "image_png"));
			}
			List<String> assets = contextAssets.getOrDefault(id, List.of()).stream().map(Reference::sha256).toList();
			if (!counts(imageHashes).equals(counts(assets))) {
				throw new IllegalArgumentException("Shared Context images disagree with PNG assets: " + id);
			}
			List<String> parts = new ArrayList<>();
			for (JsonNode part : array(values, "shared_question_context_content_parts")) {
				if ("IMAGE".equals(part.path("content_type").asText())) {
					JsonNode order = part.path("image_order");
					if (!order.isIntegralNumber() || !byOrder.containsKey(order.asInt())) {
						throw new IllegalArgumentException("Shared Context part references missing image: " + id);
					}
					parts.add("IMAGE:" + byOrder.get(order.asInt()));
				} else {
					parts.add("OTHER:" + part.toString());
				}
			}

			// The stored image_order is significant even when no content
			// assembly rows exist yet. Do not turn the Context into a set.
			List<String> orderedImages = byOrder.entrySet().stream().sorted(Map.Entry.comparingByKey())
					.map(Map.Entry::getValue).toList();
			return new OwnerContent(orderedImages, parts);
		}

		/**
		 * Builds a multiset so equal bytes in different logical images retain
		 * multiplicity.
		 */
		private Map<String, Long> counts(List<String> digests) {
			return digests.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		}

		/** Requires a canonical lower-case SHA-256 string. */
		private String hash(JsonNode node, String field) {
			JsonNode value = node.path(field);
			if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Missing or invalid " + field + " digest");
			}
			return value.asText();
		}

		/** Reads records in deterministic UUID order. */
		private List<ExchangeRecord> records(String type) {
			return records.getOrDefault(type, Map.of()).values().stream()
					.sorted(Comparator.comparing(record -> record.recordId().toString())).toList();
		}

		/** Requires the given portable record to exist in this source graph. */
		private ExchangeRecord require(String type, UUID id) {
			ExchangeRecord record = records.getOrDefault(type, Map.of()).get(id);
			if (record == null) {
				throw new IllegalArgumentException("Missing " + type + ":" + id);
			}
			return record;
		}

		/** Validates a Subject-local PNG asset owner and media type. */
		private void requireImageAsset(Reference reference, String ownerType) {
			if (!ownerType.equals(reference.ownerType()) || reference.ownerId() == null
					|| !"image/png".equals(reference.mediaType())) {
				throw new IllegalArgumentException("Invalid embedded PNG asset ownership or media type");
			}
		}

		/** Parses and validates a portable UUID reference. */
		private UUID uuid(JsonNode node, String field) {
			JsonNode value = node.path(field);
			if (!value.isTextual()) {
				throw new IllegalArgumentException("Missing portable reference: " + field);
			}
			try {
				UUID id = UUID.fromString(value.asText());
				if (!id.toString().equals(value.asText())) {
					throw new IllegalArgumentException("Noncanonical UUID");
				}
				return id;
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("Invalid portable reference: " + field, exception);
			}
		}
	}

	/** Represents the complete image inventory and ordered assembly of an owner. */
	private record OwnerContent(List<String> imageHashes, List<String> orderedParts) {

		private static final OwnerContent EMPTY = new OwnerContent(List.of(), List.of());

		/** Copies both lists for stable comparison across snapshots. */
		private OwnerContent {
			imageHashes = List.copyOf(imageHashes);
			orderedParts = List.copyOf(orderedParts);
		}

		/** Indicates that no images or image-referencing parts exist. */
		private boolean isEmpty() {
			return imageHashes.isEmpty() && orderedParts.stream().noneMatch(item -> item.startsWith("IMAGE:"));
		}
	}
}
