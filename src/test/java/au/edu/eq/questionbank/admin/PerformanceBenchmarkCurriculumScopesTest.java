package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class PerformanceBenchmarkCurriculumScopesTest {

	@TempDir
	Path tempDirectory;

	@Test
	void expandsSubjectUnitAndTopicWithDeterministicOrdering() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(fixture.snapshotPath());
		List<Long> expectedFirstTopic = List.of(fixture.subtopicId(), fixture.descriptorId(),
				fixture.directDescriptorId());
		assertEquals(expectedFirstTopic, nodeIds(scopes.topic(fixture.topicId())));
		assertEquals(expectedFirstTopic, nodeIds(scopes.unit(fixture.unitId())));
		assertEquals(List.of(fixture.subtopicId(), fixture.descriptorId(), fixture.directDescriptorId(),
				fixture.secondDescriptorId()), nodeIds(scopes.subject(fixture.subjectId())));
		assertEquals(List.of(fixture.descriptorId()), nodeIds(scopes.descriptor(fixture.descriptorId())));
	}

	@Test
	void rejectsHistoricalUnknownAndIncorrectLevelSelections() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(fixture.snapshotPath());
		assertThrows(IllegalArgumentException.class, () -> scopes.descriptor(fixture.historicalDescriptorId()));
		assertThrows(IllegalArgumentException.class, () -> scopes.descriptor(fixture.topicId()));
		assertThrows(IllegalArgumentException.class, () -> scopes.topic(fixture.unitId()));
		assertThrows(IllegalArgumentException.class, () -> scopes.subject(999999L));
		assertThrows(IllegalArgumentException.class, () -> scopes.unit(-1L));
		assertThrows(IllegalArgumentException.class, () -> scopes.descriptor(0L));
	}

	@Test
	void selectionProducesExpectedProductionRetrievalCounts() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(fixture.snapshotPath());
		PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(fixture.snapshotPath());
		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();
		PerformanceBenchmarkResult subject = runner.run("search.current.subject", 1, 2,
				workloads.findApplicableToNodes(scopes.subject(fixture.subjectId())));
		PerformanceBenchmarkResult unit = runner.run("search.current.unit", 1, 2,
				workloads.findApplicableToNodes(scopes.unit(fixture.unitId())));
		PerformanceBenchmarkResult topic = runner.run("search.current.topic", 1, 2,
				workloads.findApplicableToNodes(scopes.topic(fixture.topicId())));
		PerformanceBenchmarkResult descriptor = runner.run("search.current.descriptor", 1, 2,
				workloads.findApplicableToNodes(scopes.descriptor(fixture.descriptorId())));
		assertEquals(4, subject.resultCount());
		assertEquals(3, unit.resultCount());
		assertEquals(3, topic.resultCount());
		assertEquals(1, descriptor.resultCount());
	}

	@Test
	void supportsDescriptorDirectlyUnderTopic() throws Exception {
		Fixture fixture = createFixture();
		PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(fixture.snapshotPath());
		assertEquals(List.of(fixture.secondDescriptorId()), nodeIds(scopes.topic(fixture.secondTopicId())));
		assertEquals(List.of(fixture.directDescriptorId()), nodeIds(scopes.descriptor(fixture.directDescriptorId())));
	}

	private Fixture createFixture() throws Exception {
		Path source = tempDirectory.resolve("source.db");
		Path snapshot = tempDirectory.resolve("benchmark.db");
		SqliteDatabase database = new SqliteDatabase(source);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion historical = writer.insertSyllabusVersion(chemistry, "2019", false);
		Unit oldUnit = writer.insertUnit(historical, "1", "Historical unit", 1);
		Topic oldTopic = writer.insertTopic(oldUnit, "1.1", "Historical topic", 1);
		Descriptor oldDescriptor = writer.insertDescriptor(oldTopic, "1.1.1", "Historical descriptor", 1);
		SyllabusVersion current = writer.insertSyllabusVersion(chemistry, "2025", true);
		Unit firstUnit = writer.insertUnit(current, "1", "Unit 1", 1);
		Topic firstTopic = writer.insertTopic(firstUnit, "1.1", "Topic 1", 1);
		Subtopic subtopic = writer.insertSubtopic(firstTopic, "1.1.1", "Subtopic 1", 1);
		Descriptor descriptor = writer.insertDescriptor(subtopic, "1.1.1.1", "Descriptor 1", 1);
		Descriptor directDescriptor = writer.insertDescriptor(firstTopic, "1.1.2", "Direct descriptor", 2);
		Unit secondUnit = writer.insertUnit(current, "2", "Unit 2", 2);
		Topic secondTopic = writer.insertTopic(secondUnit, "2.1", "Topic 2", 1);
		Descriptor secondDescriptor = writer.insertDescriptor(secondTopic, "2.1.1", "Descriptor 2", 1);
		SqliteExamImporter importer = new SqliteExamImporter(database, new SqliteExamWriter(database));
		ExamBooklet booklet = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		questions.save(booklet, "Q1", "", 2, List.of(), subtopic, false);
		questions.save(booklet, "Q2", "", 2, List.of(), descriptor, false);
		questions.save(booklet, "Q3", "", 2, List.of(), directDescriptor, false);
		questions.save(booklet, "Q4", "", 2, List.of(), secondDescriptor, false);
		database.createConsistentSnapshot(snapshot);
		return new Fixture(snapshot, chemistry.getId(), firstUnit.getId(), firstTopic.getId(), secondTopic.getId(),
				subtopic.getId(), descriptor.getId(), directDescriptor.getId(), secondDescriptor.getId(),
				oldDescriptor.getId());
	}

	private List<Long> nodeIds(List<CurriculumNode> nodes) {
		return nodes.stream().map(CurriculumNode::getId).toList();
	}

	private record Fixture(Path snapshotPath, long subjectId, long unitId, long topicId, long secondTopicId,
			long subtopicId, long descriptorId, long directDescriptorId, long secondDescriptorId,
			long historicalDescriptorId) {
	}
}
