package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Previews baseline-free matching of independently created Subject and syllabus
 * records without altering either dataset.
 *
 * <p>
 * Matching uses Subject names, syllabus names and the full hierarchical paths
 * and metadata of curriculum nodes. Portable UUIDs are used to identify records
 * in a proposed alias, never as proof that independently created records match.
 * Existing master syllabus differences are conflicts, not additions that can
 * silently extend the authoritative curriculum.
 *
 * <p>
 * This is the curriculum stage of Subject reconciliation. It does not decide
 * matches for Exams, Questions, PDFs or other asset-dependent entities; those
 * require separate evidence before a complete import preview is produced.
 */
public final class EqworkCurriculumReconciler {

	/**
	 * Creates a stateless, read-only reconciler for independently created Subject
	 * and curriculum datasets.
	 *
	 * <p>
	 * Reconciliation produces proposed identity mappings and classifications
	 * without modifying either dataset or accepting any proposed aliases.
	 */
	public EqworkCurriculumReconciler() {

		// No mutable state is required.
	}

	/**
	 * Compares the curriculum subset of two independently created Subject graphs.
	 *
	 * <p>
	 * Inputs are portable records from validated, Subject-scoped snapshots. The
	 * coordinator input may contain no Subject when importing a new Subject, but
	 * must not contain a different Subject's complete collection as well. Other
	 * portable record types are intentionally left for subsequent reconciliation
	 * stages. No proposed alias is accepted automatically.
	 *
	 * @param master   coordinator's current Subject records, or an empty collection
	 * @param incoming contributor's Subject records
	 * @return inspectable proposals for the Subject and entire syllabus hierarchy
	 * @throws IllegalArgumentException if the scope, identity or parent graph is
	 *                                  invalid
	 */
	public Preview preview(List<EqworkRecord> master, List<EqworkRecord> incoming) {
		Objects.requireNonNull(master, "master");
		Objects.requireNonNull(incoming, "incoming");
		List<EqworkRecord> masterSubjects = ofType(master, "SUBJECT");
		List<EqworkRecord> incomingSubjects = ofType(incoming, "SUBJECT");
		if (masterSubjects.size() > 1 || incomingSubjects.size() != 1) {
			throw new IllegalArgumentException("Preview expects one incoming Subject and at most one master Subject");
		}
		requireUniqueIds(master);
		requireUniqueIds(incoming);
		EqworkRecord incomingSubject = incomingSubjects.getFirst();
		EqworkRecord candidateSubject = masterSubjects.isEmpty() ? null : masterSubjects.getFirst();
		boolean sameSubject = candidateSubject != null
				&& key(candidateSubject, "subject_name").equals(key(incomingSubject, "subject_name"));
		EqworkRecord masterSubject = sameSubject ? candidateSubject : null;
		List<Proposal> proposals = new ArrayList<>();
		proposals.add(new Proposal("SUBJECT", incomingSubject.recordId(),
				masterSubject == null ? null : masterSubject.recordId(), null, null,
				masterSubject == null ? Classification.NEW
						: sameValues(masterSubject, incomingSubject) ? Classification.IDENTICAL
								: Classification.CONFLICT,
				masterSubject == null ? "No master Subject has the same normalised name."
						: sameValues(masterSubject, incomingSubject) ? "Subject name and metadata agree."
								: "Subject names agree but stored metadata differs."));
		List<EqworkRecord> incomingVersions = versions(incoming, incomingSubject.recordId());
		List<EqworkRecord> masterVersions = masterSubject == null ? List.of()
				: versions(master, masterSubject.recordId());
		requireNodeVersionClosure(incoming, incomingVersions);
		if (masterSubject != null) {
			requireNodeVersionClosure(master, masterVersions);
		}
		Map<String, List<EqworkRecord>> masterByName = groupByKey(masterVersions, "syllabus_name");
		Map<String, List<EqworkRecord>> incomingByName = groupByKey(incomingVersions, "syllabus_name");
		incomingVersions.stream().sorted(Comparator.comparing((EqworkRecord version) -> key(version, "syllabus_name"))
				.thenComparing(version -> version.recordId().toString())).forEach(version -> {
					String versionName = key(version, "syllabus_name");
					List<EqworkRecord> candidates = masterByName.getOrDefault(versionName, List.of());
					boolean ambiguous = candidates.size() > 1 || incomingByName.get(versionName).size() > 1;
					EqworkRecord matching = ambiguous || candidates.isEmpty() ? null : candidates.getFirst();
					NodeGraph incomingGraph = graph(incoming, version);
					NodeGraph masterGraph = matching == null ? null : graph(master, matching);
					Classification classification;
					String reason;
					if (ambiguous) {
						classification = Classification.CONFLICT;
						reason = "Duplicate syllabus names make this match ambiguous.";
					} else if (matching == null) {
						classification = masterSubject == null ? Classification.NEW
								: Classification.COMPATIBLE_ADDITION;
						reason = masterSubject == null ? "New Subject and syllabus; no master equivalent."
								: "New syllabus version proposed under an existing Subject; coordinator approval required.";
					} else {
						reason = difference(matching, version, masterGraph, incomingGraph);
						classification = reason == null ? Classification.IDENTICAL : Classification.CONFLICT;
						if (reason == null) {
							reason = "Syllabus metadata and complete curriculum hierarchy agree.";
						}
					}
					proposals.add(new Proposal("SYLLABUS_VERSION", version.recordId(),
							matching == null ? null : matching.recordId(), incomingSubject.recordId(),
							masterSubject == null ? null : masterSubject.recordId(), classification, reason));
					proposeNodes(proposals, version, matching, incomingGraph, masterGraph, classification);
				});
		return new Preview(proposals);
	}

