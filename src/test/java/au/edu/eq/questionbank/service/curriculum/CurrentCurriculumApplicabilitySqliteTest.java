package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurrentCurriculumApplicabilitySqliteTest {

	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private Descriptor source;
	private Descriptor sourceSubtopicDescriptorOne;
	private Descriptor sourceSubtopicDescriptorTwo;
	private Subtopic sourceSubtopic;
	private Descriptor targetOne;
	private Descriptor targetTwo;
	private Descriptor targetSubtopicDescriptorOne;
	private Subtopic targetSubtopicOne;
	private Subtopic targetSubtopicTwo;
	private SyllabusVersion targetVersion;

	@Test
	void derivesCurrentApplicabilityFromPersistedConfirmedReview() throws Exception {
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		reviewWriter.confirmMappings(source, targetVersion, List.of(targetOne, targetTwo));
		SqliteCurriculumMappingRepository reloadedMappingRepository = new SqliteCurriculumMappingRepository(database);
		CurrentCurriculumApplicabilityService service = new CurrentCurriculumApplicabilityService(
				reloadedMappingRepository);
		List<CurriculumNode> currentNodes = service.findCurrentNodes(source);
		assertEquals(2, currentNodes.size());
		assertEquals(22, currentNodes.get(0).getId());
		assertEquals(23, currentNodes.get(1).getId());
	}

	@Test
	void derivesSubtopicApplicabilityFromPersistedConfirmedReview() throws Exception {
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		reviewWriter.confirmMappings(sourceSubtopic, targetVersion, List.of(targetSubtopicOne, targetSubtopicTwo));
		CurrentCurriculumApplicabilityService service = reopenedService();
		assertEquals(List.of(targetSubtopicOne, targetSubtopicTwo), service.findCurrentNodes(sourceSubtopic));
	}

	@Test
	void ignoresPersistedSuggestedAndConfirmedNonCurrentTargets() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO curriculum_mappings
					    (source_node_id, target_node_id, mapping_status)
					VALUES
					    (12, 22, 'SUGGESTED'),
					    (12, 32, 'CONFIRMED')
					""");
		}
		assertTrue(reopenedService().findCurrentNodes(source).isEmpty());
	}

	@Test
	void editedReviewIsReflectedAfterEachRepositoryReload() throws Exception {
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		reviewWriter.confirmMappings(source, targetVersion, List.of(targetOne, targetTwo));
		assertEquals(List.of(targetOne, targetTwo), reopenedService().findCurrentNodes(source));
		reviewWriter.replaceWithNoMatch(source, targetVersion);
		assertTrue(reopenedService().findCurrentNodes(source).isEmpty());
		SqliteCurriculumMappingReviewRepository reviewRepository = new SqliteCurriculumMappingReviewRepository(
				database);
		assertEquals(CurriculumMappingReviewOutcome.NO_MATCH,
				reviewRepository.findOutcome(source, targetVersion).orElseThrow());
		reviewWriter.replaceMappings(source, targetVersion, List.of(targetTwo));
		assertEquals(List.of(targetTwo), reopenedService().findCurrentNodes(source));
		assertEquals(CurriculumMappingReviewOutcome.MATCHED,
				new SqliteCurriculumMappingReviewRepository(database).findOutcome(source, targetVersion).orElseThrow());
	}

	@Test
	void questionReloadRetainsHistoricalClassificationWhileApplicabilityIsDerived() throws Exception {
		new SqliteCurriculumMappingReviewWriter(database).confirmMappings(source, targetVersion, List.of(targetOne));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'chemistry/QCAA/2020/paper-1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 1, 2020, 'External assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id, question_code, question_text, marks,
					     preamble_capture_required)
					VALUES (1, 1, 12, '1', '', 2, 0)
					""");
		}
		Question question = new SqliteQuestionRepository(database).findById(1).orElseThrow();
		List<CurriculumNode> currentNodes = reopenedService().findCurrentNodes(question);
		assertEquals(1, currentNodes.size());
		assertEquals(targetOne.getId(), currentNodes.getFirst().getId());
		assertEquals(source.getId(), question.getClassification().getId());
		assertFalse(question.getClassification().getSyllabusVersion().isCurrent());
	}

	@Test
	void persistedDescriptorReviewsProduceSubtopicCoverageAndNoMatchEvidence() throws Exception {
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		reviewWriter.confirmMappings(sourceSubtopicDescriptorOne, targetVersion, List.of(targetSubtopicDescriptorOne));
		reviewWriter.confirmNoMatch(sourceSubtopicDescriptorTwo, targetVersion);
		SubtopicMappingEvidenceService service = new SubtopicMappingEvidenceService(
				new SqliteCurriculumRepository(database), new SqliteCurriculumMappingReviewRepository(database));
		SubtopicMappingEvidence evidence = service.summarise(sourceSubtopic, targetVersion);
		assertEquals(2, evidence.reviewedDescriptorCount());
		assertEquals(3, evidence.totalDescriptorCount());
		assertEquals(1, evidence.noMatchDescriptorCount());
	}

	private CurrentCurriculumApplicabilityService reopenedService() {
		return new CurrentCurriculumApplicabilityService(new SqliteCurriculumMappingRepository(database));
	}

	@BeforeEach
	void setUp() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, '2019', 0),
					    (2, 1, '2025', 1),
					    (3, 1, '2022', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id, curriculum_code,
					     curriculum_name, curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1', 'Historical unit', 'UNIT', 0),
					    (11, 1, 10, '1.1', 'Historical topic', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1', 'Historical descriptor', 'DESCRIPTOR', 0),
					    (13, 1, 11, '1.1.2', 'Historical subtopic', 'SUBTOPIC', 1),
					    (14, 1, 13, '1.1.2.1', 'First source subtopic descriptor', 'DESCRIPTOR', 0),
					    (15, 1, 13, '1.1.2.2', 'Second source subtopic descriptor', 'DESCRIPTOR', 1),
					    (16, 1, 13, '1.1.2.3', 'Third source subtopic descriptor', 'DESCRIPTOR', 2),

					    (20, 2, NULL, '2', 'Current unit', 'UNIT', 0),
					    (21, 2, 20, '2.1', 'Current topic', 'TOPIC', 0),
					    (22, 2, 21, '2.1.1', 'First current descriptor', 'DESCRIPTOR', 0),
					    (23, 2, 21, '2.1.2', 'Second current descriptor', 'DESCRIPTOR', 1),
					    (24, 2, 21, '2.1.3', 'First current subtopic', 'SUBTOPIC', 2),
					    (25, 2, 21, '2.1.4', 'Second current subtopic', 'SUBTOPIC', 3),
					    (26, 2, 24, '2.1.3.1', 'First target subtopic descriptor', 'DESCRIPTOR', 0),
					    (27, 2, 25, '2.1.4.1', 'Second target subtopic descriptor', 'DESCRIPTOR', 0),

					    (30, 3, NULL, '3', 'Other historical unit', 'UNIT', 0),
					    (31, 3, 30, '3.1', 'Other historical topic', 'TOPIC', 0),
					    (32, 3, 31, '3.1.1', 'Other historical descriptor', 'DESCRIPTOR', 0)
					""");
		}
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion sourceVersion = new SyllabusVersion(1, chemistry, "2019", false);
		targetVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit sourceUnit = new Unit(10, sourceVersion, "1", "Historical unit", 0);
		Topic sourceTopic = new Topic(11, sourceVersion, sourceUnit, "1.1", "Historical topic", 0);
		source = new Descriptor(12, sourceVersion, sourceTopic, "1.1.1", "Historical descriptor", 0);
		sourceSubtopic = new Subtopic(13, sourceVersion, sourceTopic, "1.1.2", "Historical subtopic", 1);
		sourceSubtopicDescriptorOne = new Descriptor(14, sourceVersion, sourceSubtopic, "1.1.2.1",
				"First source subtopic descriptor", 0);
		sourceSubtopicDescriptorTwo = new Descriptor(15, sourceVersion, sourceSubtopic, "1.1.2.2",
				"Second source subtopic descriptor", 1);
		Unit targetUnit = new Unit(20, targetVersion, "2", "Current unit", 0);
		Topic targetTopic = new Topic(21, targetVersion, targetUnit, "2.1", "Current topic", 0);
		targetOne = new Descriptor(22, targetVersion, targetTopic, "2.1.1", "First current descriptor", 0);
		targetTwo = new Descriptor(23, targetVersion, targetTopic, "2.1.2", "Second current descriptor", 1);
		targetSubtopicOne = new Subtopic(24, targetVersion, targetTopic, "2.1.3", "First current subtopic", 2);
		targetSubtopicTwo = new Subtopic(25, targetVersion, targetTopic, "2.1.4", "Second current subtopic", 3);
		targetSubtopicDescriptorOne = new Descriptor(26, targetVersion, targetSubtopicOne, "2.1.3.1",
				"First target subtopic descriptor", 0);
	}
}
