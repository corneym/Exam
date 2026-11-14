package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

/**
 * Disposable two-source Biology consolidation and idempotence regression tests.
 *
 * <p>
 * Both source and master contain an independent Biology installation. The
 * master additionally contains Chemistry, Physics and Engineering Subjects,
 * which must remain unchanged by importing a complete mixed-content Biology
 * examination, booklet, Question, Answer, image and Shared Context graph.
 */
class SubjectImportCoordinatorIntegrationTest {

	private static final byte[] PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO+/WZcAAAAASUVORK5CYII=");
	@TempDir
	Path directory;

	/**
	 * Exercises full graph insertion, PDF and embedded PNG persistence, independent
	 * Subject preservation, origin aliases and a duplicate replay.
	 */
	@Test
	void consolidatesTwoBiologySourcesWithoutChangingOtherSubjects() throws Exception {
		Fixture fixture = fixture();
		SubjectImportCoordinator service = new SubjectImportCoordinator(fixture.master());
		var result = service.execute(1, fixture.plan(), fixture.incoming(), directory.resolve("backups"),
				"Coordinator approved independent Biology consolidation");
		assertTrue(result.applied());
		assertFalse(result.replay());
		assertTrue(Files.isRegularFile(Path.of(result.backupArchivePath())));
		Path questions = fixture.master().dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		assertArrayEquals("Biology question document".getBytes(), Files.readAllBytes(questions));
		SqliteDatabase master = new SqliteDatabase(fixture.master().databasePath());
		master.verifyIntegrity();
		try (Connection connection = master.openConnection()) {
			assertEquals(4, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM exams WHERE subject_id = 1"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answers"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM question_images"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM question_content_parts"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM shared_question_context_content_parts"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answer_regions"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM booklet_applicability_reviews"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertTrue(count(connection, "SELECT COUNT(*) FROM portable_identity_aliases") >= 11);
			assertEquals(count(connection, "SELECT COUNT(*) FROM portable_identity_aliases"), count(connection, """
					SELECT COUNT(*)
					FROM portable_identity_aliases
					WHERE approval_note =
					    'Coordinator approved independent Biology consolidation'
					"""),
					"Aliases without individual explanations must retain " + "the coordinator's overall approval note");
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT image_png FROM question_images")) {
				assertTrue(resultSet.next());
				assertArrayEquals(PNG, resultSet.getBytes(1));
			}
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement
							.executeQuery("SELECT image_png FROM shared_question_context_images")) {
				assertTrue(resultSet.next());
				assertArrayEquals(PNG, resultSet.getBytes(1));
			}
		}
		assertTrue(
				new SqliteSubjectImportLedger(master).find(fixture.plan().reviewedIncoming().packageId()).isPresent());

