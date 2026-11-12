package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;

/**
 * Produces unapproved, baseline-free proposals for independently created Exams,
 * Booklets, AnswerFiles and their shared SourceDocuments.
 *
 * <p>
 * Exam keys use Subject, provider, year and name. Booklets prefer the explicit
 * legacy Paper code over the editable name. Verified package asset-index hashes
 * provide PDF provenance, but identical bytes alone never establish entity
 * identity. Ambiguous matches and changed provenance remain conflicts.
 *
 * <p>
 * Both inputs must have been obtained from validated Subject snapshots. This
 * class does not validate ZIP bytes, read SQLite, write files, accept aliases
 * or alter either source graph. The supplied curriculum preview is also
 * unapproved: its proposed Subject mapping is evidence, not permission to
 * import.
 */
public final class AssessmentReconciler {

	/**
	 * Creates a stateless read-only assessment reconciliation service.
	 */
	public AssessmentReconciler() {
	}

	/**
	 * Compares portable assessment records from two independently created Subjects.
	 *
	 * @param master         coordinator's validated portable records
	 * @param masterAssets   coordinator's validated binary-reference index
	 * @param incoming       contributor's validated portable records
	 * @param incomingAssets contributor's validated binary-reference index
	 * @param curriculum     preceding Subject/curriculum reconciliation preview
	 * @return additional unapproved proposals, ordered by record type and UUID
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if identities or source references are
	 *                                  malformed
	 */
	public Preview preview(List<ExchangeRecord> master, ExamWorkAssetIndex masterAssets, List<ExchangeRecord> incoming,
			ExamWorkAssetIndex incomingAssets, Preview curriculum) {
		Objects.requireNonNull(curriculum, "curriculum");
		Graph left = new Graph(master, masterAssets);
		Graph right = new Graph(incoming, incomingAssets);
		Proposal subject = curriculum.forType("SUBJECT").stream().findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Missing Subject proposal"));
		if (!subject.incomingId().equals(right.subjectId())) {
			throw new IllegalArgumentException("Curriculum preview belongs to a different incoming Subject");
		}
		if (subject.masterId() != null && !subject.masterId().equals(left.subjectId())) {
			throw new IllegalArgumentException("Curriculum preview belongs to a different master Subject");
		}
		List<Proposal> results = new ArrayList<>();
		Map<UUID, Proposal> providerMatches = matchProviders(left, right, results);
		Map<UUID, Proposal> examMatches = matchExams(left, right, subject, providerMatches, results);
		matchOwned("EXAM_BOOKLET", left, right, examMatches, results);
		matchOwned("ANSWER_FILE", left, right, examMatches, results);
		reconcileBookletAnswerFileLinks(left, right, results);
		matchSourceDocuments(left, right, results);
		results.sort(Comparator.comparing(Proposal::recordType).thenComparing(p -> p.incomingId().toString()));
		return new Preview(results);
	}

	private String bookletKey(ExchangeRecord record) {
		String legacy = optional(record, "legacy_paper_code");
		return legacy == null ? "name:" + required(record, "booklet_name") : "legacy:" + legacy;
	}

	private boolean equivalent(ExchangeRecord master, ExchangeRecord incoming, String... ignored) {
		ObjectNode a = (ObjectNode) master.values();
		ObjectNode b = (ObjectNode) incoming.values();
		for (String field : ignored) {
			a.remove(field);
			b.remove(field);
		}
		return a.equals(b);
	}

	private String examKey(Graph graph, ExchangeRecord exam) {
		UUID provider = graph.reference(exam, "provider_id");
		ExchangeRecord providerRecord = graph.require("EXAM_PROVIDER", provider);
		JsonNode year = exam.values().path("exam_year");
		if (!year.isIntegralNumber() || year.asInt() < 1) {
			throw new IllegalArgumentException("Invalid exam year in " + exam.recordId());
		}
		return required(providerRecord, "provider_name") + "\u0000" + year.asInt() + "\u0000"
				+ required(exam, "exam_name");
	}

