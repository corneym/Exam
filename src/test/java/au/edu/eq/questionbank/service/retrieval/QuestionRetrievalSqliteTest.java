package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class QuestionRetrievalSqliteTest {

	@TempDir
	Path tempDirectory;

	@Test
	void descriptorSearchRemainsPreciseAfterReopen() throws Exception {
		Fixture fixture = createFixture("descriptor-precision.db");
		ReloadedFixture reloaded = reloadFixture(fixture);
		List<QuestionRetrievalResult> results = reloaded.service()
				.findQuestionsApplicableTo(reloaded.currentDescriptor());
		assertEquals(2, results.size());
		assertEquals(fixture.currentDescriptorQuestionId(), results.get(0).getQuestion().getId());
		assertEquals(fixture.historicalDescriptorQuestionId(), results.get(1).getQuestion().getId());
		assertEquals(List.of(reloaded.currentDescriptor()), results.get(0).getCurrentApplicability());
		assertEquals(List.of(reloaded.currentDescriptor()), results.get(1).getCurrentApplicability());
	}

	@Test
	void editedMappingReviewChangesRetrievalAfterReopen() throws Exception {
		Fixture fixture = createFixture("edited-mapping-review.db");
		ReloadedFixture initiallyReloaded = reloadFixture(fixture);
		List<QuestionRetrievalResult> initialResults = initiallyReloaded.service()
				.findQuestionsApplicableTo(initiallyReloaded.currentDescriptor());
		assertEquals(2, initialResults.size());
		QuestionRetrievalResult historicalResult = initialResults.get(1);
		CurriculumNode historicalDescriptor = historicalResult.getOriginalClassification();
		SyllabusVersion currentVersion = initiallyReloaded.currentDescriptor().getSyllabusVersion();
		SqliteDatabase editDatabase = new SqliteDatabase(fixture.databasePath());
		editDatabase.initialiseSchema();
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(editDatabase);
		reviewWriter.replaceWithNoMatch(historicalDescriptor, currentVersion);
		ReloadedFixture afterNoMatch = reloadFixture(fixture);
		List<QuestionRetrievalResult> noMatchResults = afterNoMatch.service()
				.findQuestionsApplicableTo(afterNoMatch.currentDescriptor());
		assertEquals(1, noMatchResults.size());
		assertEquals(fixture.currentDescriptorQuestionId(), noMatchResults.get(0).getQuestion().getId());
		SqliteDatabase restoreDatabase = new SqliteDatabase(fixture.databasePath());
		restoreDatabase.initialiseSchema();
		SqliteCurriculumMappingReviewWriter restoreWriter = new SqliteCurriculumMappingReviewWriter(restoreDatabase);
		restoreWriter.replaceMappings(historicalDescriptor, currentVersion, List.of(afterNoMatch.currentDescriptor()));
		ReloadedFixture afterRestore = reloadFixture(fixture);
		List<QuestionRetrievalResult> restoredResults = afterRestore.service()
				.findQuestionsApplicableTo(afterRestore.currentDescriptor());
		assertEquals(2, restoredResults.size());
		assertEquals(fixture.currentDescriptorQuestionId(), restoredResults.get(0).getQuestion().getId());
		assertEquals(fixture.historicalDescriptorQuestionId(), restoredResults.get(1).getQuestion().getId());
	}

	@Test
	void historicalClassificationRemainsUnchangedAfterReopen() throws Exception {
		Fixture fixture = createFixture("historical-provenance.db");
		ReloadedFixture reloaded = reloadFixture(fixture);
		List<QuestionRetrievalResult> results = reloaded.service()
				.findQuestionsApplicableTo(reloaded.currentDescriptor());
		QuestionRetrievalResult historicalResult = results.get(1);
		assertEquals(fixture.historicalDescriptorId(), historicalResult.getOriginalClassification().getId());
		assertEquals(fixture.historicalDescriptorId(), historicalResult.getQuestion().getClassification().getId());
		assertSame(historicalResult.getOriginalClassification(), historicalResult.getQuestion().getClassification());
		assertEquals(reloaded.currentDescriptor().getId(), historicalResult.getCurrentApplicability().get(0).getId());
	}

	@Test
	void subtopicSearchIncludesDirectAndDescriptorApplicabilityAfterReopen() throws Exception {
		Fixture fixture = createFixture("subtopic-hierarchy.db");
		ReloadedFixture reloaded = reloadFixture(fixture);
		List<QuestionRetrievalResult> results = reloaded.service()
				.findQuestionsApplicableTo(reloaded.currentSubtopic());
		assertEquals(4, results.size());
		assertEquals(fixture.currentSubtopicQuestionId(), results.get(0).getQuestion().getId());
		assertEquals(fixture.currentDescriptorQuestionId(), results.get(1).getQuestion().getId());
		assertEquals(fixture.historicalSubtopicQuestionId(), results.get(2).getQuestion().getId());
		assertEquals(fixture.historicalDescriptorQuestionId(), results.get(3).getQuestion().getId());
		assertEquals(List.of(reloaded.currentSubtopic()), results.get(0).getCurrentApplicability());
		assertEquals(List.of(reloaded.currentDescriptor()), results.get(1).getCurrentApplicability());
		assertEquals(List.of(reloaded.currentSubtopic()), results.get(2).getCurrentApplicability());
		assertEquals(List.of(reloaded.currentDescriptor(), reloaded.secondCurrentDescriptor()),
				results.get(3).getCurrentApplicability());
	}

	@Test
	void subtopicSearchReturnsMultiplyMappedQuestionOnlyOnceAfterReopen() throws Exception {
		Fixture fixture = createFixture("multiply-mapped-question.db");
		ReloadedFixture reloaded = reloadFixture(fixture);
		List<QuestionRetrievalResult> results = reloaded.service()
				.findQuestionsApplicableTo(reloaded.currentSubtopic());
		long occurrences = results.stream()
				.filter(result -> result.getQuestion().getId() == fixture.historicalDescriptorQuestionId()).count();
		assertEquals(1, occurrences);
		QuestionRetrievalResult historicalResult = results.stream()
				.filter(result -> result.getQuestion().getId() == fixture.historicalDescriptorQuestionId()).findFirst()
				.orElseThrow();
		assertEquals(List.of(reloaded.currentDescriptor(), reloaded.secondCurrentDescriptor()),
				historicalResult.getCurrentApplicability());
	}

	@Test
	void topicAndUnitSearchUsePersistedHierarchyAfterReopen() throws Exception {
		Fixture fixture = createFixture("broad-hierarchy.db");
		ReloadedFixture reloaded = reloadFixture(fixture);
		List<QuestionRetrievalResult> topicResults = reloaded.service()
				.findQuestionsApplicableTo(reloaded.currentTopic());
		List<QuestionRetrievalResult> unitResults = reloaded.service()
				.findQuestionsApplicableTo(reloaded.currentUnit());
		assertEquals(4, topicResults.size());
		assertEquals(4, unitResults.size());
		for (int index = 0; index < topicResults.size(); index++) {
			assertEquals(topicResults.get(index).getQuestion().getId(), unitResults.get(index).getQuestion().getId());
		}
	}

	private Fixture createFixture(String databaseName) throws Exception {
		Path databasePath = tempDirectory.resolve(databaseName);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion historicalVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = curriculumWriter.insertUnit(historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = curriculumWriter.insertTopic(historicalUnit, "1.1", "Historical topic", 1);
		Subtopic historicalSubtopic = curriculumWriter.insertSubtopic(historicalTopic, "1.1.1", "Historical subtopic",
				1);
		Descriptor historicalDescriptor = curriculumWriter.insertDescriptor(historicalSubtopic, "1.1.1.1",
				"Historical descriptor", 1);
		SyllabusVersion currentVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = curriculumWriter.insertUnit(currentVersion, "1", "Current unit", 1);
		Topic currentTopic = curriculumWriter.insertTopic(currentUnit, "1.1", "Current topic", 1);
		Subtopic currentSubtopic = curriculumWriter.insertSubtopic(currentTopic, "1.1.1", "Current subtopic", 1);
		Descriptor currentDescriptor = curriculumWriter.insertDescriptor(currentSubtopic, "1.1.1.1",
				"Current descriptor", 1);
		Descriptor secondCurrentDescriptor = curriculumWriter.insertDescriptor(currentSubtopic, "1.1.1.2",
				"Second current descriptor", 2);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet currentBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet historicalBooklet = examImporter.importExam(chemistry, "QCAA", 2019, "External Assessment",
				"Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question currentSubtopicQuestion = questionRepository.save(currentBooklet, "Q1", "", 2, List.of(),
				currentSubtopic, false);
		Question currentDescriptorQuestion = questionRepository.save(currentBooklet, "Q2", "", 3, List.of(),
				currentDescriptor, false);
		Question historicalSubtopicQuestion = questionRepository.save(historicalBooklet, "Q3", "", 4, List.of(),
				historicalSubtopic, false);
		Question historicalDescriptorQuestion = questionRepository.save(historicalBooklet, "Q4", "", 5, List.of(),
				historicalDescriptor, false);
		SqliteCurriculumMappingReviewWriter mappingReviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		mappingReviewWriter.confirmMappings(historicalSubtopic, currentVersion, List.of(currentSubtopic));
		mappingReviewWriter.confirmMappings(historicalDescriptor, currentVersion,
				List.of(currentDescriptor, secondCurrentDescriptor));
		return new Fixture(databasePath, currentVersion.getId(), currentUnit.getCode(), currentTopic.getCode(),
				currentSubtopic.getCode(), currentDescriptor.getCode(), secondCurrentDescriptor.getCode(),
				historicalDescriptor.getId(), currentSubtopicQuestion.getId(), currentDescriptorQuestion.getId(),
				historicalSubtopicQuestion.getId(), historicalDescriptorQuestion.getId());
	}

	private ReloadedFixture reloadFixture(Fixture fixture) throws SQLException {
		SqliteDatabase reopenedDatabase = new SqliteDatabase(fixture.databasePath());
		reopenedDatabase.initialiseSchema();
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(reopenedDatabase);
		SyllabusVersion currentVersion = curriculumRepository.findVersionById(fixture.currentVersionId()).orElseThrow();
		CurriculumNode currentUnit = curriculumRepository.findByCode(currentVersion, fixture.currentUnitCode())
				.orElseThrow();
		CurriculumNode currentTopic = curriculumRepository.findByCode(currentVersion, fixture.currentTopicCode())
				.orElseThrow();
		CurriculumNode currentSubtopic = curriculumRepository.findByCode(currentVersion, fixture.currentSubtopicCode())
				.orElseThrow();
		CurriculumNode currentDescriptor = curriculumRepository
				.findByCode(currentVersion, fixture.currentDescriptorCode()).orElseThrow();
		CurriculumNode secondCurrentDescriptor = curriculumRepository
				.findByCode(currentVersion, fixture.secondCurrentDescriptorCode()).orElseThrow();
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(reopenedDatabase);
		CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
				curriculumRepository);
		QuestionRetrievalService service = new QuestionRetrievalService(questionRepository, expansionService);
		return new ReloadedFixture(service, currentUnit, currentTopic, currentSubtopic, currentDescriptor,
				secondCurrentDescriptor);
	}

	private record Fixture(Path databasePath, long currentVersionId, String currentUnitCode, String currentTopicCode,
			String currentSubtopicCode, String currentDescriptorCode, String secondCurrentDescriptorCode,
			long historicalDescriptorId, long currentSubtopicQuestionId, long currentDescriptorQuestionId,
			long historicalSubtopicQuestionId, long historicalDescriptorQuestionId) {
	}

	private record ReloadedFixture(QuestionRetrievalService service, CurriculumNode currentUnit,
			CurriculumNode currentTopic, CurriculumNode currentSubtopic, CurriculumNode currentDescriptor,
			CurriculumNode secondCurrentDescriptor) {
	}
}
