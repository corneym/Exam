package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;

/**
 * Coordinates a complete, read-only, baseline-free two-way Subject preview.
 *
 * <p>
 * Both inputs are complete, validated {@code SUBJECT_SNAPSHOT} archives. All
 * record relationships use portable UUIDs, never installation-local integers.
 * The service provides evidence, not permission to overwrite or import; the
 * master remains authoritative and #272 owns all later publication.
 *
 * <p>
 * An image-content match is kept separate from a capture match. In particular,
 * image evidence cannot change a conflicting Question or Shared Context into an
 * automatically identical record.
 */
public final class SubjectReconciliationPreviewService {

	private static final String RECORDS_PATH = "records/subject-graph.jsonl";
	private static final Map<String, String> REFERENCE_TYPES = Map.ofEntries(Map.entry("subject_id", "SUBJECT"),
			Map.entry("syllabus_version_id", "SYLLABUS_VERSION"), Map.entry("parent_id", "CURRICULUM_NODE"),
			Map.entry("provider_id", "EXAM_PROVIDER"), Map.entry("source_document_id", "SOURCE_DOCUMENT"),
			Map.entry("exam_id", "EXAM"), Map.entry("booklet_id", "EXAM_BOOKLET"),
			Map.entry("classification_node_id", "CURRICULUM_NODE"), Map.entry("source_question_id", "SOURCE_QUESTION"),
			Map.entry("shared_context_id", "SHARED_QUESTION_CONTEXT"),
			Map.entry("pending_mcq_shared_context_id", "SHARED_QUESTION_CONTEXT"),
			Map.entry("answer_file_id", "ANSWER_FILE"), Map.entry("question_id", "QUESTION"),
			Map.entry("answer_id", "ANSWER"), Map.entry("source_node_id", "CURRICULUM_NODE"),
			Map.entry("target_node_id", "CURRICULUM_NODE"), Map.entry("target_syllabus_version_id", "SYLLABUS_VERSION"),
			Map.entry("current_curriculum_node_id", "CURRICULUM_NODE"), Map.entry("image_id", "QUESTION_IMAGE"));
	private final ExamWorkArchiveValidator validator = new ExamWorkArchiveValidator();
	private final ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();

	/** Creates a coordinator service which never writes database or asset files. */
	public SubjectReconciliationPreviewService() {
	}

	/**
	 * Compares two independently exported, complete Subject archives.
	 *
	 * @param masterArchive   coordinator's previously exported Subject snapshot
	 * @param incomingArchive independently exported contributor snapshot
	 * @return immutable proposals, image evidence and asset evidence
	 * @throws IOException              if either archive is invalid, incompatible
	 *                                  or unreadable
	 * @throws IllegalArgumentException if the supplied Subject graphs are malformed
	 */
	public Result preview(Path masterArchive, Path incomingArchive) throws IOException {
		Objects.requireNonNull(masterArchive, "masterArchive");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		if (masterArchive.toAbsolutePath().normalize().equals(incomingArchive.toAbsolutePath().normalize())) {
			throw new IllegalArgumentException("Two distinct Subject snapshots are required");
		}
		Snapshot left = read(masterArchive);
		Snapshot right = read(incomingArchive);
		if (!left.manifest().subjectName().equals(right.manifest().subjectName())) {
			throw new IllegalArgumentException("Choose snapshots for the same Subject");
		}
		CurriculumReconciler curriculumStage = new CurriculumReconciler();
		Preview curriculum = curriculumStage.preview(left.records(), right.records());
		Preview assessment = new AssessmentReconciler().preview(left.records(), left.assets(), right.records(),
				right.assets(), curriculum);
		Preview capture = new CaptureReconciler().preview(left.records(), right.records(), curriculum, assessment);
		ImageReconciler.ImagePreview images = new ImageReconciler().preview(left.records(), left.assets(),
				right.records(), right.assets(), capture);
		List<Proposal> all = new ArrayList<>();
		all.addAll(curriculum.proposals());
		all.addAll(assessment.proposals());
		all.addAll(capture.proposals());
		appendCurriculumMappings(left.records(), right.records(), all);
		appendQuestionImages(left.records(), right.records(), all);
		all.sort(Comparator.comparing(Proposal::recordType).thenComparing(p -> p.incomingId().toString()));
		requireCompleteCoverage(right.records(), all);
		List<AssetEvidence> assets = compareAssets(left.assets(), right.assets(), all);
		return new Result(left.manifest().packageId(), right.manifest().packageId(), right.manifest().subjectName(),
				all, images.evidence(), assets, collectDependencies(right.records()));
	}