	private Map<UUID, Proposal> matchExams(Graph left, Graph right, Proposal subject, Map<UUID, Proposal> providers,
			List<Proposal> results) {
		Map<String, List<ExchangeRecord>> masterByKey = left.records("EXAM").stream()
				.collect(Collectors.groupingBy(record -> examKey(left, record)));
		Map<String, Long> incomingCounts = right.records("EXAM").stream()
				.collect(Collectors.groupingBy(record -> examKey(right, record), Collectors.counting()));
		Map<UUID, Proposal> matched = new HashMap<>();
		for (ExchangeRecord incoming : right.records("EXAM")) {
			String key = examKey(right, incoming);
			List<ExchangeRecord> candidates = subject.masterId() == null ? List.of()
					: masterByKey.getOrDefault(key, List.of());
			UUID incomingProvider = right.reference(incoming, "provider_id");
			Proposal provider = providers.get(incomingProvider);
			ExchangeRecord candidate = candidates.size() == 1 && incomingCounts.get(key) == 1 ? candidates.get(0)
					: null;
			Classification classification;
			String reason;
			if (candidates.size() > 1 || incomingCounts.get(key) > 1) {
				classification = Classification.CONFLICT;
				reason = "Ambiguous Exam: duplicate normalised provider/year/name key.";
			} else if (candidate == null) {
				classification = subject.masterId() == null ? Classification.NEW : Classification.COMPATIBLE_ADDITION;
				reason = "No matching Exam for Subject/provider/year/name; proposed independent Exam.";
			} else if (subject.classification() == Classification.CONFLICT) {
				classification = Classification.CONFLICT;
				reason = "Containing Subject has unresolved metadata conflicts.";
			} else if (provider == null || provider.masterId() == null
					|| !provider.masterId().equals(left.reference(candidate, "provider_id"))) {
				classification = Classification.CONFLICT;
				reason = "Exam provider cannot be mapped unambiguously to the master.";
			} else if (!equivalent(candidate, incoming, "subject_id", "provider_id")) {
				classification = Classification.CONFLICT;
				reason = "Matching Exam identity but differing Exam metadata; coordinator review required.";
			} else {
				classification = Classification.IDENTICAL;
				reason = "Subject, provider, year, name and Exam metadata agree.";
			}
			Proposal proposal = new Proposal("EXAM", incoming.recordId(),
					candidate == null ? null : candidate.recordId(), right.reference(incoming, "subject_id"),
					candidate == null ? subject.masterId() : left.reference(candidate, "subject_id"), classification,
					reason);
			results.add(proposal);
			matched.put(incoming.recordId(), proposal);
		}
		return matched;
	}

	/**
	 * Matches Booklets and AnswerFiles within their reconciled Exams.
	 *
	 * <p>
	 * Logical identity and verified PDF provenance must agree before records can be
	 * classified as identical. Matching PDF bytes alone do not establish record
	 * identity.
	 *
	 * @param type    portable entity type
	 * @param left    master assessment graph
	 * @param right   incoming assessment graph
	 * @param exams   proposed Exam identity mappings
	 * @param results destination for reconciliation proposals
	 */
	private void matchOwned(String type, Graph left, Graph right, Map<UUID, Proposal> exams, List<Proposal> results) {
		for (ExchangeRecord incoming : right.records(type)) {
			UUID incomingExam = right.reference(incoming, "exam_id");
			Proposal parent = exams.get(incomingExam);
			if (parent == null) {
				throw new IllegalArgumentException(type + " references an absent incoming Exam");
			}
			String incomingKey = ownedKey(type, incoming);
			List<ExchangeRecord> siblings = right.owned(type, incomingExam);
			boolean duplicateIncoming = siblings.stream().filter(record -> ownedKey(type, record).equals(incomingKey))
					.count() != 1;
			List<ExchangeRecord> candidates = parent.masterId() == null ? List.of()
					: left.owned(type, parent.masterId()).stream()
							.filter(record -> ownedKey(type, record).equals(incomingKey)).toList();
			boolean weakLegacyMatch = false;
			if (type.equals("EXAM_BOOKLET") && parent.masterId() != null && candidates.isEmpty()) {

				// A renamed legacy Paper must not silently become a new
				// Booklet with a colliding display name.
				candidates = left.owned(type, parent.masterId()).stream()
						.filter(record -> required(record, "booklet_name").equals(required(incoming, "booklet_name")))
						.toList();
				weakLegacyMatch = !candidates.isEmpty();
			}
			ExchangeRecord candidate = !duplicateIncoming && candidates.size() == 1 ? candidates.get(0) : null;
			Classification classification;
			String reason;
			if (duplicateIncoming || candidates.size() > 1) {
				classification = Classification.CONFLICT;
				reason = "Ambiguous " + type + " logical identity within its Exam.";
			} else if (parent.classification() == Classification.CONFLICT) {
				classification = Classification.CONFLICT;
				reason = "Containing Exam has unresolved identity or metadata conflicts.";
			} else if (candidate == null && right.sourceHash(incoming) == null) {
				classification = Classification.CONFLICT;
				reason = "New " + type + " lacks verified source PDF provenance.";
			} else if (candidate == null) {
				classification = parent.masterId() == null ? Classification.NEW : Classification.COMPATIBLE_ADDITION;
				reason = "Distinct " + type + " identity; no existing record will be overwritten.";
			} else {
				String masterHash = left.sourceHash(candidate);
				String incomingHash = right.sourceHash(incoming);
				boolean legacyChanged = weakLegacyMatch || type.equals("EXAM_BOOKLET") && !Objects
						.equals(optional(candidate, "legacy_paper_code"), optional(incoming, "legacy_paper_code"));
				if (legacyChanged) {
					classification = Classification.CONFLICT;
					reason = "Legacy Paper identity differs despite matching Booklet name.";
				} else if (masterHash == null || incomingHash == null) {
					classification = Classification.CONFLICT;
					reason = "Missing verified source PDF SHA-256; provenance cannot be established.";
				} else if (!masterHash.equals(incomingHash)) {
					classification = Classification.CONFLICT;
					reason = "Matching logical identity references different source PDF bytes.";
				} else if (!equivalent(candidate, incoming, "exam_id", "source_document_id", "answer_file_id")) {
					classification = Classification.CONFLICT;
					reason = "Matching logical identity and source PDF, but differing " + type
							+ " metadata; coordinator review required.";
				} else {
					classification = Classification.IDENTICAL;
					reason = "Logical identity, metadata and verified source PDF SHA-256 agree.";
				}
			}
			results.add(new Proposal(type, incoming.recordId(), candidate == null ? null : candidate.recordId(),
					incomingExam, parent.masterId(), classification, reason));
		}
	}

