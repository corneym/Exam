package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumMappingRepositoryTest {

	@TempDir
	Path tempDir;

	private record Fixture(SqliteDatabase database, SqliteCurriculumMappingWriter writer, List<CurriculumNode> sources,
			List<CurriculumNode> targets) {
	}

	private Fixture createFixture(boolean arbitraryCodes) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("mapping.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Biology");
		SyllabusVersion source = writer.insertSyllabusVersion(subject, "Original", false);
		SyllabusVersion target = writer.insertSyllabusVersion(subject, "Revised", true);
		return new Fixture(database, new SqliteCurriculumMappingWriter(database),
				createHierarchy(writer, source, "1", arbitraryCodes),
				createHierarchy(writer, target, "2", arbitraryCodes));
	}

	private List<CurriculumNode> createHierarchy(SqliteCurriculumWriter writer, SyllabusVersion version, String prefix,
			boolean arbitraryCodes) throws Exception {
		Unit unit = writer.insertUnit(version, arbitraryCodes ? "Unit." + prefix : prefix, "Unit", 1);
		Topic topic = writer.insertTopic(unit, arbitraryCodes ? "Topic " + prefix : prefix + ".1", "Topic", 1);
		Subtopic subtopic = writer.insertSubtopic(topic, arbitraryCodes ? "Subtopic " + prefix : prefix + ".1.1",
				"Subtopic", 1);
		Descriptor direct = writer.insertDescriptor(topic, arbitraryCodes ? "Direct " + prefix : prefix + ".1.2",
				"Direct descriptor", 2);
		Descriptor nested = writer.insertDescriptor(subtopic, arbitraryCodes ? "Nested " + prefix : prefix + ".1.1.1",
				"Nested descriptor", 1);
		return List.of(unit, topic, subtopic, direct, nested);
	}

	private void assertHierarchy(CurriculumNode expected, CurriculumNode actual) {
		while (expected != null) {
			assertNotNull(actual);
			assertNotSame(expected, actual);
			assertEquals(expected.getId(), actual.getId());
			assertEquals(expected.getClass(), actual.getClass());
			assertEquals(expected.getCode(), actual.getCode());
			assertEquals(expected.getName(), actual.getName());
			assertEquals(expected.getDisplayOrder(), actual.getDisplayOrder());
			assertEquals(expected.getSyllabusVersion(), actual.getSyllabusVersion());
			assertEquals(expected.getSyllabusVersion().getSubject(), actual.getSyllabusVersion().getSubject());
			expected = expected.getParent();
			actual = actual.getParent();
		}
		assertNull(actual);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2, 3, 4 })
	void reconstructsEveryNodeShapeByIdentityWithoutInferringCodeAncestry(int nodeIndex) throws Exception {
		Fixture fixture = createFixture(true);
		CurriculumNode source = fixture.sources().get(nodeIndex);
		CurriculumNode target = fixture.targets().get(nodeIndex);
		CurriculumMapping stored = fixture.writer().insertMapping(source, target, MappingStatus.SUGGESTED);
		SqliteDatabase reopened = new SqliteDatabase(tempDir.resolve("mapping.db"));
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(reopened);
		CurriculumMapping loaded = repository.findAll().getFirst();
		assertEquals(stored.getId(), loaded.getId());
		assertEquals(MappingStatus.SUGGESTED, loaded.getStatus());
		assertHierarchy(source, loaded.getSource());
		assertHierarchy(target, loaded.getTarget());
		new SqliteCurriculumMappingWriter(reopened).updateStatus(loaded, MappingStatus.CONFIRMED);
		CurriculumMapping confirmed = new SqliteCurriculumMappingRepository(
				new SqliteDatabase(tempDir.resolve("mapping.db"))).findTargets(source).getFirst();
		assertEquals(stored.getId(), confirmed.getId());
		assertEquals(MappingStatus.CONFIRMED, confirmed.getStatus());
		assertHierarchy(source, confirmed.getSource());
		assertHierarchy(target, confirmed.getTarget());
	}

	@Test
	void filtersDirectionalManyToManyMappingsInPersistentIdOrder() throws Exception {
		Fixture fixture = createFixture(false);
		CurriculumNode source = fixture.sources().getFirst();
		CurriculumNode target = fixture.targets().getFirst();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(fixture.database());
		Unit otherSource = writer.insertUnit(source.getSyllabusVersion(), "9", "Other source", 2);
		Unit otherTarget = writer.insertUnit(target.getSyllabusVersion(), "8", "Other target", 2);
		CurriculumMapping first = fixture.writer().insertMapping(source, otherTarget, MappingStatus.SUGGESTED);
		CurriculumMapping second = fixture.writer().insertMapping(source, target, MappingStatus.CONFIRMED);
		CurriculumMapping third = fixture.writer().insertMapping(otherSource, target, MappingStatus.SUGGESTED);
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(fixture.database());
		assertEquals(List.of(first, second, third), repository.findAll());
		assertEquals(List.of(first, second), repository.findTargets(source));
		assertEquals(List.of(second, third), repository.findSources(target));
		assertEquals(List.of(), repository.findSources(source));
		assertEquals(List.of(), repository.findTargets(target));
		assertEquals(List.of(), repository.findTargets(new Unit(999999, source.getSyllabusVersion(), source.getCode(),
				source.getName(), source.getDisplayOrder())));
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void missingEndpointIsReportedRatherThanSilentlyOmittingMapping(boolean removeSource) throws Exception {
		Fixture fixture = createFixture(false);
		CurriculumNode source = fixture.sources().getFirst();
		CurriculumNode target = fixture.targets().getFirst();
		fixture.writer().insertMapping(source, target, MappingStatus.CONFIRMED);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM curriculum_nodes WHERE id = ?")) {
				delete.setLong(1, removeSource ? source.getId() : target.getId());
				delete.executeUpdate();
			}
		}
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(fixture.database());
		assertThrows(IllegalStateException.class, repository::findAll);
		assertThrows(IllegalStateException.class, () -> repository.findTargets(source));
		assertThrows(IllegalStateException.class, () -> repository.findSources(target));
	}

	@ParameterizedTest
	@ValueSource(strings = { "same_version", "different_level", "invalid_status" })
	void reportsInvalidStoredMappingsAsRepositoryFailures(String corruption) throws Exception {
		Fixture fixture = createFixture(false);
		CurriculumNode source = fixture.sources().getFirst();
		CurriculumNode target = fixture.targets().getFirst();
		CurriculumMapping mapping = fixture.writer().insertMapping(source, target, MappingStatus.SUGGESTED);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			if ("invalid_status".equals(corruption)) {
				statement.execute("PRAGMA ignore_check_constraints = ON");
				statement.execute("UPDATE curriculum_mappings SET mapping_status = 'UNKNOWN'");
			} else {
				long targetId;
				if ("same_version".equals(corruption)) {
					Unit sameVersion = new SqliteCurriculumWriter(fixture.database())
							.insertUnit(source.getSyllabusVersion(), "9", "Same version", 2);
					targetId = sameVersion.getId();
				} else {
					targetId = fixture.targets().get(1).getId();
				}
				try (PreparedStatement update = connection
						.prepareStatement("UPDATE curriculum_mappings SET target_node_id = ? WHERE id = ?")) {
					update.setLong(1, targetId);
					update.setLong(2, mapping.getId());
					update.executeUpdate();
				}
			}
		}
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(fixture.database());
		IllegalStateException failure = assertThrows(IllegalStateException.class, repository::findAll);
		assertTrue(failure.getMessage().contains(Long.toString(mapping.getId())));
	}

	@ParameterizedTest
	@ValueSource(strings = { "cross_version_parent", "missing_parent", "missing_version", "cycle" })
	void rejectsBrokenStoredHierarchy(String corruption) throws Exception {
		Fixture fixture = createFixture(false);
		CurriculumNode source = fixture.sources().get(3);
		CurriculumNode target = fixture.targets().get(3);
		fixture.writer().insertMapping(source, target, MappingStatus.CONFIRMED);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			if ("missing_version".equals(corruption)) {
				try (PreparedStatement delete = connection
						.prepareStatement("DELETE FROM syllabus_versions WHERE id = ?")) {
					delete.setLong(1, source.getSyllabusVersion().getId());
					delete.executeUpdate();
				}
			} else {
				long nodeId = source.getId();
				long parentId = target.getParent().getId();
				if ("missing_parent".equals(corruption)) {
					parentId = 999999;
				} else if ("cycle".equals(corruption)) {
					nodeId = source.getParent().getId();
					parentId = nodeId;
				}
				try (PreparedStatement update = connection
						.prepareStatement("UPDATE curriculum_nodes SET parent_id = ? WHERE id = ?")) {
					update.setLong(1, parentId);
					update.setLong(2, nodeId);
					update.executeUpdate();
				}
			}
		}
		assertThrows(IllegalStateException.class,
				() -> new SqliteCurriculumMappingRepository(fixture.database()).findAll());
	}

	@Test
	void emptyRepositoryAndNullLookupsHaveExplicitBehaviour() throws Exception {
		Fixture fixture = createFixture(false);
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(fixture.database());
		assertEquals(List.of(), repository.findAll());
		assertEquals(List.of(), repository.findTargets(fixture.sources().getFirst()));
		assertEquals(List.of(), repository.findSources(fixture.targets().getFirst()));
		assertThrows(NullPointerException.class, () -> new SqliteCurriculumMappingRepository(null));
		assertThrows(NullPointerException.class, () -> repository.findTargets(null));
		assertThrows(NullPointerException.class, () -> repository.findSources(null));
	}

	@Test
	void findAllReconstructsStoredMapping() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit2019 = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		Topic topic2019 = curriculumWriter.insertTopic(unit2019, "3.1", "Old Topic 3.1", 1);
		Subtopic subtopic2019 = curriculumWriter.insertSubtopic(topic2019, "3.1.1", "Old subtopic", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit2025 = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		Topic topic2025 = curriculumWriter.insertTopic(unit2025, "4.1", "New Topic 4.1", 1);
		Subtopic subtopic2025 = curriculumWriter.insertSubtopic(topic2025, "4.1.1", "New subtopic", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		CurriculumMapping stored = mappingWriter.insertMapping(subtopic2019, subtopic2025, MappingStatus.CONFIRMED);
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(database);
		List<CurriculumMapping> mappings = repository.findAll();
		assertEquals(1, mappings.size());
		CurriculumMapping loaded = mappings.getFirst();
		assertEquals(stored.getId(), loaded.getId());
		assertEquals(MappingStatus.CONFIRMED, loaded.getStatus());
		assertEquals(subtopic2019.getId(), loaded.getSource().getId());
		assertEquals(subtopic2025.getId(), loaded.getTarget().getId());
		assertEquals("2019", loaded.getSource().getSyllabusVersion().getName());
		assertEquals("2025", loaded.getTarget().getSyllabusVersion().getName());
		assertInstanceOf(Subtopic.class, loaded.getSource());
		assertInstanceOf(Subtopic.class, loaded.getTarget());
	}

	@Test
	void findSourcesReturnsMappingsToSuppliedTarget() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit2019 = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		Topic topic2019 = curriculumWriter.insertTopic(unit2019, "3.1", "Old Topic 3.1", 1);
		Subtopic subtopic2019 = curriculumWriter.insertSubtopic(topic2019, "3.1.1", "Old subtopic", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit2025 = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		Topic topic2025 = curriculumWriter.insertTopic(unit2025, "4.1", "New Topic 4.1", 1);
		Subtopic subtopic2025 = curriculumWriter.insertSubtopic(topic2025, "4.1.1", "New subtopic", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		mappingWriter.insertMapping(unit2019, unit2025, MappingStatus.CONFIRMED);
		CurriculumMapping subtopicMapping = mappingWriter.insertMapping(subtopic2019, subtopic2025,
				MappingStatus.SUGGESTED);
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(database);
		List<CurriculumMapping> mappings = repository.findSources(subtopic2025);
		assertEquals(1, mappings.size());
		assertEquals(subtopicMapping.getId(), mappings.getFirst().getId());
		assertEquals(subtopic2019.getId(), mappings.getFirst().getSource().getId());
		assertEquals(subtopic2025.getId(), mappings.getFirst().getTarget().getId());
	}

	@Test
	void findTargetsReturnsMappingsFromSuppliedSource() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit2019 = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		Topic topic2019 = curriculumWriter.insertTopic(unit2019, "3.1", "Old Topic 3.1", 1);
		Subtopic subtopic2019 = curriculumWriter.insertSubtopic(topic2019, "3.1.1", "Old subtopic", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit2025 = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		Topic topic2025 = curriculumWriter.insertTopic(unit2025, "4.1", "New Topic 4.1", 1);
		Subtopic subtopic2025 = curriculumWriter.insertSubtopic(topic2025, "4.1.1", "New subtopic", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		mappingWriter.insertMapping(unit2019, unit2025, MappingStatus.CONFIRMED);
		CurriculumMapping subtopicMapping = mappingWriter.insertMapping(subtopic2019, subtopic2025,
				MappingStatus.SUGGESTED);
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(database);
		List<CurriculumMapping> mappings = repository.findTargets(subtopic2019);
		assertEquals(1, mappings.size());
		assertEquals(subtopicMapping.getId(), mappings.getFirst().getId());
		assertEquals(subtopic2019.getId(), mappings.getFirst().getSource().getId());
		assertEquals(subtopic2025.getId(), mappings.getFirst().getTarget().getId());
	}
}
