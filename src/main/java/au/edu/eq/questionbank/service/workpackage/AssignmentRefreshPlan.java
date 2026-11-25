package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.Identity;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository.State;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository.Session;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentPackageInspector.Inspection;

/**
 * Establishes the permissible replacement of a previously installed contributor
 * Exam with a fresh authoritative baseline.
 */
final class AssignmentRefreshPlan {

	private final Map<Key, Long> refreshable;
	private final Map<Key, Identity> replacements;

	private AssignmentRefreshPlan(Map<Key, Long> refreshable, Map<Key, Identity> replacements) {
		this.refreshable = Map.copyOf(refreshable);
		this.replacements = Map.copyOf(replacements);
	}

	static AssignmentRefreshPlan prepare(Connection connection, SqliteDatabase database, Inspection incoming)
			throws SQLException, IOException {
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var visibility = new SqliteContributorAssignmentVisibilityRepository(database);
		UUID examId = incoming.manifest().scope().examIds().getFirst();
		UUID contributorId = UUID.fromString(incoming.context().values().path("contributor_id").asText());
		List<Session> previousSessions = sessions.findAll().stream()
				.filter(session -> session.examPortableId().equals(examId))
				.sorted(Comparator.comparing(Session::issuedAt)).toList();
		if (previousSessions.isEmpty()) {
			return new AssignmentRefreshPlan(Map.of(), Map.of());
		}
		for (Session session : previousSessions) {
			if (!session.masterInstallationId().equals(incoming.manifest().originInstallationId())
					|| !session.subjectPortableId().equals(incoming.manifest().subjectId())
					|| !session.contributorId().equals(contributorId)) {
				throw new PackageFormatException(
						"Previous assignment authority differs " + "from the incoming assignment");
			}
			if (!incoming.manifest().createdAt().isAfter(session.issuedAt())) {
				throw new PackageFormatException("Cannot install an older assignment over newer work");
			}
			State state = visibility.find(session.assignmentId())
					.orElseThrow(() -> new SQLException("Missing previous assignment state"));
			if (state == State.ACTIVE) {
				throw new PackageFormatException("An active assignment already exists for this Exam");
			}
		}
		Session previous = previousSessions.getLast();
		var identities = new SqlitePortableIdentityRepository(database);
		Identity localExam = identities.findByPortableId(connection, EntityType.EXAM, examId)
				.orElseThrow(() -> new SQLException("Previously installed Exam is missing"));
		if (localExam.localId() != previous.examLocalId()) {
			throw new SQLException("Previous assignment Exam identity has changed");
		}
		Map<Key, ExchangeRecord> current = readCurrentRecords(connection, database, previous.subjectLocalId(),
				previous.examLocalId());
		byte[] baselineBytes = sessions.readBaseline(previous.assignmentId()).records();
		List<ExchangeRecord> baseline = new ExamWorkJsonlCodec().readRecords(new ByteArrayInputStream(baselineBytes));

		// Detect missing or changed protected baseline records
		// and identify every locally captured addition.
		var delta = new ContributionDeltaPlanner().plan(baseline, new ArrayList<>(current.values()));
		State previousState = visibility.find(previous.assignmentId()).orElseThrow();
		if (previousState == State.REMOVED && !delta.candidates().isEmpty()) {
			throw new PackageFormatException("Removed assignment contains unresolved local work");
		}
		List<ExchangeRecord> authorised = new AssignmentReconciliationAuthority(database).assess(incoming);
		Map<Key, ExchangeRecord> mappings = new HashMap<>();
		for (ExchangeRecord evidence : authorised) {
			var values = evidence.values();
			if (!previous.assignmentId().toString().equals(values.path("assignment_id").asText())) {
				continue;
			}
			Key original = key(values.path("record_type").asText(),
					UUID.fromString(values.path("contributor_record_id").asText()));
			if (mappings.putIfAbsent(original, evidence) != null) {
				throw new PackageFormatException("Repeated contributor reconciliation mapping");
			}
		}
		Map<Key, ExchangeRecord> incomingRecords = index(incoming.records());
		Map<Key, Key> canonicalKeys = new HashMap<>();
		Map<Key, Identity> replacements = new LinkedHashMap<>();

		// Every local addition must be accounted for by evidence
		// from the most recent submitted assignment.
		for (ExchangeRecord candidate : delta.candidates()) {
			Key original = key(candidate.recordType(), candidate.recordId());
			ExchangeRecord evidence = mappings.remove(original);
			if (previousState != State.SUBMITTED || evidence == null) {
				throw new PackageFormatException(
						"Saved contributor work has no accepted " + "reconciliation evidence: " + original);
			}
			var values = evidence.values();
			if (!candidate.revisionId().toString().equals(values.path("proposed_revision_id").asText())) {
				throw new PackageFormatException(
						"Submitted contributor revision differs " + "from reconciliation evidence");
			}
			UUID canonicalId = UUID.fromString(values.path("canonical_record_id").asText());
			Key canonical = key(candidate.recordType(), canonicalId);
			if (!incomingRecords.containsKey(canonical)) {
				throw new PackageFormatException("Accepted canonical record is absent " + "from the incoming baseline");
			}
			canonicalKeys.put(original, canonical);
			if (!candidate.recordId().equals(canonicalId)) {
				Identity originalIdentity = identities
						.findByPortableId(connection, EntityType.valueOf(candidate.recordType()), candidate.recordId())
						.orElseThrow(() -> new SQLException("Contributor identity is missing"));
				if (replacements.putIfAbsent(canonical, originalIdentity) != null) {
					throw new PackageFormatException("Multiple contributor records map " + "to one canonical record");
				}
			}
		}
		if (!mappings.isEmpty()) {
			throw new PackageFormatException("Reconciliation evidence contains " + "unaccounted local records");
		}
		Map<Key, Long> refreshable = new LinkedHashMap<>();
		for (Map.Entry<Key, ExchangeRecord> entry : current.entrySet()) {
			ExchangeRecord record = entry.getValue();
			EntityType type = EntityType.valueOf(record.recordType());
			if (!isExamOwned(type)) {
				continue;
			}
			Key canonical = canonicalKeys.getOrDefault(entry.getKey(), entry.getKey());
			if (!incomingRecords.containsKey(canonical)) {

				// Do not silently discard cached records.
				// Authoritative deletion reconciliation requires
				// its own validated dependency-removal operation.
				throw new PackageFormatException(
						"Incoming assignment removes an existing " + "Exam record: " + entry.getKey());
			}
			long localId = identities.findByPortableId(connection, type, record.recordId())
					.orElseThrow(() -> new SQLException("Existing Exam identity is missing")).localId();
			if (refreshable.putIfAbsent(canonical, localId) != null) {
				throw new PackageFormatException("Multiple local records resolve " + "to one authoritative identity");
			}
		}

		// Curriculum dependencies are shared between assignments.
		// Do not extend or replace their graph during an Exam refresh.
		for (ExchangeRecord record : incoming.records()) {
			EntityType type = EntityType.valueOf(record.recordType());
			boolean sharedCurriculum = switch (type) {
			case SUBJECT, SYLLABUS_VERSION, CURRICULUM_NODE, CURRICULUM_MAPPING -> true;
			default -> false;
			};
			if (sharedCurriculum && !current.containsKey(key(record.recordType(), record.recordId()))) {
				throw new PackageFormatException(
						"Assignment refresh introduces " + "a changed shared curriculum graph");
			}
		}
		return new AssignmentRefreshPlan(refreshable, replacements);
	}

