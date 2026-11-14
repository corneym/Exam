package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;

/**
 * Produces read-only, baseline-free reconciliation proposals for captured
 * SourceQuestions, Shared Contexts, Questions and Answers.
 *
 * <p>
 * Logical matching is scoped to a previously matched Booklet or Question; never
 * to a numeric database identifier or an unqualified Question code. Parent
 * proposals, PDF provenance and curriculum mappings must already have been
 * examined by the preceding reconciliation stages. A conflicting parent
 * prevents an identical child proposal, even where child fields look alike.
 *
 * <p>
 * Portable reference fields are compared after rebasing incoming references
 * through unambiguous, IDENTICAL proposals. Content order and PDF-region
 * coordinates are compared without reordering. Unresolved image references
 * remain conflicts until image reconciliation is implemented separately.
 *
 * <p>
 * No proposal is accepted here, and neither source dataset is mutated.
 */
public final class CaptureReconciler {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Map<String, String> REFERENCE_TYPES = Map.of("booklet_id", "EXAM_BOOKLET",
			"classification_node_id", "CURRICULUM_NODE", "source_question_id", "SOURCE_QUESTION", "shared_context_id",
			"SHARED_QUESTION_CONTEXT", "pending_mcq_shared_context_id", "SHARED_QUESTION_CONTEXT",
			"current_curriculum_node_id", "CURRICULUM_NODE", "answer_file_id", "ANSWER_FILE", "question_id", "QUESTION",
			"image_id", "QUESTION_IMAGE");

	/**
	 * Creates a stateless capture reconciliation service.
	 */
	public CaptureReconciler() {
	}

	/**
	 * Compares the supported capture records in two validated Subject snapshots.
	 *
	 * <p>
	 * The supplied curriculum and assessment previews must describe these same
	 * inputs. They provide proposed parent identity mappings, but only IDENTICAL
	 * mappings count as resolved dependencies. The returned preview contains only
	 * capture types, not repeated parent proposals.
	 *
	 * @param master     coordinator's portable Subject records
	 * @param incoming   contributor's portable Subject records
	 * @param curriculum preceding curriculum reconciliation preview
	 * @param assessment preceding Exam and source-document reconciliation preview
	 * @return immutable, unapproved capture proposals
	 * @throws NullPointerException     if an input is null
	 * @throws IllegalArgumentException if identity or parent references are invalid
	 */
	public Preview preview(List<ExchangeRecord> master, List<ExchangeRecord> incoming, Preview curriculum,
			Preview assessment) {
		return preview(master, incoming, curriculum, assessment, Set.of());
	}

	Preview preview(List<ExchangeRecord> master, List<ExchangeRecord> incoming, Preview curriculum, Preview assessment,
			Set<String> verifiedImageOwners) {
		Objects.requireNonNull(verifiedImageOwners, "verifiedImageOwners");
		Graph left = new Graph(master);
		Graph right = new Graph(incoming);
		Objects.requireNonNull(curriculum, "curriculum");
		Objects.requireNonNull(assessment, "assessment");
		Map<String, Map<UUID, Proposal>> matches = new HashMap<>();
		addPreceding(matches, left, right, curriculum);
		addPreceding(matches, left, right, assessment);
		List<Proposal> results = new ArrayList<>();
		matchNamed("SOURCE_QUESTION", "source_question_code", "booklet_id", left, right, matches, results,
				verifiedImageOwners);
		matchNamed("SHARED_QUESTION_CONTEXT", "context_label", "booklet_id", left, right, matches, results,
				verifiedImageOwners);
		matchNamed("QUESTION", "question_code", "booklet_id", left, right, matches, results, verifiedImageOwners);
		matchNamed("ANSWER", null, "question_id", left, right, matches, results, verifiedImageOwners);
		results.sort(Comparator.comparing(Proposal::recordType).thenComparing(p -> p.incomingId().toString()));
		return new Preview(results);
	}

	/** Verifies and indexes all preceding identity proposals. */
	private void addPreceding(Map<String, Map<UUID, Proposal>> matches, Graph master, Graph incoming, Preview preview) {
		for (Proposal proposal : preview.proposals()) {
			incoming.require(proposal.recordType(), proposal.incomingId());
			if (proposal.masterId() != null) {
				master.require(proposal.recordType(), proposal.masterId());
			}
			store(matches, proposal);
		}
	}

