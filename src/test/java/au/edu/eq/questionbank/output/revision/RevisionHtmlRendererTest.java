package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;

class RevisionHtmlRendererTest {

	@TempDir
	Path tempDir;

	@Test
	void rejectsMissingAnswerAssetForPersistedAnswerRegion() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(currentNodes -> List
				.of(new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.answeredQuestion,
				Path.of("assets", "questions", "question-1.png"));
		RevisionHtmlRenderer renderer = new RevisionHtmlRenderer(List.of(questionAsset), List.of());
		assertThrows(IllegalStateException.class, () -> renderer.renderTopicPages(corpus, tempDir.resolve("output")));
	}

	@Test
	void rejectsMissingQuestionAssetForRenderablePlacement() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(currentNodes -> List
				.of(new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor)));
		RevisionHtmlRenderer renderer = new RevisionHtmlRenderer(List.of(), List.of());
		assertThrows(IllegalStateException.class, () -> renderer.renderTopicPages(corpus, tempDir.resolve("output")));
	}

	@Test
	void rendersSubjectUnitAndTopicNavigation() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(currentNodes -> List
				.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.directDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = new RevisionHtmlRenderer(List.of(questionAsset), List.of());
		Path outputRoot = tempDir.resolve("output");
		List<Path> htmlFiles = renderer.render(corpus, outputRoot);
		Path subjectIndex = outputRoot.resolve("index.html");
		Path unitIndex = outputRoot.resolve(Path.of("units", "unit-10", "index.html"));
		Path descriptorTopic = outputRoot.resolve(Path.of("units", "unit-10", "topic-11.html"));
		assertTrue(Files.isRegularFile(subjectIndex));
		assertTrue(Files.isRegularFile(unitIndex));
		assertTrue(Files.isRegularFile(descriptorTopic));
		assertTrue(htmlFiles.contains(subjectIndex.toAbsolutePath().normalize()));
		assertTrue(htmlFiles.contains(unitIndex.toAbsolutePath().normalize()));
		assertTrue(htmlFiles.contains(descriptorTopic.toAbsolutePath().normalize()));
		String subjectHtml = Files.readString(subjectIndex);
		assertTrue(subjectHtml.contains("href=\"units/unit-10/index.html\""));
		assertTrue(subjectHtml.contains("1 revision question"));
		assertTrue(subjectHtml.contains("Applicable questions"));
		assertTrue(subjectHtml.contains("Exportable questions"));
		String unitHtml = Files.readString(unitIndex);
		assertTrue(unitHtml.contains("href=\"../../index.html\""));
		assertTrue(unitHtml.contains("href=\"topic-11.html\""));
		String topicHtml = Files.readString(descriptorTopic);
		assertTrue(topicHtml.contains("href=\"../../index.html\""));
		assertTrue(topicHtml.contains("href=\"index.html\""));
	}

	@Test
	void rendersSubtopicBeforeItsDescriptorSections() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(currentNodes -> List.of());
		RevisionHtmlRenderer renderer = new RevisionHtmlRenderer(List.of(), List.of());
		Path outputRoot = tempDir.resolve("output");
		renderer.renderTopicPages(corpus, outputRoot);
		Path topicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20.html"));
		String html = Files.readString(topicFile);
		int subtopicPosition = html.indexOf("2.1.1 Subtopic classification");
		int descriptorPosition = html.indexOf("2.1.1.1 Nested descriptor");
		assertTrue(subtopicPosition >= 0);
		assertTrue(descriptorPosition > subtopicPosition);
	}

	@Test
	void rendersTopicQuestionCardsAnswersAndSourceAttribution() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(currentNodes -> List.of(
				new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.incompleteQuestion, fixture.directDescriptor)));
		RevisionQuestionAsset answeredAsset = new RevisionQuestionAsset(fixture.answeredQuestion,
				Path.of("assets", "questions", "question-1.png"));
		RevisionQuestionAsset noAnswerAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionAnswerAsset answerAsset = new RevisionAnswerAsset(fixture.answeredQuestion, fixture.answerRegion, 1,
				Path.of("assets", "answers", "question-1-answer-01.png"));
		RevisionHtmlRenderer renderer = new RevisionHtmlRenderer(List.of(answeredAsset, noAnswerAsset),
				List.of(answerAsset));
		Path outputRoot = tempDir.resolve("output");
		renderer.renderTopicPages(corpus, outputRoot);
		Path topicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-11.html"));
		assertTrue(Files.isRegularFile(topicFile));
		String html = Files.readString(topicFile);
		assertTrue(html.contains("href=\"../../assets/revision.css\""));
		assertTrue(html.contains("src=\"../../assets/questions/question-1.png\""));
		assertTrue(html.contains("src=\"../../assets/answers/question-1-answer-01.png\""));
		assertTrue(html.contains("<summary>Reveal answer</summary>"));
		assertTrue(html.contains("A &lt; B &amp; C"));
		assertTrue(html.contains("Source: QCAA, 2022, Chemistry examination, Paper 1, Question 7"));
		assertTrue(html.contains("2 marks"));
		assertTrue(html.contains("1 mark"));
		assertTrue(html.contains("Answer not yet available."));
		assertTrue(html.contains("Original classification: 2019"));
		assertFalse(html.contains("id=\"question-3\""));
		assertFalse(html.contains("Question 9"));
		assertTrue(Files.isRegularFile(outputRoot.resolve(Path.of("assets", "revision.css"))));
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final Descriptor directDescriptor;
		private final AnswerRegion answerRegion;
		private final Question answeredQuestion;
		private final Question noAnswerQuestion;
		private final Question incompleteQuestion;
		private final InMemoryCurriculumRepository curriculumRepository;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			Descriptor historicalDescriptor = new Descriptor(102, historicalVersion, historicalTopic, "1.1.1",
					"Historical <descriptor>", 1);
			SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			Unit currentUnit = new Unit(10, currentVersion, "1", "Unit 1", 1);
			Topic descriptorTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Descriptor topic", 1);
			directDescriptor = new Descriptor(12, currentVersion, descriptorTopic, "1.1.1", "Direct descriptor", 1);
			Topic subtopicTopic = new Topic(20, currentVersion, currentUnit, "2.1", "Subtopic mode", 2);
			Subtopic subtopic = new Subtopic(21, currentVersion, subtopicTopic, "2.1.1", "Subtopic classification", 1);
			Descriptor nestedDescriptor = new Descriptor(22, currentVersion, subtopic, "2.1.1.1", "Nested descriptor",
					1);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
			SourceDocument questionSource = new SourceDocument(1, "question.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", questionSource);
			QuestionRegion questionRegion = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
			answeredQuestion = new Question(1, booklet, "7", "", 2, List.of(questionRegion), historicalDescriptor,
					false);
			noAnswerQuestion = new Question(2, booklet, "8", "", 1, List.of(questionRegion), historicalDescriptor,
					false);
			incompleteQuestion = new Question(3, booklet, "9", "", 3, List.of(), historicalDescriptor, false);
			SourceDocument answerSource = new SourceDocument(2, "answers.pdf");
			AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", answerSource);
			answerRegion = new AnswerRegion(answerFile, 1, 0.0, 0.0, 1.0, 1.0);
			answeredQuestion.setAnswer(new Answer(1, "A < B & C", List.of(answerRegion)));
			curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(historicalVersion, currentVersion),
					List.of(historicalUnit, historicalTopic, historicalDescriptor, currentUnit, descriptorTopic,
							directDescriptor, subtopicTopic, subtopic, nestedDescriptor));
		}

		private RevisionCorpus createCorpus(QuestionRetrievalRepository retrievalRepository) {
			CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
					curriculumRepository);
			QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
					expansionService);
			RevisionCorpusBuilder builder = new RevisionCorpusBuilder(curriculumRepository, retrievalService);
			return builder.build(chemistry);
		}
	}
}
