package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumMappingWriterTest {
	@TempDir
	Path tempDir;

	private record Fixture(SqliteDatabase database, SqliteCurriculumMappingWriter writer, Unit source, Unit target) {
	}

	private Fixture createFixture() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("mapping.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Biology");
		SyllabusVersion sourceVersion = writer.insertSyllabusVersion(subject, "Original", false);
		SyllabusVersion targetVersion = writer.insertSyllabusVersion(subject, "Revised", true);
		Unit source = writer.insertUnit(sourceVersion, "1", "Original unit", 1);
		Unit target = writer.insertUnit(targetVersion, "2", "Revised unit", 1);
		return new Fixture(database, new SqliteCurriculumMappingWriter(database), source, target);
	}

	private void assertMappingCount(SqliteDatabase database, int expected) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM curriculum_mappings")) {
			assertTrue(result.next());
			assertEquals(expected, result.getInt(1));
		}
	}

	private void assertStoredMapping(SqliteDatabase database, CurriculumMapping mapping, MappingStatus status)
			throws Exception {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT source_node_id, target_node_id, mapping_status
						FROM curriculum_mappings WHERE id = ?
						""")) {
			statement.setLong(1, mapping.getId());
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				assertEquals(mapping.getSource().getId(), result.getLong("source_node_id"));
				assertEquals(mapping.getTarget().getId(), result.getLong("target_node_id"));
				assertEquals(status.name(), result.getString("mapping_status"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void duplicateInsertionLeavesExistingMappingUnchanged() throws Exception {
		Fixture fixture = createFixture();
		CurriculumMapping stored = fixture.writer().insertMapping(fixture.source(), fixture.target(),
				MappingStatus.SUGGESTED);
		assertThrows(SQLException.class,
				() -> fixture.writer().insertMapping(fixture.source(), fixture.target(), MappingStatus.CONFIRMED));
		assertMappingCount(fixture.database(), 1);
		assertStoredMapping(fixture.database(), stored, MappingStatus.SUGGESTED);
	}

	@Test
	void rejectsMissingTargetWithoutWritingMapping() throws Exception {
		Fixture fixture = createFixture();
		Unit missingTarget = new Unit(999999, fixture.target().getSyllabusVersion(), "9", "Missing target", 1);
		assertThrows(IllegalArgumentException.class,
				() -> fixture.writer().insertMapping(fixture.source(), missingTarget, MappingStatus.CONFIRMED));
		assertMappingCount(fixture.database(), 0);
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void rejectsFabricatedVersionOnEitherEndpoint(boolean fabricateSource) throws Exception {
		Fixture fixture = createFixture();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(fixture.database());
		Unit otherSource = curriculumWriter.insertUnit(fixture.source().getSyllabusVersion(), "3", "Other", 2);
		Unit source = fixture.source();
		Unit target = fixture.target();
		if (fabricateSource) {
			source = new Unit(otherSource.getId(), target.getSyllabusVersion(), "3", "Fabricated", 2);
			target = fixture.source();
		} else {
			target = new Unit(otherSource.getId(), target.getSyllabusVersion(), "3", "Fabricated", 2);
		}
		Unit suppliedSource = source;
		Unit suppliedTarget = target;
		assertThrows(IllegalArgumentException.class,
				() -> fixture.writer().insertMapping(suppliedSource, suppliedTarget, MappingStatus.SUGGESTED));
		assertMappingCount(fixture.database(), 0);
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void rejectsFabricatedLevelOnEitherEndpoint(boolean fabricateSource) throws Exception {
		Fixture fixture = createFixture();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(fixture.database());
		Unit parent = fabricateSource ? fixture.source() : fixture.target();
		Topic topic = curriculumWriter.insertTopic(parent, parent.getCode() + ".1", "Actual topic", 1);
		Unit falseUnit = new Unit(topic.getId(), topic.getSyllabusVersion(), topic.getCode(), "False unit", 1);
		Unit source = fabricateSource ? falseUnit : fixture.source();
		Unit target = fabricateSource ? fixture.target() : falseUnit;
		assertThrows(IllegalArgumentException.class,
				() -> fixture.writer().insertMapping(source, target, MappingStatus.SUGGESTED));
		assertMappingCount(fixture.database(), 0);
	}

	@Test
	void rejectsMissingMappingDuringStatusUpdateWithoutChangingOtherRows() throws Exception {
		Fixture fixture = createFixture();
		CurriculumMapping stored = fixture.writer().insertMapping(fixture.source(), fixture.target(),
				MappingStatus.SUGGESTED);
		CurriculumMapping missing = new CurriculumMapping(999999, fixture.source(), fixture.target(),
				MappingStatus.SUGGESTED);
		assertThrows(SQLException.class, () -> fixture.writer().updateStatus(missing, MappingStatus.CONFIRMED));
		assertStoredMapping(fixture.database(), stored, MappingStatus.SUGGESTED);
		assertMappingCount(fixture.database(), 1);
	}

	@Test
	void statusUpdateRevalidatesEndpointIdentity() throws Exception {
		Fixture fixture = createFixture();
		CurriculumMapping stored = fixture.writer().insertMapping(fixture.source(), fixture.target(),
				MappingStatus.SUGGESTED);
		SyllabusVersion falseVersion = new SyllabusVersion(999999, fixture.source().getSyllabusVersion().getSubject(),
				"Fabricated", false);
		Unit falseSource = new Unit(fixture.source().getId(), falseVersion, "1", "False source", 1);
		CurriculumMapping falseMapping = new CurriculumMapping(stored.getId(), falseSource, fixture.target(),
				MappingStatus.SUGGESTED);
		assertThrows(IllegalArgumentException.class,
				() -> fixture.writer().updateStatus(falseMapping, MappingStatus.CONFIRMED));
		assertStoredMapping(fixture.database(), stored, MappingStatus.SUGGESTED);
	}

	@Test
	void nullArgumentsDoNotChangePersistentState() throws Exception {
		Fixture fixture = createFixture();
		assertThrows(NullPointerException.class, () -> new SqliteCurriculumMappingWriter(null));
		assertThrows(NullPointerException.class,
				() -> fixture.writer().insertMapping(null, fixture.target(), MappingStatus.SUGGESTED));
		assertThrows(NullPointerException.class,
				() -> fixture.writer().insertMapping(fixture.source(), null, MappingStatus.SUGGESTED));
		assertThrows(NullPointerException.class,
				() -> fixture.writer().insertMapping(fixture.source(), fixture.target(), null));
		assertMappingCount(fixture.database(), 0);
		CurriculumMapping stored = fixture.writer().insertMapping(fixture.source(), fixture.target(),
				MappingStatus.SUGGESTED);
		assertThrows(NullPointerException.class, () -> fixture.writer().updateStatus(null, MappingStatus.CONFIRMED));
		assertThrows(NullPointerException.class, () -> fixture.writer().updateStatus(stored, null));
		assertStoredMapping(fixture.database(), stored, MappingStatus.SUGGESTED);
	}

	@ParameterizedTest
	@ValueSource(strings = { "INSERT", "UPDATE" })
	void rollsBackWriteWhenSqlFailsAfterChangingRow(String operation) throws Exception {
		Fixture fixture = createFixture();
		CurriculumMapping stored = "UPDATE".equals(operation)
				? fixture.writer().insertMapping(fixture.source(), fixture.target(), MappingStatus.SUGGESTED) : null;
		try (Connection connection = fixture.database().openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER fail_mapping_write AFTER %s ON curriculum_mappings
					BEGIN
					    SELECT RAISE(FAIL, 'Injected failure after row change');
					END
					""".formatted(operation));
		}
		if (stored == null) {
			assertThrows(SQLException.class,
					() -> fixture.writer().insertMapping(fixture.source(), fixture.target(), MappingStatus.CONFIRMED));
			assertMappingCount(fixture.database(), 0);
		} else {
			assertThrows(SQLException.class, () -> fixture.writer().updateStatus(stored, MappingStatus.CONFIRMED));
			assertStoredMapping(fixture.database(), stored, MappingStatus.SUGGESTED);
		}
	}

	@Test
	void insertsMappingAndReturnsPersistentId() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit2019 = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		Topic topic2019 = curriculumWriter.insertTopic(unit2019, "3.1", "Old Topic 3.1", 1);
		Subtopic subtopic2019 = curriculumWriter.insertSubtopic(topic2019, "3.1.1", "Old subtopic", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit2025 = curriculumWriter.insertUnit(syllabus2025, "3", "New Unit 3", 1);
		Topic topic2025 = curriculumWriter.insertTopic(unit2025, "3.1", "New Topic 3.1", 1);
		Subtopic subtopic2025 = curriculumWriter.insertSubtopic(topic2025, "3.1.1", "New subtopic", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		CurriculumMapping mapping = mappingWriter.insertMapping(subtopic2019, subtopic2025, MappingStatus.SUGGESTED);
		assertTrue(mapping.getId() > 0);
		assertEquals(subtopic2019, mapping.getSource());
		assertEquals(subtopic2025, mapping.getTarget());
		assertEquals(MappingStatus.SUGGESTED, mapping.getStatus());
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT source_node_id, target_node_id, mapping_status
						FROM curriculum_mappings
						WHERE id = ?
						""")) {
			statement.setLong(1, mapping.getId());
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				assertEquals(subtopic2019.getId(), result.getLong("source_node_id"));
				assertEquals(subtopic2025.getId(), result.getLong("target_node_id"));
				assertEquals("SUGGESTED", result.getString("mapping_status"));
			}
		}
	}

	@Test
	void reconstructsDescriptorMapping() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit2019 = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		Topic topic2019 = curriculumWriter.insertTopic(unit2019, "3.1", "Old Topic 3.1", 1);
		Descriptor descriptor2019 = curriculumWriter.insertDescriptor(topic2019, "3.1.1", "Old descriptor", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit2025 = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		Topic topic2025 = curriculumWriter.insertTopic(unit2025, "4.1", "New Topic 4.1", 1);
		Descriptor descriptor2025 = curriculumWriter.insertDescriptor(topic2025, "4.1.1", "New descriptor", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		CurriculumMapping stored = mappingWriter.insertMapping(descriptor2019, descriptor2025, MappingStatus.CONFIRMED);
		SqliteCurriculumMappingRepository repository = new SqliteCurriculumMappingRepository(database);
		List<CurriculumMapping> mappings = repository.findAll();
		assertEquals(1, mappings.size());
		CurriculumMapping loaded = mappings.getFirst();
		assertEquals(stored.getId(), loaded.getId());
		assertInstanceOf(Descriptor.class, loaded.getSource());
		assertInstanceOf(Descriptor.class, loaded.getTarget());
		assertEquals("3.1.1", loaded.getSource().getCode());
		assertEquals("4.1.1", loaded.getTarget().getCode());
	}

	@Test
	void rejectsMappingWhenPersistentSourceBelongsToDifferentSubject() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion chemistry2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit chemistryUnit = curriculumWriter.insertUnit(chemistry2019, "1", "Chemistry Unit", 1);
		Subject physics = curriculumWriter.insertSubject("Physics");
		SyllabusVersion physics2019 = curriculumWriter.insertSyllabusVersion(physics, "2019", false);
		SyllabusVersion physics2025 = curriculumWriter.insertSyllabusVersion(physics, "2025", true);
		Unit physicsUnit2025 = curriculumWriter.insertUnit(physics2025, "1", "Physics Unit", 1);
		Unit falseSource = new Unit(chemistryUnit.getId(), physics2019, "1", "False Physics Unit", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		assertThrows(IllegalArgumentException.class,
				() -> mappingWriter.insertMapping(falseSource, physicsUnit2025, MappingStatus.CONFIRMED));
		assertMappingCount(database, 0);
	}

	@Test
	void rejectsMappingWhenPersistentSourceDoesNotExist() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit target = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		Unit missingSource = new Unit(999999, syllabus2019, "3", "Missing Unit", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		assertThrows(IllegalArgumentException.class,
				() -> mappingWriter.insertMapping(missingSource, target, MappingStatus.CONFIRMED));
		assertMappingCount(database, 0);
	}

	@Test
	void rejectsMappingWhenPersistentTargetBelongsToDifferentSubject() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion chemistry2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit chemistryUnit = curriculumWriter.insertUnit(chemistry2025, "1", "Chemistry Unit", 1);
		Subject physics = curriculumWriter.insertSubject("Physics");
		SyllabusVersion physics2019 = curriculumWriter.insertSyllabusVersion(physics, "2019", false);
		SyllabusVersion physics2025 = curriculumWriter.insertSyllabusVersion(physics, "2025", true);
		Unit physicsUnit2019 = curriculumWriter.insertUnit(physics2019, "1", "Physics Unit 2019", 1);
		Unit falseTarget = new Unit(chemistryUnit.getId(), physics2025, "1", "False Physics Unit", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		assertThrows(IllegalArgumentException.class,
				() -> mappingWriter.insertMapping(physicsUnit2019, falseTarget, MappingStatus.CONFIRMED));
		assertMappingCount(database, 0);
	}

	@Test
	void rejectsStatusUpdateWhenMappingDoesNotMatchPersistedMapping() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit source = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit target = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		Unit differentTarget = curriculumWriter.insertUnit(syllabus2025, "5", "New Unit 5", 2);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		CurriculumMapping stored = mappingWriter.insertMapping(source, target, MappingStatus.SUGGESTED);
		CurriculumMapping falseMapping = new CurriculumMapping(stored.getId(), source, differentTarget,
				MappingStatus.SUGGESTED);
		assertThrows(IllegalArgumentException.class,
				() -> mappingWriter.updateStatus(falseMapping, MappingStatus.CONFIRMED));
		assertStoredMapping(database, stored, MappingStatus.SUGGESTED);
	}

	@Test
	void updatesMappingStatus() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus2019 = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit2019 = curriculumWriter.insertUnit(syllabus2019, "3", "Old Unit 3", 1);
		SyllabusVersion syllabus2025 = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit2025 = curriculumWriter.insertUnit(syllabus2025, "4", "New Unit 4", 1);
		SqliteCurriculumMappingWriter mappingWriter = new SqliteCurriculumMappingWriter(database);
		CurriculumMapping suggested = mappingWriter.insertMapping(unit2019, unit2025, MappingStatus.SUGGESTED);
		Unit anotherTarget = curriculumWriter.insertUnit(syllabus2025, "5", "Another target", 2);
		CurriculumMapping unaffected = mappingWriter.insertMapping(unit2019, anotherTarget, MappingStatus.SUGGESTED);
		SqliteCurriculumMappingWriter reopenedWriter = new SqliteCurriculumMappingWriter(
				new SqliteDatabase(tempDir.resolve("questionbank.db")));
		CurriculumMapping confirmed = reopenedWriter.updateStatus(suggested, MappingStatus.CONFIRMED);
		assertEquals(suggested.getId(), confirmed.getId());
		assertEquals(unit2019, confirmed.getSource());
		assertEquals(unit2025, confirmed.getTarget());
		assertEquals(MappingStatus.CONFIRMED, confirmed.getStatus());
		assertStoredMapping(database, unaffected, MappingStatus.SUGGESTED);
		assertEquals(confirmed.getId(), reopenedWriter.updateStatus(confirmed, MappingStatus.CONFIRMED).getId());
		assertMappingCount(database, 2);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT mapping_status
						FROM curriculum_mappings
						WHERE id = ?
						""")) {
			statement.setLong(1, confirmed.getId());
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				assertEquals("CONFIRMED", result.getString("mapping_status"));
			}
		}
	}
}