	private Map<UUID, Proposal> matchProviders(Graph left, Graph right, List<Proposal> results) {
		Map<String, List<ExchangeRecord>> masterByName = left.records("EXAM_PROVIDER").stream()
				.collect(Collectors.groupingBy(record -> required(record, "provider_name")));
		Map<String, Long> incomingCounts = right.records("EXAM_PROVIDER").stream()
				.collect(Collectors.groupingBy(record -> required(record, "provider_name"), Collectors.counting()));
		Map<UUID, Proposal> matched = new HashMap<>();
		for (ExchangeRecord record : right.records("EXAM_PROVIDER")) {
			String name = required(record, "provider_name");
			List<ExchangeRecord> candidates = masterByName.getOrDefault(name, List.of());
			ExchangeRecord candidate = candidates.size() == 1 && incomingCounts.get(name) == 1 ? candidates.get(0)
					: null;
			Classification status;
			String reason;
			if (candidates.size() > 1 || incomingCounts.get(name) > 1) {
				status = Classification.CONFLICT;
				reason = "Ambiguous provider name across independent datasets.";
			} else if (candidate == null) {
				status = Classification.NEW;
				reason = "No provider with this name in the master.";
			} else if (!equivalent(candidate, record)) {
				status = Classification.CONFLICT;
				reason = "Provider name matches but stored metadata differs.";
			} else {
				status = Classification.IDENTICAL;
				reason = "Provider name and metadata agree.";
			}
			Proposal proposal = new Proposal("EXAM_PROVIDER", record.recordId(),
					candidate == null ? null : candidate.recordId(), null, null, status, reason);
			results.add(proposal);
			matched.put(record.recordId(), proposal);
		}
		return matched;
	}