	private String difference(EqworkRecord master, EqworkRecord incoming, NodeGraph masterGraph,
			NodeGraph incomingGraph) {
		if (!sameValues(master, incoming, "subject_id")) {
			return "Same syllabus name, but syllabus metadata differs.";
		}
		if (!masterGraph.byPath().keySet().equals(incomingGraph.byPath().keySet())) {
			return "Curriculum paths differ: master-only "
					+ difference(masterGraph.byPath().keySet(), incomingGraph.byPath().keySet()) + "; incoming-only "
					+ difference(incomingGraph.byPath().keySet(), masterGraph.byPath().keySet());
		}
		for (String path : masterGraph.byPath().keySet().stream().sorted().toList()) {
			List<EqworkRecord> masterNodes = masterGraph.byPath().get(path);
			List<EqworkRecord> incomingNodes = incomingGraph.byPath().get(path);
			if (masterNodes.size() != 1 || incomingNodes.size() != 1) {
				return "Duplicate curriculum path requires review: " + path;
			}
			if (!sameValues(masterNodes.getFirst(), incomingNodes.getFirst(), "syllabus_version_id", "parent_id")) {
				return "Curriculum node metadata differs at " + path;
			}
		}
		return null;
	}

	private String difference(Set<String> first, Set<String> second) {
		return first.stream().filter(path -> !second.contains(path)).sorted().limit(3).toList().toString();
	}

	private NodeGraph graph(List<EqworkRecord> records, EqworkRecord version) {
		List<EqworkRecord> nodes = records.stream().filter(record -> record.recordType().equals("CURRICULUM_NODE"))
				.filter(record -> version.recordId().equals(reference(record, "syllabus_version_id"))).toList();
		Map<UUID, EqworkRecord> byId = new HashMap<>();
		for (EqworkRecord node : nodes) {
			if (byId.putIfAbsent(node.recordId(), node) != null) {
				throw new IllegalArgumentException("Duplicate curriculum node ID: " + node.recordId());
			}
		}
		Map<UUID, String> paths = new HashMap<>();
		Map<String, List<EqworkRecord>> byPath = new LinkedHashMap<>();
		for (EqworkRecord node : nodes) {
			String path = path(node, byId, paths, new HashSet<>());
			byPath.computeIfAbsent(path, _ -> new ArrayList<>()).add(node);
		}
		return new NodeGraph(Map.copyOf(paths), Map.copyOf(byPath));
	}

	private Map<String, List<EqworkRecord>> groupByKey(List<EqworkRecord> records, String field) {
		return records.stream().collect(Collectors.groupingBy(record -> key(record, field)));
	}

	private String key(EqworkRecord record, String field) {
		String value = record.values().path(field).asText("").trim().toLowerCase(Locale.ROOT);
		if (value.isBlank()) {
			throw new IllegalArgumentException(
					"Blank " + field + " in " + record.recordType() + ":" + record.recordId());
		}
		return value;
	}

	private List<EqworkRecord> ofType(List<EqworkRecord> records, String type) {
		return records.stream().filter(record -> record.recordType().equals(type)).toList();
	}

