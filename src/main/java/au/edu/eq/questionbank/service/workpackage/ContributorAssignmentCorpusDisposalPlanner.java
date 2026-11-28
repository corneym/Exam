package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/** Calculates the exact, conservative database and asset disposal inventory. */
final class ContributorAssignmentCorpusDisposalPlanner {

	private static final List<EntitySelection> ENTITY_SELECTIONS = List.of(
			new EntitySelection(EntityType.EXAM, "SELECT id FROM exams WHERE id = ?"),
			new EntitySelection(EntityType.ANSWER_FILE, "SELECT id FROM answer_files WHERE exam_id = ?"),
			new EntitySelection(EntityType.EXAM_BOOKLET, "SELECT id FROM exam_booklets WHERE exam_id = ?"),
			new EntitySelection(EntityType.SOURCE_QUESTION, """
					SELECT source.id
					FROM source_questions source
					JOIN exam_booklets booklet ON booklet.id = source.booklet_id
					WHERE booklet.exam_id = ?
					"""), new EntitySelection(EntityType.SHARED_QUESTION_CONTEXT, """
					SELECT context.id
					FROM shared_question_contexts context
					JOIN exam_booklets booklet ON booklet.id = context.booklet_id
					WHERE booklet.exam_id = ?
					"""), new EntitySelection(EntityType.QUESTION, """
					SELECT question.id
					FROM questions question
					JOIN exam_booklets booklet ON booklet.id = question.booklet_id
					WHERE booklet.exam_id = ?
					"""), new EntitySelection(EntityType.QUESTION_IMAGE, """
					SELECT image.id
					FROM question_images image
					JOIN questions question ON question.id = image.question_id
					JOIN exam_booklets booklet ON booklet.id = question.booklet_id
					WHERE booklet.exam_id = ?
					"""), new EntitySelection(EntityType.ANSWER, """
					SELECT answer.id
					FROM answers answer
					JOIN questions question ON question.id = answer.question_id
					JOIN exam_booklets booklet ON booklet.id = question.booklet_id
					WHERE booklet.exam_id = ?
					"""), new EntitySelection(EntityType.SOURCE_DOCUMENT, """
					SELECT document.id
					FROM source_documents document
					WHERE (
					    EXISTS (
					        SELECT 1 FROM exam_booklets booklet
					        WHERE booklet.source_document_id = document.id
					          AND booklet.exam_id = ?
					    )
					    OR EXISTS (
					        SELECT 1 FROM answer_files answer_file
					        WHERE answer_file.source_document_id = document.id
					          AND answer_file.exam_id = ?
					    )
					)
					AND NOT EXISTS (
					    SELECT 1 FROM exam_booklets booklet
					    WHERE booklet.source_document_id = document.id
					      AND booklet.exam_id <> ?
					)
					AND NOT EXISTS (
					    SELECT 1 FROM answer_files answer_file
					    WHERE answer_file.source_document_id = document.id
					      AND answer_file.exam_id <> ?
					)
					""", 4));
	private final SqliteDatabase database;
	private final ManagedDataLayout layout;

	ContributorAssignmentCorpusDisposalPlanner(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
	}