	private void matchSourceDocuments(Graph left, Graph right, List<Proposal> results) {
		Map<UUID, Set<UUID>> candidates = new HashMap<>();
		for (Proposal proposal : results) {
			if ((!proposal.recordType().equals("EXAM_BOOKLET") && !proposal.recordType().equals("ANSWER_FILE"))
					|| proposal.masterId() == null) {
				continue;
			}
			ExchangeRecord from = right.require(proposal.recordType(), proposal.incomingId());
			ExchangeRecord to = left.require(proposal.recordType(), proposal.masterId());
			UUID incomingSource = right.reference(from, "source_document_id");
			UUID masterSource = left.reference(to, "source_document_id");
			candidates.computeIfAbsent(incomingSource, _ -> new HashSet<>()).add(masterSource);
		}
		Map<UUID, Long> claimedMaster = candidates.values().stream().filter(ids -> ids.size() == 1)
				.map(ids -> ids.iterator().next())
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		for (ExchangeRecord document : right.records("SOURCE_DOCUMENT")) {
			Set<UUID> possible = candidates.getOrDefault(document.recordId(), Set.of());
			UUID targetId = possible.size() == 1 ? possible.iterator().next() : null;
			ExchangeRecord target = targetId == null ? null : left.require("SOURCE_DOCUMENT", targetId);
			Classification classification;
			String reason;
			if (possible.size() > 1 || targetId != null && claimedMaster.get(targetId) > 1) {
				classification = Classification.CONFLICT;
				targetId = null;
				reason = "One source document maps ambiguously through multiple Booklet/AnswerFile owners.";
			} else if (right.documentHash(document.recordId()) == null) {
				classification = Classification.CONFLICT;
				reason = "Incoming SourceDocument lacks verified PDF provenance.";
			} else if (target == null) {
				classification = Classification.NEW;
				reason = "No uniquely matched logical owner; identical PDF bytes alone cannot establish an alias.";
			} else if (left.documentHash(target.recordId()) == null
					|| right.documentHash(document.recordId()) == null) {
				classification = Classification.CONFLICT;
				reason = "SourceDocument lacks verified PDF provenance.";
			} else if (!left.documentHash(target.recordId()).equals(right.documentHash(document.recordId()))) {
				classification = Classification.CONFLICT;
				reason = "Matched logical owners refer to different PDF bytes.";
			} else if (!equivalent(target, document, "content_sha256")) {
				classification = Classification.CONFLICT;
				reason = "PDF bytes match, but SourceDocument metadata differs.";
			} else {
				classification = Classification.IDENTICAL;
				reason = "Logical owner relationships and verified PDF content agree.";
			}
			results.add(
					new Proposal("SOURCE_DOCUMENT", document.recordId(), targetId, null, null, classification, reason));
		}
	}