	private String path(EqworkRecord node, Map<UUID, EqworkRecord> nodes, Map<UUID, String> cached,
			Set<UUID> visiting) {
		String found = cached.get(node.recordId());
		if (found != null) {
			return found;
		}
		if (!visiting.add(node.recordId())) {
			throw new IllegalArgumentException("Cycle in curriculum hierarchy at " + node.recordId());
		}
		JsonNode values = node.values();
		String code = values.path("curriculum_code").asText("").trim().toLowerCase(Locale.ROOT);
		String segment = !code.isBlank() ? "code:" + code
				: "name:" + key(node, "curriculum_level") + ":" + key(node, "curriculum_name");
		UUID parentId = reference(node, "parent_id");
		String fullPath;
		if (parentId == null) {
			fullPath = segment.length() + ":" + segment;
		} else {
			EqworkRecord parent = nodes.get(parentId);
			if (parent == null) {
				throw new IllegalArgumentException("Curriculum parent is outside the syllabus: " + parentId);
			}
			fullPath = path(parent, nodes, cached, visiting) + "/" + segment.length() + ":" + segment;
		}
		visiting.remove(node.recordId());
		cached.put(node.recordId(), fullPath);
		return fullPath;
	}

	private void proposeNodes(List<Proposal> proposals, EqworkRecord incomingVersion, EqworkRecord masterVersion,
			NodeGraph incomingGraph, NodeGraph masterGraph, Classification versionClassification) {
		List<Map.Entry<UUID, String>> entries = incomingGraph.paths().entrySet().stream()
				.sorted(Map.Entry.<UUID, String>comparingByValue().thenComparing(entry -> entry.getKey().toString()))
				.toList();
		Map<UUID, EqworkRecord> incomingById = incomingGraph.byPath().values().stream().flatMap(List::stream)
				.collect(Collectors.toMap(EqworkRecord::recordId, Function.identity()));
		for (Map.Entry<UUID, String> entry : entries) {
			EqworkRecord node = incomingById.get(entry.getKey());
			String path = entry.getValue();
			List<EqworkRecord> incomingAtPath = incomingGraph.byPath().get(path);
			List<EqworkRecord> masterAtPath = masterGraph == null ? List.of()
					: masterGraph.byPath().getOrDefault(path, List.of());
			boolean unique = incomingAtPath.size() == 1 && masterAtPath.size() <= 1;
			EqworkRecord matching = unique && masterAtPath.size() == 1 ? masterAtPath.getFirst() : null;
			UUID incomingParent = reference(node, "parent_id");
			UUID masterParent = null;
			if (masterVersion != null) {
				if (incomingParent == null) {
					masterParent = masterVersion.recordId();
				} else {
					String parentPath = incomingGraph.paths().get(incomingParent);
					List<EqworkRecord> parentMatches = masterGraph == null ? List.of()
							: masterGraph.byPath().getOrDefault(parentPath, List.of());
					if (parentMatches.size() == 1) {
						masterParent = parentMatches.getFirst().recordId();
					}
				}
			}
			Classification classification;
			String reason;
			if (!unique) {
				classification = Classification.CONFLICT;
				reason = "Duplicate or ambiguous curriculum path: " + path;
			} else if (versionClassification == Classification.CONFLICT && masterVersion == null) {
				classification = Classification.CONFLICT;
				reason = "Cannot match node beneath ambiguous syllabus: " + path;
			} else if (masterVersion == null) {
				classification = versionClassification;
				reason = "Curriculum node belongs to the proposed new syllabus: " + path;
			} else if (matching == null) {
				classification = Classification.CONFLICT;
				reason = "Unmatched node in an established master syllabus: " + path;
			} else if (sameValues(matching, node, "syllabus_version_id", "parent_id")) {
				classification = Classification.IDENTICAL;
				reason = "Curriculum hierarchy and node metadata agree at " + path;
			} else {
				classification = Classification.CONFLICT;
				reason = "Curriculum node metadata differs at " + path;
			}
			proposals
					.add(new Proposal("CURRICULUM_NODE", node.recordId(), matching == null ? null : matching.recordId(),
							incomingParent == null ? incomingVersion.recordId() : incomingParent, masterParent,
							classification, reason));
		}
	}