	Plan plan(Connection connection, UUID assignmentId, Reason reason) throws SQLException, IOException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(assignmentId, "assignmentId");
		Objects.requireNonNull(reason, "reason");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Corpus disposal planning requires a transaction");
		}
		SessionEvidence session = readSession(connection, assignmentId, reason);
		requireExclusiveOwnership(connection, session);
		Map<BaselineKey, BaselineIdentity> baseline = readBaseline(session.baselineRecords());
		UUID localInstallationId = installationId(connection);
		List<Entity> entities = new ArrayList<>();
		for (EntitySelection selection : ENTITY_SELECTIONS) {
			addEntities(connection, session, selection, baseline, localInstallationId, entities);
		}
		boolean curriculumExclusive = addExclusiveCurriculum(connection, session, baseline, localInstallationId,
				entities);
		entities.sort(Comparator.comparing((Entity entity) -> entity.type().name()).thenComparingLong(Entity::localId));
		List<RetiredEntity> retiredEntities = retiredEntities(connection, session, baseline, curriculumExclusive);
		if (reason == Reason.REMOVED_UNCHANGED) {
			requireUnchanged(connection, baseline, entities, retiredEntities);
		}
		List<Asset> assets = managedAssets(connection, entities, retiredEntities, session.baselineAssets());
		String hash = planHash(session, entities, retiredEntities, assets);
		return new Plan(session.assignmentId(), reason, session.returnPackageId(), session.assignmentPackageId(),
				session.assignmentArchiveSha256(), hash, entities, retiredEntities, assets);
	}

	private boolean addExclusiveCurriculum(Connection connection, SessionEvidence session,
			Map<BaselineKey, BaselineIdentity> baseline, UUID localInstallationId, List<Entity> target)
			throws SQLException {
		if (exists(connection, """
				SELECT 1
				FROM contributor_assignment_sessions
				WHERE subject_local_id = ? AND assignment_id <> ?
				LIMIT 1
				""", session.subjectLocalId(), session.assignmentId().toString()) || exists(connection, """
				SELECT 1
				FROM imported_subject_authorities
				WHERE subject_local_id = ?
				LIMIT 1
				""", session.subjectLocalId())) {
			return false;
		}
		Set<Long> syllabusIds = new HashSet<>();
		syllabusIds.add(session.currentSyllabusLocalId());
		if (session.legacySyllabusLocalId() != null) {
			syllabusIds.add(session.legacySyllabusLocalId());
		}
		Set<Long> nodeIds = new HashSet<>();
		for (long syllabusId : syllabusIds) {
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT id
					FROM curriculum_nodes
					WHERE syllabus_version_id = ?
					""")) {
				statement.setLong(1, syllabusId);
				try (ResultSet rows = statement.executeQuery()) {
					while (rows.next()) {
						nodeIds.add(rows.getLong(1));
					}
				}
			}
		}
		if (hasExternalCurriculumDependency(connection, session.examLocalId(), syllabusIds, nodeIds)) {
			return false;
		}
		for (long syllabusId : syllabusIds) {
			addEntity(connection, session, EntityType.SYLLABUS_VERSION, syllabusId, baseline, localInstallationId,
					target);
		}
		for (long nodeId : nodeIds) {
			addEntity(connection, session, EntityType.CURRICULUM_NODE, nodeId, baseline, localInstallationId, target);
		}
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT id, source_node_id, target_node_id
				FROM curriculum_mappings
				""")) {
			while (rows.next()) {
				if (nodeIds.contains(rows.getLong("source_node_id"))
						&& nodeIds.contains(rows.getLong("target_node_id"))) {
					addEntity(connection, session, EntityType.CURRICULUM_MAPPING, rows.getLong("id"), baseline,
							localInstallationId, target);
				}
			}
		}
		return true;
	}

	private boolean hasExternalCurriculumDependency(Connection connection, long examLocalId, Set<Long> syllabusIds,
			Set<Long> nodeIds) throws SQLException {
		for (long nodeId : nodeIds) {
			if (exists(connection, """
					SELECT 1
					FROM questions question
					JOIN exam_booklets booklet ON booklet.id = question.booklet_id
					WHERE question.classification_node_id = ? AND booklet.exam_id <> ?
					LIMIT 1
					""", nodeId, examLocalId) || exists(connection, """
					SELECT 1
					FROM question_output_exclusions exclusion
					JOIN questions question ON question.id = exclusion.question_id
					JOIN exam_booklets booklet ON booklet.id = question.booklet_id
					WHERE exclusion.current_curriculum_node_id = ? AND booklet.exam_id <> ?
					LIMIT 1
					""", nodeId, examLocalId)) {
				return true;
			}
		}
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT source_node_id, target_node_id
				FROM curriculum_mappings
				""")) {
			while (rows.next()) {
				boolean source = nodeIds.contains(rows.getLong("source_node_id"));
				boolean target = nodeIds.contains(rows.getLong("target_node_id"));
				if (source != target) {
					return true;
				}
			}
		}
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT source_node_id, target_syllabus_version_id
				FROM curriculum_mapping_reviews
				""")) {
			while (rows.next()) {
				boolean source = nodeIds.contains(rows.getLong("source_node_id"));
				boolean target = syllabusIds.contains(rows.getLong("target_syllabus_version_id"));
				if (source != target) {
					return true;
				}
			}
		}
		return false;
	}

	private void addEntity(Connection connection, SessionEvidence session, EntityType type, long localId,
			Map<BaselineKey, BaselineIdentity> baseline, UUID localInstallationId, List<Entity> target)
			throws SQLException {
		try (PreparedStatement identity = connection.prepareStatement("""
				SELECT portable_id, revision_id
				FROM portable_entity_identities
				WHERE entity_type = ? AND local_id = ? AND retired = 0
				""")) {
			identity.setString(1, type.name());
			identity.setLong(2, localId);
			try (ResultSet row = identity.executeQuery()) {
				if (!row.next()) {
					throw new SQLException(
							"Disposal candidate lacks a live portable identity: " + type + ":" + localId);
				}
				UUID portableId = parseUuid(row.getString("portable_id"), "portable identity");
				UUID revisionId = parseUuid(row.getString("revision_id"), "revision identity");
				if (row.next()) {
					throw new SQLException("Duplicate disposal candidate identity");
				}
				boolean reattachable = baseline.containsKey(new BaselineKey(type, portableId));
				UUID authority = reattachable ? session.masterInstallationId() : localInstallationId;
				target.add(new Entity(type, localId, portableId, revisionId, authority, reattachable));
			}
		}
	}

	private void addEntities(Connection connection, SessionEvidence session, EntitySelection selection,
			Map<BaselineKey, BaselineIdentity> baseline, UUID localInstallationId, List<Entity> target)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(selection.sql())) {
			for (int index = 1; index <= selection.parameterCount(); index++) {
				statement.setLong(index, session.examLocalId());
			}
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					long localId = rows.getLong(1);
					addEntity(connection, session, selection.type(), localId, baseline, localInstallationId, target);
				}
			}
		}
	}

	private List<RetiredEntity> retiredEntities(Connection connection, SessionEvidence session,
			Map<BaselineKey, BaselineIdentity> baseline, boolean curriculumExclusive) throws SQLException {
		List<RetiredEntity> result = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT entity_type, local_id, portable_id, revision_id
				FROM portable_entity_retirements
				ORDER BY entity_type, local_id
				""")) {
			while (rows.next()) {
				EntityType type;
				try {
					type = EntityType.valueOf(rows.getString("entity_type"));
				} catch (IllegalArgumentException failure) {
					throw new SQLException("Retired portable identity has an unsupported entity type", failure);
				}
				UUID portableId = parseUuid(rows.getString("portable_id"), "retired portable identity");
				if (!baseline.containsKey(new BaselineKey(type, portableId))
						|| !isRetiredEntityEligible(type, curriculumExclusive)) {
					continue;
				}
				result.add(new RetiredEntity(type, rows.getLong("local_id"), portableId,
						parseUuid(rows.getString("revision_id"), "retired revision identity"),
						session.masterInstallationId()));
			}
		}
		return result.stream().sorted(Comparator.comparing((RetiredEntity entity) -> entity.type().name())
				.thenComparingLong(RetiredEntity::formerLocalId)).toList();
	}

	private boolean isRetiredEntityEligible(EntityType type, boolean curriculumExclusive) {
		return switch (type) {
		case SYLLABUS_VERSION, CURRICULUM_NODE, CURRICULUM_MAPPING -> curriculumExclusive;
		case SOURCE_DOCUMENT, EXAM, EXAM_BOOKLET, QUESTION, ANSWER_FILE, ANSWER, SOURCE_QUESTION,
				SHARED_QUESTION_CONTEXT, QUESTION_IMAGE ->
			true;
		case SUBJECT, EXAM_PROVIDER -> false;
		};
	}

	private List<Asset> managedAssets(Connection connection, List<Entity> entities, List<RetiredEntity> retiredEntities,
			byte[] baselineAssets) throws SQLException, IOException {
		Set<Long> sourceDocuments = entityIds(entities, EntityType.SOURCE_DOCUMENT);
		Set<Long> syllabuses = entityIds(entities, EntityType.SYLLABUS_VERSION);
		Map<String, Asset> assets = new LinkedHashMap<>();
		for (Entity entity : entities) {
			if (entity.type() != EntityType.SOURCE_DOCUMENT && entity.type() != EntityType.SYLLABUS_VERSION) {
				continue;
			}
			String sql = entity.type() == EntityType.SOURCE_DOCUMENT ? """
					SELECT relative_path, content_sha256
					FROM source_documents WHERE id = ?
					""" : """
					SELECT source_pdf_path, NULL
					FROM syllabus_versions WHERE id = ? AND source_pdf_path IS NOT NULL
					""";
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, entity.localId());
				try (ResultSet row = statement.executeQuery()) {
					if (!row.next()) {
						if (entity.type() == EntityType.SYLLABUS_VERSION) {
							continue;
						}
						throw new SQLException("Planned managed asset owner disappeared");
					}
					String relativePath = row.getString(1);
					String storedHash = row.getString(2);
					if (row.next()) {
						throw new SQLException("Duplicate planned managed asset owner");
					}
					if (referencedOutsideCandidates(connection, relativePath, sourceDocuments, syllabuses)) {
						continue;
					}
					Path path = requireOrdinaryManagedFile(relativePath);
					String actualHash = sha256(path);
					if (storedHash != null && !storedHash.equals(actualHash)) {
						throw new IOException("Managed source digest differs from database evidence: " + relativePath);
					}
					String kind = entity.type() == EntityType.SOURCE_DOCUMENT ? "SOURCE_DOCUMENT" : "CURRICULUM_SOURCE";
					Asset previous = assets.putIfAbsent(relativePath, new Asset(relativePath, actualHash, kind));
					if (previous != null && !previous.sha256().equals(actualHash)) {
						throw new IOException("Managed asset path has conflicting content evidence: " + relativePath);
					}
				}
			}
		}
		Set<UUID> retiredSourceDocuments = new HashSet<>();
		for (RetiredEntity entity : retiredEntities) {
			if (entity.type() == EntityType.SOURCE_DOCUMENT) {
				retiredSourceDocuments.add(entity.portableId());
			}
		}
		if (!retiredSourceDocuments.isEmpty()) {
			try (InputStream input = new ByteArrayInputStream(baselineAssets)) {
				for (ExamWorkAssetIndex.Reference reference : new ExamWorkJsonlCodec().readAssetIndex(input)
						.references()) {
					if (!EntityType.SOURCE_DOCUMENT.name().equals(reference.ownerType())
							|| !retiredSourceDocuments.contains(reference.ownerId()) || referencedOutsideCandidates(
									connection, reference.managedPath(), sourceDocuments, syllabuses)) {
						continue;
					}
					Path path = requireOrdinaryManagedFile(reference.managedPath());
					String actualHash = sha256(path);
					if (!reference.sha256().equals(actualHash)) {
						throw new IOException("Retired managed source digest differs from baseline evidence: "
								+ reference.managedPath());
					}
					Asset previous = assets.putIfAbsent(reference.managedPath(),
							new Asset(reference.managedPath(), actualHash, "SOURCE_DOCUMENT"));
					if (previous != null && !previous.sha256().equals(actualHash)) {
						throw new IOException(
								"Managed asset path has conflicting content evidence: " + reference.managedPath());
					}
				}
			}
		}
		return assets.values().stream().sorted(Comparator.comparing(Asset::relativePath)).toList();
	}

	private Set<Long> entityIds(List<Entity> entities, EntityType type) {
		Set<Long> ids = new HashSet<>();
		for (Entity entity : entities) {
			if (entity.type() == type) {
				ids.add(entity.localId());
			}
		}
		return Set.copyOf(ids);
	}

	private boolean referencedOutsideCandidates(Connection connection, String path, Set<Long> sourceDocuments,
			Set<Long> syllabuses) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 'SOURCE_DOCUMENT', id
				FROM source_documents
				WHERE relative_path = ?
				UNION ALL
				SELECT 'SYLLABUS_VERSION', id
				FROM syllabus_versions
				WHERE source_pdf_path = ?
				""")) {
			statement.setString(1, path);
			statement.setString(2, path);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					String type = rows.getString(1);
					long id = rows.getLong(2);
					if (("SOURCE_DOCUMENT".equals(type) && !sourceDocuments.contains(id))
							|| ("SYLLABUS_VERSION".equals(type) && !syllabuses.contains(id))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private Path requireOrdinaryManagedFile(String relativePath) throws IOException {
		Path path;
		try {
			path = layout.resolve(relativePath);
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new IOException("Unsafe managed source path in disposal plan", failure);
		}
		Path current = layout.dataRoot();
		for (Path component : layout.dataRoot().relativize(path)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Indirect managed source path in disposal plan: " + current);
			}
		}
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Managed source is missing or not an ordinary file: " + relativePath);
		}
		return path;
	}

	private SessionEvidence readSession(Connection connection, UUID assignmentId, Reason reason) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT session.subject_local_id,
				       session.exam_local_id,
				       session.current_syllabus_local_id,
				       session.legacy_syllabus_local_id,
				       session.master_installation_id,
				       session.baseline_records,
				       session.baseline_assets,
				       session.records_sha256,
				       session.assets_sha256,
				       session.package_id AS assignment_package_id,
				       session.archive_sha256 AS assignment_archive_sha256,
				       EXISTS (
				           SELECT 1 FROM contributor_assignment_hidden hidden
				           WHERE hidden.assignment_id = session.assignment_id
				       ) AS removed,
				       packet.package_id AS return_package_id
				FROM contributor_assignment_sessions session
				LEFT JOIN contributor_return_packages packet
				  ON packet.package_id = (
				      SELECT latest.package_id
				      FROM contributor_return_packages latest
				      WHERE latest.assignment_id = session.assignment_id
				      ORDER BY latest.return_number DESC
				      LIMIT 1
				  )
				WHERE session.assignment_id = ?
				""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet row = statement.executeQuery()) {
				if (!row.next()) {
					throw new IllegalStateException("Assignment has no installed submitted corpus: " + assignmentId);
				}
				byte[] baselineRecords = row.getBytes("baseline_records");
				byte[] baselineAssets = row.getBytes("baseline_assets");
				if (baselineRecords == null || !row.getString("records_sha256").equals(sha256(baselineRecords))
						|| baselineAssets == null || !row.getString("assets_sha256").equals(sha256(baselineAssets))) {
					throw new SQLException("Assignment baseline failed its stored digest check");
				}
				long legacySyllabus = row.getLong("legacy_syllabus_local_id");
				Long legacySyllabusId = row.wasNull() ? null : legacySyllabus;
				String returnPackage = row.getString("return_package_id");
				SessionEvidence result = new SessionEvidence(assignmentId, row.getLong("subject_local_id"),
						row.getLong("exam_local_id"), row.getLong("current_syllabus_local_id"), legacySyllabusId,
						parseUuid(row.getString("master_installation_id"), "master installation identity"),
						baselineRecords, baselineAssets,
						returnPackage == null ? null : parseUuid(returnPackage, "terminal return identity"),
						parseUuid(row.getString("assignment_package_id"), "assignment package identity"),
						row.getString("assignment_archive_sha256"), row.getInt("removed") == 1, reason);
				if (row.next()) {
					throw new SQLException("Submitted assignment resolved to duplicate terminal evidence");
				}
				return result;
			}
		}
	}

	private void requireExclusiveOwnership(Connection connection, SessionEvidence session) throws SQLException {
		if (session.reason() == Reason.SUBMITTED && session.returnPackageId() == null) {
			throw new IllegalStateException("Assignment has no terminal Contribution evidence");
		}
		if (session.reason() == Reason.REMOVED_UNCHANGED && (session.returnPackageId() != null || !session.removed())) {
			throw new IllegalStateException("Assignment is not an unsubmitted removed cache");
		}
		if (exists(connection, """
				SELECT 1
				FROM contribution_publication_attempts
				WHERE assignment_id = ?
				LIMIT 1
				""", session.assignmentId().toString())) {
			throw new IllegalStateException("Assignment publication recovery remains unresolved");
		}
		if (exists(connection, """
				SELECT 1
				FROM contributor_assignment_sessions
				WHERE exam_local_id = ?
				  AND assignment_id <> ?
				LIMIT 1
				""", session.examLocalId(), session.assignmentId().toString())) {
			throw new IllegalStateException("Assigned Exam is shared by another installed assignment");
		}
		String examPortableId = scalar(connection, """
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = 'EXAM' AND local_id = ? AND retired = 0
				""", session.examLocalId());
		if (exists(connection, """
				SELECT 1 FROM exam_work_assignment_exam_locks
				WHERE exam_portable_id = ? LIMIT 1
				""", examPortableId)) {
			throw new IllegalStateException("Assigned Exam is retained by coordinator assignment evidence");
		}
		for (String table : List.of("master_contribution_write_permits", "master_contribution_region_permits",
				"master_contribution_graph_permits")) {
			if (exists(connection, "SELECT 1 FROM " + table + " WHERE exam_local_id = ? LIMIT 1",
					session.examLocalId())) {
				throw new IllegalStateException("Assigned Exam is retained by coordinator application evidence");
			}
		}
	}

	private void requireUnchanged(Connection connection, Map<BaselineKey, BaselineIdentity> baseline,
			List<Entity> entities, List<RetiredEntity> retiredEntities) throws SQLException {
		if (!retiredEntities.isEmpty() || entities.stream().anyMatch(entity -> !entity.reattachable())) {
			throw new IllegalStateException("Removed assignment contains unsent changed work");
		}
		var content = new SqlitePortableContentRepository(database);
		for (BaselineIdentity expected : baseline.values()) {
			long localId;
			try {
				localId = Long.parseLong(scalar(connection, """
						SELECT local_id
						FROM portable_entity_identities
						WHERE entity_type = ? AND portable_id = ? AND retired = 0
						""", expected.type().name(), expected.portableId().toString()));
			} catch (NumberFormatException failure) {
				throw new SQLException("Invalid removed-assignment local identity", failure);
			}
			ExchangeRecord actual = content.snapshot(connection, expected.type(), localId).record();
			if (!expected.record().equals(actual)) {
				throw new IllegalStateException("Removed assignment differs from its authoritative baseline");
			}
		}
	}

	private Map<BaselineKey, BaselineIdentity> readBaseline(byte[] bytes) throws IOException, SQLException {
		if (bytes == null || bytes.length == 0) {
			throw new SQLException("Assignment baseline is missing");
		}
		Map<BaselineKey, BaselineIdentity> identities = new LinkedHashMap<>();
		try (InputStream input = new ByteArrayInputStream(bytes)) {
			for (ExchangeRecord record : new ExamWorkJsonlCodec().readRecords(input)) {
				EntityType type;
				try {
					type = EntityType.valueOf(record.recordType());
				} catch (IllegalArgumentException ignored) {

					// Non-entity context records do not participate in cache identity reattachment.
					continue;
				}
				if (record.revisionId() == null) {
					throw new SQLException("Assignment baseline entity is missing its revision identity");
				}
				BaselineIdentity identity = new BaselineIdentity(type, record.recordId(), record.revisionId(), record);
				BaselineIdentity previous = identities.putIfAbsent(new BaselineKey(type, record.recordId()), identity);
				if (previous != null && !previous.equals(identity)) {
					throw new SQLException("Assignment baseline repeats a portable identity with another revision");
				}
			}
		}
		return Map.copyOf(identities);
	}

	private UUID installationId(Connection connection) throws SQLException {
		return parseUuid(scalar(connection, "SELECT installation_id FROM installation_identity WHERE singleton_id = 1"),
				"local installation identity");
	}

	private boolean exists(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			bind(statement, values);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next();
			}
		}
	}

	private String scalar(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			bind(statement, values);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					throw new SQLException("Required disposal-planning evidence is missing");
				}
				String value = rows.getString(1);
				if (rows.next()) {
					throw new SQLException("Duplicate disposal-planning evidence");
				}
				return value;
			}
		}
	}

	private void bind(PreparedStatement statement, Object... values) throws SQLException {
		for (int index = 0; index < values.length; index++) {
			statement.setObject(index + 1, values[index]);
		}
	}

	private UUID parseUuid(String value, String description) throws SQLException {
		try {
			UUID uuid = UUID.fromString(value);
			if (!uuid.toString().equals(value)) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return uuid;
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new SQLException("Invalid " + description, failure);
		}
	}

	private String planHash(SessionEvidence session, List<Entity> entities, List<RetiredEntity> retiredEntities,
			List<Asset> assets) {
		MessageDigest digest = newDigest();
		update(digest,
				"CONTRIBUTOR_ASSIGNMENT\t" + session.reason() + "\t" + session.assignmentId() + "\t"
						+ session.assignmentPackageId() + "\t" + session.assignmentArchiveSha256() + "\t"
						+ session.returnPackageId() + "\n");
		for (Entity entity : entities) {
			update(digest,
					"E\t" + entity.type() + "\t" + entity.localId() + "\t" + entity.portableId() + "\t"
							+ entity.revisionId() + "\t" + entity.authorityInstallationId() + "\t"
							+ entity.reattachable() + "\n");
		}
		for (RetiredEntity entity : retiredEntities) {
			update(digest, "R\t" + entity.type() + "\t" + entity.formerLocalId() + "\t" + entity.portableId() + "\t"
					+ entity.latestRevisionId() + "\t" + entity.authorityInstallationId() + "\n");
		}
		for (Asset asset : assets) {
			update(digest, "A\t" + asset.relativePath() + "\t" + asset.sha256() + "\t" + asset.kind() + "\n");
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private String sha256(Path path) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private String sha256(byte[] contents) {
		return HexFormat.of().formatHex(newDigest().digest(contents));
	}

	private void update(MessageDigest digest, String text) {
		digest.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	record Plan(UUID assignmentId, Reason reason, UUID returnPackageId, UUID assignmentPackageId,
			String assignmentArchiveSha256, String sha256, List<Entity> entities, List<RetiredEntity> retiredEntities,
			List<Asset> assets) {

		Plan {
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(reason, "reason");
			if (reason == Reason.SUBMITTED) {
				Objects.requireNonNull(returnPackageId, "returnPackageId");
			}
			Objects.requireNonNull(assignmentPackageId, "assignmentPackageId");
			Objects.requireNonNull(assignmentArchiveSha256, "assignmentArchiveSha256");
			Objects.requireNonNull(sha256, "sha256");
			entities = List.copyOf(entities);
			retiredEntities = List.copyOf(retiredEntities);
			assets = List.copyOf(assets);
		}
	}

	record Entity(EntityType type, long localId, UUID portableId, UUID revisionId, UUID authorityInstallationId,
			boolean reattachable) {
	}

	record RetiredEntity(EntityType type, long formerLocalId, UUID portableId, UUID latestRevisionId,
			UUID authorityInstallationId) {
	}

	record Asset(String relativePath, String sha256, String kind) {
	}

	private record SessionEvidence(UUID assignmentId, long subjectLocalId, long examLocalId,
			long currentSyllabusLocalId, Long legacySyllabusLocalId, UUID masterInstallationId, byte[] baselineRecords,
			byte[] baselineAssets, UUID returnPackageId, UUID assignmentPackageId, String assignmentArchiveSha256,
			boolean removed, Reason reason) {
	}

	private record BaselineIdentity(EntityType type, UUID portableId, UUID revisionId, ExchangeRecord record) {
	}

	private record BaselineKey(EntityType type, UUID portableId) {
	}

	enum Reason {
		SUBMITTED, REMOVED_UNCHANGED
	}

	private record EntitySelection(EntityType type, String sql, int parameterCount) {

		private EntitySelection(EntityType type, String sql) {
			this(type, sql, 1);
		}
	}
}
