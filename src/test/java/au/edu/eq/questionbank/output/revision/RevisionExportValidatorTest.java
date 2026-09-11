package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;

class RevisionExportValidatorTest {

	@TempDir
	Path tempDir;

	@Test
	void acceptsCompleteStaticExport() throws Exception {
		Fixture fixture = new Fixture();
		Path root = tempDir.resolve("export");
		Path questions = root.resolve(Path.of("assets", "questions"));
		Files.createDirectories(questions);
		Path questionFile = questions.resolve("question-1.png");
		Files.writeString(questionFile, "png");
		Path index = root.resolve("index.html");
		Files.writeString(index, """
				<!DOCTYPE html>
				<html>
				<body>
				    <img src="assets/questions/question-1.png">
				</body>
				</html>
				""");
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.question,
				Path.of("assets", "questions", "question-1.png"));
		RevisionExportValidator validator = new RevisionExportValidator();
		validator.validate(root, fixture.corpus, List.of(index), List.of(questionAsset), List.of(), List.of());
	}

	@Test
	void rejectsAbsoluteReference() throws Exception {
		Fixture fixture = new Fixture();
		Path root = tempDir.resolve("export");
		Files.createDirectories(root);
		Path index = root.resolve("index.html");
		Files.writeString(index, """
				<!DOCTYPE html>
				<html>
				<body>
				    <img src="file:///C:/data/question.png">
				</body>
				</html>
				""");
		assertThrows(IOException.class, () -> new RevisionExportValidator().validate(root, fixture.emptyCorpus,
				List.of(index), List.of(), List.of(), List.of()));
	}

	@Test
	void rejectsMissingExpectedQuestionAsset() throws Exception {
		Fixture fixture = new Fixture();
		Path root = tempDir.resolve("export");
		Files.createDirectories(root);
		Path index = root.resolve("index.html");
		Files.writeString(index, "<html><body></body></html>");
		assertThrows(IOException.class, () -> new RevisionExportValidator().validate(root, fixture.corpus,
				List.of(index), List.of(), List.of(), List.of()));
	}

	@Test
	void rejectsMissingReferencedFile() throws Exception {
		Fixture fixture = new Fixture();
		Path root = tempDir.resolve("export");
		Files.createDirectories(root);
		Path index = root.resolve("index.html");
		Files.writeString(index, """
				<!DOCTYPE html>
				<html>
				<body>
				    <img src="assets/questions/missing.png">
				</body>
				</html>
				""");
		assertThrows(IOException.class, () -> new RevisionExportValidator().validate(root, fixture.emptyCorpus,
				List.of(index), List.of(), List.of(), List.of()));
	}

	@Test
	void rejectsReferenceEscapingExportRoot() throws Exception {
		Fixture fixture = new Fixture();
		Path root = tempDir.resolve("export");
		Files.createDirectories(root);
		Path outside = tempDir.resolve("outside.css");
		Files.writeString(outside, "body {}");
		Path index = root.resolve("index.html");
		Files.writeString(index, """
				<!DOCTYPE html>
				<html>
				<head>
				    <link rel="stylesheet" href="../outside.css">
				</head>
				<body></body>
				</html>
				""");
		assertThrows(IOException.class, () -> new RevisionExportValidator().validate(root, fixture.emptyCorpus,
				List.of(index), List.of(), List.of(), List.of()));
	}

	private static final class Fixture {

		private final Question question;
		private final RevisionCorpus corpus;
		private final RevisionCorpus emptyCorpus;

		private Fixture() {
			Subject chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historical = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historical, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historical, historicalUnit, "1.1", "Historical topic", 1);
			Descriptor historicalDescriptor = new Descriptor(102, historical, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
			SyllabusVersion current = new SyllabusVersion(2, chemistry, "2025", true);
			Unit currentUnit = new Unit(10, current, "1", "Unit 1", 1);
			Topic currentTopic = new Topic(11, current, currentUnit, "1.1", "Topic 1", 1);
			Descriptor currentDescriptor = new Descriptor(12, current, currentTopic, "1.1.1", "Descriptor 1", 1);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
			SourceDocument source = new SourceDocument(1, "question.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", source);
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
			question = new Question(1, booklet, "1", "", 1, List.of(region), historicalDescriptor, false);
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(historical, current), List.of(historicalUnit, historicalTopic, historicalDescriptor,
							currentUnit, currentTopic, currentDescriptor));
			CurriculumSearchNodeExpansionService expansion = new CurriculumSearchNodeExpansionService(repository);
			QuestionRetrievalService withQuestion = new QuestionRetrievalService(
					_ -> List.of(new QuestionApplicabilityMatch(question, currentDescriptor)), expansion);
			QuestionRetrievalService empty = new QuestionRetrievalService(_ -> List.of(), expansion);
			corpus = new RevisionCorpusBuilder(repository, withQuestion).build(chemistry);
			emptyCorpus = new RevisionCorpusBuilder(repository, empty).build(chemistry);
		}
	}
}