	private String optional(ExchangeRecord record, String field) {
		JsonNode value = record.values().path(field);
		return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null
				: value.asText().trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Reads an optional canonical portable UUID, retaining the distinction from
	 * null.
	 */
	private UUID optionalUuid(ExchangeRecord record, String field) {
		JsonNode value = record.values().path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException("Invalid " + field + " in " + record.recordId());
		}
		try {
			UUID parsed = UUID.fromString(value.asText());
			if (!parsed.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return parsed;
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid " + field + " in " + record.recordId(), exception);
		}
	}

	private String ownedKey(String type, ExchangeRecord record) {
		return type.equals("EXAM_BOOKLET") ? bookletKey(record) : "name:" + required(record, "answer_file_name");
	}

	/**
	 * Verifies independently created Booklets preserve their shared AnswerFile
	 * links.
	 */
	private void reconcileBookletAnswerFileLinks(Graph left, Graph right, List<Proposal> results) {
		Map<UUID, Proposal> answerFileAliases = new HashMap<>();
		for (Proposal proposal : results) {
			if (proposal.recordType().equals("ANSWER_FILE")) {
				answerFileAliases.put(proposal.incomingId(), proposal);
			}
		}
		for (int index = 0; index < results.size(); index++) {
			Proposal proposal = results.get(index);
			if (!proposal.recordType().equals("EXAM_BOOKLET") || proposal.masterId() == null
					|| proposal.classification() != Classification.IDENTICAL) {
				continue;
			}
			ExchangeRecord incoming = right.require("EXAM_BOOKLET", proposal.incomingId());
			ExchangeRecord master = left.require("EXAM_BOOKLET", proposal.masterId());
			UUID incomingAnswerFile = optionalUuid(incoming, "answer_file_id");
			UUID masterAnswerFile = optionalUuid(master, "answer_file_id");
			if (incomingAnswerFile == null && masterAnswerFile == null) {
				continue;
			}
			Proposal answerFile = incomingAnswerFile == null ? null : answerFileAliases.get(incomingAnswerFile);
			if (answerFile == null || answerFile.classification() != Classification.IDENTICAL
					|| !Objects.equals(answerFile.masterId(), masterAnswerFile)) {
				results.set(index,
						new Proposal("EXAM_BOOKLET", proposal.incomingId(), proposal.masterId(),
								proposal.incomingParentId(), proposal.masterParentId(), Classification.CONFLICT,
								"The Booklet's linked shared AnswerFile is different or unresolved; "
										+ "matching PDF bytes do not justify an alias."));
			}
		}
	}

	private String required(ExchangeRecord record, String field) {
		String value = optional(record, field);
		if (value == null) {
			throw new IllegalArgumentException(
					"Missing " + field + " in " + record.recordType() + ":" + record.recordId());
		}
		return value;
	}

	/** Immutable, validated in-memory view of one portable Subject graph. */
	private static final class Graph {

		private final Map<String, Map<UUID, ExchangeRecord>> records = new HashMap<>();
		private final Map<UUID, String> sourceHashes = new HashMap<>();
		private final UUID subjectId;

		private Graph(List<ExchangeRecord> source, ExamWorkAssetIndex assets) {
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(assets, "assets");
			for (ExchangeRecord record : source) {
				ExchangeRecord previous = records.computeIfAbsent(record.recordType(), _ -> new HashMap<>())
						.putIfAbsent(record.recordId(), record);
				if (previous != null) {
					throw new IllegalArgumentException("Duplicate record identity: " + record.recordId());
				}
			}
			List<ExchangeRecord> subjects = records("SUBJECT");
			if (subjects.size() > 1) {
				throw new IllegalArgumentException("Expected at most one Subject per snapshot");
			}
			subjectId = subjects.isEmpty() ? null : subjects.get(0).recordId();
			for (ExamWorkAssetIndex.Reference reference : assets.references()) {
				if ("SOURCE_DOCUMENT".equals(reference.ownerType()) && "SOURCE_PDF".equals(reference.usage())) {
					require("SOURCE_DOCUMENT", reference.ownerId());
					String previous = sourceHashes.putIfAbsent(reference.ownerId(), reference.sha256());
					if (previous != null && !previous.equals(reference.sha256())) {
						throw new IllegalArgumentException("Conflicting source PDF hashes: " + reference.ownerId());
					}
				}
			}
			for (ExchangeRecord document : records("SOURCE_DOCUMENT")) {
				JsonNode storedHash = document.values().path("content_sha256");
				String verifiedHash = documentHash(document.recordId());
				if (!storedHash.isMissingNode() && !storedHash.isNull()
						&& (!storedHash.isTextual() || !storedHash.asText().equals(verifiedHash))) {
					throw new IllegalArgumentException(
							"Stored source SHA-256 differs from verified asset index: " + document.recordId());
				}
			}
			for (ExchangeRecord exam : records("EXAM")) {
				if (!Objects.equals(reference(exam, "subject_id"), subjectId)) {
					throw new IllegalArgumentException("Exam outside selected Subject: " + exam.recordId());
				}
				require("EXAM_PROVIDER", reference(exam, "provider_id"));
			}
			for (String type : List.of("EXAM_BOOKLET", "ANSWER_FILE")) {
				for (ExchangeRecord owner : records(type)) {
					require("EXAM", reference(owner, "exam_id"));
					require("SOURCE_DOCUMENT", reference(owner, "source_document_id"));
				}
			}
		}

		private String documentHash(UUID id) {
			return sourceHashes.get(id);
		}

		private List<ExchangeRecord> owned(String type, UUID examId) {
			return records(type).stream().filter(record -> examId.equals(reference(record, "exam_id"))).toList();
		}

		private List<ExchangeRecord> records(String type) {
			return records.getOrDefault(type, Map.of()).values().stream()
					.sorted(Comparator.comparing(record -> record.recordId().toString())).toList();
		}

		private UUID reference(ExchangeRecord record, String name) {
			JsonNode value = record.values().path(name);
			if (!value.isTextual()) {
				throw new IllegalArgumentException("Missing portable " + name + " in " + record.recordId());
			}
			try {
				UUID id = UUID.fromString(value.asText());
				if (!id.toString().equals(value.asText())) {
					throw new IllegalArgumentException("Non-canonical UUID");
				}
				return id;
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("Invalid portable " + name + " in " + record.recordId(), exception);
			}
		}

		private ExchangeRecord require(String type, UUID id) {
			ExchangeRecord record = records.getOrDefault(type, Map.of()).get(id);
			if (record == null) {
				throw new IllegalArgumentException("Missing " + type + " reference: " + id);
			}
			return record;
		}

		private String sourceHash(ExchangeRecord owner) {
			return documentHash(reference(owner, "source_document_id"));
		}

		private UUID subjectId() {
			return subjectId;
		}
	}
}
