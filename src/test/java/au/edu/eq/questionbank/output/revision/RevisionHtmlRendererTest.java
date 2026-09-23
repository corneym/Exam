package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
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
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

class RevisionHtmlRendererTest {

	@TempDir
	Path tempDir;

	@Test
	void descriptorModeRendersDescriptorHeadingInsideSubtopicPage() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.nestedDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, RevisionGroupingMode.DESCRIPTOR,
				List.of(questionAsset), List.of());
		Path outputRoot = tempDir.resolve("descriptor-grouping-output");
		renderer.render(corpus, outputRoot);
		Path subtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		String html = Files.readString(subtopicFile);

		// Descriptor grouping keeps the Descriptor as a visible student-facing section.
		assertTrue(html.contains("2.1.1.1 Nested descriptor"));
		assertTrue(html.contains("question-2.png"));
	}

	@Test
	void displayedQuestionNumberingRestartsOnEachQuestionPage() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor),
						new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.nestedDescriptor)));
		RevisionQuestionAsset topicAsset = new RevisionQuestionAsset(fixture.answeredQuestion,
				Path.of("assets", "questions", "question-1.png"));
		RevisionQuestionAsset subtopicAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionAnswerAsset answerAsset = new RevisionAnswerAsset(fixture.answeredQuestion, fixture.answerRegion, 1,
				Path.of("assets", "answers", "question-1-answer-01.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, RevisionGroupingMode.SUBTOPIC,
				List.of(topicAsset, subtopicAsset), List.of(answerAsset));
		Path outputRoot = tempDir.resolve("page-numbering-output");
		renderer.render(corpus, outputRoot);
		Path topicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-11.html"));
		Path subtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		String topicHtml = Files.readString(topicFile);
		String subtopicHtml = Files.readString(subtopicFile);
		assertTrue(topicHtml.contains("id=\"question-1\""));
		assertFalse(topicHtml.contains("id=\"question-2\""));
		assertTrue(subtopicHtml.contains("id=\"question-1\""));
		assertFalse(subtopicHtml.contains("id=\"question-2\""));
	}

	@Test
	void omitsDescriptorSectionWhenItHasNoRenderableQuestions() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture
				.createCorpus(_ -> List.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.subtopic)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(questionAsset), List.of());
		Path outputRoot = tempDir.resolve("output");
		renderer.render(corpus, outputRoot);
		Path subtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		assertTrue(Files.isRegularFile(subtopicFile));
		String html = Files.readString(subtopicFile);
		assertTrue(html.contains("2.1.1 Subtopic classification"));
		assertTrue(html.contains("question-2.png"));
		assertFalse(html.contains("<h3>2.1.1.1 Nested descriptor</h3>"));
		assertFalse(html.contains("No revision questions available yet."));
	}

	@Test
	void omitsEmptyCurriculumBranchesFromNavigationAndGeneratedFiles() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.nestedDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, RevisionGroupingMode.SUBTOPIC,
				List.of(questionAsset), List.of());
		Path outputRoot = tempDir.resolve("pruned-output");
		List<Path> htmlFiles = renderer.render(corpus, outputRoot);
		Path populatedUnitFile = outputRoot.resolve(Path.of("units", "unit-10", "index.html"));
		Path populatedTopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20.html"));
		Path populatedSubtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		Path emptySubtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-23.html"));
		Path emptyTopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-30.html"));
		Path emptyUnitFile = outputRoot.resolve(Path.of("units", "unit-40", "index.html"));
		Path emptyUnitTopicFile = outputRoot.resolve(Path.of("units", "unit-40", "topic-41.html"));
		assertTrue(Files.isRegularFile(populatedUnitFile));
		assertTrue(Files.isRegularFile(populatedTopicFile));
		assertTrue(Files.isRegularFile(populatedSubtopicFile));
		assertFalse(Files.exists(emptySubtopicFile));
		assertFalse(Files.exists(emptyTopicFile));
		assertFalse(Files.exists(emptyUnitFile));
		assertFalse(Files.exists(emptyUnitTopicFile));
		assertFalse(htmlFiles.contains(emptySubtopicFile.toAbsolutePath().normalize()));
		assertFalse(htmlFiles.contains(emptyTopicFile.toAbsolutePath().normalize()));
		assertFalse(htmlFiles.contains(emptyUnitFile.toAbsolutePath().normalize()));
		String subjectHtml = Files.readString(outputRoot.resolve("index.html"));
		assertTrue(subjectHtml.contains("1 Unit 1"));
		assertFalse(subjectHtml.contains("4 Empty unit"));
		assertFalse(subjectHtml.contains("unit-40"));
		String unitHtml = Files.readString(populatedUnitFile);
		assertTrue(unitHtml.contains("2.1 Subtopic mode"));
		assertFalse(unitHtml.contains("3.1 Empty topic"));
		assertFalse(unitHtml.contains("topic-30.html"));
		String topicHtml = Files.readString(populatedTopicFile);
		assertTrue(topicHtml.contains("2.1.1 Subtopic classification"));
		assertFalse(topicHtml.contains("2.1.2 Empty subtopic"));
		assertFalse(topicHtml.contains("subtopic-23.html"));
	}

	@Test
	void rejectsMissingAnswerAssetForPersistedAnswerRegion() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.answeredQuestion,
				Path.of("assets", "questions", "question-1.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(questionAsset), List.of());
		assertThrows(IllegalStateException.class, () -> renderer.renderTopicPages(corpus, tempDir.resolve("output")));
	}

	@Test
	void rejectsMissingQuestionAssetForRenderablePlacement() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor)));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(), List.of());
		assertThrows(IllegalStateException.class, () -> renderer.renderTopicPages(corpus, tempDir.resolve("output")));
	}

	@Test
	void rendersMcqBeforeWrittenResponseWithSelectableSections() throws Exception {
		Fixture fixture = new Fixture();
		QuestionRegion region = new QuestionRegion(fixture.booklet, 1, 0.0, 0.0, 1.0, 1.0);
		Question written = new Question(40, fixture.booklet, "1", "", 2, List.of(region), fixture.historicalDescriptor,
				false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
		Question multipleChoice = new Question(41, fixture.booklet, "9", "", 1, List.of(region),
				fixture.historicalDescriptor, false, null, null, QuestionResponseType.MULTIPLE_CHOICE);
		RevisionCorpus corpus = fixture
				.createCorpus(_ -> List.of(new QuestionApplicabilityMatch(written, fixture.nestedDescriptor),
						new QuestionApplicabilityMatch(multipleChoice, fixture.nestedDescriptor)));
		RevisionQuestionAsset writtenAsset = new RevisionQuestionAsset(written,
				Path.of("assets", "questions", "question-40.png"));
		RevisionQuestionAsset mcqAsset = new RevisionQuestionAsset(multipleChoice,
				Path.of("assets", "questions", "question-41.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, RevisionGroupingMode.SUBTOPIC,
				List.of(writtenAsset, mcqAsset), List.of());
		Path outputRoot = tempDir.resolve("response-order-output");
		renderer.render(corpus, outputRoot);
		Path subtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		String html = Files.readString(subtopicFile);
		int mcqHeading = html.indexOf("id=\"multiple-choice\"");
		int mcqQuestion = html.indexOf("question-41.png");
		int writtenHeading = html.indexOf("id=\"written-response\"");
		int writtenQuestion = html.indexOf("question-40.png");
		assertTrue(mcqHeading >= 0);
		assertTrue(mcqQuestion > mcqHeading);
		assertTrue(writtenHeading > mcqQuestion);
		assertTrue(writtenQuestion > writtenHeading);
		assertTrue(html.contains("href=\"#multiple-choice\""));
		assertTrue(html.contains("href=\"#written-response\""));

		// The MCQ is displayed first even though its original source code is later.
		assertTrue(html.contains("id=\"question-1\""));
		assertTrue(html.contains("id=\"question-2\""));
	}

	@Test
	void rendersMultipartPresentationWithSharedContextOnceAndCombinedMarks() throws Exception {
		Fixture fixture = new Fixture();
		SourceQuestion sourceQuestion = new SourceQuestion(24, fixture.booklet, "24", SharedContextStatus.PRESENT);
		SharedQuestionContext sharedContext = new SharedQuestionContext(50, fixture.booklet,
				"Question 24 shared context", List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.25)));
		Question partA = new Question(4, fixture.booklet, "24a", "", 2,
				List.of(new QuestionRegion(fixture.booklet, 1, 0.0, 0.25, 1.0, 0.25)), fixture.historicalDescriptor,
				false, sourceQuestion, sharedContext);
		Question partB = new Question(5, fixture.booklet, "24b", "", 3,
				List.of(new QuestionRegion(fixture.booklet, 1, 0.0, 0.50, 1.0, 0.25)), fixture.historicalDescriptor,
				false, sourceQuestion, sharedContext);
		partA.setAnswer(new Answer(2, "Answer A", List.of()));
		partB.setAnswer(new Answer(3, "Answer B", List.of()));
		RevisionCorpus corpus = fixture
				.createCorpus(_ -> List.of(new QuestionApplicabilityMatch(partA, fixture.directDescriptor),
						new QuestionApplicabilityMatch(partB, fixture.directDescriptor)));
		RevisionQuestionAsset partAAsset = new RevisionQuestionAsset(partA,
				Path.of("assets", "questions", "question-4.png"));
		RevisionQuestionAsset partBAsset = new RevisionQuestionAsset(partB,
				Path.of("assets", "questions", "question-5.png"));
		RevisionSharedContextAsset contextAsset = new RevisionSharedContextAsset(sharedContext,
				Path.of("assets", "contexts", "context-50.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(partAAsset, partBAsset), List.of(),
				List.of(contextAsset));
		Path outputRoot = tempDir.resolve("multipart-output");
		renderer.renderTopicPages(corpus, outputRoot);
		Path topicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-11.html"));
		String html = Files.readString(topicFile);
		assertEquals(1, countOccurrences(html, "context-50.png"));
		assertEquals(1, countOccurrences(html, "<summary>Reveal answer</summary>"));
		assertTrue(html.contains("Question 1"));
		assertTrue(html.contains("5 marks"));
		assertFalse(html.contains("Source part 24a"));
		assertFalse(html.contains("Source part 24b"));
		assertTrue(html.contains("Questions 24a, 24b"));
		assertTrue(html.contains("question-4.png"));
		assertTrue(html.contains("question-5.png"));
		assertTrue(html.contains("Answer A"));
		assertTrue(html.contains("Answer B"));
	}

	@Test
	void rendersSubjectUnitAndTopicNavigation() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.directDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(questionAsset), List.of());
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
		assertTrue(subjectHtml.contains("Revision question"));
		assertFalse(subjectHtml.contains("Applicable questions"));
		assertFalse(subjectHtml.contains("Exportable questions"));
		assertFalse(subjectHtml.contains("Awaiting question capture"));
		assertFalse(subjectHtml.contains("Revision corpus status"));
		String unitHtml = Files.readString(unitIndex);
		assertTrue(unitHtml.contains("href=\"../../index.html\""));
		assertTrue(unitHtml.contains("href=\"topic-11.html\""));
		String topicHtml = Files.readString(descriptorTopic);
		assertTrue(topicHtml.contains("href=\"../../index.html\""));
		assertTrue(topicHtml.contains("href=\"index.html\""));
	}

	@Test
	void rendersSubtopicAsSelectablePage() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture
				.createCorpus(_ -> List.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.subtopic)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(questionAsset), List.of());
		Path outputRoot = tempDir.resolve("output");
		List<Path> htmlFiles = renderer.render(corpus, outputRoot);
		Path topicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20.html"));
		Path subtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		assertTrue(Files.isRegularFile(topicFile));
		assertTrue(Files.isRegularFile(subtopicFile));
		assertTrue(htmlFiles.contains(subtopicFile.toAbsolutePath().normalize()));
		String topicHtml = Files.readString(topicFile);
		assertTrue(topicHtml.contains("href=\"topic-20/subtopic-21.html\""));
		assertTrue(topicHtml.contains("2.1.1 Subtopic classification"));
		assertTrue(topicHtml.contains("1 revision question"));
		assertFalse(topicHtml.contains("question-2.png"));
		String subtopicHtml = Files.readString(subtopicFile);
		assertTrue(subtopicHtml.contains("2.1.1 Subtopic classification"));
		assertTrue(subtopicHtml.contains("src=\"../../../assets/questions/question-2.png\""));
		assertTrue(subtopicHtml.contains("href=\"../topic-20.html\""));
		assertFalse(subtopicHtml.contains("2.1.1.1 Nested descriptor"));
	}

	@Test
	void rendersTopicQuestionCardsAnswersAndSourceAttribution() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.answeredQuestion, fixture.directDescriptor),
						new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.directDescriptor),
						new QuestionApplicabilityMatch(fixture.incompleteQuestion, fixture.directDescriptor)));
		RevisionQuestionAsset answeredAsset = new RevisionQuestionAsset(fixture.answeredQuestion,
				Path.of("assets", "questions", "question-1.png"));
		RevisionQuestionAsset noAnswerAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionAnswerAsset answerAsset = new RevisionAnswerAsset(fixture.answeredQuestion, fixture.answerRegion, 1,
				Path.of("assets", "answers", "question-1-answer-01.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, List.of(answeredAsset, noAnswerAsset),
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
		assertFalse(html.contains("Original classification"));
		assertTrue(html.contains("Current descriptor: 1.1.1 Direct descriptor"));
		assertFalse(html.contains("id=\"question-3\""));
		assertFalse(html.contains("Question 9"));
		assertTrue(Files.isRegularFile(outputRoot.resolve(Path.of("assets", "revision.css"))));
	}

	@Test
	void subtopicModeSuppressesDescriptorHeadingAndRendersQuestionDirectly() throws Exception {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createCorpus(
				_ -> List.of(new QuestionApplicabilityMatch(fixture.noAnswerQuestion, fixture.nestedDescriptor)));
		RevisionQuestionAsset questionAsset = new RevisionQuestionAsset(fixture.noAnswerQuestion,
				Path.of("assets", "questions", "question-2.png"));
		RevisionHtmlRenderer renderer = fixture.createRenderer(corpus, RevisionGroupingMode.SUBTOPIC,
				List.of(questionAsset), List.of());
		Path outputRoot = tempDir.resolve("subtopic-grouping-output");
		renderer.render(corpus, outputRoot);
		Path subtopicFile = outputRoot.resolve(Path.of("units", "unit-10", "topic-20", "subtopic-21.html"));
		String html = Files.readString(subtopicFile);

		// Subtopic grouping rolls Descriptor Questions into the parent page.
		assertTrue(html.contains("2.1.1 Subtopic classification"));
		assertTrue(html.contains("question-2.png"));

		// Subtopic grouping suppresses Descriptor sections, while still allowing the
		// current Descriptor to appear as Question metadata.
		assertFalse(html.contains("<h3>2.1.1.1 Nested descriptor</h3>"));
		assertTrue(html.contains("Current descriptor: 2.1.1.1 Nested descriptor"));
	}

	private int countOccurrences(String text, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = text.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final Descriptor directDescriptor;
		private final AnswerRegion answerRegion;
		private final Question answeredQuestion;
		private final Question noAnswerQuestion;
		private final Question incompleteQuestion;
		private final InMemoryCurriculumRepository curriculumRepository;
		private final Subtopic subtopic;
		private final Descriptor nestedDescriptor;
		private final ExamBooklet booklet;
		private final Descriptor historicalDescriptor;
		private final Unit emptyUnit;
		private final Topic emptyTopic;
		private final Topic emptyTopicInPopulatedUnit;
		private final Subtopic emptySubtopic;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(102, historicalVersion, historicalTopic, "1.1.1",
					"Historical <descriptor>", 1);
			SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			Unit currentUnit = new Unit(10, currentVersion, "1", "Unit 1", 1);
			Topic descriptorTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Descriptor topic", 1);
			directDescriptor = new Descriptor(12, currentVersion, descriptorTopic, "1.1.1", "Direct descriptor", 1);
			Topic subtopicTopic = new Topic(20, currentVersion, currentUnit, "2.1", "Subtopic mode", 2);
			subtopic = new Subtopic(21, currentVersion, subtopicTopic, "2.1.1", "Subtopic classification", 1);
			nestedDescriptor = new Descriptor(22, currentVersion, subtopic, "2.1.1.1", "Nested descriptor", 1);
			emptySubtopic = new Subtopic(23, currentVersion, subtopicTopic, "2.1.2", "Empty subtopic", 2);
			Descriptor emptySubtopicDescriptor = new Descriptor(24, currentVersion, emptySubtopic, "2.1.2.1",
					"Empty subtopic descriptor", 1);
			emptyTopicInPopulatedUnit = new Topic(30, currentVersion, currentUnit, "3.1", "Empty topic", 3);
			Descriptor emptyTopicDescriptor = new Descriptor(31, currentVersion, emptyTopicInPopulatedUnit, "3.1.1",
					"Empty topic descriptor", 1);
			emptyUnit = new Unit(40, currentVersion, "4", "Empty unit", 2);
			emptyTopic = new Topic(41, currentVersion, emptyUnit, "4.1", "Empty unit topic", 1);
			Descriptor emptyUnitDescriptor = new Descriptor(42, currentVersion, emptyTopic, "4.1.1",
					"Empty unit descriptor", 1);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
			SourceDocument questionSource = new SourceDocument(1, "question.pdf");
			booklet = new ExamBooklet(1, exam, "Paper 1", questionSource);
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
							directDescriptor, subtopicTopic, subtopic, nestedDescriptor, emptySubtopic,
							emptySubtopicDescriptor, emptyTopicInPopulatedUnit, emptyTopicDescriptor, emptyUnit,
							emptyTopic, emptyUnitDescriptor));
		}

		private RevisionCorpus createCorpus(QuestionRetrievalRepository retrievalRepository) {
			CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
					curriculumRepository);
			QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
					expansionService);
			RevisionCorpusBuilder builder = new RevisionCorpusBuilder(curriculumRepository, retrievalService);
			return builder.build(chemistry);
		}

		private RevisionHtmlRenderer createRenderer(RevisionCorpus corpus, List<RevisionQuestionAsset> questionAssets,
				List<RevisionAnswerAsset> answerAssets) {
			return createRenderer(corpus, questionAssets, answerAssets, List.of());
		}

		private RevisionHtmlRenderer createRenderer(RevisionCorpus corpus, List<RevisionQuestionAsset> questionAssets,
				List<RevisionAnswerAsset> answerAssets, List<RevisionSharedContextAsset> sharedContextAssets) {
			return new RevisionHtmlRenderer(new RevisionPresentationPlanner().plan(corpus), questionAssets,
					answerAssets, sharedContextAssets);
		}

		private RevisionHtmlRenderer createRenderer(RevisionCorpus corpus, RevisionGroupingMode groupingMode,
				List<RevisionQuestionAsset> questionAssets, List<RevisionAnswerAsset> answerAssets) {
			RevisionPresentationPlanner planner = new RevisionPresentationPlanner();
			return new RevisionHtmlRenderer(planner.plan(corpus, groupingMode), questionAssets, answerAssets,
					List.of());
		}
	}
}
