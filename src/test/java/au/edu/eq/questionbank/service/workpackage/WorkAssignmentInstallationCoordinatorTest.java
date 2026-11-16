package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class WorkAssignmentInstallationCoordinatorTest {

	@TempDir
	Path directory;

	@Test
	void duplicateAssignmentIdentityWithDifferentPackageIsRejected() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		installer.install(fixture.archive());
		Path differentArchive = directory.resolve("different-issuance.examwork");

		// Same assignment UUID, but independently issued
		// package UUID and baseline snapshot.
		export(fixture, fixture.assignmentId(), differentArchive);
		assertThrows(IOException.class, () -> installer.install(differentArchive));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void graphConflictRollsBackAndRemovesNewlyPublishedPdfs() throws Exception {
		Fixture fixture = fixture();

		// An independently created local Subject has the same
		// name but a different canonical portable UUID.
		// Installation must not merge it without authorisation.
		execute(fixture.contributorDatabase(), """
				INSERT INTO subjects (id, subject_name)
				VALUES (77, 'Biology')
				""");
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		assertThrows(SQLException.class, () -> installer.install(fixture.archive()));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM subjects"));
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		Path question = fixture.contributor().dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answer = fixture.contributor().dataRoot().resolve("subjects/Biology/exams/answers.pdf");

		// Both files were newly published before the database
		// conflict. Recovery must remove both owned hard links.
		assertFalse(Files.exists(question));
		assertFalse(Files.exists(answer));
		assertEquals(1, count(fixture.contributorDatabase(), """
				SELECT COUNT(*)
				FROM subjects
				WHERE id = 77
				  AND subject_name = 'Biology'
				"""));
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void independentAssignmentsCanShareUnchangedSourceFiles() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		installer.install(fixture.archive());
		UUID secondAssignmentId = UUID.randomUUID();
		Path secondArchive = directory.resolve("second-assignment.examwork");
		export(fixture, secondAssignmentId, secondArchive);
		var second = installer.install(secondArchive);
		assertFalse(second.replay());
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));

		// An Exam with two Booklets and one shared AnswerFile
		// must not be duplicated when another assignment uses it.
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answer_files"));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributorDatabase());
		assertTrue(sessions.find(fixture.assignmentId()).isPresent());
		assertTrue(sessions.find(secondAssignmentId).isPresent());
		assertTrue(sessions.active().isPresent());
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void installsCompleteExamAndReplaysWithoutDuplicatingWork() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		var first = installer.install(fixture.archive());
		assertFalse(first.replay());
		assertFalse(first.cleanupPending());
		assertEquals(fixture.assignmentId(), first.assignmentId());
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answer_files"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM questions"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answers"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributorDatabase());
		assertTrue(sessions.find(fixture.assignmentId()).isPresent());
		assertTrue(sessions.active().isPresent());
		Path question = fixture.contributor().dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answer = fixture.contributor().dataRoot().resolve("subjects/Biology/exams/answers.pdf");
		assertArrayEquals(Files.readAllBytes(fixture.sourceQuestionPdf()), Files.readAllBytes(question));
		assertArrayEquals(Files.readAllBytes(fixture.sourceAnswerPdf()), Files.readAllBytes(answer));

		// Completed installation must not leave an outstanding
		// publication journal or private package stage.
		assertFalse(Files.exists(fixture.contributor().dataRoot().resolve(".assignment-install-staging")
				.resolve(first.packageId().toString())));
		var repeated = installer.install(fixture.archive());
		assertTrue(repeated.replay());
		assertFalse(repeated.cleanupPending());
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.contributorDatabase().verifyIntegrity();
	}

	private int count(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private void export(Fixture fixture, UUID assignmentId, Path destination) throws Exception {
		new WorkAssignmentExporter(fixture.sourceDatabase(), new ManagedDataLayout(fixture.source().dataRoot()))
				.export(new WorkAssignmentExporter.Request(fixture.exam(), assignmentId, UUID.randomUUID(),
						"Integration Contributor", false, destination));
	}

	private Fixture fixture() throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		ApplicationConfig contributor = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		Files.createDirectories(source.dataRoot());
		Files.createDirectories(contributor.dataRoot());
		SqliteDatabase sourceDatabase = new SqliteDatabase(source.databasePath());
		SqliteDatabase contributorDatabase = new SqliteDatabase(contributor.databasePath());
		sourceDatabase.initialiseSchema();
		contributorDatabase.initialiseSchema();
		Path questionPdf = source.dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answerPdf = source.dataRoot().resolve("subjects/Biology/exams/answers.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.writeString(questionPdf, "Question PDF test content");
		Files.writeString(answerPdf, "Answer PDF test content");
		execute(sourceDatabase, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		execute(sourceDatabase, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES (11, 1, 'Biology 2025', 1)
				""");
		execute(sourceDatabase, """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, curriculum_code,
				    curriculum_name, curriculum_level, display_order
				)
				VALUES (
				    101, 11, 'B25U1',
				    'Biology Unit', 'UNIT', 0
				)
				""");
		execute(sourceDatabase, """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (21, 'QCAA')
				""");
		execute(sourceDatabase, """
				INSERT INTO source_documents (id, relative_path)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""");
		execute(sourceDatabase, """
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
		execute(sourceDatabase, """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id, answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(sourceDatabase, """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id,
				    answer_file_id, booklet_name
				)
				VALUES
				    (61, 31, 51, 71, 'Paper 1'),
				    (62, 31, 51, 71, 'Paper 2')
				""");
		execute(sourceDatabase, """
				INSERT INTO questions (
				    id, booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (
				    81, 61, 101,
				    '1', 'Biology question', 2, 0
				)
				""");
		execute(sourceDatabase, """
				INSERT INTO answers (
				    id, question_id, answer_text
				)
				VALUES (91, 81, 'Biology answer')
				""");
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		UUID assignmentId = UUID.randomUUID();
		Path archive = directory.resolve("issued-assignment.examwork");
		Fixture fixture = new Fixture(source, contributor, sourceDatabase, contributorDatabase, exam, assignmentId,
				archive, questionPdf, answerPdf);
		export(fixture, assignmentId, archive);
		return fixture;
	}

	private record Fixture(ApplicationConfig source, ApplicationConfig contributor, SqliteDatabase sourceDatabase,
			SqliteDatabase contributorDatabase, Exam exam, UUID assignmentId, Path archive, Path sourceQuestionPdf,
			Path sourceAnswerPdf) {
	}
}