		// A package replay must be a no-op, including no duplicate Question or files.
		var repeated = service.execute(1, fixture.plan(), fixture.incoming(), directory.resolve("backups"),
				"Repeated import must not produce duplicate data");
		assertTrue(repeated.applied());
		assertTrue(repeated.replay());
		try (Connection connection = master.openConnection()) {
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answers"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
		}
	}

	/**
	 * Verifies that the contributor database and exported package retain Question,
	 * Shared Context, Answer and Booklet review child rows.
	 */
	@Test
	void contributorSnapshotPreservesOrderedContentBeforeImport() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase source = new SqliteDatabase(directory.resolve("source/questionbank.db"));
		try (Connection connection = source.openConnection()) {
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM question_content_parts"),
					"Contributor database is missing Question content parts");
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM shared_question_context_content_parts"),
					"Contributor database is missing Shared Context content parts");
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answer_regions"),
					"Contributor database is missing Answer PDF regions");
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM booklet_applicability_reviews"),
					"Contributor database is missing Booklet reviews");
		}
		try (ZipFile archive = ZipFile.builder().setPath(fixture.incoming()).get()) {
			var entry = archive.getEntry("records/subject-graph.jsonl");
			assertNotNull(entry, "Contributor archive has no Subject records");
			List<ExchangeRecord> records;
			try (InputStream input = archive.getInputStream(entry)) {
				records = new ExamWorkJsonlCodec().readRecords(input);
			}
			ExchangeRecord question = records.stream().filter(record -> record.recordType().equals("QUESTION"))
					.findFirst().orElseThrow();
			ExchangeRecord context = records.stream()
					.filter(record -> record.recordType().equals("SHARED_QUESTION_CONTEXT")).findFirst().orElseThrow();
			ExchangeRecord answer = records.stream().filter(record -> record.recordType().equals("ANSWER")).findFirst()
					.orElseThrow();
			ExchangeRecord booklet = records.stream().filter(record -> record.recordType().equals("EXAM_BOOKLET"))
					.findFirst().orElseThrow();
			assertEquals(2, question.values().path("question_content_parts").size(),
					"Portable export lost Question content parts");
			assertEquals(2, context.values().path("shared_question_context_content_parts").size(),
					"Portable export lost Shared Context content parts");
			assertEquals(1, answer.values().path("answer_regions").size(), "Portable export lost Answer PDF regions");
			assertEquals(1, booklet.values().path("booklet_applicability_reviews").size(),
					"Portable export lost Booklet applicability reviews");
		}
	}

	/**
	 * Rejects a changed master snapshot before publishing incoming files or graph
	 * changes, preserving the independently edited master.
	 */
	@Test
	void staleMasterApprovalCannotPublishImport() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = new SqliteDatabase(fixture.master().databasePath());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE subjects SET subject_name = 'Biology changed' WHERE id = 1");
		}
		SubjectImportCoordinator service = new SubjectImportCoordinator(fixture.master());
		assertThrows(IllegalStateException.class, () -> service.execute(1, fixture.plan(), fixture.incoming(),
				directory.resolve("backups"), "Stale coordinator approval"));
		try (Connection connection = database.openConnection()) {
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subjects WHERE subject_name = 'Biology changed'"));
		}
		assertFalse(Files.exists(fixture.master().dataRoot().resolve("subjects/Biology/exams/questions.pdf")));
	}

	/**
	 * Counts selected rows in a disposable SQLite connection.
	 */
	private int count(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	/**
	 * Executes trusted fixture SQL only, never package-controlled SQL.
	 */
	private void execute(SqliteDatabase database, String... sql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			for (String script : sql) {
				statement.execute(script);
			}
		}
	}

	/**
	 * Constructs two distinct installations and a complete approved review.
	 */
	private Fixture fixture() throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		ApplicationConfig master = ApplicationConfig.fromDataRoot(directory.resolve("master"));
		Files.createDirectories(source.dataRoot());
		Files.createDirectories(master.dataRoot());
		SqliteDatabase sourceDb = new SqliteDatabase(source.databasePath());
		SqliteDatabase masterDb = new SqliteDatabase(master.databasePath());
		sourceDb.initialiseSchema();
		masterDb.initialiseSchema();
		execute(masterDb, """
				INSERT INTO subjects (id, subject_name) VALUES
				    (1, 'Biology'), (2, 'Chemistry'),
				    (3, 'Physics'), (4, 'Engineering')
				""");
		seedSource(sourceDb, source);
		Path masterArchive = directory.resolve("master-biology.examwork");
		Path incoming = directory.resolve("incoming-biology.examwork");
		SubjectSnapshotExporter masterExporter = new SubjectSnapshotExporter(masterDb,
				new ManagedDataLayout(master.dataRoot()));
		SubjectSnapshotExporter incomingExporter = new SubjectSnapshotExporter(sourceDb,
				new ManagedDataLayout(source.dataRoot()));
		masterExporter.export(1, masterArchive);
		incomingExporter.export(1, incoming);
		var preview = new SubjectReconciliationPreviewService().preview(masterArchive, incoming);
		SubjectReconciliationReview review = new SubjectReconciliationReview(preview);

		// Ordinary UI approvals do not require individual justifications.
		// Their identity aliases must inherit the coordinator's import note.
		for (var key : review.reviewKeys()) {
			review.decide(key, Decision.ACCEPT, "");
		}
		Fingerprint reviewedMaster = new SubjectImportSnapshotFingerprint().inspect(masterArchive);
		Fingerprint reviewedIncoming = new SubjectImportSnapshotFingerprint().inspect(incoming);
		SubjectImportApprovalPlan plan = SubjectImportApprovalPlan.prepare(review, reviewedMaster, reviewedIncoming);
		return new Fixture(master, incoming, plan);
	}

	/**
	 * Creates source Subject, syllabus, booklet, Question, Answer and mixed
	 * content.
	 */
	private void seedSource(SqliteDatabase database, ApplicationConfig source) throws Exception {
		Path questions = source.dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answers = source.dataRoot().resolve("subjects/Biology/exams/answers.pdf");
		Path syllabus = source.dataRoot().resolve("subjects/Biology/curriculum/Biology 2025/sources/syllabus.pdf");
		for (Path path : List.of(questions, answers, syllabus)) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(questions, "Biology question document");
		Files.writeString(answers, "Biology answer document");
		Files.writeString(syllabus, "Biology syllabus document");
		execute(database, "INSERT INTO subjects (id, subject_name) VALUES (1, 'Biology')", """
				INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current, source_pdf_path)
				VALUES (11, 1, 'Biology 2025', 1,
				    'subjects/Biology/curriculum/Biology 2025/sources/syllabus.pdf')
				""", """
				INSERT INTO curriculum_nodes (id, syllabus_version_id, curriculum_code,
				    curriculum_name, curriculum_level, display_order)
				VALUES (101, 11, 'B25U1', 'Biology Unit', 'UNIT', 0)
				""", "INSERT INTO exam_providers (id, provider_name) VALUES (21, 'QCAA')", """
				INSERT INTO source_documents (id, relative_path) VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""", """
				INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
				VALUES (31, 1, 21, 2025, 'External Assessment')
				""", """
				INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
				VALUES (61, 31, 51, 'Paper 1')
				""", """
				INSERT INTO answer_files (id, exam_id, source_document_id, answer_file_name)
				VALUES (71, 31, 52, 'Marking Guide')
				""", "UPDATE exam_booklets SET answer_file_id = 71 WHERE id = 61",
				"INSERT INTO source_questions (id, booklet_id, source_question_code) VALUES (91, 61, '1')", """
						INSERT INTO shared_question_contexts (id, booklet_id, context_label)
						VALUES (121, 61, 'Biology stimulus')
						""", """
						INSERT INTO questions (id, booklet_id, classification_node_id, question_code,
						    question_text, marks, shared_context_capture_required,
						    source_question_id, shared_context_id)
						VALUES (81, 61, 101, '1', 'Imported Biology Question', 2, 0, 91, 121)
						""", """
						INSERT INTO question_regions (question_id, region_order, booklet_id,
						    page_number, x, y, width, height)
						VALUES (81, 0, 61, 2, 0.1, 0.2, 0.5, 0.3)
						""", """
						INSERT INTO shared_question_context_regions (shared_context_id, region_order,
						    page_number, x, y, width, height)
						VALUES (121, 0, 1, 0.1, 0.1, 0.4, 0.4)
						""", "INSERT INTO answers (id, question_id, answer_text) VALUES (141, 81, 'Biology response')",
				"""
						INSERT INTO answer_regions (answer_id, region_order, answer_file_id,
						    page_number, x, y, width, height)
						VALUES (141, 0, 71, 3, 0.1, 0.2, 0.4, 0.3)
						""");
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement question = connection
					.prepareStatement("INSERT INTO question_images (id, question_id, image_png) VALUES (131, 81, ?)")) {
				question.setBytes(1, PNG);
				question.executeUpdate();
			}
			try (PreparedStatement context = connection.prepareStatement(
					"INSERT INTO shared_question_context_images (shared_context_id, image_order, image_png)"
							+ " VALUES (121, 0, ?)")) {
				context.setBytes(1, PNG);
				context.executeUpdate();
			}
		}
		execute(database, """
				INSERT INTO question_content_parts (question_id, content_order, content_type, region_order)
				VALUES (81, 0, 'PDF_REGION', 0)
				""", """
				INSERT INTO question_content_parts (question_id, content_order, content_type, image_id)
				VALUES (81, 1, 'IMAGE', 131)
				""", """
				INSERT INTO shared_question_context_content_parts
				    (shared_context_id, content_order, content_type, region_order)
				VALUES (121, 0, 'PDF_REGION', 0)
				""", """
				    INSERT INTO shared_question_context_content_parts
				    (shared_context_id, content_order, content_type, image_order)
				VALUES (121, 1, 'IMAGE', 0)
				""", "INSERT INTO booklet_applicability_reviews (booklet_id) VALUES (61)");
	}

	/** Complete independent contributor and master fixture. */
	private record Fixture(ApplicationConfig master, Path incoming, SubjectImportApprovalPlan plan) {
	}
}