	/** Checks that source and target curriculum nodes identify one mapping. */
	private void appendCurriculumMappings(List<ExchangeRecord> master, List<ExchangeRecord> incoming,
			List<Proposal> proposals) {
		Map<String, Map<UUID, Proposal>> index = index(proposals);
		for (ExchangeRecord mapping : ofType(incoming, "CURRICULUM_MAPPING")) {
			UUID source = uuid(mapping.values(), "source_node_id");
			UUID target = uuid(mapping.values(), "target_node_id");
			Proposal sourceMatch = find(index, "CURRICULUM_NODE", source);
			Proposal targetMatch = find(index, "CURRICULUM_NODE", target);
			UUID masterSource = sourceMatch == null ? null : sourceMatch.masterId();
			UUID masterTarget = targetMatch == null ? null : targetMatch.masterId();
			List<ExchangeRecord> candidates = masterSource == null || masterTarget == null ? List.of()
					: ofType(master, "CURRICULUM_MAPPING").stream()
							.filter(record -> masterSource.equals(uuid(record.values(), "source_node_id"))
									&& masterTarget.equals(uuid(record.values(), "target_node_id")))
							.toList();
			ExchangeRecord match = candidates.size() == 1 ? candidates.getFirst() : null;
			Classification kind;
			String reason;
			if (sourceMatch == null || targetMatch == null || sourceMatch.classification() != Classification.IDENTICAL
					|| targetMatch.classification() != Classification.IDENTICAL || candidates.size() > 1) {
				kind = Classification.CONFLICT;
				reason = "Curriculum mapping endpoints are ambiguous, different or unresolved.";
			} else if (match == null) {
				kind = Classification.COMPATIBLE_ADDITION;
				reason = "New mapping between otherwise identical curriculum nodes; review required.";
			} else if (sameRebasedMapping(match, mapping, masterSource, masterTarget)) {
				kind = Classification.IDENTICAL;
				reason = "Both curriculum endpoints and mapping metadata agree.";
			} else {
				kind = Classification.CONFLICT;
				reason = "Matching curriculum endpoints have different mapping metadata.";
			}
			proposals.add(new Proposal("CURRICULUM_MAPPING", mapping.recordId(),
					match == null ? null : match.recordId(), source, masterSource, kind, reason));
		}
	}

	/**
	 * Reviews QuestionImage records without interpreting identical bytes as owner
	 * identity.
	 */
	private void appendQuestionImages(List<ExchangeRecord> master, List<ExchangeRecord> incoming,
			List<Proposal> proposals) {
		Map<String, Map<UUID, Proposal>> index = index(proposals);
		for (ExchangeRecord image : ofType(incoming, "QUESTION_IMAGE")) {
			UUID owner = uuid(image.values(), "question_id");
			Proposal question = find(index, "QUESTION", owner);
			if (question == null) {
				throw new IllegalArgumentException("Image has no reconciled Question: " + image.recordId());
			}
			String hash = requiredHash(image.values(), "image_png");
			List<ExchangeRecord> candidates = question.masterId() == null ? List.of()
					: ofType(master, "QUESTION_IMAGE").stream()
							.filter(other -> question.masterId().equals(uuid(other.values(), "question_id"))
									&& hash.equals(requiredHash(other.values(), "image_png")))
							.toList();
			ExchangeRecord match = candidates.size() == 1 ? candidates.getFirst() : null;
			Classification kind;
			String reason;
			if (question.classification() == Classification.CONFLICT || candidates.size() > 1) {
				kind = Classification.CONFLICT;
				reason = "Image matches are insufficient while its Question identity is unresolved.";
			} else if (match == null) {
				kind = question.masterId() == null ? Classification.NEW : Classification.COMPATIBLE_ADDITION;
				reason = "New QuestionImage bytes or an independent Question; no existing image overwritten.";
			} else {
				kind = Classification.IDENTICAL;
				reason = "Unique matching PNG bytes under the same reconciled Question.";
			}
			proposals.add(new Proposal("QUESTION_IMAGE", image.recordId(), match == null ? null : match.recordId(),
					owner, question.masterId(), kind, reason));
		}
	}

