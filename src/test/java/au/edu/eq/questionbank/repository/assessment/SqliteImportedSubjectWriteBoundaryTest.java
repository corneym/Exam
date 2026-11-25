package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumLifecycleRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftLoader;
import au.edu.eq.questionbank.service.workpackage.ExamAssignmentPreflight;
import au.edu.eq.questionbank.service.workpackage.SubjectSnapshotExporter;

/**
 * Verifies imported-Subject authority at ordinary persistence boundaries.
 */
class SqliteImportedSubjectWriteBoundaryTest {

	@TempDir
	Path directory;

	@Test
	void importedSubjectRejectsOrdinaryAssessmentAndCurriculumWrites() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = fixture.database();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		SqliteExamWriter exams = new SqliteExamWriter(database);

		assertThrows(IllegalStateException.class,
				() -> curriculum.insertSyllabusVersion(fixture.subject(), "2027", true));
		assertThrows(IllegalStateException.class,
				() -> curriculum.insertUnit(fixture.currentVersion(), "2", "Another unit", 1));
		Subject localSubject = curriculum.insertSubject("Chemistry");
		SyllabusVersion inconsistentVersion = new SyllabusVersion(fixture.currentVersion().getId(), localSubject,
				fixture.currentVersion().getName(), fixture.currentVersion().isCurrent());
		assertThrows(IllegalStateException.class,
				() -> curriculum.insertUnit(inconsistentVersion, "3", "Forged local unit", 1));
		SyllabusVersion localVersion = curriculum.insertSyllabusVersion(localSubject, "2026", true);
		Unit importedUnit = (Unit) fixture.currentSubtopic().getParent().getParent();
		Unit inconsistentParent = new Unit(importedUnit.getId(), localVersion, importedUnit.getCode(),
				importedUnit.getName(), importedUnit.getDisplayOrder());
		assertThrows(IllegalStateException.class,
				() -> curriculum.insertTopic(inconsistentParent, "3.1", "Forged local topic", 0));

