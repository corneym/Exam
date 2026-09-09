package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;

class RevisionExportServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void removesStagingWhenProgressConsumerFailsBeforePublication() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Path destination = tempDir.resolve("interrupted-export");
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> fixture.service.export(new RevisionExportRequest(fixture.chemistry, destination),
						(message, completed, total) -> {
							if (message.equals("Publishing export...")) {
								throw new IllegalStateException("consumer failed");
							}
						}));
		assertEquals("consumer failed", failure.getMessage());
		assertFalse(Files.exists(destination));
		assertFalse(hasStagingDirectory(destination));
	}

	@Test
	void publishesValidatedExportOnlyAtFinalDestination() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Path destination = tempDir.resolve("chemistry-revision");
		RevisionExportResult result = fixture.service.export(new RevisionExportRequest(fixture.chemistry, destination));
		assertEquals(destination.toAbsolutePath().normalize(), result.getDestination());
		assertTrue(Files.isDirectory(destination));
		assertTrue(Files.isRegularFile(destination.resolve("index.html")));
		assertTrue(Files.isRegularFile(destination.resolve(Path.of("assets", "revision.css"))));
		assertEquals(0, result.getStatistics().getUniqueApplicableQuestions());
		assertFalse(hasStagingDirectory(destination));
	}

	@Test
	void rejectsExistingDestinationWithoutChangingIt() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Path destination = tempDir.resolve("existing");
		Files.createDirectories(destination);
		Path marker = destination.resolve("keep.txt");
		Files.writeString(marker, "existing content");
		assertThrows(IOException.class,
				() -> fixture.service.export(new RevisionExportRequest(fixture.chemistry, destination)));
		assertEquals("existing content", Files.readString(marker));
		assertFalse(hasStagingDirectory(destination));
	}

	private boolean hasStagingDirectory(Path destination) throws IOException {
		Path parent = destination.getParent();
		String prefix = destination.getFileName() + ".staging-";
		try (java.util.stream.Stream<Path> children = Files.list(parent)) {
			return children.anyMatch(path -> path.getFileName().toString().startsWith(prefix));
		}
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final RevisionExportService service;

		private Fixture(Path tempDir) throws IOException {
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion current = new SyllabusVersion(1, chemistry, "2025", true);
			Unit unit = new Unit(10, current, "1", "Unit 1", 1);
			Topic topic = new Topic(11, current, unit, "1.1", "Topic 1", 1);
			Descriptor descriptor = new Descriptor(12, current, topic, "1.1.1", "Descriptor 1", 1);
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(current), List.of(unit, topic, descriptor));
			CurriculumSearchNodeExpansionService expansion = new CurriculumSearchNodeExpansionService(repository);
			QuestionRetrievalService retrieval = new QuestionRetrievalService(currentNodes -> List.of(), expansion);
			RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(repository, retrieval);
			Path pdfRoot = tempDir.resolve("pdf");
			Files.createDirectories(pdfRoot);
			PdfStore pdfStore = new PdfStore(pdfRoot);
			QuestionExtractor extractor = new QuestionExtractor();
			service = new RevisionExportService(corpusBuilder, new RevisionQuestionAssetRenderer(pdfStore, extractor),
					new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		}
	}
}