	private UUID reference(EqworkRecord record, String field) {
		JsonNode value = record.values().get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException("Invalid portable reference " + field + " in " + record.recordId());
		}
		try {
			return UUID.fromString(value.asText());
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid portable UUID in " + field + " for " + record.recordId(),
					exception);
		}
	}

	private void requireNodeVersionClosure(List<EqworkRecord> records, List<EqworkRecord> versions) {
		Set<UUID> versionIds = versions.stream().map(EqworkRecord::recordId).collect(Collectors.toSet());
		for (EqworkRecord node : ofType(records, "CURRICULUM_NODE")) {
			if (!versionIds.contains(reference(node, "syllabus_version_id"))) {
				throw new IllegalArgumentException(
						"Curriculum node has no syllabus in the selected Subject: " + node.recordId());
			}
		}
	}

	private void requireUniqueIds(List<EqworkRecord> records) {
		Set<String> ids = new HashSet<>();
		for (EqworkRecord record : records) {
			if (!ids.add(record.recordType() + ":" + record.recordId())) {
				throw new IllegalArgumentException(
						"Duplicate portable record identity: " + record.recordType() + ":" + record.recordId());
			}
		}
	}

	private boolean sameValues(EqworkRecord first, EqworkRecord second, String... excludedFields) {
		ObjectNode firstValues = (ObjectNode) first.values();
		ObjectNode secondValues = (ObjectNode) second.values();
		for (String excluded : excludedFields) {
			firstValues.remove(excluded);
			secondValues.remove(excluded);
		}
		return firstValues.equals(secondValues);
	}

	private List<EqworkRecord> versions(List<EqworkRecord> records, UUID subjectId) {
		List<EqworkRecord> versions = ofType(records, "SYLLABUS_VERSION");
		for (EqworkRecord version : versions) {
			if (!subjectId.equals(reference(version, "subject_id"))) {
				throw new IllegalArgumentException("Syllabus lies outside selected Subject: " + version.recordId());
			}
		}
		return versions;
	}

	/** Classification of one incoming portable record against the master. */
	public enum Classification {
		/** No equivalent master entity is known. */
		NEW,
		/** The proposed equivalent has the same relevant content and structure. */
		IDENTICAL,
		/** An independent, non-destructive addition to a matching Subject. */
		COMPATIBLE_ADDITION,
		/** A conflicting or ambiguous match requiring coordinator inspection. */
		CONFLICT
	}

	/**
	 * One inspectable, unapproved matching proposal.
	 *
	 * @param recordType       portable entity type
	 * @param incomingId       incoming portable UUID
	 * @param masterId         proposed master UUID, or null when
	 *                         unmatched/ambiguous
	 * @param incomingParentId incoming containing Subject, syllabus or parent node
	 * @param masterParentId   proposed master parent UUID, if known
	 * @param classification   comparison result
	 * @param reason           human-readable supporting evidence or conflict
	 *                         explanation
	 */
	public record Proposal(String recordType, UUID incomingId, UUID masterId, UUID incomingParentId,
			UUID masterParentId, Classification classification, String reason) {

		/** Validates an immutable reconciliation proposal. */
		public Proposal {
			Objects.requireNonNull(recordType, "recordType");
			Objects.requireNonNull(incomingId, "incomingId");
			Objects.requireNonNull(classification, "classification");
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Proposal reason must not be blank");
			}
			if (classification == Classification.NEW && masterId != null) {
				throw new IllegalArgumentException("A NEW record cannot already match a master record");
			}
		}

		/**
		 * Indicates that a different portable UUID could be linked after review.
		 *
		 * @return true when there is a proposed cross-installation alias
		 */
		public boolean proposesAlias() {
			return masterId != null && !incomingId.equals(masterId);
		}
	}

	/**
	 * An immutable read-only curriculum reconciliation preview.
	 *
	 * @param proposals one proposal per incoming Subject, syllabus and curriculum
	 *                  node
	 */
	public record Preview(List<Proposal> proposals) {

		/** Copies the proposals so callers cannot change the preview. */
		public Preview {
			proposals = List.copyOf(proposals);
		}

		/**
		 * Returns proposals for a portable record type.
		 *
		 * @param recordType the entity type name
		 * @return matching proposals in their original order
		 */
		public List<Proposal> forType(String recordType) {
			Objects.requireNonNull(recordType, "recordType");
			return proposals.stream().filter(proposal -> proposal.recordType().equals(recordType)).toList();
		}
	}

	private record NodeGraph(Map<UUID, String> paths, Map<String, List<EqworkRecord>> byPath) {
	}
}