		var authoringSession = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database))
				.load(fixture.currentVersion());
		assertThrows(IllegalStateException.class,
				() -> new SqliteCurriculumAuthoringWriter(database).save(authoringSession));
		assertThrows(IllegalStateException.class, () -> new SqliteCurriculumLifecycleRepository(database)
				.finalise(inconsistentVersion, Instant.parse("2026-11-25T00:00:00Z")));
		assertThrows(IllegalStateException.class, () -> new SqliteCurriculumSourcePdfRepository(database)
				.updateSourcePdfPath(inconsistentVersion, "subjects/Biology/curriculum/2026/sources/syllabus.pdf"));
		assertThrows(IllegalStateException.class, () -> new SqliteCurriculumMappingWriter(database)
				.insertMapping(fixture.historicalSubtopic(), fixture.currentSubtopic(), MappingStatus.CONFIRMED));
		assertThrows(IllegalStateException.class, () -> new SqliteCurriculumMappingReviewWriter(database)
				.confirmNoMatch(fixture.historicalSubtopic(), fixture.currentVersion()));

		assertThrows(IllegalStateException.class, () -> exams.insertExam(fixture.subject(),
				fixture.booklet().getExam().getProvider(), 2027, "External Assessment"));
		assertThrows(IllegalStateException.class,
				() -> exams.setExamCaptureState(fixture.booklet().getExam(), ExamCaptureState.COMPLETE));
		assertThrows(IllegalStateException.class,
				() -> exams.updateExamAssetExpectations(fixture.booklet().getExam(), 2, 1));
		assertTrue(new LegacyExamImportEligibility(database).rejectionReason(fixture.booklet().getExam())
				.contains("imported read-only Subject"));
		assertThrows(IllegalStateException.class, () -> new ExamAssignmentPreflight(database,
				new ManagedDataLayout(fixture.managedRoot())).check(fixture.booklet().getExam(), false));
		Path forbiddenSnapshot = directory.resolve("forbidden-subject-snapshot.examwork");
		assertThrows(IllegalStateException.class, () -> new SubjectSnapshotExporter(database,
				new ManagedDataLayout(fixture.managedRoot())).export(fixture.subject().getId(), forbiddenSnapshot));
		assertTrue(Files.notExists(forbiddenSnapshot));
		assertThrows(IllegalStateException.class, () -> new AnswerFileReassignmentService(database)
				.reassign(fixture.booklet(), fixture.replacementAnswerFile()));

		Path replacementPdf = directory.resolve("replacement-questions.pdf");
		Files.writeString(replacementPdf, "replacement question document");
		assertThrows(IllegalStateException.class, () -> new QuestionBookletPdfReplacementService(database,
				fixture.managedRoot()).replace(fixture.booklet(), replacementPdf));
		assertEquals("original question document",
				Files.readString(fixture.managedRoot().resolve(fixture.booklet().getSourceDocument().getRelativePath())));

		QuestionRegion questionRegion = new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.50, 0.20);
		assertThrows(IllegalStateException.class,
				() -> new SqliteQuestionWriter(database).insertQuestion(fixture.booklet(), "Q1", "", 2,
						List.of(questionRegion), fixture.currentSubtopic(), false));
		assertThrows(IllegalStateException.class,
				() -> new SqliteSourceQuestionRepository(database).save(fixture.booklet(), "Q1"));
		SharedQuestionContextRegion contextRegion = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20);
		assertThrows(IllegalStateException.class, () -> new SqliteSharedQuestionContextRepository(database)
				.save(fixture.booklet(), "Shared stimulus", List.of(contextRegion)));

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertEquals(3, count(statement, "SELECT COUNT(*) FROM syllabus_versions"));
			assertEquals(6, count(statement, "SELECT COUNT(*) FROM curriculum_nodes"));
			assertEquals(1, count(statement, "SELECT COUNT(*) FROM exams"));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM curriculum_mappings"));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM curriculum_mapping_reviews"));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM questions"));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM source_questions"));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM shared_question_contexts"));
			assertEquals(fixture.originalAnswerFile().getId(),
					new SqliteAnswerWriter(database, exams).findAnswerFile(fixture.booklet()).getId());
			assertEquals(1, count(statement, """
					SELECT COUNT(*)
					FROM exams
					WHERE capture_state = 'ACTIVE'
					  AND expected_question_booklet_count IS NULL
					  AND expected_answer_file_count IS NULL
					"""));
		}
		database.verifyIntegrity();
	}

	private int count(Statement statement, String sql) throws Exception {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private Fixture fixture() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("imported-write-boundaries.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		Subject subject = curriculum.insertSubject("Biology");
		SyllabusVersion historicalVersion = curriculum.insertSyllabusVersion(subject, "2025", false);
		Subtopic historicalSubtopic = insertSubtopic(curriculum, historicalVersion, "1");
		SyllabusVersion currentVersion = curriculum.insertSyllabusVersion(subject, "2026", true);
		Subtopic currentSubtopic = insertSubtopic(curriculum, currentVersion, "2");
		SqliteExamWriter exams = new SqliteExamWriter(database);
		Path managedRoot = directory.resolve("managed");
		Path questionPdf = managedRoot.resolve("subjects/Biology/exams/QCAA/2026/EA/questions.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.writeString(questionPdf, "original question document");
		ExamBooklet booklet = new SqliteExamImporter(database, exams).importExam(subject, "QCAA", 2026,
				"External Assessment", "Paper 1", "subjects/Biology/exams/QCAA/2026/EA/questions.pdf");
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, exams);
		AnswerFile originalAnswerFile = answers.findOrCreateAnswerFile(booklet, "Original marking guide",
				"subjects/Biology/exams/QCAA/2026/EA/answers.pdf");
		AnswerFile replacementAnswerFile = answers.findOrCreateAnswerFile(booklet.getExam(),
				"Replacement marking guide", "subjects/Biology/exams/QCAA/2026/EA/replacement-answers.pdf");

		PackageEntry imported = new PackageEntry(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
				UUID.randomUUID(), "b".repeat(64), "c".repeat(64), PackageOutcome.APPLIED,
				directory.resolve("verified-backup.zip").toString(), "Approved authoritative Subject import");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			new SqliteSubjectImportLedger(database).recordPackage(connection, imported);
			new SqliteImportedSubjectAuthorityRepository(database).recordImported(connection, subject.getId(),
					imported.packageId());
			connection.commit();
		}
		return new Fixture(database, subject, historicalVersion, currentVersion, historicalSubtopic, currentSubtopic,
				booklet, originalAnswerFile, replacementAnswerFile, managedRoot);
	}

	private Subtopic insertSubtopic(SqliteCurriculumWriter writer, SyllabusVersion version, String prefix)
			throws Exception {
		Unit unit = writer.insertUnit(version, prefix, "Unit " + prefix, 0);
		Topic topic = writer.insertTopic(unit, prefix + ".1", "Topic " + prefix, 0);
		return writer.insertSubtopic(topic, prefix + ".1.1", "Subtopic " + prefix, 0);
	}

	private record Fixture(SqliteDatabase database, Subject subject, SyllabusVersion historicalVersion,
			SyllabusVersion currentVersion, Subtopic historicalSubtopic, Subtopic currentSubtopic,
			ExamBooklet booklet, AnswerFile originalAnswerFile, AnswerFile replacementAnswerFile, Path managedRoot) {
	}
}
