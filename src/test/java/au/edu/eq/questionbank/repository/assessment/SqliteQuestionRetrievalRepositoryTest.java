package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumImporter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteQuestionRetrievalRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void noLongerCurrentTargetDoesNotCreateRetrievalMatches() throws Exception {
		Fixture fixture = createFixture("non-current-target.db");
		SqliteDatabase database = new SqliteDatabase(fixture.databasePath());
		database.initialiseSchema();
		CurriculumNode staleCurrentDescriptor = reloadCurrentDescriptor(database, fixture);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE syllabus_versions
						SET is_current = 0
						WHERE id = ?
						""")) {
			statement.setLong(1, fixture.currentVersionId());
			assertEquals(1, statement.executeUpdate());
		}
		SqliteDatabase reopenedDatabase = new SqliteDatabase(fixture.databasePath());
		reopenedDatabase.initialiseSchema();
		QuestionRetrievalRepository repository = new SqliteQuestionRepository(reopenedDatabase);
		List<QuestionApplicabilityMatch> matches = repository.findApplicableToNodes(List.of(staleCurrentDescriptor));
		assertEquals(0, matches.size());
	}

	@Test
	void rejectsInvalidRetrievalNodes() throws Exception {
		Fixture fixture = createFixture("invalid-search-nodes.db");
		SqliteDatabase reopenedDatabase = new SqliteDatabase(fixture.databasePath());
		reopenedDatabase.initialiseSchema();
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(reopenedDatabase);
		SyllabusVersion currentVersion = curriculumRepository.findVersionById(fixture.currentVersionId()).orElseThrow();
		CurriculumNode currentDescriptor = curriculumRepository
				.findByCode(currentVersion, fixture.currentDescriptorCode()).orElseThrow();
		SyllabusVersion historicalVersion = curriculumRepository.findVersionById(fixture.historicalVersionId())
				.orElseThrow();
		CurriculumNode historicalDescriptor = curriculumRepository
				.findByCode(historicalVersion, fixture.confirmedSourceDescriptorCode()).orElseThrow();
		CurriculumNode currentUnit = curriculumRepository.findByCode(currentVersion, "1").orElseThrow();
		QuestionRetrievalRepository repository = new SqliteQuestionRepository(reopenedDatabase);
		assertThrows(NullPointerException.class, () -> repository.findApplicableToNodes(null));
		assertThrows(IllegalArgumentException.class, () -> repository.findApplicableToNodes(List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> repository.findApplicableToNodes(List.of(historicalDescriptor)));
		assertThrows(IllegalArgumentException.class, () -> repository.findApplicableToNodes(List.of(currentUnit)));
		assertEquals(0, repository.findApplicableToNodes(List.of(currentDescriptor)).stream()
				.filter(match -> match.getQuestion().getId() == fixture.suggestedQuestionId()).count());
	}

	@Test
	void retrievesDirectAndConfirmedHistoricalQuestionsAfterReopen() throws Exception {
		Fixture fixture = createFixture("direct-and-confirmed.db");
		SqliteDatabase reopenedDatabase = new SqliteDatabase(fixture.databasePath());
		reopenedDatabase.initialiseSchema();
		CurriculumNode currentDescriptor = reloadCurrentDescriptor(reopenedDatabase, fixture);
		QuestionRetrievalRepository repository = new SqliteQuestionRepository(reopenedDatabase);
		List<QuestionApplicabilityMatch> matches = repository.findApplicableToNodes(List.of(currentDescriptor));
		assertEquals(3, matches.size());
		assertEquals(fixture.directQuestionId(), matches.get(0).getQuestion().getId());
		assertEquals(fixture.confirmedQuestionId(), matches.get(1).getQuestion().getId());
		assertEquals(fixture.secondConfirmedQuestionId(), matches.get(2).getQuestion().getId());
		for (QuestionApplicabilityMatch match : matches) {
			assertEquals(currentDescriptor.getId(), match.getCurrentNode().getId());
		}
		assertEquals(currentDescriptor.getId(), matches.get(0).getQuestion().getClassification().getId());
		assertEquals(fixture.confirmedSourceDescriptorId(), matches.get(1).getQuestion().getClassification().getId());
	}

	@Test
	void retrievesQuestionFromImportedThreeLevelCurriculumAfterReopen() throws Exception {
		Path databasePath = tempDirectory.resolve("three-level-retrieval.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter curriculumImporter = new SqliteCurriculumImporter(database, curriculumWriter);
		curriculumImporter.importSyllabus("Psychology", "2025", true,
				List.of(new CurriculumImportRow("2", "Individual development"),
						new CurriculumImportRow("2.3", "Psychological disorders"),
						new CurriculumImportRow("2.3.5", "Describe approaches to diagnosis")));
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		Subject psychology = curriculumRepository.findAllSubjects().stream()
				.filter(subject -> subject.getName().equals("Psychology")).findFirst().orElseThrow();
		SyllabusVersion syllabus2025 = curriculumRepository.findVersionsForSubject(psychology).stream()
				.filter(SyllabusVersion::isCurrent).findFirst().orElseThrow();
		CurriculumNode descriptor = curriculumRepository.findByCode(syllabus2025, "2.3.5").orElseThrow();
		assertEquals(Descriptor.class, descriptor.getClass());
		assertEquals("2.3", descriptor.getParent().getCode());
		assertEquals(Topic.class, descriptor.getParent().getClass());
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(psychology, "QCAA", 2025, "External Assessment", "Paper 1",
				"Psychology/2025/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question storedQuestion = questionRepository.save(booklet, "Q1", "", 2, List.of(), descriptor, false);
		SqliteDatabase reopenedDatabase = new SqliteDatabase(databasePath);
		reopenedDatabase.initialiseSchema();
		SqliteCurriculumRepository reopenedCurriculumRepository = new SqliteCurriculumRepository(reopenedDatabase);
		SyllabusVersion reopenedSyllabus = reopenedCurriculumRepository.findVersionById(syllabus2025.getId())
				.orElseThrow();
		CurriculumNode reopenedDescriptor = reopenedCurriculumRepository.findByCode(reopenedSyllabus, "2.3.5")
				.orElseThrow();
		assertEquals(Descriptor.class, reopenedDescriptor.getClass());
		assertEquals("2.3", reopenedDescriptor.getParent().getCode());
		assertEquals(Topic.class, reopenedDescriptor.getParent().getClass());
		QuestionRetrievalRepository retrievalRepository = new SqliteQuestionRepository(reopenedDatabase);
		List<QuestionApplicabilityMatch> matches = retrievalRepository
				.findApplicableToNodes(List.of(reopenedDescriptor));
		assertEquals(1, matches.size());
		assertEquals(storedQuestion.getId(), matches.get(0).getQuestion().getId());
		assertEquals(reopenedDescriptor.getId(), matches.get(0).getCurrentNode().getId());
		assertEquals("2.3.5", matches.get(0).getQuestion().getClassification().getCode());
	}

	@Test
	void suggestedMappingDoesNotCreateRetrievalMatchAfterReopen() throws Exception {
		Fixture fixture = createFixture("suggested-excluded.db");
		SqliteDatabase reopenedDatabase = new SqliteDatabase(fixture.databasePath());
		reopenedDatabase.initialiseSchema();
		CurriculumNode currentDescriptor = reloadCurrentDescriptor(reopenedDatabase, fixture);
		QuestionRetrievalRepository repository = new SqliteQuestionRepository(reopenedDatabase);
		List<QuestionApplicabilityMatch> matches = repository.findApplicableToNodes(List.of(currentDescriptor));
		assertFalse(matches.stream().anyMatch(match -> match.getQuestion().getId() == fixture.suggestedQuestionId()));
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
		Descriptor confirmedSourceDescriptor = curriculumWriter.insertDescriptor(historicalTopic, "1.1.1",
				"Confirmed historical descriptor", 1);
		Descriptor suggestedSourceDescriptor = curriculumWriter.insertDescriptor(historicalTopic, "1.1.2",
				"Suggested historical descriptor", 2);
		Descriptor secondConfirmedSourceDescriptor = curriculumWriter.insertDescriptor(historicalTopic, "1.1.3",
				"Second confirmed historical descriptor", 3);
		SyllabusVersion currentVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = curriculumWriter.insertUnit(currentVersion, "1", "Current unit", 1);
		Topic currentTopic = curriculumWriter.insertTopic(currentUnit, "1.1", "Current topic", 1);
		Descriptor currentDescriptor = curriculumWriter.insertDescriptor(currentTopic, "1.1.1", "Current descriptor",
				1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet currentBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet historicalBooklet = examImporter.importExam(chemistry, "QCAA", 2019, "External Assessment",
				"Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question directQuestion = questionRepository.save(currentBooklet, "Q1", "", 2, List.of(), currentDescriptor,
				false);
		Question confirmedQuestion = questionRepository.save(historicalBooklet, "Q2", "", 3, List.of(),
				confirmedSourceDescriptor, false);
		Question suggestedQuestion = questionRepository.save(historicalBooklet, "Q3", "", 4, List.of(),
				suggestedSourceDescriptor, false);
		Question secondConfirmedQuestion = questionRepository.save(historicalBooklet, "Q4", "", 5, List.of(),
				secondConfirmedSourceDescriptor, false);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		mappingWriter.insertMapping(confirmedSourceDescriptor, currentDescriptor, MappingStatus.CONFIRMED);
		mappingWriter.insertMapping(suggestedSourceDescriptor, currentDescriptor, MappingStatus.SUGGESTED);
		mappingWriter.insertMapping(secondConfirmedSourceDescriptor, currentDescriptor, MappingStatus.CONFIRMED);
		return new Fixture(databasePath, historicalVersion.getId(), currentVersion.getId(),
				confirmedSourceDescriptor.getId(), confirmedSourceDescriptor.getCode(), currentDescriptor.getCode(),
				directQuestion.getId(), confirmedQuestion.getId(), suggestedQuestion.getId(),
				secondConfirmedQuestion.getId());
	}

	private CurriculumNode reloadCurrentDescriptor(SqliteDatabase database, Fixture fixture) {
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SyllabusVersion currentVersion = curriculumRepository.findVersionById(fixture.currentVersionId()).orElseThrow();
		return curriculumRepository.findByCode(currentVersion, fixture.currentDescriptorCode()).orElseThrow();
	}

	private record Fixture(Path databasePath, long historicalVersionId, long currentVersionId,
			long confirmedSourceDescriptorId, String confirmedSourceDescriptorCode, String currentDescriptorCode,
			long directQuestionId, long confirmedQuestionId, long suggestedQuestionId, long secondConfirmedQuestionId) {
	}
}