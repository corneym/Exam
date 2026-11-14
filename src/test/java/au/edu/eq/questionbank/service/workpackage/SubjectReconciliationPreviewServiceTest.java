package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;

/**
 * True two-database integration tests for read-only Subject reconciliation.
 *
 * <p>
 * Each fixture initialises its own current SQLite schema, independent portable
 * installation identities and Subject-first managed PDF directory. Identical
 * local integer IDs deliberately refer to independently created portable UUIDs.
 * Assertions compare real exported, validated archives rather than hand-crafted
 * JSON objects, and recheck both source databases and original PDF bytes.
 */
class SubjectReconciliationPreviewServiceTest {

	@TempDir
	Path temporary;

	/**
	 * Confirms a changed source document is not silently aliased by the matching
	 * Booklet name.
	 */
	@Test
	void changedPdfBytesBlockBookletEquivalence() throws Exception {
		Fixture master = seed("master-pdf", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-pdf", "Unit 1", "1", "Original question", false);
		Files.writeString(incoming.pdf(), "DIFFERENT SOURCE PDF", StandardCharsets.UTF_8);
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.CONFLICT, single(result, "EXAM_BOOKLET").classification());
		assertTrue(
				result.assetEvidence().stream().anyMatch(asset -> asset.classification() == Classification.CONFLICT));
		assertEquals("CAPTURE PDF", Files.readString(master.pdf(), StandardCharsets.UTF_8));
	}

	/**
	 * Confirms a new Question beneath an identical Booklet remains a compatible
	 * addition.
	 */
	@Test
	void compatibleIndependentCaptureIsProposedWithoutReplacingMasterQuestion() throws Exception {
		Fixture master = seed("master-addition", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-addition", "Unit 1", "2", "Another question", false);
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.IDENTICAL, single(result, "EXAM_BOOKLET").classification());
		assertEquals(Classification.COMPATIBLE_ADDITION, single(result, "QUESTION").classification());
		assertEquals(null, single(result, "QUESTION").masterId());
		assertEquals("Original question", questionText(master.database()));
	}

	/**
	 * Confirms the complete Biology 2019 hierarchy must match, not merely the
	 * syllabus title.
	 */
	@Test
	void differentCurriculumStructuresRemainConflicts() throws Exception {
		Fixture master = seed("master-structure", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-structure", "Unit 1", "1", "Original question", true);
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.CONFLICT, single(result, "SYLLABUS_VERSION").classification());
		assertTrue(result.forType("CURRICULUM_NODE").stream()
				.anyMatch(proposal -> proposal.classification() != Classification.IDENTICAL));
	}

	@Test
	void differentImageBytesRemainConflicts() throws Exception {
		Fixture master = seed("master-different-image", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-different-image", "Unit 1", "1", "Original question", false);
		addQuestionImage(master, png(0x336699));
		addQuestionImage(incoming, png(0x993366));
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.CONFLICT, single(result, "QUESTION").classification());
	}

	/**
	 * Confirms the same byte-identical PDF does not erase a differently captured
	 * Question.
	 */
	@Test
	void duplicatePdfsWithDifferentQuestionContentAreReviewable() throws Exception {
		Fixture master = seed("master-question", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-question", "Unit 1", "1", "Revised question", false);
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.IDENTICAL, single(result, "EXAM_BOOKLET").classification());
		assertEquals(Classification.CONFLICT, single(result, "QUESTION").classification());
		assertEquals(1, questionCount(master.database()));
		assertEquals("Original question", questionText(master.database()));
	}

	@Test
	void identicalImageBearingCapturesAreIdentical() throws Exception {
		Fixture master = seed("master-images", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-images", "Unit 1", "1", "Original question", false);
		byte[] image = png(0x336699);
		addQuestionImage(master, image);
		addQuestionImage(incoming, image);
		addContextImage(master, image);
		addContextImage(incoming, image);
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.IDENTICAL, single(result, "SHARED_QUESTION_CONTEXT").classification());
		assertEquals(Classification.IDENTICAL, single(result, "QUESTION").classification());
		assertEquals(Classification.IDENTICAL, single(result, "QUESTION_IMAGE").classification());
	}

