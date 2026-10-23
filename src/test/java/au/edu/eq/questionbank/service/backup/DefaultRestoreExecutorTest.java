package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.HistoricalSchemaFixture;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DefaultRestoreExecutorTest {

	@TempDir
	Path tempDir;

	@Test
	void automaticDatabaseBackupRestoresImageOnlyAndMixedSharedContext() throws Exception {
		assertSharedContextBackupRoundTrip(BackupKind.AUTOMATIC_DATABASE);
	}

	@Test
	void databaseOnlyRestorePreservesManagedSubjectData() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "Restored Chemistry", "unused-pdf",
				"unused-curriculum");
		BackupResult databaseBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.automaticDatabase(sourceConfig));
		ApplicationConfig targetConfig = createDataSet("target", "Original Chemistry", "keep-pdf", "keep-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(databaseBackup.backupPath())) {
			new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, new CountingCloseable());
		}
		assertEquals("Restored Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertEquals("keep-pdf", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf")));
		assertEquals("keep-curriculum", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/curriculum/2025/sources/curriculum.txt")));
	}

	@Test
	void failedReplacementRollsBackOriginalDatabaseAndSubjectTree() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "New Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Old Chemistry", "old-pdf", "old-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(sourceBackup.backupPath())) {

			// Managed data is published before the database. Corrupting the staged
			// database therefore exercises rollback after the Subject tree changed.
			Files.writeString(preparation.databasePath(), "deliberately corrupt");
			RestoreException exception = assertThrows(RestoreException.class,
					() -> new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation,
							new CountingCloseable()));
			assertTrue(exception.applicationMustExit());
		}
		assertEquals("Old Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertEquals("old-pdf", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf")));
		assertEquals("old-curriculum", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/curriculum/2025/sources/curriculum.txt")));
		assertEquals("legacy-Old Chemistry",
				Files.readString(targetConfig.dataRoot().resolve("subjects/Chemistry/legacy/2019/questions.xlsx")));
	}

	@Test
	void fullBackupRestoresImageOnlyAndMixedSharedContext() throws Exception {
		assertSharedContextBackupRoundTrip(BackupKind.FULL);
	}

	@Test
	void fullRestoreReplacesDatabaseAndCompleteSubjectTreeAndCreatesSafetyBackup() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "Restored Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Original Chemistry", "old-pdf", "old-curriculum");

		// This file does not exist in the source backup. Replacing the complete
		// Subject tree must remove it.
		Path stale = targetConfig.dataRoot().resolve("subjects/Chemistry/exams/stale.txt");
		Files.createDirectories(stale.getParent());
		Files.writeString(stale, "stale");
		CountingCloseable resources = new CountingCloseable();
		try (RestorePreparation preparation = new DefaultRestoreService(targetConfig)
				.prepareRestore(sourceBackup.backupPath())) {
			RestoreResult result = new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation,
					resources);
			assertEquals(1, resources.closeCount);
			assertEquals(BackupKind.FULL, result.restoredManifest().kind());
			assertEquals(2, result.restoredManifest().formatVersion());
			assertTrue(Files.isRegularFile(result.safetyBackupPath()));
		}
		assertEquals("new-pdf", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf")));
		assertEquals("new-curriculum", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/curriculum/2025/sources/curriculum.txt")));
		assertEquals("legacy-Restored Chemistry",
				Files.readString(targetConfig.dataRoot().resolve("subjects/Chemistry/legacy/2019/questions.xlsx")));
		assertFalse(Files.exists(stale));
		assertEquals("Restored Chemistry", readOnlySubjectName(targetConfig.databasePath()));
	}

	@Test
	void preRestoreSafetyBackupContainsOriginalSubjectData() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "New Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Old Chemistry", "old-pdf", "old-curriculum");
		Path safetyBackupPath;
		try (RestorePreparation preparation = new DefaultRestoreService(targetConfig)
				.prepareRestore(sourceBackup.backupPath())) {
			RestoreResult result = new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation,
					new CountingCloseable());
			safetyBackupPath = result.safetyBackupPath();
		}
		try (RestorePreparation safetyPreparation = new DefaultRestoreService(targetConfig)
				.prepareRestore(safetyBackupPath)) {
			assertEquals(BackupKind.FULL, safetyPreparation.manifest().kind());
			assertEquals("old-pdf", Files.readString(safetyPreparation.subjectsRoot()
					.resolve("Chemistry/exams/QCAA/2025/External Assessment/exam.pdf")));
			assertEquals("legacy-Old Chemistry",
					Files.readString(safetyPreparation.subjectsRoot().resolve("Chemistry/legacy/2019/questions.xlsx")));
			assertEquals("Old Chemistry", readOnlySubjectName(safetyPreparation.databasePath()));
		}
	}

	@Test
	void resourceCloseFailureLeavesCurrentSubjectDataUntouched() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "New Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Old Chemistry", "old-pdf", "old-curriculum");
		CountingCloseable resources = new CountingCloseable();
		resources.failOnClose = true;
		try (RestorePreparation preparation = new DefaultRestoreService(targetConfig)
				.prepareRestore(sourceBackup.backupPath())) {
			RestoreException exception = assertThrows(RestoreException.class,
					() -> new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, resources));
			assertTrue(exception.applicationMustExit());
		}
		assertEquals("Old Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertEquals("old-pdf", Files.readString(
				targetConfig.dataRoot().resolve("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf")));
		Path safetyDirectory = targetConfig.dataRoot().resolve("backups").resolve("pre-restore");
		try (Stream<Path> stream = Files.list(safetyDirectory)) {
			assertEquals(1, stream.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void restoresVersion11BackupThenMigratesAndReopensAtLatestSchema() throws Exception {
		Path legacyDatabasePath = tempDir.resolve("legacy-v11.db");
		createVersion11Database(legacyDatabasePath);
		Path backupPath = tempDir.resolve("legacy-v11-backup.zip");
		createVersion11Backup(legacyDatabasePath, backupPath);
		ApplicationConfig targetConfig = createDataSet("target", "Original Chemistry", "keep-pdf", "keep-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(backupPath)) {

			// Preparation proves that the legacy database can reach the current schema,
			// but the staged database itself must remain at the backed-up version.
			assertEquals(11, new SqliteDatabase(preparation.databasePath()).schemaVersion());
			new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, new CountingCloseable());
		}
		SqliteDatabase restoredDatabase = new SqliteDatabase(targetConfig.databasePath());

		// Restore publishes the original database unchanged. Normal application
		// initialisation is responsible for performing the real sequential migration.
		assertEquals(11, restoredDatabase.schemaVersion());
		assertEquals("Legacy Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		restoredDatabase.initialiseSchema();

		// Reopen through a fresh database boundary so successful assertions cannot
		// depend on state retained by the object that performed the migration.
		SqliteDatabase reopenedDatabase = new SqliteDatabase(targetConfig.databasePath());
		assertEquals(SqliteDatabase.latestSchemaVersion(), reopenedDatabase.schemaVersion());
		reopenedDatabase.verifySchema();
		reopenedDatabase.verifyIntegrity();
		assertEquals("Legacy Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertTrue(tableExists(reopenedDatabase, "question_output_exclusions"));

		// Version 14 must be reached through the real restore-and-startup migration
		// path, not merely when creating a new database.
		assertTrue(tableExists(reopenedDatabase, "question_images"));
		assertTrue(tableExists(reopenedDatabase, "question_content_parts"));

		// Version 13 renamed the two remaining physical "preamble" columns without
		// changing their stored values.
		assertTrue(columnExists(reopenedDatabase, "questions", "shared_context_capture_required"));
		assertFalse(columnExists(reopenedDatabase, "questions", "preamble_capture_required"));
		assertTrue(columnExists(reopenedDatabase, "source_questions", "shared_context_status"));
		assertFalse(columnExists(reopenedDatabase, "source_questions", "preamble_status"));
		assertEquals(1, readIntegerValue(reopenedDatabase, "questions", "shared_context_capture_required"));
		assertEquals("PRESENT", readTextValue(reopenedDatabase, "source_questions", "shared_context_status"));
	}

	private void assertSharedContextBackupRoundTrip(BackupKind kind) throws Exception {
		String suffix = kind == BackupKind.FULL ? "full" : "database";
		ApplicationConfig sourceConfig = ApplicationConfig.fromDataRoot(tempDir.resolve("shared-source-" + suffix));
		ApplicationConfig targetConfig = ApplicationConfig.fromDataRoot(tempDir.resolve("shared-target-" + suffix));
		Files.createDirectories(sourceConfig.dataRoot());
		Files.createDirectories(targetConfig.dataRoot());

		// Full archives include managed Subject data; database-only archives do not.
		Path sourceAsset = sourceConfig.dataRoot().resolve("subjects/Chemistry/context-source.txt");
		Files.createDirectories(sourceAsset.getParent());
		Files.writeString(sourceAsset, "original subject asset");
		Path targetAsset = targetConfig.dataRoot().resolve("subjects/keep-existing.txt");
		Files.createDirectories(targetAsset.getParent());
		Files.writeString(targetAsset, "target subject asset");
		SqliteDatabase sourceDatabase = new SqliteDatabase(sourceConfig.databasePath());
		sourceDatabase.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(sourceDatabase);
		Subject subject = curriculum.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(subject, "2025", true);
		Unit unit = curriculum.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculum.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic classification = curriculum.insertSubtopic(topic, "1.1.1", "Topic content", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(sourceDatabase);
		ExamBooklet booklet = new SqliteExamImporter(sourceDatabase, examWriter).importExam(subject, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/2025/paper1.pdf");
		byte[] imageA = sharedContextTestPng(0xFF0000);
		byte[] imageB = sharedContextTestPng(0x0000FF);
		byte[] questionImage = sharedContextTestPng(0x00FF00);
		SharedQuestionContextRegion pdfRegion = new SharedQuestionContextRegion(1, 0.10, 0.15, 0.80, 0.20);
		List<SharedContextContentPart> imageOnlyParts = List.of(new ImageSharedContextContentPart(imageA));
		List<SharedContextContentPart> mixedParts = List.of(new ImageSharedContextContentPart(imageA), pdfRegion,
				new ImageSharedContextContentPart(imageB));
		SqliteSharedQuestionContextRepository contexts = new SqliteSharedQuestionContextRepository(sourceDatabase);
		SharedQuestionContext imageOnly = contexts.saveContentParts(booklet, "Image-only stimulus", imageOnlyParts);
		SharedQuestionContext mixed = contexts.saveContentParts(booklet, "Ordered mixed stimulus", mixedParts);
		SqliteQuestionRepository questions = new SqliteQuestionRepository(sourceDatabase);
		Question imageQuestion = questions.saveWithContent(booklet, "1", "", 1,
				List.of(new ImageQuestionContentPart(questionImage)), classification, false, null, imageOnly,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question mixedFirst = questions.saveWithContent(booklet, "2a", "", 1,
				List.of(new ImageQuestionContentPart(questionImage)), classification, false, null, mixed,
				QuestionResponseType.WRITTEN_RESPONSE);
		Question mixedSecond = questions.saveWithContent(booklet, "2b", "", 1,
				List.of(new ImageQuestionContentPart(questionImage)), classification, false, null, mixed,
				QuestionResponseType.WRITTEN_RESPONSE);
		BackupRequest request = kind == BackupKind.FULL
				? BackupRequest.full(tempDir.resolve("shared-backups-" + suffix))
				: BackupRequest.automaticDatabase(sourceConfig);
		BackupResult backup = new DefaultBackupService(sourceConfig, "Test").createBackup(request);
		assertTrue(Files.isRegularFile(backup.backupPath()));
		assertEquals(SqliteDatabase.latestSchemaVersion(), backup.manifest().databaseSchemaVersion());

		// Restore into a distinct installation. The destination must have its own
		// valid current database so the mandatory pre-restore safety backup succeeds.
		SqliteDatabase targetDatabase = new SqliteDatabase(targetConfig.databasePath());
		targetDatabase.initialiseSchema();
		try (RestorePreparation preparation = new DefaultRestoreService(targetConfig)
				.prepareRestore(backup.backupPath())) {
			new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, () -> {

				// No long-lived resources are open in this integration fixture.
			});
		}
		SqliteDatabase restoredDatabase = new SqliteDatabase(targetConfig.databasePath());
		restoredDatabase.verifySchema();
		restoredDatabase.verifyIntegrity();
		assertEquals(SqliteDatabase.latestSchemaVersion(), restoredDatabase.schemaVersion());
		SqliteQuestionRepository reloaded = new SqliteQuestionRepository(restoredDatabase);
		Question restoredImage = reloaded.findById(imageQuestion.getId()).orElseThrow();
		Question restoredMixedFirst = reloaded.findById(mixedFirst.getId()).orElseThrow();
		Question restoredMixedSecond = reloaded.findById(mixedSecond.getId()).orElseThrow();
		assertTrue(restoredImage.hasSharedContext());
		assertEquals(imageOnly.getId(), restoredImage.getSharedContext().getId());
		assertEquals(imageOnlyParts, restoredImage.getSharedContext().getContentParts());
		assertTrue(restoredImage.getSharedContext().getRegions().isEmpty());
		assertArrayEquals(imageA,
				((ImageSharedContextContentPart) restoredImage.getSharedContext().getContentParts().getFirst())
						.pngBytes());

		// Both Questions must still link to the SAME persisted mixed context.
		assertEquals(mixed.getId(), restoredMixedFirst.getSharedContext().getId());
		assertEquals(mixed.getId(), restoredMixedSecond.getSharedContext().getId());
		assertEquals(mixedParts, restoredMixedFirst.getSharedContext().getContentParts());
		assertEquals(mixedParts, restoredMixedSecond.getSharedContext().getContentParts());
		assertEquals(List.of(pdfRegion), restoredMixedFirst.getSharedContext().getRegions());
		assertArrayEquals(imageA,
				((ImageSharedContextContentPart) restoredMixedFirst.getSharedContext().getContentParts().get(0))
						.pngBytes());
		assertArrayEquals(imageB,
				((ImageSharedContextContentPart) restoredMixedFirst.getSharedContext().getContentParts().get(2))
						.pngBytes());
		assertArrayEquals(questionImage,
				((ImageQuestionContentPart) restoredImage.getContentParts().getFirst()).pngBytes());

		// The archive must not create duplicate or orphaned Shared Context records.
		assertEquals(2, sharedContextTableRowCount(restoredDatabase, "shared_question_contexts"));
		assertEquals(1, sharedContextTableRowCount(restoredDatabase, "shared_question_context_regions"));
		assertEquals(3, sharedContextTableRowCount(restoredDatabase, "shared_question_context_images"));
		assertEquals(4, sharedContextTableRowCount(restoredDatabase, "shared_question_context_content_parts"));
		try (Connection connection = restoredDatabase.openConnection();
				Statement statement = connection.createStatement();
				ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
			assertFalse(violations.next(), "Restored database contains invalid foreign-key relationships");
		}
		if (kind == BackupKind.FULL) {
			assertEquals("original subject asset",
					Files.readString(targetConfig.dataRoot().resolve("subjects/Chemistry/context-source.txt")));
			assertFalse(Files.exists(targetAsset));
		} else {
			assertEquals("target subject asset", Files.readString(targetAsset));
			assertFalse(Files.exists(targetConfig.dataRoot().resolve("subjects/Chemistry/context-source.txt")));
		}
	}

	private boolean columnExists(SqliteDatabase database, String tableName, String columnName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
			while (result.next()) {
				if (columnName.equals(result.getString("name"))) {
					return true;
				}
			}
			return false;
		}
	}

	private ApplicationConfig createDataSet(String directoryName, String subjectName, String pdfContents,
			String curriculumContents) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve(directoryName));
		Path subjectsRoot = config.dataRoot().resolve("subjects");
		Path exam = subjectsRoot.resolve("Chemistry/exams/QCAA/2025/External Assessment/exam.pdf");
		Path curriculum = subjectsRoot.resolve("Chemistry/curriculum/2025/sources/curriculum.txt");
		Path legacy = subjectsRoot.resolve("Chemistry/legacy/2019/questions.xlsx");
		Files.createDirectories(exam.getParent());
		Files.createDirectories(curriculum.getParent());
		Files.createDirectories(legacy.getParent());
		Files.writeString(exam, pdfContents);
		Files.writeString(curriculum, curriculumContents);
		Files.writeString(legacy, "legacy-" + subjectName);
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (subject_name) VALUES ('" + subjectName.replace("'", "''") + "')");
		}
		return config;
	}

	private void createVersion11Backup(Path databasePath, Path backupPath) throws Exception {
		BackupManifest manifest = BackupManifest.current(BackupKind.AUTOMATIC_DATABASE,
				Instant.parse("2026-09-26T00:00:00Z"), 11, "Test");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(backupPath))) {

			// Construct the same archive layout consumed by the production restore
			// service while retaining the deliberately old database schema.
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			new BackupManifestCodec().write(manifest, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.DATABASE_ENTRY));
			Files.copy(databasePath, output);
			output.closeEntry();
		}
	}

	private void createVersion11Database(Path databasePath) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();

		// The source backup must contain genuine v11 structures, not
		// residual applicability-review objects from modern schema versions.
		HistoricalSchemaFixture.removePostVersion19Objects(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Seed values that migration v12->v13 must preserve while renaming the
			// physical Shared Context columns.
			statement.execute("INSERT INTO subjects (subject_name) VALUES ('Legacy Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    subject_id,
					    syllabus_name,
					    is_current,
					    curriculum_status
					)
					VALUES (1, '2019', 0, 'IN_PROGRESS')
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    syllabus_version_id,
					    parent_id,
					    curriculum_code,
					    curriculum_name,
					    curriculum_level,
					    display_order
					)
					VALUES (1, NULL, '1', 'Legacy unit', 'UNIT', 1)
					""");
			statement.execute("INSERT INTO exam_providers (provider_name) VALUES ('QCAA')");
			statement.execute("""
					INSERT INTO source_documents (relative_path)
					VALUES ('Chemistry/2019/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    subject_id,
					    provider_id,
					    exam_year,
					    exam_name
					)
					VALUES (1, 1, 2019, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    exam_id,
					    source_document_id,
					    booklet_name,
					    question_format
					)
					VALUES (1, 1, 'Paper 1', 'UNSPECIFIED')
					""");
			statement.execute("""
					INSERT INTO source_questions (
					    booklet_id,
					    source_question_code,
					    shared_context_status
					)
					VALUES (1, 'Q1', 'PRESENT')
					""");
			statement.execute("""
					INSERT INTO questions (
					    booklet_id,
					    classification_node_id,
					    question_code,
					    question_text,
					    marks,
					    shared_context_capture_required,
					    response_type,
					    source_question_id
					)
					VALUES (1, 1, 'Q1', '', 2, 1, 'WRITTEN_RESPONSE', 1)
					""");

			// Reverse migrations 12 through 18 so the synthetic fixture contains only
			// structures that genuinely existed in schema version 11.
			statement.execute("DROP TABLE question_content_parts");
			statement.execute("DROP TABLE question_images");
			statement.execute("""
					ALTER TABLE questions
					RENAME COLUMN shared_context_capture_required
					TO preamble_capture_required
					""");
			statement.execute("""
					ALTER TABLE source_questions
					RENAME COLUMN shared_context_status
					TO preamble_status
					""");
			statement.execute("DROP TABLE question_output_exclusions");

			// Version 19 added AnswerFile explanation metadata, which did not exist in the
			// version-11 backup being simulated.
			statement.execute("ALTER TABLE answer_files DROP COLUMN contains_answer_explanations");

			// Version 18 added Exam-level asset expectations, which did not exist in v11.
			statement.execute("ALTER TABLE exams DROP COLUMN expected_answer_file_count");
			statement.execute("ALTER TABLE exams DROP COLUMN expected_question_booklet_count");

			// Version 17 added explicit Question source-recapture state.
			statement.execute("ALTER TABLE questions DROP COLUMN source_capture_required");

			// Version 16 added source-document hashes, which did not exist in v11.
			statement.execute("ALTER TABLE source_documents DROP COLUMN content_sha256");

			// Version 15 added Exam lifecycle and expected-count metadata that did not
			// exist in the version-11 backup being simulated.
			statement.execute("ALTER TABLE exam_booklets DROP COLUMN expected_question_count");
			statement.execute("ALTER TABLE exams DROP COLUMN capture_state");
			statement.execute("UPDATE schema_version SET version = 11");
		}

		// Validate the constructed legacy fixture against the production version-11
		// schema contract before using it as backup input.
		assertEquals(11, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	private int readIntegerValue(SqliteDatabase database, String tableName, String columnName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT " + columnName + " FROM " + tableName + " LIMIT 1")) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private String readOnlySubjectName(Path databasePath) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT subject_name
						FROM subjects
						ORDER BY id
						LIMIT 1
						""")) {
			assertTrue(result.next());
			return result.getString("subject_name");
		}
	}

	private String readTextValue(SqliteDatabase database, String tableName, String columnName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT " + columnName + " FROM " + tableName + " LIMIT 1")) {
			assertTrue(result.next());
			return result.getString(1);
		}
	}

	private int sharedContextTableRowCount(SqliteDatabase database, String tableName) throws Exception {

		// Only fixed, test-owned identifiers are passed to this helper.
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private byte[] sharedContextTestPng(int rgb) throws IOException {
		BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, rgb);
			}
		}
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			assertTrue(ImageIO.write(image, "png", output));
			return output.toByteArray();
		}
	}

	private boolean tableExists(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection(); var statement = connection.prepareStatement("""
				SELECT 1
				FROM sqlite_master
				WHERE type = 'table'
				  AND name = ?
				""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private static final class CountingCloseable implements AutoCloseable {

		private int closeCount;
		private boolean failOnClose;

		@Override
		public void close() throws Exception {
			closeCount++;
			if (failOnClose) {
				throw new IOException("Deliberate close failure");
			}
		}
	}
}
