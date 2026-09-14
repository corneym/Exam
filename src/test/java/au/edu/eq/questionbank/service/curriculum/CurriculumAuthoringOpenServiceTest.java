package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumAuthoringOpenServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void listsAndOpensExistingPersistedSyllabuses() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("curriculum-open.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		writer.insertSyllabusVersion(chemistry, "2025", true);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion engineering2025 = writer.insertSyllabusVersion(engineering, "2025", true);
		var unit = writer.insertUnit(engineering2025, "1", "Engineering fundamentals", 0);
		var topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		var descriptor = writer.insertDescriptor(topic, "1.1.1", "Resolve forces", 0);
		CurriculumAuthoringOpenService service = new CurriculumAuthoringOpenService(
				new SqliteCurriculumRepository(database),
				new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database)));
		List<SyllabusVersion> versions = service.availableVersions();
		assertEquals(2, versions.size());
		assertEquals("Chemistry", versions.get(0).getSubject().getName());
		assertEquals("Engineering", versions.get(1).getSubject().getName());
		CurriculumAuthoringSession session = service.open(engineering2025);
		assertEquals(3, session.draft().nodes().size());
		CurriculumDraftNode draftDescriptor = session.draft().nodes().stream()
				.filter(node -> node.code().equals("1.1.1")).findFirst().orElseThrow();
		assertEquals(descriptor.getId(), session.persistentIdForDraftId(draftDescriptor.draftId()).orElseThrow());
	}
}
