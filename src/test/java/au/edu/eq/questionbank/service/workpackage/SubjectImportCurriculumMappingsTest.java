package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Operation;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

/**
 * Exercises additive mappings through real independently exported Subject
 * packages.
 */
class SubjectImportCurriculumMappingsTest {

	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2 })
	void importsMappingWithTwoOneOrNoNewEndpointsAndReplaysWithoutDuplicates(int existingVersions) throws Exception {
		Fixture fixture = fixture(existingVersions);
		Result preview = preview(fixture);
		Proposal mapping = mapping(preview);
		assertEquals(Classification.COMPATIBLE_ADDITION, mapping.classification());
		assertEquals(null, mapping.masterId());
		assertEquals(3 * (2 - existingVersions), preview.forType("CURRICULUM_NODE").stream()
				.filter(node -> node.classification() == Classification.COMPATIBLE_ADDITION).count());
		SubjectImportApprovalPlan plan = plan(fixture, acceptAll(preview));
		assertEquals(Operation.INSERT_RECORD,
				plan.actions().stream().filter(action -> mapping.incomingId().equals(action.incomingId())).findFirst()
						.orElseThrow().operation());
		Map<String, List<String>> unrelatedBefore = unrelated(fixture);
		var coordinator = new SubjectImportCoordinator(fixture.master());
		var result = coordinator.execute(1, plan, fixture.incomingArchive(), directory.resolve("backups"),
				"Approved new Biology curriculum mapping");
		assertTrue(result.applied());
		assertFalse(result.replay());
		assertEquals(unrelatedBefore, unrelated(fixture));
		assertBiologyMapping(fixture);
		assertNotEquals(mapping.incomingId(), new SqlitePortableIdentityRepository(fixture.masterDb())
				.requireByLocalId(EntityType.CURRICULUM_MAPPING, mappingId(fixture)).portableId());
		assertEquals(1, rows(fixture.masterDb(), "SELECT approval_note FROM portable_identity_aliases "
				+ "WHERE entity_type = 'CURRICULUM_MAPPING' AND approval_note = 'Reviewed curriculum relationship'")
				.size());
		assertEquals(1, rows(fixture.masterDb(), "SELECT * FROM subject_import_packages").size());
		Map<String, List<String>> afterImport = databaseState(fixture.masterDb());
		assertTrue(coordinator
				.execute(1, plan, fixture.incomingArchive(), directory.resolve("backups"), "Replay approved mapping")
				.replay());
		assertEquals(afterImport, databaseState(fixture.masterDb()));

		// A freshly exported package has a new package identity. Endpoint evidence
		// must still find the existing mapping instead of proposing another insert.
		export(fixture.master(), fixture.masterArchive());
		export(fixture.source(), fixture.incomingArchive());
		Result repeatedPreview = preview(fixture);
		assertEquals(Classification.IDENTICAL, mapping(repeatedPreview).classification());
		coordinator.execute(1, plan(fixture, acceptAll(repeatedPreview)), fixture.incomingArchive(),
				directory.resolve("backups"), "Reviewed re-export of the same mapping");
		assertBiologyMapping(fixture);
		assertEquals(unrelatedBefore, unrelated(fixture));
	}

	@Test
	void retainsAnExistingIdenticalMapping() throws Exception {
		Fixture fixture = fixture(2);
		addMapping(fixture.masterDb());
		export(fixture.master(), fixture.masterArchive());
		Result preview = preview(fixture);
		assertEquals(Classification.IDENTICAL, mapping(preview).classification());
		new SubjectImportCoordinator(fixture.master()).execute(1, plan(fixture, acceptAll(preview)),
				fixture.incomingArchive(), directory.resolve("backups"), "Keep existing mapping");
		assertBiologyMapping(fixture);
	}

	@Test
	void differingMappingMetadataCannotBeSilentlyOverwritten() throws Exception {
		Fixture fixture = fixture(2);
		addMapping(fixture.masterDb());
		execute(fixture.masterDb(), "UPDATE curriculum_mappings SET mapping_status = 'SUGGESTED' WHERE id = 1");
		export(fixture.master(), fixture.masterArchive());
		Result preview = preview(fixture);
		assertEquals(Classification.CONFLICT, mapping(preview).classification());
		Map<String, List<String>> before = databaseState(fixture.masterDb());
		assertThrows(IllegalStateException.class, () -> plan(fixture, acceptAll(preview)));
		assertEquals(before, databaseState(fixture.masterDb()));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void conflictingOrAmbiguousEndpointsBlockMappingApproval(boolean ambiguous) throws Exception {
		Fixture fixture = fixture(1);
		if (ambiguous) {

			// SQLite permits distinct raw codes which reconcile to the same normalised
			// path.
			execute(fixture.sourceDb(), """
					INSERT INTO curriculum_nodes (id, syllabus_version_id, parent_id, curriculum_code,
					    curriculum_name, curriculum_level, display_order)
					VALUES (114, 11, 112, ' s1 ', 'Duplicate subtopic', 'SUBTOPIC', 1)
					""");
		} else {
			execute(fixture.sourceDb(),
					"UPDATE curriculum_nodes SET curriculum_name = 'Different content' WHERE id = 113");
		}
		export(fixture.source(), fixture.incomingArchive());
		Result preview = preview(fixture);
		assertEquals(Classification.CONFLICT, mapping(preview).classification());
		assertEquals(null, mapping(preview).masterId());
		Map<String, List<String>> before = databaseState(fixture.masterDb());
		assertThrows(IllegalStateException.class, () -> plan(fixture, acceptAll(preview)));
		assertEquals(before, databaseState(fixture.masterDb()));
	}

	@Test
	void pendingMappingRequiresAnExplicitDecision() throws Exception {
		Fixture fixture = fixture(0);
		Result preview = preview(fixture);
		SubjectReconciliationReview review = new SubjectReconciliationReview(preview);
		for (var proposal : preview.proposals()) {
			if (!proposal.recordType().equals("CURRICULUM_MAPPING")) {
				review.decide(key(proposal), Decision.ACCEPT, "Reviewed curriculum");
			}
		}
		assertThrows(IllegalStateException.class, () -> plan(fixture, review));
	}

	@Test
	void rejectingMappingKeepsItsAcceptedNodesAndRecordsTheDecision() throws Exception {
		Fixture fixture = fixture(0);
		Result preview = preview(fixture);
		SubjectReconciliationReview review = acceptAll(preview);
		review.decide(key(mapping(preview)), Decision.REJECT, "Coordinator declined this mapping");
		SubjectImportApprovalPlan plan = plan(fixture, review);
		assertEquals(Operation.SKIP, plan.actions().stream()
				.filter(action -> action.key().equals(key(mapping(preview)))).findFirst().orElseThrow().operation());
		new SubjectImportCoordinator(fixture.master()).execute(1, plan, fixture.incomingArchive(),
				directory.resolve("backups"), "Import curriculum without declined mapping");
		assertEquals(6, rows(fixture.masterDb(), "SELECT n.* FROM curriculum_nodes n JOIN syllabus_versions v "
				+ "ON v.id = n.syllabus_version_id WHERE v.subject_id = 1").size());
		assertEquals(0, biologyMappings(fixture).size());
		assertEquals(1, rows(fixture.masterDb(), "SELECT * FROM subject_import_packages").size());
	}

	@ParameterizedTest
	@ValueSource(ints = { 113, 123 })
	void rejectedSourceOrTargetPreventsDependentMappingApproval(int rejectedNode) throws Exception {
		Fixture fixture = fixture(0);
		Result preview = preview(fixture);
		SubjectReconciliationReview review = acceptAll(preview);
		UUID incomingId = new SqlitePortableIdentityRepository(fixture.sourceDb())
				.requireByLocalId(EntityType.CURRICULUM_NODE, rejectedNode).portableId();
		var endpointKey = SubjectReconciliationReview.recordKey("CURRICULUM_NODE", incomingId);
		assertThrows(IllegalStateException.class,
				() -> review.decide(endpointKey, Decision.REJECT, "Endpoint declined"));
		review.decide(key(mapping(preview)), Decision.REJECT, "Decline dependent mapping first");
		review.decide(endpointKey, Decision.REJECT, "Endpoint declined");
		assertThrows(IllegalStateException.class,
				() -> review.decide(key(mapping(preview)), Decision.ACCEPT, "Mapping requires both endpoints"));
		assertEquals(Operation.SKIP, plan(fixture, review).actions().stream()
				.filter(action -> action.key().equals(key(mapping(preview)))).findFirst().orElseThrow().operation());
		assertEquals(0, biologyMappings(fixture).size());
	}

	@Test
	void staleApprovalCannotInsertMappingOrLedger() throws Exception {
		Fixture fixture = fixture(1);
		SubjectImportApprovalPlan plan = plan(fixture, acceptAll(preview(fixture)));
		execute(fixture.masterDb(),
				"UPDATE curriculum_nodes SET curriculum_name = 'Edited after review' WHERE id = 113");
		Map<String, List<String>> before = databaseState(fixture.masterDb());
		assertThrows(IllegalStateException.class, () -> new SubjectImportCoordinator(fixture.master()).execute(1, plan,
				fixture.incomingArchive(), directory.resolve("backups"), "Stale mapping approval"));
		assertEquals(before, databaseState(fixture.masterDb()));
	}

	@Test
	void rollingBackAfterMappingInsertionRemovesNodesMappingsAndAliasesTogether() throws Exception {
		Fixture fixture = fixture(0);
		SubjectImportApprovalPlan plan = plan(fixture, acceptAll(preview(fixture)));

		// This package has no assets. Decode a private archive copy, then exercise
		// the writer's caller-owned transaction after every graph row is inserted.
		Path stagedArchive = Files.copy(fixture.incomingArchive(), directory.resolve("private.examwork"));
		StagedPackage staged = new StagedPackage(plan.reviewedIncoming().packageId(), plan.approvalSha256(), directory,
				stagedArchive, List.of());
		SubjectImportGraphWriter writer = new SubjectImportGraphWriter(fixture.masterDb());
		var decoded = writer.decode(staged, plan);
		Map<String, List<String>> before = databaseState(fixture.masterDb());
		try (Connection connection = fixture.masterDb().openConnection()) {
			connection.setAutoCommit(false);
			try {
				writer.apply(connection, plan, staged, decoded, "Reviewed additive mapping");
				try (Statement statement = connection.createStatement();
						ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM curriculum_mappings")) {
					assertTrue(result.next());
					assertEquals(2, result.getInt(1), "Both the unrelated and new mappings must exist before rollback");
				}
			} finally {
				connection.rollback();
			}
		}
		assertEquals(before, databaseState(fixture.masterDb()));
	}

	private Fixture fixture(int existingVersions) throws Exception {
		ApplicationConfig source = initialise("source");
		ApplicationConfig master = initialise("master");
		SqliteDatabase sourceDb = new SqliteDatabase(source.databasePath());
		SqliteDatabase masterDb = new SqliteDatabase(master.databasePath());
		for (int version = 11; version <= 12; version++) {
			addVersion(sourceDb, version, 1);
			if (version - 11 < existingVersions) {
				addVersion(masterDb, version, 1);
			}
		}
		addMapping(sourceDb);
		execute(masterDb, "INSERT INTO subjects (id, subject_name) VALUES (2, 'Chemistry')");
		addVersion(masterDb, 21, 2);
		addVersion(masterDb, 22, 2);
		execute(masterDb, "INSERT INTO curriculum_mappings (id, source_node_id, target_node_id, mapping_status) "
				+ "VALUES (999, 213, 223, 'SUGGESTED')");
		Path masterArchive = directory.resolve("master.examwork");
		Path incomingArchive = directory.resolve("incoming.examwork");
		export(master, masterArchive);
		export(source, incomingArchive);
		return new Fixture(master, source, masterDb, sourceDb, masterArchive, incomingArchive);
	}

	private ApplicationConfig initialise(String name) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve(name));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		execute(database, "INSERT INTO subjects (id, subject_name) VALUES (1, 'Biology')");
		return config;
	}

	private void addVersion(SqliteDatabase database, int version, int subject) throws Exception {
		execute(database,
				"INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current) VALUES (" + version + ", "
						+ subject + ", '" + (version % 10 == 1 ? "2019" : "2025") + "', 0)",
				"INSERT INTO curriculum_nodes (id, syllabus_version_id, parent_id, curriculum_code, "
						+ "curriculum_name, curriculum_level, display_order) VALUES " + "(" + (version * 10 + 1) + ", "
						+ version + ", NULL, 'U1', 'Unit', 'UNIT', 0)," + "(" + (version * 10 + 2) + ", " + version
						+ ", " + (version * 10 + 1) + ", 'T1', 'Topic', 'TOPIC', 0)," + "(" + (version * 10 + 3) + ", "
						+ version + ", " + (version * 10 + 2) + ", 'S1', 'Subtopic', 'SUBTOPIC', 0)");
	}

	private void addMapping(SqliteDatabase database) throws Exception {
		execute(database, "INSERT INTO curriculum_mappings (id, source_node_id, target_node_id, mapping_status) "
				+ "VALUES (1, 113, 123, 'CONFIRMED')");
	}

	private void export(ApplicationConfig config, Path archive) throws Exception {

		// Export deliberately creates a fresh package for each subsequent review.
		Files.deleteIfExists(archive);
		new SubjectSnapshotExporter(new SqliteDatabase(config.databasePath()), new ManagedDataLayout(config.dataRoot()))
				.export(1, archive);
	}

	private Result preview(Fixture fixture) throws Exception {
		return new SubjectReconciliationPreviewService().preview(fixture.masterArchive(), fixture.incomingArchive());
	}

	private Proposal mapping(Result preview) {
		assertEquals(1, preview.forType("CURRICULUM_MAPPING").size());
		return preview.forType("CURRICULUM_MAPPING").getFirst();
	}

	private SubjectReconciliationReview.Key key(Proposal proposal) {
		return SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
	}

	private SubjectReconciliationReview acceptAll(Result preview) {
		SubjectReconciliationReview review = new SubjectReconciliationReview(preview);
		for (var key : review.reviewKeys()) {
			review.decide(key, Decision.ACCEPT, "Reviewed curriculum relationship");
		}
		return review;
	}

	private SubjectImportApprovalPlan plan(Fixture fixture, SubjectReconciliationReview review) throws Exception {
		var fingerprints = new SubjectImportSnapshotFingerprint();
		return SubjectImportApprovalPlan.prepare(review, fingerprints.inspect(fixture.masterArchive()),
				fingerprints.inspect(fixture.incomingArchive()));
	}

	private void assertBiologyMapping(Fixture fixture) throws Exception {
		assertEquals(List.of("2019|2025|S1|S1|CONFIRMED"), biologyMappings(fixture));
		fixture.masterDb().verifyIntegrity();
	}

	private List<String> biologyMappings(Fixture fixture) throws Exception {
		return rows(fixture.masterDb(), """
				SELECT sv.syllabus_name, tv.syllabus_name, s.curriculum_code, t.curriculum_code, m.mapping_status
				FROM curriculum_mappings m JOIN curriculum_nodes s ON s.id = m.source_node_id
				JOIN curriculum_nodes t ON t.id = m.target_node_id
				JOIN syllabus_versions sv ON sv.id = s.syllabus_version_id
				JOIN syllabus_versions tv ON tv.id = t.syllabus_version_id
				WHERE sv.subject_id = 1 AND tv.subject_id = 1
				""");
	}

	private long mappingId(Fixture fixture) throws Exception {
		return Long
				.parseLong(rows(fixture.masterDb(), "SELECT id FROM curriculum_mappings WHERE id <> 999").getFirst());
	}

	private Map<String, List<String>> unrelated(Fixture fixture) throws Exception {
		return Map.of("subject", rows(fixture.masterDb(), "SELECT * FROM subjects WHERE id = 2"), "versions",
				rows(fixture.masterDb(), "SELECT * FROM syllabus_versions WHERE subject_id = 2"), "nodes",
				rows(fixture.masterDb(), "SELECT * FROM curriculum_nodes WHERE syllabus_version_id IN (21, 22)"),
				"mappings", rows(fixture.masterDb(), "SELECT * FROM curriculum_mappings WHERE id = 999"));
	}

	private Map<String, List<String>> databaseState(SqliteDatabase database) throws Exception {
		Map<String, List<String>> state = new TreeMap<>();
		for (String table : rows(database, "SELECT name FROM sqlite_master WHERE type = 'table'")) {
			state.put(table, rows(database, "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\""));
		}
		return state;
	}

	private List<String> rows(SqliteDatabase database, String sql) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			List<String> rows = new ArrayList<>();
			while (result.next()) {
				List<String> fields = new ArrayList<>();
				for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
					fields.add(String.valueOf(result.getString(i)));
				}
				rows.add(String.join("|", fields));
			}
			return rows.stream().sorted().toList();
		}
	}

	private void execute(SqliteDatabase database, String... sql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			for (String command : sql) {
				statement.execute(command);
			}
		}
	}

	private record Fixture(ApplicationConfig master, ApplicationConfig source, SqliteDatabase masterDb,
			SqliteDatabase sourceDb, Path masterArchive, Path incomingArchive) {
	}
}
