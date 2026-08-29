package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;

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
}