	static void requireComplete(Connection connection, SqliteDatabase database, long subjectId, long examId,
			List<ExchangeRecord> expected) throws SQLException {
		Map<Key, ExchangeRecord> actual = readCurrentRecords(connection, database, subjectId, examId);
		Map<Key, ExchangeRecord> required = index(expected);
		if (!required.keySet().equals(actual.keySet())) {
			throw new SQLException("Installed Exam graph has missing " + "or unexpected records");
		}
		for (Key key : required.keySet()) {
			if (!required.get(key).equals(actual.get(key))) {
				throw new SQLException("Installed Exam differs from " + "authoritative baseline: " + key);
			}
		}
	}

	private static Map<Key, ExchangeRecord> index(List<ExchangeRecord> records) throws SQLException {
		Map<Key, ExchangeRecord> result = new LinkedHashMap<>();
		for (ExchangeRecord record : records) {
			Key key = key(record.recordType(), record.recordId());
			if (result.putIfAbsent(key, record) != null) {
				throw new SQLException("Duplicate portable record: " + key);
			}
		}
		return result;
	}

	private static boolean isExamOwned(EntityType type) {
		return switch (type) {
		case EXAM, EXAM_BOOKLET, ANSWER_FILE, SOURCE_QUESTION, SHARED_QUESTION_CONTEXT, QUESTION, QUESTION_IMAGE,
				ANSWER ->
			true;
		default -> false;
		};
	}

	private static Key key(String type, UUID portableId) {
		return SubjectReconciliationReview.recordKey(type, portableId);
	}

	private static Map<Key, ExchangeRecord> readCurrentRecords(Connection connection, SqliteDatabase database,
			long subjectId, long examId) throws SQLException {
		var graph = SqliteExamAssignmentGraphReader.readInTransaction(connection, subjectId, examId);
		var content = new SqlitePortableContentRepository(database);
		List<ExchangeRecord> records = new ArrayList<>();
		for (EntityType type : EntityType.values()) {
			for (long localId : graph.ids(type)) {
				records.add(content.snapshot(connection, type, localId).record());
			}
		}
		return index(records);
	}

	boolean canRefresh(Key key, long localId) {
		return Long.valueOf(localId).equals(refreshable.get(key));
	}

	Map<Key, Identity> replacements() {
		return replacements;
	}
}
