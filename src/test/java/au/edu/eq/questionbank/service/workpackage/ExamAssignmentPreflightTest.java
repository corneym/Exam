package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteBookletApplicabilityReviewRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteCoordinatorExamLockRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class ExamAssignmentPreflightTest {

	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = { "", "required-password" })
	void acceptsPdfReadableWithoutPasswordAndRejectsPasswordRequired(String userPassword) throws Exception {
		Fixture fixture = fixture();
		try (var pdf = Loader.loadPDF(TestPdf.bytes("Protected PDF"))) {
			pdf.protect(new StandardProtectionPolicy("owner-password", userPassword, new AccessPermission()));
			pdf.save(fixture.questionPdf().toFile());
		}
		if (userPassword.isEmpty()) {
			assertEquals(1, fixture.preflight().check(fixture.exam(), false).booklets().size());
		} else {
			assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		}
	}

	@Test
	void acceptsSyllabusWithSubtopicInsteadOfDescriptor() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE curriculum_nodes
				SET curriculum_level = 'SUBTOPIC'
				WHERE id = 106
				""");
		ExamAssignmentPreflight.Result result = fixture.preflight().check(fixture.exam(), true);
		assertEquals("2025", result.currentSyllabus().getName());
		assertEquals("2019", result.legacy2019Syllabus().getName());
	}

	@Test
	void acceptsWholeExamWithCurrentAndLegacySyllabuses() throws Exception {
		Fixture fixture = fixture();
		ExamAssignmentPreflight.Result result = fixture.preflight().check(fixture.exam(), true);
		assertEquals(1, result.booklets().size());
		assertEquals(1, result.answerFiles().size());
		assertEquals("2025", result.currentSyllabus().getName());
		assertTrue(result.currentSyllabus().isCurrent());
		assertEquals("2019", result.legacy2019Syllabus().getName());
	}

	@Test
	void rejectsCompletedExamUntilReactivated() throws Exception {
		Fixture fixture = fixture();
		ManagedDataLayout layout = new ManagedDataLayout(directory.resolve("data"));
		Path archive = directory.resolve("prepared-assignment.examwork");
		UUID assignmentId = UUID.randomUUID();

		// Prepare a valid archive while the Exam is ACTIVE,
		// but do not register the assignment yet.
		new WorkAssignmentExporter(fixture.database(), layout).export(new WorkAssignmentExporter.Request(fixture.exam(),
				assignmentId, UUID.randomUUID(), "Lifecycle Test Contributor", false, archive));
		new SqliteBookletApplicabilityReviewRepository(fixture.database()).setReviewed(61, true);
		SqliteExamWriter writer = new SqliteExamWriter(fixture.database());
		Exam completed = writer.setExamCaptureState(fixture.exam(), ExamCaptureState.COMPLETE);

		// A stale ACTIVE Exam object must not bypass preflight.
		IOException preflightFailure = assertThrows(IOException.class,
				() -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(preflightFailure.getMessage().contains("complete"));
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());

		// The previously prepared archive must also be rejected.
		SQLException registrationFailure = assertThrows(SQLException.class, () -> ledger.register(archive));
		assertTrue(registrationFailure.getMessage().contains("complete"));
		assertTrue(ledger.find(assignmentId).isEmpty());
		assertTrue(new SqliteCoordinatorExamLockRepository(fixture.database()).find(fixture.exam().getId()).isEmpty());
		writer.setExamCaptureState(completed, ExamCaptureState.ACTIVE);
		assertEquals(1, fixture.preflight().check(fixture.exam(), false).booklets().size());
		fixture.database().verifyIntegrity();
	}

	@Test
	void rejectsCurrentSyllabusContainingOnlyUnitsOrTopics() throws Exception {
		Fixture fixture = fixture();

		// A Unit and Topic without a classifiable descendant are insufficient.
		execute(fixture.database(), """
				DELETE FROM curriculum_nodes
				WHERE id = 106
				""");
		IOException topicsOnly = assertThrows(IOException.class,
				() -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(topicsOnly.getMessage().contains("no usable Subtopic or Descriptor"));
		assertTrue(topicsOnly.getMessage().contains("2025"));

		// A Unit without any child nodes must also be rejected.
		execute(fixture.database(), """
				DELETE FROM curriculum_nodes
				WHERE id = 104
				""");
		IOException unitsOnly = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(unitsOnly.getMessage().contains("no usable Subtopic or Descriptor"));
		assertTrue(unitsOnly.getMessage().contains("2025"));
	}

	@Test
	void rejectsExamPdfOutsideSubjectExamDirectory() throws Exception {
		Fixture fixture = fixture();

		// Begin with a valid, complete Exam structure.
		assertEquals(1, fixture.preflight().check(fixture.exam(), false).booklets().size());
		Path misplacedPdf = fixture.questionPdf().getParent().getParent().resolve("curriculum/questions.pdf");
		Files.createDirectories(misplacedPdf.getParent());
		Files.copy(fixture.questionPdf(), misplacedPdf);

		// The replacement is a genuine readable PDF inside
		// the correct Subject, but outside its Exam namespace.
		execute(fixture.database(), """
				UPDATE source_documents
				SET relative_path =
				    'subjects/Biology/curriculum/questions.pdf'
				WHERE id = 51
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(failure.getMessage().contains("unsafe Exam PDF path"));
		assertTrue(failure.getMessage().contains("Question booklet Paper 1"));

		// Refusal must not delete or overwrite either file.
		assertTrue(Files.isRegularFile(fixture.questionPdf()));
		assertTrue(Files.isRegularFile(misplacedPdf));
		assertEquals(-1L, Files.mismatch(fixture.questionPdf(), misplacedPdf));

		// Restoring the original persisted reference makes
		// the same Exam eligible again.
		execute(fixture.database(), """
				UPDATE source_documents
				SET relative_path =
				    'subjects/Biology/exams/questions.pdf'
				WHERE id = 51
				""");
		assertEquals(1, fixture.preflight().check(fixture.exam(), false).booklets().size());
		fixture.database().verifyIntegrity();
	}

	@Test
	void rejectsMissingAnswerPdf() throws Exception {
		Fixture fixture = fixture();
		Files.delete(fixture.answerPdf());
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(failure.getMessage().contains("missing or unreadable"));
	}

	@Test
	void rejectsMissingOrAmbiguousCurrentSyllabus() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 0
				WHERE id = 12
				""");
		assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 1
				WHERE id IN (11, 12)
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(failure.getMessage().contains("exactly one"));
	}

	@Test
	void rejectsMissingOrIncorrectAssetExpectations() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE exams
				SET expected_question_booklet_count = 2
				WHERE id = 31
				""");
		IOException mismatch = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(mismatch.getMessage().contains("booklet count"));
		execute(fixture.database(), """
				UPDATE exams
				SET expected_question_booklet_count = 1,
				    expected_answer_file_count = NULL
				WHERE id = 31
				""");
		IOException missing = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(missing.getMessage().contains("Answer file count"));
	}

	@Test
	void rejectsNonPdfBytesEvenWithoutStoredHash() throws Exception {
		Fixture fixture = fixture();
		Files.writeString(fixture.questionPdf(), "%PDF-1.4\nThis is not a PDF document");
		assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
	}

	@Test
	void rejectsSyllabusWithoutCurriculum() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				DELETE FROM curriculum_nodes
				WHERE syllabus_version_id = 12
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(failure.getMessage().contains("no usable"));
	}

	@Test
	void rejectsUnclassifiableOriginal2019Syllabus() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				DELETE FROM curriculum_nodes
				WHERE id = 105
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(failure.getMessage().contains("no usable Subtopic or Descriptor"));
		assertTrue(failure.getMessage().contains("2019"));
	}

	@Test
	void rejectsUnregisteredExam() throws Exception {
		Fixture fixture = fixture();
		Exam unknown = new Exam(999, new Subject(1, "Biology"), new ExamProvider(1, "QCAA"), 2025, "Unknown Exam");
		assertThrows(IllegalArgumentException.class, () -> fixture.preflight().check(unknown, false));
	}

	@ParameterizedTest
	@ValueSource(strings = { "missing", "directory", "empty", "invalid", "wrong-hash" })
	void rejectsUnusableQuestionPdfWithoutChangingMaster(String defect) throws Exception {
		Fixture fixture = fixture();
		Path source = fixture.questionPdf();
		switch (defect) {
		case "missing" -> Files.delete(source);
		case "directory" -> {
			Files.delete(source);
			Files.createDirectory(source);
		}
		case "empty" -> Files.write(source, new byte[0]);
		case "invalid" -> Files.writeString(source, "%PDF-1.4\nInvalid document");
		case "wrong-hash" -> execute(fixture.database(),
				"UPDATE source_documents SET content_sha256 = '" + "0".repeat(64) + "' WHERE id = 51");
		default -> throw new IllegalArgumentException(defect);
		}
		byte[] answerBefore = Files.readAllBytes(fixture.answerPdf());
		assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertArrayEquals(answerBefore, Files.readAllBytes(fixture.answerPdf()));
		assertTrue(Files.exists(source) || defect.equals("missing"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "missing", "directory", "invalid" })
	void rejectsUnusableSyllabusPdf(String defect) throws Exception {
		Fixture fixture = fixture();
		String relative = "subjects/Biology/curriculum/2025/sources/syllabus.pdf";
		Path source = directory.resolve("data").resolve(relative);
		Files.createDirectories(source.getParent());
		if (defect.equals("directory")) {
			Files.createDirectory(source);
		} else if (defect.equals("invalid")) {
			Files.writeString(source, "%PDF-1.4\nInvalid syllabus");
		}
		execute(fixture.database(), "UPDATE syllabus_versions SET source_pdf_path = '" + relative + "' WHERE id = 12");
		assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
	}

	@Test
	void requiresOriginal2019ForLegacyCapture() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET syllabus_name = '2018'
				WHERE id = 11
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(failure.getMessage().contains("original 2019"));
		ExamAssignmentPreflight.Result nonLegacy = fixture.preflight().check(fixture.exam(), false);
		assertNull(nonLegacy.legacy2019Syllabus());
		assertEquals("2025", nonLegacy.currentSyllabus().getName());
	}

	private void execute(SqliteDatabase database, String sql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Fixture fixture() throws Exception {
		ManagedDataLayout layout = new ManagedDataLayout(directory.resolve("data"));
		SqliteDatabase database = new SqliteDatabase(directory.resolve("questionbank.db"));
		database.initialiseSchema();
		Path questionPdf = layout.resolve("subjects/Biology/exams/questions.pdf");
		Path answerPdf = layout.resolve("subjects/Biology/exams/answers.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.write(questionPdf, TestPdf.bytes("Question PDF fixture"));
		Files.write(answerPdf, TestPdf.bytes("Answer PDF fixture"));
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		execute(database, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES
				    (11, 1, '2019', 0),
				    (12, 1, '2025', 1)
				""");
		execute(database, """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order
				)
				VALUES
				    (101, 11, NULL, '1', 'Legacy Unit', 'UNIT', 0),
				    (103, 11, 101, '1.1', 'Legacy Topic', 'TOPIC', 0),
				    (105, 11, 103, '1.1.1', 'Legacy Descriptor', 'DESCRIPTOR', 0),
				    (102, 12, NULL, '1', 'Current Unit', 'UNIT', 0),
				    (104, 12, 102, '1.1', 'Current Topic', 'TOPIC', 0),
				    (106, 12, 104, '1.1.1', 'Current Descriptor', 'DESCRIPTOR', 0)
				""");
		execute(database, """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (1, 'QCAA')
				""");
		execute(database, """
				INSERT INTO source_documents (id, relative_path)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""");
		execute(database, """
				INSERT INTO exams (
				    id, subject_id, provider_id, exam_year,
				    exam_name, expected_question_booklet_count,
				    expected_answer_file_count
				)
				VALUES
				    (31, 1, 1, 2025, 'External Assessment', 1, 1)
				""");
		execute(database, """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id, booklet_name
				)
				VALUES (61, 31, 51, 'Paper 1')
				""");
		execute(database, """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id, answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(database, """
				UPDATE exam_booklets
				SET answer_file_id = 71
				WHERE id = 61
				""");
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(1, "QCAA"), 2025, "External Assessment");
		return new Fixture(new ExamAssignmentPreflight(database, layout), database, exam, questionPdf, answerPdf);
	}

	private record Fixture(ExamAssignmentPreflight preflight, SqliteDatabase database, Exam exam, Path questionPdf,
			Path answerPdf) {
	}
}
