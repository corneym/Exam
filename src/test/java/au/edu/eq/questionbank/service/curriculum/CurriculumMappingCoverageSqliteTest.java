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

import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumMappingCoverageSqliteTest {

	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private SqliteCurriculumRepository curriculumRepository;
	private SqliteCurriculumMappingReviewWriter reviewWriter;
	private SyllabusVersion sourceVersion;
	private SyllabusVersion targetVersion;
	private Subtopic sourceSubtopicOne;
	private Subtopic sourceSubtopicTwo;
	private Descriptor sourceDescriptorOne;
	private Descriptor sourceDescriptorTwo;
	private Descriptor sourceDescriptorThree;
	private Subtopic targetSubtopicOne;
	private Subtopic targetSubtopicTwo;
	private Descriptor targetDescriptorOne;
	private Descriptor targetDescriptorTwo;

	@Test
	void becomesCompleteAfterEveryApplicableSourceIsReviewed() throws Exception {
		reviewWriter.confirmMappings(sourceDescriptorOne, targetVersion,
				List.of(targetDescriptorOne, targetDescriptorTwo));
		reviewWriter.confirmNoMatch(sourceDescriptorTwo, targetVersion);
		reviewWriter.confirmMappings(sourceDescriptorThree, targetVersion, List.of(targetDescriptorTwo));
		reviewWriter.confirmMappings(sourceSubtopicOne, targetVersion, List.of(targetSubtopicOne, targetSubtopicTwo));
		reviewWriter.confirmNoMatch(sourceSubtopicTwo, targetVersion);
		CurriculumMappingCoverage coverage = service().calculateCoverage(sourceVersion, targetVersion);
		assertEquals(3, coverage.descriptorCoverage().total());
		assertEquals(2, coverage.descriptorCoverage().matched());
		assertEquals(1, coverage.descriptorCoverage().noMatch());
		assertEquals(0, coverage.descriptorCoverage().unreviewed());
		assertEquals(0, coverage.descriptorCoverage().inconsistent());
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, coverage.descriptorCoverage().status());
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, coverage.subtopicCoverage().status());
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, coverage.status());
		assertTrue(coverage.complete());
		assertEquals(0, coverage.descriptorCoverage().uncoveredTargetCount());
		assertEquals(0, coverage.subtopicCoverage().uncoveredTargetCount());
		assertEquals(100.0, coverage.descriptorCoverage().reviewedPercentage(), 0.0001);
		assertEquals(100.0, coverage.subtopicCoverage().reviewedPercentage(), 0.0001);
	}

	@Test
	void detectsPersistedContradictionsAndIgnoresSuggestedMappings() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO curriculum_mappings
					    (source_node_id, target_node_id, mapping_status)
					VALUES
					    (14, 24, 'CONFIRMED'),
					    (15, 24, 'SUGGESTED')
					""");
			statement.execute("""
					INSERT INTO curriculum_mapping_reviews
					    (source_node_id, target_syllabus_version_id, review_outcome)
					VALUES
					    (16, 2, 'MATCHED')
					""");
		}
		CurriculumMappingCoverage coverage = service().calculateCoverage(sourceVersion, targetVersion);
		CurriculumMappingLevelCoverage descriptors = coverage.descriptorCoverage();
		/*
		 * 14: confirmed mapping but no review -> inconsistent 15: suggested mapping
		 * only -> unreviewed 16: MATCHED review but no confirmed mapping ->
		 * inconsistent
		 */
		assertEquals(3, descriptors.total());
		assertEquals(0, descriptors.matched());
		assertEquals(0, descriptors.noMatch());
		assertEquals(1, descriptors.unreviewed());
		assertEquals(2, descriptors.inconsistent());
		assertEquals(CurriculumMappingCoverageStatus.INCOMPLETE, descriptors.status());
		assertEquals(CurriculumMappingReviewOutcome.MATCHED, new SqliteCurriculumMappingReviewRepository(database)
				.findOutcome(sourceDescriptorThree, targetVersion).orElseThrow());
	}

	@Test
	void reportsPersistedMatchedNoMatchAndUnreviewedCoverage() throws Exception {
		reviewWriter.confirmMappings(sourceDescriptorOne, targetVersion, List.of(targetDescriptorOne));
		reviewWriter.confirmNoMatch(sourceDescriptorTwo, targetVersion);
		reviewWriter.confirmMappings(sourceSubtopicOne, targetVersion, List.of(targetSubtopicOne));
		reviewWriter.confirmNoMatch(sourceSubtopicTwo, targetVersion);
		CurriculumMappingCoverage coverage = service().calculateCoverage(sourceVersion, targetVersion);
		CurriculumMappingLevelCoverage descriptors = coverage.descriptorCoverage();
		assertEquals(3, descriptors.total());
		assertEquals(1, descriptors.matched());
		assertEquals(1, descriptors.noMatch());
		assertEquals(1, descriptors.unreviewed());
		assertEquals(0, descriptors.inconsistent());
		assertEquals(CurriculumMappingCoverageStatus.INCOMPLETE, descriptors.status());
		assertEquals(1, descriptors.uncoveredTargetCount());
		assertEquals(2, descriptors.deliberatelyReviewed());
		assertEquals(66.66666666666667, descriptors.reviewedPercentage(), 0.0001);
		CurriculumMappingLevelCoverage subtopics = coverage.subtopicCoverage();
		assertEquals(2, subtopics.total());
		assertEquals(1, subtopics.matched());
		assertEquals(1, subtopics.noMatch());
		assertEquals(0, subtopics.unreviewed());
		assertEquals(0, subtopics.inconsistent());
		assertEquals(CurriculumMappingCoverageStatus.COMPLETE, subtopics.status());
		assertEquals(1, subtopics.uncoveredTargetCount());
		assertEquals(100.0, subtopics.reviewedPercentage(), 0.0001);
		assertEquals(CurriculumMappingCoverageStatus.INCOMPLETE, coverage.status());
		assertFalse(coverage.complete());
	}

	@BeforeEach
	void setUp() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects
					    (id, subject_name)
					VALUES
					    (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, '2019', 0),
					    (2, 1, '2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1',
					     'Historical unit', 'UNIT', 0),

					    (11, 1, 10, '1.1',
					     'Historical topic', 'TOPIC', 0),

					    (12, 1, 11, '1.1.1',
					     'Historical subtopic one', 'SUBTOPIC', 0),

					    (13, 1, 11, '1.1.2',
					     'Historical subtopic two', 'SUBTOPIC', 1),

					    (14, 1, 12, '1.1.1.1',
					     'Historical descriptor one', 'DESCRIPTOR', 0),

					    (15, 1, 12, '1.1.1.2',
					     'Historical descriptor two', 'DESCRIPTOR', 1),

					    (16, 1, 13, '1.1.2.1',
					     'Historical descriptor three', 'DESCRIPTOR', 0),

					    (20, 2, NULL, '1',
					     'Current unit', 'UNIT', 0),

					    (21, 2, 20, '1.1',
					     'Current topic', 'TOPIC', 0),

					    (22, 2, 21, '1.1.1',
					     'Current subtopic one', 'SUBTOPIC', 0),

					    (23, 2, 21, '1.1.2',
					     'Current subtopic two', 'SUBTOPIC', 1),

					    (24, 2, 22, '1.1.1.1',
					     'Current descriptor one', 'DESCRIPTOR', 0),

					    (25, 2, 23, '1.1.2.1',
					     'Current descriptor two', 'DESCRIPTOR', 0)
					""");
		}
		curriculumRepository = new SqliteCurriculumRepository(database);
		reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		sourceVersion = curriculumRepository.findVersionById(1).orElseThrow();
		targetVersion = curriculumRepository.findVersionById(2).orElseThrow();
		sourceSubtopicOne = (Subtopic) findSource("1.1.1");
		sourceSubtopicTwo = (Subtopic) findSource("1.1.2");
		sourceDescriptorOne = (Descriptor) findSource("1.1.1.1");
		sourceDescriptorTwo = (Descriptor) findSource("1.1.1.2");
		sourceDescriptorThree = (Descriptor) findSource("1.1.2.1");
		targetSubtopicOne = (Subtopic) findTarget("1.1.1");
		targetSubtopicTwo = (Subtopic) findTarget("1.1.2");
		targetDescriptorOne = (Descriptor) findTarget("1.1.1.1");
		targetDescriptorTwo = (Descriptor) findTarget("1.1.2.1");
	}

	private CurriculumNode findSource(String code) {
		return curriculumRepository.findByCode(sourceVersion, code).orElseThrow();
	}

	private CurriculumNode findTarget(String code) {
		return curriculumRepository.findByCode(targetVersion, code).orElseThrow();
	}

	private CurriculumMappingCoverageService service() {
		return new CurriculumMappingCoverageService(new SqliteCurriculumRepository(database),
				new SqliteCurriculumMappingRepository(database), new SqliteCurriculumMappingReviewRepository(database));
	}
}
