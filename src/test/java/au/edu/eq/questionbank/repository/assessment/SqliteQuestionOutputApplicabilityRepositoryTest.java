package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteQuestionOutputApplicabilityRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void persistsAndRemovesIndividualOutputExclusions() throws Exception {
		Fixture fixture = createFixture("output-exclusions.db");
		SqliteQuestionOutputApplicabilityRepository repository = new SqliteQuestionOutputApplicabilityRepository(
				fixture.database());

		// Migration leaves existing Questions fully included by default.
		assertTrue(repository.findExcludedCurrentNodeIds(fixture.question()).isEmpty());
		repository.setExcluded(fixture.question(), fixture.firstCurrentDescriptor(), true);
		repository.setExcluded(fixture.question(), fixture.secondCurrentDescriptor(), true);

		// Reopen the repository to prove that the exclusions are persisted rather
		// than retained only in application memory.
		SqliteQuestionOutputApplicabilityRepository reopened = new SqliteQuestionOutputApplicabilityRepository(
				fixture.database());
		assertEquals(Set.of(fixture.firstCurrentDescriptor().getId(), fixture.secondCurrentDescriptor().getId()),
				reopened.findExcludedCurrentNodeIds(fixture.question()));
		reopened.setExcluded(fixture.question(), fixture.firstCurrentDescriptor(), false);
		assertEquals(Set.of(fixture.secondCurrentDescriptor().getId()),
				new SqliteQuestionOutputApplicabilityRepository(fixture.database())
						.findExcludedCurrentNodeIds(fixture.question()));

		// Repeating either state change must not create duplicates or errors.
		reopened.setExcluded(fixture.question(), fixture.secondCurrentDescriptor(), true);
		reopened.setExcluded(fixture.question(), fixture.firstCurrentDescriptor(), false);
		assertEquals(Set.of(fixture.secondCurrentDescriptor().getId()),
				reopened.findExcludedCurrentNodeIds(fixture.question()));
	}

	@Test
	void rejectsCurrentNodeFromAnotherSubject() throws Exception {
		Fixture fixture = createFixture("wrong-subject-output-node.db");
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(fixture.database());
		Subject physics = curriculumWriter.insertSubject("Physics");
		SyllabusVersion physicsCurrent = curriculumWriter.insertSyllabusVersion(physics, "2025", true);
		Unit physicsUnit = curriculumWriter.insertUnit(physicsCurrent, "1", "Unit 1", 1);
		Topic physicsTopic = curriculumWriter.insertTopic(physicsUnit, "1.1", "Topic 1", 1);
		Descriptor physicsDescriptor = curriculumWriter.insertDescriptor(physicsTopic, "1.1.1", "Descriptor 1", 1);
		SqliteQuestionOutputApplicabilityRepository repository = new SqliteQuestionOutputApplicabilityRepository(
				fixture.database());

		// A Question-specific exception must never cross Subject boundaries.
		assertThrows(IllegalArgumentException.class,
				() -> repository.setExcluded(fixture.question(), physicsDescriptor, true));
	}

	@Test
	void rejectsHistoricalAndNonPlacementNodes() throws Exception {
		Fixture fixture = createFixture("invalid-output-node.db");
		SqliteQuestionOutputApplicabilityRepository repository = new SqliteQuestionOutputApplicabilityRepository(
				fixture.database());

		// Historical curriculum may classify the Question, but an output exclusion
		// must target the derived current curriculum.
		assertThrows(IllegalArgumentException.class,
				() -> repository.setExcluded(fixture.question(), fixture.historicalDescriptor(), true));

		// Topic is a search scope rather than a final Question-placement node.
		assertThrows(IllegalArgumentException.class,
				() -> repository.setExcluded(fixture.question(), fixture.currentTopic(), true));
	}

	private Fixture createFixture(String databaseName) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion historical = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = curriculumWriter.insertUnit(historical, "1", "Historical Unit 1", 1);
		Topic historicalTopic = curriculumWriter.insertTopic(historicalUnit, "1.1", "Historical Topic 1", 1);
		Descriptor historicalDescriptor = curriculumWriter.insertDescriptor(historicalTopic, "1.1.1",
				"Historical Descriptor 1", 1);
		SyllabusVersion current = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = curriculumWriter.insertUnit(current, "1", "Current Unit 1", 1);
		Topic currentTopic = curriculumWriter.insertTopic(currentUnit, "1.1", "Current Topic 1", 1);
		Descriptor firstCurrentDescriptor = curriculumWriter.insertDescriptor(currentTopic, "1.1.1",
				"Current Descriptor 1", 1);
		Descriptor secondCurrentDescriptor = curriculumWriter.insertDescriptor(currentTopic, "1.1.2",
				"Current Descriptor 2", 2);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question question = questionRepository.save(booklet, "Q7", "", 3,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.60, 0.15)), historicalDescriptor, false);
		return new Fixture(database, question, historicalDescriptor, currentTopic, firstCurrentDescriptor,
				secondCurrentDescriptor);
	}

	private record Fixture(SqliteDatabase database, Question question, Descriptor historicalDescriptor,
			Topic currentTopic, Descriptor firstCurrentDescriptor, Descriptor secondCurrentDescriptor) {
	}
}
