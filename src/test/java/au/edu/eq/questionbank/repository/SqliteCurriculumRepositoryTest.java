package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteCurriculumRepositoryTest {

	@TempDir
	Path tempDir;

	@Test
	void findsAllSubjects() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		writer.insertSubject("Physics");
		writer.insertSubject("Chemistry");

		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);

		List<Subject> subjects = repository.findAllSubjects();

		assertEquals(2, subjects.size());
		assertEquals("Chemistry", subjects.get(0).getName());
		assertEquals("Physics", subjects.get(1).getName());
	}

	@Test
	void findsChildrenThroughCurriculumHierarchy() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = writer.insertUnit(syllabus2025, "4", "Unit four", 1);
		Topic topic = writer.insertTopic(unit, "4.2", "Organic chemistry", 1);
		Subtopic subtopic = writer.insertSubtopic(topic, "4.2.1", "Organic reactions", 1);
		writer.insertDescriptor(subtopic, "4.2.1.1", "Describe addition reactions", 1);
		writer.insertDescriptor(subtopic, "4.2.1.2", "Describe substitution reactions", 2);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		List<CurriculumNode> topics = repository.findChildren(unit);
		assertEquals(1, topics.size());
		assertTrue(topics.get(0) instanceof Topic);
		assertEquals("4.2", topics.get(0).getCode());
		Topic storedTopic = (Topic) topics.get(0);
		List<CurriculumNode> subtopics = repository.findChildren(storedTopic);
		assertEquals(1, subtopics.size());
		assertTrue(subtopics.get(0) instanceof Subtopic);
		Subtopic storedSubtopic = (Subtopic) subtopics.get(0);
		List<CurriculumNode> descriptors = repository.findChildren(storedSubtopic);
		assertEquals(2, descriptors.size());
		assertTrue(descriptors.get(0) instanceof Descriptor);
		assertTrue(descriptors.get(1) instanceof Descriptor);
		assertEquals("4.2.1.1", descriptors.get(0).getCode());
		assertEquals("4.2.1.2", descriptors.get(1).getCode());
	}

	@Test
	void findsCurriculumNodeByCode() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = writer.insertUnit(syllabus2025, "4", "Unit four", 1);
		Topic topic = writer.insertTopic(unit, "4.2", "Organic chemistry", 1);
		Subtopic subtopic = writer.insertSubtopic(topic, "4.2.1", "Organic reactions", 1);
		Descriptor descriptor = writer.insertDescriptor(subtopic, "4.2.1.3", "Explain reaction mechanisms", 1);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Optional<CurriculumNode> result = repository.findByCode(syllabus2025, "4.2.1.3");
		assertTrue(result.isPresent());
		assertEquals(descriptor, result.get());
		assertTrue(result.get() instanceof Descriptor);
		assertEquals("Explain reaction mechanisms", result.get().getName());
	}

	@Test
	void findsDescriptorDirectlyUnderTopic() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("psychology.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject psychology = writer.insertSubject("Psychology");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(psychology, "2025", true);
		Unit unit = writer.insertUnit(syllabus2025, "2", "Unit two", 1);
		Topic topic = writer.insertTopic(unit, "2.3", "Psychological disorders", 1);
		writer.insertDescriptor(topic, "2.3.5", "Describe approaches to diagnosis", 1);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		List<CurriculumNode> children = repository.findChildren(topic);
		assertEquals(1, children.size());
		assertTrue(children.get(0) instanceof Descriptor);
		assertEquals("2.3.5", children.get(0).getCode());
		assertEquals(topic, children.get(0).getParent());
	}

	@Test
	void findsRootNodesInDisplayOrder() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(chemistry, "2025", true);
		writer.insertUnit(syllabus2025, "2", "Unit two", 2);
		writer.insertUnit(syllabus2025, "1", "Unit one", 1);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		List<CurriculumNode> roots = repository.findRootNodes(syllabus2025);
		assertEquals(2, roots.size());
		assertTrue(roots.get(0) instanceof Unit);
		assertTrue(roots.get(1) instanceof Unit);
		assertEquals("1", roots.get(0).getCode());
		assertEquals("2", roots.get(1).getCode());
	}

	@Test
	void findsSubjectById() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Optional<Subject> result = repository.findSubjectById(chemistry.getId());

		assertTrue(result.isPresent());
		assertEquals(chemistry, result.get());
	}

	@Test
	void findsVersionById() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(chemistry, "2025", true);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Optional<SyllabusVersion> result = repository.findVersionById(syllabus2025.getId());
		assertTrue(result.isPresent());
		assertEquals(syllabus2025, result.get());
		assertEquals("Chemistry", result.get().getSubject().getName());
		assertEquals("2025", result.get().getName());
		assertTrue(result.get().isCurrent());
	}

	@Test
	void findsVersionsForSubject() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		writer.insertSyllabusVersion(chemistry, "2019", false);
		writer.insertSyllabusVersion(chemistry, "2025", true);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		List<SyllabusVersion> versions = repository.findVersionsForSubject(chemistry);
		assertEquals(2, versions.size());
		assertEquals("2019", versions.get(0).getName());
		assertEquals("2025", versions.get(1).getName());
		assertEquals(false, versions.get(0).isCurrent());
		assertEquals(true, versions.get(1).isCurrent());
	}

	@Test
	void returnsEmptyWhenCurriculumCodeDoesNotExist() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus2025 = writer.insertSyllabusVersion(chemistry, "2025", true);
		writer.insertUnit(syllabus2025, "1", "Unit one", 1);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Optional<CurriculumNode> result = repository.findByCode(syllabus2025, "9.9.9");
		assertTrue(result.isEmpty());
	}
}