	/**
	 * Confirms two installations with colliding integer IDs can still match logical
	 * records.
	 */
	@Test
	void independentlyInitialisedDatabasesMatchByEvidenceNotSqliteIds() throws Exception {
		Fixture master = seed("master", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming", "Unit 1", "1", "Original question", false);
		var masterIdentity = new SqlitePortableIdentityRepository(master.database())
				.requireByLocalId(EntityType.QUESTION, 81).portableId();
		var incomingIdentity = new SqlitePortableIdentityRepository(incoming.database())
				.requireByLocalId(EntityType.QUESTION, 81).portableId();
		assertNotEquals(masterIdentity, incomingIdentity);
		byte[] masterPdf = Files.readAllBytes(master.pdf());
		byte[] incomingPdf = Files.readAllBytes(incoming.pdf());
		int masterCount = questionCount(master.database());
		int incomingCount = questionCount(incoming.database());
		var result = service().preview(export(master), export(incoming));
		assertEquals(Classification.IDENTICAL, single(result, "SUBJECT").classification());
		assertEquals(Classification.IDENTICAL, single(result, "SYLLABUS_VERSION").classification());
		assertEquals(Classification.IDENTICAL, single(result, "EXAM").classification());
		assertEquals(Classification.IDENTICAL, single(result, "EXAM_BOOKLET").classification());
		assertEquals(Classification.IDENTICAL, single(result, "QUESTION").classification());
		assertTrue(single(result, "QUESTION").proposesAlias());
		assertEquals(1, result.assetEvidence().size());
		assertEquals(Classification.IDENTICAL, result.assetEvidence().getFirst().classification());
		assertEquals(masterCount, questionCount(master.database()));
		assertEquals(incomingCount, questionCount(incoming.database()));
		assertArrayEquals(masterPdf, Files.readAllBytes(master.pdf()));
		assertArrayEquals(incomingPdf, Files.readAllBytes(incoming.pdf()));
	}

	@Test
	void readsEveryDeclaredRecordFile() throws Exception {
		Fixture master = seed("master-multifile", "Unit 1", "1", "Original question", false);
		Fixture incoming = seed("incoming-multifile", "Unit 1", "1", "Original question", false);
		Path masterArchive = export(master);
		Path incomingArchive = splitRecordFiles(export(incoming));
		var result = service().preview(masterArchive, incomingArchive);
		assertEquals(Classification.IDENTICAL, single(result, "QUESTION").classification());
		assertEquals(1, result.forType("QUESTION").size());
		assertEquals(1, questionCount(incoming.database()));
	}

	/**
	 * Confirms package validation and Subject identity are enforced before
	 * matching.
	 */
	@Test
	void rejectsAnUntrustedArchiveRatherThanReadingUnvalidatedRecords() throws Exception {
		Fixture master = seed("trusted", "Unit 1", "1", "Original question", false);
		Path fake = temporary.resolve("fake.examwork");
		Files.writeString(fake, "not a ZIP", StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> service().preview(export(master), fake));
		assertFalse(Files.size(fake) == 0);
	}

	private void addContextImage(Fixture fixture, byte[] image) throws Exception {
		try (Connection connection = fixture.database().openConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO shared_question_contexts
						    (id, booklet_id, context_label)
						VALUES (121, 61, 'Shared stimulus')
						""");
				statement.execute("""
						UPDATE questions
						SET shared_context_id = 121
						WHERE id = 81
						""");
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO shared_question_context_images
					    (shared_context_id, image_order, image_png)
					VALUES (121, 0, ?)
					""")) {
				statement.setBytes(1, image);
				statement.executeUpdate();
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO shared_question_context_content_parts
						    (shared_context_id, content_order,
						     content_type, image_order)
						VALUES (121, 0, 'IMAGE', 0)
						""");
			}
		}
	}

	private void addQuestionImage(Fixture fixture, byte[] image) throws Exception {
		try (Connection connection = fixture.database().openConnection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO question_images
					    (id, question_id, image_png)
					VALUES (131, 81, ?)
					""")) {
				statement.setBytes(1, image);
				statement.executeUpdate();
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO question_content_parts
						    (question_id, content_order,
						     content_type, image_id)
						VALUES (81, 1, 'IMAGE', 131)
						""");
			}
		}
	}

	/** Produces the actual validated portable snapshot from the given database. */
	private Path export(Fixture fixture) throws Exception {
		new SubjectSnapshotExporter(fixture.database(), fixture.layout()).export(1, fixture.archive());
		return fixture.archive();
	}

	private byte[] png(int rgb) throws Exception {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, rgb);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "png", output));
		return output.toByteArray();
	}

	/** Counts persisted Questions without using the exporter cache. */
	private int questionCount(SqliteDatabase database) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT count(*) FROM questions")) {
			result.next();
			return result.getInt(1);
		}
	}

	/** Reads the authoritative Question text after a read-only preview. */
	private String questionText(SqliteDatabase database) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT question_text FROM questions WHERE id=81")) {
			assertTrue(result.next());
			return result.getString(1);
		}
	}

	/**
	 * Creates a genuine current-schema SQLite fixture with independent installation
	 * identity.
	 */
	private Fixture seed(String name, String unitName, String code, String questionText, boolean extraCurriculumNode)
			throws Exception {
		Path root = temporary.resolve(name);
		Files.createDirectories(root);
		SqliteDatabase database = new SqliteDatabase(root.resolve("questionbank.db"));
		database.initialiseSchema();
		ManagedDataLayout layout = new ManagedDataLayout(root);
		Path pdf = layout.resolve("subjects/Biology/exams/questions.pdf");
		Files.createDirectories(pdf.getParent());
		Files.writeString(pdf, "CAPTURE PDF", StandardCharsets.UTF_8);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects(id,subject_name) VALUES(1,'Biology')");
			statement.execute("""
					INSERT INTO syllabus_versions(id,subject_id,syllabus_name,is_current)
					VALUES(11,1,'Biology 2019',1)
					""");
			try (PreparedStatement insert = connection.prepareStatement("""
					INSERT INTO curriculum_nodes(id,syllabus_version_id,parent_id,curriculum_code,
					    curriculum_name,curriculum_level,display_order)
					VALUES(101,11,NULL,'UNIT1',?,'UNIT',0)
					""")) {
				insert.setString(1, unitName);
				insert.executeUpdate();
			}
			if (extraCurriculumNode) {
				statement.execute("""
						INSERT INTO curriculum_nodes(id,syllabus_version_id,parent_id,curriculum_code,
						    curriculum_name,curriculum_level,display_order)
						VALUES(102,11,101,'TOPIC1','Different topic','TOPIC',1)
						""");
			}
			statement.execute("INSERT INTO exam_providers(id,provider_name) VALUES(1,'QCAA')");
			statement.execute("""
					INSERT INTO source_documents(id,relative_path)
					VALUES(51,'subjects/Biology/exams/questions.pdf')
					""");
			statement.execute("""
					INSERT INTO exams(id,subject_id,provider_id,exam_year,exam_name,
					    capture_state,expected_question_booklet_count)
					VALUES(31,1,1,2024,'External Assessment','ACTIVE',1)
					""");
			statement.execute("""
					INSERT INTO exam_booklets(id,exam_id,source_document_id,booklet_name,legacy_paper_code)
					VALUES(61,31,51,'Paper 1','1')
					""");
			try (PreparedStatement insert = connection.prepareStatement("""
					INSERT INTO questions(id,booklet_id,classification_node_id,question_code,
					    question_text,marks,shared_context_capture_required)
					VALUES(81,61,101,?,?,2,0)
					""")) {
				insert.setString(1, code);
				insert.setString(2, questionText);
				insert.executeUpdate();
			}
			statement.execute("""
					INSERT INTO question_regions(question_id,region_order,booklet_id,page_number,x,y,width,height)
					VALUES(81,0,61,2,0.1,0.2,0.5,0.3)
					""");
			statement.execute("""
					INSERT INTO question_content_parts(question_id,content_order,content_type,region_order)
					VALUES(81,0,'PDF_REGION',0)
					""");
		}
		return new Fixture(database, layout, pdf, root.resolve(name + ".examwork"));
	}

	/** Creates the production two-way preview service. */
	private SubjectReconciliationPreviewService service() {
		return new SubjectReconciliationPreviewService();
	}

	/** Selects the single incoming proposal of a portable entity type. */
	private Proposal single(SubjectReconciliationPreviewService.Result result, String type) {
		assertEquals(1, result.forType(type).size());
		return result.forType(type).getFirst();
	}

	private Path splitRecordFiles(Path original) throws Exception {
		ExamWorkArchiveValidator validator = new ExamWorkArchiveValidator();
		ExamWorkManifest manifest = validator.validate(original, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		Map<String, byte[]> contents = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(original.toFile())) {
			for (ExamWorkManifest.Entry entry : manifest.entries()) {
				try (var input = zip.getInputStream(zip.getEntry(entry.path()))) {
					contents.put(entry.path(), input.readAllBytes());
				}
			}
		}
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		String originalPath = "records/subject-graph.jsonl";
		String additionalPath = "records/questions.jsonl";
		List<ExchangeRecord> originalRecords;
		try (var input = new ByteArrayInputStream(contents.get(originalPath))) {
			originalRecords = codec.readRecords(input);
		}
		List<ExchangeRecord> questions = originalRecords.stream()
				.filter(record -> "QUESTION".equals(record.recordType())).toList();
		List<ExchangeRecord> otherRecords = originalRecords.stream()
				.filter(record -> !"QUESTION".equals(record.recordType())).toList();
		assertEquals(1, questions.size());
		ByteArrayOutputStream first = new ByteArrayOutputStream();
		ByteArrayOutputStream second = new ByteArrayOutputStream();
		codec.writeRecords(otherRecords, first);
		codec.writeRecords(questions, second);
		contents.put(originalPath, first.toByteArray());
		contents.put(additionalPath, second.toByteArray());
		List<ExamWorkManifest.Entry> entries = new ArrayList<>();
		for (ExamWorkManifest.Entry entry : manifest.entries()) {
			byte[] bytes = contents.get(entry.path());
			entries.add(new ExamWorkManifest.Entry(entry.path(), entry.role(), bytes.length,
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
		}
		byte[] additional = contents.get(additionalPath);
		entries.add(new ExamWorkManifest.Entry(additionalPath, ExamWorkManifest.EntryRole.RECORDS, additional.length,
				HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(additional))));
		ExamWorkManifest splitManifest = new ExamWorkManifest(manifest.formatVersion(), manifest.packageId(),
				manifest.packageType(), manifest.createdAt(), manifest.originInstallationId(),
				manifest.intendedMasterInstallationId(), manifest.subjectId(), manifest.subjectName(),
				manifest.assignmentId(), manifest.baselineSnapshotId(), manifest.producerApplicationVersion(),
				manifest.minimumReaderApplicationVersion(), manifest.producerSchemaVersion(),
				manifest.minimumReaderSchemaVersion(), manifest.scope(), entries);
		Map<String, ExamWorkArchiveWriter.EntrySource> sources = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> item : contents.entrySet()) {
			byte[] bytes = item.getValue();
			sources.put(item.getKey(), () -> new ByteArrayInputStream(bytes));
		}
		Path destination = temporary.resolve("split-" + UUID.randomUUID() + ".examwork");
		try (var output = Files.newOutputStream(destination)) {
			new ExamWorkArchiveWriter().write(splitManifest, sources, output);
		}
		validator.validate(destination, ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
		return destination;
	}

	/** Isolates the two independent database and filesystem fixture locations. */
	private record Fixture(SqliteDatabase database, ManagedDataLayout layout, Path pdf, Path archive) {
	}
}