	/**
	 * Validates and inventories every portable relationship in the incoming graph.
	 */
	private List<Dependency> collectDependencies(List<ExchangeRecord> records) {
		Set<String> identities = new HashSet<>();
		for (ExchangeRecord record : records) {
			identities.add(record.recordType() + ":" + record.recordId());
		}
		Set<Dependency> edges = new HashSet<>();
		for (ExchangeRecord record : records) {
			collectNodeDependencies(record.recordType(), record.recordId(), record.values(), identities, edges);
		}
		return edges.stream()
				.sorted(Comparator.comparing(Dependency::ownerType).thenComparing(edge -> edge.ownerId().toString())
						.thenComparing(Dependency::relatedType).thenComparing(edge -> edge.relatedId().toString())
						.thenComparing(Dependency::field))
				.toList();
	}

	/**
	 * Recursively reads only recognised portable reference fields, never local IDs.
	 */
	private void collectNodeDependencies(String ownerType, UUID ownerId, JsonNode node, Set<String> identities,
			Set<Dependency> edges) {
		if (node.isArray()) {
			for (JsonNode child : node) {
				collectNodeDependencies(ownerType, ownerId, child, identities, edges);
			}
		} else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> field : node.properties()) {
				JsonNode value = field.getValue();
				String relatedType = REFERENCE_TYPES.get(field.getKey());
				if (relatedType != null && !value.isNull()) {
					UUID relatedId = uuid(node, field.getKey());
					if (!identities.contains(relatedType + ":" + relatedId)) {
						throw new IllegalArgumentException("Missing portable dependency " + relatedType + ":"
								+ relatedId + " in " + ownerType + ":" + ownerId);
					}
					edges.add(new Dependency(ownerType, ownerId, relatedType, relatedId, field.getKey()));
				}
				collectNodeDependencies(ownerType, ownerId, value, identities, edges);
			}
		}
	}

	/**
	 * Provides a classification for each incoming file without deduplicating by
	 * digest alone.
	 */
	private List<AssetEvidence> compareAssets(ExamWorkAssetIndex master, ExamWorkAssetIndex incoming,
			List<Proposal> proposals) {
		Map<String, Map<UUID, Proposal>> matches = index(proposals);
		Map<String, Reference> masterPaths = new HashMap<>();
		for (Reference asset : master.references()) {
			masterPaths.put(asset.managedPath(), asset);
		}
		List<AssetEvidence> evidence = new ArrayList<>();
		for (Reference asset : incoming.references()) {
			Reference match = masterPaths.get(asset.managedPath());
			Classification status;
			String reason;
			if (match == null) {
				status = Classification.COMPATIBLE_ADDITION;
				reason = "New managed path; matching bytes elsewhere do not imply the same source record.";
			} else if (match.sha256().equals(asset.sha256()) && match.usage().equals(asset.usage())) {
				status = Classification.IDENTICAL;
				reason = "Managed path, usage and verified bytes agree.";
			} else {
				status = Classification.CONFLICT;
				reason = "Existing managed path has different bytes or usage; must not overwrite.";
			}
			if (asset.ownerType() != null) {
				Proposal owner = find(matches, asset.ownerType(), asset.ownerId());
				if (owner == null || owner.classification() == Classification.CONFLICT) {
					status = Classification.CONFLICT;
					reason = "Referenced owner has unresolved identity or content; " + reason;
				} else if (match != null && match.ownerType() != null && owner.masterId() != null
						&& (!match.ownerType().equals(asset.ownerType())
								|| !match.ownerId().equals(owner.masterId()))) {
					status = Classification.CONFLICT;
					reason = "The same managed location is attributed to a different master record.";
				}
			}
			evidence.add(new AssetEvidence(asset.managedPath(), asset.usage(), asset.ownerType(), asset.ownerId(),
					match == null ? null : match.managedPath(), asset.sha256(), match == null ? null : match.sha256(),
					status, reason));
		}
		evidence.sort(Comparator.comparing(AssetEvidence::incomingPath).thenComparing(AssetEvidence::incomingSha256));
		return List.copyOf(evidence);
	}

	/** Reports a small deterministic set of missing identity keys. */
	private Set<String> difference(Set<String> expected, Set<String> described) {
		Set<String> remainder = new java.util.TreeSet<>(expected);
		remainder.removeAll(described);
		return remainder;
	}

	/** Finds exactly one matching portable proposal for an incoming reference. */
	private Proposal find(Map<String, Map<UUID, Proposal>> matches, String type, UUID id) {
		return matches.getOrDefault(type, Map.of()).get(id);
	}

	/** Creates an index for already reconciled incoming identities. */
	private Map<String, Map<UUID, Proposal>> index(List<Proposal> proposals) {
		Map<String, Map<UUID, Proposal>> matches = new HashMap<>();
		for (Proposal proposal : proposals) {
			Proposal previous = matches.computeIfAbsent(proposal.recordType(), _ -> new HashMap<>())
					.putIfAbsent(proposal.incomingId(), proposal);
			if (previous != null) {
				throw new IllegalArgumentException(
						"Duplicate preview proposal: " + proposal.recordType() + ":" + proposal.incomingId());
			}
		}
		return matches;
	}

	/** Selects records of a portable entity type. */
	private List<ExchangeRecord> ofType(List<ExchangeRecord> records, String type) {
		return records.stream().filter(record -> type.equals(record.recordType())).toList();
	}

	/**
	 * Validates one preselected archive and reads its canonical structured entries.
	 */
	private Snapshot read(Path path) throws IOException {
		ExamWorkManifest manifest = validator.validate(path, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		if (manifest.packageType() != ExamWorkPackageType.SUBJECT_SNAPSHOT
				|| manifest.scope().level() != ExamWorkManifest.ScopeLevel.SUBJECT) {
			throw new PackageFormatException("Coordinator preview requires complete Subject snapshots");
		}
		try (ZipFile zip = ZipFile.builder().setPath(path).get()) {
			ZipArchiveEntry recordEntry = zip.getEntry(RECORDS_PATH);
			ZipArchiveEntry assetEntry = zip.getEntry(ExamWorkAssetIndex.INDEX_PATH);
			if (recordEntry == null || assetEntry == null) {
				throw new PackageFormatException("Subject snapshot lacks its portable record or asset index");
			}
			List<ExchangeRecord> records;
			ExamWorkAssetIndex assets;
			try (InputStream input = zip.getInputStream(recordEntry)) {
				records = codec.readRecords(input);
			}
			try (InputStream input = zip.getInputStream(assetEntry)) {
				assets = codec.readAssetIndex(input);
			}
			List<ExchangeRecord> subjects = ofType(records, "SUBJECT");
			if (subjects.size() != 1 || !subjects.getFirst().recordId().equals(manifest.subjectId())
					|| !manifest.subjectName().equals(subjects.getFirst().values().path("subject_name").asText())) {
				throw new PackageFormatException("Manifest Subject disagrees with portable Subject records");
			}
			return new Snapshot(manifest, records, assets);
		}
	}

	/**
	 * Accepts only one proposal for each incoming record, including peripheral
	 * types.
	 */
	private void requireCompleteCoverage(List<ExchangeRecord> incoming, List<Proposal> proposals) {
		Set<String> identifiers = new HashSet<>();
		for (ExchangeRecord proposal : incoming) {
			identifiers.add(proposal.recordType() + ":" + proposal.recordId());
		}
		Set<String> described = new HashSet<>();
		for (Proposal proposal : proposals) {
			if (!described.add(proposal.recordType() + ":" + proposal.incomingId())) {
				throw new IllegalArgumentException("Duplicate proposal for incoming entity");
			}
		}
		if (!identifiers.equals(described)) {
			throw new IllegalArgumentException("Incomplete Subject preview: missing or extra record proposals "
					+ difference(identifiers, described));
		}
	}

	/** Requires canonical lower-case SHA-256 evidence. */
	private String requiredHash(JsonNode values, String field) {
		String hash = values.path(field).asText("");
		if (!hash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Missing or invalid embedded image hash");
		}
		return hash;
	}

	/**
	 * Compares mapping metadata after replacing cross-installation node references.
	 */
	private boolean sameRebasedMapping(ExchangeRecord master, ExchangeRecord incoming, UUID source, UUID target) {
		ObjectNode adjusted = (ObjectNode) incoming.values();
		adjusted.put("source_node_id", source.toString());
		adjusted.put("target_node_id", target.toString());
		return master.values().equals(adjusted);
	}

	/** Parses a canonical portable relationship UUID. */
	private UUID uuid(JsonNode values, String field) {
		String text = values.path(field).asText("");
		UUID id;
		try {
			id = UUID.fromString(text);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid " + field + " UUID", exception);
		}
		if (!id.toString().equals(text)) {
			throw new IllegalArgumentException("Noncanonical " + field + " UUID");
		}
		return id;
	}

	/**
	 * One read-only comparison of a logical asset location.
	 *
	 * @param incomingPath    contributor's managed relative path
	 * @param usage           logical asset usage
	 * @param ownerType       portable owner type, or null for an inventoried asset
	 * @param incomingOwnerId portable owner UUID, or null
	 * @param masterPath      matched master path, or null for an independent asset
	 * @param incomingSha256  verified contributor bytes digest
	 * @param masterSha256    verified master bytes digest, or null
	 * @param classification  content/location comparison
	 * @param reason          review explanation
	 */
	public record AssetEvidence(String incomingPath, String usage, String ownerType, UUID incomingOwnerId,
			String masterPath, String incomingSha256, String masterSha256, Classification classification,
			String reason) {

		/** Checks that a comparison has meaningful evidence and status. */
		public AssetEvidence {
			Objects.requireNonNull(incomingPath, "incomingPath");
			Objects.requireNonNull(usage, "usage");
			if ((ownerType == null) != (incomingOwnerId == null)) {
				throw new IllegalArgumentException("Asset owner is incomplete");
			}
			Objects.requireNonNull(incomingSha256, "incomingSha256");
			Objects.requireNonNull(classification, "classification");
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Asset evidence requires a reason");
			}
		}
	}

	/**
	 * One portable reference which must remain intact in any later import plan.
	 *
	 * @param ownerType   referencing record's entity type
	 * @param ownerId     referencing portable UUID
	 * @param relatedType referenced entity type
	 * @param relatedId   referenced portable UUID
	 * @param field       portable JSON relationship field
	 */
	public record Dependency(String ownerType, UUID ownerId, String relatedType, UUID relatedId, String field) {

		/** Validates both portable identities and the relationship field. */
		public Dependency {
			Objects.requireNonNull(ownerType, "ownerType");
			Objects.requireNonNull(ownerId, "ownerId");
			Objects.requireNonNull(relatedType, "relatedType");
			Objects.requireNonNull(relatedId, "relatedId");
			Objects.requireNonNull(field, "field");
		}
	}

	/**
	 * Immutable complete coordinator preview, containing no accepted decisions.
	 *
	 * @param masterPackageId   coordinator snapshot identity
	 * @param incomingPackageId contributor snapshot identity
	 * @param subjectName       compared Subject
	 * @param proposals         exactly one proposal per incoming portable record
	 * @param imageEvidence     evidence for image-bearing capture owners
	 * @param assetEvidence     evidence for every incoming binary asset usage
	 * @param dependencies      portable relationships to preserve when reviewing
	 *                          decisions
	 */
	public record Result(UUID masterPackageId, UUID incomingPackageId, String subjectName, List<Proposal> proposals,
			List<ImageReconciler.Evidence> imageEvidence, List<AssetEvidence> assetEvidence,
			List<Dependency> dependencies) {

		/** Defensively copies the complete read-only preview. */
		public Result {
			Objects.requireNonNull(masterPackageId, "masterPackageId");
			Objects.requireNonNull(incomingPackageId, "incomingPackageId");
			Objects.requireNonNull(subjectName, "subjectName");
			proposals = List.copyOf(proposals);
			imageEvidence = List.copyOf(imageEvidence);
			assetEvidence = List.copyOf(assetEvidence);
			dependencies = List.copyOf(dependencies);
		}

		/**
		 * Selects proposals of a portable record type.
		 *
		 * @param recordType portable entity type
		 * @return ordered proposals for the requested type
		 */
		public List<Proposal> forType(String recordType) {
			Objects.requireNonNull(recordType, "recordType");
			return proposals.stream().filter(proposal -> proposal.recordType().equals(recordType)).toList();
		}
	}

	/** Retains the validated archive's parsed portable content. */
	private record Snapshot(ExamWorkManifest manifest, List<ExchangeRecord> records, ExamWorkAssetIndex assets) {
	}
}