	/**
	 * Rejects a new capture whose required relationships are unresolved.
	 *
	 * <p>
	 * An incoming Question may reference its own QuestionImage before QuestionImage
	 * proposals have been produced. That reference is permitted only when the image
	 * exists in the incoming graph and is owned by the same Question.
	 *
	 * <p>
	 * Image bytes, multiplicity and composition order remain subject to the
	 * separate ImageReconciler validation stage.
	 */
	private String blockingNewDependency(JsonNode node, Map<String, Map<UUID, Proposal>> matches, Graph incomingGraph,
			ExchangeRecord owner) {
		if (node.isArray()) {
			for (JsonNode child : node) {
				String problem = blockingNewDependency(child, matches, incomingGraph, owner);
				if (problem != null) {
					return problem;
				}
			}
		} else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> field : node.properties()) {
				String type = REFERENCE_TYPES.get(field.getKey());
				JsonNode value = field.getValue();
				if (type != null && !value.isNull()) {
					if (!value.isTextual()) {
						return field.getKey() + " is not a portable UUID";
					}
					UUID id;
					try {
						id = UUID.fromString(value.asText());
						if (!id.toString().equals(value.asText())) {
							return field.getKey() + " is not a canonical UUID";
						}
					} catch (IllegalArgumentException exception) {
						return field.getKey() + " is not a valid UUID";
					}
					if ("QUESTION_IMAGE".equals(type) && "QUESTION".equals(owner.recordType())) {

						// QuestionImage proposals follow Question proposals.
						// Resolve this forward reference against the incoming
						// graph, but never accept an image owned by another
						// Question.
						if (!isOwnedImageReference(incomingGraph, owner.recordId(), id)) {
							return "QUESTION_IMAGE:" + id + " is not owned by Question " + owner.recordId();
						}
					} else {
						Proposal linked = proposal(matches, type, id);
						if (linked == null || linked.classification() == Classification.CONFLICT) {
							return type + ":" + id;
						}
					}
				}
				String nested = blockingNewDependency(value, matches, incomingGraph, owner);
				if (nested != null) {
					return nested;
				}
			}
		}
		return null;
	}

	/** Returns whether image content still needs separate owner validation. */
	private boolean containsUnresolvedImages(ExchangeRecord record) {
		JsonNode values = record.values();
		if (record.recordType().equals("QUESTION")) {
			if (values.path("question_images").isArray() && !values.path("question_images").isEmpty()) {
				return true;
			}
		}
		if (record.recordType().equals("SHARED_QUESTION_CONTEXT")) {
			if (values.path("shared_question_context_images").isArray()
					&& !values.path("shared_question_context_images").isEmpty()) {
				return true;
			}
		}
		return hasImageReference(values);
	}

	/** Finds embedded portable image references recursively. */
	private boolean hasImageReference(JsonNode node) {
		if (node.isArray()) {
			for (JsonNode child : node) {
				if (hasImageReference(child)) {
					return true;
				}
			}
		} else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> field : node.properties()) {
				if (field.getKey().equals("image_id") && !field.getValue().isNull()) {
					return true;
				}
				if (hasImageReference(field.getValue())) {
					return true;
				}
			}
		}
		return false;
	}

	/** Compares all persisted values after resolving independent portable UUIDs. */
	private boolean identicalAfterRebasing(ExchangeRecord master, ExchangeRecord incoming,
			Map<String, Map<UUID, Proposal>> matches) {
		return identicalAfterRebasing(master, incoming, matches, false);
	}

	private boolean identicalAfterRebasing(ExchangeRecord master, ExchangeRecord incoming,
			Map<String, Map<UUID, Proposal>> matches, boolean imageVerified) {
		JsonNode masterValues = master.values();
		JsonNode incomingValues = incoming.values();
		if (imageVerified) {

			// Images have already been independently verified by hash,
			// owner, multiplicity and content order.
			masterValues = withoutImageMaterial(masterValues);
			incomingValues = withoutImageMaterial(incomingValues);
		}
		JsonNode rebased = rebase(incomingValues, matches);
		return masterValues.equals(rebased);
	}

	/**
	 * Verifies that a forward-referenced QuestionImage belongs to the incoming
	 * Question, rather than another capture.
	 */
	private boolean isOwnedImageReference(Graph incomingGraph, UUID questionId, UUID imageId) {
		ExchangeRecord image = incomingGraph.require("QUESTION_IMAGE", imageId);
		return questionId.equals(incomingGraph.reference(image, "question_id"));
	}

	/** Builds the normalised non-empty identity key for a named capture. */
	private String key(ExchangeRecord record, String field) {
		JsonNode value = record.values().path(field);
		if (!value.isTextual() || value.asText().isBlank()) {
			throw new IllegalArgumentException(
					"Missing " + field + " in " + record.recordType() + ":" + record.recordId());
		}
		return value.asText().trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Matches one capture entity type beneath its already reviewed parent.
	 */
	private void matchNamed(String type, String identityField, String parentField, Graph left, Graph right,
			Map<String, Map<UUID, Proposal>> matches, List<Proposal> results, Set<String> verifiedImageOwners) {
		String parentType = REFERENCE_TYPES.get(parentField);
		for (ExchangeRecord incoming : right.records(type)) {
			UUID incomingParentId = right.reference(incoming, parentField);
			right.require(parentType, incomingParentId);
			Proposal parent = proposal(matches, parentType, incomingParentId);
			if (parent == null) {
				throw new IllegalArgumentException(
						"Missing " + parentType + " proposal for " + type + ":" + incoming.recordId());
			}
			List<ExchangeRecord> incomingSiblings = right.children(type, parentField, incomingParentId);
			String identity = identityField == null ? "" : key(incoming, identityField);
			long incomingCount = incomingSiblings.stream()
					.filter(record -> identityField == null || key(record, identityField).equals(identity)).count();
			List<ExchangeRecord> candidates = parent.masterId() == null ? List.of()
					: left.children(type, parentField, parent.masterId()).stream()
							.filter(record -> identityField == null || key(record, identityField).equals(identity))
							.toList();
			ExchangeRecord candidate = incomingCount == 1 && candidates.size() == 1 ? candidates.getFirst() : null;
			Classification classification;
			String reason;
			if (incomingCount != 1 || candidates.size() > 1) {
				classification = Classification.CONFLICT;
				reason = "Ambiguous " + type + " identity within the containing " + parentType + ".";
			} else if (parent.classification() == Classification.CONFLICT) {
				classification = Classification.CONFLICT;
				reason = "Containing " + parentType + " has unresolved metadata or PDF provenance.";
			} else if (parent.masterId() != null && parent.classification() != Classification.IDENTICAL) {
				classification = Classification.CONFLICT;
				reason = "Containing " + parentType + " has not been established as identical.";
			} else if (candidate == null) {
				String dependencyProblem = blockingNewDependency(incoming.values(), matches, right, incoming);
				if (dependencyProblem != null) {
					classification = Classification.CONFLICT;
					reason = "Independent capture has unresolved dependency: " + dependencyProblem;
				} else {
					classification = parent.masterId() == null ? Classification.NEW
							: Classification.COMPATIBLE_ADDITION;
					reason = "Independent " + type + " under the proposed parent; " + "no master record overwritten.";
				}
			} else {
				boolean imageBearing = containsUnresolvedImages(incoming) || containsUnresolvedImages(candidate);
				boolean imagesVerified = verifiedImageOwners.contains(type + ":" + incoming.recordId());
				if (imageBearing && !imagesVerified) {
					classification = Classification.CONFLICT;
					reason = "Image-bearing capture requires verified " + "image ownership, bytes and order.";
				} else {
					try {
						boolean identical = identicalAfterRebasing(candidate, incoming, matches,
								imageBearing && imagesVerified);
						if (identical) {
							classification = Classification.IDENTICAL;
							reason = imageBearing
									? "Capture metadata and rebased dependencies "
											+ "agree; image ownership, bytes and " + "order have also been verified."
									: "Logical identity, content, ordered regions "
											+ "and resolved dependencies agree.";
						} else {
							classification = Classification.CONFLICT;
							reason = "Matching logical identity has changed " + "content, regions or dependencies.";
						}
					} catch (UnresolvedReferenceException exception) {
						classification = Classification.CONFLICT;
						reason = "Unresolved portable dependency: " + exception.getMessage();
					}
				}
			}
			Proposal result = new Proposal(type, incoming.recordId(), candidate == null ? null : candidate.recordId(),
					incomingParentId, parent.masterId(), classification, reason);
			results.add(result);
			store(matches, result);
		}
	}

	/** Finds a preceding proposal by incoming portable identity. */
	private Proposal proposal(Map<String, Map<UUID, Proposal>> matches, String type, UUID incomingId) {
		return matches.getOrDefault(type, Map.of()).get(incomingId);
	}

	/**
	 * Replaces references only when the proposed alias is unambiguously identical.
	 */
	private JsonNode rebase(JsonNode node, Map<String, Map<UUID, Proposal>> matches) {
		if (node.isArray()) {
			ArrayNode result = MAPPER.createArrayNode();
			for (JsonNode child : node) {
				result.add(rebase(child, matches));
			}
			return result;
		}
		if (!node.isObject()) {
			return node.deepCopy();
		}
		ObjectNode result = MAPPER.createObjectNode();
		for (Map.Entry<String, JsonNode> field : node.properties()) {
			JsonNode value = field.getValue();
			String referenceType = REFERENCE_TYPES.get(field.getKey());
			if (referenceType != null && !value.isNull()) {
				if (!value.isTextual()) {
					throw new UnresolvedReferenceException(field.getKey() + " is not a UUID");
				}
				UUID incomingId;
				try {
					incomingId = UUID.fromString(value.asText());
					if (!incomingId.toString().equals(value.asText())) {
						throw new IllegalArgumentException("Noncanonical UUID");
					}
				} catch (IllegalArgumentException exception) {
					throw new UnresolvedReferenceException(field.getKey() + " has an invalid UUID");
				}
				Proposal mapping = proposal(matches, referenceType, incomingId);
				if (mapping == null || mapping.masterId() == null
						|| mapping.classification() != Classification.IDENTICAL) {
					throw new UnresolvedReferenceException(referenceType + ":" + incomingId);
				}
				result.put(field.getKey(), mapping.masterId().toString());
			} else {
				result.set(field.getKey(), rebase(value, matches));
			}
		}
		return result;
	}

	/**
	 * Records a proposal once and rejects duplicate incoming identity decisions.
	 */
	private void store(Map<String, Map<UUID, Proposal>> matches, Proposal proposal) {
		Proposal previous = matches.computeIfAbsent(proposal.recordType(), _ -> new HashMap<>())
				.putIfAbsent(proposal.incomingId(), proposal);
		if (previous != null) {
			throw new IllegalArgumentException(
					"Duplicate reconciliation proposal: " + proposal.recordType() + ":" + proposal.incomingId());
		}
	}

	private JsonNode withoutImageMaterial(JsonNode source) {
		if (source.isArray()) {
			ArrayNode adjusted = MAPPER.createArrayNode();
			for (JsonNode child : source) {
				adjusted.add(withoutImageMaterial(child));
			}
			return adjusted;
		}
		if (!source.isObject()) {
			return source.deepCopy();
		}
		ObjectNode adjusted = MAPPER.createObjectNode();
		for (Map.Entry<String, JsonNode> field : source.properties()) {
			String name = field.getKey();

			// The image stage verifies these independently.
			if (name.equals("question_images") || name.equals("shared_question_context_images")
					|| name.equals("image_id")) {
				continue;
			}
			adjusted.set(name, withoutImageMaterial(field.getValue()));
		}
		return adjusted;
	}

	/** Read-only identity index for a single portable Subject graph. */
	private static final class Graph {

		private final Map<String, Map<UUID, ExchangeRecord>> indexed = new HashMap<>();

		/** Validates and indexes unique portable record identities. */
		private Graph(List<ExchangeRecord> records) {
			Objects.requireNonNull(records, "records");
			for (ExchangeRecord record : records) {
				ExchangeRecord previous = indexed.computeIfAbsent(record.recordType(), _ -> new HashMap<>())
						.putIfAbsent(record.recordId(), record);
				if (previous != null) {
					throw new IllegalArgumentException("Duplicate record identity: " + record.recordId());
				}
			}
		}

		/** Retrieves records with the requested portable parent reference. */
		private List<ExchangeRecord> children(String type, String parentField, UUID parentId) {
			return records(type).stream().filter(record -> parentId.equals(reference(record, parentField))).toList();
		}

		/** Retrieves records of one type in deterministic identity order. */
		private List<ExchangeRecord> records(String type) {
			return indexed.getOrDefault(type, Map.of()).values().stream()
					.sorted(Comparator.comparing(record -> record.recordId().toString())).toList();
		}

		/** Parses a required, canonical portable parent UUID. */
		private UUID reference(ExchangeRecord record, String field) {
			JsonNode value = record.values().path(field);
			if (!value.isTextual()) {
				throw new IllegalArgumentException("Missing " + field + " reference in " + record.recordId());
			}
			try {
				UUID id = UUID.fromString(value.asText());
				if (!id.toString().equals(value.asText())) {
					throw new IllegalArgumentException("Noncanonical UUID");
				}
				return id;
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("Invalid " + field + " reference in " + record.recordId(),
						exception);
			}
		}

		/** Resolves one required portable record or rejects a dangling reference. */
		private ExchangeRecord require(String type, UUID id) {
			ExchangeRecord record = indexed.getOrDefault(type, Map.of()).get(id);
			if (record == null) {
				throw new IllegalArgumentException("Missing " + type + " reference: " + id);
			}
			return record;
		}
	}

	/**
	 * Internal signal that an unapproved or absent dependency blocks equivalence.
	 */
	private static final class UnresolvedReferenceException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/** Describes the blocking dependency. */
		private UnresolvedReferenceException(String message) {
			super(message);
		}
	}
}
