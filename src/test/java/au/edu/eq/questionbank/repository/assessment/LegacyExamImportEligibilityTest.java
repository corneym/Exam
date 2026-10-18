package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter.ExamRegistration;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class LegacyExamImportEligibilityTest {

	@TempDir
	Path tempDirectory;

	@Test
	void activeEmptyAndMetadataOnlyExamsRemainEligible() throws Exception {
		Fixture fixture = fixture("metadata-only.db");
		LegacyExamImportEligibility eligibility = new LegacyExamImportEligibility(fixture.database());
		assertNull(eligibility.rejectionReason(fixture.exam()));
		addMetadataOnlyQuestion(fixture);
		assertNull(eligibility.rejectionReason(fixture.exam()));
		execute(fixture.database(), """
				INSERT INTO answers (question_id, answer_text)
				SELECT id, 'C' FROM questions WHERE question_code = '1'
				""");
		assertNull(eligibility.rejectionReason(fixture.exam()));
	}

	@Test
	void completeAndReviewedExamsAreProtected() throws Exception {
		Fixture complete = fixture("completed.db");
		execute(complete.database(), "UPDATE exams SET capture_state = 'COMPLETE' WHERE id = 1");
		assertTrue(new LegacyExamImportEligibility(complete.database()).rejectionReason(complete.exam())
				.contains("COMPLETE"));
		Fixture reviewed = fixture("reviewed.db");
		execute(reviewed.database(), """
				INSERT INTO booklet_applicability_reviews (booklet_id) VALUES (1)
				""");
		assertTrue(new LegacyExamImportEligibility(reviewed.database()).rejectionReason(reviewed.exam())
				.contains("applicability reviews"));
		assertEquals(1, count(reviewed.database(), "booklet_applicability_reviews"));
	}

	@Test
	void descriptorAndAcceptedRegionsBlockEvenAnActiveExam() throws Exception {
		Fixture descriptor = fixture("descriptor.db");
		addMetadataOnlyQuestion(descriptor);
		execute(descriptor.database(), """
				UPDATE questions
				SET classification_node_id = 101
				WHERE question_code = '1'
				""");
		assertTrue(new LegacyExamImportEligibility(descriptor.database()).rejectionReason(descriptor.exam())
				.contains("descriptor-level"));
		Fixture captured = fixture("capture.db");
		addMetadataOnlyQuestion(captured);
		execute(captured.database(), """
				INSERT INTO question_regions
				    (question_id, region_order, booklet_id, page_number, x, y, width, height)
				SELECT id, 0, booklet_id, 1, 0.1, 0.1, 0.2, 0.2
				FROM questions WHERE question_code = '1'
				""");
		assertTrue(new LegacyExamImportEligibility(captured.database()).rejectionReason(captured.exam())
				.contains("captured Question content"));
	}

	@Test
	void developedExamRollsBackOtherRegistrationsAndPreservesReviews() throws Exception {
		Fixture fixture = fixture("rollback.db");
		execute(fixture.database(), """
				INSERT INTO booklet_applicability_reviews (booklet_id) VALUES (1)
				""");
		SqliteExamWriter writer = new SqliteExamWriter(fixture.database());
		ExamRegistration newMock = new ExamRegistration("QCAA", 2020, "Mock Examination");
		ExamRegistration protectedExam = new ExamRegistration("QCAA", 2020, "External Assessment");

		// Ordering a new Exam before the protected Exam must not leave a
		// partially created Exam or changed expected-booklet count.
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> writer.findOrCreateExams(fixture.subject(), List.of(newMock, protectedExam),
						Map.of(newMock, 3, protectedExam, 3)));
		assertTrue(exception.getMessage().contains("Legacy Question Import blocked"));
		assertEquals(1, writer.findExamsForSubject(fixture.subject()).size());
		assertNull(writer.findExamAssetExpectations(fixture.exam()).expectedQuestionBookletCount());
		assertEquals(1, count(fixture.database(), "booklet_applicability_reviews"));
	}

	private void addMetadataOnlyQuestion(Fixture fixture) throws SQLException {
		execute(fixture.database(), """
				INSERT INTO questions
				    (booklet_id, classification_node_id, question_code, question_text,
				     marks, shared_context_capture_required)
				VALUES (1, 100, '1', '', 1, 0)
				""");
	}

	private int count(SqliteDatabase database, String table) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			result.next();
			return result.getInt(1);
		}
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	private Fixture fixture(String databaseName) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		Subject subject = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		Exam exam = writer.createExam(subject, "QCAA", 2020, "External Assessment");
		execute(database, """
				INSERT INTO source_documents (id, relative_path)
				VALUES (1, 'Chemistry/QCAA/2020/paper1.pdf')
				""");
		execute(database, """
				INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
				VALUES (1, 1, 1, 'Paper 1')
				""");
		execute(database, """
				INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current)
				VALUES (10, 1, '2019', 0)
				""");
		execute(database, """
				INSERT INTO curriculum_nodes
				    (id, syllabus_version_id, parent_id, curriculum_code,
				     curriculum_name, curriculum_level, display_order)
				VALUES
				    (100, 10, NULL, '1.1.1', 'Subtopic', 'SUBTOPIC', 0),
				    (101, 10, 100, '1.1.1.1', 'Descriptor', 'DESCRIPTOR', 1)
				""");
		return new Fixture(database, subject, exam);
	}

	private record Fixture(SqliteDatabase database, Subject subject, Exam exam) {
	}
}
