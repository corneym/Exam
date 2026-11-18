package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentArchiveStager.StagedAssignment;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentAssetExtractor.Extraction;

class SubjectImportGraphWriterAssignmentTest {

	@TempDir
	Path directory;

	@Test
	void callerRollbackRemovesEntireAssignmentGraph() throws Exception {
		Fixture fixture = fixture();
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			new SubjectImportGraphWriter(fixture.contributorDb()).installAssignment(connection, fixture.extraction());
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM exams"));
			connection.rollback();
		}
		assertEquals(0, count(fixture.contributorDb(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.contributorDb(), "SELECT COUNT(*) FROM subjects"));
		fixture.contributorDb().verifyIntegrity();
		discard(fixture);
	}

	@Test
	void changedExistingExamIsNotSilentlyOverwritten() throws Exception {
		Fixture fixture = fixture();
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			new SubjectImportGraphWriter(fixture.contributorDb()).installAssignment(connection, fixture.extraction());
			connection.commit();
		}
		try (Connection connection = fixture.contributorDb().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE exams
					SET exam_name = 'Locally changed exam'
					""");
		}
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class, () -> new SubjectImportGraphWriter(fixture.contributorDb())
					.installAssignment(connection, fixture.extraction()));
			connection.rollback();
		}
		assertEquals(1, count(fixture.contributorDb(), """
				SELECT COUNT(*)
				FROM exams
				WHERE exam_name = 'Locally changed exam'
				"""));
		fixture.contributorDb().verifyIntegrity();
		discard(fixture);
	}

	@Test
	void changedPrivateAssetPreventsGraphInsertion() throws Exception {
		Fixture fixture = fixture();
		Path asset = fixture.extraction().assets().getFirst().stagedPath();
		Files.writeString(asset, "Modified private asset");
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(IOException.class, () -> new SubjectImportGraphWriter(fixture.contributorDb())
					.installAssignment(connection, fixture.extraction()));
			connection.rollback();
		}
		assertEquals(0, count(fixture.contributorDb(), "SELECT COUNT(*) FROM exams"));
		discard(fixture);
	}

	@Test
	void identicalGraphInstallationReusesExistingRecords() throws Exception {
		Fixture fixture = fixture();
		Map<Key, Long> first;
		Map<Key, Long> second;
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			first = new SubjectImportGraphWriter(fixture.contributorDb()).installAssignment(connection,
					fixture.extraction());
			connection.commit();
		}
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			second = new SubjectImportGraphWriter(fixture.contributorDb()).installAssignment(connection,
					fixture.extraction());
			connection.commit();
		}
		assertEquals(first, second);
		assertEquals(1, count(fixture.contributorDb(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDb(), "SELECT COUNT(*) FROM exam_booklets"));
		fixture.contributorDb().verifyIntegrity();
		discard(fixture);
	}

	@Test
	void installsCompleteExamWithOriginalPortableIdentities() throws Exception {
		Fixture fixture = fixture();
		Map<Key, Long> installed;
		try (Connection connection = fixture.contributorDb().openConnection()) {
			connection.setAutoCommit(false);
			installed = new SubjectImportGraphWriter(fixture.contributorDb()).installAssignment(connection,
					fixture.extraction());
			connection.commit();
		}
		assertEquals(2, count(fixture.contributorDb(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDb(), "SELECT COUNT(*) FROM answer_files"));
		assertEquals(1, count(fixture.contributorDb(), "SELECT COUNT(*) FROM questions"));
		assertEquals(1, count(fixture.contributorDb(), "SELECT COUNT(*) FROM answers"));
		var sourceIdentities = new SqlitePortableIdentityRepository(fixture.sourceDb());
		var installedIdentities = new SqlitePortableIdentityRepository(fixture.contributorDb());
		UUID originalExam = sourceIdentities.requireByLocalId(EntityType.EXAM, 31).portableId();
		Key examKey = SubjectReconciliationReview.recordKey("EXAM", originalExam);
		long installedExamId = installed.get(examKey);
		assertEquals(originalExam, installedIdentities.requireByLocalId(EntityType.EXAM, installedExamId).portableId());
		assertEquals(sourceIdentities.requireByLocalId(EntityType.EXAM, 31).revisionId(),
				installedIdentities.requireByLocalId(EntityType.EXAM, installedExamId).revisionId());

		// Domain graph insertion must not publish master PDFs.
		assertFalse(Files.exists(fixture.contributor().dataRoot().resolve("subjects/Biology/exams/questions.pdf")));
		assertFalse(Files.exists(fixture.contributor().dataRoot().resolve("subjects/Biology/exams/answers.pdf")));
		fixture.contributorDb().verifyIntegrity();
		discard(fixture);
	}

	private int count(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private int count(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return count(connection, sql);
		}
	}

	private void discard(Fixture fixture) throws IOException {
		new WorkAssignmentArchiveStager(fixture.contributor()).discard(fixture.staged());
	}

	private Fixture fixture() throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		ApplicationConfig contributor = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		Files.createDirectories(source.dataRoot());
		Files.createDirectories(contributor.dataRoot());
		SqliteDatabase sourceDb = new SqliteDatabase(source.databasePath());
		SqliteDatabase contributorDb = new SqliteDatabase(contributor.databasePath());
		sourceDb.initialiseSchema();
		contributorDb.initialiseSchema();
		Path questions = source.dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answers = source.dataRoot().resolve("subjects/Biology/exams/answers.pdf");
		Files.createDirectories(questions.getParent());
		Files.write(questions, TestPdf.bytes("Question PDF test content"));
		Files.write(answers, TestPdf.bytes("Answer PDF test content"));
		try (Connection connection = sourceDb.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (11, 1, 'Biology 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES
					    (101, 11, NULL, 'B25U1',
					     'Biology Unit', 'UNIT', 0),
					    (102, 11, 101, 'B25U1T1',
					     'Biology Topic', 'TOPIC', 0),
					    (103, 11, 102, 'B25U1T1S1',
					     'Biology Subtopic', 'SUBTOPIC', 0)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (21, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES
					    (51, 'subjects/Biology/exams/questions.pdf'),
					    (52, 'subjects/Biology/exams/answers.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name,
					    expected_question_booklet_count,
					    expected_answer_file_count
					)
					VALUES (
					    31, 1, 21, 2025, 'External Assessment',
					    2, 1
					)
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id,
					    answer_file_name
					)
					VALUES (71, 31, 52, 'Marking Guide')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id,
					    answer_file_id, booklet_name
					)
					VALUES
					    (61, 31, 51, 71, 'Paper 1'),
					    (62, 31, 51, 71, 'Paper 2')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id,
					    booklet_id,
					    classification_node_id,
					    question_code,
					    question_text,
					    marks,
					    shared_context_capture_required
					)
					VALUES (
					    81,
					    61,
					    103,
					    '1',
					    'Biology question',
					    2,
					    0
					)
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES (91, 81, 'Biology answer')
					""");
		}
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		Path archive = directory.resolve("issued-assignment.examwork");
		new WorkAssignmentExporter(sourceDb, new ManagedDataLayout(source.dataRoot()))
				.export(new WorkAssignmentExporter.Request(exam, UUID.randomUUID(), UUID.randomUUID(),
						"Integration Contributor", false, archive));
		WorkAssignmentArchiveStager stager = new WorkAssignmentArchiveStager(contributor);
		StagedAssignment staged = stager.stage(archive);
		Extraction extraction = new WorkAssignmentAssetExtractor(contributor).extract(staged);
		return new Fixture(sourceDb, contributorDb, contributor, staged, extraction);
	}

	private record Fixture(SqliteDatabase sourceDb, SqliteDatabase contributorDb, ApplicationConfig contributor,
			StagedAssignment staged, Extraction extraction) {
	}
}
